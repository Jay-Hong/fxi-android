#!/usr/bin/env python3
"""Strict S5/S11 Macrobenchmark gate and evidence-manifest validator.

The tool intentionally has no third-party dependencies so hosted Android CI can test it
without the server repository or a Python environment beyond the standard library.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shlex
import statistics
import sys
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Sequence


SCHEMA_VERSION = 1
S5_P95_LIMIT_MS = 0.0
S5_P99_LIMIT_MS = 8.33
EXPECTED_VALID_RUNS = 3
EXPECTED_ITERATIONS = 5
EXPECTED_WARMUPS = 3
EXPECTED_JOURNEY_IDS = {"tab-entry", "period-switch", "scroll"}
EXPECTED_BENCHMARK_IDENTITIES = {
    "tab-entry": ("com.jay.fxi.macrobenchmark.TetherGraphBenchmark", "tabEntry"),
    "period-switch": ("com.jay.fxi.macrobenchmark.TetherGraphBenchmark", "periodSwitch"),
    "scroll": ("com.jay.fxi.macrobenchmark.TetherGraphBenchmark", "scroll"),
}
REQUIRED_FIXTURE_CASE_IDS = {
    "s5-exact-boundary-pass",
    "s5-p95-epsilon-fail",
    "s5-p99-epsilon-fail",
    "missing-p95",
    "missing-p99",
    "missing-frame-count",
    "s11-floor-equality-pass",
    "s11-floor-epsilon-fail",
    "s11-interior-equality-pass",
    "s11-cap-equality-pass",
    "s11-cap-epsilon-fail",
}
FIXTURE_NUMERIC_CONTRACT = {
    "s5-exact-boundary-pass": ("s5", None, 0.0, 8.33, "pass"),
    "s5-p95-epsilon-fail": ("s5", None, 0.001, 1.0, "fail"),
    "s5-p99-epsilon-fail": ("s5", None, 0.0, 8.331, "fail"),
    "s11-floor-equality-pass": ("s11", -3.0, 0.0, 0.0, "pass"),
    "s11-floor-epsilon-fail": ("s11", -3.0, 0.0, 0.001, "fail"),
    "s11-interior-equality-pass": ("s11", 2.0, 0.0, 4.0, "pass"),
    "s11-cap-equality-pass": ("s11", 7.0, 0.0, 8.33, "pass"),
    "s11-cap-epsilon-fail": ("s11", 7.0, 0.0, 8.331, "fail"),
}
FIXTURE_MISSING_FIELD_CONTRACT = {
    "missing-p95": "p95Ms",
    "missing-p99": "p99Ms",
    "missing-frame-count": "frameCount",
}
EXPECTED_LIBRARIES = {
    "agp": "8.13.2",
    "benchmark": "1.4.1",
    "profileInstaller": "1.4.1",
    "uiautomator": "2.4.0",
}
EXPECTED_MEASUREMENT_CONTRACT = {
    "model": "SM-F711N",
    "fingerprint": (
        "samsung/b2qksx/b2q:15/AP3A.240905.015.A2/"
        "F711NKSSFKZH5:user/release-keys"
    ),
    "api": 35,
    "posture": "OPENED",
    "display": "1080x2640@120Hz",
    "animationScale": 1.0,
    "lowPower": False,
    "charging": False,
    "batteryMin": 30,
    "thermalRequired": 0,
    "compilationMode": "Partial(BaselineProfileMode.Disable,warmupIterations=3)",
    "startupMode": "WARM",
    "repeatIterations": 5,
    "validRuns": 3,
    "maxAttempts": 5,
}
ARTIFACT_KEYS = {
    "fixturePayload",
    "harnessResolvedGraph",
    "macrobenchmarkTestApk",
    "parser",
    "productionResolvedGraph",
    "r8RulesCanonicalManifest",
    "targetBenchmarkApk",
}
S11_PROTECTED_ARTIFACT_KEYS = {
    "fixturePayload",
    "harnessResolvedGraph",
    "macrobenchmarkTestApk",
    "parser",
}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
RUN_ID_RE = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")

# S5-4 B: the behavior evidence each valid run carries (plan :1538-1539 - each tag's UIAutomator lookup exactly once, the
# expected screen state and the action completed). The harness records it; this parser only checks the document. It cannot
# prove that the observations themselves are true, nor that a behavior failure was not recorded under another reason.
BEHAVIOR_SCHEMA_VERSION = 1
CHART_TAG = "graph_v2:inline:chart"
PAGE_SCROLL_TAG = "premium_page_scroll"
BEHAVIOR_STATE_KEYS = {"tab", "period", "ready", "seriesShown", "scroll"}
BEHAVIOR_SCROLL_VALUES = {"top", "moved", "na"}
BEHAVIOR_ACTIONS = {"completed", "failed", "none"}
BEHAVIOR_PHASES = ["warmup"] * EXPECTED_WARMUPS + ["measured"] * EXPECTED_ITERATIONS
BEHAVIOR_INDICES = list(range(EXPECTED_WARMUPS)) + list(range(EXPECTED_ITERATIONS))
_TETHER_ONE_DAY = {"tab": "TETHER", "period": "1d", "ready": True, "seriesShown": 11}
# Per journey: the selectors looked up, the state values compared before and after, and the scroll observed in between
# (None: no "during" entry). The tab-entry start compares only its tab - the usd screen's data is not part of the fixture.
EXPECTED_BEHAVIOR: dict[str, dict[str, Any]] = {
    "tab-entry": {
        "lookups": {"premium_tab_TETHER", CHART_TAG},
        "before": {"tab": "USD"},
        "after": {**_TETHER_ONE_DAY, "scroll": "na"},
        "during": None,
    },
    "period-switch": {
        "lookups": {"period_tab:1d", CHART_TAG},
        "before": {"tab": "TETHER", "period": "1w", "ready": True, "seriesShown": 5, "scroll": "na"},
        "after": {**_TETHER_ONE_DAY, "scroll": "na"},
        "during": None,
    },
    "scroll": {
        "lookups": {PAGE_SCROLL_TAG, CHART_TAG},
        "before": {**_TETHER_ONE_DAY, "scroll": "top"},
        "after": {**_TETHER_ONE_DAY, "scroll": "top"},
        "during": {"scroll": "moved"},
    },
}


class InvalidEvidence(ValueError):
    """Input is missing, malformed, ambiguous, or internally inconsistent."""


@dataclass(frozen=True)
class PurityResult:
    """D17 r4 §4.6. Incomplete results never assert an actual counter."""

    state: str
    defects: Sequence[Mapping[str, str]]
    instances: Sequence[Mapping[str, Any]]
    actual_count: int | None


@dataclass(frozen=True)
class PuritySummary:
    """Lossless D17 bridge decoding seam; state is IDLE, BUSY or POISON."""

    state: str
    runner_nonce: str
    lifetime_id: str
    instance_id: str
    counter: int


def decode_purity_summary(raw: bytes) -> PuritySummary:
    if not isinstance(raw, bytes) or len(raw) != 64 or raw[:8] != b"FXID17R1" or raw[8] != 1 or raw[9] > 2 or raw[10:12] != b"\0\0":
        raise InvalidEvidence("malformed D17 summary")
    counter = int.from_bytes(raw[60:], "big")
    if counter > 2147483647 or raw[28:44] == bytes(16) or raw[44:60] == bytes(16):
        raise InvalidEvidence("invalid D17 summary identity/counter")
    return PuritySummary(("IDLE", "BUSY", "POISON")[raw[9]], raw[12:28].hex(), raw[28:44].hex(), raw[44:60].hex(), counter)


PURITY_ROLES = (
    "boot-before", "device-api", "install", "install-identity", "install-clock", "absent", "context-push",
    "context-readback", "apk-before-target", "apk-before-test", "instrumentation", "apk-after-target", "apk-after-test",
    "stop-clock", "force-stop", "live-after-stop", "inventory-pre", "pull", "inventory-post", "exit-query",
    "after-stop-clock", "boot-after", "install-identity-after", "live-final",
)
PURITY_ROOT = "/sdcard/Android/data/com.jay.fxi/files/s5purity/"
PURITY_PROBE = "com.jay.fxi.s5fixture.S5PurityProbe"
PURITY_REG_KEYS = set("kind schema instanceId lifetimeId attemptId runnerNonce attemptState pid processName processStartWallMs userId packageUid bootId".split())


def _purity_int(value: Any, maximum: int = 2147483647, minimum: int = 0) -> bool:
    return type(value) is int and minimum <= value <= maximum


def _purity_id(value: Any) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{32}", value) is not None and value != "0" * 32


def _purity_ref(ref: Any, root: Path) -> Path:
    path = validate_artifact_ref(ref, root, "purity input")
    relative = Path(ref["path"])
    if relative.is_absolute() or relative.as_posix() != ref["path"] or any(p in (".", "..") for p in relative.parts):
        raise InvalidEvidence("noncanonical purity ref")
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise InvalidEvidence("symlink purity input")
    if path.stat().st_nlink != 1:
        raise InvalidEvidence("aliased purity input")
    return path


def _purity_json_bytes(raw: bytes) -> Any:
    if not raw.endswith(b"\n") or raw.endswith(b"\n\n"):
        raise InvalidEvidence("JSON must end in exactly one LF")
    return json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)


def _purity_lines(raw: bytes) -> list[dict[str, Any]]:
    if not raw or not raw.endswith(b"\n"):
        raise InvalidEvidence("empty or truncated JSONL")
    rows = [_purity_json_bytes(line + b"\n") for line in raw[:-1].split(b"\n")]
    if any(not isinstance(row, dict) for row in rows):
        raise InvalidEvidence("JSONL object required")
    return rows


def _purity_context_valid(context: Any) -> bool:
    context_keys = set("schema attemptId runnerNonce bootId targetApkSha256 testApkSha256".split())
    return (isinstance(context, dict) and set(context) == context_keys and type(context.get("schema")) is int and context.get("schema") == 2
        and isinstance(context.get("attemptId"), str) and RUN_ID_RE.fullmatch(context["attemptId"]) and _purity_id(context.get("runnerNonce"))
        and isinstance(context.get("bootId"), str) and re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", context["bootId"])
        and all(isinstance(context.get(k), str) and SHA256_RE.fullmatch(context[k]) for k in ("targetApkSha256", "testApkSha256")))


def _purity_registration_valid(row: Mapping[str, Any]) -> bool:
    # Null attribution is a producer diagnostic, rejected by C4 rather than C2.
    return (set(row) == PURITY_REG_KEYS and type(row.get("schema")) is int and row.get("schema") == 2
        and all(isinstance(row.get(k), str) for k in ("kind", "instanceId", "lifetimeId", "processName", "attemptState"))
        and all(row.get(k) is None or isinstance(row.get(k), str) for k in ("attemptId", "runnerNonce", "bootId"))
        and all(_purity_int(row.get(k)) for k in ("pid", "userId", "packageUid"))
        and _purity_int(row.get("processStartWallMs"), 2**63-1))


def read_purity_proof(proof_ref: Mapping[str, Any], artifact_root: Path,
                      expected_attempt: Mapping[str, Any], expected_apks: Mapping[str, str]) -> PurityResult:
    try:
        proof = _purity_json_bytes(_purity_ref(proof_ref, artifact_root).read_bytes())
    except (ValueError, OSError, UnicodeError, TypeError, KeyError):
        return PurityResult("incomplete", [{"check": "C2", "code": "proof"}], [], None)
    return _read_purity_inputs(proof, artifact_root, expected_attempt, expected_apks)


def _read_purity_inputs(proof: Any, root: Path, expected: Mapping[str, Any], apks: Mapping[str, str], *,
                        compare_result: bool = True) -> PurityResult:
    """One evaluator for collector, ledger and standalone; claimed results never supply expectations."""
    defects: list[dict[str, str]] = []
    instances: list[dict[str, Any]] = []
    def bad(check, code):
        item = {"check": check, "code": code}
        if item not in defects:
            defects.append(item)
    def need(condition, check, code):
        if not condition:
            bad(check, code)
    def raw(ref):
        return _purity_ref(ref, root).read_bytes()
    def document(ref):
        return _purity_json_bytes(raw(ref))
    def attempt(check, code, block):
        try:
            return block()
        except (ValueError, OSError, UnicodeError, TypeError, KeyError, IndexError, AttributeError, OverflowError):
            bad(check, code)
            return None
    if not isinstance(proof, dict) or set(proof) != set("schema attemptId runnerNonce context apks observations commands deviceFiles instances result".split()) or type(proof.get("schema")) is not int or proof.get("schema") != 2:
        return PurityResult("incomplete", [{"check": "C2", "code": "proof"}], [], None)
    context = attempt("C1", "context", lambda: document(proof["context"]))
    valid_context = _purity_context_valid(context)
    need(valid_context, "C1", "context")
    context = context if isinstance(context, dict) else {}
    need(proof["attemptId"] == expected.get("attemptId") == context.get("attemptId") and proof["runnerNonce"] == context.get("runnerNonce")
         and ("runnerNonce" not in expected or proof["runnerNonce"] == expected["runnerNonce"])
         and ("context" not in expected or proof["context"] == expected["context"]), "C1", "context")
    need(proof["apks"] == apks == {"target": context.get("targetApkSha256"), "test": context.get("testApkSha256")}
         and set(apks) == {"target", "test"} and all(isinstance(v, str) and SHA256_RE.fullmatch(v) for v in apks.values()), "C1", "apk")
    commands = attempt("C8", "envelope", lambda: _purity_lines(raw(proof["commands"]))) or []
    pairs = {}
    previous = 0
    command_nonces = set()
    for index in range(0, len(commands), 2):
        def pair():
            nonlocal previous
            start, finish = commands[index:index + 2]
            assert_keys_start = set("kind schema attemptId runnerNonce seq op argv commandNonce monoNs".split())
            assert_keys_finish = set("kind schema attemptId runnerNonce seq monoNs returnCode timedOut stdout stderr".split())
            need(set(start) == assert_keys_start and set(finish) == assert_keys_finish and start.get("kind") == "start" and finish.get("kind") == "finish", "C8", "envelope")
            seq = index // 2 + 1
            need(all(type(e.get("schema")) is int and e.get("schema") == 2 and type(e.get("seq")) is int and e.get("seq") == seq for e in (start, finish)), "C8", "envelope")
            need(all(e.get("attemptId") == proof["attemptId"] and e.get("runnerNonce") == proof["runnerNonce"] for e in (start, finish)), "C8", "command-identity")
            need(_purity_int(start.get("monoNs"), 2**63-1) and _purity_int(finish.get("monoNs"), 2**63-1)
                 and previous <= start["monoNs"] <= finish["monoNs"], "C8", "order")
            previous = finish["monoNs"]
            out, err = raw(finish["stdout"]), raw(finish["stderr"])
            need(type(finish.get("timedOut")) is bool and not finish["timedOut"] and type(finish.get("returnCode")) is int and finish["returnCode"] == 0, "C8", "command-result")
            need(isinstance(start.get("argv"), list) and all(isinstance(a, str) for a in start["argv"]) and isinstance(start.get("commandNonce"), str) and re.fullmatch(r"[A-Za-z0-9._-]{1,128}", start["commandNonce"]) is not None, "C8", "command-identity")
            need(start["commandNonce"] not in command_nonces, "C8", "command-identity")
            command_nonces.add(start["commandNonce"])
            pairs[seq] = (start, finish, out)
        attempt("C8", "envelope", pair)
    observations = proof["observations"]
    by_role: dict[str, list[Any]] = {}
    if not isinstance(observations, list):
        bad("C8", "roles")
        observations = []
    used_seqs = set()
    for observation in observations:
        def obs():
            need(set(observation) == {"role", "ref", "seqs"}, "C8", "roles")
            role, seqs = observation["role"], observation["seqs"]
            need(role in PURITY_ROLES and isinstance(seqs, list) and bool(seqs) and all(type(n) is int for n in seqs) and seqs == sorted(set(seqs)), "C8", "roles")
            need(not used_seqs.intersection(seqs), "C8", "roles")
            used_seqs.update(seqs)
            group = [pairs[n] for n in seqs]
            by_role.setdefault(role, []).append((observation, group, raw(observation["ref"])))
        attempt("C8", "roles", obs)
    for role in PURITY_ROLES:
        need(len(by_role.get(role, [])) >= 1 if role == "exit-query" else len(by_role.get(role, [])) == 1, "C8", "roles")
    need(used_seqs == set(pairs), "C8", "roles")
    def primary(role):
        return by_role[role][0]
    def output(role):
        return primary(role)[2]
    def argv(group):
        value = group[0]["argv"]
        # Record transport prefixes, but compare the exact remote operation.
        if value and Path(value[0]).name == "adb":
            value = value[1:]
            if value[:1] == ["-s"]:
                value = value[2:]
        if value[:1] == ["shell"] and len(value) == 2:
            value = ["shell", *shlex.split(value[1])]
        return value
    def exact(role, expected_argv):
        groups = primary(role)[1]
        need(len(groups) == 1 and argv(groups[0]) == expected_argv, "C8", "command-identity")
        need(groups[0][0]["op"] == role, "C8", "command-identity")
        need(primary(role)[0]["ref"] == groups[0][1]["stdout"], "C8", "command-identity")
    def api():
        exact("device-api", ["shell", "getprop", "ro.build.version.sdk"])
        value = output("device-api")
        need(re.fullmatch(rb"[1-9][0-9]*\n", value) is not None and int(value) >= 30, "C1", "api")
        group = primary("device-api")[1][0]
        need(group[1]["returnCode"] == 0 and group[1]["timedOut"] is False, "C1", "api")
        api_seq = primary("device-api")[0]["seqs"][-1]
        for role in ("install", "absent", "context-push", "instrumentation"):
            need(api_seq < primary(role)[0]["seqs"][0], "C1", "api")
    attempt("C1", "api", api)
    # Only exit-query may repeat; all phases remain ordered by actual finish sequences.
    def order():
        prev = 0
        for role in PURITY_ROLES:
            for item in by_role[role]:
                need(item[0]["seqs"][0] > prev, "C8", "order")
                prev = item[0]["seqs"][-1]
        queries = by_role["exit-query"]
        start_ns = queries[0][1][0][0]["monoNs"]
        end_ns = queries[-1][1][-1][1]["monoNs"]
        need(end_ns - start_ns <= 10_000_000_000, "C8", "query-budget")
    attempt("C8", "order", order)
    uid = user = None
    base_paths = {}
    def identity():
        nonlocal uid, user, base_paths
        parsed = []
        for role in ("install-identity", "install-identity-after"):
            _, groups, observed = primary(role)
            need(len(groups) == 4 and primary(role)[0]["ref"] == groups[0][1]["stdout"], "C8", "command-identity")
            expected_args = [["shell", "cmd", "package", "list", "packages", "-U", "com.jay.fxi"], ["shell", "am", "get-current-user"],
                             ["shell", "pm", "path", "com.jay.fxi"], ["shell", "pm", "path", "com.jay.fxi.macrobenchmark"]]
            need(all(argv(g) == a and g[0]["op"] == role for g, a in zip(groups, expected_args)), "C8", "command-identity")
            match = re.fullmatch(rb"package:com\.jay\.fxi uid:([0-9]+)\n", observed)
            if not match or not re.fullmatch(rb"(?:0|[1-9][0-9]*)\n", groups[1][2]):
                raise InvalidEvidence("identity format")
            paths = []
            for g in groups[2:]:
                m = re.fullmatch(rb"package:(/[^\r\n]+\.apk)\n", g[2])
                if not m:
                    raise InvalidEvidence("base APK format")
                paths.append(m[1].decode())
            parsed.append((int(match[1]), int(groups[1][2]), paths))
        need(parsed[0] == parsed[1], "C1", "boot-uid-clock")
        uid, user, paths = parsed[0]
        need(uid // 100000 == user and 10000 <= uid % 100000 <= 19999, "C1", "boot-uid-clock")
        base_paths = dict(zip(("target", "test"), paths))
    attempt("C1", "boot-uid-clock", identity)
    clocks = {}
    for role in ("install-clock", "stop-clock", "after-stop-clock"):
        def clock(role=role):
            exact(role, ["shell", "date", "+%s%3N"])
            value = output(role)
            need(re.fullmatch(rb"(?:0|[1-9][0-9]*)\n", value) is not None and _purity_int(int(value), 2**63-1), "C1", "boot-uid-clock")
            clocks[role] = int(value)
        attempt("C1", "boot-uid-clock", clock)
    need(clocks.get("install-clock", 1) <= clocks.get("stop-clock", 0) <= clocks.get("after-stop-clock", -1), "C1", "boot-uid-clock")
    for role in ("boot-before", "boot-after"):
        def boot(role=role):
            exact(role, ["shell", "cat", "/proc/sys/kernel/random/boot_id"])
            need(output(role) == (context["bootId"] + "\n").encode(), "C1", "boot-uid-clock")
        attempt("C1", "boot-uid-clock", boot)
    probe_payloads = {}
    for role in ("absent", "inventory-pre", "inventory-post", "exit-query"):
        for item in by_role.get(role, []):
            def probe(item=item, role=role):
                observation, groups, observed = item
                op = "inventory" if role.startswith("inventory-") else "exits" if role == "exit-query" else role
                need(len(groups) == 1 and observation["ref"] == groups[0][1]["stdout"], "C8", "command-identity")
                start = groups[0][0]
                need(not raw(groups[0][1]["stderr"]), "C8", "command-result")
                need(start["op"] == op and argv(groups[0]) == ["shell", "env", "CLASSPATH=" + base_paths["target"], "app_process", "/system/bin", PURITY_PROBE,
                    op, str(user), proof["attemptId"], proof["runnerNonce"], start["commandNonce"]], "C8", "command-identity")
                value = _purity_json_bytes(observed)
                need(set(value) == set("schema op attemptId runnerNonce commandNonce collectorUid userId payload".split())
                     and type(value["schema"]) is int and value["schema"] == 2 and value["op"] == op and value["attemptId"] == proof["attemptId"] and value["runnerNonce"] == proof["runnerNonce"]
                     and value["commandNonce"] == start["commandNonce"] and type(value["collectorUid"]) is int and value["collectorUid"] == 2000
                     and type(value["userId"]) is int and value["userId"] == user, "C8", "command-identity")
                probe_payloads.setdefault(role, []).append(value["payload"])
            attempt("C8", "command-identity", probe)
    def context_io():
        collected = proof["deviceFiles"]["root"] + "/attempt.json"
        need(raw(proof["context"]) == output("context-readback") == (root / collected).read_bytes(), "C1", "context")
        exact("context-readback", ["shell", "cat", PURITY_ROOT + "attempt.json"])
        group = primary("context-push")[1]
        need(len(group) in (1, 2) and primary("context-push")[0]["ref"] == group[0][1]["stdout"], "C8", "command-identity")
        if len(group) == 2:
            need(argv(group[0]) == ["shell", "mkdir", "-p", PURITY_ROOT] and group[0][0]["op"] == "context-push", "C8", "command-identity")
        args = argv(group[-1])
        source = Path(args[1])
        source = source if source.is_absolute() else root / source
        need(group[-1][0]["op"] == "context-push" and len(args) == 3 and args[0] == "push" and args[2] == PURITY_ROOT + "attempt.json"
             and source.resolve() == (root / proof["context"]["path"]).resolve(), "C8", "command-identity")
        need(probe_payloads["absent"] == [{"exists": False}] and type(probe_payloads["absent"][0]["exists"]) is bool, "C1", "context")
    attempt("C1", "context", context_io)
    def install():
        _, groups, observed = primary("install")
        need(len(groups) == 2 and all(g[0]["op"] == "install" and argv(g)[:2] == ["install", "--no-streaming"] and len(argv(g)) == 3 and g[2].splitlines().count(b"Success") == 1 for g in groups)
             and primary("install")[0]["ref"] == groups[0][1]["stdout"], "C8", "command-identity")
    attempt("C1", "apk", install)
    for role in ("apk-before-target", "apk-before-test", "apk-after-target", "apk-after-test"):
        def apk(role=role):
            key = "target" if role.endswith("target") else "test"
            record = _purity_json_bytes(output(role))
            package = "com.jay.fxi" if key == "target" else "com.jay.fxi.macrobenchmark"
            need(set(record) == set("schema package pathOnDevice expectedSha256 actualSha256 actualSize matched".split()) and type(record["schema"]) is int and record["schema"] == 2
                 and record["package"] == package and record["pathOnDevice"] == base_paths[key] and record["expectedSha256"] == record["actualSha256"] == apks[key]
                 and _purity_int(record["actualSize"], 2**63-1, 1) and record["matched"] is True, "C1", "apk")
            groups = primary(role)[1]
            need(len(groups) == 1 and groups[0][0]["op"] == role and argv(groups[0])[:2] == ["pull", base_paths[key]] and len(argv(groups[0])) == 3, "C8", "command-identity")
        attempt("C1", "apk", apk)
    def stopped():
        exact("force-stop", ["shell", "am", "force-stop", "--user", str(user), "com.jay.fxi"])
        for role in ("live-after-stop", "live-final"):
            exact(role, ["shell", "ps", "-A", "-o", "USER,PID,NAME"])
            lines = output(role).decode().splitlines()
            need(bool(lines) and lines[0].split() == ["USER", "PID", "NAME"], "C8", "command-result")
            for line in lines[1:]:
                owner, pid, name = line.split()
                if owner.isdecimal():
                    process_uid = int(owner)
                else:
                    m = re.fullmatch(r"u([0-9]+)_a([0-9]+)", owner)
                    # Only numeric and Android application UID names can represent the target UID.
                    if m:
                        process_uid = int(m[1])*100000 + 10000 + int(m[2])
                    elif re.fullmatch(r"[a-z_][a-z0-9_-]*", owner):
                        process_uid = -1
                    else:
                        raise InvalidEvidence("unsupported process UID")
                need(pid.isdecimal() and process_uid != uid and name != "com.jay.fxi" and not name.startswith("com.jay.fxi:"), "C8", "command-result")
        groups = primary("instrumentation")[1]
        args = argv(groups[0])
        need(len(groups) == 1 and groups[0][0]["op"] == "instrumentation" and args[:3] == ["shell", "am", "instrument"]
             and "s5.runId" in args and args[args.index("s5.runId") + 1] == proof["attemptId"]
             and args[-1] == "com.jay.fxi.macrobenchmark/androidx.test.runner.AndroidJUnitRunner", "C8", "command-identity")
        groups = primary("pull")[1]
        args = argv(groups[0])
        destination = Path(args[2])
        destination = destination if destination.is_absolute() else root / destination
        need(len(groups) == 1 and groups[0][0]["op"] == "pull" and args[:2] == ["pull", PURITY_ROOT + "."]
             and destination.resolve() == (root / proof["deviceFiles"]["root"]).resolve(), "C8", "command-identity")
    attempt("C8", "command-identity", stopped)
    # Population parsing is independent of the proof's instance list.
    registry = []
    rows_by_id = {}
    device_bytes = {}
    def files():
        device = proof["deviceFiles"]
        if not isinstance(device, dict) or set(device) != {"root", "files"}:
            raise InvalidEvidence("deviceFiles schema")
        folder = _safe_artifact(root, device["root"], "device folder")
        need(not folder.is_symlink() and folder.is_dir(), "C3", "file-set")
        names = []
        for item in device["files"]:
            need(set(item) == {"name", "ref"} and isinstance(item["name"], str) and Path(item["name"]).name == item["name"] and item["name"] not in (".", ".."), "C3", "file-set")
            name = item["name"]
            need(item["ref"]["path"] == device["root"] + "/" + name, "C3", "file-set")
            names.append(name)
            device_bytes[name] = raw(item["ref"])
        need(names == sorted(set(names)), "C3", "file-set")
        actual = {p.name for p in folder.iterdir()}
        need(actual == set(names), "C3", "file-set")
        for p in folder.iterdir():
            need(p.is_file() and not p.is_symlink() and p.stat().st_nlink == 1, "C3", "file-set")
        host = [dict(name=n, type="file", size=len(b), sha256=hashlib.sha256(b).hexdigest()) for n,b in sorted(device_bytes.items())]
        for role in ("inventory-pre", "inventory-post"):
            payload = probe_payloads[role][0]
            need(set(payload) == {"files"} and payload["files"] == host, "C3", "byte-equality")
            need(all(set(f) == {"name", "type", "size", "sha256"} and type(f["size"]) is int and f["type"] == "file" for f in payload["files"]), "C3", "file-set")
    attempt("C3", "file-set", files)
    def parse_registry():
        if "registry.jsonl" not in device_bytes:
            bad("C3", "file-set")
            return
        raw_registry = _purity_lines(device_bytes["registry.jsonl"])
        ids, lives = set(), set()
        for row in raw_registry:
            valid_row = _purity_registration_valid(row) and row.get("kind") == "register"
            need(valid_row, "C2", "json-schema")
            iid, life = row.get("instanceId"), row.get("lifetimeId")
            valid_ids = _purity_id(iid) and _purity_id(life)
            need(valid_ids and iid not in ids and life not in lives, "C2", "registry")
            if not valid_row or not valid_ids:
                # Only typed rows may supply set keys, instance counts or OS time bounds.
                continue
            ids.add(iid); lives.add(life)
            registry.append(row)
            filename = f"instance-{iid}.jsonl"
            if filename not in device_bytes:
                bad("C3", "file-set")
                lines = None
            else:
                lines = attempt("C2", "json-schema", lambda: _purity_lines(device_bytes[filename]))
            if lines is not None:
                rows_by_id[iid] = lines
                need(lines[0].get("kind") == "begin" and sum(r.get("kind") == "begin" for r in lines) == 1, "C2", "registry")
                begin = lines[0]
                need(_purity_registration_valid(begin) and _purity_id(begin.get("instanceId"))
                     and _purity_id(begin.get("lifetimeId")), "C2", "json-schema")
                need({k:v for k,v in row.items() if k != "kind"} == {k:v for k,v in lines[0].items() if k != "kind"}, "C3", "instance-set")
                for row2 in lines[1:]:
                    kind = row2.get("kind")
                    need(kind in ("prepare", "commit") and set(row2) == set("kind schema instanceId attemptId runnerNonce seq".split()) | ({"line"} if kind == "prepare" else set()), "C2", "json-schema")
                    need(type(row2.get("schema")) is int and row2.get("schema") == 2
                         and all(isinstance(row2.get(k), str) for k in ("instanceId", "attemptId", "runnerNonce")), "C2", "json-schema")
                    if kind == "prepare":
                        need(isinstance(row2.get("line"), str), "C2", "json-schema")
            for row2 in [row, *(lines or [])]:
                need(type(row2.get("schema")) is int and row2.get("schema") == 2 and row2.get("instanceId") == iid
                    and row2.get("attemptId") == context.get("attemptId") and row2.get("runnerNonce") == context.get("runnerNonce"), "C4", "attribution")
                if row2.get("kind") in ("register", "begin"):
                    need(row2.get("attemptState") == "ok" and row2.get("bootId") == context.get("bootId"), "C4", "attribution")
        required = {"attempt.json", "registry.jsonl"} | {f"instance-{i}.jsonl" for i in ids}
        need(set(device_bytes) == required, "C3", "file-set")
        need(set(rows_by_id) == ids, "C3", "instance-set")
    attempt("C2", "registry", parse_registry)
    for row in registry:
        iid = row.get("instanceId")
        lines = rows_by_id.get(iid, [])
        count = max(0, (len(lines)-1)//2)
        need(bool(lines) and len(lines) % 2 == 1, "C5", "sequence")
        for index, line in enumerate(lines[1:]):
            need(line.get("kind") == ("prepare" if index % 2 == 0 else "commit") and _purity_int(line.get("seq"), minimum=1)
                 and line["seq"] == index//2 + 1, "C5", "sequence")
        instances.append(dict(instanceId=iid, lifetimeId=row.get("lifetimeId"), commits=count))
    instances.sort(key=lambda value: str(value["instanceId"]))
    claimed_instances = proof["instances"]
    instance_schema = isinstance(claimed_instances, list) and all(isinstance(i, dict) and set(i) == {"instanceId", "lifetimeId", "commits"}
        and _purity_id(i["instanceId"]) and _purity_id(i["lifetimeId"]) and _purity_int(i["commits"]) for i in claimed_instances)
    projection = lambda rows: [(i["instanceId"], i["lifetimeId"]) for i in rows]
    need(instance_schema and projection(claimed_instances) == projection(instances), "C3", "instance-set")
    if instance_schema:
        # C3 checks the identity population; a malformed p/c sequence is C5, including MAX bridge samples.
        need(claimed_instances == instances, "C5", "sequence")
    records = []
    for payload in probe_payloads.get("exit-query", []):
        if not isinstance(payload, dict) or set(payload) != {"records"} or not isinstance(payload["records"], list):
            bad("C6", "record-set")
            continue
        for record in payload["records"]:
            if record not in records:
                records.append(record)
    decoded = []
    for record in records:
        def decode_record():
            if not isinstance(record, dict) or set(record) != set("pid processName realUid packageUid definingUid timestampMs reason status summaryHex".split()):
                bad("C6", "record-set")
                return
            need(all(_purity_int(record.get(k)) for k in ("pid", "realUid", "packageUid", "definingUid", "reason", "status"))
                 and isinstance(record.get("processName"), str), "C6", "record-identity")
            need(_purity_int(record.get("timestampMs"), 2**63-1), "C6", "record-time")
            h = record["summaryHex"]
            if h is None:
                return
            if not isinstance(h, str) or re.fullmatch("[0-9a-f]{128}", h) is None:
                raise InvalidEvidence("summary hex")
            decoded.append((record, decode_purity_summary(bytes.fromhex(h))))
        attempt("C6", "summary-codec", decode_record)
    for instance in instances:
        matching = [(r,s) for r,s in decoded if (s.runner_nonce,s.lifetime_id,s.instance_id) == (proof["runnerNonce"],instance["lifetimeId"],instance["instanceId"])]
        need(len(matching) == 1, "C6", "record-set")
        if len(matching) != 1:
            continue
        record, summary = matching[0]
        row = next(r for r in registry if r["instanceId"] == instance["instanceId"])
        need(summary.state == "IDLE", "C6", "summary-state")
        need(summary.counter == instance["commits"], "C6", "summary-count")
        need(all(_purity_int(record.get(k)) for k in ("pid", "realUid", "packageUid", "definingUid", "reason", "status"))
             and record.get("pid") == row.get("pid") and record.get("processName") == row.get("processName") == "com.jay.fxi"
             and record.get("packageUid") == record.get("realUid") == record.get("definingUid") == row.get("packageUid") == uid
             and row.get("userId") == user, "C6", "record-identity")
        need(_purity_int(record.get("timestampMs"), 2**63-1) and clocks.get("install-clock", 2**63) <= row.get("processStartWallMs", -1)
             <= record["timestampMs"] <= clocks.get("after-stop-clock", -1), "C6", "record-time")
        need(_purity_int(record.get("reason")) and _purity_int(record.get("status"))
             and (record["reason"], record["status"]) in {(10,0),(11,0),(2,9),(2,15)}, "C7", "exit-reason-status")
    # Sort by responsibility; proof-result is always a trailing diagnostic.
    defects.sort(key=lambda item: int(item["check"][1:]))
    count = sum(i["commits"] for i in instances)
    state, actual = ("incomplete", None) if defects else ("nonzero" if count else "zero", count)
    expected_result = dict(state=state, actualCount=actual, defects=defects.copy())
    claim = proof["result"]
    well_formed_claim = (isinstance(claim, dict) and set(claim) == {"state", "actualCount", "defects"}
        and isinstance(claim.get("state"), str) and claim["state"] in {"zero", "nonzero", "incomplete"}
        and (claim["actualCount"] is None if claim["state"] == "incomplete" else type(claim["actualCount"]) is int and claim["actualCount"] >= 0)
        and isinstance(claim.get("defects"), list) and all(isinstance(d, dict) and set(d) == {"check", "code"}
            and isinstance(d["check"], str) and isinstance(d["code"], str) for d in claim["defects"]))
    if compare_result and (not well_formed_claim or proof["result"] != expected_result):
        bad("C8", "proof-result")
        state, actual = "incomplete", None
    return PurityResult(state, defects, instances, actual)


def validate_manifest_purity(manifest: Mapping[str, Any], artifact_root: Path) -> None:
    apks = {key: manifest["artifacts"][artifact]["sha256"] for key,artifact in
            (("target", "targetBenchmarkApk"), ("test", "macrobenchmarkTestApk"))}
    seen_nonces = set()
    owners = {}
    for journey in manifest["journeys"]:
        for run in journey["runs"]:
            if "purity" not in run:
                raise InvalidEvidence("valid run requires purity")
            result = read_purity_proof(run["purity"], artifact_root, {"attemptId": run["runId"]}, apks)
            if result.state != "zero":
                raise InvalidEvidence(f"run {run['runId']} purity is {result.state}: {result.defects}")
            proof = _purity_json_bytes(_purity_ref(run["purity"], artifact_root).read_bytes())
            if proof["runnerNonce"] in seen_nonces:
                raise InvalidEvidence("cross-attempt nonce reuse")
            seen_nonces.add(proof["runnerNonce"])
            def refs(value):
                if isinstance(value, dict):
                    if set(value) == {"path", "sha256"}:
                        yield value
                    else:
                        for item in value.values():
                            yield from refs(item)
                elif isinstance(value, list):
                    for item in value:
                        yield from refs(item)
            command_rows = _purity_lines(_purity_ref(proof["commands"], artifact_root).read_bytes())
            for ref in [run["purity"], *refs(proof), *refs(command_rows)]:
                path = _purity_ref(ref, artifact_root)
                stat = path.stat()
                inode = stat.st_dev, stat.st_ino
                owner = owners.setdefault(inode, run["runId"])
                if owner != run["runId"]:
                    raise InvalidEvidence("cross-attempt evidence ref reuse")


def _reject_constant(value: str) -> None:
    raise InvalidEvidence(f"non-finite JSON number is forbidden: {value}")


def _unique_object(pairs: Sequence[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise InvalidEvidence(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(path: Path) -> Any:
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except InvalidEvidence:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise InvalidEvidence(f"cannot read strict JSON {path}: {error}") from error


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        raise InvalidEvidence(f"cannot hash artifact {path}: {error}") from error
    return digest.hexdigest()


def _finite_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise InvalidEvidence(f"{label} must be a number")
    converted = float(value)
    if not math.isfinite(converted):
        raise InvalidEvidence(f"{label} must be finite")
    return converted


def percentile(values: Sequence[float], percentile_value: float) -> float:
    """AndroidX-compatible linear interpolation over sorted frame samples."""
    if not values:
        raise InvalidEvidence("cannot compute a percentile from zero frames")
    if not 0.0 <= percentile_value <= 100.0:
        raise InvalidEvidence("percentile must be within [0, 100]")
    ordered = sorted(_finite_number(value, "frameOverrunMs sample") for value in values)
    position = (len(ordered) - 1) * percentile_value / 100.0
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


@dataclass(frozen=True)
class RunMetrics:
    p95_ms: float
    p99_ms: float
    frame_count: int


@dataclass(frozen=True)
class JourneyVerdict:
    median_p95_ms: float
    median_p99_ms: float
    p95_limit_ms: float
    p99_limit_ms: float
    verdict: str


def metrics_from_mapping(value: Mapping[str, Any], label: str = "run") -> RunMetrics:
    try:
        p95 = _finite_number(value["p95Ms"], f"{label}.p95Ms")
        p99 = _finite_number(value["p99Ms"], f"{label}.p99Ms")
        count_value = value["frameCount"]
    except KeyError as error:
        raise InvalidEvidence(f"{label} missing {error.args[0]}") from error
    if isinstance(count_value, bool) or not isinstance(count_value, int) or count_value <= 0:
        raise InvalidEvidence(f"{label}.frameCount must be a positive integer")
    return RunMetrics(p95, p99, count_value)


def journey_metrics_from_mapping(value: Mapping[str, Any], label: str) -> RunMetrics:
    try:
        p95 = _finite_number(value["medianP95Ms"], f"{label}.medianP95Ms")
        p99 = _finite_number(value["medianP99Ms"], f"{label}.medianP99Ms")
        count_value = value["frameCount"]
    except KeyError as error:
        raise InvalidEvidence(f"{label} missing {error.args[0]}") from error
    if isinstance(count_value, bool) or not isinstance(count_value, int) or count_value <= 0:
        raise InvalidEvidence(f"{label}.frameCount must be a positive integer")
    return RunMetrics(p95, p99, count_value)


def _single_benchmark(
    document: Mapping[str, Any],
    expected_identity: tuple[str, str] | None,
) -> Mapping[str, Any]:
    benchmarks = document.get("benchmarks")
    if not isinstance(benchmarks, list) or len(benchmarks) != 1:
        raise InvalidEvidence("Macrobenchmark result must contain exactly one benchmark")
    benchmark = benchmarks[0]
    if not isinstance(benchmark, dict):
        raise InvalidEvidence("benchmark entry must be an object")
    if expected_identity is not None:
        expected_class, expected_name = expected_identity
        actual_identity = (benchmark.get("className"), benchmark.get("name"))
        if actual_identity != expected_identity:
            raise InvalidEvidence(
                "benchmark identity mismatch: expected "
                f"className={expected_class!r}, name={expected_name!r}; got "
                f"className={actual_identity[0]!r}, name={actual_identity[1]!r}"
            )
    return benchmark


def extract_macrobenchmark_run(
    document: Mapping[str, Any],
    *,
    expected_identity: tuple[str, str] | None = None,
    require_traces: bool = True,
) -> RunMetrics:
    """Extract one invocation's pooled P95/P99 and reject false-green result JSON."""
    benchmark = _single_benchmark(document, expected_identity)
    context = document.get("context")
    build = context.get("build") if isinstance(context, dict) else None
    version = build.get("version") if isinstance(build, dict) else None
    if not isinstance(build, dict) or not isinstance(version, dict):
        raise InvalidEvidence("Macrobenchmark result missing context.build/version")
    if build.get("model") != EXPECTED_MEASUREMENT_CONTRACT["model"]:
        raise InvalidEvidence("Macrobenchmark context model differs from D31")
    if build.get("fingerprint") != EXPECTED_MEASUREMENT_CONTRACT["fingerprint"]:
        raise InvalidEvidence("Macrobenchmark context fingerprint differs from D31")
    if version.get("sdk") != EXPECTED_MEASUREMENT_CONTRACT["api"]:
        raise InvalidEvidence("Macrobenchmark context SDK differs from D31")
    if benchmark.get("repeatIterations") != EXPECTED_ITERATIONS:
        raise InvalidEvidence(f"repeatIterations must be {EXPECTED_ITERATIONS}")
    if benchmark.get("warmupIterations") != EXPECTED_WARMUPS:
        raise InvalidEvidence(f"warmupIterations must be {EXPECTED_WARMUPS}")

    sampled = benchmark.get("sampledMetrics")
    metric = sampled.get("frameOverrunMs") if isinstance(sampled, dict) else None
    if not isinstance(metric, dict):
        raise InvalidEvidence("missing sampledMetrics.frameOverrunMs")
    try:
        reported_p95 = _finite_number(metric["P95"], "frameOverrunMs.P95")
        reported_p99 = _finite_number(metric["P99"], "frameOverrunMs.P99")
        sample_runs = metric["runs"]
    except KeyError as error:
        raise InvalidEvidence(f"frameOverrunMs missing {error.args[0]}") from error
    if not isinstance(sample_runs, list) or len(sample_runs) != EXPECTED_ITERATIONS:
        raise InvalidEvidence(f"frameOverrunMs.runs must contain {EXPECTED_ITERATIONS} iterations")
    flattened: list[float] = []
    per_iteration_counts: list[int] = []
    for index, samples in enumerate(sample_runs):
        if not isinstance(samples, list) or not samples:
            raise InvalidEvidence(f"frameOverrunMs.runs[{index}] must contain frames")
        converted = [_finite_number(sample, f"frameOverrunMs.runs[{index}]") for sample in samples]
        flattened.extend(converted)
        per_iteration_counts.append(len(converted))

    calculated_p95 = percentile(flattened, 95.0)
    calculated_p99 = percentile(flattened, 99.0)
    if not math.isclose(reported_p95, calculated_p95, abs_tol=1e-6):
        raise InvalidEvidence(
            f"reported P95 {reported_p95} != recomputed pooled P95 {calculated_p95}"
        )
    if not math.isclose(reported_p99, calculated_p99, abs_tol=1e-6):
        raise InvalidEvidence(
            f"reported P99 {reported_p99} != recomputed pooled P99 {calculated_p99}"
        )

    metrics = benchmark.get("metrics")
    frame_count = metrics.get("frameCount") if isinstance(metrics, dict) else None
    count_runs = frame_count.get("runs") if isinstance(frame_count, dict) else None
    if not isinstance(count_runs, list) or len(count_runs) != EXPECTED_ITERATIONS:
        raise InvalidEvidence(f"metrics.frameCount.runs must contain {EXPECTED_ITERATIONS} values")
    normalized_counts: list[int] = []
    for index, value in enumerate(count_runs):
        numeric = _finite_number(value, f"frameCount.runs[{index}]")
        if numeric <= 0 or not numeric.is_integer():
            raise InvalidEvidence(f"frameCount.runs[{index}] must be a positive whole number")
        normalized_counts.append(int(numeric))
    if normalized_counts != per_iteration_counts:
        raise InvalidEvidence(
            f"frameCount runs {normalized_counts} do not match sampled frames {per_iteration_counts}"
        )

    if require_traces:
        outputs = benchmark.get("profilerOutputs")
        if not isinstance(outputs, list) or len(outputs) != EXPECTED_ITERATIONS:
            raise InvalidEvidence(f"profilerOutputs must contain {EXPECTED_ITERATIONS} traces")
        filenames: list[str] = []
        for index, output in enumerate(outputs):
            if not isinstance(output, dict) or output.get("type") != "PerfettoTrace":
                raise InvalidEvidence(f"profilerOutputs[{index}] is not a PerfettoTrace")
            filename = output.get("filename")
            if not isinstance(filename, str) or not filename:
                raise InvalidEvidence(f"profilerOutputs[{index}] missing filename")
            filenames.append(filename)
        if len(set(filenames)) != len(filenames):
            raise InvalidEvidence("Perfetto trace filenames must be unique")

    return RunMetrics(reported_p95, reported_p99, sum(normalized_counts))


