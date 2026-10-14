import copy
import io
import json
import subprocess
import tempfile
import unittest
import unittest.mock
from pathlib import Path

import benchmark_gate as gate
import device_preflight as dp
import s5_ledger as ledger
import s5_runner as runner
from test_benchmark_gate import BENCHMARK_METHODS, DEVICE_SNAPSHOT, behavior_doc, failure_doc, macro_result

SERIAL = "R5TESTSERIAL"
ORIGINAL_SETTINGS = {key: "60.0" if "refresh" in key else "0.5" for key in runner.EXPECTED_SETTINGS}
CONTRACT = {key: value for key, value in DEVICE_SNAPSHOT.items() if key not in ("settings", "serialRecorded")}
APKS = {dp.TARGET_PACKAGE: b"target apk bytes", dp.TEST_PACKAGE: b"test apk bytes"}


def green(method):
    cls = runner.TEST_CLASS
    return "\n".join([
        f"INSTRUMENTATION_STATUS: class={cls}", "INSTRUMENTATION_STATUS: current=1", "INSTRUMENTATION_STATUS: numtests=1",
        f"INSTRUMENTATION_STATUS: test={method}", "INSTRUMENTATION_STATUS_CODE: 1",
        "INSTRUMENTATION_STATUS: additionalTestOutputFile_x=/sdcard/x", "INSTRUMENTATION_STATUS_CODE: 2",
        f"INSTRUMENTATION_STATUS: class={cls}", "INSTRUMENTATION_STATUS: current=1", "INSTRUMENTATION_STATUS: numtests=1",
        f"INSTRUMENTATION_STATUS: test={method}", "INSTRUMENTATION_STATUS_CODE: 0", "", "Time: 12.5", "", "OK (1 test)", "",
        "INSTRUMENTATION_CODE: -1", ""])


def failed(method):
    return green(method).replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2").replace(
        "OK (1 test)", "FAILURES!!!\nTests run: 1,  Failures: 1")


class FakeDevice:
    """A device that obeys device_preflight's contract. ``plan`` maps journey to outcome codes per attempt:
    v valid · f valid over the P99 limit · e install refused (before the instrumentation) · r read-back command fails before
    the instrumentation · E postflight drifts after a successful measure · m malformed result · u unreadable result (1e999) ·
    b behavior failure · B behavior failure and a drifted postflight · t instrumentation times out, no record · x corrupt
    behavior record · n not green with a successful record · k an installed APK is other bytes · c uninstall leaves the package."""

    def __init__(self, plan):
        self.serial = SERIAL
        self.plan = {journey: list(codes) for journey, codes in plan.items()}
        self.current = None
        self.installed = {}
        self.journal = None
        self.settings_now = dict(ORIGINAL_SETTINGS)
        self.outputs = {}
        self.calls = []
        self.drift = False
        self.restore_fails = False
        self.interrupt_on = None
        self.on_pin = None

    def _code(self):
        return self.current[1] if self.current else None

    def connect(self):
        self.calls.append("connect")

    def restore_settings(self):
        self.calls.append("restore")
        if self.restore_fails:
            raise dp.PreflightError("restore failed")
        if self.journal is None:
            return None
        restored, self.journal = self.journal, None
        self.settings_now = dict(restored)
        return dict(restored)

    def settings(self):
        return dict(self.settings_now)

    def assert_absent(self, package):
        if package in self.installed:
            raise dp.PreflightError(f"{package} is already installed for an Android user")

    def contract(self, *, require_active_120hz):
        if self.drift:
            raise dp.PreflightError("thermal status must be NONE(0)")
        result = copy.deepcopy(CONTRACT)
        if self._code() == "F":
            result["fingerprint"] = "samsung/other/firmware:15/user/release-keys"  # device_preflight passed, the gate does not
        return result

    def pin_settings(self):
        self.calls.append("pin")
        if self.on_pin:
            self.on_pin()
        self.journal = dict(self.settings_now)
        self.settings_now = dict(runner.EXPECTED_SETTINGS)
        return dict(self.journal)

    def wait_120hz(self):
        self.calls.append("wait120")

    def install(self, apk):
        package = dp.TARGET_PACKAGE if apk.name.startswith("app-") else dp.TEST_PACKAGE
        if self._code() == "e" and package == dp.TEST_PACKAGE:
            raise dp.PreflightError("adb could not install a staged benchmark APK")
        self.installed[package] = apk.read_bytes()
        if self._code() == "i" and package == dp.TEST_PACKAGE:
            raise dp.PreflightError("adb command timed out (install --no-streaming ...)")
        if self._code() == "k" and package == dp.TEST_PACKAGE:
            self.installed[package] = b"someone else's apk"

    def pull_installed(self, package, destination, expected):
        """device_preflight.installed_apk_identity's contract: pull, then raise if the bytes are not the expected ones."""
        if self._code() == "r":
            raise dp.PreflightError(f"cannot read back installed {package} APK")
        if self._code() == "h":
            destination.write_bytes(self.installed[package][:3])  # a pull that failed half way
            raise dp.PreflightError(f"cannot read back installed {package} APK")
        destination.write_bytes(self.installed[package])
        if dp.stable_file_identity(destination, expected["path"]) != expected:
            raise dp.PreflightError(f"installed {package} APK differs from staged bytes")

    def clear_output(self, attempt_id):
        if self._code() == "o":
            return False
        self.outputs.pop(attempt_id, None)
        return True

    def instrument(self, method, attempt_id):
        journey, code = self.current[0], self._code()
        self.calls.append(f"instrument {attempt_id}")
        if self.interrupt_on == attempt_id:
            raise KeyboardInterrupt
        files = {}
        record_path = f"{runner.BEHAVIOR_FOLDER}/s5-behavior-{attempt_id}.json"
        if code in "vfEmunkTFRQPp" or code == "c":
            prefix = f"TetherGraphBenchmark_{method}_{attempt_id}"
            result = macro_result(tail=20.0 if code == "f" else 8.33, trace_prefix=prefix, benchmark_name=BENCHMARK_METHODS[journey])
            data = json.dumps(result).encode()
            if code == "m":
                del result["benchmarks"][0]["sampledMetrics"]["frameOverrunMs"]["P99"]
                data = json.dumps(result).encode()
            if code == "u":
                data = data.replace(b'"P95": 0.0', b'"P95": 1e999', 1)
            files[runner.RESULT_NAME] = data
            for index in range(5):
                files[f"{prefix}-{index}.perfetto-trace"] = f"{attempt_id}/{index}".encode()
            files[record_path] = json.dumps(behavior_doc(journey, attempt_id)).encode()
            if code == "R":
                del files[runner.RESULT_NAME]
            if code == "Q":
                del files[f"{prefix}-3.perfetto-trace"]
        elif code in "bBW":
            files[record_path] = json.dumps(failure_doc(journey, attempt_id, slot=4, stage="lookup", kind="operation-error",
                                                         failed={"lookups": None, "before": None})).encode()
        elif code == "x":
            files[record_path] = json.dumps(behavior_doc(journey, "someone-else")).encode()
        self.outputs[attempt_id] = files
        if code in "EB":
            self.drift = True
        if code == "t":
            raise dp.PreflightError("adb command timed out (shell am instrument ...)")
        if code == "z":
            raise dp.PreflightError("cannot execute adb command (shell am instrument ...)")
        if code == "T":
            raise dp.PreflightError("adb command timed out (shell am instrument ...)")
        text = failed(method) if code in "bBnW" else green(method)
        return subprocess.CompletedProcess(["adb"], 0, text, "")

    def pull_output(self, attempt_id, destination):
        if self._code() in ("P", "p", "W"):
            raise dp.PreflightError("cannot pull the attempt's device output")
        for name, data in self.outputs.get(attempt_id, {}).items():
            path = destination / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def pull_behavior(self, attempt_id, destination):
        record = self.outputs.get(attempt_id, {}).get(f"{runner.BEHAVIOR_FOLDER}/s5-behavior-{attempt_id}.json")
        if self._code() == "p" or record is None:
            raise dp.PreflightError("cannot pull the attempt's behavior record")
        destination.write_bytes(record)

    def force_stop(self, package):
        return True

    def uninstall(self, package):
        if self._code() == "c" and package == dp.TEST_PACKAGE:
            return False
        self.installed.pop(package, None)
        return True

    def home(self):
        self.drift = False
        return True


