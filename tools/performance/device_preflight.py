#!/usr/bin/env python3
"""Guarded SM-F711N preflight and S0-f launcher-smoke runner.

The script never simulates battery state, clears app data, or unlocks the device. It
temporarily pins refresh/animation settings, journals their originals before mutation,
and restores them in finally (or at the start of the next run after an uncatchable exit).
"""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import math
import os
import re
import shutil
import signal
import stat
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence


ROOT = Path(__file__).resolve().parents[2]
DEVICE_STATE_DIR = Path.home() / ".cache" / "fxi" / "benchmark-sm-f711n"
JOURNAL = DEVICE_STATE_DIR / "restore.json"
RUN_LOCK = DEVICE_STATE_DIR / "runner.lock"
TARGET_PACKAGE = "com.jay.fxi"
TEST_PACKAGE = "com.jay.fxi.macrobenchmark"
EXPECTED_MODEL = "SM-F711N"
EXPECTED_API = "35"
EXPECTED_FINGERPRINT = (
    "samsung/b2qksx/b2q:15/AP3A.240905.015.A2/"
    "F711NKSSEKZE1:user/release-keys"
)
EXPECTED_SIZE = "1080x2640"
BENCHMARK_FIREBASE_CONFIG = ROOT / "app" / "src" / "benchmark" / "google-services.json"
CI_FIREBASE_FIXTURE = ROOT / "ci" / "google-services.ci-fixture.json"
TARGET_BENCHMARK_APK = (
    ROOT / "app" / "build" / "outputs" / "apk" / "benchmark" / "app-benchmark.apk"
)
TEST_BENCHMARK_APK = (
    ROOT
    / "macrobenchmark"
    / "build"
    / "outputs"
    / "apk"
    / "benchmark"
    / "macrobenchmark-benchmark.apk"
)
LAUNCHER_TASK = "adb-am-instrument"
LAUNCHER_CLASS = "com.jay.fxi.macrobenchmark.LauncherSmokeBenchmark"
LAUNCHER_METHOD = "launcherSmoke"
INSTRUMENTATION_COMPONENT = (
    "com.jay.fxi.macrobenchmark/androidx.test.runner.AndroidJUnitRunner"
)
DEVICE_OUTPUT_ROOT = (
    "/sdcard/Android/media/com.jay.fxi.macrobenchmark/additional_test_output"
)
SETTING_KEYS = [
    ("system", "peak_refresh_rate", "120.0"),
    ("system", "min_refresh_rate", "120.0"),
    ("global", "window_animation_scale", "1.0"),
    ("global", "transition_animation_scale", "1.0"),
    ("global", "animator_duration_scale", "1.0"),
]


class PreflightError(RuntimeError):
    pass


def safe_failure(label: str, error: BaseException) -> str:
    """Return a non-secret cleanup error suitable for sanitized evidence."""
    if isinstance(error, PreflightError):
        return str(error)
    if isinstance(error, KeyboardInterrupt):
        return f"{label} was interrupted"
    return f"{label} failed ({type(error).__name__})"


def acquire_run_lock() -> int:
    try:
        RUN_LOCK.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        directory_stat = RUN_LOCK.parent.lstat()
    except OSError as error:
        raise PreflightError(f"cannot prepare private device-state directory: {error}") from error
    if (
        not stat.S_ISDIR(directory_stat.st_mode)
        or directory_stat.st_uid != os.getuid()
        or stat.S_IMODE(directory_stat.st_mode) & 0o077
    ):
        raise PreflightError("device-state directory must be a private user-owned directory")
    flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(RUN_LOCK, flags, 0o600)
    except OSError as error:
        raise PreflightError(f"cannot open secure global runner lock: {error}") from error
    lock_stat = os.fstat(descriptor)
    if (
        not stat.S_ISREG(lock_stat.st_mode)
        or lock_stat.st_uid != os.getuid()
        or stat.S_IMODE(lock_stat.st_mode) & 0o077
    ):
        os.close(descriptor)
        raise PreflightError("global runner lock must be a private user-owned regular file")
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as error:
        os.close(descriptor)
        raise PreflightError("another benchmark preflight owns the runner lock") from error
    os.ftruncate(descriptor, 0)
    os.write(descriptor, f"pid={os.getpid()}\n".encode("ascii"))
    return descriptor


def release_run_lock(descriptor: int) -> None:
    fcntl.flock(descriptor, fcntl.LOCK_UN)
    os.close(descriptor)


def parse_devices(output: str) -> list[str]:
    return [
        line.split()[0]
        for line in output.splitlines()[1:]
        if line.strip() and len(line.split()) >= 2 and line.split()[1] == "device"
    ]


def parse_battery(output: str) -> dict[str, Any]:
    if "UPDATES STOPPED" in output.upper():
        raise PreflightError("dumpsys battery is simulated (UPDATES STOPPED)")
    fields: dict[str, str] = {}
    for line in output.splitlines():
        if ":" in line:
            key, value = line.strip().split(":", 1)
            fields[key.strip().lower()] = value.strip().lower()
    powered: dict[str, bool] = {}
    for label in ("ac powered", "usb powered", "wireless powered", "dock powered"):
        if label in fields:
            if fields[label] not in {"true", "false"}:
                raise PreflightError(
                    f"dumpsys battery returned non-boolean {label}: {fields[label]!r}"
                )
            powered[label] = fields[label] == "true"
    required = {"ac powered", "usb powered", "wireless powered", "dock powered"}
    if not required.issubset(powered):
        raise PreflightError("dumpsys battery omitted a required power-source field")
    try:
        level = int(fields["level"])
    except (KeyError, ValueError) as error:
        raise PreflightError("dumpsys battery omitted a numeric level") from error
    return {"level": level, "powered": powered}


def parse_wm_size(output: str) -> tuple[str, str | None]:
    physical = re.search(r"Physical size:\s*(\d+x\d+)", output)
    override = re.search(r"Override size:\s*(\d+x\d+)", output)
    if not physical:
        raise PreflightError("wm size omitted Physical size")
    return physical.group(1), override.group(1) if override else None


