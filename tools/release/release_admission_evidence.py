#!/usr/bin/env python3
"""Strict validator for promoted S0-g release-admission device evidence."""

from __future__ import annotations

import argparse
from pathlib import Path, PurePosixPath
import re
import sys
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[2]
PERFORMANCE_TOOLS = ROOT / "tools" / "performance"
if str(PERFORMANCE_TOOLS) not in sys.path:
    sys.path.insert(0, str(PERFORMANCE_TOOLS))

from launcher_evidence import (  # noqa: E402
    EvidenceError,
    _assert_commit_exists,
    _exact_keys,
    _file_identity,
    _git_blob_identity,
    _load_json,
    _parse_time,
    _positive_int,
    _require_bool,
    _require_int,
    _safe_relative_path,
    _sha,
    _walk_json,
)


BUNDLE_FILES = {
    "run.json",
    "preflight.json",
    "postflight.json",
    "instrumentation-result.json",
}
EVIDENCE_FILES = BUNDLE_FILES - {"run.json"}
RUN_ID = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")
SOURCE_FIELDS = {"commit", "worktreeClean"}
DEVICE_FIELDS = {
    "model",
    "fingerprint",
    "api",
    "posture",
    "physicalSize",
    "overrideSize",
    "orientation",
    "viewportSize",
    "wakefulness",
}
SNAPSHOT_FIELDS = {"runId", "observedAt", "device"}
IDENTITY_FIELDS = {"path", "sha256", "size"}
ARTIFACT_KEYS = {
    "targetDebugApk",
    "androidTestApk",
    "canonicalFirebaseFixture",
    "debugFirebaseInput",
    "runner",
    "artifactVerifier",
    "evidenceValidator",
    "debugManifest",
}
ARTIFACT_PATHS = {
    "targetDebugApk": "app/build/outputs/apk/debug/app-debug.apk",
    "androidTestApk": "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    "canonicalFirebaseFixture": "ci/google-services.ci-fixture.json",
    "debugFirebaseInput": "app/src/debug/google-services.json",
    "runner": "tools/release/device_release_admission.py",
    "artifactVerifier": "tools/release/verify_release_admission_artifacts.py",
    "evidenceValidator": "tools/release/release_admission_evidence.py",
    "debugManifest": "app/src/debug/AndroidManifest.xml",
}
GIT_BLOB_ARTIFACTS = {
    "canonicalFirebaseFixture",
    "runner",
    "artifactVerifier",
    "evidenceValidator",
    "debugManifest",
}
INSTALLED_FIELDS = {"sha256", "size", "matchesStagedArtifact"}
TRAFFIC_FIELDS = {
    "forcedPoll",
    "historyRows",
    "historyRxBytes",
    "historyTxBytes",
    "liveRows",
    "liveRxBytes",
    "liveTxBytes",
}
TRAFFIC_DELTA_FIELDS = {
    "historyRxBytes",
    "historyTxBytes",
    "liveRxBytes",
    "liveTxBytes",
}
PROCESS_COLD_FIELDS = {
    "forceStopPidAbsent",
    "startStatusOk",
    "mainActivityResumed",
    "composeViewPresent",
    "notificationExtrasSupplied",
    "applicationGuardObserved",
    "unavailableTitlePresent",
    "unavailableMessagePresent",
    "appClickableNodeCount",
    "temporaryUiDumpRemoved",
    "forceStoppedAfterObservation",
    "pidAbsentAfterObservation",
    "trafficBefore",
    "trafficAfterFirstPoll",
    "trafficAfter",
    "trafficDelta",
    "postStopPolls",
    "postStopSnapshotsStable",
    "observationSeconds",
}
INSTRUMENTATION_FIELDS = {
    "schemaVersion",
    "runId",
    "task",
    "runnerComponent",
    "tests",
    "failures",
    "errors",
    "skipped",
    "durationSeconds",
    "cases",
    "rawSha256",
}
EXPECTED_CASES = (
    (
        "com.jay.fxi.ExampleInstrumentedTest",
        "useAppContext",
    ),
    (
        "com.jay.fxi.ReleaseAdmissionOffEntrypointTest",
        "freshActivityRecreationAndWarmNotificationIntentRemainUnavailable",
    ),
    (
        "com.jay.fxi.ReleaseAdmissionOffEntrypointTest",
        "FCMCallbacksAndLocalEventBusHaveNoAppOwnedSideEffects",
    ),
    (
        "com.jay.fxi.ReleaseAdmissionOffUiTest",
        "OFF_rendersOnlyUnavailableWithoutResolvingRuntimeProviders",
    ),
)
RUN_FIELDS = {
    "schemaVersion",
    "runId",
    "startedAt",
    "finishedAt",
    "serialRecorded",
    "sourceAtStart",
    "sourceAtEnd",
    "buildReturnCode",
    "artifactVerificationReturnCode",
    "preflight",
    "postflight",
    "artifactsBefore",
    "artifactsAfter",
    "artifactsStable",
    "installedArtifacts",
    "instrumentationReturnCode",
    "instrumentationResult",
    "processCold",
    "temporaryPackagesRemoved",
    "temporaryStagingRemoved",
    "temporarySourceSnapshotRemoved",
    "evidenceDirectory",
    "evidenceFiles",
    "exitCode",
    "verdict",
}