def _whole(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise InvalidEvidence(f"{label} must be a non-negative whole number")
    return value


def _check_behavior_state(value: Any, label: str) -> None:
    if not isinstance(value, dict) or set(value) != BEHAVIOR_STATE_KEYS:
        raise InvalidEvidence(f"{label} must contain exactly {sorted(BEHAVIOR_STATE_KEYS)}")
    for key in ("tab", "period"):
        if not isinstance(value[key], str) or not value[key]:
            raise InvalidEvidence(f"{label}.{key} must be a non-empty string")
    if not isinstance(value["ready"], bool):
        raise InvalidEvidence(f"{label}.ready must be a boolean")
    _whole(value["seriesShown"], f"{label}.seriesShown")
    if not isinstance(value["scroll"], str) or value["scroll"] not in BEHAVIOR_SCROLL_VALUES:
        raise InvalidEvidence(f"{label}.scroll must be one of {sorted(BEHAVIOR_SCROLL_VALUES)}")


def _iteration_keys(expected: Mapping[str, Any]) -> set[str]:
    keys = {"phase", "index", "lookups", "before", "after", "action"}
    if expected["during"] is not None:
        keys.add("during")
    return keys


def _check_slot_position(iteration: Mapping[str, Any], position: int, item: str) -> None:
    if iteration["phase"] != BEHAVIOR_PHASES[position]:
        raise InvalidEvidence(f"{item}.phase must be {BEHAVIOR_PHASES[position]}")
    if _whole(iteration["index"], f"{item}.index") != BEHAVIOR_INDICES[position]:
        raise InvalidEvidence(f"{item}.index must be {BEHAVIOR_INDICES[position]}")


def _check_success_iteration(iteration: Mapping[str, Any], expected: Mapping[str, Any], item: str) -> None:
    """A fully observed slot: every observation present and of its type (phase and index are checked apart)."""
    lookups = iteration["lookups"]
    if not isinstance(lookups, dict) or set(lookups) != expected["lookups"]:
        raise InvalidEvidence(f"{item}.lookups must record exactly {sorted(expected['lookups'])}")
    for tag, count in lookups.items():
        _whole(count, f"{item}.lookups[{tag}]")
    _check_behavior_state(iteration["before"], f"{item}.before")
    _check_behavior_state(iteration["after"], f"{item}.after")
    if expected["during"] is not None:
        during = iteration["during"]
        if (
            not isinstance(during, dict)
            or set(during) != {"scroll"}
            or not isinstance(during["scroll"], str)
            or during["scroll"] not in BEHAVIOR_SCROLL_VALUES
        ):
            raise InvalidEvidence(f"{item}.during must be a scroll observation")
    if not isinstance(iteration["action"], str) or iteration["action"] not in BEHAVIOR_ACTIONS:
        raise InvalidEvidence(f"{item}.action must be one of {sorted(BEHAVIOR_ACTIONS)}")


def _iteration_failures(iteration: Mapping[str, Any], expected: Mapping[str, Any], item: str) -> list[str]:
    """Every expectation a fully observed slot misses."""
    failures: list[str] = []
    for tag in sorted(expected["lookups"]):
        if iteration["lookups"][tag] != 1:
            failures.append(f"{item} looked {tag} up {iteration['lookups'][tag]} times")
    for phase in ("before", "after"):
        for key, value in expected[phase].items():
            if iteration[phase][key] != value:
                failures.append(f"{item}.{phase}.{key} is {iteration[phase][key]!r}, expected {value!r}")
    if expected["during"] is not None and iteration["during"] != expected["during"]:
        failures.append(f"{item}.during is {iteration['during']!r}, expected {expected['during']!r}")
    if iteration["action"] != "completed":
        failures.append(f"{item}.action is {iteration['action']!r}")
    return failures


def check_behavior_structure(document: Any, *, run_id: str, journey_id: str, label: str) -> None:
    """Shape, types and identity of one run's behavior record; anything else is not a record of that run at all."""
    expected = EXPECTED_BEHAVIOR.get(journey_id)
    if expected is None:
        raise InvalidEvidence(f"{label} has no behavior contract for journey {journey_id!r}")
    if not isinstance(document, dict) or set(document) != {"schemaVersion", "runId", "journey", "iterations"}:
        raise InvalidEvidence(f"{label} must contain exactly schemaVersion, runId, journey and iterations")
    if _whole(document["schemaVersion"], f"{label}.schemaVersion") != BEHAVIOR_SCHEMA_VERSION:
        raise InvalidEvidence(f"{label}.schemaVersion must be {BEHAVIOR_SCHEMA_VERSION}")
    if document["runId"] != run_id:
        raise InvalidEvidence(f"{label}.runId does not match {run_id!r}")
    if document["journey"] != journey_id:
        raise InvalidEvidence(f"{label}.journey does not match {journey_id!r}")
    iterations = document["iterations"]
    if not isinstance(iterations, list) or len(iterations) != len(BEHAVIOR_PHASES):
        raise InvalidEvidence(f"{label}.iterations must contain {len(BEHAVIOR_PHASES)} entries")
    keys = _iteration_keys(expected)
    for position, iteration in enumerate(iterations):
        item = f"{label}.iterations[{position}]"
        if not isinstance(iteration, dict) or set(iteration) != keys:
            raise InvalidEvidence(f"{item} must contain exactly {sorted(keys)}")
        _check_slot_position(iteration, position, item)
        _check_success_iteration(iteration, expected, item)


def behavior_failures(document: Mapping[str, Any], journey_id: str) -> list[str]:
    """Every expectation a structurally checked record misses; empty when the journey behaved as agreed."""
    expected = EXPECTED_BEHAVIOR[journey_id]
    failures: list[str] = []
    for position, iteration in enumerate(document["iterations"]):
        failures.extend(_iteration_failures(iteration, expected, f"iterations[{position}]"))
    return failures


def validate_behavior(document: Any, *, run_id: str, journey_id: str, label: str) -> None:
    """A valid run's behavior record: well formed for that run and journey, and every expectation met."""
    check_behavior_structure(document, run_id=run_id, journey_id=journey_id, label=label)
    failures = behavior_failures(document, journey_id)
    if failures:
        raise InvalidEvidence(f"{label} does not show the agreed behavior: " + "; ".join(failures[:3]))


# S5-4 slice 1a: a journey that does not behave as agreed stops at once. Its record keeps the planned eight slots: the
# slots before the failure completed and met every expectation, the failed slot holds what was observed (null where
# nothing was), and the slots after it never ran. The parser checks the record's form and consistency; it cannot prove
# that an exception or an observation actually happened.
FAILURE_STAGES = {"setup", "lookup", "before", "action", "during", "after"}
FAILURE_KINDS = {"expectation-mismatch", "operation-error"}
FAILURE_RECORD_KEYS = {"schemaVersion", "runId", "journey", "status", "failure", "iterations"}
FAILURE_DETAIL_KEYS = {"slot", "stage", "kind", "reason"}


def _check_partial_lookups(value: Any, tags: set[str], label: str) -> None:
    if value is None:
        return
    if not isinstance(value, dict) or set(value) != tags:
        raise InvalidEvidence(f"{label} must be null or record exactly {sorted(tags)}")
    for tag, count in value.items():
        if count is not None:
            _whole(count, f"{label}[{tag}]")


def _check_partial_state(value: Any, label: str) -> None:
    if value is None:
        return
    if not isinstance(value, dict) or set(value) != BEHAVIOR_STATE_KEYS:
        raise InvalidEvidence(f"{label} must be null or contain exactly {sorted(BEHAVIOR_STATE_KEYS)}")
    for key in ("tab", "period"):
        if value[key] is not None and (not isinstance(value[key], str) or not value[key]):
            raise InvalidEvidence(f"{label}.{key} must be null or a non-empty string")
    if value["ready"] is not None and not isinstance(value["ready"], bool):
        raise InvalidEvidence(f"{label}.ready must be null or a boolean")
    if value["seriesShown"] is not None:
        _whole(value["seriesShown"], f"{label}.seriesShown")
    scroll = value["scroll"]
    if scroll is not None and (not isinstance(scroll, str) or scroll not in BEHAVIOR_SCROLL_VALUES):
        raise InvalidEvidence(f"{label}.scroll must be null or one of {sorted(BEHAVIOR_SCROLL_VALUES)}")


def _check_partial_during(value: Any, label: str) -> None:
    if value is None:
        return
    if not isinstance(value, dict) or set(value) != {"scroll"}:
        raise InvalidEvidence(f"{label} must be null or a scroll observation")
    scroll = value["scroll"]
    if scroll is not None and (not isinstance(scroll, str) or scroll not in BEHAVIOR_SCROLL_VALUES):
        raise InvalidEvidence(f"{label}.scroll must be null or one of {sorted(BEHAVIOR_SCROLL_VALUES)}")


def _stage_mismatch(stage: str, iteration: Mapping[str, Any], expected: Mapping[str, Any]) -> bool:
    """Whether the declared stage's own non-null observations miss an expectation."""
    if stage == "lookup":
        lookups = iteration["lookups"]
        return lookups is not None and any(count is not None and count != 1 for count in lookups.values())
    if stage in ("before", "after"):
        state = iteration[stage]
        return state is not None and any(
            state[key] is not None and state[key] != value for key, value in expected[stage].items()
        )
    if stage == "during":
        during = iteration["during"]
        return during is not None and during["scroll"] is not None and during["scroll"] != expected["during"]["scroll"]
    return False


def check_failure_record(document: Any, *, run_id: str, journey_id: str, label: str) -> None:
    """A terminal behavior failure record of that attempt, consistent with the slot and stage it declares."""
    expected = EXPECTED_BEHAVIOR.get(journey_id)
    if expected is None:
        raise InvalidEvidence(f"{label} has no behavior contract for journey {journey_id!r}")
    if not isinstance(document, dict) or set(document) != FAILURE_RECORD_KEYS:
        raise InvalidEvidence(f"{label} must contain exactly {sorted(FAILURE_RECORD_KEYS)}")
    if _whole(document["schemaVersion"], f"{label}.schemaVersion") != BEHAVIOR_SCHEMA_VERSION:
        raise InvalidEvidence(f"{label}.schemaVersion must be {BEHAVIOR_SCHEMA_VERSION}")
    if document["runId"] != run_id:
        raise InvalidEvidence(f"{label}.runId does not match {run_id!r}")
    if document["journey"] != journey_id:
        raise InvalidEvidence(f"{label}.journey does not match {journey_id!r}")
    if document["status"] != "failed":
        raise InvalidEvidence(f"{label}.status must be failed")
    failure = document["failure"]
    if not isinstance(failure, dict) or set(failure) != FAILURE_DETAIL_KEYS:
        raise InvalidEvidence(f"{label}.failure must contain exactly {sorted(FAILURE_DETAIL_KEYS)}")
    slot = _whole(failure["slot"], f"{label}.failure.slot")
    if slot >= len(BEHAVIOR_PHASES):
        raise InvalidEvidence(f"{label}.failure.slot must be below {len(BEHAVIOR_PHASES)}")
    stage, kind, reason = failure["stage"], failure["kind"], failure["reason"]
    if not isinstance(stage, str) or stage not in FAILURE_STAGES:
        raise InvalidEvidence(f"{label}.failure.stage must be one of {sorted(FAILURE_STAGES)}")
    if not isinstance(kind, str) or kind not in FAILURE_KINDS:
        raise InvalidEvidence(f"{label}.failure.kind must be one of {sorted(FAILURE_KINDS)}")
    if not isinstance(reason, str) or not reason.strip():
        raise InvalidEvidence(f"{label}.failure.reason must say what failed")
    if stage == "during" and expected["during"] is None:
        raise InvalidEvidence(f"{label}.failure.stage during does not exist for journey {journey_id}")
    iterations = document["iterations"]
    if not isinstance(iterations, list) or len(iterations) != len(BEHAVIOR_PHASES):
        raise InvalidEvidence(f"{label}.iterations must contain {len(BEHAVIOR_PHASES)} entries")
    observation_keys = _iteration_keys(expected) - {"phase", "index"}
    keys = _iteration_keys(expected) | {"status"}
    for position, iteration in enumerate(iterations):
        item = f"{label}.iterations[{position}]"
        if not isinstance(iteration, dict) or set(iteration) != keys:
            raise InvalidEvidence(f"{item} must contain exactly {sorted(keys)}")
        _check_slot_position(iteration, position, item)
        status = iteration["status"]
        if position < slot:
            if status != "completed":
                raise InvalidEvidence(f"{item}.status must be completed before the failed slot")
            _check_success_iteration(iteration, expected, item)
            missed = _iteration_failures(iteration, expected, item)
            if missed:
                raise InvalidEvidence(f"{item} completed before the failure but misses an expectation: {missed[0]}")
        elif position == slot:
            if status != "failed":
                raise InvalidEvidence(f"{item}.status must be failed at the declared slot")
            _check_partial_lookups(iteration["lookups"], expected["lookups"], f"{item}.lookups")
            _check_partial_state(iteration["before"], f"{item}.before")
            _check_partial_state(iteration["after"], f"{item}.after")
            if expected["during"] is not None:
                _check_partial_during(iteration["during"], f"{item}.during")
            action = iteration["action"]
            if action is not None and (not isinstance(action, str) or action not in BEHAVIOR_ACTIONS):
                raise InvalidEvidence(f"{item}.action must be null or one of {sorted(BEHAVIOR_ACTIONS)}")
            if action == "failed" and stage != "action":
                raise InvalidEvidence(f"{item}.action is failed only when the action stage failed")
            if stage == "action" and (kind != "operation-error" or action != "failed"):
                raise InvalidEvidence(f"{item} an action failure is an operation error recorded as action=failed")
            if kind == "expectation-mismatch" and stage not in ("setup", "action"):
                if not _stage_mismatch(stage, iteration, expected):
                    raise InvalidEvidence(f"{item} shows no {stage} expectation mismatch")
        else:
            if status != "not-run":
                raise InvalidEvidence(f"{item}.status must be not-run after the failed slot")
            if any(iteration[key] is not None for key in observation_keys):
                raise InvalidEvidence(f"{item} never ran, so it observes nothing")


FAILURE_REPORT_KEYS = {
    "schemaVersion", "kind", "gate", "sourceCommit", "journey", "attemptId", "ordinal",
    "behavior", "preflight", "postflight", "verdict",
}


def validate_failure_report(report: Any, artifact_root: Path) -> None:
    """A terminal behavior failure report: the failing attempt's record and its pre/postflight. It always fails the gate.
    It does not prove that no earlier attempt was hidden or that nothing ran after it; the runner's journal owns that."""
    if not isinstance(report, dict) or set(report) != FAILURE_REPORT_KEYS:
        raise InvalidEvidence(f"failure report must contain exactly {sorted(FAILURE_REPORT_KEYS)}")
    if _whole(report["schemaVersion"], "schemaVersion") != SCHEMA_VERSION:
        raise InvalidEvidence(f"schemaVersion must be {SCHEMA_VERSION}")
    if report["kind"] != "terminal-behavior-failure":
        raise InvalidEvidence("kind must be terminal-behavior-failure")
    gate = report["gate"]
    if not isinstance(gate, str) or gate not in {"S5", "S11"}:
        raise InvalidEvidence("gate must be S5 or S11")
    commit = report["sourceCommit"]
    if not isinstance(commit, str) or not GIT_SHA_RE.fullmatch(commit):
        raise InvalidEvidence("sourceCommit must be a lowercase 40-hex Git SHA")
    journey_id = report["journey"]
    if not isinstance(journey_id, str) or journey_id not in EXPECTED_JOURNEY_IDS:
        raise InvalidEvidence(f"journey must be one of {sorted(EXPECTED_JOURNEY_IDS)}")
    attempt_id = report["attemptId"]
    if not isinstance(attempt_id, str) or not RUN_ID_RE.fullmatch(attempt_id):
        raise InvalidEvidence("attemptId must be a stable id")
    ordinal = _whole(report["ordinal"], "ordinal")
    if not 1 <= ordinal <= EXPECTED_MEASUREMENT_CONTRACT["maxAttempts"]:
        raise InvalidEvidence(f"ordinal must be 1 to {EXPECTED_MEASUREMENT_CONTRACT['maxAttempts']}")
    if report["verdict"] != "fail":
        raise InvalidEvidence("a terminal behavior failure report can only fail")
    # No separate reuse check: one file cannot serve two roles, because a failure record has an exact key set without the
    # device fields and the two snapshots differ in their phase.
    paths = {role: validate_artifact_ref(report[role], artifact_root, role) for role in ("behavior", "preflight", "postflight")}
    check_failure_record(load_json(paths["behavior"]), run_id=attempt_id, journey_id=journey_id, label="behavior")
    pre_observed = validate_preflight_snapshot(paths["preflight"], "preflight", run_id=attempt_id, phase="preflight")
    post_observed = validate_preflight_snapshot(paths["postflight"], "postflight", run_id=attempt_id, phase="postflight")
    try:
        pre_at = datetime.fromisoformat(pre_observed.replace("Z", "+00:00"))
        post_at = datetime.fromisoformat(post_observed.replace("Z", "+00:00"))
    except ValueError as error:
        raise InvalidEvidence("failure report has malformed observation timestamps") from error
    if pre_at >= post_at:
        raise InvalidEvidence("postflight must be later than preflight")


def s11_p99_limit(b99: float) -> float:
    return min(S5_P99_LIMIT_MS, max(0.0, _finite_number(b99, "B99") + 2.0))


def evaluate_triplet(
    runs: Sequence[RunMetrics],
    *,
    mode: str,
    b99: float | None = None,
) -> JourneyVerdict:
    if len(runs) != EXPECTED_VALID_RUNS:
        raise InvalidEvidence(f"journey must contain exactly {EXPECTED_VALID_RUNS} valid runs")
    p95 = statistics.median(run.p95_ms for run in runs)
    p99 = statistics.median(run.p99_ms for run in runs)
    if mode == "s5":
        if b99 is not None:
            raise InvalidEvidence("S5 must not derive its own threshold from B99")
        p99_limit = S5_P99_LIMIT_MS
    elif mode == "s11":
        if b99 is None:
            raise InvalidEvidence("S11 requires B99 from an approved S5 manifest")
        p99_limit = s11_p99_limit(b99)
    else:
        raise InvalidEvidence(f"unsupported gate mode: {mode}")
    verdict = "pass" if p95 <= S5_P95_LIMIT_MS and p99 <= p99_limit else "fail"
    return JourneyVerdict(p95, p99, S5_P95_LIMIT_MS, p99_limit, verdict)


def _safe_artifact(root: Path, relative: Any, label: str) -> Path:
    if not isinstance(relative, str) or not relative:
        raise InvalidEvidence(f"{label}.path must be a non-empty relative path")
    candidate = Path(relative)
    if candidate.is_absolute() or ".." in candidate.parts:
        raise InvalidEvidence(f"{label}.path escapes artifact root")
    resolved_root = root.resolve()
    resolved = (resolved_root / candidate).resolve()
    try:
        resolved.relative_to(resolved_root)
    except ValueError as error:
        raise InvalidEvidence(f"{label}.path escapes artifact root") from error
    return resolved


def validate_artifact_ref(entry: Any, artifact_root: Path, label: str) -> Path:
    if not isinstance(entry, dict) or set(entry) != {"path", "sha256"}:
        raise InvalidEvidence(f"{label} must contain path and sha256")
    digest = entry["sha256"]
    if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
        raise InvalidEvidence(f"{label}.sha256 must be lowercase 64-hex")
    path = _safe_artifact(artifact_root, entry["path"], label)
    if not path.is_file():
        raise InvalidEvidence(f"artifact does not exist: {entry['path']}")
    actual = sha256_file(path)
    if actual != digest:
        raise InvalidEvidence(f"artifact hash mismatch for {label}: {actual} != {digest}")
    return path


def validate_preflight_snapshot(
    path: Path,
    label: str,
    *,
    run_id: str,
    phase: str,
) -> str:
    snapshot = load_json(path)
    if not isinstance(snapshot, dict):
        raise InvalidEvidence(f"{label} root must be an object")
    expected = {
        "model": EXPECTED_MEASUREMENT_CONTRACT["model"],
        "fingerprint": EXPECTED_MEASUREMENT_CONTRACT["fingerprint"],
        "api": EXPECTED_MEASUREMENT_CONTRACT["api"],
        "posture": EXPECTED_MEASUREMENT_CONTRACT["posture"],
        "physicalSize": "1080x2640",
        "orientation": 0,
        "supports120Hz": True,
        "lowPower": False,
        "thermalStatus": 0,
        "serialRecorded": False,
        "runId": run_id,
        "phase": phase,
    }
    for key, expected_value in expected.items():
        if snapshot.get(key) != expected_value:
            raise InvalidEvidence(f"{label}.{key} does not match D31: {snapshot.get(key)!r}")
    if snapshot.get("overrideSize") not in {None, "1080x2640"}:
        raise InvalidEvidence(f"{label}.overrideSize changes the fixed display")
    battery = snapshot.get("batteryLevel")
    if isinstance(battery, bool) or not isinstance(battery, int) or battery < 30:
        raise InvalidEvidence(f"{label}.batteryLevel must be at least 30")
    powered = snapshot.get("powered")
    required_power = {"ac powered", "usb powered", "wireless powered"}
    if (
        not isinstance(powered, dict)
        or not required_power.issubset(powered)
        or any(value is not False for value in powered.values())
    ):
        raise InvalidEvidence(f"{label}.powered must contain only false values")
    settings = snapshot.get("settings")
    expected_settings = {
        "system/peak_refresh_rate": "120.0",
        "system/min_refresh_rate": "120.0",
        "global/window_animation_scale": "1.0",
        "global/transition_animation_scale": "1.0",
        "global/animator_duration_scale": "1.0",
    }
    if settings != expected_settings:
        raise InvalidEvidence(f"{label}.settings do not match fixed refresh/animation contract")
    forbidden = [key for key in snapshot if "serial" in key.lower() and key != "serialRecorded"]
    if forbidden:
        raise InvalidEvidence(f"{label} records forbidden serial fields: {forbidden}")
    observed_at = snapshot.get("observedAt")
    if not isinstance(observed_at, str) or not observed_at.endswith(("Z", "+00:00")):
        raise InvalidEvidence(f"{label}.observedAt must be an explicit UTC timestamp")
    return observed_at


def validate_manifest(manifest: Mapping[str, Any], artifact_root: Path) -> None:
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise InvalidEvidence(f"schemaVersion must be {SCHEMA_VERSION}")
    gate = manifest.get("gate")
    if not isinstance(gate, str) or gate not in {"S5", "S11"}:
        raise InvalidEvidence("gate must be S5 or S11")
    manifest_verdict = manifest.get("verdict")
    if not isinstance(manifest_verdict, str) or manifest_verdict not in {"pass", "fail"}:
        raise InvalidEvidence("verdict must be pass or fail")
    source_commit = manifest.get("sourceCommit")
    if not isinstance(source_commit, str) or not GIT_SHA_RE.fullmatch(source_commit):
        raise InvalidEvidence("sourceCommit must be a lowercase 40-hex Git SHA")
    if manifest.get("libraries") != EXPECTED_LIBRARIES:
        raise InvalidEvidence(f"libraries must equal pinned versions {EXPECTED_LIBRARIES}")

    contract = manifest.get("measurementContract")
    if not isinstance(contract, dict):
        raise InvalidEvidence("measurementContract must be an object")
    if contract != EXPECTED_MEASUREMENT_CONTRACT:
        raise InvalidEvidence("measurementContract does not match the frozen D31/S5 contract")

    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, dict) or set(artifacts) != ARTIFACT_KEYS:
        raise InvalidEvidence(f"artifacts must contain exactly {sorted(ARTIFACT_KEYS)}")
    artifact_paths: set[Path] = set()
    artifact_digests: set[str] = set()
    validated_artifact_paths: dict[str, Path] = {}
    for key in sorted(ARTIFACT_KEYS):
        artifact_path = validate_artifact_ref(
            artifacts[key], artifact_root, f"artifacts.{key}"
        ).resolve()
        artifact_digest = sha256_file(artifact_path)
        if artifact_path in artifact_paths or artifact_digest in artifact_digests:
            raise InvalidEvidence(
                "semantic top-level artifacts must have unique paths and bytes; "
                f"duplicate at artifacts.{key}"
            )
        artifact_paths.add(artifact_path)
        artifact_digests.add(artifact_digest)
        validated_artifact_paths[key] = artifact_path
    running_parser_digest = sha256_file(Path(__file__).resolve())
    if sha256_file(validated_artifact_paths["parser"]) != running_parser_digest:
        raise InvalidEvidence(
            "artifacts.parser bytes do not match the benchmark_gate.py executing this verdict"
        )

    journeys = manifest.get("journeys")
    if not isinstance(journeys, list) or not journeys:
        raise InvalidEvidence("journeys must be a non-empty array")
    seen: set[str] = set()
    # One evidence file cannot impersonate two semantic roles, including a top-level
    # artifact and a run/preflight/trace/invalid-attempt artifact.
    used_run_artifacts: set[Path] = set(artifact_paths)
    used_run_digests: set[str] = set(artifact_digests)
    seen_run_ids: set[str] = set()
    seen_attempt_ids: set[str] = set()
    journey_verdicts: list[str] = []
    for index, journey in enumerate(journeys):
        if not isinstance(journey, dict):
            raise InvalidEvidence(f"journeys[{index}] must be an object")
        journey_id = journey.get("id")
        if not isinstance(journey_id, str) or not journey_id or journey_id in seen:
            raise InvalidEvidence(f"journeys[{index}].id must be unique and non-empty")
        seen.add(journey_id)
        expected_benchmark_identity = EXPECTED_BENCHMARK_IDENTITIES.get(journey_id)
        reported = journey_metrics_from_mapping(journey, f"journeys[{index}]")
        runs = journey.get("runs")
        if not isinstance(runs, list) or len(runs) != EXPECTED_VALID_RUNS:
            raise InvalidEvidence(
                f"journeys[{index}].runs must contain {EXPECTED_VALID_RUNS} valid runs"
            )
        extracted: list[RunMetrics] = []
        for run_index, run in enumerate(runs):
            label = f"journeys[{index}].runs[{run_index}]"
            if not isinstance(run, dict):
                raise InvalidEvidence(f"{label} must be an object")
            run_id = run.get("runId")
            if (
                not isinstance(run_id, str)
                or not RUN_ID_RE.fullmatch(run_id)
                or run_id in seen_run_ids
            ):
                raise InvalidEvidence(f"{label}.runId must be globally unique and stable")
            seen_run_ids.add(run_id)
            result_path = validate_artifact_ref(run.get("result"), artifact_root, f"{label}.result")
            behavior_path = validate_artifact_ref(run.get("behavior"), artifact_root, f"{label}.behavior")
            preflight_path = validate_artifact_ref(
                run.get("preflight"), artifact_root, f"{label}.preflight"
            )
            postflight_path = validate_artifact_ref(
                run.get("postflight"), artifact_root, f"{label}.postflight"
            )
            pre_observed = validate_preflight_snapshot(
                preflight_path, f"{label}.preflight", run_id=run_id, phase="preflight"
            )
            post_observed = validate_preflight_snapshot(
                postflight_path, f"{label}.postflight", run_id=run_id, phase="postflight"
            )
            try:
                if datetime.fromisoformat(pre_observed.replace("Z", "+00:00")) >= datetime.fromisoformat(
                    post_observed.replace("Z", "+00:00")
                ):
                    raise InvalidEvidence(f"{label} postflight must be later than preflight")
            except ValueError as error:
                raise InvalidEvidence(f"{label} has malformed observation timestamps") from error
            traces = run.get("traces")
            if not isinstance(traces, list) or len(traces) != EXPECTED_ITERATIONS:
                raise InvalidEvidence(f"{label}.traces must contain {EXPECTED_ITERATIONS} artifacts")
            trace_paths = [
                validate_artifact_ref(trace, artifact_root, f"{label}.traces[{trace_index}]")
                for trace_index, trace in enumerate(traces)
            ]
            if len({path.resolve() for path in trace_paths}) != len(trace_paths):
                raise InvalidEvidence(f"{label}.traces must be unique")
            run_artifacts = [result_path, behavior_path, preflight_path, postflight_path, *trace_paths]
            for path in run_artifacts:
                resolved_path = path.resolve()
                if resolved_path in used_run_artifacts:
                    raise InvalidEvidence(f"run evidence is reused instead of independent: {path}")
                used_run_artifacts.add(resolved_path)
                digest = sha256_file(path)
                if digest in used_run_digests:
                    raise InvalidEvidence(f"byte-identical evidence is reused: {path}")
                used_run_digests.add(digest)
            raw = load_json(result_path)
            if not isinstance(raw, dict):
                raise InvalidEvidence(f"{label}.result root must be an object")
            extracted_run = extract_macrobenchmark_run(
                raw, expected_identity=expected_benchmark_identity
            )
            raw_benchmark = _single_benchmark(raw, None)
            raw_outputs = raw_benchmark.get("profilerOutputs")
            # extract_macrobenchmark_run already established five mapping entries with
            # non-empty filenames, keeping malformed evidence on the InvalidEvidence path.
            raw_trace_names = [Path(output["filename"]).name for output in raw_outputs]
            evidence_trace_names = [path.name for path in trace_paths]
            if raw_trace_names != evidence_trace_names:
                raise InvalidEvidence(f"{label}.traces do not match profilerOutputs filenames")
            validate_behavior(
                load_json(behavior_path), run_id=run_id, journey_id=journey_id, label=f"{label}.behavior"
            )
            extracted.append(extracted_run)

        calculated = evaluate_triplet(extracted, mode="s5")
        if not math.isclose(reported.p95_ms, calculated.median_p95_ms, abs_tol=1e-6):
            raise InvalidEvidence(f"journey medianP95Ms is inconsistent: {journey_id}")
        if not math.isclose(reported.p99_ms, calculated.median_p99_ms, abs_tol=1e-6):
            raise InvalidEvidence(f"journey medianP99Ms is inconsistent: {journey_id}")
        if reported.frame_count != sum(run.frame_count for run in extracted):
            raise InvalidEvidence(f"journey frameCount is inconsistent: {journey_id}")
        attempts = journey.get("attempts")
        if not isinstance(attempts, list) or not 3 <= len(attempts) <= 5:
            raise InvalidEvidence(f"journeys[{index}].attempts must contain 3 to 5 attempts")
        valid_attempt_runs: set[str] = set()
        for attempt_index, attempt in enumerate(attempts):
            attempt_label = f"journeys[{index}].attempts[{attempt_index}]"
            if not isinstance(attempt, dict):
                raise InvalidEvidence(f"{attempt_label} must be an object")
            attempt_id = attempt.get("attemptId")
            ordinal = attempt.get("ordinal")
            outcome = attempt.get("outcome")
            if (
                not isinstance(attempt_id, str)
                or not attempt_id
                or attempt_id in seen_attempt_ids
            ):
                raise InvalidEvidence(f"{attempt_label}.attemptId must be globally unique")
            seen_attempt_ids.add(attempt_id)
            if isinstance(ordinal, bool) or ordinal != attempt_index + 1:
                raise InvalidEvidence(f"{attempt_label}.ordinal must be {attempt_index + 1}")
            if outcome == "valid":
                attempt_run_id = attempt.get("runId")
                if set(attempt) != {"attemptId", "ordinal", "outcome", "runId"}:
                    raise InvalidEvidence(f"{attempt_label} valid shape is invalid")
                if not isinstance(attempt_run_id, str) or not RUN_ID_RE.fullmatch(attempt_run_id):
                    raise InvalidEvidence(f"{attempt_label}.runId must be a stable string")
                if attempt_run_id in valid_attempt_runs:
                    raise InvalidEvidence(f"{attempt_label} repeats a valid runId")
                valid_attempt_runs.add(attempt_run_id)
            elif outcome == "invalid":
                if set(attempt) != {
                    "attemptId",
                    "ordinal",
                    "outcome",
                    "reason",
                    "evidence",
                }:
                    raise InvalidEvidence(f"{attempt_label} invalid shape is invalid")
                reason = attempt.get("reason")
                if not isinstance(reason, str) or reason not in {
                    "environment-invalid",
                    "result-malformed",
                }:
                    raise InvalidEvidence(
                        f"{attempt_label}.reason must be environment-invalid or result-malformed"
                    )
                invalid_path = validate_artifact_ref(
                    attempt.get("evidence"), artifact_root, f"{attempt_label}.evidence"
                )
                digest = sha256_file(invalid_path)
                if digest in used_run_digests:
                    raise InvalidEvidence(f"attempt evidence is byte-reused: {invalid_path}")
                used_run_digests.add(digest)
                invalid_document = load_json(invalid_path)
                if not isinstance(invalid_document, dict):
                    raise InvalidEvidence(f"{attempt_label}.evidence root must be an object")
                if reason == "environment-invalid":
                    if invalid_document.get("schemaVersion") != 1:
                        raise InvalidEvidence(f"{attempt_label} environment evidence schema mismatch")
                    if invalid_document.get("runId") != attempt_id:
                        raise InvalidEvidence(f"{attempt_label} environment evidence runId mismatch")
                    if invalid_document.get("serialRecorded") is not False:
                        raise InvalidEvidence(f"{attempt_label} environment evidence records serial")
                    if not (
                        isinstance(invalid_document.get("error"), str)
                        and invalid_document.get("error")
                    ) and not (
                        isinstance(invalid_document.get("restoreError"), str)
                        and invalid_document.get("restoreError")
                    ):
                        raise InvalidEvidence(
                            f"{attempt_label} environment evidence must record an error"
                        )
                else:
                    try:
                        extract_macrobenchmark_run(
                            invalid_document,
                            expected_identity=expected_benchmark_identity,
                        )
                    except InvalidEvidence:
                        pass
                    else:
                        raise InvalidEvidence(
                            f"{attempt_label} result-malformed evidence is structurally valid; "
                            "a threshold miss is a valid measured run"
                        )
            elif outcome == "behavior-failed":
                raise InvalidEvidence(
                    f"{attempt_label} a behavior failure is terminal: report it with validate-failure, not as a manifest attempt"
                )
            else:
                raise InvalidEvidence(f"{attempt_label}.outcome must be valid or invalid")
        journey_run_ids = {run["runId"] for run in runs}
        if valid_attempt_runs != journey_run_ids or len(valid_attempt_runs) != 3:
            raise InvalidEvidence(
                f"journeys[{index}] evaluated runs must equal its three valid attempts"
            )
        if attempts[-1].get("outcome") != "valid":
            raise InvalidEvidence(
                f"journeys[{index}] must stop when the third valid run is obtained"
            )
        if manifest.get("gate") == "S5":
            if journey.get("verdict") != calculated.verdict:
                raise InvalidEvidence(f"S5 journey verdict is inconsistent: {journey_id}")
            journey_verdicts.append(calculated.verdict)
    if seen != EXPECTED_JOURNEY_IDS:
        raise InvalidEvidence(
            f"journey ids must equal {sorted(EXPECTED_JOURNEY_IDS)}, got {sorted(seen)}"
        )
    if manifest.get("gate") == "S5":
        aggregate = "pass" if all(value == "pass" for value in journey_verdicts) else "fail"
        if manifest.get("verdict") != aggregate:
            raise InvalidEvidence("S5 manifest verdict is inconsistent with journey verdicts")
    validate_manifest_purity(manifest, artifact_root)


