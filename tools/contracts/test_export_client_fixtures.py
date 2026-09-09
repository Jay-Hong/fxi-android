import tempfile
import unittest
from pathlib import Path

from export_client_fixtures import compare_trees, verify_capture_headers


class CompareTreesTest(unittest.TestCase):
    def test_reports_checked_and_generated_only_files_with_correct_ownership(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            checked = root / "checked"
            generated = root / "generated"
            checked.mkdir()
            generated.mkdir()
            (checked / "stale.json").write_text("checked only\n")
            (generated / "new.json").write_text("generated only\n")

            self.assertEqual(
                [
                    "missing checked fixture: new.json",
                    "orphan checked fixture: stale.json",
                ],
                compare_trees(checked, generated),
            )

    def test_reports_content_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            checked = root / "checked"
            generated = root / "generated"
            checked.mkdir()
            generated.mkdir()
            (checked / "same-path.json").write_text("old\n")
            (generated / "same-path.json").write_text("new\n")

            self.assertEqual(
                ["content differs: same-path.json"],
                compare_trees(checked, generated),
            )



class VerifyCaptureHeadersTest(unittest.TestCase):
    """The half of the header contract that the fixture cannot carry.

    A recorded header proves the server sent it. An *un*recorded header proves nothing —
    the capture writes only what it asked for, so "absent" and "never requested" are the
    same bytes on disk. These lock the check that closes that gap, at the one point where
    the live response is still in hand.
    """

    def test_missing_required_header_fails_the_capture(self):
        with self.assertRaises(RuntimeError) as raised:
            verify_capture_headers("premium-pending-503", {}, required=("Retry-After",))
        self.assertIn("lost headers", str(raised.exception))

    def test_forbidden_header_fails_the_capture(self):
        # The boundary this exists for: a server that started sending a retry floor on a
        # response whose contract is that it has none.
        with self.assertRaises(RuntimeError) as raised:
            verify_capture_headers(
                "auth-infrastructure-503",
                {"Retry-After": "5"},
                forbidden=("Retry-After",),
            )
        self.assertIn("gained headers", str(raised.exception))

    def test_absent_forbidden_header_passes(self):
        verify_capture_headers(
            "auth-infrastructure-503", {"Content-Type": "application/json"},
            forbidden=("Retry-After",),
        )

    def test_both_halves_are_checked_together(self):
        verify_capture_headers(
            "mixed", {"Retry-After": "5"},
            required=("Retry-After",), forbidden=("Cache-Control",),
        )
        with self.assertRaises(RuntimeError):
            verify_capture_headers(
                "mixed", {"Retry-After": "5", "Cache-Control": "no-store"},
                required=("Retry-After",), forbidden=("Cache-Control",),
            )
if __name__ == "__main__":
    unittest.main()