def _source(value: Any, label: str) -> str:
    source = _exact_keys(value, SOURCE_FIELDS, label)
    commit = source["commit"]
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise EvidenceError(f"{label}.commit must be a lowercase 40-hex commit")
    _require_bool(source["worktreeClean"], True, f"{label}.worktreeClean")
    return commit


def _nonnegative_int(value: Any, label: str) -> int:
    if type(value) is not int or value < 0:
        raise EvidenceError(f"{label} must be a non-negative integer")
    return value


def _duration(value: Any, label: str) -> float:
    if type(value) not in {int, float} or isinstance(value, bool) or value <= 0:
        raise EvidenceError(f"{label} must be a positive finite duration")
    return float(value)


def _device(value: Any, label: str) -> Mapping[str, Any]:
    device = _exact_keys(value, DEVICE_FIELDS, label)
    expected = {
        "model": "SM-F711N",
        "fingerprint": (
            "samsung/b2qksx/b2q:15/AP3A.240905.015.A2/"
            "F711NKSSEKZE1:user/release-keys"
        ),
        "api": 35,
        "posture": "OPENED",
        "physicalSize": "1080x2640",
        "orientation": 0,
        "viewportSize": "1080x2640",
        "wakefulness": "Awake",
    }
    for key, expected_value in expected.items():
        if device[key] != expected_value:
            raise EvidenceError(f"{label}.{key} differs from the frozen S0 device")
    if device["overrideSize"] not in {None, "1080x2640"}:
        raise EvidenceError(f"{label}.overrideSize is not the frozen display size")
    return device


def _snapshot(value: Any, label: str, run_id: str) -> tuple[Mapping[str, Any], Any]:
    snapshot = _exact_keys(value, SNAPSHOT_FIELDS, label)
    if snapshot["runId"] != run_id:
        raise EvidenceError(f"{label}.runId differs from run.json")
    observed = _parse_time(snapshot["observedAt"], f"{label}.observedAt")
    return _device(snapshot["device"], f"{label}.device"), observed


def _artifact(value: Any, label: str, expected_path: str) -> dict[str, Any]:
    artifact = _exact_keys(value, IDENTITY_FIELDS, label)
    path = _safe_relative_path(artifact["path"], f"{label}.path", expected_path)
    return {
        "path": path,
        "sha256": _sha(artifact["sha256"], f"{label}.sha256"),
        "size": _positive_int(artifact["size"], f"{label}.size"),
    }