class RunnerHarness:
    """Runs runner.run with the real ledger, assembler and gate, a FakeDevice, a fake build output and no global lock."""

    def __init__(self, test, plan, *, source=None):
        self.test = test
        self.temp = tempfile.TemporaryDirectory()
        test.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "s5-run-1"
        self.device = FakeDevice(plan)
        self.source = source or {"worktreeClean": True, "commit": "a" * 40}
        self.ledger_lines_at_pin = None
        self.device.on_pin = lambda: setattr(self, "ledger_lines_at_pin", self.lines())
        self.clock = 0

    def lines(self):
        path = self.root / ledger.LEDGER_NAME
        return len(path.read_bytes().splitlines()) if path.exists() else 0

    def fake_prepare(self, root, commit, scratch, run_process):
        out = root / "artifacts"
        out.mkdir(mode=0o700)
        refs, staged = {}, {}
        for key, package, name in (("targetBenchmarkApk", dp.TARGET_PACKAGE, "app-s5Benchmark.apk"),
                                   ("macrobenchmarkTestApk", dp.TEST_PACKAGE, "macrobenchmark-s5Benchmark.apk")):
            path = out / name
            path.write_bytes(APKS[package])
            staged[package] = {"path": path, "sha256": dp.file_sha256(path),
                               "identity": dp.stable_file_identity(path, f"app/build/{name}")}
            refs[key] = {"path": f"artifacts/{name}", "sha256": staged[package]["sha256"]}
        for key in sorted(gate.ARTIFACT_KEYS - set(refs)):
            path = out / f"{key}.bin"
            path.write_bytes(Path(gate.__file__).read_bytes() if key == "parser" else key.encode())
            refs[key] = {"path": f"artifacts/{path.name}", "sha256": dp.file_sha256(path)}
        return refs, staged

    def tick(self):
        self.clock += 1
        return f"2026-10-14T00:{self.clock // 60:02d}:{self.clock % 60:02d}+00:00"

    def run(self):
        original_attempt = runner.run_attempt

        def attempt(session, journey, ordinal):
            self.device.current = (journey, self.device.plan.get(journey, ["v"]).pop(0) if self.device.plan.get(journey) else "v")
            try:
                return original_attempt(session, journey, ordinal)
            finally:
                self.device.current = None

        out = io.StringIO()
        with unittest.mock.patch.object(dp, "acquire_run_lock", lambda: 99), \
                unittest.mock.patch.object(dp, "release_run_lock", lambda descriptor: None), \
                unittest.mock.patch.object(dp, "private_temporary_directory",
                                           lambda prefix: tempfile.TemporaryDirectory(prefix=prefix, dir=self.base)), \
                unittest.mock.patch.object(runner, "prepare_artifacts", self.fake_prepare), \
                unittest.mock.patch.object(runner, "run_attempt", attempt), \
                unittest.mock.patch.object(runner, "_utc_now", self.tick), \
                unittest.mock.patch("sys.stdout", out):
            try:
                code = runner.run(self.root, self.root.name, device=self.device, source_state=lambda: dict(self.source))
            except runner.PreSession as error:
                return 2, {"error": str(error)}
        return code, json.loads(out.getvalue())

    def entries(self):
        return ledger.load_ledger(self.root)

    def attempts(self, journey=None):
        ends = [entry for entry in self.entries() if entry["type"] == "attempt-end"]
        starts = {entry["attemptId"]: entry for entry in self.entries() if entry["type"] == "attempt-start"}
        return [(starts[end["attemptId"]], end) for end in ends if journey is None or starts[end["attemptId"]]["journey"] == journey]


