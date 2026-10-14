import hashlib
import io
import json
import os
import subprocess
import tempfile
import unittest
import unittest.mock
from pathlib import Path

import benchmark_gate as gate
import s5_artifacts as artifacts
import verify_s5_benchmark_artifacts as packaging

ASSETS = ("bundle.json", "catalog.json", "tether-1d.json", "tether-1w.json", "usd-1d.json")
HARNESS = "------------------------------------------------------------\nProject ':macrobenchmark'\n" \
          "------------------------------------------------------------\n\n" \
          "s5BenchmarkRuntimeClasspath - Runtime classpath of '/s5Benchmark'.\n+--- a:b:1.0\n\\--- c:d:2.0 -> 2.1\n\n"
PRODUCTION = "Project ':app'\r\n\r\nreleaseRuntimeClasspath - Runtime classpath of '/release'.   \r\n\\--- e:f:3.0\r\n\r\n"


class Snapshot:
    """A built snapshot in a temporary folder: fixture sources, the Firebase fixture and its build copy, the parser, rule
    files in the snapshot and in a fake Gradle home, a configuration.txt listing them, and a fake process runner for the
    two dependencies reports."""

    def __init__(self, base: Path):
        self.root = base / "snapshot"
        self.home = base / "gradle-home"
        self.calls = []
        self.reports = {"s5BenchmarkRuntimeClasspath": HARNESS, "releaseRuntimeClasspath": PRODUCTION}
        self.codes = {}
        self.errors = {}
        for name in ASSETS:
            self.write(f"app/src/benchmark/assets/s5_4/{name}", f"asset {name}".encode())
        self.write("app/src/s5Benchmark/java/com/jay/fxi/s5fixture/S5FixtureRoutes.kt", b"object Routes\n")
        self.write("app/src/s5Benchmark/AndroidManifest.xml", b"<manifest/>\n")
        self.write(artifacts.FIREBASE_CANONICAL, b"{\"fixture\": true}\n")
        self.write(artifacts.FIREBASE_BUILD_COPY, b"{\"fixture\": true}\n")
        self.write(artifacts.PARSER_PATH, Path(gate.__file__).read_bytes())
        self.rules = [
            self.rule(self.root / "app/proguard-rules.pro", b"-keep class A\r\n"),
            self.rule(self.root / "app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-8.13.2", b"-optimizations x\n"),
            self.rule(self.root / "app/build/intermediates/aapt_proguard_file/s5Benchmark/processS5BenchmarkResources/aapt_rules.txt", b"-keep class R\n"),
            self.rule(self.home / "caches/9.1.0/transforms/0123abcd/transformed/lib-1.0/proguard.txt", b"-dontwarn x"),
        ]
        self.configure()

    def write(self, relative, data: bytes):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def rule(self, path: Path, data: bytes):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    @staticmethod
    def sections(items):
        """configuration.txt as AGP writes it: each section's body is the file bytes plus one LF."""
        out = b""
        for path, data in items:
            out += (artifacts.SECTION_START + str(path) + "\n").encode() + data + b"\n" + (artifacts.SECTION_END + str(path) + "\n").encode()
        return out

    def configure(self, items=None, *, unknown=b""):
        items = items if items is not None else [(path, path.read_bytes()) for path in self.rules]
        text = self.sections(items) + (artifacts.SECTION_START + "<unknown>\n").encode() + unknown + b"\n" + (artifacts.SECTION_END + "<unknown>").encode()
        self.write(artifacts.CONFIGURATION_PATH, text)

    def approved(self):
        return {name: hashlib.sha256(f"asset {name}".encode()).hexdigest() for name in ASSETS}

    def run(self, command, cwd, environment):
        self.calls.append((command, cwd, environment))
        configuration = command[command.index("--configuration") + 1]
        if configuration in self.errors:
            raise self.errors[configuration]
        return subprocess.CompletedProcess(command, self.codes.get(configuration, 0), self.reports[configuration], "noise on stderr")


