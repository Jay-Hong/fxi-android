#!/usr/bin/env python3
"""Verify the S5 fixture benchmark APKs (S5-4 4c) from what is packaged, and that the public-like ciMinified APK carries none
of the fixture. ciMinified is public-like evidence only; the real public AAB is proven in S11. Firebase identifiers are compared
with the checked-in non-production CI fixture and never printed."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Sequence

# The D24 marker rule has one owner: the admission verifier. Reused, not copied.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "release"))
from verify_release_admission_artifacts import parse_boolean_metadata, parse_marker  # noqa: E402


class VerificationError(RuntimeError):
    pass


PACKAGE = "com.jay.fxi"
TEST_PACKAGE = "com.jay.fxi.macrobenchmark"
FIREBASE_FLAGS = (
    "firebase_analytics_collection_enabled",
    "firebase_crashlytics_collection_enabled",
    "firebase_messaging_auto_init_enabled",
)
# The four frozen S5-4 A fixtures (TetherBenchmarkFixtureTest BF1) and the approved USD asset (S5UsdFixtureAssetTest).
FIXTURE_ASSETS = {
    "catalog.json": "b18f0798f972f44a6d45f3610f66e43dddc400cc9166d60c6d7fe90b474ebf62",
    "tether-1d.json": "1982e740c5adb5bc2b470ea256f75051d2581e2eeaa6bf42495cbaa336af8e34",
    "tether-1w.json": "653ee04152a045bac1ab9e3ec2ed3a9343fd6b6e5cff832d388cb9f55bb132c8",
    "bundle.json": "8fff6bcd1b301c7d7bcc25bc8d64529e34a120fddb1ad85fe217e37a13eb5333",
    "usd-1d.json": "9321ca91305504129167d8c2397cfe367925b367761338e8bfc2d51d27fbae61",
}
FIXTURE_SOURCE_ROOTS = ("app/src/s5Benchmark", "app/src/benchmark/assets/s5_4")
FIXTURE_NAMES = ("com.jay.fxi.s5fixture.", "okhttp3.mockwebserver.")
FIXTURE_DESCRIPTORS = ("Lcom/jay/fxi/s5fixture/", "Lokhttp3/mockwebserver/")
KB_IMAGE_DOMAIN = "newsimage.einfomax.co.kr"


def command(*arguments: str) -> str:
    result = subprocess.run(arguments, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise VerificationError(f"command failed ({Path(arguments[0]).name} {arguments[1] if len(arguments) > 1 else ''})")
    return result.stdout


# --- pure checks ---------------------------------------------------------------------------------------------------------


def expected_firebase(fixture_json: str) -> dict[str, str]:
    data = json.loads(fixture_json)
    clients = [c for c in data.get("client", []) if c.get("client_info", {}).get("android_client_info", {}).get("package_name") == PACKAGE]
    if len(clients) != 1:
        raise VerificationError(f"CI Firebase fixture must have exactly one {PACKAGE} client")
    return {
        "google_app_id": clients[0]["client_info"]["mobilesdk_app_id"],
        "project_id": data["project_info"]["project_id"],
        "gcm_defaultSenderId": data["project_info"]["project_number"],
    }


def resource_strings(dump: str, names: Sequence[str]) -> dict[str, str]:
    lines = dump.splitlines()
    found: dict[str, list[str]] = {name: [] for name in names}
    for index, line in enumerate(lines):
        match = re.match(r"\s*resource 0x[0-9a-f]+ string/(\S+)\s*$", line)
        if not match or match.group(1) not in found:
            continue
        value = re.match(r'\s*\(\) "(.*)"\s*$', lines[index + 1]) if index + 1 < len(lines) else None
        if value is None:
            raise VerificationError(f"string resource {match.group(1)} has no single default value")
        found[match.group(1)].append(value.group(1))
    for name, values in found.items():
        if len(values) != 1:
            raise VerificationError(f"expected exactly one string resource {name}, found {len(values)}")
    return {name: values[0] for name, values in found.items()}


def check_firebase(dump: str, expected: dict[str, str]) -> None:
    actual = resource_strings(dump, list(expected))
    for name in expected:
        if actual[name] != expected[name]:
            raise VerificationError(f"packaged {name} is not the CI fixture's")


def network_config_reference(manifest: str) -> str:
    refs = re.findall(r"networkSecurityConfig\(0x01010527\)=@(0x[0-9a-f]+)", manifest)
    if len(refs) != 1:
        raise VerificationError(f"expected one networkSecurityConfig reference, found {len(refs)}")
    return refs[0]


def resource_file(dump: str, resource_id: str) -> str:
    lines = dump.splitlines()
    for index, line in enumerate(lines):
        if re.match(rf"\s*resource {resource_id} \S+\s*$", line):
            files = []
            for following in lines[index + 1:]:
                if re.match(r"\s*resource 0x", following):
                    break
                match = re.search(r"\(file\) (\S+)", following)
                if match:
                    files.append(match.group(1))
            if len(files) != 1:
                raise VerificationError(f"resource {resource_id} must name exactly one file, found {len(files)}")
            return files[0]
    raise VerificationError(f"resource {resource_id} not in the resource table")


def parse_network_config(xmltree: str) -> dict:
    """Base cleartext, each domain-config's cleartext and domains, and whether anything else is present."""
    lines = [line for line in xmltree.splitlines() if line.strip()]
    config: dict = {"base": None, "domain_configs": [], "other": []}
    current = None
    in_base = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("E: "):
            element = stripped[3:].split(" ", 1)[0]
            in_base = element == "base-config"
            if element == "network-security-config":
                continue
            if element == "base-config":
                continue
            if element == "domain-config":
                current = {"cleartext": None, "domains": []}
                config["domain_configs"].append(current)
                continue
            if element == "domain" and current is not None:
                current["domains"].append({"name": None, "subdomains": None})
                continue
            config["other"].append(element)
            continue
        match = re.match(r"A: (?:\S*:)?(\w+)=(\S+)", stripped)
        if match:
            name, value = match.group(1), match.group(2)
            if name == "cleartextTrafficPermitted":
                if in_base:
                    config["base"] = value
                elif current is not None:
                    current["cleartext"] = value
            elif name == "includeSubdomains" and current is not None and current["domains"]:
                current["domains"][-1]["subdomains"] = value
            continue
        text = re.match(r"T: '(.*)'", stripped)
        if text and current is not None and current["domains"]:
            current["domains"][-1]["name"] = text.group(1)
    return config