def validate_s11_against_golden(
    candidate: Mapping[str, Any],
    golden: Mapping[str, Any],
    *,
    golden_bytes_sha256: str,
) -> None:
    if golden.get("gate") != "S5" or golden.get("verdict") != "pass":
        raise InvalidEvidence("S11 golden must be a passing S5 manifest")
    validate_golden_approval(golden.get("approval"))
    if candidate.get("gate") != "S11":
        raise InvalidEvidence("candidate must be an S11 manifest")
    if candidate.get("goldenManifestSha256") != golden_bytes_sha256:
        raise InvalidEvidence("candidate goldenManifestSha256 does not match golden bytes")
    if candidate.get("libraries") != golden.get("libraries"):
        raise InvalidEvidence("S11 library versions differ from approved S5 golden")
    if candidate.get("measurementContract") != golden.get("measurementContract"):
        raise InvalidEvidence("S11 measurementContract differs from approved S5 golden")
    candidate_artifacts = candidate.get("artifacts", {})
    golden_artifacts = golden.get("artifacts", {})
    for key in S11_PROTECTED_ARTIFACT_KEYS:
        if candidate_artifacts.get(key, {}).get("sha256") != golden_artifacts.get(key, {}).get("sha256"):
            raise InvalidEvidence(f"S11 protected artifact differs from golden: {key}")

    golden_journeys = {journey["id"]: journey for journey in golden.get("journeys", [])}
    candidate_ids = {journey.get("id") for journey in candidate.get("journeys", [])}
    if set(golden_journeys) != EXPECTED_JOURNEY_IDS or candidate_ids != EXPECTED_JOURNEY_IDS:
        raise InvalidEvidence("S11 candidate and golden must contain the exact three journeys")
    candidate_verdicts: list[str] = []
    for journey in candidate.get("journeys", []):
        journey_id = journey.get("id")
        if journey_id not in golden_journeys:
            raise InvalidEvidence(f"S11 journey absent from golden: {journey_id}")
        candidate_metrics = journey_metrics_from_mapping(journey, f"candidate.{journey_id}")
        b99 = _finite_number(
            golden_journeys[journey_id].get("medianP99Ms"),
            f"golden.{journey_id}.medianP99Ms",
        )
        expected = evaluate_triplet(
            [candidate_metrics, candidate_metrics, candidate_metrics], mode="s11", b99=b99
        )
        if journey.get("verdict") != expected.verdict:
            raise InvalidEvidence(f"S11 journey verdict is inconsistent: {journey_id}")
        candidate_verdicts.append(expected.verdict)
    aggregate = "pass" if candidate_verdicts and all(v == "pass" for v in candidate_verdicts) else "fail"
    if candidate.get("verdict") != aggregate:
        raise InvalidEvidence("S11 manifest verdict is inconsistent with journey verdicts")