def parse_active_main_refresh_rate(
    output: str, expected_size: str = EXPECTED_SIZE
) -> float:
    """Return the active render rate for the unfolded main display.

    A supported 120 Hz mode is not proof that the panel is currently using it.
    Samsung's Android 15 display dump reports the live value on the
    ``DisplayDeviceInfo`` row.  Select only the ON internal display whose
    physical dimensions match the frozen unfolded panel and fail on missing or
    conflicting observations.
    """
    expected_width, expected_height = expected_size.split("x", 1)
    observations: set[float] = set()
    for line in output.splitlines():
        if "DisplayDeviceInfo{" not in line or "type INTERNAL" not in line:
            continue
        size = re.search(r'uniqueId="[^"]+",\s*(\d+)\s*x\s*(\d+),', line)
        state = re.search(r"\bstate\s+(\w+)", line)
        refresh = re.search(r"\brenderFrameRate\s+([0-9]+(?:\.[0-9]+)?)", line)
        if not size or not state or not refresh:
            continue
        if (size.group(1), size.group(2)) != (expected_width, expected_height):
            continue
        if state.group(1) != "ON":
            continue
        rate = float(refresh.group(1))
        if not math.isfinite(rate) or rate <= 0:
            raise PreflightError("active main display reported an invalid render frame rate")
        observations.add(rate)
    if len(observations) != 1:
        raise PreflightError(
            "dumpsys display must expose exactly one active unfolded-main refresh rate"
        )
    return observations.pop()


def parse_surfaceflinger_active_main_refresh_rate(
    output: str, expected_size: str = EXPECTED_SIZE
) -> float:
    """Return SurfaceFlinger's physical active mode for the unfolded panel."""
    observations: set[float] = set()
    for block in re.split(r"(?m)^Display\s+\d+\s*$", output)[1:]:
        if not re.search(r"(?m)^\s*connectionType=Internal\s*$", block):
            continue
        if not re.search(r"(?m)^\s*powerMode=ON\s*$", block):
            continue
        active = re.search(
            r"(?m)^\s*activeMode=\{[^\n]*resolution=(\d+x\d+),\s*"
            r"vsyncRate=([0-9]+(?:\.[0-9]+)?)\s*Hz",
            block,
        )
        render = re.search(
            r"(?m)^\s*renderRate=([0-9]+(?:\.[0-9]+)?)\s*Hz\s*$", block
        )
        if not active or active.group(1) != expected_size or not render:
            continue
        vsync_rate = float(active.group(2))
        render_rate = float(render.group(1))
        if (
            not math.isfinite(vsync_rate)
            or not math.isfinite(render_rate)
            or vsync_rate <= 0
            or render_rate <= 0
            or not math.isclose(vsync_rate, render_rate, rel_tol=0.0, abs_tol=0.01)
        ):
            raise PreflightError(
                "SurfaceFlinger reported conflicting active main-display rates"
            )
        observations.add(vsync_rate)
    if len(observations) != 1:
        raise PreflightError(
            "SurfaceFlinger must expose exactly one active unfolded-main refresh rate"
        )
    return observations.pop()


def collect_active_refresh_rates(
    adb: Adb, *, display_dump: str | None = None, require_120hz: bool = False
) -> dict[str, Any]:
    """Cross-check DisplayManager and SurfaceFlinger live refresh observations."""
    manager_rate = parse_active_main_refresh_rate(
        display_dump if display_dump is not None else adb.shell("dumpsys", "display")
    )
    surfaceflinger_rate = parse_surfaceflinger_active_main_refresh_rate(
        adb.shell("dumpsys", "SurfaceFlinger", "--displays")
    )
    if not math.isclose(
        manager_rate, surfaceflinger_rate, rel_tol=0.0, abs_tol=0.01
    ):
        raise PreflightError(
            "DisplayManager and SurfaceFlinger disagree on the active main-display rate"
        )
    if require_120hz and not math.isclose(
        surfaceflinger_rate, 120.0, rel_tol=0.0, abs_tol=0.01
    ):
        raise PreflightError(
            "active unfolded-main display must render at 120Hz, "
            f"got {surfaceflinger_rate}Hz"
        )
    return {
        "displayManagerHz": manager_rate,
        "surfaceFlingerHz": surfaceflinger_rate,
        "observedAt": datetime.now(timezone.utc).isoformat(),
    }


def wait_for_active_120hz(adb: Adb, timeout_seconds: float = 5.0) -> None:
    """Wait read-only for two consecutive dual-oracle 120 Hz observations."""
    deadline = time.monotonic() + timeout_seconds
    consecutive = 0
    last_error: PreflightError | None = None
    while time.monotonic() < deadline:
        try:
            collect_active_refresh_rates(adb, require_120hz=True)
            consecutive += 1
            if consecutive == 2:
                return
        except PreflightError as error:
            last_error = error
            consecutive = 0
        time.sleep(0.2)
    detail = f": {last_error}" if last_error is not None else ""
    raise PreflightError(
        f"active unfolded-main display did not stabilize at 120Hz{detail}"
    )


def parse_active_internal_viewport(
    output: str, display_id: int = 0
) -> tuple[int, str]:
    """Return the active internal viewport orientation and device size.

    Samsung's Android 15 input dump no longer exposes the older
    ``SurfaceOrientation`` field. The same dump can repeat viewport rows in
    multiple sections, so identical duplicates are accepted while conflicting
    active rows fail closed.
    """
    active_viewports: set[tuple[int, str]] = set()
    for line in output.splitlines():
        if "Viewport INTERNAL:" not in line:
            continue
        viewport_display = re.search(r"\bdisplayId=(\d+)\b", line)
        if not viewport_display or int(viewport_display.group(1)) != display_id:
            continue
        orientation = re.search(r"\borientation=(\d+)\b", line)
        device_size = re.search(r"\bdeviceSize=\[(\d+),\s*(\d+)\]", line)
        active = re.search(r"\bisActive=\[(0|1|false|true)\]", line, re.IGNORECASE)
        if not orientation or not device_size or not active:
            raise PreflightError(
                f"active internal displayId={display_id} viewport fields are incomplete"
            )
        if active.group(1).lower() in {"1", "true"}:
            active_viewports.add(
                (
                    int(orientation.group(1)),
                    f"{device_size.group(1)}x{device_size.group(2)}",
                )
            )
    if not active_viewports:
        raise PreflightError(f"cannot find active internal displayId={display_id} viewport")
    if len(active_viewports) != 1:
        raise PreflightError(
            f"active internal displayId={display_id} viewport is inconsistent: "
            f"{sorted(active_viewports)}"
        )
    return next(iter(active_viewports))


def parse_device_state_name(dumpsys: str, current_output: str) -> str:
    current_match = re.search(r"\b(\d+)\b", current_output)
    if not current_match:
        current_match = re.search(r"mCommittedState\s*=\s*(\d+)", dumpsys)
    if not current_match:
        raise PreflightError("cannot determine committed device-state identifier")
    identifier = current_match.group(1)
    patterns = [
        rf"identifier\s*=\s*{identifier}\b[^\n]*?name\s*=\s*['\"]?([A-Za-z0-9_-]+)",
        rf"DeviceState\{{\s*identifier\s*=\s*{identifier}\b[^\n]*?\b([A-Z][A-Z0-9_-]+)\b",
    ]
    for pattern in patterns:
        match = re.search(pattern, dumpsys, re.IGNORECASE)
        if match:
            return match.group(1).upper()
    if re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", current_output.strip()):
        return current_output.strip().upper()
    raise PreflightError(f"cannot map device-state identifier {identifier} to a name")


