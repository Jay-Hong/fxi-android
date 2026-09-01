#!/usr/bin/env python3
"""Fail-closed validator for promoted S0-f launcher-smoke evidence.

Only the four sanitized files produced by ``device_preflight.py`` belong in a
durable bundle. Raw Android Test Orchestrator/UTP output is intentionally not a
member of this contract because it can contain an ADB serial and local paths.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[2]

BUNDLE_FILES = {
    "run.json",
    "preflight.json",
    "postflight.json",
    "launcher-result.xml",
}
EVIDENCE_FILES = {
    "preflight.json",
    "postflight.json",
    "launcher-result.xml",
}
RUN_FIELDS = {
    "schemaVersion",
    "runId",
    "startedAt",
    "finishedAt",
    "serialRecorded",
    "launcherSmokeAttempted",
    "benchmarkBuildReturnCode",
    "launcherSmokeReturnCode",
    "sourceAtStart",
    "sourceAtEnd",
    "preflight",
    "postflight",
    "settingsBefore",
    "settingsDuring",
    "settingsAfter",
    "settingsRestored",
    "temporaryFirebaseFixtureRemoved",
    "temporaryPackagesRemoved",
    "deviceOutputRemoved",
    "temporaryStagingRemoved",
    "temporarySourceSnapshotRemoved",
    "launcherResult",
    "artifactsBeforeSmoke",
    "artifactsAfterSmoke",
    "artifactsStableAcrossSmoke",
    "artifacts",
    "deviceInstalledArtifacts",
    "activeRefreshBeforeSmoke",
    "activeRefreshAfterSmoke",
    "exitCode",
    "verdict",
    "evidenceDirectory",
    "evidenceFiles",
}
SOURCE_FIELDS = {"commit", "worktreeClean"}
IMMUTABLE_DEVICE_FIELDS = {
    "model",
    "fingerprint",
    "api",
    "posture",
    "physicalSize",
    "overrideSize",
    "orientation",
    "supports120Hz",
    "activeRefreshRateHz",
    "lowPower",
    "batteryLevel",
    "powered",
    "thermalStatus",
}
SNAPSHOT_FIELDS = IMMUTABLE_DEVICE_FIELDS | {
    "settings",
    "serialRecorded",
    "runId",
    "phase",
    "observedAt",
}
SETTING_TARGETS = {
    "system/peak_refresh_rate": "120.0",
    "system/min_refresh_rate": "120.0",
    "global/window_animation_scale": "1.0",
    "global/transition_animation_scale": "1.0",
    "global/animator_duration_scale": "1.0",
}
SETTING_KEYS = set(SETTING_TARGETS)
ACTIVE_REFRESH_FIELDS = {"displayManagerHz", "surfaceFlingerHz", "observedAt"}
EXPECTED_MODEL = "SM-F711N"
EXPECTED_API = 35
EXPECTED_FINGERPRINT = (
    "samsung/b2qksx/b2q:15/AP3A.240905.015.A2/"
    "F711NKSSEKZE1:user/release-keys"
)
EXPECTED_SIZE = "1080x2640"
LAUNCHER_CLASS = "com.jay.fxi.macrobenchmark.LauncherSmokeBenchmark"
LAUNCHER_METHOD = "launcherSmoke"
LAUNCHER_TASK = "adb-am-instrument"
ARTIFACT_PATHS = {
    "targetBenchmarkApk": "app/build/outputs/apk/benchmark/app-benchmark.apk",
    "macrobenchmarkTestApk": (
        "macrobenchmark/build/outputs/apk/benchmark/"
        "macrobenchmark-benchmark.apk"
    ),
    "benchmarkFirebaseInput": "app/src/benchmark/google-services.json",
    "canonicalFirebaseFixture": "ci/google-services.ci-fixture.json",
    "devicePreflight": "tools/performance/device_preflight.py",
}
SMOKE_ARTIFACTS = {
    "targetBenchmarkApk",
    "macrobenchmarkTestApk",
    "benchmarkFirebaseInput",
}
ALL_ARTIFACTS = set(ARTIFACT_PATHS)
INSTALLED_ARTIFACTS = {"targetBenchmarkApk", "macrobenchmarkTestApk"}
SHA256 = re.compile(r"[0-9a-f]{64}")
RUN_ID = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")
SAMSUNG_SERIAL = re.compile(r"\bR[A-Z0-9]{10,15}\b", re.IGNORECASE)
ADB_SERIAL_MARKERS = re.compile(
    r"(?i)(?:\badb-[a-z0-9._:-]{6,}|_adb-tls-connect|transport_id|ro\.serialno)"
)


class EvidenceError(RuntimeError):
    """The evidence cannot prove the claimed launcher-smoke result."""


def _reject_constant(value: str) -> None:
    raise EvidenceError(f"JSON contains a non-finite constant: {value}")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EvidenceError(f"JSON contains duplicate key {key!r}")
        result[key] = value
    return result


def _load_json(path: Path) -> dict[str, Any]:
    try:
        raw = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise EvidenceError(f"cannot read UTF-8 JSON {path.name}: {error}") from error
    try:
        value = json.loads(
            raw,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except json.JSONDecodeError as error:
        raise EvidenceError(f"malformed JSON {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise EvidenceError(f"{path.name} must contain a JSON object")
    return value


def _exact_keys(value: Any, expected: set[str], label: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError(f"{label} must be an object")
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise EvidenceError(f"{label} fields differ (missing={missing}, extra={extra})")
    return value


def _require_bool(value: Any, expected: bool, label: str) -> None:
    if type(value) is not bool or value is not expected:
        raise EvidenceError(f"{label} must be {expected}")


def _require_int(value: Any, expected: int, label: str) -> None:
    if type(value) is not int or value != expected:
        raise EvidenceError(f"{label} must be integer {expected}")


def _positive_int(value: Any, label: str) -> int:
    if type(value) is not int or value <= 0:
        raise EvidenceError(f"{label} must be a positive integer")
    return value


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise EvidenceError(f"{label} must be a lowercase SHA-256")
    return value


def _safe_relative_path(value: Any, label: str, expected: str | None = None) -> str:
    if not isinstance(value, str) or not value:
        raise EvidenceError(f"{label} must be a non-empty relative POSIX path")
    path = PurePosixPath(value)
    if (
        path.is_absolute()
        or ".." in path.parts
        or "." in path.parts
        or path.as_posix() != value
        or re.match(r"^[A-Za-z]:[\\/]", value)
        or value.startswith("file:")
    ):
        raise EvidenceError(f"{label} must be a normalized relative POSIX path")
    if expected is not None and value != expected:
        raise EvidenceError(f"{label} must identify {expected}")
    return value


def _walk_json(value: Any, label: str = "JSON") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if "serial" in key.lower() and key != "serialRecorded":
                raise EvidenceError(f"{label} contains forbidden serial field {key!r}")
            _walk_json(child, f"{label}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _walk_json(child, f"{label}[{index}]")
    elif isinstance(value, str):
        if (
            value.startswith("/")
            or value.startswith("file:")
            or re.match(r"^[A-Za-z]:[\\/]", value)
        ):
            raise EvidenceError(f"{label} leaks an absolute path")
        if (
            value != LAUNCHER_TASK
            and (ADB_SERIAL_MARKERS.search(value) or SAMSUNG_SERIAL.search(value))
        ):
            raise EvidenceError(f"{label} leaks an ADB/device serial")
    elif isinstance(value, float) and not math.isfinite(value):
        raise EvidenceError(f"{label} contains a non-finite number")


def _file_identity(path: Path) -> dict[str, Any]:
    return {
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "size": path.stat().st_size,
    }


def _parse_time(value: Any, label: str) -> datetime:
    if not isinstance(value, str):
        raise EvidenceError(f"{label} must be an ISO-8601 UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as error:
        raise EvidenceError(f"{label} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise EvidenceError(f"{label} must include the UTC offset")
    return parsed


def _validate_source(source: Any, label: str) -> str:
    value = _exact_keys(source, SOURCE_FIELDS, label)
    commit = value["commit"]
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise EvidenceError(f"{label}.commit must be a 40-hex commit")
    _require_bool(value["worktreeClean"], True, f"{label}.worktreeClean")
    return commit


def _validate_settings(value: Any, label: str, expected: Mapping[str, str] | None) -> None:
    settings = _exact_keys(value, SETTING_KEYS, label)
    for key, setting in settings.items():
        if not isinstance(setting, str) or not re.fullmatch(r"null|-?\d+(?:\.\d+)?", setting):
            raise EvidenceError(f"{label}.{key} has an invalid settings value")
    if expected is not None and settings != expected:
        raise EvidenceError(f"{label} differs from the D31 measurement settings")


def _validate_device(value: Any, label: str) -> Mapping[str, Any]:
    device = _exact_keys(value, IMMUTABLE_DEVICE_FIELDS, label)
    if device["model"] != EXPECTED_MODEL:
        raise EvidenceError(f"{label}.model differs from D31")
    if device["fingerprint"] != EXPECTED_FINGERPRINT:
        raise EvidenceError(f"{label}.fingerprint differs from D31")
    _require_int(device["api"], EXPECTED_API, f"{label}.api")
    if device["posture"] != "OPENED":
        raise EvidenceError(f"{label}.posture must be OPENED")
    if device["physicalSize"] != EXPECTED_SIZE:
        raise EvidenceError(f"{label}.physicalSize differs from D31")
    if device["overrideSize"] not in {None, EXPECTED_SIZE}:
        raise EvidenceError(f"{label}.overrideSize differs from D31")
    _require_int(device["orientation"], 0, f"{label}.orientation")
    _require_bool(device["supports120Hz"], True, f"{label}.supports120Hz")
    active_refresh = device["activeRefreshRateHz"]
    if (
        type(active_refresh) not in {int, float}
        or not math.isfinite(active_refresh)
        or not math.isclose(active_refresh, 120.0, rel_tol=0.0, abs_tol=0.01)
    ):
        raise EvidenceError(f"{label}.activeRefreshRateHz must prove active 120Hz")
    _require_bool(device["lowPower"], False, f"{label}.lowPower")
    battery = device["batteryLevel"]
    if type(battery) is not int or not 30 <= battery <= 100:
        raise EvidenceError(f"{label}.batteryLevel must be in 30..100")
    powered = _exact_keys(
        device["powered"],
        {"ac powered", "usb powered", "wireless powered", "dock powered"},
        f"{label}.powered",
    )
    for key, active in powered.items():
        _require_bool(active, False, f"{label}.powered.{key}")
    _require_int(device["thermalStatus"], 0, f"{label}.thermalStatus")
    return device


def _validate_active_refresh(value: Any, label: str) -> datetime:
    observations = _exact_keys(value, ACTIVE_REFRESH_FIELDS, label)
    rates: list[float] = []
    for key in ("displayManagerHz", "surfaceFlingerHz"):
        rate = observations[key]
        if (
            type(rate) not in {int, float}
            or not math.isfinite(rate)
            or not math.isclose(rate, 120.0, rel_tol=0.0, abs_tol=0.01)
        ):
            raise EvidenceError(f"{label}.{key} must prove active 120Hz")
        rates.append(float(rate))
    if not math.isclose(rates[0], rates[1], rel_tol=0.0, abs_tol=0.01):
        raise EvidenceError(f"{label} display oracles disagree")
    return _parse_time(observations["observedAt"], f"{label}.observedAt")


def _validate_snapshot(
    value: Any, phase: str, run_id: str
) -> tuple[Mapping[str, Any], datetime]:
    snapshot = _exact_keys(value, SNAPSHOT_FIELDS, f"{phase}.json")
    if snapshot["phase"] != phase:
        raise EvidenceError(f"{phase}.json has the wrong phase")
    if snapshot["runId"] != run_id:
        raise EvidenceError(f"{phase}.json runId differs from run.json")
    _require_bool(snapshot["serialRecorded"], False, f"{phase}.json.serialRecorded")
    _validate_settings(snapshot["settings"], f"{phase}.json.settings", SETTING_TARGETS)
    device = _validate_device(
        {key: snapshot[key] for key in IMMUTABLE_DEVICE_FIELDS},
        f"{phase}.json",
    )
    return device, _parse_time(snapshot["observedAt"], f"{phase}.json.observedAt")


def _validate_artifact(value: Any, label: str, expected_path: str) -> dict[str, Any]:
    artifact = _exact_keys(value, {"path", "sha256", "size"}, label)
    return {
        "path": _safe_relative_path(artifact["path"], f"{label}.path", expected_path),
        "sha256": _sha(artifact["sha256"], f"{label}.sha256"),
        "size": _positive_int(artifact["size"], f"{label}.size"),
    }


def _validate_artifact_map(
    value: Any, expected_keys: set[str], label: str
) -> dict[str, dict[str, Any]]:
    artifacts = _exact_keys(value, expected_keys, label)
    return {
        key: _validate_artifact(artifacts[key], f"{label}.{key}", ARTIFACT_PATHS[key])
        for key in sorted(expected_keys)
    }


def _validate_installed_artifacts(
    value: Any, staged: Mapping[str, Mapping[str, Any]]
) -> None:
    installed = _exact_keys(value, INSTALLED_ARTIFACTS, "deviceInstalledArtifacts")
    for key in sorted(INSTALLED_ARTIFACTS):
        identity = _exact_keys(
            installed[key],
            {"sha256", "size", "matchesStagedArtifact"},
            f"deviceInstalledArtifacts.{key}",
        )
        _require_bool(
            identity["matchesStagedArtifact"],
            True,
            f"deviceInstalledArtifacts.{key}.matchesStagedArtifact",
        )
        actual = {
            "sha256": _sha(identity["sha256"], f"deviceInstalledArtifacts.{key}.sha256"),
            "size": _positive_int(identity["size"], f"deviceInstalledArtifacts.{key}.size"),
        }
        expected = {name: staged[key][name] for name in ("sha256", "size")}
        if actual != expected:
            raise EvidenceError(f"installed {key} differs from the staged APK")


def _validate_launcher_xml(path: Path) -> None:
    raw = path.read_bytes()
    upper = raw.upper()
    if b"<!DOCTYPE" in upper or b"<!ENTITY" in upper or b"<!--" in raw:
        raise EvidenceError("launcher-result.xml contains non-canonical XML constructs")
    without_declaration = re.sub(br"^\s*<\?xml[^?]*\?>", b"", raw, count=1)
    if b"<?" in without_declaration:
        raise EvidenceError("launcher-result.xml contains a processing instruction")
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as error:
        raise EvidenceError("launcher-result.xml is malformed") from error
    expected_suite = {
        "name": LAUNCHER_CLASS,
        "tests": "1",
        "failures": "0",
        "errors": "0",
        "skipped": "0",
    }
    if root.tag != "testsuite" or root.attrib != expected_suite:
        raise EvidenceError("launcher-result.xml must be exactly one green launcher suite")
    if root.text and root.text.strip():
        raise EvidenceError("launcher-result.xml contains unexpected suite text")
    children = list(root)
    if len(children) != 1 or children[0].tag != "testcase":
        raise EvidenceError("launcher-result.xml must contain exactly one testcase")
    case = children[0]
    expected_case = {"name": LAUNCHER_METHOD, "classname": LAUNCHER_CLASS}
    case_fields = set(case.attrib)
    if case_fields != set(expected_case) and case_fields != set(expected_case) | {"time"}:
        raise EvidenceError("launcher-result.xml testcase attributes are not canonical")
    if any(case.attrib.get(key) != expected for key, expected in expected_case.items()):
        raise EvidenceError("launcher-result.xml identifies the wrong test")
    if "time" in case.attrib:
        try:
            duration = float(case.attrib["time"])
        except ValueError as error:
            raise EvidenceError("launcher-result.xml has an invalid duration") from error
        if not math.isfinite(duration) or duration < 0:
            raise EvidenceError("launcher-result.xml has an invalid duration")
    if list(case) or (case.text and case.text.strip()) or (case.tail and case.tail.strip()):
        raise EvidenceError("launcher-result.xml testcase contains unexpected content")


def _validate_launcher_result(value: Any, evidence_xml: Mapping[str, Any]) -> None:
    result = _exact_keys(
        value,
        {"class", "method", "task", "result", "tests", "failures", "errors", "skipped"},
        "launcherResult",
    )
    if (
        result["class"] != LAUNCHER_CLASS
        or result["method"] != LAUNCHER_METHOD
        or result["task"] != LAUNCHER_TASK
    ):
        raise EvidenceError("launcherResult is not the direct launcher instrumentation")
    for key, expected in (("tests", 1), ("failures", 0), ("errors", 0), ("skipped", 0)):
        _require_int(result[key], expected, f"launcherResult.{key}")
    identity = _exact_keys(
        result["result"],
        {"path", "sha256", "size", "sourceSha256"},
        "launcherResult.result",
    )
    _safe_relative_path(identity["path"], "launcherResult.result.path", "launcher-result.xml")
    _sha(identity["sha256"], "launcherResult.result.sha256")
    _sha(identity["sourceSha256"], "launcherResult.result.sourceSha256")
    _positive_int(identity["size"], "launcherResult.result.size")
    if {key: identity[key] for key in ("sha256", "size")} != evidence_xml:
        raise EvidenceError("launcherResult does not bind the promoted launcher-result.xml")


def _assert_commit_exists(commit: str, repository_root: Path) -> None:
    result = subprocess.run(
        ["git", "cat-file", "-e", f"{commit}^{{commit}}"],
        cwd=repository_root,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if result.returncode != 0:
        raise EvidenceError("source commit is not present in this Git repository")


def _git_blob_identity(
    commit: str, path: str, repository_root: Path
) -> dict[str, Any]:
    result = subprocess.run(
        ["git", "show", f"{commit}:{path}"],
        cwd=repository_root,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    if result.returncode != 0:
        raise EvidenceError(f"source commit does not contain {path}")
    return {
        "sha256": hashlib.sha256(result.stdout).hexdigest(),
        "size": len(result.stdout),
    }


def validate_bundle(
    bundle: Path, repository_root: Path | None = None
) -> dict[str, str]:
    """Validate one promoted bundle and return its sealed identity."""

    bundle = Path(bundle)
    repository_root = ROOT if repository_root is None else Path(repository_root)
    if bundle.is_symlink() or not bundle.is_dir():
        raise EvidenceError("evidence bundle must be a real directory, not a symlink")
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
    for name, value in (
        ("run.json", run),
        ("preflight.json", preflight),
        ("postflight.json", postflight),
    ):
        _walk_json(value, name)

    run = _exact_keys(run, RUN_FIELDS, "run.json")
    _require_int(run["schemaVersion"], 1, "run.json.schemaVersion")
    run_id = run["runId"]
    if not isinstance(run_id, str) or not RUN_ID.fullmatch(run_id):
        raise EvidenceError("run.json.runId is not a stable non-secret id")
    _require_bool(run["serialRecorded"], False, "run.json.serialRecorded")
    _require_bool(run["launcherSmokeAttempted"], True, "launcherSmokeAttempted")
    _require_int(run["benchmarkBuildReturnCode"], 0, "benchmarkBuildReturnCode")
    _require_int(run["launcherSmokeReturnCode"], 0, "launcherSmokeReturnCode")
    _require_int(run["exitCode"], 0, "exitCode")
    if run["verdict"] != "pass":
        raise EvidenceError("verdict must be pass")
    refresh_before_time = _validate_active_refresh(
        run["activeRefreshBeforeSmoke"], "activeRefreshBeforeSmoke"
    )
    refresh_after_time = _validate_active_refresh(
        run["activeRefreshAfterSmoke"], "activeRefreshAfterSmoke"
    )
    for key in (
        "settingsRestored",
        "temporaryFirebaseFixtureRemoved",
        "temporaryPackagesRemoved",
        "deviceOutputRemoved",
        "temporaryStagingRemoved",
        "temporarySourceSnapshotRemoved",
        "artifactsStableAcrossSmoke",
    ):
        _require_bool(run[key], True, key)

    source_start = _validate_source(run["sourceAtStart"], "sourceAtStart")
    source_end = _validate_source(run["sourceAtEnd"], "sourceAtEnd")
    if source_start != source_end:
        raise EvidenceError("source commit changed during launcher smoke")
    _assert_commit_exists(source_start, repository_root)

    directory = _safe_relative_path(run["evidenceDirectory"], "evidenceDirectory")
    if PurePosixPath(directory).name != run_id:
        raise EvidenceError("evidenceDirectory basename must equal runId")

    _validate_settings(run["settingsBefore"], "settingsBefore", None)
    _validate_settings(run["settingsDuring"], "settingsDuring", SETTING_TARGETS)
    _validate_settings(run["settingsAfter"], "settingsAfter", None)
    if run["settingsBefore"] != run["settingsAfter"]:
        raise EvidenceError("device settings were not restored byte-for-byte")

    pre_device, pre_time = _validate_snapshot(preflight, "preflight", run_id)
    post_device, post_time = _validate_snapshot(postflight, "postflight", run_id)
    if run["preflight"] != pre_device or run["postflight"] != post_device:
        raise EvidenceError("run.json device state differs from pre/post snapshots")
    if preflight["settings"] != run["settingsDuring"] or postflight["settings"] != run["settingsDuring"]:
        raise EvidenceError("pre/post snapshots differ from settingsDuring")
    stable_device_keys = IMMUTABLE_DEVICE_FIELDS - {"batteryLevel"}
    if any(pre_device[key] != post_device[key] for key in stable_device_keys):
        raise EvidenceError("D31 device state drifted during launcher smoke")

    started = _parse_time(run["startedAt"], "startedAt")
    finished = _parse_time(run["finishedAt"], "finishedAt")
    if not started <= pre_time <= post_time <= finished:
        raise EvidenceError("evidence timestamps are not monotonic")
    if not pre_time <= refresh_before_time <= refresh_after_time <= post_time:
        raise EvidenceError("active-refresh samples do not bracket launcher instrumentation")

    evidence_files = _exact_keys(run["evidenceFiles"], EVIDENCE_FILES, "evidenceFiles")
    verified_files: dict[str, dict[str, Any]] = {}
    for name in sorted(EVIDENCE_FILES):
        recorded = _exact_keys(evidence_files[name], {"sha256", "size"}, f"evidenceFiles.{name}")
        identity = {
            "sha256": _sha(recorded["sha256"], f"evidenceFiles.{name}.sha256"),
            "size": _positive_int(recorded["size"], f"evidenceFiles.{name}.size"),
        }
        actual = _file_identity(bundle / name)
        if identity != actual:
            raise EvidenceError(f"{name} differs from its recorded hash/size")
        verified_files[name] = actual

    _validate_launcher_xml(bundle / "launcher-result.xml")
    _validate_launcher_result(run["launcherResult"], verified_files["launcher-result.xml"])

    before = _validate_artifact_map(run["artifactsBeforeSmoke"], SMOKE_ARTIFACTS, "artifactsBeforeSmoke")
    after = _validate_artifact_map(run["artifactsAfterSmoke"], SMOKE_ARTIFACTS, "artifactsAfterSmoke")
    if before != after:
        raise EvidenceError("launcher inputs changed between pre- and post-smoke hashing")
    artifacts = _validate_artifact_map(run["artifacts"], ALL_ARTIFACTS, "artifacts")
    for key in SMOKE_ARTIFACTS:
        if artifacts[key] != after[key]:
            raise EvidenceError(f"artifacts.{key} differs from the post-smoke identity")
    actual_firebase = artifacts["benchmarkFirebaseInput"]
    canonical_firebase = artifacts["canonicalFirebaseFixture"]
    if any(actual_firebase[key] != canonical_firebase[key] for key in ("sha256", "size")):
        raise EvidenceError("benchmark Firebase input differs from the canonical fixture")
    for key in ("canonicalFirebaseFixture", "devicePreflight"):
        blob = _git_blob_identity(source_start, ARTIFACT_PATHS[key], repository_root)
        recorded = {name: artifacts[key][name] for name in ("sha256", "size")}
        if recorded != blob:
            raise EvidenceError(f"artifacts.{key} differs from its source-commit blob")
    _validate_installed_artifacts(run["deviceInstalledArtifacts"], after)

    return {"runId": run_id, "sourceCommit": source_start, "verdict": "pass"}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate = subparsers.add_parser("validate", help="validate one promoted evidence bundle")
    validate.add_argument("bundle", type=Path)
    args = parser.parse_args(argv)
    try:
        result = validate_bundle(args.bundle)
    except (EvidenceError, OSError) as error:
        print(f"invalid launcher evidence: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