class S5ArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.snap = Snapshot(base)
        self.out = base / "out"
        self.out.mkdir()
        patch = unittest.mock.patch.object(packaging, "FIXTURE_ASSETS", self.snap.approved())
        patch.start()
        self.addCleanup(patch.stop)

    def tearDown(self):
        self.temp.cleanup()

    def collect(self, out=None):
        return artifacts.collect(self.snap.root, out or self.out, gradle_homes=[self.snap.home], run=self.snap.run)

    def r8(self):
        configuration = (self.snap.root / artifacts.CONFIGURATION_PATH).read_bytes().decode()
        return artifacts.r8_rules(configuration, self.snap.root, [self.snap.home])

    def assertFails(self, text, call):
        with self.assertRaises(artifacts.ArtifactError) as caught:
            call()
        self.assertIn(text, str(caught.exception))

    # --- fixture payload ------------------------------------------------------------------------------------------
    def test_the_fixture_payload_lists_the_sources_and_the_firebase_fixture_not_its_copy(self):
        lines = artifacts.fixture_payload(self.snap.root).decode().splitlines()
        labels = [line.split("  ", 1)[1] for line in lines]
        self.assertEqual(sorted(labels, key=str.encode), labels)
        self.assertIn(artifacts.FIREBASE_CANONICAL, labels)
        self.assertNotIn(artifacts.FIREBASE_BUILD_COPY, labels)
        self.assertEqual(8, len(labels))
        routes = self.snap.root / "app/src/s5Benchmark/java/com/jay/fxi/s5fixture/S5FixtureRoutes.kt"
        self.assertIn(f"{hashlib.sha256(routes.read_bytes()).hexdigest()}  app/src/s5Benchmark/java/com/jay/fxi/s5fixture/S5FixtureRoutes.kt", lines)

    def test_the_fixture_payload_refuses_what_it_cannot_vouch_for(self):
        root = self.snap.root
        cases = [
            ("asset bytes", lambda: (root / "app/src/benchmark/assets/s5_4/usd-1d.json").write_bytes(b"changed"), "not the approved bytes"),
            ("asset missing", lambda: (root / "app/src/benchmark/assets/s5_4/usd-1d.json").unlink(), "not the approved bytes"),
            ("asset by basename elsewhere", lambda: (
                (root / "app/src/benchmark/assets/s5_4/z").mkdir(),
                (root / "app/src/benchmark/assets/s5_4/z/usd-1d.json").write_bytes((root / "app/src/benchmark/assets/s5_4/usd-1d.json").read_bytes()),
                (root / "app/src/benchmark/assets/s5_4/usd-1d.json").write_bytes(b"changed")), "not the approved bytes"),
            ("root is a link", lambda: (os.rename(root / "app/src/s5Benchmark", root / "app/src/real"),
                                        (root / "app/src/s5Benchmark").symlink_to(root / "app/src/real", target_is_directory=True)),
             "missing or not a directory"),
            ("special file", lambda: os.mkfifo(root / "app/src/s5Benchmark/pipe"), "symlink or special"),
            ("copy differs", lambda: (root / artifacts.FIREBASE_BUILD_COPY).write_bytes(b"{}"), "is not the bytes of"),
            ("copy missing", lambda: (root / artifacts.FIREBASE_BUILD_COPY).unlink(), "missing or not a regular file"),
            ("copy is a link", lambda: ((root / artifacts.FIREBASE_BUILD_COPY).unlink(),
                                        (root / artifacts.FIREBASE_BUILD_COPY).symlink_to(root / artifacts.FIREBASE_CANONICAL)),
             "symlink or special"),
            ("canonical is a link", lambda: (os.rename(root / artifacts.FIREBASE_CANONICAL, root / "ci/real.json"),
                                             (root / artifacts.FIREBASE_CANONICAL).symlink_to(root / "ci/real.json")),
             "missing or not a regular file"),
            ("canonical missing", lambda: (root / artifacts.FIREBASE_CANONICAL).unlink(), "missing or not a regular file"),
            ("symlink source", lambda: (root / "app/src/s5Benchmark/link.kt").symlink_to(root / artifacts.PARSER_PATH), "symlink or special"),
            ("root missing", lambda: os.rename(root / "app/src/s5Benchmark", root / "app/src/s5Gone"), "missing or not a directory"),
        ]
        for name, damage, text in cases:
            with self.subTest(name), tempfile.TemporaryDirectory() as temp:
                self.snap = Snapshot(Path(temp))
                root = self.snap.root
                damage()
                self.assertFails(text, lambda: artifacts.fixture_payload(self.snap.root))
        with tempfile.TemporaryDirectory() as temp:
            self.snap = Snapshot(Path(temp))
            for path in (self.snap.root / "app/src/s5Benchmark").rglob("*"):
                if path.is_file() and path.relative_to(self.snap.root).as_posix() != artifacts.FIREBASE_BUILD_COPY:
                    path.unlink()
            self.assertFails("is empty", lambda: artifacts.fixture_payload(self.snap.root))

    def test_extra_fixture_files_are_listed_too(self):
        (self.snap.root / "app/src/benchmark/assets/s5_4/extra.json").write_bytes(b"x")
        labels = [line.split("  ", 1)[1] for line in artifacts.fixture_payload(self.snap.root).decode().splitlines()]
        self.assertIn("app/src/benchmark/assets/s5_4/extra.json", labels)

    def test_unreadable_fixture_folders_and_files_fail_instead_of_dropping_out(self):
        folder = self.snap.root / "app/src/s5Benchmark/java"
        folder.chmod(0)
        try:
            self.assertFails("cannot scan fixture directory", lambda: artifacts.fixture_payload(self.snap.root))
        finally:
            folder.chmod(0o755)
        source = self.snap.root / "app/src/s5Benchmark/AndroidManifest.xml"
        source.chmod(0)
        try:
            self.assertFails("cannot read", lambda: artifacts.fixture_payload(self.snap.root))
        finally:
            source.chmod(0o644)

    # --- R8 rules -------------------------------------------------------------------------------------------------
    def test_r8_lines_hash_each_rule_file_by_its_own_bytes(self):
        lines = self.r8().decode().splitlines()
        self.assertEqual(4, len(lines))
        labels = [line.split("  ", 1)[1] for line in lines]
        self.assertEqual(sorted(labels, key=str.encode), labels)
        self.assertNotEqual(labels, [artifacts._r8_label(path, self.snap.root, [self.snap.home]) for path in self.snap.rules])
        by_label = {line.split("  ", 1)[1]: line.split("  ", 1)[0] for line in lines}
        self.assertEqual(hashlib.sha256(b"-keep class A\r\n").hexdigest(), by_label["app/proguard-rules.pro"])
        self.assertIn("gradle-transform:lib-1.0/proguard.txt", by_label)
        self.assertNotIn("<unknown>", by_label)

    def test_a_rule_file_that_changed_after_the_build_is_refused(self):
        self.snap.rules[0].write_bytes(b"-keep class B\r\n")
        self.assertFails("differs from the rules the build used", self.r8)

    def test_hosts_differ_only_by_cache_hash_and_home_and_give_the_same_bytes(self):
        first = self.r8()
        with tempfile.TemporaryDirectory() as temp:
            other = Snapshot(Path(temp))
            moved = other.home / "caches/9.2.0/transforms/feedbeef/transformed/lib-1.0/proguard.txt"
            moved.parent.mkdir(parents=True)
            os.rename(other.rules[3], moved)
            other.rules[3] = moved
            other.configure()
            configuration = (other.root / artifacts.CONFIGURATION_PATH).read_bytes().decode()
            self.assertEqual(first, artifacts.r8_rules(configuration, other.root, [other.home]))

    def test_a_temporary_gradle_home_linked_to_the_real_cache_gives_the_same_label(self):
        temporary_home = Path(self.temp.name) / "temporary-home"
        temporary_home.mkdir()
        (temporary_home / "caches").symlink_to(self.snap.home / "caches", target_is_directory=True)
        linked = temporary_home / self.snap.rules[3].relative_to(self.snap.home)
        items = [(path, path.read_bytes()) for path in self.snap.rules[:3]] + [(linked, linked.read_bytes())]
        self.snap.configure(items)
        configuration = (self.snap.root / artifacts.CONFIGURATION_PATH).read_bytes().decode()
        via_link = artifacts.r8_rules(configuration, self.snap.root, [temporary_home, self.snap.home])
        self.snap.configure()
        self.assertEqual(self.r8(), via_link)

    def test_a_rule_file_listed_twice_keeps_both_identical_lines(self):
        self.snap.configure([(path, path.read_bytes()) for path in self.snap.rules + [self.snap.rules[1]]])
        lines = self.r8().decode().splitlines()
        self.assertEqual(5, len(lines))
        self.assertEqual(2, lines.count(next(line for line in lines if "proguard-android-optimize" in line)))

    def test_dot_dot_cannot_lead_out_of_the_snapshot_or_the_cache(self):
        good = [(path, path.read_bytes()) for path in self.snap.rules]
        outside = Path(self.temp.name) / "outside.pro"
        outside.write_bytes(b"-keep class E\n")
        escaped_root = Path(f"{self.snap.root}/../outside.pro")
        escaped_cache = Path(f"{self.snap.home}/caches/9.1.0/transforms/0123abcd/transformed/../../../../../../outside.pro")
        for name, path, message in (("root", escaped_root, "outside the snapshot"), ("cache", escaped_cache, "outside the snapshot")):
            with self.subTest(name):
                self.snap.write(artifacts.CONFIGURATION_PATH, Snapshot.sections(good + [(path, outside.read_bytes())]))
                self.assertFails(message, self.r8)

    def test_the_same_label_with_other_bytes_keeps_both_lines(self):
        twin = self.snap.home / "caches/9.1.0/transforms/99999999/transformed/lib-1.0/proguard.txt"
        twin.parent.mkdir(parents=True)
        twin.write_bytes(b"-dontwarn y")
        pair = sorted([self.snap.rules[3], twin], key=lambda path: hashlib.sha256(path.read_bytes()).hexdigest(), reverse=True)
        # Listed with the larger digest first, so only the digest tie-break puts them in order.
        self.snap.configure([(path, path.read_bytes()) for path in self.snap.rules[:3] + pair])
        lines = [line.split("  ", 1) for line in self.r8().decode().splitlines()]
        twins = [digest for digest, label in lines if label == "gradle-transform:lib-1.0/proguard.txt"]
        self.assertEqual(2, len(twins))
        self.assertEqual(sorted(twins), twins)
        self.assertEqual(sorted([hashlib.sha256(b"-dontwarn x").hexdigest(), hashlib.sha256(b"-dontwarn y").hexdigest()]), twins)

    def test_paths_spelled_through_a_resolved_folder_get_the_same_labels(self):
        resolved = [(path.resolve(), path.read_bytes()) for path in self.snap.rules]
        self.assertNotEqual(str(self.snap.rules[0]), str(resolved[0][0]), "the temporary folder is reached through a symlink")
        expected = self.r8()
        self.snap.configure(resolved)
        self.assertEqual(expected, self.r8())

    def test_r8_configuration_defects_fail(self):
        start, end = artifacts.SECTION_START, artifacts.SECTION_END
        good = [(path, path.read_bytes()) for path in self.snap.rules]
        outside_home = Path(self.temp.name) / "elsewhere/rules.pro"
        outside_home.parent.mkdir()
        outside_home.write_bytes(b"-keep class Z\n")
        lookalike = self.snap.rule(self.snap.root / "app/proguard-rules.pro.old", b"-keep class O\n")
        not_transform = self.snap.home / "init.d/rules.pro"
        not_transform.parent.mkdir()
        not_transform.write_bytes(b"-keep class Y\n")
        cases = {
            "rule outside": (b"-keep class A\n" + Snapshot.sections(good), "outside any section"),
            "nested": ((start + "<unknown>\n" + start + "<unknown>\n" + end + "<unknown>").encode(), "starts inside"),
            "end mismatch": ((start + "<unknown>\n\n" + end + "/other").encode(), "does not match"),
            "end without start": ((end + "<unknown>").encode(), "does not match"),
            "never ends": ((start + "<unknown>\n-keep class A").encode(), "never ends"),
            "no file": (b"\n\n", "lists no rule file"),
            "unknown rules": (None, "unknown source"),
            "outside path": (Snapshot.sections(good + [(outside_home, outside_home.read_bytes())]), "outside the snapshot"),
            "home not transform": (Snapshot.sections(good + [(not_transform, not_transform.read_bytes())]), "not a transform output"),
            "relative path": (Snapshot.sections(good) + (start + "rules.pro\n\n" + end + "rules.pro").encode(), "not absolute"),
            "missing file": (Snapshot.sections(good + [(self.snap.root / "app/gone.pro", b"")]), "missing or not a regular file"),
            "missing proguard-rules": (Snapshot.sections(good[1:]), "app/proguard-rules.pro"),
            "lookalike proguard-rules": (Snapshot.sections([(lookalike, lookalike.read_bytes())] + good[1:]), "app/proguard-rules.pro"),
            "missing optimize": (Snapshot.sections(good[:1] + good[2:]), "proguard-android-optimize"),
            "missing aapt": (Snapshot.sections(good[:2] + good[3:]), "aapt_proguard_file"),
        }
        for name, (text, message) in cases.items():
            with self.subTest(name):
                if text is None:
                    self.snap.configure(unknown=b"-keep class Q")
                else:
                    self.snap.write(artifacts.CONFIGURATION_PATH, text)
                self.assertFails(message, self.r8)

    def test_the_last_end_may_lack_its_line_feed_and_labels_may_not_break_lines(self):
        self.r8()  # configure() writes the <unknown> end without a final LF
        for label in ("a\nb", "a\rb", "a\0b"):
            with self.subTest(repr(label)), self.assertRaises(artifacts.ArtifactError):
                artifacts._manifest([("0" * 64, label)])

    # --- graphs ---------------------------------------------------------------------------------------------------
    def test_graph_reports_are_normalized(self):
        expected = b"Project ':app'\n\nreleaseRuntimeClasspath - Runtime classpath of '/release'.\n\\--- e:f:3.0\n"
        self.assertEqual(expected, artifacts.normalize_graph(PRODUCTION, self.snap.root, ":app", "releaseRuntimeClasspath"))
        self.assertEqual(expected, artifacts.normalize_graph("\n  \n" + PRODUCTION, self.snap.root, ":app", "releaseRuntimeClasspath"))

    def test_graph_reports_that_are_unresolved_foreign_or_empty_fail(self):
        root = self.snap.root
        cases = {
            "failed": (HARNESS.replace("a:b:1.0", "a:b:1.0 FAILED"), "unresolved"),
            "could not": (HARNESS + "a:b could not be resolved\n", "unresolved"),
            "not resolved": (HARNESS.replace("a:b:1.0", "a:b:1.0 (n)"), "unresolved"),
            "home": (HARNESS + f"{Path.home()}/lib.jar\n", "host path"),
            "root": (HARNESS + f"{root.resolve()}/lib.jar\n", "host path"),
            "shared path": (HARNESS + "+--- /opt/shared/sdk/lib.jar\n", "host path"),
            "other home": (HARNESS + "+--- files(/Users/someone/lib.jar)\n", "host path"),
            "windows": (HARNESS + "+--- C:\\sdk\\lib.jar\n", "host path"),
            "tilde": (HARNESS + "+--- ~/lib.jar\n", "host path"),
            "file url": (HARNESS + "+--- file:/opt/lib.jar\n", "host path"),
            "wrong variant": (HARNESS.replace("of '/s5Benchmark'.", "of '/release'."), "not exactly"),
            # No slash anywhere, so only the exact header comparison (not the host-path check) refuses it.
            "header text differs": (HARNESS.replace("Runtime classpath of '/s5Benchmark'.", "Runtime classpath."), "not exactly"),
            "second configuration": (HARNESS + "\nbenchmarkRuntimeClasspath - Runtime classpath of '/benchmark'.\n+--- x:y:1\n", "not exactly"),
            "empty": ("\n \n", "empty"),
            "other project": (HARNESS.replace(":macrobenchmark", ":app"), "not exactly"),
            "two projects": (HARNESS + HARNESS, "not exactly"),
            "other configuration": (HARNESS.replace("s5BenchmarkRuntimeClasspath", "benchmarkRuntimeClasspath"), "not exactly"),
            "no dependency": (HARNESS.split("+---")[0], "lists no dependency"),
        }
        for name, (text, message) in cases.items():
            with self.subTest(name):
                self.assertFails(message, lambda: artifacts.normalize_graph(text, root, ":macrobenchmark", "s5BenchmarkRuntimeClasspath"))

    def test_dependency_coordinates_with_urls_or_user_like_names_pass(self):
        text = HARNESS + "+--- com.jay:users-jay-tools:1.0 (https://example.com/repo)\n"
        artifacts.normalize_graph(text, self.snap.root, ":macrobenchmark", "s5BenchmarkRuntimeClasspath")

    def test_the_real_runner_bounds_gradle_with_a_timeout(self):
        with unittest.mock.patch.object(artifacts.subprocess, "run") as run:
            artifacts._run(["gradlew"], self.snap.root, {})
        self.assertEqual(artifacts.GRADLE_TIMEOUT_SECONDS, run.call_args.kwargs["timeout"])
        self.assertEqual((True, "utf-8", True), (run.call_args.kwargs["text"], run.call_args.kwargs["encoding"], run.call_args.kwargs["capture_output"]))

    def test_the_temporary_gradle_home_reuses_the_real_caches(self):
        real = Path(self.temp.name) / "real-home"
        for name in ("caches", "wrapper", "jdks"):
            (real / name).mkdir(parents=True)
        seen = {}

        def run(command, cwd, environment):
            home = Path(environment["GRADLE_USER_HOME"])
            seen.update({name: (home / name).is_symlink() and (home / name).resolve() == (real / name).resolve() for name in ("caches", "wrapper", "jdks")})
            return self.snap.run(command, cwd, environment)

        with unittest.mock.patch.dict(os.environ, {"GRADLE_USER_HOME": str(real)}):
            artifacts.resolved_graph(self.snap.root, ":app", "releaseRuntimeClasspath", run)
        self.assertEqual({"caches": True, "wrapper": True, "jdks": True}, seen)

    def test_graphs_run_the_snapshot_wrapper_with_the_runner_environment(self):
        with unittest.mock.patch.dict(os.environ, {"GRADLE_OPTS": "-Xmx1m", "JAVA_TOOL_OPTIONS": "x", "_JAVA_OPTIONS": "x",
                                                    "KOTLIN_OPTS": "x", "ORG_GRADLE_PROJECT_any": "x", "GRADLE_USER_HOME": str(self.snap.home)}):
            self.collect()
        gradle = self.snap.calls
        self.assertEqual([":macrobenchmark:dependencies", ":app:dependencies"], [call[0][5] for call in gradle])
        self.assertEqual(["s5BenchmarkRuntimeClasspath", "releaseRuntimeClasspath"], [call[0][7] for call in gradle])
        for command, cwd, environment in gradle:
            self.assertEqual([str(self.snap.root / "gradlew"), "-q", "--console=plain", "--no-daemon", "--no-configuration-cache"], command[:5])
            self.assertEqual(self.snap.root, cwd)
            for variable in ("GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "KOTLIN_OPTS", "ORG_GRADLE_PROJECT_any"):
                self.assertNotIn(variable, environment)
            self.assertEqual(environment["ANDROID_HOME"], environment["ANDROID_SDK_ROOT"])
            self.assertNotEqual(str(self.snap.home), environment["GRADLE_USER_HOME"])
            self.assertFalse(Path(environment["GRADLE_USER_HOME"]).exists(), "the temporary Gradle home is removed")

    def test_gradle_failures_fail_the_graph(self):
        for name, setup, message in (
            ("exit", lambda: self.snap.codes.__setitem__("releaseRuntimeClasspath", 1), "failed with exit 1"),
            ("timeout", lambda: self.snap.errors.__setitem__("releaseRuntimeClasspath", subprocess.TimeoutExpired("gradlew", 1)), "did not finish"),
            ("decode", lambda: self.snap.errors.__setitem__("releaseRuntimeClasspath", UnicodeDecodeError("utf-8", b"\xff", 0, 1, "bad")), "did not finish"),
        ):
            with self.subTest(name):
                self.snap.codes.clear()
                self.snap.errors.clear()
                setup()
                self.assertFails(message, lambda: artifacts.resolved_graph(self.snap.root, ":app", "releaseRuntimeClasspath", self.snap.run))

    # --- collect --------------------------------------------------------------------------------------------------
    def test_collect_publishes_the_five_keys_once_with_relative_paths(self):
        references = self.collect()
        self.assertEqual(artifacts.KEYS, set(references))
        self.assertEqual(gate.ARTIFACT_KEYS - {"targetBenchmarkApk", "macrobenchmarkTestApk"}, set(references))
        for key, reference in references.items():
            self.assertEqual(artifacts.FILE_NAMES[key], reference["path"])
            self.assertRegex(reference["sha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(hashlib.sha256((self.out / reference["path"]).read_bytes()).hexdigest(), reference["sha256"])
        self.assertEqual(5, len({reference["sha256"] for reference in references.values()}))
        self.assertEqual(Path(gate.__file__).read_bytes(), (self.out / "benchmark_gate.py").read_bytes())
        self.assertEqual(len(artifacts.FILE_NAMES), len(set(artifacts.FILE_NAMES.values())))
        self.assertEqual([], [p.name for p in self.out.iterdir() if p.name.endswith(".tmp")])

    def test_the_same_inputs_give_the_same_bytes(self):
        second = Path(self.temp.name) / "out2"
        second.mkdir()
        self.assertEqual(self.collect(), self.collect(second))

    def test_collect_never_overwrites_and_publishes_nothing_on_failure(self):
        (self.out / "r8-rules.sha256").symlink_to(self.out / "elsewhere")
        with self.assertRaises(FileExistsError):
            self.collect()
        self.assertFalse((self.out / "elsewhere").exists())
        fresh = Path(self.temp.name) / "fresh"
        fresh.mkdir()
        self.snap.codes["releaseRuntimeClasspath"] = 1
        with self.assertRaises(artifacts.ArtifactError):
            self.collect(fresh)
        self.assertEqual([], list(fresh.iterdir()))

    def test_a_file_created_just_before_publication_is_not_overwritten(self):
        real_link = os.link

        def racing_link(source, destination):
            if Path(destination).name == "fixture-payload.sha256":
                Path(destination).write_bytes(b"someone else\n")
            return real_link(source, destination)

        with unittest.mock.patch.object(artifacts.os, "link", racing_link):
            with self.assertRaises(FileExistsError):
                self.collect()
        self.assertEqual(b"someone else\n", (self.out / "fixture-payload.sha256").read_bytes())
        self.assertEqual([], [p.name for p in self.out.iterdir() if p.name.endswith(".tmp")])

    def test_a_published_file_changed_before_its_rehash_fails(self):
        real_publish = artifacts._publish

        def publish_then_change(path, data):
            real_publish(path, data)
            if path.name == "harness-resolved-graph.txt":
                os.chmod(path, 0o600)
                path.write_bytes(data + b"tampered\n")

        with unittest.mock.patch.object(artifacts, "_publish", publish_then_change):
            self.assertFails("changed after it was published", self.collect)

    def test_a_failure_while_publishing_keeps_finished_files_and_leaves_no_partial_one(self):
        real_collect = artifacts.collect
        real_publish = artifacts._publish
        for stage in ("write", "fsync", "link"):
            with self.subTest(stage):
                output = self.out / stage
                output.mkdir()
                calls = []
                expected = {}

                def fail(*args, **kwargs):
                    raise OSError(f"injected {stage} failure")

                def publish(path, data):
                    calls.append(path.name)
                    expected[path.name] = data
                    if len(calls) != 3:
                        return real_publish(path, data)
                    if stage == "write":
                        real_write = os.write
                        writes = []

                        def write_then_fail(descriptor, pending):
                            if writes:
                                return fail()
                            writes.append(descriptor)
                            return real_write(descriptor, pending[:1])

                        operation = write_then_fail
                    else:
                        operation = fail
                    with unittest.mock.patch.object(artifacts.os, stage, operation):
                        return real_publish(path, data)

                def collect(root, out, *, gradle_homes):
                    return real_collect(root, out, gradle_homes=gradle_homes, run=self.snap.run)

                stdout = io.StringIO()
                with unittest.mock.patch.object(artifacts, "_publish", publish), \
                        unittest.mock.patch.object(artifacts, "collect", collect), \
                        unittest.mock.patch("sys.stdout", stdout):
                    code = artifacts.main([
                        "collect", "--build-root", str(self.snap.root),
                        "--gradle-home", str(self.snap.home), "--out", str(output),
                    ])
                self.assertEqual(2, code)
                result = json.loads(stdout.getvalue())
                self.assertEqual({"error"}, set(result))
                self.assertIn(f"injected {stage} failure", result["error"])
                order = [artifacts.FILE_NAMES[key] for key in sorted(artifacts.KEYS)]
                self.assertEqual(order[:3], calls)
                self.assertEqual(sorted(order[:2]), sorted(path.name for path in output.iterdir()))
                for name in order[:2]:
                    self.assertEqual(expected[name], (output / name).read_bytes())

    def test_the_parser_must_be_the_running_judge(self):
        self.snap.write(artifacts.PARSER_PATH, b"# another judge\n")
        self.assertFails("not the judge running this collect", self.collect)
        self.assertEqual([], list(self.out.iterdir()))

    def test_equal_bytes_for_two_keys_are_refused(self):
        with unittest.mock.patch.object(artifacts, "normalize_graph", lambda *args: b"same\n"):
            self.assertFails("distinct bytes", self.collect)
        self.assertEqual([], list(self.out.iterdir()))

    def test_the_cli_reports_errors_as_exit_two(self):
        (self.snap.root / artifacts.CONFIGURATION_PATH).unlink()
        out = io.StringIO()
        with unittest.mock.patch("sys.stdout", out):
            code = artifacts.main(["collect", "--build-root", str(self.snap.root), "--gradle-home", str(self.snap.home),
                                   "--out", str(self.out)])
        self.assertEqual(2, code)
        self.assertIn("configuration.txt", json.loads(out.getvalue())["error"])


if __name__ == "__main__":
    unittest.main()
