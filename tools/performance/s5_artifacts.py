#!/usr/bin/env python3
"""S5 canonical evidence inputs (S5-4 E slice 6b). Standard library only.

Builds five of the manifest's seven top-level artifacts from one materialized source snapshot after the runner has built it
(the two APKs come from that build): the parser bytes, the fixture payload manifest, the harness and production resolved
dependency graphs and the R8 rule-input manifest. Each is one file (README "Artifact hashes must refer to a single file");
several inputs become a canonical manifest of ``<sha256>  <label>`` lines sorted by the label's UTF-8 bytes. Nothing is
published until all five are built and checked, and nothing existing is overwritten.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Callable, Iterable, Sequence

import benchmark_gate as gate
import verify_s5_benchmark_artifacts as packaging

PARSER_PATH = "tools/performance/benchmark_gate.py"
CONFIGURATION_PATH = "app/build/outputs/mapping/s5Benchmark/configuration.txt"
FIREBASE_CANONICAL = "ci/google-services.ci-fixture.json"
FIREBASE_BUILD_COPY = "app/src/s5Benchmark/google-services.json"
GRAPHS = {
    "harnessResolvedGraph": (":macrobenchmark", "s5BenchmarkRuntimeClasspath"),
    "productionResolvedGraph": (":app", "releaseRuntimeClasspath"),
}
FILE_NAMES = {
    "parser": "benchmark_gate.py",
    "fixturePayload": "fixture-payload.sha256",
    "harnessResolvedGraph": "harness-resolved-graph.txt",
    "productionResolvedGraph": "production-resolved-graph.txt",
    "r8RulesCanonicalManifest": "r8-rules.sha256",
}
KEYS = gate.ARTIFACT_KEYS - {"targetBenchmarkApk", "macrobenchmarkTestApk"}
SECTION_START = "# The proguard configuration file for the following section is "
SECTION_END = "# End of content from "
REQUIRED_R8_INPUTS = (
    ("app/proguard-rules.pro", False),
    ("app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-", True),
    ("app/build/intermediates/aapt_proguard_file/s5Benchmark/", True),
)
UNRESOLVED = ("FAILED", "could not be resolved", " (n)")
GRADLE_TIMEOUT_SECONDS = 1800


class ArtifactError(RuntimeError):
    """An input cannot be turned into a canonical artifact."""


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _manifest(lines: list[tuple[str, str]]) -> bytes:
    for _, label in lines:
        if any(character in label for character in "\n\r\0"):
            raise ArtifactError(f"a manifest label holds a line break or NUL: {label!r}")
    ordered = sorted(lines, key=lambda line: (line[1].encode("utf-8"), line[0]))
    return "".join(f"{digest}  {label}\n" for digest, label in ordered).encode("utf-8")


def _regular_bytes(path: Path, label: str) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ArtifactError(f"{label} is missing or not a regular file")
    try:
        return path.read_bytes()
    except OSError as error:
        raise ArtifactError(f"cannot read {label}: {error}") from error


def fixture_payload(root: Path) -> bytes:
    """Every regular file under the fixture source roots and the checked-in CI Firebase fixture. The s5Benchmark Firebase file
    is the build's copy of that fixture: it must be the same bytes and is not listed itself. Firebase bytes are only hashed
    and compared."""
    lines: list[tuple[str, str]] = []
    for source_root in packaging.FIXTURE_SOURCE_ROOTS:
        base = root / source_root
        if base.is_symlink() or not base.is_dir():
            raise ArtifactError(f"fixture source root {source_root} is missing or not a directory")
        found = 0
        paths: list[Path] = []
        pending = [base]
        while pending:
            directory = pending.pop()
            try:
                children = list(directory.iterdir())
            except OSError as error:
                raise ArtifactError(f"cannot scan fixture directory {directory}: {error}") from error
            paths.extend(children)
            pending.extend(path for path in children if not path.is_symlink() and path.is_dir())
        for path in sorted(paths):
            relative = path.relative_to(root).as_posix()
            if path.is_symlink() or not (path.is_file() or path.is_dir()):
                raise ArtifactError(f"fixture source {relative} is a symlink or special file")
            if path.is_dir() or relative == FIREBASE_BUILD_COPY:
                continue
            lines.append((_sha256(_regular_bytes(path, relative)), relative))
            found += 1
        if not found:
            raise ArtifactError(f"fixture source root {source_root} is empty")
    canonical = _regular_bytes(root / FIREBASE_CANONICAL, FIREBASE_CANONICAL)
    if _regular_bytes(root / FIREBASE_BUILD_COPY, FIREBASE_BUILD_COPY) != canonical:
        raise ArtifactError(f"{FIREBASE_BUILD_COPY} is not the bytes of {FIREBASE_CANONICAL}")
    lines.append((_sha256(canonical), FIREBASE_CANONICAL))
    by_path = {label: digest for digest, label in lines}
    for name, approved in packaging.FIXTURE_ASSETS.items():
        label = f"app/src/benchmark/assets/s5_4/{name}"
        if by_path.get(label) != approved:
            raise ArtifactError(f"fixture asset {label} is missing or not the approved bytes")
    return _manifest(lines)


def _relative(path: Path, base: Path) -> str | None:
    try:
        return path.resolve().relative_to(base.resolve()).as_posix()
    except ValueError:
        return None


def _r8_label(path: Path, root: Path, gradle_homes: Iterable[Path]) -> str:
    inside = _relative(path, root)
    if inside is not None:
        return inside
    for home in gradle_homes:
        relative = _relative(path, home)
        if relative is None:
            continue
        parts = relative.split("/")
        if len(parts) >= 7 and parts[0] == "caches" and parts[2] == "transforms" and parts[4] == "transformed":
            return "gradle-transform:" + "/".join(parts[5:])
        raise ArtifactError(f"R8 input under a Gradle home but not a transform output: {path}")
    raise ArtifactError(f"R8 input outside the snapshot and the build's Gradle homes: {path}")


def r8_rules(configuration: str, root: Path, gradle_homes: Sequence[Path]) -> bytes:
    """The rule input files AGP listed in configuration.txt, each hashed from its own bytes. Each section body must be exactly
    that file plus one LF; otherwise the file is not the one the build used."""
    lines: list[tuple[str, str]] = []
    current: str | None = None
    body: list[str] = []
    for number, line in enumerate(configuration.split("\n"), 1):
        if line.startswith(SECTION_START):
            if current is not None:
                raise ArtifactError(f"line {number}: a section starts inside {current}")
            current, body = line[len(SECTION_START):], []
        elif line.startswith(SECTION_END):
            if current is None or line[len(SECTION_END):] != current:
                raise ArtifactError(f"line {number}: the end does not match the open section")
            text = "\n".join(body) + "\n"
            if current == "<unknown>":
                if text.strip():
                    raise ArtifactError("rules with an unknown source cannot be attributed")
            else:
                path = Path(current)
                if not path.is_absolute():
                    raise ArtifactError(f"R8 input path is not absolute: {current}")
                label = _r8_label(path, root, gradle_homes)
                data = _regular_bytes(path, f"R8 input {label}")
                if text.encode("utf-8") != data + b"\n":
                    raise ArtifactError(f"R8 input {label} differs from the rules the build used")
                lines.append((_sha256(data), label))
            current = None
        elif current is not None:
            body.append(line)
        elif line.strip():
            raise ArtifactError(f"line {number}: a rule outside any section")
    if current is not None:
        raise ArtifactError(f"the section {current} never ends")
    if not lines:
        raise ArtifactError("configuration.txt lists no rule file")
    labels = [label for _, label in lines]
    for required, is_prefix in REQUIRED_R8_INPUTS:
        if not any(label.startswith(required) if is_prefix else label == required for label in labels):
            raise ArtifactError(f"configuration.txt lacks the required rule input {required}")
    return _manifest(lines)


def normalize_graph(output: str, root: Path, project: str, configuration: str) -> bytes:
    """One dependencies report as LF text without trailing spaces or blank edges. It must be the requested project and
    configuration with at least one dependency, and show nothing unresolved and no host path."""
    lines = [line.rstrip() for line in output.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    while lines and not lines[0]:
        lines.pop(0)
    while lines and not lines[-1]:
        lines.pop()
    if not lines:
        raise ArtifactError("the dependency report is empty")
    text = "\n".join(lines) + "\n"
    marker = next((marker for marker in UNRESOLVED if marker in text), None)
    if marker is not None:
        raise ArtifactError(f"the dependency report shows an unresolved dependency ({marker.strip()})")
    variant = configuration.removesuffix("RuntimeClasspath")
    expected_header = f"{configuration} - Runtime classpath of '/{variant}'."
    projects = [line for line in lines if line.startswith("Project ")]
    headers = [line for line in lines if re.match(r"^[A-Za-z0-9_$.-]+ - ", line)]
    if projects != [f"Project '{project}'"] or headers != [expected_header]:
        raise ArtifactError(f"the report is not exactly {project} {configuration}")
    for line in lines:
        if line == expected_header:
            continue
        if re.search(r"(?<![A-Za-z0-9_./:-])(?:/|[A-Za-z]:[\\/]|\\\\|~[/\\]|file:/)", line):
            raise ArtifactError("the dependency report contains a host path")
    if not any(line.startswith(("+--- ", "\\--- ")) for line in lines[lines.index(expected_header) + 1:]):
        raise ArtifactError("the dependency report lists no dependency")
    return text.encode("utf-8")


def gradle_environment() -> dict[str, str]:
    """The environment of device_preflight.run_benchmark_build, kept here so that bound runner stays unchanged."""
    environment = os.environ.copy()
    for variable in ("GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "KOTLIN_OPTS"):
        environment.pop(variable, None)
    for variable in list(environment):
        if variable.startswith("ORG_GRADLE_PROJECT_"):
            environment.pop(variable)
    sdk = environment.get("ANDROID_HOME") or environment.get("ANDROID_SDK_ROOT")
    if not sdk:
        default_sdk = Path.home() / "Library" / "Android" / "sdk"
        if not default_sdk.is_dir():
            raise ArtifactError("Android SDK path is not configured")
        sdk = str(default_sdk)
    environment["ANDROID_HOME"] = sdk
    environment["ANDROID_SDK_ROOT"] = sdk
    return environment


Runner = Callable[[list[str], Path, dict[str, str]], "subprocess.CompletedProcess[str]"]


def _run(command: list[str], cwd: Path, environment: dict[str, str]) -> "subprocess.CompletedProcess[str]":
    return subprocess.run(command, cwd=cwd, env=environment, text=True, encoding="utf-8", capture_output=True,
                          timeout=GRADLE_TIMEOUT_SECONDS)


def resolved_graph(root: Path, project: str, configuration: str, run: Runner = _run) -> bytes:
    environment = gradle_environment()
    original_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    with tempfile.TemporaryDirectory(prefix="s5-gradle-home-") as temp:
        home = Path(temp)
        for reusable in ("caches", "wrapper", "jdks"):
            if (original_home / reusable).exists():
                (home / reusable).symlink_to(original_home / reusable, target_is_directory=True)
        environment["GRADLE_USER_HOME"] = str(home)
        command = [str(root / "gradlew"), "-q", "--console=plain", "--no-daemon", "--no-configuration-cache",
                   f"{project}:dependencies", "--configuration", configuration]
        try:
            result = run(command, root, environment)
        except (subprocess.SubprocessError, UnicodeError, OSError) as error:
            raise ArtifactError(f"{project}:dependencies did not finish: {type(error).__name__}") from error
    if result.returncode != 0:
        raise ArtifactError(f"{project}:dependencies --configuration {configuration} failed with exit {result.returncode}")
    return normalize_graph(result.stdout, root, project, configuration)


def _publish(path: Path, data: bytes) -> None:
    """Temporary file, fsync, then a hard link that fails if anything (a file or a symlink) is already at ``path``."""
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        try:
            written = 0
            while written < len(data):
                written += os.write(descriptor, data[written:])
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.link(temporary, path)
    finally:
        os.unlink(temporary)


def collect(root: Path, out: Path, *, gradle_homes: Sequence[Path], run: Runner = _run) -> dict[str, dict[str, str]]:
    """Builds and checks all five, publishes each without overwriting, re-hashes them and returns {key: {path, sha256}} with
    paths relative to ``out``. ``gradle_homes`` are the Gradle user homes the build used (a temporary one and its real one)."""
    try:
        # Bytes, decoded without newline translation: a rule file's CR LF must reach the byte comparison unchanged.
        configuration = (root / CONFIGURATION_PATH).read_bytes().decode("utf-8")
    except (OSError, UnicodeError) as error:
        raise ArtifactError(f"cannot read {CONFIGURATION_PATH}: {error}") from error
    parser = _regular_bytes(root / PARSER_PATH, PARSER_PATH)
    if parser != Path(gate.__file__).read_bytes():
        raise ArtifactError("the snapshot's benchmark_gate.py is not the judge running this collect")
    contents = {
        "parser": parser,
        "fixturePayload": fixture_payload(root),
        "r8RulesCanonicalManifest": r8_rules(configuration, root, gradle_homes),
        **{key: resolved_graph(root, project, name, run) for key, (project, name) in GRAPHS.items()},
    }
    if set(contents) != KEYS or len({_sha256(data) for data in contents.values()}) != len(contents):
        raise ArtifactError("the five inputs must be exactly the gate's keys with distinct bytes")
    for key in sorted(contents):
        _publish(out / FILE_NAMES[key], contents[key])
    references = {}
    for key in sorted(contents):
        digest = _sha256(_regular_bytes(out / FILE_NAMES[key], FILE_NAMES[key]))
        if digest != _sha256(contents[key]):
            raise ArtifactError(f"{FILE_NAMES[key]} changed after it was published")
        references[key] = {"path": FILE_NAMES[key], "sha256": digest}
    return references


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("collect", help="write the five canonical inputs of one built snapshot")
    command.add_argument("--build-root", type=Path, required=True)
    command.add_argument("--gradle-home", type=Path, action="append", required=True,
                         help="a Gradle user home the build used (repeat for a temporary home and its real one)")
    command.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        references = collect(args.build_root, args.out, gradle_homes=args.gradle_home)
    except (ArtifactError, OSError) as error:
        print(json.dumps({"error": str(error)}, ensure_ascii=False))
        return 2
    print(json.dumps(references, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
