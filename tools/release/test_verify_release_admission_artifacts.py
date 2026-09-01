import unittest
from pathlib import Path

from verify_release_admission_artifacts import VerificationError, parse_artifact, parse_marker


def manifest(value: str) -> str:
    return f'''E: application
  E: meta-data
    A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.jay.fxi.TOPIC_V2_RELEASE_ON"
    A: http://schemas.android.com/apk/res/android:value(0x01010024)={value}
'''


class ParseMarkerTest(unittest.TestCase):
    def test_accepts_canonical_false_and_true(self):
        self.assertFalse(parse_marker(manifest("false")))
        self.assertTrue(parse_marker(manifest("true")))

    def test_rejects_missing_duplicate_non_boolean_and_noncanonical_values(self):
        cases = (
            "E: application",
            manifest("false") + manifest("false"),
            manifest("1"),
            manifest('"true" (Raw: "true")'),
            manifest("true").replace(
                "    A: http://schemas.android.com/apk/res/android:value(0x01010024)=true\n",
                "  E: meta-data\n"
                "    A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"other\"\n"
                "    A: http://schemas.android.com/apk/res/android:value(0x01010024)=true\n",
            ),
        )
        for payload in cases:
            with self.subTest(payload=payload), self.assertRaises(VerificationError):
                parse_marker(payload)

    def test_artifact_spec_is_strict(self):
        self.assertEqual(
            ("debug", False, Path("app-debug.apk")),
            parse_artifact("debug=off=app-debug.apk"),
        )
        for invalid in ("debug=false=x.apk", "debug=off", "=off=x.apk"):
            with self.subTest(spec=invalid), self.assertRaises(VerificationError):
                parse_artifact(invalid)


if __name__ == "__main__":
    unittest.main()