def _artifact_map(value: Any, label: str) -> dict[str, dict[str, Any]]:
    artifacts = _exact_keys(value, ARTIFACT_KEYS, label)
    return {
        key: _artifact(artifacts[key], f"{label}.{key}", ARTIFACT_PATHS[key])
        for key in sorted(ARTIFACT_KEYS)
    }


def _traffic(value: Any, label: str) -> dict[str, Any]:
    traffic = _exact_keys(value, TRAFFIC_FIELDS, label)
    _require_bool(traffic["forcedPoll"], True, f"{label}.forcedPoll")
    parsed: dict[str, Any] = {"forcedPoll": True}
    for key in sorted(TRAFFIC_FIELDS - {"forcedPoll"}):
        parsed[key] = _nonnegative_int(traffic[key], f"{label}.{key}")
    if parsed["liveRows"] not in {0, 1}:
        raise EvidenceError(f"{label}.liveRows must be zero or one")
    return parsed


def _instrumentation(value: Any, run_id: str) -> None:
    result = _exact_keys(value, INSTRUMENTATION_FIELDS, "instrumentation-result.json")
    _require_int(result["schemaVersion"], 1, "instrumentation.schemaVersion")
    if result["runId"] != run_id:
        raise EvidenceError("instrumentation runId differs from run.json")
    if result["task"] != "adb-am-instrument":
        raise EvidenceError("instrumentation task is not direct adb-am-instrument")
    if result["runnerComponent"] != (
        "com.jay.fxi.test/androidx.test.runner.AndroidJUnitRunner"
    ):
        raise EvidenceError("instrumentation runner component differs")
    for key, expected in (
        ("tests", 4),
        ("failures", 0),
        ("errors", 0),
        ("skipped", 0),
    ):
        _require_int(result[key], expected, f"instrumentation.{key}")
    _duration(result["durationSeconds"], "instrumentation.durationSeconds")
    _sha(result["rawSha256"], "instrumentation.rawSha256")
    cases = result["cases"]
    if not isinstance(cases, list) or len(cases) != len(EXPECTED_CASES):
        raise EvidenceError("instrumentation cases must contain exactly four entries")
    actual: list[tuple[str, str]] = []
    for index, case in enumerate(cases):
        item = _exact_keys(case, {"class", "method"}, f"instrumentation.cases[{index}]")
        if not all(isinstance(item[key], str) for key in ("class", "method")):
            raise EvidenceError("instrumentation case identifiers must be strings")
        actual.append((item["class"], item["method"]))
    if tuple(actual) != EXPECTED_CASES:
        raise EvidenceError("instrumentation cases differ from the exact S0-g set")