ALL_VALID = {"tab-entry": "vvv", "period-switch": "vvv", "scroll": "vvv"}


class S5RunnerTest(unittest.TestCase):
    def assertClean(self, h):
        self.assertEqual({}, h.device.installed, "every package this run installed is removed")
        self.assertEqual(ORIGINAL_SETTINGS, h.device.settings_now, "the settings are restored")
        self.assertIsNone(h.device.journal)

    def assertAborted(self, h, code, summary, text=None):
        self.assertEqual((1, "aborted"), (code, summary["status"]))
        end = h.entries()[-1]
        self.assertEqual("aborted", end["status"])
        diagnostic = gate.load_json(h.root / end["evidence"]["path"])
        self.assertEqual((1, "s5-run-1", False), (diagnostic["schemaVersion"], diagnostic["runId"], diagnostic["serialRecorded"]))
        if text:
            self.assertIn(text, diagnostic["error"])
        self.assertNotIn(SERIAL, json.dumps(diagnostic))
        self.assertFalse((h.root / ledger.MANIFEST_NAME).exists())
        self.assertClean(h)
        return diagnostic

    # --- whole runs -----------------------------------------------------------------------------------------------
    def test_a_full_run_measures_three_valid_attempts_per_journey_into_a_manifest_the_gate_accepts(self):
        h = RunnerHarness(self, ALL_VALID)
        code, summary = h.run()
        self.assertEqual((0, "complete", "pass"), (code, summary["status"], summary["verdict"]))
        gate.validate_manifest(gate.load_json(h.root / ledger.MANIFEST_NAME), h.root)
        self.assertEqual(1, h.ledger_lines_at_pin, "the session line is written before the settings change, and only it")
        self.assertLess(h.device.calls.index("pin"), next(i for i, c in enumerate(h.device.calls) if c.startswith("instrument")))
        self.assertEqual("restore", h.device.calls[-1], "the settings are restored at the end")
        self.assertClean(h)

    def test_invalid_attempts_are_recorded_and_retried(self):
        h = RunnerHarness(self, {"tab-entry": "evrvv", "period-switch": "Evvv", "scroll": "mvvv"})
        code, summary = h.run()
        self.assertEqual(0, code, summary)
        tab = h.attempts("tab-entry")
        self.assertEqual(["invalid", "valid", "invalid", "valid", "valid"], [end["outcome"] for _, end in tab])
        self.assertEqual(["environment-invalid", "environment-invalid"], [end["reason"] for _, end in tab if end["outcome"] == "invalid"])
        self.assertEqual("environment-invalid", h.attempts("period-switch")[0][1]["reason"], "a postflight drift after a good measure")
        self.assertEqual("result-malformed", h.attempts("scroll")[0][1]["reason"])
        for _, end in h.attempts():
            if end.get("reason") == "environment-invalid":
                evidence = gate.load_json(h.root / end["evidence"]["path"])
                self.assertEqual((1, end["attemptId"], False), (evidence["schemaVersion"], evidence["runId"], evidence["serialRecorded"]))
                self.assertTrue(evidence["error"])
            if end.get("reason") == "result-malformed":
                self.assertTrue(end["evidence"]["path"].endswith(runner.RESULT_NAME))

    def test_a_threshold_miss_is_valid_and_the_run_fails(self):
        h = RunnerHarness(self, {"tab-entry": "vvv", "period-switch": "fff", "scroll": "vvv"})
        code, summary = h.run()
        self.assertEqual((1, "complete", "fail"), (code, summary["status"], summary["verdict"]))

    def test_five_attempts_without_three_valid_ones_end_the_measurement(self):
        h = RunnerHarness(self, {"tab-entry": "eevee"})
        code, summary = h.run()
        self.assertEqual((1, "attempts-exhausted", None), (code, summary["status"], summary["document"]))
        self.assertEqual(5, len(h.attempts()))
        self.assertClean(h)

    def test_a_behavior_failure_is_terminal_and_reported(self):
        h = RunnerHarness(self, {"tab-entry": "vvv", "period-switch": "vb"})
        code, summary = h.run()
        self.assertEqual((1, "behavior-failed", ledger.FAILURE_REPORT_NAME), (code, summary["status"], summary["document"]))
        gate.validate_failure_report(gate.load_json(h.root / ledger.FAILURE_REPORT_NAME), h.root)
        self.assertNotIn("scroll", {entry.get("journey") for entry in h.entries()})
        self.assertClean(h)

    def test_states_that_cannot_be_retried_or_reported_abort_the_run_and_leave_the_attempt_unfinished(self):
        for code_letter, text in (("t", "no behavior record"), ("x", "corrupt"), ("u", "finite JSON object"),
                                  ("n", "did not end green"), ("k", "not the staged bytes"), ("c", "cleanup failed"),
                                  ("B", "without valid snapshots")):
            with self.subTest(code_letter):
                h = RunnerHarness(self, {"tab-entry": "v" + code_letter})
                code, summary = h.run()
                self.assertAborted(h, code, summary, text)
                entries = h.entries()
                self.assertEqual("attempt-start", entries[-2]["type"], "the aborted attempt has no attempt-end")
                self.assertEqual("s5-run-1-tab-entry-a2", entries[-2]["attemptId"])

    def test_an_interruption_during_an_attempt_aborts_after_cleanup(self):
        h = RunnerHarness(self, ALL_VALID)
        h.device.interrupt_on = "s5-run-1-period-switch-a1"
        code, summary = h.run()
        self.assertAborted(h, code, summary, "interrupted during attempts")

    def test_a_restore_failure_turns_even_a_complete_run_into_aborted(self):
        h = RunnerHarness(self, ALL_VALID)
        original = h.device.restore_settings
        state = {"calls": 0}

        def restore():
            state["calls"] += 1
            if state["calls"] >= 2:
                raise dp.PreflightError(f"restore failed on {SERIAL}")
            return original()

        h.device.restore_settings = restore
        code, summary = h.run()
        self.assertEqual((1, "aborted"), (code, summary["status"]))
        diagnostic = gate.load_json(h.root / h.entries()[-1]["evidence"]["path"])
        self.assertIs(False, diagnostic["cleanup"]["settingsRestored"])
        self.assertIn("restore failed", diagnostic["cleanup"]["settingsError"])
        self.assertNotIn(SERIAL, json.dumps(diagnostic))
        self.assertFalse((h.root / ledger.MANIFEST_NAME).exists())

    def test_pinned_settings_that_do_not_read_back_abort_after_the_session(self):
        h = RunnerHarness(self, ALL_VALID)
        original = h.device.pin_settings

        def pin():
            result = original()
            h.device.settings_now["system/min_refresh_rate"] = "60.0"
            return result

        h.device.pin_settings = pin
        code, summary = h.run()
        self.assertAborted(h, code, summary, "did not read back")
        self.assertEqual(["session", "end"], [entry["type"] for entry in h.entries()])

    # --- before the session line: exit 2, no ledger ---------------------------------------------------------------
    def test_stale_settings_are_recovered_and_the_run_stops_without_a_ledger(self):
        h = RunnerHarness(self, ALL_VALID)
        h.device.journal = dict(ORIGINAL_SETTINGS)
        h.device.settings_now = dict(runner.EXPECTED_SETTINGS)
        code, summary = h.run()
        self.assertEqual(2, code)
        self.assertIn("stale settings were recovered", summary["error"])
        self.assertFalse(h.root.exists())
        self.assertEqual(ORIGINAL_SETTINGS, h.device.settings_now)

    def test_an_unclean_source_or_a_present_package_stops_before_anything_changes(self):
        h = RunnerHarness(self, ALL_VALID, source={"worktreeClean": False, "commit": "a" * 40})
        code, summary = h.run()
        self.assertEqual(2, code)
        self.assertIn("not clean", summary["error"])
        self.assertFalse(h.root.exists())
        h = RunnerHarness(self, ALL_VALID)
        h.device.installed[dp.TARGET_PACKAGE] = b"the production app"
        code, summary = h.run()
        self.assertEqual(2, code)
        self.assertNotIn("pin", h.device.calls)
        self.assertEqual(b"the production app", h.device.installed[dp.TARGET_PACKAGE], "a package this run did not install is left alone")
        self.assertFalse((h.root / ledger.LEDGER_NAME).exists())

    def test_the_run_id_must_name_the_root_and_fit_attempt_ids(self):
        with self.assertRaises(runner.PreSession):
            runner.run(Path("/tmp/other"), "s5-run-1", device=FakeDevice({}))
        with self.assertRaises(runner.PreSession):
            runner.run(Path("/tmp") / ("a" * 41), "a" * 41, device=FakeDevice({}))

    def test_more_attempt_boundaries(self):
        cases = {
            "an install that timed out but installed": ({"tab-entry": "ivvv", "period-switch": "vvv", "scroll": "vvv"}, 0),
            "a timeout after a successful measure still collects its record": ({"tab-entry": "Tvvv", "period-switch": "vvv", "scroll": "vvv"}, 0),
            "snapshots the gate refuses after a successful measure": ({"tab-entry": "Fvvv", "period-switch": "vvv", "scroll": "vvv"}, 0),
            "a result file that was not collected": ({"tab-entry": "Rvvv", "period-switch": "vvv", "scroll": "vvv"}, 0),
            "a trace that was not collected": ({"tab-entry": "Qvvv", "period-switch": "vvv", "scroll": "vvv"}, 0),
            "a read-back pull that failed half way": ({"tab-entry": "hvvv", "period-switch": "vvv", "scroll": "vvv"}, 0),
            "an unknown instrumentation start without a record": ({"tab-entry": "vz"}, 1),
            "an output folder that cannot be cleared": ({"tab-entry": "vo"}, 1),
        }
        for name, (plan, expected) in cases.items():
            with self.subTest(name):
                h = RunnerHarness(self, plan)
                code, summary = h.run()
                self.assertEqual(expected, code, summary)
                self.assertEqual({}, h.device.installed)
                if expected == 0:
                    self.assertEqual("environment-invalid", h.attempts("tab-entry")[0][1]["reason"])
                else:
                    self.assertEqual("aborted", summary["status"])

    def test_a_complete_candidate_that_the_gate_cannot_take_is_aborted(self):
        h = RunnerHarness(self, ALL_VALID)
        original = h.device.instrument

        def same_bytes(method, attempt_id):
            result = original(method, attempt_id)
            files = h.device.outputs[attempt_id]
            for name in list(files):
                if name == runner.RESULT_NAME:
                    files[name] = files[name].replace(attempt_id.encode(), b"same")
                elif name.endswith(".perfetto-trace"):
                    data = files.pop(name)
                    files[name.replace(attempt_id, "same")] = data
            return result

        h.device.instrument = same_bytes
        code, summary = h.run()
        self.assertAborted(h, code, summary, "cannot be expressed")

    def test_a_foreign_package_that_appears_is_left_alone(self):
        h = RunnerHarness(self, {"tab-entry": "vvv", "period-switch": "vvvvv"})
        original = h.device.assert_absent

        def appear(package):
            if h.device.current and h.device.current[0] == "period-switch" and package == dp.TARGET_PACKAGE:
                h.device.installed.setdefault(dp.TARGET_PACKAGE, b"a foreign app")
            return original(package)

        h.device.assert_absent = appear
        code, summary = h.run()
        self.assertEqual((1, "attempts-exhausted"), (code, summary["status"]))
        self.assertEqual({dp.TARGET_PACKAGE: b"a foreign app"}, h.device.installed)
        self.assertEqual(["environment-invalid"] * 5, [end["reason"] for _, end in h.attempts("period-switch")])

    def test_a_pinning_failure_after_the_journal_is_restored_and_aborts(self):
        h = RunnerHarness(self, ALL_VALID)

        def pin():
            h.device.journal = dict(h.device.settings_now)
            h.device.settings_now["system/peak_refresh_rate"] = "120.0"
            raise dp.PreflightError("cannot write setting")

        h.device.pin_settings = pin
        code, summary = h.run()
        self.assertAborted(h, code, summary, "cannot write setting")

    def test_a_lost_journal_or_a_restore_that_does_not_read_back_aborts(self):
        for name, lose in (("lost", True), ("read-back", False)):
            with self.subTest(name):
                h = RunnerHarness(self, {"tab-entry": "eeeee"})
                original = h.device.restore_settings
                calls = {"n": 0}

                def restore(original=original, lose=lose, h=h, calls=calls):
                    calls["n"] += 1
                    if calls["n"] == 1:
                        return original()
                    if lose:
                        h.device.journal = None
                        return None
                    result = original()
                    h.device.settings_now["global/animator_duration_scale"] = "0.0"
                    return result

                h.device.restore_settings = restore
                code, summary = h.run()
                self.assertEqual((1, "aborted"), (code, summary["status"]))
                diagnostic = gate.load_json(h.root / h.entries()[-1]["evidence"]["path"])
                self.assertIs(False, diagnostic["cleanup"]["settingsRestored"])
                self.assertIn("journal is missing" if lose else "did not read back", diagnostic["cleanup"]["settingsError"])

    def test_a_failed_ledger_write_is_never_followed_by_another_line(self):
        h = RunnerHarness(self, ALL_VALID)
        real_append = ledger.LedgerWriter.append
        count = {"n": 0}

        def append(writer, entry_type, **fields):
            if entry_type == "attempt-end":
                count["n"] += 1
                if count["n"] == 2:
                    raise OSError("disk full")
            return real_append(writer, entry_type, **fields)

        with unittest.mock.patch.object(ledger.LedgerWriter, "append", append):
            code, summary = h.run()
        self.assertEqual(2, code)
        self.assertEqual("attempt-start", ledger.read_ledger(h.root)[-1]["type"])
        self.assertClean(h)

    def test_a_second_sigterm_during_cleanup_does_not_cut_it(self):
        import os
        import signal
        h = RunnerHarness(self, {"tab-entry": "eeeee"})
        original = h.device.restore_settings
        calls = {"n": 0}

        def restore():
            calls["n"] += 1
            if calls["n"] == 2:
                os.kill(os.getpid(), signal.SIGTERM)
            return original()

        h.device.restore_settings = restore
        code, summary = h.run()
        self.assertEqual((1, "attempts-exhausted"), (code, summary["status"]))
        self.assertClean(h)

    def test_a_failed_full_pull_still_fetches_the_behavior_record_alone(self):
        cases = {
            "a success record fetched alone": ({"tab-entry": "Pvvv", "period-switch": "vvv", "scroll": "vvv"}, (0, "environment-invalid")),
            "a failure record fetched alone": ({"tab-entry": "vW"}, (1, "behavior-failed")),
            "nothing fetched": ({"tab-entry": "vp"}, (1, "aborted")),
        }
        for name, (plan, (expected_code, expected)) in cases.items():
            with self.subTest(name):
                h = RunnerHarness(self, plan)
                code, summary = h.run()
                self.assertEqual(expected_code, code, summary)
                if expected == "environment-invalid":
                    self.assertEqual("environment-invalid", h.attempts("tab-entry")[0][1]["reason"])
                else:
                    self.assertEqual(expected, summary["status"])
                self.assertClean(h)

    def test_evidence_that_cannot_be_written_ends_the_run_with_exit_two_and_no_end(self):
        for victim in ("preflight.json", "instrumentation.txt", "environment.json", "postflight.json"):
            with self.subTest(victim):
                h = RunnerHarness(self, {"tab-entry": "evvv", "period-switch": "vvv", "scroll": "vvv"})
                real = runner._write_new_bytes

                def write(path, data, real=real, victim=victim):
                    if path.name == victim:
                        raise OSError("disk full")
                    return real(path, data)

                with unittest.mock.patch.object(runner, "_write_new_bytes", write):
                    code, summary = h.run()
                self.assertEqual(2, code, summary)
                self.assertIs(False, summary["ledgerSealed"])
                self.assertEqual("attempt-start", ledger.read_ledger(h.root)[-1]["type"], "no attempt-end after an evidence failure")
                self.assertClean(h)

    def test_a_session_or_attempt_start_that_cannot_be_written_is_exit_two(self):
        for kind in ("session", "attempt-start"):
            with self.subTest(kind):
                h = RunnerHarness(self, ALL_VALID)
                real_append = ledger.LedgerWriter.append

                def append(writer, entry_type, real_append=real_append, kind=kind, **fields):
                    if entry_type == kind:
                        raise OSError("disk full")
                    return real_append(writer, entry_type, **fields)

                with unittest.mock.patch.object(ledger.LedgerWriter, "append", append):
                    code, summary = h.run()
                self.assertEqual(2, code, summary)
                if kind == "session":
                    diagnostic = gate.load_json(h.root / "diagnostic.json")
                    self.assertEqual("not-attempted", diagnostic["cleanup"]["settingsRestored"])
                    self.assertNotIn("pin", h.device.calls)
                self.assertClean(h)

    def test_a_failure_after_the_root_exists_but_before_the_session_leaves_a_diagnostic(self):
        h = RunnerHarness(self, ALL_VALID)

        def broken(root, commit, scratch, run_process):
            raise runner.PreSession("the s5Benchmark build failed with exit 1")

        h.fake_prepare = broken
        code, summary = h.run()
        self.assertEqual(2, code)
        diagnostic = gate.load_json(h.root / "diagnostic.json")
        self.assertEqual((1, "s5-run-1", False), (diagnostic["schemaVersion"], diagnostic["runId"], diagnostic["serialRecorded"]))
        self.assertIn("build failed", diagnostic["error"])
        self.assertEqual({"packagesRemoved": "not-attempted", "deviceOutputRemoved": "not-attempted",
                          "settingsRestored": "not-attempted", "scratchRemoved": True, "lockReleased": True}, diagnostic["cleanup"])
        self.assertFalse((h.root / ledger.LEDGER_NAME).exists())

    def test_an_unexpected_error_after_the_root_exists_still_leaves_a_diagnostic(self):
        for name in ("contract", "prepare"):
            with self.subTest(name):
                h = RunnerHarness(self, ALL_VALID)
                if name == "contract":
                    def contract(*, require_active_120hz):
                        raise ValueError("invalid literal for int() with base 10: 'x'")
                    h.device.contract = contract
                else:
                    def prepare(root, commit, scratch, run_process):
                        raise RuntimeError("collect blew up")
                    h.fake_prepare = prepare
                code, summary = h.run()
                self.assertEqual(2, code)
                diagnostic = gate.load_json(h.root / "diagnostic.json")
                self.assertIn("ValueError" if name == "contract" else "RuntimeError", diagnostic["error"])
                self.assertIs(True, diagnostic["cleanup"]["lockReleased"])
                self.assertFalse((h.root / ledger.LEDGER_NAME).exists())
                self.assertNotIn("pin", h.device.calls)

    def test_a_cleanup_step_that_raises_does_not_stop_the_others_and_is_reported(self):
        h = RunnerHarness(self, ALL_VALID)
        h.device.interrupt_on = "s5-run-1-tab-entry-a2"
        real_uninstall = h.device.uninstall

        def uninstall(package):
            if package == dp.TEST_PACKAGE:
                raise RuntimeError("adb went away")
            return real_uninstall(package)

        h.device.uninstall = uninstall
        code, summary = h.run()
        self.assertEqual((1, "aborted"), (code, summary["status"]))
        diagnostic = gate.load_json(h.root / h.entries()[-1]["evidence"]["path"])
        self.assertIs(False, diagnostic["cleanup"]["packagesRemoved"])
        self.assertIs(True, diagnostic["cleanup"]["settingsRestored"])
        self.assertIs(True, diagnostic["cleanup"]["lockReleased"])
        self.assertIn(dp.TEST_PACKAGE, h.device.installed)
        self.assertNotIn(dp.TARGET_PACKAGE, h.device.installed, "the next package's removal still ran")

    def test_the_aborted_diagnostic_says_what_each_cleanup_step_did(self):
        h = RunnerHarness(self, {"tab-entry": "vt"})
        code, summary = h.run()
        diagnostic = self.assertAborted(h, code, summary, "no behavior record")
        self.assertEqual({"packagesRemoved": True, "deviceOutputRemoved": True, "settingsRestored": True,
                          "scratchRemoved": True, "lockReleased": True}, diagnostic["cleanup"])

    def test_an_end_that_cannot_be_written_is_exit_two(self):
        h = RunnerHarness(self, {"tab-entry": "eeeee"})
        real_append = ledger.LedgerWriter.append

        def append(writer, entry_type, **fields):
            if entry_type == "end":
                raise OSError("disk full")
            return real_append(writer, entry_type, **fields)

        with unittest.mock.patch.object(ledger.LedgerWriter, "append", append):
            code, summary = h.run()
        self.assertEqual(2, code)
        self.assertIn("disk full", summary["error"])


