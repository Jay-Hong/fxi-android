"""Pure-stdlib tests of verify_cutover_acceptance: it accepts only the exact approved run."""
import json
import os
import tempfile
import unittest

import verify_cutover_acceptance as v

CLS = "com.jay.fxi.budget.PremiumGraphCutoverAcceptanceTest"


class VerifyCutoverAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.cases = os.path.join(self.dir.name, "cases.json")
        self.results = os.path.join(self.dir.name, "results")
        os.makedirs(self.results)
        with open(self.cases, "w", encoding="utf-8") as f:
            json.dump({"class": CLS, "cases": ["A01 one", "A02 two"]}, f)

    def tearDown(self):
        self.dir.cleanup()

    def write(self, cases, name="TEST-run.xml", cls=CLS):
        body = "".join(
            f'<testcase name="{n}" classname="{cls}" time="1">{extra}</testcase>' for n, extra in cases
        )
        with open(os.path.join(self.results, name), "w", encoding="utf-8") as f:
            f.write(f'<?xml version="1.0"?><testsuite name="{cls}" tests="{len(cases)}">{body}</testsuite>')

    def problems(self):
        return v.check(self.cases, self.results)

    def test_the_exact_run_passes(self):
        self.write([("A01 one", ""), ("A02 two", "")])
        self.assertEqual([], self.problems())

    def test_a_missing_case_fails(self):
        self.write([("A01 one", "")])
        self.assertEqual(["missing: A02 two"], self.problems())

    def test_a_replaced_case_with_the_same_total_fails(self):
        self.write([("A01 one", ""), ("A02 other", "")])
        self.assertEqual(["missing: A02 two", "not in the approved list: A02 other"], self.problems())

    def test_a_duplicated_case_fails(self):
        self.write([("A01 one", ""), ("A02 two", "")], name="TEST-a.xml")
        self.write([("A02 two", "")], name="TEST-b.xml")
        self.assertEqual(["ran 2 times: A02 two"], self.problems())

    def test_a_skipped_failed_or_errored_case_fails(self):
        for kind in ("skipped", "failure", "error"):
            with self.subTest(kind=kind):
                self.write([("A01 one", f"<{kind}/>"), ("A02 two", "")])
                self.assertEqual([f"{kind}: A01 one"], self.problems())

    def test_no_xml_fails(self):
        self.assertEqual([f"no JUnit XML in {self.results}"], self.problems())

    def test_other_classes_are_ignored(self):
        self.write([("A01 one", ""), ("A02 two", "")])
        self.write([("x", "<failure/>")], name="TEST-other.xml", cls="com.jay.fxi.Other")
        self.assertEqual([], self.problems())

    def test_the_main_exit_codes(self):
        self.write([("A01 one", ""), ("A02 two", "")])
        self.assertEqual(0, v.main(["v", self.cases, self.results]))
        self.write([("A01 one", "")])
        self.assertEqual(1, v.main(["v", self.cases, self.results]))


if __name__ == "__main__":
    unittest.main()
