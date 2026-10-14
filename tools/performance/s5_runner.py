#!/usr/bin/env python3
"""S5 device runner (S5-4 E slice 6c). Standard library only; never run against an emulator.

One run measures the three TetherGraphBenchmark journeys on the frozen SM-F711N and leaves an evidence root the gate can
judge: the seven top-level artifacts, a per-attempt folder, the append-only ledger (s5_ledger) and, when the run finished,
the manifest or the terminal failure report. It reuses device_preflight's lock, restore journal, device checks, install and
read-back helpers by import and never changes that module.

Order (s6c_plan.r2.md §2): lock -> stale settings recovery (a recovery ends the run before any measurement) -> clean source ->
evidence root -> packages absent -> read-only device contract -> committed snapshot, Firebase build copy, s5Benchmark build,
staged APKs, the five canonical inputs (s5_artifacts) -> session line -> settings pinned, read back, 120 Hz -> attempts by
s5_ledger.next_step -> cleanup and settings restore read back -> end line -> assemble. Before the session line a failure exits 2
with no ledger; after it, an interruption, a cleanup or restore failure or an unclassifiable state ends the run as aborted.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import signal
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Optional, Sequence

import benchmark_gate as gate
import device_preflight as dp
import s5_artifacts as artifacts
import s5_ledger as ledger

TEST_CLASS = "com.jay.fxi.macrobenchmark.TetherGraphBenchmark"
RESULT_NAME = f"{dp.TEST_PACKAGE}-benchmarkData.json"
BEHAVIOR_FOLDER = "s5-behavior"
TARGET_APK = "app/build/outputs/apk/s5Benchmark/app-s5Benchmark.apk"
TEST_APK = "macrobenchmark/build/outputs/apk/s5Benchmark/macrobenchmark-s5Benchmark.apk"
BUILD_TASKS = (":app:assembleS5Benchmark", ":macrobenchmark:assembleS5Benchmark")
INSTRUMENT_TIMEOUT_SECONDS = 1800
PULL_TIMEOUT_SECONDS = 600
UNINSTALL_TIMEOUT_SECONDS = 180
EXPECTED_SETTINGS = {f"{namespace}/{key}": value for namespace, key, value in dp.SETTING_KEYS}
METHODS = {journey: method for journey, (_, method) in gate.EXPECTED_BENCHMARK_IDENTITIES.items()}
PACKAGES = (dp.TARGET_PACKAGE, dp.TEST_PACKAGE)
NOT_INVOKED, INVOKED, UNKNOWN = "not-invoked", "invoked", "unknown"


class RunnerError(RuntimeError):
    """The run cannot go on; the message is safe to record."""


class EvidenceIOError(RunnerError):
    """Host evidence could not be written or hashed; the run must exit 2."""


# --------------------------------------------------------------------------------------------------------------------------
# Classification (pure)


@dataclass(frozen=True)
class Observation:
    """What one attempt established. ``environment_error`` is the first confirmed preflight, postflight, install, read-back
    command, collection or timeout failure; ``apks_changed`` only a confirmed byte mismatch of an installed APK."""

    invocation: str
    environment_error: Optional[str] = None
    cleanup_error: Optional[str] = None
    apks_changed: bool = False
    behavior: Optional[str] = None        # None absent, "failure" (check_failure_record), "success" (validate_behavior), "bad"
    snapshots_valid: bool = False
    instrumentation_green: bool = False
    result: Optional[str] = None          # None absent, "valid", "malformed" (an object the strict parser refuses), "unreadable"
    traces_ok: bool = False


@dataclass(frozen=True)
class Outcome:
    kind: str                             # "valid", "invalid", "behavior-failed" or "aborted"
    reason: Optional[str] = None          # invalid: environment-invalid / result-malformed; aborted: why


def classify(o: Observation) -> Outcome:
    """s6_plan.r4.md §3 6c, in its order: cleanup and confirmed APK changes first, then a valid behavior failure (terminal),
    then the behavior record's presence and validity, then the environment, the instrumentation and the result."""
    if o.cleanup_error:
        return Outcome("aborted", f"cleanup failed: {o.cleanup_error}")
    if o.apks_changed:
        return Outcome("aborted", "an installed APK is not the staged bytes")
    if o.behavior == "failure":
        if o.snapshots_valid:
            return Outcome("behavior-failed")
        return Outcome("aborted", "a behavior failure without valid snapshots")
    if o.behavior == "bad":
        return Outcome("aborted", "the behavior record is corrupt, for another attempt, or not as agreed")
    if o.behavior is None:
        if o.invocation == NOT_INVOKED and o.environment_error:
            return Outcome("invalid", "environment-invalid")
        return Outcome("aborted", "no behavior record although the instrumentation ran or may have run")
    if o.environment_error:
        return Outcome("invalid", "environment-invalid")
    if not o.instrumentation_green:
        return Outcome("aborted", "the instrumentation did not end green although its behavior record succeeded")
    if o.result in (None, "unreadable"):
        return Outcome("aborted", "the result is missing or not a finite JSON object")
    if o.result == "malformed":
        return Outcome("invalid", "result-malformed")
    if not (o.traces_ok and o.snapshots_valid):
        return Outcome("aborted", "an unclassified collection state")
    return Outcome("valid")