class PrepareArtifactsTest(unittest.TestCase):
    """prepare_artifacts against a fake committed snapshot: the Firebase build copy, the build with its own Gradle home, the
    staged APKs and the canonical inputs, all under the evidence root with root-relative paths."""

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.root = self.base / "s5-run-1"
        self.root.mkdir()
        self.scratch = self.base / "scratch"
        self.scratch.mkdir()
        self.real_home = self.base / "real-gradle"
        (self.real_home / "caches").mkdir(parents=True)
        self.snapshot = self.base / "snapshot"
        (self.snapshot / "ci").mkdir(parents=True)
        (self.snapshot / "ci/google-services.ci-fixture.json").write_bytes(b'{"fixture": true}\n')
        (self.snapshot / "gradlew").write_text("#!/bin/sh\n")
        self.cleaned = []
        self.calls = []

    def snapshot_context(self, commit):
        self.assertEqual("c" * 40, commit)
        test = self

        class Context:
            name = str(test.snapshot)

            def cleanup(self):
                test.cleaned.append(True)

        return Context(), self.snapshot

    def build(self, command, cwd, environment):
        self.calls.append((command, cwd, environment))
        for relative, data in ((runner.TARGET_APK, b"target"), (runner.TEST_APK, b"test")):
            path = cwd / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        return subprocess.CompletedProcess(command, 0, "", "")

    def fake_collect(self, snapshot, out, *, gradle_homes, run):
        self.collected = (snapshot, out, list(gradle_homes))
        references = {}
        for key in sorted(gate.ARTIFACT_KEYS - {"targetBenchmarkApk", "macrobenchmarkTestApk"}):
            path = out / f"{key}.txt"
            path.write_bytes(key.encode())
            references[key] = {"path": path.name, "sha256": dp.file_sha256(path)}
        return references

    def prepare(self, build=None):
        with unittest.mock.patch.object(dp, "materialize_commit_snapshot", self.snapshot_context), \
                unittest.mock.patch.object(runner.artifacts, "collect", self.fake_collect), \
                unittest.mock.patch.dict("os.environ", {"GRADLE_USER_HOME": str(self.real_home)}):
            return runner.prepare_artifacts(self.root, "c" * 40, self.scratch, build or self.build)

    def test_the_seven_references_are_root_relative_and_the_apks_staged_read_only(self):
        refs, staged = self.prepare()
        self.assertEqual(gate.ARTIFACT_KEYS, set(refs))
        for key, reference in refs.items():
            self.assertTrue(reference["path"].startswith("artifacts/"), key)
            gate.validate_artifact_ref(reference, self.root, key)
        self.assertEqual(b"target", (self.root / refs["targetBenchmarkApk"]["path"]).read_bytes())
        self.assertEqual(0o400, (self.root / refs["macrobenchmarkTestApk"]["path"]).stat().st_mode & 0o777)
        self.assertEqual({dp.TARGET_PACKAGE, dp.TEST_PACKAGE}, set(staged))
        self.assertEqual(refs["targetBenchmarkApk"]["sha256"], staged[dp.TARGET_PACKAGE]["sha256"])
        self.assertEqual(b'{"fixture": true}\n', (self.snapshot / "app/src/s5Benchmark/google-services.json").read_bytes())
        self.assertEqual([True], self.cleaned, "the snapshot is cleaned after collect")

    def test_the_build_uses_its_own_gradle_home_linked_to_the_real_caches_and_collect_gets_both(self):
        self.prepare()
        command, cwd, environment = self.calls[0]
        self.assertEqual(self.snapshot, cwd)
        self.assertEqual([str(self.snapshot / "gradlew"), *runner.BUILD_TASKS], command[:3])
        self.assertIn("--rerun-tasks", command)
        home = Path(environment["GRADLE_USER_HOME"])
        self.assertEqual(self.scratch / "gradle-home", home)
        self.assertTrue((home / "caches").is_symlink())
        self.assertEqual([home, self.real_home], self.collected[2])

    def test_a_failed_build_stops_before_the_session(self):
        def failing(command, cwd, environment):
            return subprocess.CompletedProcess(command, 1, "", "")

        with self.assertRaises(runner.PreSession):
            self.prepare(failing)
        self.assertEqual([True], self.cleaned)

        def crashing(command, cwd, environment):
            raise subprocess.TimeoutExpired(command, 1)

        self.scratch = self.base / "scratch-2"
        self.scratch.mkdir()
        with self.assertRaises(runner.PreSession):
            self.prepare(crashing)