def check_network(config: dict, allowed: dict[str, str], label: str) -> None:
    """allowed: the only cleartext domain and its includeSubdomains value."""
    if config["base"] != "false":
        raise VerificationError(f"{label}: base-config cleartext is not false")
    if config["other"]:
        raise VerificationError(f"{label}: unexpected network config element(s) {sorted(set(config['other']))}")
    if len(config["domain_configs"]) != 1:
        raise VerificationError(f"{label}: expected one domain-config, found {len(config['domain_configs'])}")
    only = config["domain_configs"][0]
    if only["cleartext"] != "true":
        raise VerificationError(f"{label}: the domain-config does not allow cleartext")
    domains = {(d["name"], d["subdomains"]) for d in only["domains"]}
    if len(only["domains"]) != 1 or domains != set(allowed.items()):
        raise VerificationError(f"{label}: cleartext domains are not exactly {sorted(allowed)}")


def check_assets(apk: zipfile.ZipFile, label: str) -> None:
    for name, digest in FIXTURE_ASSETS.items():
        try:
            data = apk.read("assets/" + name)
        except KeyError:
            raise VerificationError(f"{label}: asset {name} missing") from None
        if hashlib.sha256(data).hexdigest() != digest:
            raise VerificationError(f"{label}: asset {name} bytes are not the approved fixture")


def check_no_assets(apk: zipfile.ZipFile, label: str) -> None:
    leaked = sorted(name for name in FIXTURE_ASSETS if "assets/" + name in apk.namelist())
    if leaked:
        raise VerificationError(f"{label}: fixture asset(s) packaged: {leaked}")


def check_dependency_resolved(report: str, coordinate: str, version: str, label: str) -> None:
    if not re.search(rf"^{re.escape(coordinate)}:{re.escape(version)}$", report, re.M):
        raise VerificationError(f"{label}: {coordinate} did not resolve {version}")


def check_dependency_absent(report: str, coordinate: str, label: str) -> None:
    if re.search(rf"^{re.escape(coordinate)}:", report, re.M) or "No dependencies matching given input were found" not in report:
        raise VerificationError(f"{label}: {coordinate} is on the runtime classpath (or the report is not a no-match report)")