def evaluate_fixture_case(case: Mapping[str, Any]) -> JourneyVerdict:
    runs_value = case.get("runs")
    if not isinstance(runs_value, list):
        raise InvalidEvidence("case.runs must be an array")
    runs = [metrics_from_mapping(value, f"runs[{index}]") for index, value in enumerate(runs_value)]
    b99 = case.get("b99")
    return evaluate_triplet(runs, mode=str(case.get("mode")), b99=b99)


def validate_golden_approval(value: Any) -> None:
    if not isinstance(value, dict) or set(value) != {"status", "approvedAt", "authority"}:
        raise InvalidEvidence(
            "S11 golden approval must contain exactly status, approvedAt, and authority"
        )
    if value.get("status") != "approved" or value.get("authority") != "user":
        raise InvalidEvidence("S11 golden must be explicitly approved by user authority")
    approved_at = value.get("approvedAt")
    if not isinstance(approved_at, str) or not re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z", approved_at
    ):
        raise InvalidEvidence("S11 golden approvedAt must be an explicit UTC RFC3339 timestamp")
    try:
        datetime.fromisoformat(approved_at.replace("Z", "+00:00"))
    except ValueError as error:
        raise InvalidEvidence("S11 golden approvedAt is not a real timestamp") from error


def validate_required_fixture_semantics(case: Mapping[str, Any], actual: str) -> None:
    case_id = case.get("id")
    if case_id in FIXTURE_NUMERIC_CONTRACT:
        mode, b99, expected_p95, expected_p99, expected_verdict = FIXTURE_NUMERIC_CONTRACT[case_id]
        if case.get("mode") != mode or case.get("expected") != expected_verdict:
            raise InvalidEvidence(f"fixture {case_id} changed its mode/expected contract")
        actual_b99 = case.get("b99")
        if b99 is None:
            if "b99" in case:
                raise InvalidEvidence(f"fixture {case_id} must not supply b99")
        elif not math.isclose(_finite_number(actual_b99, f"{case_id}.b99"), b99, abs_tol=0.0):
            raise InvalidEvidence(f"fixture {case_id} changed its B99 boundary")
        verdict = evaluate_fixture_case(case)
        if not math.isclose(verdict.median_p95_ms, expected_p95, abs_tol=1e-12):
            raise InvalidEvidence(f"fixture {case_id} changed its median P95 boundary")
        if not math.isclose(verdict.median_p99_ms, expected_p99, abs_tol=1e-12):
            raise InvalidEvidence(f"fixture {case_id} changed its median P99 boundary")
        if actual != expected_verdict:
            raise InvalidEvidence(f"fixture {case_id} no longer produces its pinned verdict")
        return
    missing = FIXTURE_MISSING_FIELD_CONTRACT.get(case_id)
    if missing is not None:
        runs = case.get("runs")
        if (
            case.get("mode") != "s5"
            or case.get("expected") != "invalid"
            or not isinstance(runs, list)
            or len(runs) != EXPECTED_VALID_RUNS
        ):
            raise InvalidEvidence(f"fixture {case_id} changed its missing-field contract")
        required = {"p95Ms", "p99Ms", "frameCount"}
        omissions = [required - set(run) if isinstance(run, dict) else required for run in runs]
        if sum(fields == {missing} for fields in omissions) != 1 or any(
            fields not in (set(), {missing}) for fields in omissions
        ):
            raise InvalidEvidence(f"fixture {case_id} must omit only {missing} exactly once")
        if actual != "invalid":
            raise InvalidEvidence(f"fixture {case_id} no longer rejects the missing field")