class ReadBackTest(unittest.TestCase):
    """installed_match through the real DeviceOps and device_preflight.installed_apk_identity with a scripted adb."""

    class Adb:
        serial = SERIAL

        def __init__(self, pulled, code):
            self.pulled, self.code = pulled, code

        def shell(self, *arguments, check=True):
            return "0" if arguments[:2] == ("am", "get-current-user") else ""

        def run(self, *arguments, check=True, timeout=None):
            if arguments[:2] == ("shell", "pm"):
                return subprocess.CompletedProcess(arguments, 0, "package:/data/app/x/base.apk\n", "")
            if arguments[0] == "pull":
                Path(arguments[2]).write_bytes(self.pulled)
                return subprocess.CompletedProcess(arguments, self.code, "", "")
            raise AssertionError(arguments)

    def session(self, temp, pulled, code):
        base = Path(temp)
        staged = {}
        for package, data in APKS.items():
            path = base / f"{package}.apk"
            path.write_bytes(data)
            staged[package] = {"path": path, "sha256": dp.file_sha256(path), "identity": dp.stable_file_identity(path, path.name)}
        device = runner.DeviceOps()
        device.adb = self.Adb(pulled, code)
        return runner.Session(base, "s5-run-1", device, None, staged, base)

    def test_only_a_completed_pull_of_other_bytes_is_a_mismatch(self):
        with tempfile.TemporaryDirectory() as temp:
            self.assertFalse(runner.installed_match(self.session(temp, b"other bytes", 0)))
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(dp.PreflightError):
                runner.installed_match(self.session(temp, APKS[dp.TARGET_PACKAGE][:3], 1))
        with tempfile.TemporaryDirectory() as temp:
            session = self.session(temp, b"", 0)
            session.device.adb.pulled = None

            def run(*arguments, check=True, timeout=None, adb=session.device.adb):
                if arguments[0] == "pull":
                    Path(arguments[2]).write_bytes(APKS[dp.TARGET_PACKAGE] if "first" not in adb.__dict__ else APKS[dp.TEST_PACKAGE])
                    adb.__dict__["first"] = True
                    return subprocess.CompletedProcess(arguments, 0, "", "")
                return ReadBackTest.Adb.run(adb, *arguments, check=check, timeout=timeout)

            session.device.adb.run = run
            self.assertTrue(runner.installed_match(session))