SOURCE_LIST_HEADER = "project=:app variant=ciMinified"
SOURCE_KINDS = ("Java sources", "Kotlin sources", "Assets")


def source_sets(report: str) -> dict[str, dict[str, list[str]]]:
    """The AGP `sourceSets` report: per source set, the listed roots of each kind."""
    sets: dict[str, dict[str, list[str]]] = {}
    current = None
    lines = report.splitlines()
    for index, line in enumerate(lines):
        if index + 1 < len(lines) and re.fullmatch(r"-+", lines[index + 1]) and line and not line.startswith(" "):
            current = sets.setdefault(line.strip(), {})
            continue
        match = re.match(r"([A-Za-z -]+): \[(.*)\]$", line)
        if current is not None and match:
            current[match.group(1)] = [p.strip() for p in match.group(2).split(",") if p.strip()]
    return sets


def ci_minified_roots(report: str) -> list[str]:
    sets = source_sets(report)
    for name in ("main", "ciMinified"):
        if name not in sets:
            raise VerificationError(f"source report has no {name} source set")
    return [root for name in ("main", "ciMinified") for kind in SOURCE_KINDS for root in sets[name].get(kind, [])]


def _collect(path: Path, repo_root: Path, lines: list[str], ancestors: frozenset[str]) -> None:
    rel = os.path.relpath(path, repo_root)
    if path.is_symlink():
        lines.append("file\t" + rel)  # the link itself; its canonical path is checked
        if not path.exists():
            raise VerificationError(f"broken symlink in a ciMinified source root: {rel}")
    if path.is_dir():
        real = os.path.realpath(path)
        if real in ancestors:
            raise VerificationError(f"symlink cycle in a ciMinified source root: {rel}")
        try:
            with os.scandir(path) as listing:
                entries = sorted(listing, key=lambda entry: entry.name)
        except OSError as error:
            raise VerificationError(f"cannot read ciMinified source directory {rel}: {error.strerror}") from error
        for entry in entries:
            _collect(Path(entry.path), repo_root, lines, ancestors | {real})
    elif not path.is_symlink():
        lines.append("file\t" + rel)


def collect_source_files(report: str, repo_root: Path) -> str:
    """Every regular file and symlink under each ciMinified root, by a recursive os.scandir that also walks into directory
    symlinks; each link is listed itself too. A cycle, a broken link or an unreadable directory fails the collection."""
    lines = [SOURCE_LIST_HEADER]
    for root in ci_minified_roots(report):
        lines.append(f"root\t{root}")
        top = repo_root / root
        if os.path.lexists(top):
            _collect(top, repo_root, lines, frozenset())
    return "\n".join(lines) + "\n"


def _under_fixture(path: str, repo_root: Path) -> bool:
    real = os.path.realpath(repo_root / path)
    roots = [os.path.realpath(repo_root / root) for root in FIXTURE_SOURCE_ROOTS]
    return any(real == root or real.startswith(root + os.sep) for root in roots)


def check_source_inputs(report: str, file_list: str, repo_root: Path) -> None:
    roots = ci_minified_roots(report)
    lines = file_list.splitlines()
    if not lines or lines[0] != SOURCE_LIST_HEADER:
        raise VerificationError("source file list is missing or not for :app ciMinified")
    entries = [line.split("\t", 1) for line in lines[1:] if line]
    if any(len(entry) != 2 or entry[0] not in ("root", "file") for entry in entries):
        raise VerificationError("source file list has a malformed line")
    listed_roots = [path for kind, path in entries if kind == "root"]
    files = [path for kind, path in entries if kind == "file"]
    if listed_roots != roots:
        raise VerificationError("source file list roots are not the source report's ciMinified roots")
    if not files:
        raise VerificationError("source file list has no files")
    for path in roots + files:
        if _under_fixture(path, repo_root):
            raise VerificationError(f"ciMinified source input reaches the fixture: {path}")


def check_mapping(mapping: str) -> None:
    for line in mapping.splitlines():
        if line.startswith(FIXTURE_NAMES):
            raise VerificationError("ciMinified mapping names a fixture class")


def check_dex_classes(dexdump: str) -> None:
    if any(descriptor in dexdump for descriptor in FIXTURE_DESCRIPTORS):
        raise VerificationError("ciMinified DEX contains a fixture class")


