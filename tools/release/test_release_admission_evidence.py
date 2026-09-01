import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from release_admission_evidence import (
    ARTIFACT_PATHS,
    EvidenceError,
    EXPECTED_CASES,
    validate_bundle,
)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


class EvidenceFixture:
    def __init__(self, root: Path):
        self.root = root
        self.repo = root / "repo"
        self.repo.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=self.repo, check=True)
        subprocess.run(
            ["git", "config", "user.email", "fixture@example.invalid"],
            cwd=self.repo,
            check=True,
        )
        subprocess.run(
            ["git", "config", "user.name", "Fixture"], cwd=self.repo, check=True
        )
        blob_contents = {
            "canonicalFirebaseFixture": b'{"fixture":true}\n',
            "runner": b"runner\n",
            "artifactVerifier": b"artifact verifier\n",
            "evidenceValidator": b"evidence validator\n",
            "debugManifest": b"debug manifest\n",
        }
        for key, content in blob_contents.items():
            path = self.repo / ARTIFACT_PATHS[key]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        subprocess.run(["git", "add", "."], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.repo, check=True)
        self.commit = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=self.repo,
            check=True,
            text=True,
            capture_output=True,
        ).stdout.strip()

        self.run_id = "release-admission-deadbee-r1"
        self.bundle = root / self.run_id
        self.bundle.mkdir()
        self.device = {
            "model": "SM-F711N",
            "fingerprint": (
                "samsung/b2qksx/b2q:15/AP3A.240905.015.A2/"
                "F711NKSSEKZE1:user/release-keys"
            ),
            "api": 35,
            "posture": "OPENED",
            "physicalSize": "1080x2640",
            "overrideSize": None,
            "orientation": 0,
            "viewportSize": "1080x2640",
            "wakefulness": "Awake",
        }
        self.preflight = {
            "runId": self.run_id,
            "observedAt": "2026-09-01T08:00:01+00:00",
            "device": self.device,
        }
        self.postflight = {
            "runId": self.run_id,
            "observedAt": "2026-09-01T08:00:09+00:00",
            "device": self.device,
        }
        self.instrumentation = {
            "schemaVersion": 1,
            "runId": self.run_id,
            "task": "adb-am-instrument",
            "runnerComponent": (
                "com.jay.fxi.test/androidx.test.runner.AndroidJUnitRunner"
            ),
            "tests": 4,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "durationSeconds": 1.25,
            "cases": [
                {"class": class_name, "method": method}
                for class_name, method in EXPECTED_CASES
            ],
            "rawSha256": "a" * 64,
        }
        write_json(self.bundle / "preflight.json", self.preflight)
        write_json(self.bundle / "postflight.json", self.postflight)
        write_json(self.bundle / "instrumentation-result.json", self.instrumentation)

        artifacts = {
            "targetDebugApk": {
                "path": ARTIFACT_PATHS["targetDebugApk"],
                "sha256": "1" * 64,
                "size": 100,
            },
            "androidTestApk": {
                "path": ARTIFACT_PATHS["androidTestApk"],
                "sha256": "2" * 64,
                "size": 200,
            },
        }
        for key in (
            "canonicalFirebaseFixture",
            "runner",
            "artifactVerifier",
            "evidenceValidator",
            "debugManifest",
        ):
            data = (self.repo / ARTIFACT_PATHS[key]).read_bytes()
            artifacts[key] = {
                "path": ARTIFACT_PATHS[key],
                "sha256": sha(data),
                "size": len(data),
            }
        artifacts["debugFirebaseInput"] = {
            **artifacts["canonicalFirebaseFixture"],
            "path": ARTIFACT_PATHS["debugFirebaseInput"],
        }
        evidence_files = {}
        for name in (
            "preflight.json",
            "postflight.json",
            "instrumentation-result.json",
        ):
            data = (self.bundle / name).read_bytes()
            evidence_files[name] = {"sha256": sha(data), "size": len(data)}

        self.run = {
            "schemaVersion": 1,
            "runId": self.run_id,
            "startedAt": "2026-09-01T08:00:00+00:00",
            "finishedAt": "2026-09-01T08:00:10+00:00",
            "serialRecorded": False,
            "sourceAtStart": {"commit": self.commit, "worktreeClean": True},
            "sourceAtEnd": {"commit": self.commit, "worktreeClean": True},
            "buildReturnCode": 0,
            "artifactVerificationReturnCode": 0,
            "preflight": self.device,
            "postflight": self.device,
            "artifactsBefore": artifacts,
            "artifactsAfter": copy.deepcopy(artifacts),
            "artifactsStable": True,
            "installedArtifacts": {
                key: {
                    "sha256": artifacts[key]["sha256"],
                    "size": artifacts[key]["size"],
                    "matchesStagedArtifact": True,
                }
                for key in ("targetDebugApk", "androidTestApk")
            },
            "instrumentationReturnCode": 0,
            "instrumentationResult": {
                "path": "instrumentation-result.json",
                **evidence_files["instrumentation-result.json"],
                "rawSha256": self.instrumentation["rawSha256"],
            },
            "processCold": {
                "forceStopPidAbsent": True,
                "startStatusOk": True,
                "mainActivityResumed": True,
                "composeViewPresent": True,
                "notificationExtrasSupplied": True,
                "applicationGuardObserved": True,
                "unavailableTitlePresent": True,
                "unavailableMessagePresent": True,
                "appClickableNodeCount": 0,
                "temporaryUiDumpRemoved": True,
                "forceStoppedAfterObservation": True,
                "pidAbsentAfterObservation": True,
                "trafficBefore": {
                    "forcedPoll": True,
                    "historyRows": 2,
                    "historyRxBytes": 100,
                    "historyTxBytes": 200,
                    "liveRows": 1,
                    "liveRxBytes": 100,
                    "liveTxBytes": 200,
                },
                "trafficAfterFirstPoll": {
                    "forcedPoll": True,
                    "historyRows": 2,
                    "historyRxBytes": 100,
                    "historyTxBytes": 200,
                    "liveRows": 1,
                    "liveRxBytes": 100,
                    "liveTxBytes": 200,
                },
                "trafficAfter": {
                    "forcedPoll": True,
                    "historyRows": 2,
                    "historyRxBytes": 100,
                    "historyTxBytes": 200,
                    "liveRows": 1,
                    "liveRxBytes": 100,
                    "liveTxBytes": 200,
                },
                "trafficDelta": {
                    "historyRxBytes": 0,
                    "historyTxBytes": 0,
                    "liveRxBytes": 0,
                    "liveTxBytes": 0,
                },
                "postStopPolls": 2,
                "postStopSnapshotsStable": True,
                "observationSeconds": 5.0,
            },
            "temporaryPackagesRemoved": True,
            "temporaryStagingRemoved": True,
            "temporarySourceSnapshotRemoved": True,
            "evidenceDirectory": self.run_id,
            "evidenceFiles": evidence_files,
            "exitCode": 0,
            "verdict": "pass",
        }
        self.write_run()

    def write_run(self):
        write_json(self.bundle / "run.json", self.run)


