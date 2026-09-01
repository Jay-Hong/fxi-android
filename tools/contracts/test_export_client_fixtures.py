import tempfile
import unittest
from pathlib import Path

from export_client_fixtures import compare_trees


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


if __name__ == "__main__":
    unittest.main()
