"""Explicit L2 candidate APK check; outside test_*.py discovery.

Only the validation helpers are used by manifest unit tests. Those tests do not
run the real-candidate target or prove APK validity.
"""
import hashlib
import json
import os
import re
import unittest
from pathlib import Path

import verify_s5_benchmark_artifacts as packaging


SOURCE_TREES = ("app/src", "macrobenchmark/src")
SOURCE_FILES = (
    "tools/performance/verify_s5_benchmark_artifacts.py",
    "tools/performance/s5_artifacts.py",
    "tools/performance/benchmark_gate.py",
    "tools/performance/evidence-manifest.schema.json",
    "app/build.gradle.kts", "app/proguard-rules.pro",
    "build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml",
    ".github/workflows/android.yml", "build/reports/s5/compile-classpath.txt",
    "build/reports/s5/compile-classpath-omitted.txt",
)
APK_ARGUMENTS = {
    "s5_target_apk": "app/build/outputs/apk/s5Benchmark/app-s5Benchmark.apk",
    "s5_test_apk": "macrobenchmark/build/outputs/apk/s5Benchmark/macrobenchmark-s5Benchmark.apk",
    "debug_apk": "app/build/outputs/apk/debug/app-debug.apk",
    "ci_minified_apk": "app/build/outputs/apk/ciMinified/app-ciMinified.apk",
    "benchmark_apk": "app/build/outputs/apk/benchmark/app-benchmark.apk",
}
SOURCE_ARGUMENTS = (
    "ci_minified_mapping", "firebase_fixture", "s5_dependency_report",
    "ci_minified_dependency_report", "ci_minified_source_report",
    "ci_minified_source_files", "benchmark_source", "s5_source_report",
    "s5_r8_configuration",
)


def require(condition, message):
    if not condition:
        raise AssertionError("precondition(D17-pkg): " + message)


def canonical_path(value):
    require(isinstance(value, str) and bool(value), "path must be a nonempty string")
    path = Path(value)
    require(path.is_absolute(), "absolute paths required: " + value)
    require(str(path.resolve(strict=True)) == value, "canonical path required: " + value)
    return path


def tree_files(root, *, allow_empty=False):
    require(root.is_dir() and not root.is_symlink(), "source/classpath directory required: " + str(root))
    found = set()
    def scan_error(error):
        raise error
    for directory, dirs, files in os.walk(root, followlinks=False, onerror=scan_error):
        for name in dirs + files:
            path = Path(directory) / name
            require(not path.is_symlink(), "symlink source/classpath input: " + str(path))
            if name in files:
                path = canonical_path(str(path))
                require(path.is_file(), "regular source/classpath file required: " + str(path))
                found.add(str(path))
    require(found or allow_empty, "empty source/classpath tree: " + str(root))
    return found


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key: " + key)
        result[key] = value
    return result