class ClassifyTest(unittest.TestCase):
    def test_the_rules_in_order(self):
        base = runner.Observation(runner.INVOKED, behavior="success", snapshots_valid=True, instrumentation_green=True,
                                  result="valid", traces_ok=True)
        import dataclasses
        cases = [
            ({}, ("valid", None)),
            ({"cleanup_error": "x", "behavior": "failure"}, ("aborted", None)),
            ({"apks_changed": True}, ("aborted", None)),
            ({"behavior": "failure", "environment_error": "e"}, ("behavior-failed", None)),
            ({"behavior": "failure", "snapshots_valid": False}, ("aborted", None)),
            ({"behavior": "bad"}, ("aborted", None)),
            ({"behavior": None, "invocation": runner.NOT_INVOKED, "environment_error": "e"}, ("invalid", "environment-invalid")),
            ({"behavior": None, "invocation": runner.INVOKED, "environment_error": "e"}, ("aborted", None)),
            ({"behavior": None, "invocation": runner.UNKNOWN, "environment_error": "e"}, ("aborted", None)),
            ({"behavior": None, "invocation": runner.NOT_INVOKED}, ("aborted", None)),
            ({"environment_error": "postflight"}, ("invalid", "environment-invalid")),
            ({"instrumentation_green": False}, ("aborted", None)),
            ({"result": "unreadable"}, ("aborted", None)),
            ({"result": None}, ("aborted", None)),
            ({"result": "malformed"}, ("invalid", "result-malformed")),
            ({"traces_ok": False}, ("aborted", None)),
            ({"snapshots_valid": False}, ("aborted", None)),
        ]
        for change, (kind, reason) in cases:
            with self.subTest(change):
                outcome = runner.classify(dataclasses.replace(base, **change))
                self.assertEqual(kind, outcome.kind)
                if reason is not None or kind != "aborted":
                    self.assertEqual(reason, outcome.reason)


