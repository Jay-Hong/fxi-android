import unittest

from verify_benchmark_artifacts import VerificationError, assert_metadata_false


class ManifestMetadataTest(unittest.TestCase):
    def test_false_boolean_is_read_from_real_aapt_style_node_header(self):
        manifest = """E: manifest (line=2)
  E: application (line=10)
    E: meta-data (line=88)
      A: android:name(0x01010003)=\"firebase_crashlytics_collection_enabled\" (Raw: \"firebase_crashlytics_collection_enabled\")
      A: android:value(0x01010024)=(type 0x12)0x0
"""
        assert_metadata_false(manifest, "firebase_crashlytics_collection_enabled")

    def test_true_boolean_is_rejected(self):
        manifest = """E: manifest (line=2)
  E: application (line=10)
    E: meta-data (line=88)
      A: android:name(0x01010003)=\"firebase_crashlytics_collection_enabled\"
      A: android:value(0x01010024)=(type 0x12)0xffffffff
"""
        with self.assertRaises(VerificationError):
            assert_metadata_false(manifest, "firebase_crashlytics_collection_enabled")


if __name__ == "__main__":
    unittest.main()