# --------------------------------------------------------------------------------------------------------------------------
# Reading what an attempt left


def instrumentation_green(text: str, method: str) -> bool:
    """Exactly one test, the requested one, ending green (device_preflight's launcher check, for this class and method)."""
    status_codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", text, re.MULTILINE)
    return (
        re.findall(r"^INSTRUMENTATION_STATUS: class=(.+)$", text, re.MULTILINE) == [TEST_CLASS, TEST_CLASS]
        and re.findall(r"^INSTRUMENTATION_STATUS: test=(.+)$", text, re.MULTILINE) == [method, method]
        and re.findall(r"^INSTRUMENTATION_STATUS: numtests=(.+)$", text, re.MULTILINE) == ["1", "1"]
        and re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.MULTILINE) == ["-1"]
        and bool(status_codes) and status_codes[0] == "1" and status_codes[-1] == "0"
        and status_codes.count("1") == 1 and status_codes.count("0") == 1
        and len(re.findall(r"^OK \(1 test\)\s*$", text, re.MULTILINE)) == 1
        and "FAILURES!!!" not in text and "INSTRUMENTATION_FAILED" not in text
    )


def _finite(value: Any) -> bool:
    if isinstance(value, float):
        return math.isfinite(value)
    if isinstance(value, dict):
        return all(_finite(item) for item in value.values())
    if isinstance(value, list):
        return all(_finite(item) for item in value)
    return True


def read_result(path: Path, journey: str) -> tuple[Optional[str], list[str]]:
    """("valid" | "malformed" | "unreadable" | None, the trace basenames it names in order)."""
    if not path.is_file() or path.is_symlink():
        return None, []
    try:
        document = gate.load_json(path)
    except gate.InvalidEvidence:
        return "unreadable", []
    if not isinstance(document, dict) or not _finite(document):
        return "unreadable", []
    try:
        gate.extract_macrobenchmark_run(document, expected_identity=gate.EXPECTED_BENCHMARK_IDENTITIES[journey])
    except gate.InvalidEvidence:
        return "malformed", []
    benchmark = document["benchmarks"][0]
    return "valid", [Path(output["filename"]).name for output in benchmark["profilerOutputs"]]


def read_behavior(path: Path, attempt_id: str, journey: str) -> Optional[str]:
    if not path.exists() and not path.is_symlink():
        return None
    try:
        document = gate.load_json(path)
    except gate.InvalidEvidence:
        return "bad"
    try:
        gate.check_failure_record(document, run_id=attempt_id, journey_id=journey, label="behavior")
        return "failure"
    except gate.InvalidEvidence:
        pass
    try:
        gate.validate_behavior(document, run_id=attempt_id, journey_id=journey, label="behavior")
        return "success"
    except gate.InvalidEvidence:
        return "bad"


def snapshots_valid(pre: Path, post: Path, attempt_id: str) -> bool:
    try:
        before = gate.validate_preflight_snapshot(pre, "preflight", run_id=attempt_id, phase="preflight")
        after = gate.validate_preflight_snapshot(post, "postflight", run_id=attempt_id, phase="postflight")
        return datetime.fromisoformat(before.replace("Z", "+00:00")) < datetime.fromisoformat(after.replace("Z", "+00:00"))
    except (gate.InvalidEvidence, ValueError):
        return False