def validate_bundle(bundle: Path, repository_root: Path | None = None) -> dict[str, str]:
    bundle = Path(bundle)
    repository_root = ROOT if repository_root is None else Path(repository_root)
    if bundle.is_symlink() or not bundle.is_dir():
        raise EvidenceError("evidence bundle must be a real directory")
    entries = {entry.name for entry in bundle.iterdir()}
    if entries != BUNDLE_FILES:
        raise EvidenceError(
            f"evidence bundle files differ (missing={sorted(BUNDLE_FILES - entries)}, "
            f"extra={sorted(entries - BUNDLE_FILES)})"
        )
    for name in sorted(BUNDLE_FILES):
        path = bundle / name
        if path.is_symlink() or not path.is_file():
            raise EvidenceError(f"{name} must be a non-symlink regular file")
        if path.stat().st_size <= 0 or path.stat().st_size > 4 * 1024 * 1024:
            raise EvidenceError(f"{name} has an invalid evidence-file size")

    run = _load_json(bundle / "run.json")
    preflight = _load_json(bundle / "preflight.json")
    postflight = _load_json(bundle / "postflight.json")
    instrumentation = _load_json(bundle / "instrumentation-result.json")
    for name, document in (
        ("run.json", run),
        ("preflight.json", preflight),
        ("postflight.json", postflight),
        ("instrumentation-result.json", instrumentation),
    ):
        _walk_json(document, name)

    run = _exact_keys(run, RUN_FIELDS, "run.json")
    _require_int(run["schemaVersion"], 1, "run.schemaVersion")
    run_id = run["runId"]
    if not isinstance(run_id, str) or not RUN_ID.fullmatch(run_id):
        raise EvidenceError("runId is not a stable non-secret id")
    _require_bool(run["serialRecorded"], False, "serialRecorded")
    for key in (
        "buildReturnCode",
        "artifactVerificationReturnCode",
        "instrumentationReturnCode",
        "exitCode",
    ):
        _require_int(run[key], 0, key)
    if run["verdict"] != "pass":
        raise EvidenceError("verdict must be pass")
    for key in (
        "artifactsStable",
        "temporaryPackagesRemoved",
        "temporaryStagingRemoved",
        "temporarySourceSnapshotRemoved",
    ):
        _require_bool(run[key], True, key)

    source_start = _source(run["sourceAtStart"], "sourceAtStart")
    source_end = _source(run["sourceAtEnd"], "sourceAtEnd")
    if source_start != source_end:
        raise EvidenceError("source commit changed during S0-g evidence run")
    _assert_commit_exists(source_start, repository_root)
    directory = _safe_relative_path(run["evidenceDirectory"], "evidenceDirectory")
    if PurePosixPath(directory).name != run_id:
        raise EvidenceError("evidenceDirectory basename must equal runId")

    pre_device, pre_time = _snapshot(preflight, "preflight.json", run_id)
    post_device, post_time = _snapshot(postflight, "postflight.json", run_id)
    if run["preflight"] != pre_device or run["postflight"] != post_device:
        raise EvidenceError("run.json device state differs from pre/post snapshots")
    if pre_device != post_device:
        raise EvidenceError("frozen device state drifted during S0-g run")
    started = _parse_time(run["startedAt"], "startedAt")
    finished = _parse_time(run["finishedAt"], "finishedAt")
    if not started <= pre_time <= post_time <= finished:
        raise EvidenceError("evidence timestamps are not monotonic")

    evidence_files = _exact_keys(run["evidenceFiles"], EVIDENCE_FILES, "evidenceFiles")
    verified_files: dict[str, dict[str, Any]] = {}
    for name in sorted(EVIDENCE_FILES):
        recorded = _exact_keys(
            evidence_files[name], {"sha256", "size"}, f"evidenceFiles.{name}"
        )
        identity = {
            "sha256": _sha(recorded["sha256"], f"evidenceFiles.{name}.sha256"),
            "size": _positive_int(recorded["size"], f"evidenceFiles.{name}.size"),
        }
        actual = _file_identity(bundle / name)
        if actual != identity:
            raise EvidenceError(f"{name} differs from its recorded hash/size")
        verified_files[name] = actual

    _instrumentation(instrumentation, run_id)
    result_identity = _exact_keys(
        run["instrumentationResult"],
        {"path", "sha256", "size", "rawSha256"},
        "instrumentationResult",
    )
    _safe_relative_path(
        result_identity["path"],
        "instrumentationResult.path",
        "instrumentation-result.json",
    )
    for key in ("sha256", "rawSha256"):
        _sha(result_identity[key], f"instrumentationResult.{key}")
    _positive_int(result_identity["size"], "instrumentationResult.size")
    if {
        "sha256": result_identity["sha256"],
        "size": result_identity["size"],
    } != verified_files["instrumentation-result.json"]:
        raise EvidenceError("instrumentation result differs from its bound file")
    if result_identity["rawSha256"] != instrumentation["rawSha256"]:
        raise EvidenceError("raw instrumentation digest differs across evidence files")

    before = _artifact_map(run["artifactsBefore"], "artifactsBefore")
    after = _artifact_map(run["artifactsAfter"], "artifactsAfter")
    if before != after:
        raise EvidenceError("build artifacts changed during S0-g device run")
    if before["canonicalFirebaseFixture"]["sha256"] != before[
        "debugFirebaseInput"
    ]["sha256"] or before["canonicalFirebaseFixture"]["size"] != before[
        "debugFirebaseInput"
    ]["size"]:
        raise EvidenceError("debug Firebase input differs from canonical fixture")
    if before["targetDebugApk"]["sha256"] == before["androidTestApk"]["sha256"]:
        raise EvidenceError("target and test APK identities must be distinct")
    for key in GIT_BLOB_ARTIFACTS:
        blob = _git_blob_identity(source_start, ARTIFACT_PATHS[key], repository_root)
        recorded = {field: before[key][field] for field in ("sha256", "size")}
        if blob != recorded:
            raise EvidenceError(f"artifactsBefore.{key} differs from source commit")

    installed = _exact_keys(
        run["installedArtifacts"],
        {"targetDebugApk", "androidTestApk"},
        "installedArtifacts",
    )
    for key in ("targetDebugApk", "androidTestApk"):
        item = _exact_keys(installed[key], INSTALLED_FIELDS, f"installedArtifacts.{key}")
        _require_bool(
            item["matchesStagedArtifact"], True, f"installedArtifacts.{key}.matches"
        )
        if {
            "sha256": _sha(item["sha256"], f"installedArtifacts.{key}.sha256"),
            "size": _positive_int(item["size"], f"installedArtifacts.{key}.size"),
        } != {field: before[key][field] for field in ("sha256", "size")}:
            raise EvidenceError(f"installed {key} differs from staged APK")

    process = _exact_keys(run["processCold"], PROCESS_COLD_FIELDS, "processCold")
    for key in (
        "forceStopPidAbsent",
        "startStatusOk",
        "mainActivityResumed",
        "composeViewPresent",
        "notificationExtrasSupplied",
        "applicationGuardObserved",
        "unavailableTitlePresent",
        "unavailableMessagePresent",
        "temporaryUiDumpRemoved",
        "forceStoppedAfterObservation",
        "pidAbsentAfterObservation",
        "postStopSnapshotsStable",
    ):
        _require_bool(process[key], True, f"processCold.{key}")
    _require_int(process["appClickableNodeCount"], 0, "processCold.appClickableNodeCount")
    _require_int(process["postStopPolls"], 2, "processCold.postStopPolls")
    _duration(process["observationSeconds"], "processCold.observationSeconds")
    traffic_before = _traffic(process["trafficBefore"], "processCold.trafficBefore")
    traffic_after_first = _traffic(
        process["trafficAfterFirstPoll"], "processCold.trafficAfterFirstPoll"
    )
    traffic_after = _traffic(process["trafficAfter"], "processCold.trafficAfter")
    if traffic_after_first != traffic_after:
        raise EvidenceError("post-stop UID traffic snapshots are not stable")
    delta = _exact_keys(
        process["trafficDelta"], TRAFFIC_DELTA_FIELDS, "processCold.trafficDelta"
    )
    for key in sorted(TRAFFIC_DELTA_FIELDS):
        _require_int(delta[key], 0, f"processCold.trafficDelta.{key}")
        if traffic_after[key] < traffic_before[key]:
            raise EvidenceError(f"processCold {key} counter decreased")
        if traffic_after[key] - traffic_before[key] != delta[key]:
            raise EvidenceError(f"processCold {key} delta is arithmetically false")

    return {"runId": run_id, "sourceCommit": source_start, "verdict": "pass"}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate",))
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args(argv)
    try:
        result = validate_bundle(args.bundle)
    except EvidenceError as error:
        print(f"invalid release-admission evidence: {error}", file=sys.stderr)
        return 2
    import json

    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