class ReadingTest(unittest.TestCase):
    def test_only_one_green_test_of_the_requested_method_is_green(self):
        self.assertTrue(runner.instrumentation_green(green("tabEntry"), "tabEntry"))
        for name, text in (("other method", green("scroll")), ("failed", failed("tabEntry")),
                           ("no ok", green("tabEntry").replace("OK (1 test)", "")),
                           ("crashed", green("tabEntry") + "INSTRUMENTATION_FAILED: x"),
                           ("two tests", green("tabEntry").replace("numtests=1", "numtests=2"))):
            with self.subTest(name):
                self.assertFalse(runner.instrumentation_green(text, "tabEntry"))

    def test_snapshots_must_be_valid_and_in_time_order(self):
        with tempfile.TemporaryDirectory() as temp:
            pre, post = Path(temp) / "pre.json", Path(temp) / "post.json"

            def write(path, phase, at, **change):
                path.write_text(json.dumps({**DEVICE_SNAPSHOT, "runId": "r", "phase": phase, "observedAt": at, **change}))

            write(pre, "preflight", "2026-10-14T00:00:01+00:00")
            write(post, "postflight", "2026-10-14T00:00:02+00:00")
            self.assertTrue(runner.snapshots_valid(pre, post, "r"))
            write(post, "postflight", "2026-10-14T00:00:01+00:00")
            self.assertFalse(runner.snapshots_valid(pre, post, "r"), "equal times")
            write(post, "postflight", "2026-10-14T00:00:00+00:00")
            self.assertFalse(runner.snapshots_valid(pre, post, "r"), "post before pre")
            write(post, "postflight", "2026-10-14T00:00:02+00:00", thermalStatus=2)
            self.assertFalse(runner.snapshots_valid(pre, post, "r"), "a gate violation")

    def test_results_are_valid_malformed_or_unreadable(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "result.json"
            good = macro_result(trace_prefix="t", benchmark_name="tabEntry")
            path.write_text(json.dumps(good))
            self.assertEqual(("valid", [f"t-{i}.perfetto-trace" for i in range(5)]), runner.read_result(path, "tab-entry"))
            self.assertEqual("malformed", runner.read_result(path, "scroll")[0], "another journey's identity is malformed")
            for name, data in (("duplicate key", '{"a": 1, "a": 2}'), ("nan", '{"a": NaN}'), ("overflow", '{"a": 1e999}'),
                               ("array", "[1]"), ("syntax", "{")):
                with self.subTest(name):
                    path.write_text(data)
                    self.assertEqual(("unreadable", []), runner.read_result(path, "tab-entry"))
            path.unlink()
            self.assertEqual((None, []), runner.read_result(path, "tab-entry"))


if __name__ == "__main__":
    unittest.main()