def evaluate_run_set(
    document: Mapping[str, Any],
    *,
    mode: str,
    artifact_root: Path,
    golden: Mapping[str, Any] | None = None,
) -> list[dict[str, Any]]:
    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise InvalidEvidence(f"run-set schemaVersion must be {SCHEMA_VERSION}")
    journeys = document.get("journeys")
    if not isinstance(journeys, list) or not journeys:
        raise InvalidEvidence("run-set journeys must be a non-empty array")
    golden_by_id: dict[str, Mapping[str, Any]] = {}
    if mode == "s11":
        if golden is None:
            raise InvalidEvidence("S11 run-set evaluation requires an approved golden")
        if golden.get("gate") != "S5" or golden.get("verdict") != "pass":
            raise InvalidEvidence("S11 golden must be a passing S5 manifest")
        validate_golden_approval(golden.get("approval"))
        golden_by_id = {journey["id"]: journey for journey in golden.get("journeys", [])}
    elif mode != "s5":
        raise InvalidEvidence(f"unsupported gate mode: {mode}")

    seen: set[str] = set()
    seen_run_ids: set[str] = set()
    used_results: set[Path] = set()
    used_result_digests: set[str] = set()
    results: list[dict[str, Any]] = []
    for index, journey in enumerate(journeys):
        if not isinstance(journey, dict):
            raise InvalidEvidence(f"run-set journeys[{index}] must be an object")
        journey_id = journey.get("id")
        benchmark_class_name = journey.get("benchmarkClassName")
        benchmark_name = journey.get("benchmarkName")
        if not isinstance(journey_id, str) or not journey_id or journey_id in seen:
            raise InvalidEvidence(f"run-set journeys[{index}].id must be unique")
        if not isinstance(benchmark_class_name, str) or not benchmark_class_name:
            raise InvalidEvidence(f"run-set journeys[{index}].benchmarkClassName is required")
        if not isinstance(benchmark_name, str) or not benchmark_name:
            raise InvalidEvidence(f"run-set journeys[{index}].benchmarkName is required")
        benchmark_identity = (benchmark_class_name, benchmark_name)
        if benchmark_identity != EXPECTED_BENCHMARK_IDENTITIES.get(journey_id):
            raise InvalidEvidence(f"run-set benchmark identity does not match journey {journey_id}")
        seen.add(journey_id)
        runs = journey.get("runs")
        if not isinstance(runs, list) or len(runs) != EXPECTED_VALID_RUNS:
            raise InvalidEvidence(f"{journey_id} must reference exactly {EXPECTED_VALID_RUNS} runs")
        metrics: list[RunMetrics] = []
        for run_index, run in enumerate(runs):
            label = f"{journey_id}.runs[{run_index}]"
            if not isinstance(run, dict) or set(run) != {"runId", "result", "behavior"}:
                raise InvalidEvidence(f"{label} must contain exactly runId, result and behavior")
            run_id = run["runId"]
            if not isinstance(run_id, str) or not RUN_ID_RE.fullmatch(run_id) or run_id in seen_run_ids:
                raise InvalidEvidence(f"{label}.runId must be globally unique and stable")
            seen_run_ids.add(run_id)
            paths: dict[str, Path] = {}
            for role in ("result", "behavior"):
                relative = run[role]
                path = _safe_artifact(artifact_root, relative, f"{label}.{role}")
                if not path.is_file():
                    raise InvalidEvidence(f"run {role} does not exist: {relative}")
                resolved = path.resolve()
                if resolved in used_results:
                    raise InvalidEvidence(f"three independent runs may not reuse a {role} JSON")
                used_results.add(resolved)
                digest = sha256_file(path)
                if digest in used_result_digests:
                    raise InvalidEvidence(f"three independent runs may not reuse byte-identical {role} evidence")
                used_result_digests.add(digest)
                paths[role] = path
            raw = load_json(paths["result"])
            if not isinstance(raw, dict):
                raise InvalidEvidence(f"run result root must be an object: {run['result']}")
            metrics.append(extract_macrobenchmark_run(raw, expected_identity=benchmark_identity))
            validate_behavior(
                load_json(paths["behavior"]), run_id=run_id, journey_id=journey_id, label=f"{label}.behavior"
            )
        b99 = None
        if mode == "s11":
            golden_journey = golden_by_id.get(journey_id)
            if golden_journey is None:
                raise InvalidEvidence(f"S11 journey absent from golden: {journey_id}")
            b99 = _finite_number(
                golden_journey.get("medianP99Ms"), f"golden.{journey_id}.medianP99Ms"
            )
        verdict = evaluate_triplet(metrics, mode=mode, b99=b99)
        results.append(
            {
                "id": journey_id,
                "medianP95Ms": verdict.median_p95_ms,
                "medianP99Ms": verdict.median_p99_ms,
                "p95LimitMs": verdict.p95_limit_ms,
                "p99LimitMs": verdict.p99_limit_ms,
                "frameCount": sum(metric.frame_count for metric in metrics),
                "verdict": verdict.verdict,
            }
        )
    if seen != EXPECTED_JOURNEY_IDS:
        raise InvalidEvidence(
            f"run-set journey ids must equal {sorted(EXPECTED_JOURNEY_IDS)}, got {sorted(seen)}"
        )
    return results