# --------------------------------------------------------------------------------------------------------------------------
# The device, thinly


class DeviceOps:
    """device_preflight's helpers and the few adb calls this runner adds; no decisions are made here."""

    def __init__(self) -> None:
        self.adb: Optional[dp.Adb] = None

    @property
    def serial(self) -> Optional[str]:
        return self.adb.serial if self.adb else None

    def connect(self) -> None:
        self.adb = dp.connected_adb()

    def restore_settings(self) -> Optional[dict[str, str]]:
        return dp.restore_from_journal(self.adb)

    def settings(self) -> dict[str, str]:
        return dp.collect_settings(self.adb)

    def assert_absent(self, package: str) -> None:
        dp.assert_package_absent(self.adb, package)

    def contract(self, *, require_active_120hz: bool) -> dict[str, Any]:
        return dp.collect_immutable_preflight(self.adb, require_active_120hz=require_active_120hz)

    def pin_settings(self) -> dict[str, str]:
        return dp.prepare_settings(self.adb)

    def wait_120hz(self) -> None:
        dp.wait_for_active_120hz(self.adb)

    def install(self, apk: Path) -> None:
        dp.install_apk(self.adb, apk)

    def pull_installed(self, package: str, destination: Path, expected: dict[str, Any]) -> None:
        """device_preflight's read-back: the installed base APK pulled to ``destination`` and compared with ``expected``."""
        dp.installed_apk_identity(self.adb, package, expected, destination)

    def clear_output(self, attempt_id: str) -> bool:
        return dp.remove_device_output(self.adb, attempt_id)

    def instrument(self, method: str, attempt_id: str):
        return self.adb.run(
            "shell", "am", "instrument", "-w", "-r", "-e", "class", f"{TEST_CLASS}#{method}", "-e", "s5.runId", attempt_id,
            "-e", "additionalTestOutputDir", f"{dp.DEVICE_OUTPUT_ROOT}/{attempt_id}", dp.INSTRUMENTATION_COMPONENT,
            check=False, timeout=INSTRUMENT_TIMEOUT_SECONDS,
        )

    def pull_output(self, attempt_id: str, destination: Path) -> None:
        result = self.adb.run("pull", f"{dp.DEVICE_OUTPUT_ROOT}/{attempt_id}/.", str(destination), check=False,
                              timeout=PULL_TIMEOUT_SECONDS)
        if result.returncode != 0:
            raise dp.PreflightError("cannot pull the attempt's device output")

    def pull_behavior(self, attempt_id: str, destination: Path) -> None:
        source = f"{dp.DEVICE_OUTPUT_ROOT}/{attempt_id}/{BEHAVIOR_FOLDER}/s5-behavior-{attempt_id}.json"
        result = self.adb.run("pull", source, str(destination), check=False, timeout=PULL_TIMEOUT_SECONDS)
        if result.returncode != 0:
            raise dp.PreflightError("cannot pull the attempt's behavior record")

    def force_stop(self, package: str) -> bool:
        return self.adb.run("shell", "am", "force-stop", package, check=False).returncode == 0

    def uninstall(self, package: str) -> bool:
        result = self.adb.run("uninstall", package, check=False, timeout=UNINSTALL_TIMEOUT_SECONDS)
        return result.returncode == 0 and "Success" in result.stdout.splitlines()

    def home(self) -> bool:
        return self.adb.run("shell", "input", "keyevent", "KEYCODE_HOME", check=False).returncode == 0


# --------------------------------------------------------------------------------------------------------------------------
# Evidence files


def _mask(text: str, serial: Optional[str]) -> str:
    return text.replace(serial, "<redacted-device>") if serial else text


def _safe(label: str, error: BaseException, serial: Optional[str]) -> str:
    """device_preflight.safe_failure, with this runner's own errors kept as their message, then the serial masked."""
    known = (RunnerError, artifacts.ArtifactError, ledger.LedgerError, gate.InvalidEvidence)
    message = str(error) if isinstance(error, known) else dp.safe_failure(label, error)
    return _mask(message, serial).strip() or f"{label} failed"


