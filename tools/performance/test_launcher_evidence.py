import copy
import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import launcher_evidence
from launcher_evidence import EvidenceError, validate_bundle


RUN_ID = "s0f-launcher-smoke-test"
SOURCE_FIREBASE = b"firebase"
SOURCE_PREFLIGHT = b"preflight-runner"


def identity(path: str, payload: bytes) -> dict[str, object]:
    return {
        "path": path,
        "sha256": hashlib.sha256(payload).hexdigest(),
        "size": len(payload),
    }


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def git_head(repository: Path) -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repository,
        text=True,
        stdout=subprocess.PIPE,
        check=True,
    ).stdout.strip()


def valid_device(battery: int = 74) -> dict[str, object]:
    return {
        "model": launcher_evidence.EXPECTED_MODEL,
        "fingerprint": launcher_evidence.EXPECTED_FINGERPRINT,
        "api": launcher_evidence.EXPECTED_API,
        "posture": "OPENED",
        "physicalSize": launcher_evidence.EXPECTED_SIZE,
        "overrideSize": None,
        "orientation": 0,
        "supports120Hz": True,
        "activeRefreshRateHz": 120.0,
        "lowPower": False,
        "batteryLevel": battery,
        "powered": {
            "ac powered": False,
            "usb powered": False,
            "wireless powered": False,
            "dock powered": False,
        },
        "thermalStatus": 0,
    }


def create_valid_bundle(root: Path, commit: str) -> Path:
    bundle = root / RUN_ID
    bundle.mkdir()
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<testsuite name="com.jay.fxi.macrobenchmark.LauncherSmokeBenchmark" '
        'tests="1" failures="0" errors="0" skipped="0">\n'
        '  <testcase name="launcherSmoke" '
        'classname="com.jay.fxi.macrobenchmark.LauncherSmokeBenchmark" time="1.25" />\n'
        '</testsuite>\n'
    )
    (bundle / "launcher-result.xml").write_text(xml, encoding="utf-8")

    settings_during = copy.deepcopy(launcher_evidence.SETTING_TARGETS)
    settings_original = {
        **settings_during,
        "system/peak_refresh_rate": "null",
        "system/min_refresh_rate": "null",
    }
    pre_device = valid_device(74)
    post_device = valid_device(73)
    preflight = {
        **pre_device,
        "settings": settings_during,
        "serialRecorded": False,
        "runId": RUN_ID,
        "phase": "preflight",
        "observedAt": "2026-09-01T01:00:01+00:00",
    }
    postflight = {
        **post_device,
        "settings": settings_during,
        "serialRecorded": False,
        "runId": RUN_ID,
        "phase": "postflight",
        "observedAt": "2026-09-01T01:00:03+00:00",
    }
    write_json(bundle / "preflight.json", preflight)
    write_json(bundle / "postflight.json", postflight)

    target = identity(launcher_evidence.ARTIFACT_PATHS["targetBenchmarkApk"], b"target-apk")
    test = identity(launcher_evidence.ARTIFACT_PATHS["macrobenchmarkTestApk"], b"test-apk")
    firebase_input = identity(
        launcher_evidence.ARTIFACT_PATHS["benchmarkFirebaseInput"], SOURCE_FIREBASE
    )
    smoke_artifacts = {
        "targetBenchmarkApk": target,
        "macrobenchmarkTestApk": test,
        "benchmarkFirebaseInput": firebase_input,
    }
    all_artifacts = {
        **copy.deepcopy(smoke_artifacts),
        "canonicalFirebaseFixture": identity(
            launcher_evidence.ARTIFACT_PATHS["canonicalFirebaseFixture"], SOURCE_FIREBASE
        ),
        "devicePreflight": identity(
            launcher_evidence.ARTIFACT_PATHS["devicePreflight"], SOURCE_PREFLIGHT
        ),
    }
    evidence_files = {
        name: launcher_evidence._file_identity(bundle / name)
        for name in launcher_evidence.EVIDENCE_FILES
    }
    xml_identity = evidence_files["launcher-result.xml"]
    run = {
        "schemaVersion": 1,
        "runId": RUN_ID,
        "startedAt": "2026-09-01T01:00:00+00:00",
        "finishedAt": "2026-09-01T01:00:04+00:00",
        "serialRecorded": False,
        "launcherSmokeAttempted": True,
        "benchmarkBuildReturnCode": 0,
        "launcherSmokeReturnCode": 0,
        "sourceAtStart": {"commit": commit, "worktreeClean": True},
        "sourceAtEnd": {"commit": commit, "worktreeClean": True},
        "preflight": pre_device,
        "postflight": post_device,
        "settingsBefore": settings_original,
        "settingsDuring": settings_during,
        "settingsAfter": settings_original,
        "settingsRestored": True,
        "temporaryFirebaseFixtureRemoved": True,
        "temporaryPackagesRemoved": True,
        "deviceOutputRemoved": True,
        "temporaryStagingRemoved": True,
        "temporarySourceSnapshotRemoved": True,
        "launcherResult": {
            "class": launcher_evidence.LAUNCHER_CLASS,
            "method": launcher_evidence.LAUNCHER_METHOD,
            "task": launcher_evidence.LAUNCHER_TASK,
            "result": {
                "path": "launcher-result.xml",
                "sha256": xml_identity["sha256"],
                "size": xml_identity["size"],
                "sourceSha256": "1" * 64,
            },
            "tests": 1,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
        },
        "artifactsBeforeSmoke": copy.deepcopy(smoke_artifacts),
        "artifactsAfterSmoke": copy.deepcopy(smoke_artifacts),
        "artifactsStableAcrossSmoke": True,
        "artifacts": all_artifacts,
        "deviceInstalledArtifacts": {
            key: {
                "sha256": smoke_artifacts[key]["sha256"],
                "size": smoke_artifacts[key]["size"],
                "matchesStagedArtifact": True,
            }
            for key in launcher_evidence.INSTALLED_ARTIFACTS
        },
        "activeRefreshBeforeSmoke": {
            "displayManagerHz": 120.00001,
            "surfaceFlingerHz": 120.0,
            "observedAt": "2026-09-01T01:00:01.5+00:00",
        },
        "activeRefreshAfterSmoke": {
            "displayManagerHz": 120.00001,
            "surfaceFlingerHz": 120.0,
            "observedAt": "2026-09-01T01:00:02.5+00:00",
        },
        "exitCode": 0,
        "verdict": "pass",
        "evidenceDirectory": f"build/benchmark-preflight/{RUN_ID}",
        "evidenceFiles": evidence_files,
    }
    write_json(bundle / "run.json", run)
    return bundle