def check_target_package_constant(source: str) -> None:
    if not re.search(r'TARGET_PACKAGE\s*=\s*"com\.jay\.fxi"', source):
        raise VerificationError("TetherGraphBenchmark TARGET_PACKAGE is not com.jay.fxi")


def xml_elements(xmltree: str) -> list[dict]:
    """aapt2 xmltree as elements: name, parent index and the element's own attributes (local name -> value)."""
    elements: list[dict] = []
    stack: list[tuple[int, int]] = []
    for line in xmltree.splitlines():
        stripped = line.lstrip()
        indent = len(line) - len(stripped)
        if stripped.startswith("E: "):
            while stack and stack[-1][0] >= indent:
                stack.pop()
            elements.append({"name": stripped[3:].split(" ", 1)[0], "parent": stack[-1][1] if stack else None, "attrs": {}})
            stack.append((indent, len(elements) - 1))
        elif stripped.startswith("A: "):
            owners = [index for level, index in stack if level < indent]
            match = re.match(r"A: (?:\S*?:)?(\w+)(?:\(0x[0-9a-f]+\))?=(.*)$", stripped)
            if owners and match:
                raw = match.group(2)
                quoted = re.match(r'"([^"]*)"', raw)
                elements[owners[-1]]["attrs"][match.group(1)] = quoted.group(1) if quoted else raw.split(" ", 1)[0]
    return elements


def check_target_manifest(badging: str, manifest: str, signing: str) -> None:
    if f"package: name='{PACKAGE}'" not in badging:
        raise VerificationError("s5Benchmark target package is not com.jay.fxi")
    if "application-debuggable" in badging:
        raise VerificationError("s5Benchmark target is debuggable")
    if not parse_marker(manifest):
        raise VerificationError("s5Benchmark target D24 marker is not ON")
    for flag in FIREBASE_FLAGS:
        if parse_boolean_metadata(manifest, flag):
            raise VerificationError(f"s5Benchmark target {flag} is not false")
    profileable = [e for e in xml_elements(manifest) if e["name"] == "profileable"]
    if len(profileable) != 1 or profileable[0]["attrs"].get("shell") != "true":
        raise VerificationError("s5Benchmark target profileable android:shell is not true")
    if "CN=Android Debug" not in signing:
        raise VerificationError("s5Benchmark target is not debug-signed")


def check_test_manifest(badging: str, manifest: str) -> None:
    if f"package: name='{TEST_PACKAGE}'" not in badging:
        raise VerificationError("s5Benchmark test APK package is not com.jay.fxi.macrobenchmark")
    elements = xml_elements(manifest)
    instrumentation = [e for e in elements if e["name"] == "instrumentation"]
    if len(instrumentation) != 1:
        raise VerificationError(f"s5Benchmark test APK must declare one instrumentation, found {len(instrumentation)}")
    if instrumentation[0]["attrs"].get("name") != "androidx.test.runner.AndroidJUnitRunner":
        raise VerificationError("s5Benchmark test APK runner is not AndroidJUnitRunner")
    if instrumentation[0]["attrs"].get("targetPackage") != TEST_PACKAGE:
        raise VerificationError("s5Benchmark test APK is not self-instrumenting")
    queries = {index for index, e in enumerate(elements) if e["name"] == "queries"}
    queried = [e["attrs"].get("name") for e in elements if e["name"] == "package" and e["parent"] in queries]
    if PACKAGE not in queried:
        raise VerificationError("s5Benchmark test APK does not query com.jay.fxi")


# --- orchestration --------------------------------------------------------------------------------------------------------


