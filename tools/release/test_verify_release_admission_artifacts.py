import unittest
from pathlib import Path

from verify_release_admission_artifacts import (
    FIREBASE_AUTO_INIT_MARKERS,
    VerificationError,
    parse_artifact,
    parse_marker,
    verify_firebase_auto_init_off,
)


def manifest(value: str) -> str:
    return f'''E: application
  E: meta-data
    A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.jay.fxi.TOPIC_V2_RELEASE_ON"
    A: http://schemas.android.com/apk/res/android:value(0x01010024)={value}
'''


def firebase_manifest(value: str = "false") -> str:
    lines = ["E: application"]
    for marker in FIREBASE_AUTO_INIT_MARKERS:
        lines.extend(
            (
                "  E: meta-data",
                "    A: http://schemas.android.com/apk/res/android:name(0x01010003)="
                f'"{marker}"',
                "    A: http://schemas.android.com/apk/res/android:value(0x01010024)="
                f"{value}",
            )
        )
    return "\n".join(lines) + "\n"


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

    def test_off_artifact_requires_all_three_firebase_markers_to_be_false(self):
        verify_firebase_auto_init_off(firebase_manifest())
        for invalid in (
            firebase_manifest("true"),
            firebase_manifest().replace(FIREBASE_AUTO_INIT_MARKERS[0], "missing", 1),
            firebase_manifest() + firebase_manifest(),
        ):
            with self.subTest(payload=invalid), self.assertRaises(VerificationError):
                verify_firebase_auto_init_off(invalid)


if __name__ == "__main__":
    unittest.main()
