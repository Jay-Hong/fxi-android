import subprocess
import tempfile
import unittest
from pathlib import Path

from device_release_admission import (
    EXPECTED_CASES,
    ReleaseEvidenceError,
    capture_instrumentation_result,
    force_netstats_poll,
    observe_application_guard,
    observe_unavailable_ui,
    parse_netstats_app_uid_map,
    parse_netstats_uid_history,
    parse_unavailable_ui_dump,
    physical,
)


class FakeAdb:
    serial = "fixture-device-serial"


class FakeCommandAdb:
    serial = "fixture-device-serial"

    def __init__(self, result):
        self.result = result

    def run(self, *args, **kwargs):
        return self.result


class FakeUiDumpAdb:
    serial = "fixture-device-serial"

    def __init__(
        self,
        xml: str,
        read_return_code: int = 0,
        *,
        preexisting_link: bool = False,
        removal_return_code: int = 0,
        raise_during_capture: bool = False,
    ):
        self.xml = xml
        self.read_return_code = read_return_code
        self.preexisting_link = preexisting_link
        self.removal_return_code = removal_return_code
        self.raise_during_capture = raise_during_capture
        self.calls = []

    def run(self, *args, **kwargs):
        self.calls.append(args)
        if args[:3] == ("shell", "test", "-e"):
            return subprocess.CompletedProcess(args=args, returncode=1, stdout="", stderr="")
        if args[:3] == ("shell", "test", "-L"):
            return subprocess.CompletedProcess(
                args=args,
                returncode=0 if self.preexisting_link else 1,
                stdout="",
                stderr="",
            )
        if args[:3] == ("shell", "uiautomator", "dump"):
            if self.raise_during_capture:
                raise physical.PreflightError(f"leaked path {args[-1]}")
            return subprocess.CompletedProcess(
                args=args, returncode=0, stdout="dumped\n", stderr=""
            )
        if args[:2] == ("exec-out", "cat"):
            return subprocess.CompletedProcess(
                args=args,
                returncode=self.read_return_code,
                stdout=self.xml if self.read_return_code == 0 else "",
                stderr="" if self.read_return_code == 0 else "read failed",
            )
        if args[:3] == ("shell", "rm", "-f"):
            return subprocess.CompletedProcess(
                args=args,
                returncode=self.removal_return_code,
                stdout="",
                stderr="",
            )
        if args[:4] == ("shell", "test", "!", "-e"):
            return subprocess.CompletedProcess(args=args, returncode=0, stdout="", stderr="")
        if args[:4] == ("shell", "test", "!", "-L"):
            return subprocess.CompletedProcess(args=args, returncode=0, stdout="", stderr="")
        raise AssertionError(args)


def green_instrumentation() -> str:
    lines = []
    for class_name, method in EXPECTED_CASES:
        for status in ("1", "0"):
            lines.extend(
                (
                    f"INSTRUMENTATION_STATUS: class={class_name}",
                    f"INSTRUMENTATION_STATUS: test={method}",
                    f"INSTRUMENTATION_STATUS_CODE: {status}",
                )
            )
    lines.extend(("Time: 1.25", "OK (4 tests)", "INSTRUMENTATION_CODE: -1"))
    return "\n".join(lines) + "\n"


class InstrumentationCaptureTest(unittest.TestCase):
    def test_sanitizes_exact_four_test_result(self):
        result = subprocess.CompletedProcess(
            args=["adb"], returncode=0, stdout=green_instrumentation(), stderr=""
        )
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "instrumentation-result.json"
            document = capture_instrumentation_result(
                FakeAdb(), result, "release-admission-deadbee-r1", destination
            )
            self.assertEqual(4, document["tests"])
            self.assertEqual(0, document["failures"])
            self.assertEqual(1.25, document["durationSeconds"])
            self.assertTrue(destination.is_file())
            self.assertNotIn(FakeAdb.serial, destination.read_text())

    def test_rejects_missing_case_failure_and_serial_leak(self):
        cases = (
            green_instrumentation().replace(
                f"INSTRUMENTATION_STATUS: class={EXPECTED_CASES[-1][0]}\n",
                "",
                1,
            ),
            green_instrumentation().replace("OK (4 tests)", "FAILURES!!!"),
            green_instrumentation() + FakeAdb.serial,
        )
        for index, output in enumerate(cases):
            result = subprocess.CompletedProcess(
                args=["adb"], returncode=0, stdout=output, stderr=""
            )
            with self.subTest(index=index), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaises(ReleaseEvidenceError):
                    capture_instrumentation_result(
                        FakeAdb(),
                        result,
                        "release-admission-deadbee-r1",
                        Path(temporary) / "instrumentation-result.json",
                    )