def validate_candidate_inputs(inputs, repo_root):
    """Preconditions only. Never call main or replace its verifier/tool transport."""
    try:
        require(isinstance(inputs, dict) and set(inputs) == {"sourceHashes", "apkHashes", "argv"},
                "manifest requires exactly sourceHashes/apkHashes/argv")
        maps = []
        for field in ("sourceHashes", "apkHashes"):
            bindings = inputs[field]
            require(isinstance(bindings, dict) and bool(bindings), field + " must be a nonempty map")
            for filename, digest in bindings.items():
                path = canonical_path(filename)
                require(path.is_file(), "binding must name a regular file: " + filename)
                require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
                        "lowercase SHA-256 required: " + filename)
            maps.append(bindings)
        sources, apks = maps
        for filename in sources.keys() & apks.keys():
            require(sources[filename] == apks[filename], "conflicting digest across maps: " + filename)

        argv = inputs["argv"]
        require(isinstance(argv, list) and bool(argv) and all(isinstance(v, str) for v in argv),
                "argv must be a nonempty string list")
        try:
            args = packaging.build_parser().parse_args(argv)
        except SystemExit as error:
            raise AssertionError("precondition(D17-pkg): invalid verify argv") from error
        require(args.command == "verify", "verify subcommand required")
        repo = canonical_path(str(repo_root))
        require(repo.is_dir(), "current repository directory required")
        require(canonical_path(args.repo_root) == repo, "argv repo is not the current candidate")

        expected_apks = set()
        for field, relative in APK_ARGUMENTS.items():
            expected = canonical_path(str(repo / relative))
            require(canonical_path(getattr(args, field)) == expected,
                    "argv APK is not the current candidate " + field)
            expected_apks.add(str(expected))
        require(set(apks) == expected_apks, "apkHashes must bind exactly the five argv/current APKs")

        required_sources = {str(canonical_path(str(repo / relative))) for relative in SOURCE_FILES}
        for relative in SOURCE_TREES:
            required_sources.update(tree_files(repo / relative))
        for field in SOURCE_ARGUMENTS:
            path = canonical_path(getattr(args, field))
            require(path.is_file(), "regular argv input required: " + field)
            required_sources.add(str(path))
        # Use the verifier's real sourceSets parser, including roots outside app/src.
        for field in ("s5_source_report", "ci_minified_source_report"):
            sets = packaging.source_sets(Path(getattr(args, field)).read_text(encoding="utf-8"))
            for variant in ("main", "debug", "release", "ciMinified", "benchmark", "s5Benchmark"):
                require(variant in sets, "missing source set in " + field + ": " + variant)
                for kind in packaging.SOURCE_KINDS:
                    for value in sets[variant].get(kind, []):
                        path = repo / value
                        require(path.resolve().is_relative_to(repo), "source root escapes current repo: " + value)
                        require(not path.is_symlink(), "symlink source root: " + value)
                        if path.exists():
                            path = canonical_path(str(path))
                            required_sources.update(tree_files(path, allow_empty=True))
        classpath = args.s5_compile_classpath_entry
        require(isinstance(classpath, list) and bool(classpath), "current compile classpath required")
        for entry in classpath:
            path = canonical_path(entry)
            if path.is_dir():
                required_sources.update(tree_files(path))
            else:
                require(path.is_file(), "regular classpath input required: " + entry)
                required_sources.add(str(path))
        report = repo / "build/reports/s5/compile-classpath.txt"
        require(report.read_text(encoding="utf-8").splitlines() == classpath,
                "argv classpath differs from current compiler task report")
        require(required_sources <= sources.keys(), "sourceHashes omits candidate source or verify/classpath input")

        # Compare overlap explicitly above; never use a right-biased map merge.
        for bindings in maps:
            for filename, digest in bindings.items():
                require(hashlib.sha256(Path(filename).read_bytes()).hexdigest() == digest,
                        "current candidate bytes mismatch: " + filename)
        return argv
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        raise AssertionError("precondition(D17-pkg): malformed or missing candidate input: " + str(error)) from error


def load_candidate_inputs(path, repo_root):
    try:
        require(bool(path), "current candidate input manifest required")
        inputs = json.loads(Path(path).read_bytes(), object_pairs_hook=unique_object)
        return validate_candidate_inputs(inputs, repo_root)
    except (OSError, ValueError, TypeError) as error:
        raise AssertionError("precondition(D17-pkg): unreadable or malformed manifest: " + str(error)) from error


class CandidatePackageTest(unittest.TestCase):
    def test_candidate_final_apks(self):
        # The expected repository comes from this target, never from the manifest.
        argv = load_candidate_inputs(os.environ.get("S6_D17_PACKAGE_INPUTS"),
                                     Path(__file__).resolve().parents[2])
        result = packaging.main(argv)
        if result != 0:
            self.fail("contract(D17b):pkg-candidate/final-apks: verify exit " + str(result))


if __name__ == "__main__":
    unittest.main()
