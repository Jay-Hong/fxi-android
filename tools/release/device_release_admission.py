#!/usr/bin/env python3
"""Create sanitized, commit-bound S0-g release-admission device evidence.

The runner builds a clean Git snapshot, protects every pre-existing Android install,
and records no ADB serial, absolute device path, or raw instrumentation output.  It
does not unlock the device or mutate battery, refresh-rate, or animation settings.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from typing import Any, Sequence


ROOT = Path(__file__).resolve().parents[2]
PERFORMANCE_TOOLS = ROOT / "tools" / "performance"
if str(PERFORMANCE_TOOLS) not in sys.path:
    sys.path.insert(0, str(PERFORMANCE_TOOLS))

import device_preflight as physical  # noqa: E402


TARGET_PACKAGE = "com.jay.fxi"
TEST_PACKAGE = "com.jay.fxi.test"
UNAVAILABLE_TITLE = "현재 버전은 사용할 수 없습니다"
UNAVAILABLE_MESSAGE = "업데이트가 준비될 때까지 잠시 기다려 주세요."
INSTRUMENTATION_COMPONENT = (
    "com.jay.fxi.test/androidx.test.runner.AndroidJUnitRunner"
)
TARGET_APK = Path("app/build/outputs/apk/debug/app-debug.apk")
TEST_APK = Path(
    "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
)
CANONICAL_FIREBASE_FIXTURE = Path("ci/google-services.ci-fixture.json")
DEBUG_FIREBASE_INPUT = Path("app/src/debug/google-services.json")
RUNNER_PATH = Path("tools/release/device_release_admission.py")
ARTIFACT_VERIFIER_PATH = Path(
    "tools/release/verify_release_admission_artifacts.py"
)
EVIDENCE_VALIDATOR_PATH = Path(
    "tools/release/release_admission_evidence.py"
)
DEBUG_MANIFEST_PATH = Path("app/src/debug/AndroidManifest.xml")
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


class ReleaseEvidenceError(physical.PreflightError):
    pass


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _identity(path: Path, recorded_path: Path | str) -> dict[str, Any]:
    return physical.stable_file_identity(path, Path(recorded_path).as_posix())


def _snapshot_artifacts(build_root: Path) -> dict[str, dict[str, Any]]:
    return {
        "targetDebugApk": _identity(build_root / TARGET_APK, TARGET_APK),
        "androidTestApk": _identity(build_root / TEST_APK, TEST_APK),
        "canonicalFirebaseFixture": _identity(
            build_root / CANONICAL_FIREBASE_FIXTURE,
            CANONICAL_FIREBASE_FIXTURE,
        ),
        "debugFirebaseInput": _identity(
            build_root / DEBUG_FIREBASE_INPUT,
            DEBUG_FIREBASE_INPUT,
        ),
        "runner": _identity(build_root / RUNNER_PATH, RUNNER_PATH),
        "artifactVerifier": _identity(
            build_root / ARTIFACT_VERIFIER_PATH,
            ARTIFACT_VERIFIER_PATH,
        ),
        "evidenceValidator": _identity(
            build_root / EVIDENCE_VALIDATOR_PATH,
            EVIDENCE_VALIDATOR_PATH,
        ),
        "debugManifest": _identity(
            build_root / DEBUG_MANIFEST_PATH,
            DEBUG_MANIFEST_PATH,
        ),
    }


def prepare_debug_firebase_fixture(build_root: Path) -> None:
    source = build_root / CANONICAL_FIREBASE_FIXTURE
    destination = build_root / DEBUG_FIREBASE_INPUT
    if destination.exists() or destination.is_symlink():
        raise ReleaseEvidenceError("debug Firebase input already exists in source snapshot")
    if source.is_symlink() or not source.is_file():
        raise ReleaseEvidenceError("canonical Firebase fixture is not a regular file")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    if _identity(source, CANONICAL_FIREBASE_FIXTURE)["sha256"] != _identity(
        destination, DEBUG_FIREBASE_INPUT
    )["sha256"]:
        raise ReleaseEvidenceError("debug Firebase input differs from canonical fixture")


def run_debug_build(build_root: Path) -> int:
    for relative in (TARGET_APK, TEST_APK):
        output = build_root / relative
        if output.exists() or output.is_symlink():
            if output.is_dir() and not output.is_symlink():
                raise ReleaseEvidenceError("APK output is unexpectedly a directory")
            output.unlink()

    environment = os.environ.copy()
    for variable in ("GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "KOTLIN_OPTS"):
        environment.pop(variable, None)
    for variable in tuple(environment):
        if variable.startswith("ORG_GRADLE_PROJECT_"):
            environment.pop(variable)
    sdk = environment.get("ANDROID_HOME") or environment.get("ANDROID_SDK_ROOT")
    if not sdk:
        default_sdk = Path.home() / "Library" / "Android" / "sdk"
        if not default_sdk.is_dir():
            raise ReleaseEvidenceError("Android SDK path is not configured")
        sdk = str(default_sdk)
    environment["ANDROID_HOME"] = sdk
    environment["ANDROID_SDK_ROOT"] = sdk

    original_gradle_home = Path(
        os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")
    )
    with physical.private_temporary_directory("release-gradle-home-") as temporary_home:
        gradle_home = Path(temporary_home)
        for reusable in ("caches", "wrapper", "jdks"):
            source = original_gradle_home / reusable
            destination = gradle_home / reusable
            if source.exists():
                destination.symlink_to(source, target_is_directory=True)
        environment["GRADLE_USER_HOME"] = str(gradle_home)
        result = subprocess.run(
            [
                str(build_root / "gradlew"),
                ":app:assembleDebug",
                ":app:assembleDebugAndroidTest",
                "--console=plain",
                "--no-daemon",
                "--no-configuration-cache",
                "--rerun-tasks",
            ],
            cwd=build_root,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    return result.returncode


def find_aapt2() -> Path:
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    sdk = Path(sdk_value) if sdk_value else Path.home() / "Library" / "Android" / "sdk"
    candidates = [
        candidate
        for candidate in (sdk / "build-tools").glob("*/aapt2")
        if candidate.is_file() and os.access(candidate, os.X_OK)
    ]
    if not candidates:
        raise ReleaseEvidenceError("aapt2 is unavailable in the Android SDK")

    def version_key(path: Path) -> tuple[tuple[int, int | str], ...]:
        return tuple(
            (0, int(part)) if part.isdigit() else (1, part)
            for part in re.split(r"[.-]", path.parent.name)
        )

    return max(candidates, key=version_key)


def verify_debug_artifact(build_root: Path) -> int:
    result = subprocess.run(
        [
            sys.executable,
            str(build_root / ARTIFACT_VERIFIER_PATH),
            "--aapt2",
            str(find_aapt2()),
            "--artifact",
            f"debug=off={build_root / TARGET_APK}",
        ],
        cwd=build_root,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return result.returncode


def collect_release_device(adb: physical.Adb) -> dict[str, Any]:
    identity = physical.read_identity(adb)
    expected = {
        "model": physical.EXPECTED_MODEL,
        "fingerprint": physical.EXPECTED_FINGERPRINT,
        "api": int(physical.EXPECTED_API),
    }
    if identity != expected:
        raise ReleaseEvidenceError("device identity differs from the frozen S0 contract")
    state_dump = adb.shell("dumpsys", "device_state")
    current_state = adb.shell("cmd", "device_state", "print-state", check=False)
    posture = physical.parse_device_state_name(state_dump, current_state)
    if posture != "OPENED":
        raise ReleaseEvidenceError(f"device posture must be OPENED, got {posture}")
    physical_size, override_size = physical.parse_wm_size(adb.shell("wm", "size"))
    if physical_size != physical.EXPECTED_SIZE or (
        override_size is not None and override_size != physical.EXPECTED_SIZE
    ):
        raise ReleaseEvidenceError("active display size differs from the frozen S0 contract")
    orientation, viewport_size = physical.parse_active_internal_viewport(
        adb.shell("dumpsys", "input")
    )
    if orientation != 0 or viewport_size != physical.EXPECTED_SIZE:
        raise ReleaseEvidenceError("opened inner display must be portrait 1080x2640")
    power = adb.shell("dumpsys", "power")
    if not re.search(r"^\s*mWakefulness=Awake\s*$", power, re.MULTILINE):
        raise ReleaseEvidenceError("device screen must be awake; runner never unlocks it")
    return {
        **identity,
        "posture": posture,
        "physicalSize": physical_size,
        "overrideSize": override_size,
        "orientation": orientation,
        "viewportSize": viewport_size,
        "wakefulness": "Awake",
    }


def force_netstats_poll(adb: physical.Adb) -> None:
    poll = adb.run(
        "shell",
        "dumpsys",
        "netstats",
        "--poll",
        check=False,
        timeout=30,
    )
    if (
        poll.returncode != 0
        or poll.stdout.strip() != "Forced poll"
        or poll.stderr.strip()
    ):
        raise ReleaseEvidenceError("Android network-stats forced poll did not succeed")


def collect_uid_traffic(adb: physical.Adb) -> dict[str, Any]:
    force_netstats_poll(adb)
    package = adb.run(
        "shell",
        "cmd",
        "package",
        "list",
        "packages",
        "-U",
        TARGET_PACKAGE,
        check=False,
    )
    matches = re.findall(
        rf"^package:{re.escape(TARGET_PACKAGE)}\s+uid:(\d+)\s*$",
        package.stdout,
        re.MULTILINE,
    )
    if package.returncode != 0 or len(matches) != 1:
        raise ReleaseEvidenceError("cannot resolve target app UID for traffic observation")
    uid = int(matches[0])
    dump = adb.run(
        "shell", "dumpsys", "netstats", "detail", check=False, timeout=30
    )
    if dump.returncode != 0:
        raise ReleaseEvidenceError("cannot read Android UID traffic counters")
    history = parse_netstats_uid_history(dump.stdout, uid)
    live = parse_netstats_app_uid_map(dump.stdout, uid)
    return {
        "forcedPoll": True,
        "historyRows": history["rows"],
        "historyRxBytes": history["rxBytes"],
        "historyTxBytes": history["txBytes"],
        "liveRows": live["rows"],
        "liveRxBytes": live["rxBytes"],
        "liveTxBytes": live["txBytes"],
    }


def parse_netstats_uid_history(output: str, uid: int) -> dict[str, int]:
    """Sum authoritative untagged UID history buckets from dumpsys netstats detail."""
    header_pattern = re.compile(
        r"^\s+ident=.*\suid=(\d+)\sset=([A-Z_]+)\s"
        r"tag=(0x[0-9a-fA-F]+)\s*$"
    )
    bucket_pattern = re.compile(
        r"^\s+st=\d+\srb=(\d+)\srp=\d+\stb=(\d+)\stp=\d+\sop=\d+\s*$"
    )
    header_count = 0
    bucket_count = 0
    selected = False
    rows: list[tuple[int, int]] = []
    for line in output.splitlines():
        header = header_pattern.match(line)
        if header:
            header_count += 1
            target = int(header.group(1)) == uid
            counter_set = header.group(2)
            if target and counter_set not in {
                "DEFAULT",
                "FOREGROUND",
                "DBG_VPN_IN",
                "DBG_VPN_OUT",
            }:
                raise ReleaseEvidenceError("target UID traffic-history counter set changed")
            selected = (
                target
                and counter_set in {"DEFAULT", "FOREGROUND"}
                and int(header.group(3), 16) == 0
            )
            continue
        if line.lstrip().startswith("ident="):
            if re.search(rf"\buid={uid}\b", line):
                raise ReleaseEvidenceError(
                    "target UID traffic-history header format changed"
                )
            selected = False
            continue
        bucket = bucket_pattern.match(line)
        if bucket:
            bucket_count += 1
            if selected:
                rows.append((int(bucket.group(1)), int(bucket.group(2))))
        elif selected and line.lstrip().startswith("st="):
            raise ReleaseEvidenceError("Android UID traffic bucket format changed")
    if header_count == 0 or bucket_count == 0:
        raise ReleaseEvidenceError("Android UID traffic history format is unrecognized")
    return {
        "rows": len(rows),
        "rxBytes": sum(received for received, _ in rows),
        "txBytes": sum(transmitted for _, transmitted in rows),
    }


def parse_netstats_app_uid_map(output: str, uid: int) -> dict[str, int]:
    """Read the live per-UID BPF counter independently of persisted history."""
    marker = re.compile(r"^\s*mAppUidStatsMap:\s*$")
    header = re.compile(
        r"^\s*uid\s+rxBytes\s+rxPackets\s+txBytes\s+txPackets\s*$"
    )
    row = re.compile(r"^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$")
    next_map = re.compile(r"^\s*\S.*:\s*$")
    lines = output.splitlines()
    starts = [index for index, line in enumerate(lines) if marker.match(line)]
    if len(starts) != 1:
        raise ReleaseEvidenceError(
            "Android live per-UID BPF map must appear exactly once"
        )

    index = starts[0] + 1
    while index < len(lines) and not lines[index].strip():
        index += 1
    if index >= len(lines) or not header.match(lines[index]):
        raise ReleaseEvidenceError("Android live per-UID BPF map header changed")
    index += 1

    target_rows: list[tuple[int, int, int, int, int]] = []
    while index < len(lines):
        line = lines[index]
        if next_map.match(line):
            break
        if not line.strip():
            index += 1
            continue
        if "Map dump end with error" in line or "Map is null" in line:
            raise ReleaseEvidenceError("Android live per-UID BPF map is unavailable")
        match = row.match(line)
        if match:
            values = tuple(int(value) for value in match.groups())
            if values[0] == uid:
                target_rows.append(values)
            index += 1
            continue
        if "Entry is deleted while dumping, iterating from first entry" in line:
            index += 1
            continue
        raise ReleaseEvidenceError("Android live per-UID BPF map row format changed")

    distinct = set(target_rows)
    if len(distinct) > 1:
        raise ReleaseEvidenceError("Android live per-UID BPF map has conflicting rows")
    if not distinct:
        return {"rows": 0, "rxBytes": 0, "txBytes": 0}
    values = next(iter(distinct))
    return {"rows": 1, "rxBytes": values[1], "txBytes": values[3]}


def parse_unavailable_ui_dump(output: str) -> dict[str, Any]:
    start = output.find("<?xml")
    end = output.rfind("</hierarchy>")
    if start < 0 or end < start:
        raise ReleaseEvidenceError("UIAutomator did not return a hierarchy")
    try:
        root = ET.fromstring(output[start : end + len("</hierarchy>")])
    except ET.ParseError as error:
        raise ReleaseEvidenceError("UIAutomator hierarchy is malformed") from error
    app_nodes = [
        node for node in root.iter("node") if node.attrib.get("package") == TARGET_PACKAGE
    ]
    if not app_nodes:
        raise ReleaseEvidenceError("UIAutomator hierarchy has no target-app nodes")
    if sum(node.attrib.get("text") == UNAVAILABLE_TITLE for node in app_nodes) != 1:
        raise ReleaseEvidenceError("exact unavailable title is not visible")
    if sum(node.attrib.get("text") == UNAVAILABLE_MESSAGE for node in app_nodes) != 1:
        raise ReleaseEvidenceError("exact unavailable message is not visible")
    clickable = [
        node
        for node in app_nodes
        if node.attrib.get("clickable") == "true"
        or node.attrib.get("long-clickable") == "true"
    ]
    if clickable:
        raise ReleaseEvidenceError("D24-OFF app surface exposes a click action")
    return {
        "unavailableTitlePresent": True,
        "unavailableMessagePresent": True,
        "appClickableNodeCount": 0,
    }


def observe_unavailable_ui(adb: physical.Adb, run_id: str) -> dict[str, Any]:
    # Samsung's Android 15 uiautomator reports success for /dev/tty without writing
    # XML to stdout. Use one exact shell-owned path, never persist the raw hierarchy,
    # and prove cleanup before accepting the sanitized assertions.
    remote = f"/data/local/tmp/fxi-release-admission-ui-{run_id}.xml"
    try:
        preexisting = adb.run("shell", "test", "-e", remote, check=False)
        preexisting_link = adb.run("shell", "test", "-L", remote, check=False)
    except physical.PreflightError as error:
        raise ReleaseEvidenceError(
            "cannot inspect the temporary UIAutomator path"
        ) from error
    if preexisting.returncode == 0 or preexisting_link.returncode == 0:
        raise ReleaseEvidenceError("temporary UIAutomator path already exists")
    if preexisting.returncode != 1 or preexisting_link.returncode != 1:
        raise ReleaseEvidenceError("cannot prove temporary UIAutomator path is absent")

    output = ""
    capture_error: ReleaseEvidenceError | None = None
    try:
        capture = adb.run(
            "shell", "uiautomator", "dump", remote, check=False, timeout=30
        )
        if capture.returncode != 0 or capture.stderr.strip():
            raise ReleaseEvidenceError("UIAutomator hierarchy capture failed")
        readback = adb.run("exec-out", "cat", remote, check=False, timeout=30)
        if readback.returncode != 0 or readback.stderr.strip():
            raise ReleaseEvidenceError("cannot read back UIAutomator hierarchy")
        output = readback.stdout
    except physical.PreflightError as error:
        capture_error = ReleaseEvidenceError("UIAutomator capture/readback failed")
        capture_error.__cause__ = error
    finally:
        try:
            removal = adb.run("shell", "rm", "-f", remote, check=False)
            absent = adb.run("shell", "test", "!", "-e", remote, check=False)
            link_absent = adb.run("shell", "test", "!", "-L", remote, check=False)
        except physical.PreflightError as error:
            raise ReleaseEvidenceError(
                "cannot clean the temporary UIAutomator hierarchy"
            ) from error
        if (
            removal.returncode != 0
            or absent.returncode != 0
            or link_absent.returncode != 0
        ):
            raise ReleaseEvidenceError("temporary UIAutomator hierarchy was not removed")
    if capture_error is not None:
        raise capture_error
    return {**parse_unavailable_ui_dump(output), "temporaryUiDumpRemoved": True}


def write_log_watermark(adb: physical.Adb, run_id: str) -> str:
    marker = f"FXI_S0G_{run_id}_{time.monotonic_ns()}"
    result = adb.run(
        "shell", "log", "-p", "i", "-t", "FXiEvidence", marker, check=False
    )
    if result.returncode != 0:
        raise ReleaseEvidenceError("cannot write the process-cold log watermark")
    return marker


def observe_application_guard(
    adb: physical.Adb, marker: str, process_id: str
) -> bool:
    result = adb.run(
        "shell",
        "logcat",
        "-d",
        "-v",
        "threadtime",
        "-s",
        "FXiEvidence:I",
        "FXiApplication:I",
        "*:S",
        check=False,
        timeout=30,
    )
    if result.returncode != 0:
        raise ReleaseEvidenceError("cannot read process-cold admission logs")
    lines = result.stdout.splitlines()
    marker_rows = [
        index
        for index, line in enumerate(lines)
        if "FXiEvidence" in line and marker in line
    ]
    if len(marker_rows) != 1:
        raise ReleaseEvidenceError("process-cold log watermark is not unique")
    guard = "D24-OFF/no-data process: app-owned services remain disabled."
    guard_pattern = re.compile(
        rf"\s{re.escape(process_id)}\s+\d+\s+I\s+FXiApplication\s*:\s*"
        rf"{re.escape(guard)}\s*$"
    )
    if not any(
        index > marker_rows[0] and guard_pattern.search(line)
        for index, line in enumerate(lines)
    ):
        raise ReleaseEvidenceError("D24-OFF Application guard is not after the watermark")
    return True


def capture_instrumentation_result(
    adb: physical.Adb,
    result: subprocess.CompletedProcess[str],
    run_id: str,
    destination: Path,
) -> dict[str, Any]:
    raw = (result.stdout + "\n" + result.stderr).encode("utf-8")
    if adb.serial.encode("utf-8") in raw:
        raise ReleaseEvidenceError("instrumentation output contains the ADB serial")
    text = raw.decode("utf-8")
    if (
        result.returncode != 0
        or re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.MULTILINE)
        != ["-1"]
        or len(re.findall(r"^OK \(4 tests\)\s*$", text, re.MULTILINE)) != 1
        or "FAILURES!!!" in text
        or "INSTRUMENTATION_FAILED" in text
    ):
        raise ReleaseEvidenceError("instrumentation is not exactly four green tests")

    observed: list[tuple[str, str]] = []
    current_class: str | None = None
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: class="):
            current_class = line.split("=", 1)[1]
        elif line.startswith("INSTRUMENTATION_STATUS: test=") and current_class:
            observed.append((current_class, line.split("=", 1)[1]))
    if set(observed) != set(EXPECTED_CASES) or any(
        observed.count(case) != 2 for case in EXPECTED_CASES
    ):
        raise ReleaseEvidenceError("instrumentation cases differ from the S0-g contract")
    status_codes = re.findall(
        r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", text, re.MULTILINE
    )
    if status_codes != [value for _case in EXPECTED_CASES for value in ("1", "0")]:
        raise ReleaseEvidenceError("instrumentation status sequence is not four green tests")
    duration = re.search(r"^Time:\s*(\d+(?:\.\d+)?)\s*$", text, re.MULTILINE)
    if not duration:
        raise ReleaseEvidenceError("instrumentation omitted its total duration")
    document = {
        "schemaVersion": 1,
        "runId": run_id,
        "task": "adb-am-instrument",
        "runnerComponent": INSTRUMENTATION_COMPONENT,
        "tests": 4,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "durationSeconds": float(duration.group(1)),
        "cases": [
            {"class": class_name, "method": method}
            for class_name, method in EXPECTED_CASES
        ],
        "rawSha256": hashlib.sha256(raw).hexdigest(),
    }
    physical.write_json_atomic(destination, document)
    return document


def run_instrumentation(adb: physical.Adb) -> subprocess.CompletedProcess[str]:
    return adb.run(
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        INSTRUMENTATION_COMPONENT,
        check=False,
        timeout=180,
    )


def observe_process_cold(
    adb: physical.Adb, run_id: str, wait_seconds: float = 5.0
) -> dict[str, Any]:
    adb.shell("am", "force-stop", TARGET_PACKAGE)
    time.sleep(1)
    if adb.shell("pidof", TARGET_PACKAGE, check=False):
        raise ReleaseEvidenceError("target process remains after force-stop")
    traffic_before = collect_uid_traffic(adb)
    log_marker = write_log_watermark(adb, run_id)
    started = adb.run(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        f"{TARGET_PACKAGE}/.MainActivity",
        "--es",
        "type",
        "rate_alert",
        "--es",
        "setting_id",
        "123",
        check=False,
        timeout=30,
    )
    if started.returncode != 0 or "Status: ok" not in started.stdout:
        raise ReleaseEvidenceError("process-cold MainActivity launch failed")
    time.sleep(wait_seconds)
    process_id = adb.shell("pidof", TARGET_PACKAGE, check=False)
    if not re.fullmatch(r"\d+", process_id):
        raise ReleaseEvidenceError("target process is absent after process-cold launch")
    top = adb.shell("dumpsys", "activity", "top")
    activity = re.search(
        r"^\s*ACTIVITY com\.jay\.fxi/\.MainActivity.*?(?=^\s*ACTIVITY |\Z)",
        top,
        re.MULTILINE | re.DOTALL,
    )
    if (
        not activity
        or "mResumed=true" not in activity.group(0)
        or "AndroidComposeView" not in activity.group(0)
    ):
        raise ReleaseEvidenceError("OFF MainActivity Compose surface is not resumed")
    unavailable_ui = observe_unavailable_ui(adb, run_id)
    application_guard_observed = observe_application_guard(adb, log_marker, process_id)

    # Seal the entire process lifetime: stop first, prove PID absence, then force a
    # final persisted snapshot and read the independent live BPF UID counter.
    adb.shell("am", "force-stop", TARGET_PACKAGE)
    time.sleep(1)
    if adb.shell("pidof", TARGET_PACKAGE, check=False):
        raise ReleaseEvidenceError("target process remains after post-observation force-stop")
    traffic_after = collect_uid_traffic(adb)
    time.sleep(1)
    traffic_after_stable = collect_uid_traffic(adb)
    if traffic_after_stable != traffic_after:
        raise ReleaseEvidenceError("post-stop UID traffic counters did not stabilize")
    traffic_delta = {
        key: traffic_after_stable[key] - traffic_before[key]
        for key in (
            "historyRxBytes",
            "historyTxBytes",
            "liveRxBytes",
            "liveTxBytes",
        )
    }
    if any(value != 0 for value in traffic_delta.values()):
        raise ReleaseEvidenceError("target UID traffic changed while D24 admission was OFF")
    return {
        "forceStopPidAbsent": True,
        "startStatusOk": True,
        "mainActivityResumed": True,
        "composeViewPresent": True,
        "notificationExtrasSupplied": True,
        "applicationGuardObserved": application_guard_observed,
        **unavailable_ui,
        "forceStoppedAfterObservation": True,
        "pidAbsentAfterObservation": True,
        "trafficBefore": traffic_before,
        "trafficAfterFirstPoll": traffic_after,
        "trafficAfter": traffic_after_stable,
        "trafficDelta": traffic_delta,
        "postStopPolls": 2,
        "postStopSnapshotsStable": True,
        "observationSeconds": wait_seconds,
    }


def _remove_owned_package(adb: physical.Adb, package_name: str) -> bool:
    result = adb.run("uninstall", package_name, check=False, timeout=180)
    return result.returncode == 0 and "Success" in result.stdout.splitlines()


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", args.run_id):
        parser.error("--run-id must be a stable lowercase non-secret identifier")
    if args.evidence_dir.name != args.run_id:
        parser.error("--evidence-dir basename must equal --run-id")

    evidence: dict[str, Any] = {
        "schemaVersion": 1,
        "runId": args.run_id,
        "startedAt": _utc_now(),
        "serialRecorded": False,
    }
    exit_code = 0
    adb: physical.Adb | None = None
    lock: int | None = None
    source_context: tempfile.TemporaryDirectory[str] | None = None
    staging_context: tempfile.TemporaryDirectory[str] | None = None
    target_owned = False
    test_owned = False
    evidence_owned = False

    def interrupt(_signum: int, _frame: Any) -> None:
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupt)
    try:
        lock = physical.acquire_run_lock()
        source_start = physical.collect_source_state()
        evidence["sourceAtStart"] = source_start
        if not source_start["worktreeClean"]:
            raise ReleaseEvidenceError("durable evidence requires a clean source worktree")
        args.evidence_dir.mkdir(parents=True, exist_ok=False)
        evidence_owned = True

        source_context, build_root = physical.materialize_commit_snapshot(
            source_start["commit"]
        )
        prepare_debug_firebase_fixture(build_root)
        build_return_code = run_debug_build(build_root)
        evidence["buildReturnCode"] = build_return_code
        if build_return_code != 0:
            raise ReleaseEvidenceError("clean source-snapshot Android build failed")
        artifact_verification_return_code = verify_debug_artifact(build_root)
        evidence["artifactVerificationReturnCode"] = artifact_verification_return_code
        if artifact_verification_return_code != 0:
            raise ReleaseEvidenceError(
                "debug APK admission/Firebase binary-manifest verification failed"
            )
        artifacts_before = _snapshot_artifacts(build_root)
        if artifacts_before["canonicalFirebaseFixture"]["sha256"] != artifacts_before[
            "debugFirebaseInput"
        ]["sha256"]:
            raise ReleaseEvidenceError("build Firebase input differs from canonical fixture")
        evidence["artifactsBefore"] = artifacts_before

        staging_context = physical.private_temporary_directory("release-staging-")
        staging_root = Path(staging_context.name)
        staging_root.chmod(0o700)
        staged_target = staging_root / "target-debug.apk"
        staged_test = staging_root / "android-test.apk"
        physical.copy_stable_artifact(
            build_root / TARGET_APK, staged_target, root=build_root
        )
        physical.copy_stable_artifact(
            build_root / TEST_APK, staged_test, root=build_root
        )

        adb = physical.connected_adb()
        preflight = collect_release_device(adb)
        evidence["preflight"] = preflight
        physical.write_json_atomic(
            args.evidence_dir / "preflight.json",
            {"runId": args.run_id, "observedAt": _utc_now(), "device": preflight},
        )
        physical.assert_package_absent(adb, TARGET_PACKAGE)
        physical.assert_package_absent(adb, TEST_PACKAGE)
        physical.install_apk(adb, staged_target)
        target_owned = True
        physical.install_apk(adb, staged_test)
        test_owned = True
        installed = {
            "targetDebugApk": physical.installed_apk_identity(
                adb,
                TARGET_PACKAGE,
                artifacts_before["targetDebugApk"],
                staging_root / "installed-target.apk",
            ),
            "androidTestApk": physical.installed_apk_identity(
                adb,
                TEST_PACKAGE,
                artifacts_before["androidTestApk"],
                staging_root / "installed-test.apk",
            ),
        }
        evidence["installedArtifacts"] = installed

        instrumentation = run_instrumentation(adb)
        evidence["instrumentationReturnCode"] = instrumentation.returncode
        instrumentation_document = capture_instrumentation_result(
            adb,
            instrumentation,
            args.run_id,
            args.evidence_dir / "instrumentation-result.json",
        )
        evidence["instrumentationResult"] = {
            "path": "instrumentation-result.json",
            "sha256": physical.file_sha256(
                args.evidence_dir / "instrumentation-result.json"
            ),
            "size": (args.evidence_dir / "instrumentation-result.json").stat().st_size,
            "rawSha256": instrumentation_document["rawSha256"],
        }
        evidence["processCold"] = observe_process_cold(adb, args.run_id)
        postflight = collect_release_device(adb)
        evidence["postflight"] = postflight
        physical.write_json_atomic(
            args.evidence_dir / "postflight.json",
            {"runId": args.run_id, "observedAt": _utc_now(), "device": postflight},
        )
        artifacts_after = _snapshot_artifacts(build_root)
        evidence["artifactsAfter"] = artifacts_after
        evidence["artifactsStable"] = artifacts_after == artifacts_before
        if not evidence["artifactsStable"]:
            raise ReleaseEvidenceError("source-snapshot artifacts changed during device run")
    except (Exception, KeyboardInterrupt) as error:
        evidence["error"] = physical.safe_failure("S0-g device evidence", error)
        exit_code = 2
    finally:
        cleanup_errors: list[str] = []
        if adb is not None:
            _ = adb.run("shell", "am", "force-stop", TARGET_PACKAGE, check=False)
            for package_name, owned in (
                (TEST_PACKAGE, test_owned),
                (TARGET_PACKAGE, target_owned),
            ):
                if owned and not _remove_owned_package(adb, package_name):
                    cleanup_errors.append(f"cannot remove temporary {package_name}")
            try:
                physical.assert_package_absent(adb, TEST_PACKAGE)
                physical.assert_package_absent(adb, TARGET_PACKAGE)
            except Exception:
                cleanup_errors.append("temporary release-admission packages remain installed")
        evidence["temporaryPackagesRemoved"] = not cleanup_errors
        if cleanup_errors:
            evidence["packageCleanupError"] = "; ".join(cleanup_errors)
            exit_code = 2

        if staging_context is not None:
            try:
                staging_context.cleanup()
                evidence["temporaryStagingRemoved"] = True
            except Exception as error:
                evidence["temporaryStagingRemoved"] = False
                evidence["stagingCleanupError"] = physical.safe_failure(
                    "temporary staging cleanup", error
                )
                exit_code = 2
        else:
            evidence["temporaryStagingRemoved"] = True
        if source_context is not None:
            try:
                source_context.cleanup()
                evidence["temporarySourceSnapshotRemoved"] = True
            except Exception as error:
                evidence["temporarySourceSnapshotRemoved"] = False
                evidence["sourceSnapshotCleanupError"] = physical.safe_failure(
                    "source snapshot cleanup", error
                )
                exit_code = 2
        else:
            evidence["temporarySourceSnapshotRemoved"] = True

        try:
            source_end = physical.collect_source_state()
            evidence["sourceAtEnd"] = source_end
            source_start = evidence.get("sourceAtStart")
            if (
                not isinstance(source_start, dict)
                or not source_end["worktreeClean"]
                or source_end["commit"] != source_start.get("commit")
            ):
                evidence["sourceStateError"] = (
                    "durable evidence requires the same clean commit at both ends"
                )
                exit_code = 2
        except Exception as error:
            evidence["sourceStateError"] = physical.safe_failure(
                "source state verification", error
            )
            exit_code = 2

        evidence["evidenceDirectory"] = args.evidence_dir.name
        evidence_files: dict[str, dict[str, Any]] = {}
        if evidence_owned:
            for name in (
                "preflight.json",
                "postflight.json",
                "instrumentation-result.json",
            ):
                evidence_path = args.evidence_dir / name
                if evidence_path.is_file() and not evidence_path.is_symlink():
                    evidence_files[name] = {
                        "sha256": physical.file_sha256(evidence_path),
                        "size": evidence_path.stat().st_size,
                    }
        evidence["evidenceFiles"] = evidence_files
        if set(evidence_files) != {
            "preflight.json",
            "postflight.json",
            "instrumentation-result.json",
        }:
            exit_code = 2
        evidence["exitCode"] = exit_code
        evidence["verdict"] = "pass" if exit_code == 0 else "fail"
        evidence["finishedAt"] = _utc_now()
        if evidence_owned:
            physical.write_json_atomic(args.evidence_dir / "run.json", evidence)
        if lock is not None:
            physical.release_run_lock(lock)

    print(
        json.dumps(
            {
                "runId": args.run_id,
                "sourceCommit": (evidence.get("sourceAtStart") or {}).get("commit"),
                "verdict": evidence["verdict"],
            },
            sort_keys=True,
        )
    )
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
