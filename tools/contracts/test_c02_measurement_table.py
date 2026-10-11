"""Pure-stdlib tests of c02_measurement_table: 통과 only for a complete run in budget with its evidence."""
import json
import os
import tempfile
import unittest
from xml.sax.saxutils import escape

import c02_measurement_table as t

CLS = "com.jay.fxi.budget.PremiumGraphCutoverAcceptanceTest"
IN = "Verdict(apiExcessMilli=9000, wsExcessMilli=0, apiInFlight=3, refused=[], answered429=0, apiInFlightHeaders=3, apiInFlightBodies=3)"
OVER = "Verdict(apiExcessMilli=10023, wsExcessMilli=0, apiInFlight=3, refused=[], answered429=0, apiInFlightHeaders=3, apiInFlightBodies=3)"
ROWS = ["C02M-L1a five", "C02M-L1b order", "C02M-A05t1600 timed"]


def out(label, verdict=IN, timeline=True, extra=""):
    lines = []
    if timeline:
        lines.append(f"CUT-C02 {label} timeline: 1ms api /api/entitlements -> 200 body@2ms")
    if verdict:
        lines.append(f"CUT-C02 {label} verdict: {verdict}")
    if extra:
        lines.append(extra)
    return "\n".join(lines)


class C02MeasurementTableTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.cases = os.path.join(self.dir.name, "cases.json")
        self.results = os.path.join(self.dir.name, "results")
        os.makedirs(self.results)
        with open(self.cases, "w", encoding="utf-8") as f:
            json.dump({"class": CLS, "cases": ROWS}, f)

    def tearDown(self):
        self.dir.cleanup()

    def write(self, cases, name="TEST-run.xml"):
        body = ""
        for n, system_out, extra in cases:
            body += f'<testcase name="{escape(n)}" classname="{CLS}" time="1">{extra}<system-out>{escape(system_out)}</system-out></testcase>'
        with open(os.path.join(self.results, name), "w", encoding="utf-8") as f:
            f.write(f'<?xml version="1.0"?><testsuite name="{CLS}" tests="{len(cases)}">{body}</testsuite>')

    def good(self):
        return [
            ("C02M-L1a five", out("C02M-L1a"), ""),
            ("C02M-L1b order", out("C02M-L1b", extra="C02M-ORDER C02M-L1b: opened @1"), ""),
            ("C02M-A05t1600 timed", out("C02M-A05t1600", extra="C02M-TIMING C02M-A05t1600: T0=8 target=+1600 changed=+1601"), ""),
        ]

    def run_table(self):
        return t.table(self.cases, self.results, sha="abc", run="1", status="success")

    def row(self, text, name):
        return next(line for line in text.splitlines() if line.startswith(f"| {name} |"))

    def test_complete_run_in_budget_passes(self):
        self.write(self.good())
        passed, text = self.run_table()
        self.assertTrue(passed, text)
        self.assertTrue(text.startswith("C02 계량 묶음: 통과"))
        self.assertIn("C02 통과를 선언하지 않는다", text)
        self.assertIn("순서 확보", self.row(text, "C02M-L1b order"))
        self.assertIn("시점 확보", self.row(text, "C02M-A05t1600 timed"))

    def test_over_budget_failure_is_over(self):
        rows = self.good()
        rows[0] = ("C02M-L1a five", out("C02M-L1a", verdict=OVER), '<failure message="api excess 10023 &gt; 10000"/>')
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        line = self.row(text, "C02M-L1a five")
        self.assertIn("| failure | 초과 | api 10023", line)

    def test_failure_message_alone_is_partial(self):
        rows = self.good()
        rows[0] = ("C02M-L1a five", out("C02M-L1a", verdict=None), '<failure message="api excess 10206 &gt; 10000"/>')
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("부분 계량 | api 10206 (실패 메시지만, 나머지 미계량)", self.row(text, "C02M-L1a five"))

    def test_other_failure_without_verdict_is_unmeasured(self):
        rows = self.good()
        rows[0] = ("C02M-L1a five", out("C02M-L1a", verdict=None, timeline=False), '<failure message="timed out: x"/>')
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        line = self.row(text, "C02M-L1a five")
        self.assertIn("| failure | 미계량 |", line)
        self.assertIn("누락", line)

    def test_missing_row_fails(self):
        self.write(self.good()[:2])
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("missing 1", text.splitlines()[0])
        self.assertIn("| missing | 미계량 |", self.row(text, "C02M-A05t1600 timed"))

    def test_duplicate_and_unlisted_fail(self):
        rows = self.good() + [("C02M-L1a five", out("C02M-L1a"), ""), ("C02M-X9 extra", out("C02M-X9"), "")]
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("중복 1", text.splitlines()[0])
        self.assertIn("미등록 1", text.splitlines()[0])
        self.assertIn("not in the approved list: C02M-X9 extra", text)

    def test_error_and_skip_fail(self):
        rows = self.good()
        rows[0] = ("C02M-L1a five", out("C02M-L1a"), '<error message="boom"/>')
        rows[1] = ("C02M-L1b order", out("C02M-L1b"), "<skipped/>")
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("| error |", self.row(text, "C02M-L1a five"))
        self.assertIn("| skipped |", self.row(text, "C02M-L1b order"))

    def test_no_xml_and_unreadable_xml_fail(self):
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("XML 없음/파싱 실패 1", text.splitlines()[0])
        with open(os.path.join(self.results, "TEST-bad.xml"), "w", encoding="utf-8") as f:
            f.write("<testsuite")
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("unreadable XML TEST-bad.xml", text)

    def test_order_not_secured_fails(self):
        rows = self.good()
        rows[1] = ("C02M-L1b order", out("C02M-L1b"), '<failure message="C02M-L1b: 순서 미확보 — x"/>')
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("순서 미확보", self.row(text, "C02M-L1b order"))
        self.assertIn("순서·시점 미확보 1", text.splitlines()[0])

    def test_timeline_missing_fails_even_in_budget(self):
        rows = self.good()
        rows[0] = ("C02M-L1a five", out("C02M-L1a", timeline=False), "")
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("| pass | 통과 |", self.row(text, "C02M-L1a five"))
        self.assertIn("| 누락 |", self.row(text, "C02M-L1a five"))

    def test_timing_not_secured_fails(self):
        rows = self.good()
        rows[2] = ("C02M-A05t1600 timed", out("C02M-A05t1600", verdict=None),
                   '<failure message="C02M-A05t1600: the change started within 100 ms of its target (target +1600, changed +1900)"/>')
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("시점 미확보", self.row(text, "C02M-A05t1600 timed"))

    def test_each_row_reads_only_its_own_output(self):
        rows = self.good()
        # The L1a case carries the L1b verdict only: L1a must not borrow it, and L1b must not borrow L1a's.
        rows[0] = ("C02M-L1a five", out("C02M-L1b", verdict=OVER), "")
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("| pass | 미계량 |", self.row(text, "C02M-L1a five"))
        self.assertIn("| pass | 통과 |", self.row(text, "C02M-L1b order"))

    def test_order_line_missing_is_not_secured(self):
        rows = self.good()
        rows[1] = ("C02M-L1b order", out("C02M-L1b"), "")
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("순서 미확보", self.row(text, "C02M-L1b order"))

    def test_output_only_at_suite_level_is_unmeasured(self):
        rows = [(n, "", extra) for n, _, extra in self.good()]
        self.write(rows)
        with open(os.path.join(self.results, "TEST-run.xml"), encoding="utf-8") as f:
            xml = f.read()
        suite_out = escape(out("C02M-L1a"))
        with open(os.path.join(self.results, "TEST-run.xml"), "w", encoding="utf-8") as f:
            f.write(xml.replace("</testsuite>", f"<system-out>{suite_out}</system-out></testsuite>"))
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("| pass | 미계량 |", self.row(text, "C02M-L1a five"))

    def test_timing_premise_failure_is_not_secured(self):
        rows = self.good()
        rows[2] = ("C02M-A05t1600 timed", out("C02M-A05t1600", verdict=None, timeline=False),
                   '<failure message="C02M-A05t1600: premise: the change time is still ahead (now 1914, T0 271, target 1871)"/>')
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("시점 미확보", self.row(text, "C02M-A05t1600 timed"))

    def test_run_status_other_than_success_fails(self):
        self.write(self.good())
        passed, text = t.table(self.cases, self.results, sha="abc", run="1", status="failure")
        self.assertFalse(passed, text)
        self.assertIn("실행 상태 failure 1", text.splitlines()[0])
        passed, _ = t.table(self.cases, self.results)
        self.assertTrue(passed)

    def test_missing_rows_without_order_or_timing_count_no_evidence_gap(self):
        with open(self.cases, "w", encoding="utf-8") as f:
            json.dump({"class": CLS, "cases": ROWS + ["C02M-K1a socket"]}, f)
        self.write(self.good())
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("| missing | 미계량 |  | 누락 | 해당 없음 |", self.row(text, "C02M-K1a socket"))
        self.assertNotIn("순서·시점 미확보", text.splitlines()[0])

    def test_incomplete_verdict_is_unmeasured(self):
        rows = self.good()
        no_refused = "Verdict(apiExcessMilli=9000, wsExcessMilli=0, apiInFlight=3, answered429=0)"
        no_ws = "Verdict(apiExcessMilli=9000, apiInFlight=3, refused=[], answered429=0)"
        rows[0] = ("C02M-L1a five", out("C02M-L1a", verdict=no_refused), "")
        rows[1] = ("C02M-L1b order", out("C02M-L1b", verdict=no_ws, extra="C02M-ORDER C02M-L1b: x"), "")
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("| pass | 미계량 | api 9000 / ws 0 / in-flight 3 / refused 미계량 / 429 0 |", self.row(text, "C02M-L1a five"))
        self.assertIn("| pass | 미계량 | api 9000 / ws 미계량 /", self.row(text, "C02M-L1b order"))

    def test_timeline_missing_is_summarised(self):
        rows = self.good()
        rows[0] = ("C02M-L1a five", out("C02M-L1a", timeline=False), "")
        self.write(rows)
        passed, text = self.run_table()
        self.assertFalse(passed)
        self.assertIn("timeline 미확보 1", text.splitlines()[0])

    def test_reachability_and_comparison_evidence_are_listed(self):
        self.write(self.good())
        _, text = self.run_table()
        self.assertIn("| C02M-A05t1600 | 판단 보류 — 실제 인증·UI 경로 근거 미확보 |", text)
        self.assertIn("A03a_burst_excess.xml — api excess 10.023", text)
        self.assertIn("RT01-A05_merged_identity_change_1600ms_after_cold_start.xml — api excess 10.206", text)
        self.assertIn("실행 checkout SHA: abc", text)

    def test_cli_exit_codes(self):
        self.write(self.good())
        self.assertEqual(0, t.main(["x", self.cases, self.results, "--sha", "abc"]))
        self.write(self.good()[:1])
        self.assertEqual(1, t.main(["x", self.cases, self.results]))
        self.assertEqual(2, t.main(["x", self.cases]))


if __name__ == "__main__":
    unittest.main()