def read_run(bundle: Path) -> dict[str, object]:
    return json.loads((bundle / "run.json").read_text(encoding="utf-8"))


def update_run(bundle: Path, mutate) -> None:
    run = read_run(bundle)
    mutate(run)
    write_json(bundle / "run.json", run)


def refresh_evidence_identity(bundle: Path, filename: str) -> None:
    update_run(
        bundle,
        lambda run: run["evidenceFiles"].__setitem__(
            filename, launcher_evidence._file_identity(bundle / filename)
        ),
    )


def replace_firebase_identities(run: dict[str, object]) -> None:
    replacement = {
        "sha256": "2" * 64,
        "size": 123,
    }
    for section in ("artifactsBeforeSmoke", "artifactsAfterSmoke", "artifacts"):
        run[section]["benchmarkFirebaseInput"].update(replacement)
    run["artifacts"]["canonicalFirebaseFixture"].update(replacement)


class LauncherEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository_context = tempfile.TemporaryDirectory()
        cls.repository = Path(cls.repository_context.name)
        (cls.repository / "tools" / "performance").mkdir(parents=True)
        (cls.repository / "ci").mkdir()
        (cls.repository / launcher_evidence.ARTIFACT_PATHS["devicePreflight"]).write_bytes(
            SOURCE_PREFLIGHT
        )
        (
            cls.repository / launcher_evidence.ARTIFACT_PATHS["canonicalFirebaseFixture"]
        ).write_bytes(SOURCE_FIREBASE)
        subprocess.run(["git", "init", "-q"], cwd=cls.repository, check=True)
        subprocess.run(["git", "add", "."], cwd=cls.repository, check=True)
        subprocess.run(
            [
                "git",
                "-c",
                "user.name=Evidence Test",
                "-c",
                "user.email=evidence@example.invalid",
                "commit",
                "-qm",
                "fixture source",
            ],
            cwd=cls.repository,
            check=True,
        )
        cls.commit = git_head(cls.repository)

    @classmethod
    def tearDownClass(cls):
        cls.repository_context.cleanup()

    def create_bundle(self, root: Path) -> Path:
        return create_valid_bundle(root, self.commit)

    def validate(self, bundle: Path) -> dict[str, str]:
        return validate_bundle(bundle, repository_root=self.repository)

    def test_valid_bundle_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            bundle = self.create_bundle(Path(temp))
            result = self.validate(bundle)
        self.assertEqual(RUN_ID, result["runId"])
        self.assertEqual("pass", result["verdict"])

    def test_bundle_membership_and_symlinks_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for mutation in ("missing", "extra", "symlink"):
                with self.subTest(mutation=mutation):
                    bundle = self.create_bundle(root)
                    if mutation == "missing":
                        (bundle / "postflight.json").unlink()
                    elif mutation == "extra":
                        (bundle / "raw-utp-output.txt").write_text("raw", encoding="utf-8")
                    else:
                        original = bundle / "launcher-result.xml"
                        target = root / "outside.xml"
                        target.write_bytes(original.read_bytes())
                        original.unlink()
                        original.symlink_to(target)
                    with self.assertRaises(EvidenceError):
                        self.validate(bundle)
                    for child in bundle.iterdir():
                        child.unlink()
                    bundle.rmdir()

    def test_duplicate_json_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            bundle = self.create_bundle(Path(temp))
            raw = (bundle / "run.json").read_text(encoding="utf-8")
            (bundle / "run.json").write_text(
                raw.replace('"schemaVersion": 1,', '"schemaVersion": 1,\n  "schemaVersion": 1,'),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(EvidenceError, "duplicate key"):
                self.validate(bundle)

    def test_terminal_success_and_cleanup_flags_are_mandatory(self):
        mutations = {
            "verdict": lambda run: run.__setitem__("verdict", "fail"),
            "exit": lambda run: run.__setitem__("exitCode", 2),
            "build": lambda run: run.__setitem__("benchmarkBuildReturnCode", 1),
            "instrumentation": lambda run: run.__setitem__("launcherSmokeReturnCode", 1),
            "restore": lambda run: run.__setitem__("settingsRestored", False),
            "firebase": lambda run: run.__setitem__("temporaryFirebaseFixtureRemoved", False),
            "packages": lambda run: run.__setitem__("temporaryPackagesRemoved", False),
            "device-output": lambda run: run.__setitem__("deviceOutputRemoved", False),
            "staging": lambda run: run.__setitem__("temporaryStagingRemoved", False),
            "source-snapshot": lambda run: run.__setitem__("temporarySourceSnapshotRemoved", False),
            "error-field": lambda run: run.__setitem__("restoreError", "failed"),
        }
        for label, mutation in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                bundle = self.create_bundle(Path(temp))
                update_run(bundle, mutation)
                with self.assertRaises(EvidenceError):
                    self.validate(bundle)

    def test_source_must_remain_the_same_clean_real_commit(self):
        mutations = {
            "dirty-start": lambda run: run["sourceAtStart"].__setitem__("worktreeClean", False),
            "dirty-end": lambda run: run["sourceAtEnd"].__setitem__("worktreeClean", False),
            "changed": lambda run: run["sourceAtEnd"].__setitem__("commit", "0" * 40),
            "nonexistent": lambda run: (
                run["sourceAtStart"].__setitem__("commit", "0" * 40),
                run["sourceAtEnd"].__setitem__("commit", "0" * 40),
            ),
        }
        for label, mutation in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                bundle = self.create_bundle(Path(temp))
                update_run(bundle, mutation)
                with self.assertRaises(EvidenceError):
                    self.validate(bundle)

    def test_artifacts_are_bound_before_after_installed_and_to_firebase(self):
        mutations = {
            "unstable-flag": lambda run: run.__setitem__("artifactsStableAcrossSmoke", False),
            "changed-after": lambda run: run["artifactsAfterSmoke"]["targetBenchmarkApk"].__setitem__("sha256", "2" * 64),
            "summary-alias": lambda run: run["artifacts"]["targetBenchmarkApk"].__setitem__("sha256", "2" * 64),
            "firebase-drift": lambda run: run["artifacts"]["canonicalFirebaseFixture"].__setitem__("sha256", "2" * 64),
            "installed-drift": lambda run: run["deviceInstalledArtifacts"]["targetBenchmarkApk"].__setitem__("sha256", "2" * 64),
            "installed-unmatched": lambda run: run["deviceInstalledArtifacts"]["macrobenchmarkTestApk"].__setitem__("matchesStagedArtifact", False),
            "zero-size": lambda run: run["artifactsBeforeSmoke"]["targetBenchmarkApk"].__setitem__("size", 0),
            "missing": lambda run: run["artifacts"].pop("devicePreflight"),
            "runner-blob": lambda run: run["artifacts"]["devicePreflight"].__setitem__("sha256", "2" * 64),
            "fixture-blob": replace_firebase_identities,
        }
        for label, mutation in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                bundle = self.create_bundle(Path(temp))
                update_run(bundle, mutation)
                with self.assertRaises(EvidenceError):
                    self.validate(bundle)

    def test_evidence_file_hash_size_and_launcher_binding_are_mandatory(self):
        mutations = {
            "hash": lambda run: run["evidenceFiles"]["preflight.json"].__setitem__("sha256", "2" * 64),
            "size": lambda run: run["evidenceFiles"]["postflight.json"].__setitem__("size", 1),
            "launcher-binding": lambda run: run["launcherResult"]["result"].__setitem__("sha256", "2" * 64),
            "source-hash": lambda run: run["launcherResult"]["result"].__setitem__("sourceSha256", "bad"),
        }
        for label, mutation in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                bundle = self.create_bundle(Path(temp))
                update_run(bundle, mutation)
                with self.assertRaises(EvidenceError):
                    self.validate(bundle)

    def test_every_frozen_d31_axis_is_fail_closed(self):
        mutations = {
            "model": ("model", "other"),
            "fingerprint": ("fingerprint", "other"),
            "api": ("api", 34),
            "posture": ("posture", "CLOSED"),
            "size": ("physicalSize", "512x260"),
            "override": ("overrideSize", "720x1760"),
            "orientation": ("orientation", 1),
            "refresh": ("supports120Hz", False),
            "active-refresh": ("activeRefreshRateHz", 60.0),
            "low-power": ("lowPower", True),
            "battery": ("batteryLevel", 29),
            "thermal": ("thermalStatus", 1),
        }
        for label, (key, value) in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                bundle = self.create_bundle(Path(temp))
                snapshot = json.loads((bundle / "preflight.json").read_text(encoding="utf-8"))
                snapshot[key] = value
                write_json(bundle / "preflight.json", snapshot)
                update_run(bundle, lambda run: run["preflight"].__setitem__(key, value))
                refresh_evidence_identity(bundle, "preflight.json")
                with self.assertRaises(EvidenceError):
                    self.validate(bundle)

        with tempfile.TemporaryDirectory() as temp:
            bundle = self.create_bundle(Path(temp))
            snapshot = json.loads((bundle / "postflight.json").read_text(encoding="utf-8"))
            snapshot["powered"]["usb powered"] = True
            write_json(bundle / "postflight.json", snapshot)
            update_run(
                bundle,
                lambda run: run["postflight"]["powered"].__setitem__("usb powered", True),
            )
            refresh_evidence_identity(bundle, "postflight.json")
            with self.assertRaises(EvidenceError):
                self.validate(bundle)

    def test_before_and_after_active_refresh_oracles_are_fail_closed(self):
        mutations = {
            "before-manager-60": lambda run: run["activeRefreshBeforeSmoke"].__setitem__(
                "displayManagerHz", 60.0
            ),
            "after-surfaceflinger-60": lambda run: run[
                "activeRefreshAfterSmoke"
            ].__setitem__("surfaceFlingerHz", 60.0),
            "oracle-disagreement": lambda run: run[
                "activeRefreshBeforeSmoke"
            ].__setitem__("displayManagerHz", 119.0),
            "timestamp-outside-bracket": lambda run: run[
                "activeRefreshBeforeSmoke"
            ].__setitem__("observedAt", "2026-09-01T01:00:03.5+00:00"),
        }
        for label, mutation in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                bundle = self.create_bundle(Path(temp))
                update_run(bundle, mutation)
                with self.assertRaises(EvidenceError):
                    self.validate(bundle)

    def test_measurement_settings_and_restore_are_bound(self):
        mutations = {
            "during": lambda run: run["settingsDuring"].__setitem__("system/peak_refresh_rate", "60.0"),
            "restored-drift": lambda run: run["settingsAfter"].__setitem__("system/peak_refresh_rate", "60.0"),
            "missing-key": lambda run: run["settingsBefore"].pop("system/min_refresh_rate"),
        }
        for label, mutation in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                bundle = self.create_bundle(Path(temp))
                update_run(bundle, mutation)
                with self.assertRaises(EvidenceError):
                    self.validate(bundle)

    def test_launcher_xml_must_be_one_exact_sanitized_green_test(self):
        variants = {
            "failure": '<failure message="boom" />',
            "system-out": "<system-out>/Users/private/work</system-out>",
            "serial": "<system-out>R5CRC3SH38V</system-out>",
            "second-case": (
                '<testcase name="other" '
                'classname="com.jay.fxi.macrobenchmark.LauncherSmokeBenchmark" />'
            ),
        }
        for label, addition in variants.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                bundle = self.create_bundle(Path(temp))
                xml = (bundle / "launcher-result.xml").read_text(encoding="utf-8")
                xml = xml.replace("</testsuite>", f"{addition}</testsuite>")
                (bundle / "launcher-result.xml").write_text(xml, encoding="utf-8")
                actual = launcher_evidence._file_identity(bundle / "launcher-result.xml")
                update_run(
                    bundle,
                    lambda run: (
                        run["evidenceFiles"].__setitem__("launcher-result.xml", actual),
                        run["launcherResult"]["result"].update(actual),
                    ),
                )
                with self.assertRaises(EvidenceError):
                    self.validate(bundle)

    def test_run_id_timestamps_paths_and_serials_cannot_leak_or_drift(self):
        mutations = {
            "run-id": lambda run: run.__setitem__("runId", "Different"),
            "directory": lambda run: run.__setitem__("evidenceDirectory", "/Users/private/evidence"),
            "time-order": lambda run: run.__setitem__("finishedAt", "2026-09-01T00:59:00+00:00"),
            "serial-field": lambda run: run.__setitem__("deviceSerial", "R5CRC3SH38V"),
            "serial-value": lambda run: run["sourceAtStart"].__setitem__("note", "adb-R5CRC3SH38V"),
        }
        for label, mutation in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                bundle = self.create_bundle(Path(temp))
                update_run(bundle, mutation)
                with self.assertRaises(EvidenceError):
                    self.validate(bundle)

    def test_cli_returns_zero_for_valid_and_two_for_invalid(self):
        with tempfile.TemporaryDirectory() as temp:
            bundle = self.create_bundle(Path(temp))
            output = io.StringIO()
            errors = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                with mock.patch.object(launcher_evidence, "ROOT", self.repository):
                    self.assertEqual(0, launcher_evidence.main(["validate", str(bundle)]))
                    (bundle / "postflight.json").write_text("{}\n", encoding="utf-8")
                    self.assertEqual(2, launcher_evidence.main(["validate", str(bundle)]))
            self.assertIn('"verdict": "pass"', output.getvalue())
            self.assertIn("invalid launcher evidence", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