def _command_fixture(args: argparse.Namespace) -> int:
    document = load_json(args.file)
    cases = document.get("cases") if isinstance(document, dict) else None
    if not isinstance(cases, list) or not cases:
        raise InvalidEvidence("fixture file must contain non-empty cases[]")
    output: list[dict[str, Any]] = []
    invalid_seen = False
    seen_ids: set[str] = set()
    for case in cases:
        if (
            not isinstance(case, dict)
            or not isinstance(case.get("id"), str)
            or not case["id"]
            or case["id"] in seen_ids
        ):
            raise InvalidEvidence("each fixture case needs a unique non-empty id")
        seen_ids.add(case["id"])
        try:
            verdict = evaluate_fixture_case(case)
            actual = verdict.verdict
            record: dict[str, Any] = {"id": case["id"], "actual": actual, **asdict(verdict)}
        except InvalidEvidence as error:
            actual = "invalid"
            record = {"id": case["id"], "actual": actual, "error": str(error)}
        expected = case.get("expected")
        record["expected"] = expected
        record["matches"] = actual == expected
        validate_required_fixture_semantics(case, actual)
        invalid_seen = invalid_seen or not record["matches"]
        output.append(record)
    missing_cases = REQUIRED_FIXTURE_CASE_IDS - seen_ids
    if missing_cases:
        raise InvalidEvidence(
            f"fixture file is missing required boundary cases: {sorted(missing_cases)}"
        )
    print(json.dumps({"cases": output}, ensure_ascii=False, sort_keys=True, indent=2))
    return 1 if invalid_seen else 0