class ReleaseAdmissionEvidenceTest(unittest.TestCase):
    def make_fixture(self, temporary: str) -> EvidenceFixture:
        return EvidenceFixture(Path(temporary))

    def test_accepts_strict_commit_bound_bundle(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.make_fixture(temporary)
            self.assertEqual(
                {
                    "runId": fixture.run_id,
                    "sourceCommit": fixture.commit,
                    "verdict": "pass",
                },
                validate_bundle(fixture.bundle, fixture.repo),
            )

    def test_rejects_source_drift_network_bytes_and_cleanup_failure(self):
        mutations = (
            lambda run: run["sourceAtEnd"].update(commit="0" * 40),
            lambda run: run["processCold"]["trafficDelta"].update(liveRxBytes=1),
            lambda run: run.update(temporaryPackagesRemoved=False),
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                fixture = self.make_fixture(temporary)
                mutation(fixture.run)
                fixture.write_run()
                with self.assertRaises(EvidenceError):
                    validate_bundle(fixture.bundle, fixture.repo)

    def test_rejects_evidence_hash_drift_and_wrong_test_set(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.make_fixture(temporary)
            fixture.preflight["device"]["posture"] = "CLOSED"
            write_json(fixture.bundle / "preflight.json", fixture.preflight)
            with self.assertRaises(EvidenceError):
                validate_bundle(fixture.bundle, fixture.repo)

        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.make_fixture(temporary)
            fixture.instrumentation["cases"][0]["method"] = "wrong"
            write_json(
                fixture.bundle / "instrumentation-result.json", fixture.instrumentation
            )
            data = (fixture.bundle / "instrumentation-result.json").read_bytes()
            identity = {"sha256": sha(data), "size": len(data)}
            fixture.run["evidenceFiles"]["instrumentation-result.json"] = identity
            fixture.run["instrumentationResult"].update(identity)
            fixture.write_run()
            with self.assertRaises(EvidenceError):
                validate_bundle(fixture.bundle, fixture.repo)


if __name__ == "__main__":
    unittest.main()
