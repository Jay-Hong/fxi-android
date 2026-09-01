#!/usr/bin/env python3
"""Verify D24 admission from the binary manifest of packaged Android APKs."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys


MARKER = "com.jay.fxi.TOPIC_V2_RELEASE_ON"
BOOLEAN_VALUE = re.compile(r"android:value\(0x01010024\)=(true|false)\s*$")


class VerificationError(ValueError):
    pass


def parse_marker(xmltree: str) -> bool:
    lines = xmltree.splitlines()
    name_lines = [index for index, line in enumerate(lines) if MARKER in line]
    if len(name_lines) != 1:
        raise VerificationError(
            f"expected exactly one {MARKER} marker, found {len(name_lines)}"
        )

    name_line = name_lines[0]
    name_indent = len(lines[name_line]) - len(lines[name_line].lstrip())
    values: list[bool] = []
    for line in lines[name_line + 1 :]:
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        if indent < name_indent:
            break
        match = BOOLEAN_VALUE.search(line)
        if match:
            values.append(match.group(1) == "true")
    if len(values) != 1:
        raise VerificationError(
            "marker must have exactly one direct binary-boolean android:value attribute"
        )
    return values[0]


def dump_manifest(aapt2: Path, apk: Path) -> str:
    if not aapt2.is_file():
        raise VerificationError(f"aapt2 not found: {aapt2}")
    if not apk.is_file():
        raise VerificationError(f"APK not found: {apk}")
    result = subprocess.run(
        [str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise VerificationError(f"aapt2 failed for {apk}: {detail}")
    return result.stdout


def parse_artifact(spec: str) -> tuple[str, bool, Path]:
    parts = spec.split("=", 2)
    if len(parts) != 3 or parts[1] not in {"on", "off"} or not parts[0]:
        raise VerificationError(
            f"invalid --artifact {spec!r}; expected NAME=on|off=APK_PATH"
        )
    return parts[0], parts[1] == "on", Path(parts[2])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aapt2", required=True, type=Path)
    parser.add_argument("--artifact", action="append", required=True)
    args = parser.parse_args(argv)

    seen: set[str] = set()
    for raw in args.artifact:
        name, expected, apk = parse_artifact(raw)
        if name in seen:
            raise VerificationError(f"duplicate artifact name: {name}")
        seen.add(name)
        actual = parse_marker(dump_manifest(args.aapt2, apk))
        if actual != expected:
            raise VerificationError(
                f"{name}: expected admission {'ON' if expected else 'OFF'}, "
                f"packaged marker is {'ON' if actual else 'OFF'}"
            )
        print(f"{name}: admission {'ON' if actual else 'OFF'} ({apk})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        print(f"release admission verification failed: {error}", file=sys.stderr)
        raise SystemExit(2)
