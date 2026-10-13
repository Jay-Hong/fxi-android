"""S5-4 slice 1b: every copy of the fixed device's fingerprint is the one observed on 2026-10-13 (one value, no relaxation).

The copies stay apart on purpose: benchmark_gate.py is hashed into the manifest as the executing parser, so its contract
values must live in its own bytes. Each module's own tests build their fixtures from that module's constant, so a copy left
at the old value would still pass them; this test pins every copy to the observed value: the three host constants, the
release admission validator's in-function expectation and its test's device fixture (read from source, exactly one each),
and the launcher smoke's Kotlin constant.
"""

import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "release"))

import benchmark_gate  # noqa: E402
import device_preflight  # noqa: E402
import launcher_evidence  # noqa: E402
import release_admission_evidence  # noqa: E402

# Observed on the fixed SM-F711N after its OTA (ro.build.fingerprint, read-only, 2026-10-13 23:21 KST).
OBSERVED = "samsung/b2qksx/b2q:15/AP3A.240905.015.A2/F711NKSSFKZH5:user/release-keys"
SEALED_S0 = "samsung/b2qksx/b2q:15/AP3A.240905.015.A2/F711NKSSEKZE1:user/release-keys"


def _fingerprint_literals(path):
    """Each `"fingerprint": ("..." "...")` block in [path], its string pieces joined as Python joins them."""
    source = path.read_text(encoding="utf-8")
    blocks = re.findall(r'"fingerprint":\s*\(((?:\s*"[^"]*")+)\s*\)', source)
    return ["".join(re.findall(r'"([^"]*)"', block)) for block in blocks]


class DeviceContractTest(unittest.TestCase):
    def test_host_checkers_hold_the_observed_fingerprint(self):
        self.assertEqual(OBSERVED, device_preflight.EXPECTED_FINGERPRINT)
        self.assertEqual(OBSERVED, launcher_evidence.EXPECTED_FINGERPRINT)
        self.assertEqual(OBSERVED, benchmark_gate.EXPECTED_MEASUREMENT_CONTRACT["fingerprint"])

    def test_release_admission_sources_hold_the_observed_fingerprint(self):
        release = ROOT / "tools" / "release"
        self.assertEqual([OBSERVED], _fingerprint_literals(release / "release_admission_evidence.py"))
        self.assertEqual([OBSERVED], _fingerprint_literals(release / "test_release_admission_evidence.py"))

    def test_release_admission_accepts_only_the_observed_fingerprint(self):
        device = {
            "model": "SM-F711N",
            "fingerprint": OBSERVED,
            "api": 35,
            "posture": "OPENED",
            "physicalSize": "1080x2640",
            "overrideSize": None,
            "orientation": 0,
            "viewportSize": "1080x2640",
            "wakefulness": "Awake",
        }
        release_admission_evidence._device(device, "device")
        with self.assertRaises(release_admission_evidence.EvidenceError):
            release_admission_evidence._device({**device, "fingerprint": SEALED_S0}, "device")

    def test_launcher_smoke_holds_the_observed_fingerprint(self):
        source = (
            ROOT / "macrobenchmark/src/main/kotlin/com/jay/fxi/macrobenchmark/LauncherSmokeBenchmark.kt"
        ).read_text(encoding="utf-8")
        found = re.findall(r'const val EXPECTED_FINGERPRINT =\s*"([^"]*)"', source)
        self.assertEqual([OBSERVED], found)


if __name__ == "__main__":
    unittest.main()