def collect_source_state() -> dict[str, Any]:
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if commit.returncode != 0 or not re.fullmatch(r"[0-9a-f]{40}", commit.stdout.strip()):
        raise PreflightError("cannot resolve a 40-hex source commit")
    status = subprocess.run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if status.returncode != 0:
        raise PreflightError("cannot determine source worktree state")
    # Paths are deliberately not persisted: only whether the source was clean is evidence.
    return {
        "commit": commit.stdout.strip(),
        "worktreeClean": not bool(status.stdout),
    }


def find_adb() -> str:
    candidates = []
    if os.environ.get("ANDROID_HOME"):
        candidates.append(Path(os.environ["ANDROID_HOME"]) / "platform-tools" / "adb")
    candidates.append(Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / "adb")
    from_path = shutil.which("adb")
    if from_path:
        candidates.append(Path(from_path))
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    raise PreflightError("adb not found; set ANDROID_HOME or install platform-tools")


@dataclass
class Adb:
    executable: str
    serial: str

    def run(
        self,
        *arguments: str,
        check: bool = True,
        timeout: int | None = None,
    ) -> subprocess.CompletedProcess[str]:
        try:
            result = subprocess.run(
                [self.executable, "-s", self.serial, *arguments],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as error:
            raise PreflightError(
                f"adb command timed out ({' '.join(arguments)})"
            ) from error
        except OSError as error:
            raise PreflightError(
                f"cannot execute adb command ({' '.join(arguments)})"
            ) from error
        if check and result.returncode != 0:
            redacted_error = result.stderr.strip().replace(self.serial, "<redacted-device>")
            raise PreflightError(
                f"adb command failed ({' '.join(arguments)}): {redacted_error}"
            )
        return result

    def shell(self, *arguments: str, check: bool = True) -> str:
        return self.run("shell", *arguments, check=check).stdout.strip()


def connected_adb() -> Adb:
    executable = find_adb()
    result = subprocess.run(
        [executable, "devices", "-l"], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    if result.returncode != 0:
        raise PreflightError(f"adb devices failed: {result.stderr.strip()}")
    devices = parse_devices(result.stdout)
    if len(devices) != 1:
        raise PreflightError(f"exactly one authorized device is required; found {len(devices)}")
    return Adb(executable, devices[0])


def setting_get(adb: Adb, namespace: str, key: str) -> str:
    return adb.shell("settings", "get", namespace, key)


def setting_write(adb: Adb, namespace: str, key: str, value: str) -> None:
    if value == "null":
        adb.shell("settings", "delete", namespace, key)
    else:
        adb.shell("settings", "put", namespace, key, value)
    actual = setting_get(adb, namespace, key)
    expected = "null" if value == "null" else value
    if actual != expected:
        raise PreflightError(f"settings read-back mismatch for {namespace}/{key}: {actual}")


def read_identity(adb: Adb) -> dict[str, Any]:
    return {
        "model": adb.shell("getprop", "ro.product.model"),
        "fingerprint": adb.shell("getprop", "ro.build.fingerprint"),
        "api": int(adb.shell("getprop", "ro.build.version.sdk")),
    }


def device_user_ids(adb: Adb) -> list[str]:
    result = adb.run("shell", "pm", "list", "users", check=False)
    if result.returncode != 0:
        redacted_error = result.stderr.strip().replace(adb.serial, "<redacted-device>")
        raise PreflightError(f"cannot enumerate Android users: {redacted_error}")
    user_ids = re.findall(r"UserInfo\{(\d+):", result.stdout)
    if not user_ids or len(user_ids) != len(set(user_ids)):
        raise PreflightError("pm list users returned no unique Android user ids")
    return user_ids


def assert_package_absent(adb: Adb, package_name: str) -> None:
    for user_id in device_user_ids(adb):
        result = adb.run(
            "shell", "pm", "path", "--user", user_id, package_name, check=False
        )
        stdout = result.stdout.strip()
        stderr = result.stderr.strip()
        # AOSP `pm path` returns 1 with no output when PackageInfo is absent.
        if result.returncode == 1 and not stdout and not stderr:
            continue
        if result.returncode == 0 and stdout and all(
            line.startswith("package:") for line in stdout.splitlines()
        ):
            raise PreflightError(
                f"{package_name} is already installed for an Android user; "
                "refusing to replace or clear an existing app"
            )
        redacted_error = stderr.replace(adb.serial, "<redacted-device>")
        raise PreflightError(
            f"cannot verify whether {package_name} is installed for every Android user "
            f"(rc={result.returncode}, stdout_present={bool(stdout)}): {redacted_error}"
        )


def collect_immutable_preflight(
    adb: Adb, *, require_active_120hz: bool = False
) -> dict[str, Any]:
    identity = read_identity(adb)
    if identity != {
        "model": EXPECTED_MODEL,
        "fingerprint": EXPECTED_FINGERPRINT,
        "api": int(EXPECTED_API),
    }:
        raise PreflightError(f"device identity differs from frozen D31 contract: {identity}")

    state_dump = adb.shell("dumpsys", "device_state")
    current_state = adb.shell("cmd", "device_state", "print-state", check=False)
    state_name = parse_device_state_name(state_dump, current_state)
    if state_name != "OPENED":
        raise PreflightError(f"device posture must be OPENED, got {state_name}")

    physical, override = parse_wm_size(adb.shell("wm", "size"))
    if physical != EXPECTED_SIZE or (override is not None and override != EXPECTED_SIZE):
        raise PreflightError(f"display size must be {EXPECTED_SIZE}, got {physical}/{override}")
    orientation, viewport_size = parse_active_internal_viewport(
        adb.shell("dumpsys", "input")
    )
    if viewport_size != EXPECTED_SIZE:
        raise PreflightError(
            f"active inner viewport must be {EXPECTED_SIZE}, got {viewport_size}"
        )
    if orientation != 0:
        raise PreflightError(f"inner display must be portrait (orientation 0), got {orientation}")

    display_dump = adb.shell("dumpsys", "display")
    if not re.search(r"fps=120(?:\.0+)?", display_dump):
        raise PreflightError("display does not report a 120Hz mode")
    active_refresh = collect_active_refresh_rates(
        adb,
        display_dump=display_dump,
        require_120hz=require_active_120hz,
    )

    low_power = setting_get(adb, "global", "low_power")
    if low_power not in {"0", "null"}:
        raise PreflightError(f"low-power mode must be off, got {low_power}")
    battery = parse_battery(adb.shell("dumpsys", "battery"))
    if battery["level"] < 30:
        raise PreflightError(f"battery must be at least 30%, got {battery['level']}%")
    active_power = [name for name, active in battery["powered"].items() if active]
    if active_power:
        raise PreflightError(f"charging power must be disconnected: {active_power}")
    thermal_dump = adb.shell("dumpsys", "thermalservice")
    thermal = re.search(r"Thermal Status:\s*(\d+)", thermal_dump, re.IGNORECASE)
    if not thermal or thermal.group(1) != "0":
        raise PreflightError("thermal status must be NONE(0)")

    return {
        **identity,
        "posture": state_name,
        "physicalSize": physical,
        "overrideSize": override,
        "orientation": orientation,
        "supports120Hz": True,
        "activeRefreshRateHz": active_refresh["surfaceFlingerHz"],
        "lowPower": False,
        "batteryLevel": battery["level"],
        "powered": battery["powered"],
        "thermalStatus": 0,
    }


def restore_from_journal(adb: Adb) -> dict[str, str] | None:
    if not JOURNAL.exists():
        return None
    try:
        journal = json.loads(JOURNAL.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise PreflightError(f"cannot read restore journal {JOURNAL}: {error}") from error
    if not isinstance(journal, dict):
        raise PreflightError("restore journal must contain a JSON object")
    if journal.get("fingerprint") != adb.shell("getprop", "ro.build.fingerprint"):
        raise PreflightError("restore journal belongs to a different device fingerprint")
    originals = journal.get("settings")
    if not isinstance(originals, dict):
        raise PreflightError("restore journal has no settings map")
    # Restore min before peak; null means delete rather than writing the word "null".
    order = [
        "system/min_refresh_rate",
        "system/peak_refresh_rate",
        "global/window_animation_scale",
        "global/transition_animation_scale",
        "global/animator_duration_scale",
    ]
    for compound in order:
        namespace, key = compound.split("/", 1)
        if compound not in originals:
            raise PreflightError(f"restore journal missing {compound}")
        setting_write(adb, namespace, key, str(originals[compound]))
    try:
        JOURNAL.unlink()
    except OSError as error:
        raise PreflightError(f"cannot remove restored settings journal: {error}") from error
    return {key: str(value) for key, value in originals.items()}


def write_json_atomic(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def prepare_settings(adb: Adb) -> dict[str, str]:
    originals = {
        f"{namespace}/{key}": setting_get(adb, namespace, key)
        for namespace, key, _ in SETTING_KEYS
    }
    write_json_atomic(
        JOURNAL,
        {
            "fingerprint": adb.shell("getprop", "ro.build.fingerprint"),
            "settings": originals,
        },
    )
    # Peak before min avoids a transient min > peak inversion. Restoration uses min first.
    for namespace, key, target in SETTING_KEYS:
        setting_write(adb, namespace, key, target)
    return originals


def collect_settings(adb: Adb) -> dict[str, str]:
    return {
        f"{namespace}/{key}": setting_get(adb, namespace, key)
        for namespace, key, _ in SETTING_KEYS
    }


def materialize_commit_snapshot(
    commit: str,
) -> tuple[tempfile.TemporaryDirectory[str], Path]:
    """Materialize exactly one committed tree, independent of worktree mutations."""
    tree = subprocess.run(
        ["git", "ls-tree", "-r", commit],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if tree.returncode != 0:
        raise PreflightError("cannot inspect the committed source tree")
    if any(line.startswith(("120000 ", "160000 ")) for line in tree.stdout.splitlines()):
        raise PreflightError("durable source snapshot does not allow symlinks or submodules")
    context = tempfile.TemporaryDirectory(prefix="source-", dir=DEVICE_STATE_DIR)
    destination = Path(context.name)
    destination.chmod(0o700)
    tar = shutil.which("tar")
    if tar is None:
        context.cleanup()
        raise PreflightError("tar is required to materialize the committed source tree")
    archive = subprocess.Popen(
        ["git", "archive", "--format=tar", commit],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert archive.stdout is not None
    extraction = subprocess.run(
        [tar, "-x", "-C", str(destination)],
        stdin=archive.stdout,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    archive.stdout.close()
    archive_stderr = archive.stderr.read() if archive.stderr is not None else b""
    if archive.stderr is not None:
        archive.stderr.close()
    archive_return_code = archive.wait()
    if archive_return_code != 0 or extraction.returncode != 0:
        context.cleanup()
        _ = archive_stderr
        raise PreflightError("cannot materialize the committed source tree")
    return context, destination


def benchmark_paths(root: Path) -> tuple[Path, Path, Path, Path]:
    return (
        root / TARGET_BENCHMARK_APK.relative_to(ROOT),
        root / TEST_BENCHMARK_APK.relative_to(ROOT),
        root / BENCHMARK_FIREBASE_CONFIG.relative_to(ROOT),
        root / CI_FIREBASE_FIXTURE.relative_to(ROOT),
    )


def run_benchmark_build(
    build_root: Path = ROOT,
    target_apk: Path | None = None,
    test_apk: Path | None = None,
) -> int:
    """Build fresh APK outputs without using a connected-device Gradle task."""
    target_apk = target_apk or TARGET_BENCHMARK_APK
    test_apk = test_apk or TEST_BENCHMARK_APK
    for output in (target_apk, test_apk):
        if output.exists() or output.is_symlink():
            if output.is_dir() and not output.is_symlink():
                raise PreflightError(
                    "benchmark APK output is unexpectedly a directory"
                )
            output.unlink()
    command = [
        str(build_root / "gradlew"),
        ":app:assembleBenchmark",
        ":macrobenchmark:assembleBenchmark",
        "--console=plain",
        "--no-daemon",
        "--no-configuration-cache",
        "--rerun-tasks",
    ]
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
            raise PreflightError("Android SDK path is not configured")
        sdk = str(default_sdk)
    environment["ANDROID_HOME"] = sdk
    environment["ANDROID_SDK_ROOT"] = sdk
    original_gradle_home = Path(
        os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")
    )
    with tempfile.TemporaryDirectory(prefix="gradle-home-", dir=DEVICE_STATE_DIR) as temp:
        gradle_home = Path(temp)
        for reusable in ("caches", "wrapper", "jdks"):
            source = original_gradle_home / reusable
            destination = gradle_home / reusable
            if source.exists():
                destination.symlink_to(source, target_is_directory=True)
        environment["GRADLE_USER_HOME"] = str(gradle_home)
        return subprocess.run(command, cwd=build_root, env=environment).returncode


def run_launcher_smoke(
    adb: Adb, run_id: str
) -> subprocess.CompletedProcess[str]:
    output_directory = f"{DEVICE_OUTPUT_ROOT}/{run_id}"
    return adb.run(
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        f"{LAUNCHER_CLASS}#{LAUNCHER_METHOD}",
        "-e",
        "additionalTestOutputDir",
        output_directory,
        "-e",
        "testTimeoutSeconds",
        "600",
        INSTRUMENTATION_COMPONENT,
        check=False,
        timeout=660,
    )


def stable_file_identity(path: Path, recorded_path: str) -> dict[str, Any]:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise PreflightError(f"expected regular artifact missing: {recorded_path}") from error
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise PreflightError(f"artifact is not a regular file: {recorded_path}")
        digest = hashlib.sha256()
        size = 0
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
        after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    identity_before = (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    )
    identity_after = (
        after.st_dev,
        after.st_ino,
        after.st_size,
        after.st_mtime_ns,
        after.st_ctime_ns,
    )
    if identity_before != identity_after or size != before.st_size:
        raise PreflightError(f"artifact changed while hashing: {recorded_path}")
    return {"path": recorded_path, "sha256": digest.hexdigest(), "size": size}


def file_sha256(path: Path) -> str:
    return stable_file_identity(path, path.name)["sha256"]


def artifact_identity(path: Path, root: Path = ROOT) -> dict[str, Any]:
    try:
        recorded_path = path.relative_to(root).as_posix()
    except ValueError as error:
        raise PreflightError("artifact path must be inside the repository") from error
    return stable_file_identity(path, recorded_path)


def copy_stable_artifact(
    source: Path, destination: Path, root: Path = ROOT
) -> dict[str, Any]:
    before = artifact_identity(source, root)
    if destination.exists() or destination.is_symlink():
        raise PreflightError("private staged artifact destination already exists")
    try:
        shutil.copyfile(source, destination)
        destination.chmod(0o400)
    except OSError as error:
        raise PreflightError("cannot stage a private benchmark artifact") from error
    staged = stable_file_identity(destination, before["path"])
    after = artifact_identity(source, root)
    if before != staged or before != after:
        raise PreflightError(f"artifact changed while staging: {before['path']}")
    return staged


def install_apk(adb: Adb, path: Path) -> None:
    result = adb.run(
        "install",
        "--no-streaming",
        str(path),
        check=False,
        timeout=180,
    )
    if result.returncode != 0 or "Success" not in result.stdout.splitlines():
        raise PreflightError("adb could not install a staged benchmark APK")


def current_user_id(adb: Adb) -> str:
    user_id = adb.shell("am", "get-current-user")
    if not re.fullmatch(r"\d+", user_id):
        raise PreflightError("cannot determine the current Android user")
    return user_id


def installed_apk_identity(
    adb: Adb,
    package_name: str,
    expected: dict[str, Any],
    destination: Path,
) -> dict[str, Any]:
    result = adb.run(
        "shell",
        "pm",
        "path",
        "--user",
        current_user_id(adb),
        package_name,
        check=False,
    )
    paths = [
        line.removeprefix("package:")
        for line in result.stdout.splitlines()
        if line.startswith("package:")
    ]
    if result.returncode != 0 or len(paths) != 1 or not paths[0].endswith("/base.apk"):
        raise PreflightError(f"installed {package_name} does not have exactly one base APK")
    pull = adb.run("pull", paths[0], str(destination), check=False, timeout=180)
    if pull.returncode != 0:
        raise PreflightError(f"cannot read back installed {package_name} APK")
    actual = stable_file_identity(destination, expected["path"])
    if actual != expected:
        raise PreflightError(f"installed {package_name} APK differs from staged bytes")
    return {
        "sha256": actual["sha256"],
        "size": actual["size"],
        "matchesStagedArtifact": True,
    }


def remove_device_output(adb: Adb, run_id: str) -> bool:
    output_directory = f"{DEVICE_OUTPUT_ROOT}/{run_id}"
    removal = adb.run("shell", "rm", "-rf", output_directory, check=False)
    if removal.returncode != 0:
        return False
    check = adb.run("shell", "test", "!", "-e", output_directory, check=False)
    return check.returncode == 0


def capture_launcher_result(
    adb: Adb,
    evidence_dir: Path,
    result: subprocess.CompletedProcess[str],
    run_id: str,
) -> dict[str, Any]:
    raw = (result.stdout + "\n" + result.stderr).encode("utf-8")
    if adb.serial.encode("utf-8") in raw:
        raise PreflightError("launcher instrumentation output contains the ADB serial")
    text = raw.decode("utf-8")
    status_codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", text, re.MULTILINE)
    final_codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.MULTILINE)
    classes = re.findall(r"^INSTRUMENTATION_STATUS: class=(.+)$", text, re.MULTILINE)
    methods = re.findall(r"^INSTRUMENTATION_STATUS: test=(.+)$", text, re.MULTILINE)
    currents = re.findall(r"^INSTRUMENTATION_STATUS: current=(.+)$", text, re.MULTILINE)
    test_counts = re.findall(
        r"^INSTRUMENTATION_STATUS: numtests=(.+)$", text, re.MULTILINE
    )
    trace_paths = re.findall(
        r"^INSTRUMENTATION_STATUS: additionalTestOutputFile_[^=]+=(.+)$",
        text,
        re.MULTILINE,
    )
    exact_status_sequence = (
        status_codes
        and status_codes[0] == "1"
        and status_codes[-1] == "0"
        and status_codes.count("1") == 1
        and status_codes.count("0") == 1
        and all(code == "2" for code in status_codes[1:-1])
    )
    if (
        result.returncode != 0
        or final_codes != ["-1"]
        or not exact_status_sequence
        or "2" not in status_codes
        or classes != [LAUNCHER_CLASS, LAUNCHER_CLASS]
        or methods != [LAUNCHER_METHOD, LAUNCHER_METHOD]
        or currents != ["1", "1"]
        or test_counts != ["1", "1"]
        or len(trace_paths) != 1
        or not trace_paths[0].startswith(f"{DEVICE_OUTPUT_ROOT}/{run_id}/")
        or not re.search(
            r"^INSTRUMENTATION_STATUS: time_to_initial_display_millis_median=\d+(?:\.\d+)?$",
            text,
            re.MULTILINE,
        )
        or len(re.findall(r"^OK \(1 test\)\s*$", text, re.MULTILINE)) != 1
        or "FAILURES!!!" in text
        or "INSTRUMENTATION_FAILED" in text
    ):
        raise PreflightError("launcher instrumentation is not exactly one green test")
    duration_match = re.search(r"^Time:\s*(\d+(?:\.\d+)?)\s*$", text, re.MULTILINE)
    if not duration_match:
        raise PreflightError("launcher instrumentation omitted its test duration")
    destination = evidence_dir / "launcher-result.xml"
    if destination.exists():
        raise PreflightError("launcher-result.xml already exists in the evidence directory")
    expected_counts = {"tests": "1", "failures": "0", "errors": "0", "skipped": "0"}
    sanitized_suite = ET.Element("testsuite", {"name": LAUNCHER_CLASS, **expected_counts})
    ET.SubElement(
        sanitized_suite,
        "testcase",
        {
            "name": LAUNCHER_METHOD,
            "classname": LAUNCHER_CLASS,
            "time": duration_match.group(1),
        },
    )
    ET.indent(sanitized_suite, space="  ")
    ET.ElementTree(sanitized_suite).write(
        destination,
        encoding="utf-8",
        xml_declaration=True,
    )
    return {
        "class": LAUNCHER_CLASS,
        "method": LAUNCHER_METHOD,
        "task": LAUNCHER_TASK,
        "result": {
            "path": destination.name,
            "sha256": file_sha256(destination),
            "size": destination.stat().st_size,
            "sourceSha256": hashlib.sha256(raw).hexdigest(),
        },
        **{key: int(value) for key, value in expected_counts.items()},
    }


def prepare_nonproduction_firebase_fixture(
    canonical_fixture: Path | None = None,
    benchmark_input: Path | None = None,
) -> bool:
    canonical_fixture = canonical_fixture or CI_FIREBASE_FIXTURE
    benchmark_input = benchmark_input or BENCHMARK_FIREBASE_CONFIG
    if not canonical_fixture.is_file():
        raise PreflightError("checked-in Firebase fixture is missing")
    expected = file_sha256(canonical_fixture)
    if benchmark_input.exists():
        if benchmark_input.is_symlink():
            raise PreflightError("benchmark google-services.json must not be a symlink")
        if file_sha256(benchmark_input) != expected:
            raise PreflightError(
                "benchmark google-services.json exists but is not the checked-in non-production fixture"
            )
        # A prior SIGKILL may have left the exact temporary fixture behind. Claim the
        # matching non-production copy so this normal run removes it in finally.
        return True
    benchmark_input.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".google-services.",
        suffix=".tmp",
        dir=benchmark_input.parent,
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        shutil.copyfile(canonical_fixture, temporary)
        if file_sha256(temporary) != expected:
            raise PreflightError("copied benchmark Firebase fixture failed SHA-256 verification")
        try:
            # Hard-link publish is atomic and refuses to replace a file that appeared
            # after the initial absence check. Both paths are in the same directory.
            os.link(temporary, benchmark_input)
        except FileExistsError as error:
            raise PreflightError(
                "benchmark google-services.json appeared during fixture preparation"
            ) from error
    except PreflightError:
        raise
    except OSError as error:
        raise PreflightError(f"cannot prepare benchmark Firebase fixture: {error}") from error
    finally:
        temporary.unlink(missing_ok=True)
    return True


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--run-launcher-smoke",
        action="store_true",
        help="run the benchmark-only no-data launcher surface after preflight",
    )
    parser.add_argument(
        "--evidence-dir",
        type=Path,
        help="output directory for run/preflight/postflight JSON (default: timestamped build dir)",
    )
    parser.add_argument("--run-id", help="stable non-secret run id stored in pre/post evidence")
    parser.add_argument(
        "--require-clean-source",
        action="store_true",
        help="fail unless the source commit is unchanged and the worktree is clean at both ends",
    )
    args = parser.parse_args(argv)
    if args.run_id is not None and not re.fullmatch(
        r"[a-z0-9][a-z0-9._-]{0,63}", args.run_id
    ):
        parser.error("--run-id must be a stable lowercase non-secret identifier")
    if args.run_launcher_smoke and args.run_id is None:
        parser.error("--run-launcher-smoke requires a stable --run-id")
    run_id = args.run_id or "preflight-check"
    evidence_dir = args.evidence_dir or (
        ROOT
        / "build"
        / "benchmark-preflight"
        / datetime.now(timezone.utc).strftime(f"run-{run_id}-%Y%m%dT%H%M%S%fZ")
    )
    if (
        args.run_launcher_smoke
        and args.require_clean_source
        and evidence_dir.name != run_id
    ):
        parser.error(
            "durable launcher evidence requires --evidence-dir basename to equal --run-id"
        )
    adb: Adb | None = None
    run_lock: int | None = None
    evidence_owned = False
    originals: dict[str, str] | None = None
    created_firebase_fixture = False
    owned_target_session = False
    owned_test_session = False
    owned_device_output = False
    staging_context: tempfile.TemporaryDirectory[str] | None = None
    source_snapshot_context: tempfile.TemporaryDirectory[str] | None = None
    firebase_input_path = BENCHMARK_FIREBASE_CONFIG
    source_at_start: dict[str, Any] | None = None
    exit_code = 0
    evidence: dict[str, Any] = {
        "schemaVersion": 1,
        "runId": run_id,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "serialRecorded": False,
        "launcherSmokeAttempted": False,
    }

    def interrupt(_signum: int, _frame: Any) -> None:
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupt)
    try:
        run_lock = acquire_run_lock()
        source_at_start = collect_source_state()
        evidence["sourceAtStart"] = source_at_start
        if args.require_clean_source and not source_at_start["worktreeClean"]:
            raise PreflightError("durable evidence requires a clean source worktree at start")
        evidence_dir.mkdir(parents=True, exist_ok=False)
        evidence_owned = True

        staged_target: Path | None = None
        staged_test: Path | None = None
        if args.run_launcher_smoke:
            build_root = ROOT
            if args.require_clean_source:
                source_snapshot_context, build_root = materialize_commit_snapshot(
                    source_at_start["commit"]
                )
            (
                build_target_apk,
                build_test_apk,
                firebase_input_path,
                canonical_firebase_path,
            ) = benchmark_paths(build_root)
            created_firebase_fixture = prepare_nonproduction_firebase_fixture(
                canonical_firebase_path,
                firebase_input_path,
            )
            evidence["launcherSmokeAttempted"] = True
            build_return_code = run_benchmark_build(
                build_root,
                build_target_apk,
                build_test_apk,
            )
            evidence["benchmarkBuildReturnCode"] = build_return_code
            if build_return_code != 0:
                raise PreflightError(
                    f"benchmark APK build failed with exit code {build_return_code}"
                )
            firebase_input = artifact_identity(firebase_input_path, build_root)
            canonical_firebase = artifact_identity(canonical_firebase_path, build_root)
            if (
                firebase_input["sha256"] != canonical_firebase["sha256"]
                or firebase_input["size"] != canonical_firebase["size"]
            ):
                raise PreflightError(
                    "benchmark Firebase build input differs from the checked-in fixture"
                )
            staging_context = tempfile.TemporaryDirectory(
                prefix="artifacts-",
                dir=DEVICE_STATE_DIR,
            )
            staging_root = Path(staging_context.name)
            staging_root.chmod(0o700)
            staged_target = staging_root / "target-benchmark.apk"
            staged_test = staging_root / "macrobenchmark-test.apk"
            target_before = copy_stable_artifact(
                build_target_apk, staged_target, build_root
            )
            test_before = copy_stable_artifact(
                build_test_apk, staged_test, build_root
            )
            evidence["artifactsBeforeSmoke"] = {
                "targetBenchmarkApk": target_before,
                "macrobenchmarkTestApk": test_before,
                "benchmarkFirebaseInput": firebase_input,
            }

        adb = connected_adb()
        restored = restore_from_journal(adb)
        if restored is not None:
            evidence["recoveredStaleJournal"] = True
            if args.require_clean_source:
                raise PreflightError(
                    "durable evidence requires a fresh run after stale-setting recovery"
                )
        assert_package_absent(adb, TARGET_PACKAGE)
        assert_package_absent(adb, TEST_PACKAGE)
        # Verify the frozen device before mutation, then separately prove that
        # the pinned settings actually changed the active panel mode.  Merely
        # seeing a supported 120 Hz mode is not sufficient evidence.
        collect_immutable_preflight(adb)
        originals = prepare_settings(adb)
        during = collect_settings(adb)
        expected = {
            f"{namespace}/{key}": target for namespace, key, target in SETTING_KEYS
        }
        if during != expected:
            raise PreflightError(f"configured settings do not match D31 contract: {during}")
        wait_for_active_120hz(adb)
        evidence["preflight"] = collect_immutable_preflight(
            adb, require_active_120hz=True
        )
        evidence["settingsBefore"] = originals
        evidence["settingsDuring"] = during
        preflight_snapshot = {
            **evidence["preflight"],
            "settings": during,
            "serialRecorded": False,
            "runId": run_id,
            "phase": "preflight",
            "observedAt": datetime.now(timezone.utc).isoformat(),
        }
        write_json_atomic(evidence_dir / "preflight.json", preflight_snapshot)

        if args.run_launcher_smoke:
            if staged_target is None or staged_test is None:
                raise PreflightError("private benchmark artifacts were not staged")
            if not remove_device_output(adb, run_id):
                raise PreflightError("cannot clear the dedicated device output directory")
            owned_device_output = True
            install_apk(adb, staged_target)
            owned_target_session = True
            install_apk(adb, staged_test)
            owned_test_session = True
            installed_before = {
                "targetBenchmarkApk": installed_apk_identity(
                    adb,
                    TARGET_PACKAGE,
                    evidence["artifactsBeforeSmoke"]["targetBenchmarkApk"],
                    Path(staging_context.name) / "installed-target-before.apk",
                ),
                "macrobenchmarkTestApk": installed_apk_identity(
                    adb,
                    TEST_PACKAGE,
                    evidence["artifactsBeforeSmoke"]["macrobenchmarkTestApk"],
                    Path(staging_context.name) / "installed-test-before.apk",
                ),
            }
            evidence["activeRefreshBeforeSmoke"] = collect_active_refresh_rates(
                adb, require_120hz=True
            )
            launcher = run_launcher_smoke(adb, run_id)
            evidence["activeRefreshAfterSmoke"] = collect_active_refresh_rates(
                adb, require_120hz=True
            )
            evidence["launcherSmokeReturnCode"] = launcher.returncode
            evidence["launcherResult"] = capture_launcher_result(
                adb, evidence_dir, launcher, run_id
            )
            artifacts_after = {
                "targetBenchmarkApk": artifact_identity(build_target_apk, build_root),
                "macrobenchmarkTestApk": artifact_identity(build_test_apk, build_root),
                "benchmarkFirebaseInput": artifact_identity(
                    firebase_input_path, build_root
                ),
            }
            evidence["artifactsAfterSmoke"] = artifacts_after
            evidence["artifactsStableAcrossSmoke"] = (
                artifacts_after == evidence["artifactsBeforeSmoke"]
            )
            if not evidence["artifactsStableAcrossSmoke"]:
                raise PreflightError("benchmark artifacts changed during launcher smoke")
            installed_after = {
                "targetBenchmarkApk": installed_apk_identity(
                    adb,
                    TARGET_PACKAGE,
                    artifacts_after["targetBenchmarkApk"],
                    Path(staging_context.name) / "installed-target-after.apk",
                ),
                "macrobenchmarkTestApk": installed_apk_identity(
                    adb,
                    TEST_PACKAGE,
                    artifacts_after["macrobenchmarkTestApk"],
                    Path(staging_context.name) / "installed-test-after.apk",
                ),
            }
            if installed_before != installed_after:
                raise PreflightError("installed benchmark APK bytes changed during launcher smoke")
            evidence["deviceInstalledArtifacts"] = installed_after
            evidence["artifacts"] = {
                **artifacts_after,
                "canonicalFirebaseFixture": canonical_firebase,
                "devicePreflight": artifact_identity(Path(__file__).resolve()),
            }
        evidence["postflight"] = collect_immutable_preflight(
            adb, require_active_120hz=True
        )
        postflight_settings = collect_settings(adb)
        if postflight_settings != expected:
            raise PreflightError(
                f"postflight settings drifted from D31 contract: {postflight_settings}"
            )
        postflight_snapshot = {
            **evidence["postflight"],
            "settings": postflight_settings,
            "serialRecorded": False,
            "runId": run_id,
            "phase": "postflight",
            "observedAt": datetime.now(timezone.utc).isoformat(),
        }
        write_json_atomic(evidence_dir / "postflight.json", postflight_snapshot)
    except (Exception, KeyboardInterrupt) as error:
        evidence["error"] = safe_failure("preflight", error)
        exit_code = 2
    finally:
        # A second SIGTERM must not cut the restoration chain in half.  Every
        # cleanup operation below is isolated so one failure cannot prevent the
        # remaining device, file, source-snapshot, or lock cleanup.
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        package_cleanup_errors: list[str] = []
        if adb is not None and (owned_test_session or owned_target_session):
            session_cleanup_errors: list[str] = []
            for package_name, owned in (
                (TEST_PACKAGE, owned_test_session),
                (TARGET_PACKAGE, owned_target_session),
            ):
                if not owned:
                    continue
                try:
                    result = adb.run(
                        "shell", "am", "force-stop", package_name, check=False
                    )
                    if result.returncode != 0:
                        session_cleanup_errors.append(
                            f"cannot stop temporary {package_name}"
                        )
                except (Exception, KeyboardInterrupt):
                    session_cleanup_errors.append(f"cannot stop temporary {package_name}")
            try:
                home = adb.run(
                    "shell", "input", "keyevent", "KEYCODE_HOME", check=False
                )
                if home.returncode != 0:
                    session_cleanup_errors.append("cannot return the device to Home")
            except (Exception, KeyboardInterrupt):
                session_cleanup_errors.append("cannot return the device to Home")
            if session_cleanup_errors:
                evidence["deviceSessionCleanupError"] = "; ".join(
                    session_cleanup_errors
                )
                exit_code = 2
        if adb is not None:
            try:
                restored = restore_from_journal(adb)
                if originals is not None and restored is None:
                    evidence["restoreError"] = "settings were mutated but restore journal is missing"
                    exit_code = 2
                elif restored is not None:
                    evidence["settingsAfter"] = collect_settings(adb)
                    evidence["settingsRestored"] = evidence["settingsAfter"] == restored
                    if not evidence["settingsRestored"]:
                        exit_code = 2
            except (Exception, KeyboardInterrupt) as restore_error:
                evidence["restoreError"] = safe_failure(
                    "settings restoration", restore_error
                )
                exit_code = 2
        if adb is not None and args.run_launcher_smoke:
            for package_name, owned in (
                (TEST_PACKAGE, owned_test_session),
                (TARGET_PACKAGE, owned_target_session),
            ):
                if not owned:
                    continue
                try:
                    removal = adb.run(
                        "uninstall", package_name, check=False, timeout=180
                    )
                except (Exception, KeyboardInterrupt):
                    package_cleanup_errors.append(f"cannot remove temporary {package_name}")
                    continue
                if removal.returncode != 0 or "Success" not in removal.stdout.splitlines():
                    package_cleanup_errors.append(f"cannot remove temporary {package_name}")
            try:
                assert_package_absent(adb, TEST_PACKAGE)
                assert_package_absent(adb, TARGET_PACKAGE)
            except (Exception, KeyboardInterrupt):
                package_cleanup_errors.append("temporary benchmark packages remain installed")
            evidence["temporaryPackagesRemoved"] = not package_cleanup_errors
            if package_cleanup_errors:
                evidence["packageCleanupError"] = "; ".join(package_cleanup_errors)
                exit_code = 2
            try:
                evidence["deviceOutputRemoved"] = (
                    not owned_device_output or remove_device_output(adb, run_id)
                )
            except (Exception, KeyboardInterrupt):
                evidence["deviceOutputRemoved"] = False
            if not evidence["deviceOutputRemoved"]:
                evidence["deviceOutputCleanupError"] = (
                    "cannot remove the dedicated device output directory"
                )
                exit_code = 2
        if created_firebase_fixture:
            try:
                firebase_input_path.unlink()
                evidence["temporaryFirebaseFixtureRemoved"] = True
            except (Exception, KeyboardInterrupt) as cleanup_error:
                evidence["firebaseFixtureCleanupError"] = safe_failure(
                    "temporary Firebase fixture cleanup", cleanup_error
                )
                exit_code = 2
        if source_at_start is not None:
            try:
                source_at_end = collect_source_state()
                evidence["sourceAtEnd"] = source_at_end
                if args.require_clean_source and (
                    not source_at_end["worktreeClean"]
                    or source_at_end["commit"] != source_at_start["commit"]
                ):
                    evidence["sourceStateError"] = (
                        "durable evidence requires the same clean source commit at both ends"
                    )
                    exit_code = 2
            except (Exception, KeyboardInterrupt) as source_error:
                evidence["sourceStateError"] = safe_failure(
                    "source state verification", source_error
                )
                exit_code = 2
        if staging_context is not None:
            try:
                staging_context.cleanup()
                evidence["temporaryStagingRemoved"] = True
            except (Exception, KeyboardInterrupt):
                evidence["stagingCleanupError"] = (
                    "cannot remove the private staged artifact directory"
                )
                exit_code = 2
        if source_snapshot_context is not None:
            try:
                source_snapshot_context.cleanup()
                evidence["temporarySourceSnapshotRemoved"] = True
            except (Exception, KeyboardInterrupt):
                evidence["sourceSnapshotCleanupError"] = (
                    "cannot remove the committed source snapshot"
                )
                exit_code = 2
        evidence["evidenceDirectory"] = evidence_dir.name
        if run_lock is not None:
            descriptor = run_lock
            run_lock = None
            try:
                release_run_lock(descriptor)
            except (Exception, KeyboardInterrupt) as lock_error:
                evidence["runnerLockReleaseError"] = safe_failure(
                    "runner lock release", lock_error
                )
                exit_code = 2
                try:
                    os.close(descriptor)
                except OSError:
                    pass
        if evidence_owned:
            evidence_files: dict[str, dict[str, Any]] = {}
            try:
                for filename in (
                    "preflight.json",
                    "postflight.json",
                    "launcher-result.xml",
                ):
                    path = evidence_dir / filename
                    if path.is_file():
                        evidence_files[filename] = {
                            "sha256": file_sha256(path),
                            "size": path.stat().st_size,
                        }
                if args.run_launcher_smoke and set(evidence_files) != {
                    "preflight.json",
                    "postflight.json",
                    "launcher-result.xml",
                }:
                    raise PreflightError(
                        "launcher evidence is missing a required sanitized result file"
                    )
                evidence["evidenceFiles"] = evidence_files
            except (Exception, KeyboardInterrupt) as evidence_error:
                evidence["evidencePublishingError"] = safe_failure(
                    "evidence identity collection", evidence_error
                )
                exit_code = 2
        evidence["exitCode"] = exit_code
        evidence["verdict"] = "pass" if exit_code == 0 else "fail"
        evidence["finishedAt"] = datetime.now(timezone.utc).isoformat()
        if evidence_owned:
            try:
                write_json_atomic(evidence_dir / "run.json", evidence)
            except (Exception, KeyboardInterrupt) as write_error:
                exit_code = 2
                evidence["exitCode"] = 2
                evidence["verdict"] = "fail"
                evidence["evidencePublishingError"] = safe_failure(
                    "run evidence publication", write_error
                )
        try:
            print(json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2))
        except (OSError, BrokenPipeError):
            exit_code = 2
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