def _write_new_bytes(path: Path, data: bytes) -> dict[str, str]:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        written = 0
        while written < len(data):
            written += os.write(descriptor, data[written:])
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    return {"path": path.name, "sha256": dp.file_sha256(path)}


def _write_new(path: Path, data: bytes) -> dict[str, str]:
    try:
        return _write_new_bytes(path, data)
    except (OSError, dp.PreflightError) as error:
        raise EvidenceIOError(f"cannot write evidence {path.name} ({type(error).__name__})") from error


def write_json_new(path: Path, value: Any) -> None:
    _write_new(path, (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8"))


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


# --------------------------------------------------------------------------------------------------------------------------
# One attempt


class PreSession(RuntimeError):
    """A failure before the session line: no ledger exists and the run exits 2."""


@dataclass
class Session:
    root: Path
    run_id: str
    device: DeviceOps
    writer: ledger.LedgerWriter
    staged: dict[str, dict[str, Any]]          # package -> {"path": Path, "sha256": str}
    scratch: Path                              # private temporary folder for read-back copies
    owned_packages: list[str] = field(default_factory=list)
    owned_outputs: list[str] = field(default_factory=list)
    ledger_broken: bool = False
    evidence_error: Optional[str] = None

    def append(self, entry_type: str, **fields: Any) -> None:
        """A ledger line; a failed write marks the ledger broken so nothing more is ever added to it."""
        try:
            self.writer.append(entry_type, **fields)
        except OSError as error:
            self.ledger_broken = True
            raise RunnerError(f"the ledger could not be written ({type(error).__name__})") from error


def _ref(session: Session, path: Path) -> dict[str, str]:
    try:
        return {"path": path.relative_to(session.root).as_posix(), "sha256": dp.file_sha256(path)}
    except (dp.PreflightError, OSError, ValueError) as error:
        raise EvidenceIOError(f"cannot hash evidence {path.name} ({type(error).__name__})") from error


def write_snapshot(session: Session, path: Path, attempt_id: str, phase: str) -> None:
    contract = session.device.contract(require_active_120hz=True)
    settings = session.device.settings()
    if settings != EXPECTED_SETTINGS:
        raise RunnerError(f"{phase} settings are not the pinned values")
    write_json_new(path, {**contract, "settings": settings, "serialRecorded": False, "runId": attempt_id, "phase": phase,
                          "observedAt": _utc_now()})


def installed_match(session: Session) -> bool:
    """Reads both installed APKs back with device_preflight's helper. False only for a confirmed mismatch: the APK was pulled
    and its bytes or size differ from the staged one. A command or read-back failure raises."""
    for package in PACKAGES:
        copy = session.scratch / f"installed-{package}.apk"
        expected = session.staged[package]["identity"]
        try:
            try:
                session.device.pull_installed(package, copy, expected)
            except dp.PreflightError as error:
                if (str(error) == f"installed {package} APK differs from staged bytes"
                        and copy.is_file() and not copy.is_symlink()):
                    pulled = dp.stable_file_identity(copy, expected["path"])
                    if pulled != expected:
                        return False
                raise
        finally:
            copy.unlink(missing_ok=True)
    return True


def cleanup_attempt(session: Session, attempt_id: str) -> Optional[str]:
    """Stops and removes the packages this attempt took responsibility for and its device output. Each step runs even when
    an earlier one failed; only what is left behind counts as a failure."""
    errors = []
    for package in reversed(session.owned_packages):
        for step in (session.device.force_stop, session.device.uninstall):
            try:
                step(package)
            except Exception:  # noqa: BLE001 - any failure of one step leaves the next to run; the absence check decides
                pass
    try:
        session.device.home()
    except Exception:  # noqa: BLE001 - returning Home is best effort
        pass
    for package in list(session.owned_packages):
        try:
            session.device.assert_absent(package)
            session.owned_packages.remove(package)
        except Exception as error:  # noqa: BLE001 - recorded as a cleanup failure
            errors.append(_safe(f"removing {package}", error, session.device.serial))
    if attempt_id in session.owned_outputs:
        try:
            if session.device.clear_output(attempt_id):
                session.owned_outputs.remove(attempt_id)
            else:
                errors.append("the attempt's device output remains")
        except Exception as error:  # noqa: BLE001 - recorded as a cleanup failure
            errors.append(_safe("removing the device output", error, session.device.serial))
    return "; ".join(errors) or None


def run_attempt(session: Session, journey: str, ordinal: int) -> Outcome:
    """One attempt in s6c_plan.r2.md §3 order. Writes its attempt-end unless the outcome is aborted (the session then ends the
    run with an aborted end and leaves the start unfinished)."""
    attempt_id = f"{session.run_id}-{journey}-a{ordinal}"
    folder = session.root / "attempts" / attempt_id
    folder.mkdir(parents=True, exist_ok=False, mode=0o700)
    output = folder / "output"
    pre, post = folder / "preflight.json", folder / "postflight.json"
    serial = session.device.serial
    session.append("attempt-start", journey=journey, ordinal=ordinal, attemptId=attempt_id)
    invocation, environment_error, apks_changed, green = NOT_INVOKED, None, False, False

    def note(label: str, error: BaseException) -> None:
        nonlocal environment_error
        message = _safe(label, error, serial)
        if isinstance(error, EvidenceIOError):
            session.evidence_error = session.evidence_error or message
        else:
            environment_error = environment_error or message

    try:
        for package in PACKAGES:
            session.device.assert_absent(package)
        for package in PACKAGES:
            session.owned_packages.append(package)
            session.device.install(session.staged[package]["path"])
        apks_changed = not installed_match(session)
        session.owned_outputs.append(attempt_id)
        if not session.device.clear_output(attempt_id):
            raise RunnerError("cannot clear the attempt's device output")
        write_snapshot(session, pre, attempt_id, "preflight")
        if not apks_changed:
            invocation = UNKNOWN
            result = session.device.instrument(METHODS[journey], attempt_id)
            invocation = INVOKED
            text = result.stdout + "\n" + result.stderr
            if serial and serial in text:
                raise RunnerError("the instrumentation output names the device serial")
            _write_new(folder / "instrumentation.txt", text.encode("utf-8"))
            green = result.returncode == 0 and instrumentation_green(text, METHODS[journey])
    except (dp.PreflightError, RunnerError, OSError) as error:
        note("the attempt", error)
    if invocation != NOT_INVOKED:
        try:
            write_snapshot(session, post, attempt_id, "postflight")
        except (dp.PreflightError, RunnerError, OSError) as error:
            note("the postflight", error)
        try:
            output.mkdir(mode=0o700)
            session.device.pull_output(attempt_id, output)
        except (dp.PreflightError, OSError) as error:
            note("collecting the output", error)
        try:
            behavior_file = output / BEHAVIOR_FOLDER / f"s5-behavior-{attempt_id}.json"
            if not behavior_file.exists() and not behavior_file.is_symlink():
                behavior_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                session.device.pull_behavior(attempt_id, behavior_file)
        except (dp.PreflightError, OSError) as error:
            note("collecting the behavior record", error)
        try:
            apks_changed = apks_changed or not installed_match(session)
        except (dp.PreflightError, OSError) as error:
            note("reading the APKs back", error)
    cleanup_error = cleanup_attempt(session, attempt_id)
    if session.evidence_error:
        raise EvidenceIOError(session.evidence_error)
    behavior = read_behavior(output / BEHAVIOR_FOLDER / f"s5-behavior-{attempt_id}.json", attempt_id, journey)
    result_kind, traces = read_result(output / RESULT_NAME, journey)
    traces_ok = len(traces) == gate.EXPECTED_ITERATIONS and all((output / name).is_file() for name in traces)
    if behavior == "success" and environment_error is None and green:
        if result_kind is None:
            environment_error = "the result file was not collected"
        elif result_kind == "valid" and not traces_ok:
            environment_error = "the traces were not all collected"
    valid_snapshots = pre.is_file() and post.is_file() and snapshots_valid(pre, post, attempt_id)
    if behavior == "success" and environment_error is None and not valid_snapshots:
        environment_error = "the preflight or postflight snapshot does not satisfy the gate"
    outcome = classify(Observation(invocation, environment_error, cleanup_error, apks_changed, behavior, valid_snapshots,
                                   green, result_kind, traces_ok))
    if outcome.kind == "aborted":
        return outcome
    if outcome.kind == "valid":
        fields = {"result": _ref(session, output / RESULT_NAME),
                  "behavior": _ref(session, output / BEHAVIOR_FOLDER / f"s5-behavior-{attempt_id}.json"),
                  "preflight": _ref(session, pre), "postflight": _ref(session, post),
                  "traces": [_ref(session, output / name) for name in traces]}
    elif outcome.kind == "behavior-failed":
        fields = {"behavior": _ref(session, output / BEHAVIOR_FOLDER / f"s5-behavior-{attempt_id}.json"),
                  "preflight": _ref(session, pre), "postflight": _ref(session, post)}
    elif outcome.reason == "result-malformed":
        fields = {"reason": "result-malformed", "evidence": _ref(session, output / RESULT_NAME)}
    else:
        evidence = folder / "environment.json"
        write_json_new(evidence, {"schemaVersion": 1, "runId": attempt_id, "serialRecorded": False,
                                  "error": environment_error or "environment failure"})
        fields = {"reason": "environment-invalid", "evidence": _ref(session, evidence)}
    session.append("attempt-end", attemptId=attempt_id, outcome=outcome.kind, **fields)
    return outcome


# --------------------------------------------------------------------------------------------------------------------------
# The session


def build_snapshot(snapshot: Path, gradle_home: Path, run: artifacts.Runner = artifacts._run) -> None:
    environment = artifacts.gradle_environment()
    environment["GRADLE_USER_HOME"] = str(gradle_home)
    command = [str(snapshot / "gradlew"), *BUILD_TASKS, "--console=plain", "--no-daemon", "--no-configuration-cache",
               "--rerun-tasks"]
    try:
        result = run(command, snapshot, environment)
    except (OSError, UnicodeError, subprocess.SubprocessError) as error:
        raise PreSession(f"the s5Benchmark build did not finish ({type(error).__name__})") from error
    if result.returncode != 0:
        raise PreSession(f"the s5Benchmark build failed with exit {result.returncode}")


def link_gradle_home(home: Path, real: Path) -> None:
    for reusable in ("caches", "wrapper", "jdks"):
        if (real / reusable).exists():
            (home / reusable).symlink_to(real / reusable, target_is_directory=True)


def prepare_artifacts(root: Path, commit: str, scratch: Path, run: artifacts.Runner) -> tuple[dict[str, Any], dict[str, Any]]:
    """The seven top-level references (paths under ``root``) and the staged APKs by package."""
    snapshot_context, snapshot = dp.materialize_commit_snapshot(commit)
    try:
        dp.prepare_nonproduction_firebase_fixture(snapshot / artifacts.FIREBASE_CANONICAL, snapshot / artifacts.FIREBASE_BUILD_COPY)
        real_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
        gradle_home = scratch / "gradle-home"
        gradle_home.mkdir(mode=0o700)
        link_gradle_home(gradle_home, real_home)
        build_snapshot(snapshot, gradle_home, run)
        out = root / "artifacts"
        out.mkdir(mode=0o700)
        staged = {}
        refs = {}
        for key, package, relative in (("targetBenchmarkApk", dp.TARGET_PACKAGE, TARGET_APK),
                                       ("macrobenchmarkTestApk", dp.TEST_PACKAGE, TEST_APK)):
            destination = out / Path(relative).name
            identity = dp.copy_stable_artifact(snapshot / relative, destination, snapshot)
            staged[package] = {"path": destination, "sha256": identity["sha256"], "identity": identity}
            refs[key] = {"path": destination.relative_to(root).as_posix(), "sha256": identity["sha256"]}
        for key, reference in artifacts.collect(snapshot, out, gradle_homes=[gradle_home, real_home], run=run).items():
            refs[key] = {"path": f"artifacts/{reference['path']}", "sha256": reference["sha256"]}
    finally:
        snapshot_context.cleanup()
    for key in sorted(refs):
        gate.validate_artifact_ref(refs[key], root, f"artifacts.{key}")
    return refs, staged


def _candidate_representable(writer: ledger.LedgerWriter, root: Path, status: str) -> Optional[str]:
    """Applies 6a's assembly and the gate to a complete or behavior-failed candidate without publishing; None if it holds."""
    provisional = [*writer.entries, {"type": "end", "status": status}]
    try:
        if status == "complete":
            ledger.build_manifest(provisional, root)
        elif status == "behavior-failed":
            ledger.build_failure_report(provisional, root)
    except (gate.InvalidEvidence, ledger.LedgerError) as error:
        return f"the {status} evidence cannot be expressed: {error}"
    return None


def run(root: Path, run_id: str, *, device: Optional[DeviceOps] = None, run_process: artifacts.Runner = artifacts._run,
        source_state: Callable[[], dict[str, Any]] = dp.collect_source_state) -> int:
    """One S5 run (s6c_plan.r2.md §2). Returns the exit code: before the session line 2 (PreSession, with a diagnostic in the
    evidence root if this run created it); after it, assemble's code (0 pass, 1 fail, behavior failure, exhausted or aborted)
    or 2 for a ledger, evidence or publication defect, after which nothing more is added to the ledger."""
    if not gate.RUN_ID_RE.fullmatch(run_id) or len(run_id) > 40:
        raise PreSession("the run id must be a gate run id of at most 40 characters")
    if root.name != run_id:
        raise PreSession("the evidence root's name must be the run id")
    device = device or DeviceOps()
    lock: Optional[int] = None
    scratch_context: Optional[tempfile.TemporaryDirectory] = None
    writer: Optional[ledger.LedgerWriter] = None
    session: Optional[Session] = None
    root_owned = False
    settings_journaled = False
    candidate: Optional[str] = None
    aborted: Optional[str] = None
    pre_error: Optional[str] = None
    defect: Optional[str] = None
    phase = "start"
    cleanup: dict[str, Any] = {"packagesRemoved": "not-attempted", "deviceOutputRemoved": "not-attempted",
                               "settingsRestored": "not-attempted", "scratchRemoved": "not-attempted",
                               "lockReleased": "not-attempted"}

    def interrupt(_signum, _frame):
        raise KeyboardInterrupt

    previous_handler = signal.signal(signal.SIGTERM, interrupt)
    try:
        lock = dp.acquire_run_lock()
        device.connect()
        restored = device.restore_settings()
        if restored is not None:
            readback = device.settings()
            raise PreSession("stale settings were recovered" + ("" if readback == restored else " but did not read back")
                             + "; this run does not measure, start a new one")
        source = source_state()
        if not source.get("worktreeClean"):
            raise PreSession("the source worktree is not clean")
        root.mkdir(mode=0o700)
        root_owned = True
        phase = "preparing"
        for package in PACKAGES:
            device.assert_absent(package)
        device.contract(require_active_120hz=False)
        scratch_context = dp.private_temporary_directory("s5-runner-")
        scratch = Path(scratch_context.name)
        refs, staged = prepare_artifacts(root, source["commit"], scratch, run_process)
        writer = ledger.LedgerWriter.create(root, source_commit=source["commit"], artifacts=refs)
        session = Session(root, run_id, device, writer, staged, scratch)
        phase = "settings"
        settings_journaled = True  # prepare_settings writes the restore journal before it changes anything
        device.pin_settings()
        if device.settings() != EXPECTED_SETTINGS:
            raise RunnerError("the pinned settings did not read back")
        device.wait_120hz()
        phase = "attempts"
        while True:
            step = ledger.next_step(writer.entries)
            if step.status is not None:
                candidate = step.status
                break
            outcome = run_attempt(session, step.journey, step.ordinal)
            if outcome.kind == "aborted":
                aborted = outcome.reason
                break
        if candidate in ("complete", "behavior-failed"):
            aborted = _candidate_representable(writer, root, candidate)
    except PreSession as error:
        pre_error = str(error)
    except KeyboardInterrupt:
        if writer is None:
            pre_error = f"interrupted during {phase}, before the session line"
        else:
            aborted = aborted or f"interrupted during {phase}"
    except EvidenceIOError as error:
        message = _safe(f"the run ({phase})", error, device.serial)
        if writer is None:
            pre_error = message
        else:
            defect = message
    except Exception as error:  # noqa: BLE001 - preserve a sanitized failure after cleanup
        message = _safe(f"the run ({phase})", error, device.serial)
        if writer is None:
            pre_error = message
        else:
            aborted = aborted or message
    except BaseException:
        if writer is not None:
            writer.close()
        raise
    finally:
        # A second SIGTERM must not cut the cleanup; each step is tried even when an earlier one failed.
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        if session is not None:
            if session.owned_packages or session.owned_outputs:
                errors = []
                for attempt_id in list(session.owned_outputs) or [""]:
                    try:
                        error_text = cleanup_attempt(session, attempt_id)
                    except (Exception, KeyboardInterrupt) as error:
                        error_text = _safe("the final cleanup", error, device.serial)
                    if error_text:
                        errors.append(error_text)
                if errors:
                    cleanup["cleanupError"] = "; ".join(errors)
            cleanup["packagesRemoved"] = not session.owned_packages
            cleanup["deviceOutputRemoved"] = not session.owned_outputs
        if settings_journaled:
            try:
                restored = device.restore_settings()
                if restored is None:
                    cleanup["settingsRestored"] = False
                    cleanup["settingsError"] = "settings were changed but the restore journal is missing"
                elif device.settings() != restored:
                    cleanup["settingsRestored"] = False
                    cleanup["settingsError"] = "the restored settings did not read back"
                else:
                    cleanup["settingsRestored"] = True
            except (Exception, KeyboardInterrupt) as error:
                cleanup["settingsRestored"] = False
                cleanup["settingsError"] = _safe("restoring the settings", error, device.serial)
        if scratch_context is not None:
            try:
                scratch_context.cleanup()
                cleanup["scratchRemoved"] = True
            except (Exception, KeyboardInterrupt):
                cleanup["scratchRemoved"] = False
        if lock is not None:
            try:
                dp.release_run_lock(lock)
                cleanup["lockReleased"] = True
            except (Exception, KeyboardInterrupt):
                cleanup["lockReleased"] = False
        signal.signal(signal.SIGTERM, previous_handler)
    failures = [key for key, value in cleanup.items() if value is False or key in ("cleanupError", "settingsError")]
    if writer is None:
        if root_owned:
            try:
                write_json_new(root / "diagnostic.json", {"schemaVersion": 1, "runId": run_id, "serialRecorded": False,
                                                          "phase": phase, "error": pre_error, "cleanup": cleanup})
            except EvidenceIOError:
                pass  # the run already exits 2; a missing diagnostic is reported by its absence
        raise PreSession(pre_error or "the run stopped before the session line")
    if defect or (session is not None and (session.ledger_broken or session.evidence_error)):
        writer.close()
        reason = defect or (session.evidence_error if session and session.evidence_error else "a ledger write failed")
        print(json.dumps({"error": reason, "ledgerSealed": False, "cleanup": cleanup}, ensure_ascii=False))
        return 2
    try:
        try:
            if aborted or failures or candidate is None:
                diagnostic = {"schemaVersion": 1, "runId": run_id, "serialRecorded": False, "phase": phase,
                              "error": aborted or "cleanup failed", "cleanup": cleanup}
                path = root / "aborted.json"
                write_json_new(path, diagnostic)
                writer.append("end", status="aborted", evidence={"path": path.name, "sha256": dp.file_sha256(path)})
            else:
                writer.append("end", status=candidate)
        finally:
            writer.close()
        code, summary = ledger.assemble(root)
    except (ledger.LedgerError, gate.InvalidEvidence, dp.PreflightError, RunnerError, OSError) as error:
        print(json.dumps({"error": _mask(str(error), device.serial)}, ensure_ascii=False))
        return 2
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return code


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("run", help="measure the three journeys on the frozen device into a new evidence root")
    command.add_argument("--evidence-root", type=Path, required=True)
    command.add_argument("--run-id", required=True)
    args = parser.parse_args(argv)
    try:
        return run(args.evidence_root, args.run_id)
    except PreSession as error:
        print(json.dumps({"error": str(error), "ledger": False}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    sys.exit(main())