def _command_validate(args: argparse.Namespace) -> int:
    manifest = load_json(args.manifest)
    if not isinstance(manifest, dict):
        raise InvalidEvidence("manifest root must be an object")
    validate_manifest(manifest, args.artifact_root)
    if manifest.get("gate") == "S11" and (
        args.golden is None or args.golden_artifact_root is None
    ):
        raise InvalidEvidence(
            "S11 validation requires --golden and --golden-artifact-root"
        )
    if args.golden is None and args.golden_artifact_root is not None:
        raise InvalidEvidence("--golden-artifact-root requires --golden")
    if args.golden is not None:
        if args.golden_artifact_root is None:
            raise InvalidEvidence("--golden-artifact-root is required with --golden")
        golden = load_json(args.golden)
        if not isinstance(golden, dict):
            raise InvalidEvidence("golden root must be an object")
        validate_manifest(golden, args.golden_artifact_root)
        validate_s11_against_golden(
            manifest,
            golden,
            golden_bytes_sha256=sha256_file(args.golden),
        )
    verdict = manifest["verdict"]
    print(json.dumps({"valid": True, "gate": manifest["gate"], "verdict": verdict}, sort_keys=True))
    return 0 if verdict == "pass" else 1


def _command_evaluate(args: argparse.Namespace) -> int:
    document = load_json(args.run_set)
    if not isinstance(document, dict):
        raise InvalidEvidence("run-set root must be an object")
    golden = None
    if args.golden is not None:
        golden = load_json(args.golden)
        if not isinstance(golden, dict):
            raise InvalidEvidence("golden root must be an object")
        if args.golden_artifact_root is None:
            raise InvalidEvidence("--golden-artifact-root is required with --golden")
        validate_manifest(golden, args.golden_artifact_root)
    results = evaluate_run_set(
        document,
        mode=args.mode,
        artifact_root=args.artifact_root,
        golden=golden,
    )
    verdict = "pass" if all(result["verdict"] == "pass" for result in results) else "fail"
    print(
        json.dumps(
            {"schemaVersion": SCHEMA_VERSION, "mode": args.mode, "verdict": verdict, "journeys": results},
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
        )
    )
    return 0 if verdict == "pass" else 1