class NetstatsParserTest(unittest.TestCase):
    def test_sums_only_untagged_target_uid_default_and_foreground_histories(self):
        payload = """
  ident=[redacted] uid=123 set=DEFAULT tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=1 rb=10 rp=1 tb=20 tp=2 op=0
      st=2 rb=30 rp=3 tb=40 tp=4 op=0
  ident=[redacted] uid=123 set=FOREGROUND tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=3 rb=50 rp=5 tb=60 tp=6 op=0
  ident=[redacted] uid=123 set=DEFAULT tag=0x42
    NetworkStatsHistory: bucketDuration=7200
      st=4 rb=700 rp=7 tb=800 tp=8 op=0
  ident=[redacted] uid=123 set=DBG_VPN_IN tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=4 rb=700 rp=7 tb=800 tp=8 op=0
  ident=[redacted] uid=999 set=DEFAULT tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=5 rb=900 rp=9 tb=1000 tp=10 op=0
"""
        self.assertEqual(
            {"rows": 3, "rxBytes": 90, "txBytes": 120},
            parse_netstats_uid_history(payload, 123),
        )

    def test_zero_target_rows_is_valid_but_format_drift_is_not(self):
        other_uid = """
  ident=[redacted] uid=999 set=DEFAULT tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=5 rb=900 rp=9 tb=1000 tp=10 op=0
"""
        self.assertEqual(
            {"rows": 0, "rxBytes": 0, "txBytes": 0},
            parse_netstats_uid_history(other_uid, 123),
        )
        malformed = """
  ident=[redacted] uid=123 set=DEFAULT tag=0x0
    NetworkStatsHistory: bucketDuration=7200
      st=5 rxBytes=1 txBytes=2
"""
        with self.assertRaises(ReleaseEvidenceError):
            parse_netstats_uid_history(malformed, 123)

    def test_target_history_header_drift_fails_even_when_other_uid_is_parseable(self):
        payload = """
  ident=[redacted] uid=123 set=DEFAULT tag=0x0 newField=1
      st=1 rb=10 rp=1 tb=20 tp=2 op=0
  ident=[redacted] uid=999 set=DEFAULT tag=0x0
      st=2 rb=30 rp=3 tb=40 tp=4 op=0
"""
        with self.assertRaises(ReleaseEvidenceError):
            parse_netstats_uid_history(payload, 123)

    def test_live_bpf_map_deduplicates_identical_target_rows(self):
        payload = """
BPF map content:
  mAppUidStatsMap:
    uid rxBytes rxPackets txBytes txPackets
    123 10 1 20 2
    Entry is deleted while dumping, iterating from first entry
    123 10 1 20 2
    999 30 3 40 4
  mStatsMapA:
"""
        self.assertEqual(
            {"rows": 1, "rxBytes": 10, "txBytes": 20},
            parse_netstats_app_uid_map(payload, 123),
        )

    def test_live_bpf_map_rejects_conflict_missing_header_and_format_drift(self):
        valid = """
  mAppUidStatsMap:
    uid rxBytes rxPackets txBytes txPackets
    123 10 1 20 2
  mStatsMapA:
"""
        cases = (
            valid.replace("    123 10 1 20 2\n", "    123 10 1 20 2\n    123 11 1 20 2\n"),
            valid.replace("uid rxBytes rxPackets txBytes txPackets", "uid rx tx"),
            valid.replace("123 10 1 20 2", "123 rx=10 tx=20"),
        )
        for payload in cases:
            with self.subTest(payload=payload), self.assertRaises(ReleaseEvidenceError):
                parse_netstats_app_uid_map(payload, 123)