def dex_dump(dexdump: Path, apk_path: Path) -> str:
    out = []
    with zipfile.ZipFile(apk_path) as apk, tempfile.TemporaryDirectory() as tmp:
        names = [n for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        if not names:
            raise VerificationError("ciMinified APK has no DEX")
        for name in names:
            path = Path(tmp) / name
            path.write_bytes(apk.read(name))
            # dexdump prints string constants verbatim, not always UTF-8; class descriptors are ASCII.
            result = subprocess.run([str(dexdump), str(path)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if result.returncode != 0:
                raise VerificationError(f"dexdump failed on {name}")
            out.append(result.stdout.decode("utf-8", errors="replace"))
    return "\n".join(out)


def network_config(aapt2: Path, apk: Path, manifest: str, resources: str, run=command) -> dict:
    path = resource_file(resources, network_config_reference(manifest))
    return parse_network_config(run(str(aapt2), "dump", "xmltree", "--file", path, str(apk)))


def verify(args: argparse.Namespace, run=command, dex=None) -> None:
    """run(*arguments) -> stdout and dex(dexdump, apk) -> text are the external tools; tests pass their own."""
    dex = dex or dex_dump
    aapt2, apksigner, dexdump = Path(args.aapt2), Path(args.apksigner), Path(args.dexdump)
    repo = Path(args.repo_root)
    for path in (args.s5_target_apk, args.s5_test_apk, args.ci_minified_apk, args.ci_minified_mapping, args.firebase_fixture,
                 args.s5_dependency_report, args.ci_minified_dependency_report, args.ci_minified_source_report,
                 args.ci_minified_source_files, args.benchmark_source):
        if not Path(path).is_file():
            raise VerificationError(f"input missing: {path}")

    target = Path(args.s5_target_apk)
    manifest = run(str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(target))
    check_target_manifest(run(str(aapt2), "dump", "badging", str(target)), manifest,
                          run(str(apksigner), "verify", "--print-certs", str(target)))
    resources = run(str(aapt2), "dump", "resources", str(target))
    check_firebase(resources, expected_firebase(Path(args.firebase_fixture).read_text(encoding="utf-8")))
    check_network(network_config(aapt2, target, manifest, resources, run), {"127.0.0.1": "false"}, "s5Benchmark")
    with zipfile.ZipFile(target) as apk:
        check_assets(apk, "s5Benchmark")
    check_dependency_resolved(Path(args.s5_dependency_report).read_text(encoding="utf-8"),
                              "androidx.profileinstaller:profileinstaller", "1.4.1", "s5BenchmarkRuntimeClasspath")

    test = Path(args.s5_test_apk)
    check_test_manifest(run(str(aapt2), "dump", "badging", str(test)),
                        run(str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(test)))
    check_target_package_constant(Path(args.benchmark_source).read_text(encoding="utf-8"))

    public = Path(args.ci_minified_apk)
    public_manifest = run(str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(public))
    public_resources = run(str(aapt2), "dump", "resources", str(public))
    check_network(network_config(aapt2, public, public_manifest, public_resources, run), {KB_IMAGE_DOMAIN: "true"}, "ciMinified")
    with zipfile.ZipFile(public) as apk:
        check_no_assets(apk, "ciMinified")
    check_dependency_absent(Path(args.ci_minified_dependency_report).read_text(encoding="utf-8"),
                            "com.squareup.okhttp3:mockwebserver", "ciMinifiedRuntimeClasspath")
    check_source_inputs(Path(args.ci_minified_source_report).read_text(encoding="utf-8"),
                        Path(args.ci_minified_source_files).read_text(encoding="utf-8"), repo)
    check_mapping(Path(args.ci_minified_mapping).read_text(encoding="utf-8"))
    check_dex_classes(dex(dexdump, public))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    collect = commands.add_parser("collect-source-files", help="list the ciMinified source files from the sourceSets report")
    collect.add_argument("--repo-root", required=True)
    collect.add_argument("--ci-minified-source-report", required=True)
    collect.add_argument("--out", required=True)
    check = commands.add_parser("verify", help="verify the packaged S5 and ciMinified artifacts")
    for name in ("--aapt2", "--apksigner", "--dexdump", "--repo-root", "--s5-target-apk", "--s5-test-apk", "--ci-minified-apk",
                 "--ci-minified-mapping", "--firebase-fixture", "--s5-dependency-report", "--ci-minified-dependency-report",
                 "--ci-minified-source-report", "--ci-minified-source-files", "--benchmark-source"):
        check.add_argument(name, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "collect-source-files":
            listing = collect_source_files(Path(args.ci_minified_source_report).read_text(encoding="utf-8"), Path(args.repo_root))
            Path(args.out).write_text(listing, encoding="utf-8")
            print(f"ciMinified source file list written ({listing.count(chr(10) + 'file' + chr(9))} files)")
            return 0
        verify(args)
    except (VerificationError, OSError, KeyError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"S5 benchmark artifact verification failed: {error}", file=sys.stderr)
        return 2
    print("S5 benchmark artifact verification passed (ciMinified is public-like evidence; the public AAB is S11)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