def _command_validate_failure(args: argparse.Namespace) -> int:
    report = load_json(args.report)
    validate_failure_report(report, args.artifact_root)
    print(json.dumps({"valid": True, "gate": report["gate"], "journey": report["journey"], "verdict": "fail"}, sort_keys=True))
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    fixture = subparsers.add_parser("check-fixtures", help="run checked-in threshold cases")
    fixture.add_argument("file", type=Path)
    fixture.set_defaults(handler=_command_fixture)
    validate = subparsers.add_parser("validate-manifest", help="validate evidence and hashes")
    validate.add_argument("manifest", type=Path)
    validate.add_argument("--artifact-root", type=Path, required=True)
    validate.add_argument("--golden", type=Path)
    validate.add_argument("--golden-artifact-root", type=Path)
    validate.set_defaults(handler=_command_validate)
    evaluate = subparsers.add_parser("evaluate-runs", help="evaluate three raw JSON runs per journey")
    evaluate.add_argument("run_set", type=Path)
    evaluate.add_argument("--mode", choices=("s5", "s11"), required=True)
    evaluate.add_argument("--artifact-root", type=Path, required=True)
    evaluate.add_argument("--golden", type=Path)
    evaluate.add_argument("--golden-artifact-root", type=Path)
    evaluate.set_defaults(handler=_command_evaluate)
    failure = subparsers.add_parser(
        "validate-failure", help="validate a terminal behavior failure report (a valid report exits 1: the gate fails)"
    )
    failure.add_argument("report", type=Path)
    failure.add_argument("--artifact-root", type=Path, required=True)
    failure.set_defaults(handler=_command_validate_failure)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.handler(args))
    except InvalidEvidence as error:
        print(json.dumps({"valid": False, "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