class ObservationOracleTest(unittest.TestCase):
    def test_forced_poll_requires_the_aosp_success_oracle(self):
        good = subprocess.CompletedProcess(
            args=["adb"], returncode=0, stdout="Forced poll\n", stderr=""
        )
        force_netstats_poll(FakeCommandAdb(good))
        bad = (
            subprocess.CompletedProcess(args=["adb"], returncode=1, stdout="", stderr=""),
            subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr=""),
            subprocess.CompletedProcess(
                args=["adb"], returncode=0, stdout="Forced poll\n", stderr="warning"
            ),
        )
        for result in bad:
            with self.subTest(result=result), self.assertRaises(ReleaseEvidenceError):
                force_netstats_poll(FakeCommandAdb(result))

    def test_ui_dump_requires_exact_unavailable_text_and_no_app_clicks(self):
        xml = '''<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node package="com.jay.fxi" text="현재 버전은 사용할 수 없습니다" clickable="false" long-clickable="false" />
  <node package="com.jay.fxi" text="업데이트가 준비될 때까지 잠시 기다려 주세요." clickable="false" long-clickable="false" />
</hierarchy>
UI hierchary dumped to: /dev/tty
'''
        self.assertEqual(0, parse_unavailable_ui_dump(xml)["appClickableNodeCount"])
        with self.assertRaises(ReleaseEvidenceError):
            parse_unavailable_ui_dump(xml.replace('clickable="false"', 'clickable="true"', 1))

    def test_device_ui_dump_uses_one_owned_path_and_always_removes_it(self):
        xml = '''<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node package="com.jay.fxi" text="현재 버전은 사용할 수 없습니다" clickable="false" long-clickable="false" />
  <node package="com.jay.fxi" text="업데이트가 준비될 때까지 잠시 기다려 주세요." clickable="false" long-clickable="false" />
</hierarchy>'''
        adb = FakeUiDumpAdb(xml)
        result = observe_unavailable_ui(adb, "release-admission-deadbee-r1")
        self.assertTrue(result["temporaryUiDumpRemoved"])
        self.assertEqual(1, sum(call[:3] == ("shell", "rm", "-f") for call in adb.calls))
        remote_paths = {
            call[-1]
            for call in adb.calls
            if call[:2] in {("shell", "test"), ("shell", "rm")}
            or call[:2] == ("exec-out", "cat")
            or call[:3] == ("shell", "uiautomator", "dump")
        }
        self.assertEqual(
            {"/data/local/tmp/fxi-release-admission-ui-release-admission-deadbee-r1.xml"},
            remote_paths,
        )

        failing = FakeUiDumpAdb(xml, read_return_code=1)
        with self.assertRaises(ReleaseEvidenceError):
            observe_unavailable_ui(failing, "release-admission-deadbee-r2")
        self.assertEqual(
            1, sum(call[:3] == ("shell", "rm", "-f") for call in failing.calls)
        )

    def test_device_ui_dump_rejects_symlink_cleanup_failure_and_path_leak(self):
        xml = '''<?xml version="1.0"?><hierarchy rotation="0"></hierarchy>'''
        with self.assertRaises(ReleaseEvidenceError):
            observe_unavailable_ui(
                FakeUiDumpAdb(xml, preexisting_link=True),
                "release-admission-deadbee-link",
            )
        with self.assertRaises(ReleaseEvidenceError):
            observe_unavailable_ui(
                FakeUiDumpAdb(xml, removal_return_code=1),
                "release-admission-deadbee-cleanup",
            )
        with self.assertRaises(ReleaseEvidenceError) as raised:
            observe_unavailable_ui(
                FakeUiDumpAdb(xml, raise_during_capture=True),
                "release-admission-deadbee-error",
            )
        self.assertNotIn("/data/local/tmp", str(raised.exception))

    def test_application_guard_must_follow_unique_watermark_for_current_pid(self):
        marker = "FXI_S0G_fixture"
        output = "\n".join(
            (
                "09-01 08:00:00.000  111  111 I FXiApplication: D24-OFF/no-data process: app-owned services remain disabled.",
                f"09-01 08:00:01.000  222  222 I FXiEvidence: {marker}",
                "09-01 08:00:02.000  333  333 I FXiApplication: D24-OFF/no-data process: app-owned services remain disabled.",
            )
        )
        result = subprocess.CompletedProcess(
            args=["adb"], returncode=0, stdout=output, stderr=""
        )
        self.assertTrue(observe_application_guard(FakeCommandAdb(result), marker, "333"))
        with self.assertRaises(ReleaseEvidenceError):
            observe_application_guard(FakeCommandAdb(result), marker, "111")


if __name__ == "__main__":
    unittest.main()
