#!/usr/bin/env python3
"""Verify S0-f APK identities without trusting source configuration alone."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Sequence


class VerificationError(RuntimeError):
    pass


def _version_key(path: Path) -> tuple[int, ...]:
    values = re.findall(r"\d+", path.parent.name)
    return tuple(int(value) for value in values)


def find_build_tool(name: str) -> Path:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        default = Path.home() / "Library" / "Android" / "sdk"
        if default.is_dir():
            sdk = str(default)
    if not sdk:
        raise VerificationError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    candidates = [path for path in (Path(sdk) / "build-tools").glob(f"*/{name}") if path.is_file()]
    if not candidates:
        raise VerificationError(f"Android build tool not found: {name}")
    return max(candidates, key=_version_key)


def command(*arguments: str) -> str:
    result = subprocess.run(arguments, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise VerificationError(f"command failed ({' '.join(arguments)}): {result.stderr.strip()}")
    return result.stdout


def assert_contains(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise VerificationError(f"{label} missing {needle!r}")


def manifest_node_for_value(text: str, value: str, expected_node: str) -> str:
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if f'="{value}"' not in line:
            continue
        node_index = index
        while node_index >= 0 and not lines[node_index].lstrip().startswith("E: "):
            node_index -= 1
        if node_index < 0 or not lines[node_index].strip().startswith(f"E: {expected_node}"):
            raise VerificationError(f"manifest value {value!r} is not under {expected_node}")
        indentation = len(lines[node_index]) - len(lines[node_index].lstrip())
        end = node_index + 1
        while end < len(lines):
            stripped = lines[end].lstrip()
            current_indentation = len(lines[end]) - len(stripped)
            if stripped.startswith("E: ") and current_indentation <= indentation:
                break
            end += 1
        return "\n".join(lines[node_index:end])
    raise VerificationError(f"manifest missing value {value!r}")


def assert_metadata_false(text: str, name: str) -> None:
    node = manifest_node_for_value(text, name, "meta-data")
    if not re.search(r"android:value[^\n]*(?:0x0\b|=false\b)", node):
        raise VerificationError(f"manifest metadata {name!r} is not false")


def verify(
    target_apk: Path,
    test_apk: Path,
    public_like_apk: Path,
    dependency_report: Path,
) -> None:
    for apk in (target_apk, test_apk, public_like_apk):
        if not apk.is_file():
            raise VerificationError(f"APK missing: {apk}")
    aapt = find_build_tool("aapt")
    apksigner = find_build_tool("apksigner")

    target_badging = command(str(aapt), "dump", "badging", str(target_apk))
    assert_contains(target_badging, "package: name='com.jay.fxi'", "target badging")
    assert_contains(target_badging, "targetSdkVersion:'36'", "target badging")
    if "application-debuggable" in target_badging:
        raise VerificationError("target benchmark APK must be non-debuggable")
    target_manifest = command(str(aapt), "dump", "xmltree", str(target_apk), "AndroidManifest.xml")
    if not re.search(r"E: profileable.*?android:shell[^\n]*0xffffffff", target_manifest, re.S):
        raise VerificationError("target profileable android:shell is not true")
    assert_contains(target_manifest, "com.jay.fxi.benchmark.BenchmarkSmokeActivity", "target manifest")
    assert_contains(target_manifest, "com.jay.fxi.action.BENCHMARK_SMOKE", "target manifest")
    assert_contains(target_manifest, '="fxi-benchmark"', "target benchmark data scheme")
    for metadata_name in (
        "firebase_analytics_collection_enabled",
        "firebase_crashlytics_collection_enabled",
        "firebase_messaging_auto_init_enabled",
    ):
        assert_metadata_false(target_manifest, metadata_name)
    assert_contains(
        target_manifest,
        "androidx.profileinstaller.ProfileInstallerInitializer",
        "target manifest",
    )
    assert_contains(
        target_manifest,
        "androidx.profileinstaller.ProfileInstallReceiver",
        "target manifest",
    )
    signing = command(str(apksigner), "verify", "--print-certs", str(target_apk))
    assert_contains(signing, "CN=Android Debug", "target signer")

    test_badging = command(str(aapt), "dump", "badging", str(test_apk))
    assert_contains(test_badging, "package: name='com.jay.fxi.macrobenchmark'", "test badging")
    assert_contains(test_badging, "sdkVersion:'31'", "test badging")
    assert_contains(test_badging, "targetSdkVersion:'36'", "test badging")
    assert_contains(test_badging, "application-debuggable", "test badging")
    test_manifest = command(str(aapt), "dump", "xmltree", str(test_apk), "AndroidManifest.xml")
    assert_contains(test_manifest, 'android:name(0x01010003)="com.jay.fxi"', "test queries")
    assert_contains(test_manifest, "androidx.test.runner.AndroidJUnitRunner", "test runner")

    public_manifest = command(
        str(aapt), "dump", "xmltree", str(public_like_apk), "AndroidManifest.xml"
    )
    if "E: profileable" in public_manifest:
        raise VerificationError("non-benchmark public-like APK contains profileable")
    if "com.jay.fxi.action.BENCHMARK_SMOKE" in public_manifest or "fxi-benchmark" in public_manifest:
        raise VerificationError("non-benchmark public-like APK contains benchmark admission")

    try:
        dependencies = dependency_report.read_text(encoding="utf-8")
    except OSError as error:
        raise VerificationError(f"cannot read dependency report: {error}") from error
    if not re.search(r"^androidx\.profileinstaller:profileinstaller:1\.4\.1$", dependencies, re.M):
        raise VerificationError("benchmarkRuntimeClasspath did not resolve ProfileInstaller 1.4.1")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--public-like-apk", type=Path, required=True)
    parser.add_argument("--dependency-report", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        verify(args.target_apk, args.test_apk, args.public_like_apk, args.dependency_report)
    except VerificationError as error:
        print(f"benchmark artifact verification failed: {error}", file=sys.stderr)
        return 2
    print("benchmark artifact verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
