"""L1 D17 Python contracts, r4 §§4–6. Deliberately RED until L2.

Host fixtures model the stated OS assumptions. Only BridgeTest claims Kotlin byte
compatibility, and it requires actual Kotlin exports (never skip/fabricate them).
Every generated method has one stable sub-ID; CASES is the reviewable catalog.
"""
import copy
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

import benchmark_gate as gate
import device_preflight as dp
import s5_ledger as ledger
import s5_runner as runner
from test_s5_ledger import ALL_VALID, Bench
from test_s5_runner import APKS, FakeDevice, RunnerHarness

REPO = Path(__file__).resolve().parents[2]
BRIDGE_ROOT = REPO / "app/build/s5purity-bridge-r1"
NONCE = "00112233445566778899aabbccddeeff"
LIFE = "102132435465768798a9bacbdcedfe0f"
INSTANCE = "2031425364758697a8b9cadbecfd0e1f"
BOOT = "12345678-1234-4234-8234-123456789abc"
UID = 10123
MAX = 2147483647
ROLES = (
    "boot-before", "device-api", "install", "install-identity", "install-clock", "absent",
    "context-push", "context-readback", "apk-before-target", "apk-before-test", "instrumentation",
    "apk-after-target", "apk-after-test", "stop-clock", "force-stop", "live-after-stop",
    "inventory-pre", "pull", "inventory-post", "exit-query", "after-stop-clock", "boot-after",
    "install-identity-after", "live-final",
)
CASES = []
# Stable checker sub-IDs, shared by direct, ledger and standalone consumers.
DEFECT_CODES = {
    "C1": {"api", "context", "apk", "boot-uid-clock"},
    "C2": {"json-schema", "registry", "proof"},
    "C3": {"file-set", "instance-set", "byte-equality"},
    "C4": {"attribution"}, "C5": {"sequence"},
    "C6": {"summary-codec", "summary-state", "summary-count", "record-identity", "record-time", "record-set"},
    "C7": {"exit-reason-status"},
    "C8": {"envelope", "command-result", "command-identity", "order", "roles", "query-budget", "proof-result"},
}


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def summary(state=0, count=0, *, nonce=NONCE, life=LIFE, instance=INSTANCE):
    # Host model only. Kotlin compatibility is tested from actual summary.bin.
    return b"FXID17R1" + bytes((1, state, 0, 0)) + bytes.fromhex(nonce + life + instance) + struct.pack(">I", count)


def prefix(sub_id, duty):
    return f"contract(D17b):{sub_id}/{duty}"


class ProofFixture:
    """A full standalone proof, with raw refs and hashes rebuilt after semantic edits.

    No opaque copied proof/golden: commands are paired, each stream is preserved,
    inventories derive from device bytes, and host bytes are independently written.
    """

    def __init__(self, root, attempt="s5-run-1-tab-entry-a1", count=0):
        self.root, self.attempt = Path(root), attempt
        self.nonce = NONCE
        self.folder = f"attempts/{attempt}"
        self.device_root = f"{self.folder}/device"
        self.apks = {"target": hashlib.sha256(APKS[dp.TARGET_PACKAGE]).hexdigest(),
                     "test": hashlib.sha256(APKS[dp.TEST_PACKAGE]).hexdigest()}
        self.context = dict(schema=2, attemptId=attempt, runnerNonce=NONCE, bootId=BOOT,
                            targetApkSha256=self.apks["target"], testApkSha256=self.apks["test"])
        self.register = dict(kind="register", schema=2, instanceId=INSTANCE, lifetimeId=LIFE,
                             attemptId=attempt, runnerNonce=NONCE, attemptState="ok", pid=1234,
                             processName=dp.TARGET_PACKAGE, processStartWallMs=1100,
                             userId=0, packageUid=UID, bootId=BOOT)
        self.rows = [dict(self.register, kind="begin")]
        for seq in range(1, count + 1):
            common = dict(schema=2, instanceId=INSTANCE, attemptId=attempt, runnerNonce=NONCE, seq=seq)
            self.rows.extend([dict(common, kind="prepare", line="GET /unexpected"), dict(common, kind="commit")])
        self.registry = [self.register]
        self.summary = summary(count=count)
        self.records = [self.exit_record(self.summary.hex())]
        self.extra_files = {}
        self.raw_overrides = {}
        self.host_overrides = {}
        self.inventory_pre_edit = lambda files: None
        self.inventory_post_edit = lambda files: None
        self.context_readback = None
        self.stream_edits = {}
        self.stderr_bytes = {}
        self.readback_edits = {}
        self.api = b"33\n"
        self.api_code, self.api_timeout = 0, False
        self.absent = False
        self.order = list(ROLES)
        self.command_edit = lambda commands: None
        self.observation_edit = lambda observations: None
        self.proof_edit = lambda proof: None
        self.record_queries = None
        self.state = "zero" if count == 0 else "nonzero"
        self.count = count
        self.defects = []
        self.instances = [dict(instanceId=INSTANCE, lifetimeId=LIFE, commits=count)]
        self.context_bytes = None  # Exact Kotlin bytes when importing a bridge sample.
        self.clock_values = {"install-clock": 1000, "stop-clock": 1800, "after-stop-clock": 2000}
        self.probe_nonces = {}
        self.root.mkdir(parents=True, exist_ok=True)

    def exit_record(self, hex_value, **kwargs):
        return dict(dict(pid=1234, processName=dp.TARGET_PACKAGE, realUid=UID, packageUid=UID,
                         definingUid=UID, timestampMs=1900, reason=10, status=0, summaryHex=hex_value), **kwargs)

    def put(self, relative, data):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return {"path": relative, "sha256": hashlib.sha256(data).hexdigest()}

    def json(self, relative, value):
        return self.put(relative, encoded(value))

    def write(self):
        # Remove only files this generated fixture previously wrote, so omission
        # cases actually omit bytes instead of only shrinking the proof list.
        for item in getattr(self, "file_refs", []):
            (self.root / item["ref"]["path"]).unlink(missing_ok=True)
        context_bytes = self.context_bytes if self.context_bytes is not None else encoded(self.context)
        self.context_ref = self.put(f"{self.folder}/context.json", context_bytes)
        raw = {"attempt.json": context_bytes, "registry.jsonl": b"".join(map(encoded, self.registry)),
               f"instance-{INSTANCE}.jsonl": b"".join(map(encoded, self.rows)), **self.extra_files}
        raw.update(self.raw_overrides)
        raw = {name: data for name, data in raw.items() if data is not None}
        host = {**raw, **self.host_overrides}
        self.file_refs = [{"name": name, "ref": self.put(f"{self.device_root}/{name}", data)}
                          for name, data in sorted(host.items()) if data is not None]
        inventory = [dict(name=name, type="file", size=len(data), sha256=hashlib.sha256(data).hexdigest())
                     for name, data in sorted(raw.items())]
        pre, post = copy.deepcopy(inventory), copy.deepcopy(inventory)
        self.inventory_pre_edit(pre)
        self.inventory_post_edit(post)
        commands, observations = [], []
        self.streams = {}
        query_index = 0
        for role in self.order:
            seq = len(commands) // 2 + 1
            nonce = self.probe_nonces.get(role, f"{seq:032x}")
            op = "inventory" if role.startswith("inventory-") else "exits" if role == "exit-query" else role
            payload = None
            if role == "absent":
                payload = {"exists": self.absent}
            elif role == "inventory-pre":
                payload = {"files": pre}
            elif role == "inventory-post":
                payload = {"files": post}
            elif role == "exit-query":
                records = self.record_queries[min(query_index, len(self.record_queries) - 1)] if self.record_queries else self.records
                payload = {"records": records}
                query_index += 1
            if payload is not None:
                argv = ["adb", "shell", "env", "CLASSPATH=/data/app/base.apk", "app_process", "/system/bin",
                        "com.jay.fxi.s5fixture.S5PurityProbe", op, "0", self.attempt, self.nonce, nonce]
                out = encoded(dict(schema=2, op=op, attemptId=self.attempt, runnerNonce=self.nonce,
                                   commandNonce=nonce, collectorUid=2000, userId=0, payload=payload))
            else:
                argv, out = self.non_probe(role)
            out = self.stream_edits.get(role, lambda raw: raw)(out)
            stdout = self.put(f"{self.folder}/commands/{seq:03d}.stdout", out)
            stderr = self.put(f"{self.folder}/commands/{seq:03d}.stderr", self.stderr_bytes.get(role, b""))
            self.streams.setdefault(role, []).append(stdout)
            commands.extend([
                dict(kind="start", schema=2, attemptId=self.attempt, runnerNonce=self.nonce, seq=seq,
                     op=op, argv=argv, commandNonce=nonce, monoNs=seq * 100_000_000),
                dict(kind="finish", schema=2, attemptId=self.attempt, runnerNonce=self.nonce, seq=seq,
                     monoNs=seq * 100_000_000 + 10, returnCode=self.api_code if role == "device-api" else 0,
                     timedOut=self.api_timeout if role == "device-api" else False, stdout=stdout, stderr=stderr),
            ])
            if role.startswith("apk-"):
                key = "target" if role.endswith("target") else "test"
                record = dict(schema=2,
                    package=dp.TARGET_PACKAGE if key == "target" else dp.TEST_PACKAGE, pathOnDevice="/data/app/base.apk" if key == "target" else "/data/app/test-base.apk",
                    expectedSha256=self.apks[key], actualSha256=self.apks[key],
                    actualSize=len(APKS[dp.TARGET_PACKAGE if key == "target" else dp.TEST_PACKAGE]), matched=True)
                self.readback_edits.get(role, lambda value: None)(record)
                ref = self.json(f"{self.folder}/{role}.json", record)
            else:
                ref = stdout
            seqs = [seq]
            extra_commands = []
            if role == "install":
                extra_commands = [(["adb", "install", "--no-streaming", "artifacts/macrobenchmark-s5Benchmark.apk"], b"Success\n")]
            elif role.startswith("install-identity"):
                extra_commands = [
                    (["adb", "shell", "am", "get-current-user"], b"0\n"),
                    (["adb", "shell", "pm", "path", dp.TARGET_PACKAGE], b"package:/data/app/base.apk\n"),
                    (["adb", "shell", "pm", "path", dp.TEST_PACKAGE], b"package:/data/app/test-base.apk\n"),
                ]
            for argv_extra, out_extra in extra_commands:
                extra_seq = len(commands) // 2 + 1
                out_ref = self.put(f"{self.folder}/commands/{extra_seq:03d}.stdout", out_extra)
                err_ref = self.put(f"{self.folder}/commands/{extra_seq:03d}.stderr", b"")
                commands.extend([
                    dict(kind="start", schema=2, attemptId=self.attempt, runnerNonce=self.nonce, seq=extra_seq,
                         op=role, argv=argv_extra, commandNonce=f"{extra_seq:032x}", monoNs=extra_seq * 100_000_000),
                    dict(kind="finish", schema=2, attemptId=self.attempt, runnerNonce=self.nonce, seq=extra_seq,
                         monoNs=extra_seq * 100_000_000 + 10, returnCode=0, timedOut=False, stdout=out_ref, stderr=err_ref),
                ])
                seqs.append(extra_seq)
            observations.append(dict(role=role, ref=ref, seqs=seqs))
        self.command_edit(commands)
        self.observation_edit(observations)
        self.commands = commands
        command_ref = self.put(f"{self.folder}/commands.jsonl", b"".join(map(encoded, commands)))
        proof = dict(schema=2, attemptId=self.attempt, runnerNonce=self.nonce, context=self.context_ref,
                     apks=self.apks, observations=observations, commands=command_ref,
                     deviceFiles=dict(root=self.device_root, files=self.file_refs), instances=self.instances,
                     result=dict(state=self.state, actualCount=self.count, defects=self.defects))
        self.proof_edit(proof)
        self.proof = proof
        self.proof_ref = self.json(f"{self.folder}/purity.json", proof)
        self.expected = dict(attemptId=self.attempt, runnerNonce=self.nonce, context=self.context_ref)
        return self

    def non_probe(self, role):
        argv = ["adb", "shell"]
        if role == "device-api":
            return argv + ["getprop", "ro.build.version.sdk"], self.api
        if role.startswith("boot-"):
            return argv + ["cat", "/proc/sys/kernel/random/boot_id"], (BOOT + "\n").encode()
        if role in ("install-clock", "stop-clock", "after-stop-clock"):
            value = self.clock_values[role]
            return argv + ["date", "+%s%3N"], f"{value}\n".encode()
        if role == "install":
            return ["adb", "install", "--no-streaming", "artifacts/app-s5Benchmark.apk"], b"Success\n"
        if role.startswith("install-identity"):
            return argv + ["cmd", "package", "list", "packages", "-U", dp.TARGET_PACKAGE], f"package:com.jay.fxi uid:{UID}\n".encode()
        if role == "context-push":
            return ["adb", "push", f"{self.folder}/context.json", "/sdcard/Android/data/com.jay.fxi/files/s5purity/attempt.json"], b"1 file pushed\n"
        if role == "context-readback":
            return argv + ["cat", "/sdcard/Android/data/com.jay.fxi/files/s5purity/attempt.json"], (
                self.context_readback if self.context_readback is not None else encoded(self.context))
        if role.startswith("apk-"):
            return ["adb", "pull", "/data/app/base.apk" if role.endswith("target") else "/data/app/test-base.apk", f"scratch/{role}.apk"], b"1 file pulled\n"
        if role == "instrumentation":
            return argv + ["am", "instrument", "-w", "-e", "s5.runId", self.attempt, dp.INSTRUMENTATION_COMPONENT], b"OK (1 test)\n"
        if role == "force-stop":
            return argv + ["am", "force-stop", "--user", "0", dp.TARGET_PACKAGE], b""
        if role.startswith("live-"):
            return argv + ["ps", "-A", "-o", "USER,PID,NAME"], b"USER PID NAME\n"
        if role == "pull":
            return ["adb", "pull", "/sdcard/Android/data/com.jay.fxi/files/s5purity/.", self.device_root], b"3 files pulled\n"
        raise AssertionError(role)


class ContractTest(unittest.TestCase):
    # Keep the contract prefix first even for unittest's tuple/dict comparisons.
    longMessage = False

    def fixture(self, count=0, attempt="s5-run-1-tab-entry-a1"):
        temp = tempfile.TemporaryDirectory(dir=REPO)
        self.addCleanup(temp.cleanup)
        return ProofFixture(Path(temp.name), attempt, count)

    def invoke(self, sub_id, duty, call):
        try:
            return call()
        except NotImplementedError as error:
            self.fail(f"precondition(D17b/{sub_id}): implementation stub: {error}")
        except Exception as error:
            self.fail(f"{prefix(sub_id, duty)}: {type(error).__name__}: {error}")

    def read(self, f, sub_id, duty):
        value = self.invoke(sub_id, duty, lambda: gate.read_purity_proof(f.proof_ref, f.root, f.expected, f.apks))
        for defect in value.defects:
            self.assertEqual({"check", "code"}, set(defect), prefix(sub_id, "defect-exact-keys"))
            self.assertIn(defect["check"], DEFECT_CODES, prefix(sub_id, "fixed-check"))
            self.assertIn(defect["code"], DEFECT_CODES[defect["check"]], prefix(sub_id, "fixed-sub-ID"))
        return value

    def positive(self, f, sub_id, state="zero", count=0):
        f.write()
        value = self.read(f, sub_id, "positive-C1-C8")
        self.assertEqual((state, count, []), (value.state, value.actual_count, list(value.defects)),
                         prefix(sub_id, "positive-C1-C8"))
        self.assertEqual(f.instances, list(value.instances), prefix(sub_id, "raw-instance-count"))
        return value

    def defect(self, sub_id, check, edit, count=0):
        f = self.fixture(count)
        self.positive(f, sub_id, "nonzero" if count else "zero", count)
        edit(f)
        f.write()
        result = self.read(f, sub_id, check)
        self.assertEqual(("incomplete", None), (result.state, result.actual_count), prefix(sub_id, check))
        self.assertIn(check, [d["check"] for d in result.defects], prefix(sub_id, check + "-diagnostic"))


def register_case(cls, sub_id, row, duty, body, mutation="input-only"):
    name = "test_" + sub_id.replace("-", "_")
    if hasattr(cls, name):
        raise ValueError(f"duplicate sub-ID {sub_id}")
    body.__name__ = name
    body.__doc__ = f"{prefix(sub_id, duty)}; {row}; {mutation}"
    setattr(cls, name, body)
    CASES.append(dict(sub_id=sub_id, test=f"{cls.__name__}.{name}", row=row, duty=duty, mutation=mutation))


class ProofTest(ContractTest):
    pass


def defect_case(sub_id, check, edit, count=0, row="D17b-defects", mutation="input-only"):
    register_case(ProofTest, sub_id, row, check,
                  lambda self: self.defect(sub_id, check, edit, count), mutation)


def row_edit(index, key, value):
    return lambda f: f.rows[index].__setitem__(key, value)


def record_edit(key, value):
    return lambda f: f.records[0].__setitem__(key, value)


def command_edit(index, key, value):
    return lambda f: setattr(f, "command_edit", lambda c: c[index].__setitem__(key, value))


def proof_edit(key, value):
    return lambda f: setattr(f, "proof_edit", lambda p: p.__setitem__(key, value))


for _count in (0, 1, 70):
    register_case(ProofTest, f"raw-count-{_count}", "D17b-defects", "C5-C6",
                  lambda self, n=_count: self.positive(self.fixture(n), f"raw-count-{n}", "nonzero" if n else "zero", n))

# r3: every negative starts with a complete positive and rebinds all raw hashes.
# Exercise both the collector seam (no result comparison) and the public reader.
class RawFieldTypeTest(ContractTest):
    pass


def raw_field_type_case(sub_id, check, code, edit, count=0):
    def body(self):
        f = self.fixture(count)
        self.positive(f, sub_id, "nonzero" if count else "zero", count)
        edit(f)
        f.write()
        public = self.read(f, sub_id, "typed-public-reader")
        collected = self.invoke(sub_id, "typed-collector-reader", lambda: gate._read_purity_inputs(
            f.proof, f.root, f.expected, f.apks, compare_result=False))
        for result in (public, collected):
            self.assertEqual(("incomplete", None), (result.state, result.actual_count), prefix(sub_id, "incomplete-null"))
            self.assertIn({"check": check, "code": code}, result.defects, prefix(sub_id, "fixed-type-diagnostic"))
        # A self-consistent incomplete claim must retain the underlying diagnostic.
        f.proof["result"] = dict(state="incomplete", actualCount=None, defects=list(collected.defects))
        f.proof_ref = f.json(f"{f.folder}/purity.json", f.proof)
        rebound = self.read(f, sub_id, "rebound-incomplete-claim")
        self.assertEqual(("incomplete", None, list(collected.defects)),
                         (rebound.state, rebound.actual_count, list(rebound.defects)), prefix(sub_id, "raw-defects-preserved"))
    register_case(RawFieldTypeTest, sub_id, "D17b-defects", check + "/" + code, body)


# All JSON types that differ from the expected field type, including unhashable
# arrays/objects, bool-as-int, and numbers encoded as strings. Nullable producer
# attribution remains a C4 diagnostic under the locked grammar.
_RAW_TYPE_VALUES = (("null", None), ("array", []), ("object", {}), ("bool", True),
                    ("int", 1), ("float", 1.5), ("string", "1500"))
_RAW_REG_STRINGS = {"kind", "instanceId", "lifetimeId", "attemptId", "runnerNonce", "attemptState", "processName", "bootId"}
_RAW_REG_FIELDS = ("kind", "schema", "instanceId", "lifetimeId", "attemptId", "runnerNonce",
                   "attemptState", "pid", "processName", "processStartWallMs", "userId", "packageUid", "bootId")
for _scope in ("register", "begin"):
    for _field in _RAW_REG_FIELDS:
        for _label, _value in _RAW_TYPE_VALUES:
            if (_field in _RAW_REG_STRINGS and _label == "string") or (_field not in _RAW_REG_STRINGS and _label == "int"):
                continue
            _check, _code = ("C4", "attribution") if _field in {"attemptId", "runnerNonce", "bootId"} and _value is None else ("C2", "json-schema")
            def _edit(f, scope=_scope, field=_field, value=_value):
                (f.register if scope == "register" else f.rows[0])[field] = copy.deepcopy(value)
            raw_field_type_case(f"raw-type-{_scope}-{_field}-{_label}", _check, _code, _edit)
        raw_field_type_case(f"raw-missing-{_scope}-{_field}", "C2", "json-schema",
                            lambda f, scope=_scope, field=_field: (f.register if scope == "register" else f.rows[0]).pop(field))

for _index, _kind in ((1, "prepare"), (2, "commit")):
    for _field in ("kind", "schema", "instanceId", "attemptId", "runnerNonce", "seq") + (("line",) if _kind == "prepare" else ()):
        for _label, _value in _RAW_TYPE_VALUES:
            _integer = _field in {"schema", "seq"}
            if (_integer and _label == "int") or (not _integer and _label == "string"):
                continue
            _check, _code = ("C5", "sequence") if _field == "seq" else ("C2", "json-schema")
            raw_field_type_case(f"raw-type-{_kind}-{_field}-{_label}", _check, _code,
                                row_edit(_index, _field, _value), count=1)

for _field in ("pid", "processName", "realUid", "packageUid", "definingUid", "timestampMs", "reason", "status", "summaryHex"):
    for _label, _value in _RAW_TYPE_VALUES:
        if (_field in {"processName", "summaryHex"} and _label == "string") or (_field not in {"processName", "summaryHex"} and _label == "int"):
            continue
        _code = "record-time" if _field == "timestampMs" else "summary-codec" if _field == "summaryHex" else "record-identity"
        if _field == "summaryHex" and _value is None:
            _code = "record-set"
        raw_field_type_case(f"raw-type-record-{_field}-{_label}", "C6", _code, record_edit(_field, _value))
    raw_field_type_case(f"raw-missing-record-{_field}", "C6", "record-set",
                        lambda f, field=_field: f.records[0].pop(field))

for _field in ("instanceId", "lifetimeId", "commits"):
    for _label, _value in _RAW_TYPE_VALUES:
        if (_field == "commits" and _label == "int") or (_field != "commits" and _label == "string"):
            continue
        raw_field_type_case(f"raw-type-proof-instance-{_field}-{_label}", "C3", "instance-set",
                            lambda f, field=_field, value=_value: f.instances[0].__setitem__(field, copy.deepcopy(value)))

for _label, _value in _RAW_TYPE_VALUES:
    if _label != "object":
        raw_field_type_case(f"raw-type-register-row-{_label}", "C2", "registry",
                            lambda f, value=_value: f.registry.__setitem__(0, copy.deepcopy(value)))
        raw_field_type_case(f"raw-type-begin-row-{_label}", "C2", "json-schema",
                            lambda f, value=_value: f.rows.__setitem__(0, copy.deepcopy(value)))
        raw_field_type_case(f"raw-type-record-row-{_label}", "C6", "record-set",
                            lambda f, value=_value: f.records.__setitem__(0, copy.deepcopy(value)))
        raw_field_type_case(f"raw-type-proof-instance-row-{_label}", "C3", "instance-set",
                            lambda f, value=_value: f.instances.__setitem__(0, copy.deepcopy(value)))
    if _label != "array":
        raw_field_type_case(f"raw-type-proof-instances-{_label}", "C3", "instance-set", proof_edit("instances", _value))


# The two review counterexamples and partial-registry continuation are named
# separately so reviewers can run them without the complete type matrix.
raw_field_type_case("registry-instanceId-unhashable", "C2", "registry",
                    lambda f: f.register.__setitem__("instanceId", []))
raw_field_type_case("registry-processStartWallMs-string", "C2", "json-schema",
                    lambda f: f.register.__setitem__("processStartWallMs", "1500"))


def registry_invalid_then_valid(self):
    sub = "registry-invalid-then-valid"
    f = self.fixture()
    self.positive(f, sub)
    invalid = dict(f.register, instanceId=[])
    f.registry.insert(0, invalid)
    f.write()
    value = self.read(f, sub, "continue-after-invalid-row")
    self.assertEqual(("incomplete", None), (value.state, value.actual_count), prefix(sub, "incomplete-null"))
    self.assertIn({"check": "C2", "code": "registry"}, value.defects, prefix(sub, "fixed-diagnostic"))
    self.assertEqual(f.instances, list(value.instances), prefix(sub, "valid-population-retained"))


register_case(RawFieldTypeTest, "registry-invalid-then-valid", "D17b-defects", "C2/registry", registry_invalid_then_valid)


# Invalid records must be diagnosed even when their summary is null or belongs
# to another lifetime; matching a separate valid record cannot hide bad types.
for _field, _code in (("reason", "record-identity"), ("status", "record-identity"), ("timestampMs", "record-time")):
    for _summary_name, _hex in (("null", None), ("unrelated", summary(instance="55" * 16).hex())):
        raw_field_type_case(f"raw-type-extra-record-{_summary_name}-{_field}", "C6", _code,
                            lambda f, field=_field, h=_hex: f.records.append(f.exit_record(h, **{field: []})))

for _field in ("instanceId", "lifetimeId", "commits"):
    raw_field_type_case(f"raw-missing-proof-instance-{_field}", "C3", "instance-set",
                        lambda f, field=_field: f.instances[0].pop(field))


# C1: standalone attribution and installation. Every edit retains valid outer hashes.
for _key, _value in (("schema", 3), ("attemptId", "old-attempt"), ("runnerNonce", "00" * 16),
                     ("bootId", "bad-boot"), ("targetApkSha256", "aa" * 32), ("testApkSha256", "bb" * 32)):
    defect_case(f"context-{_key}", "C1", lambda f, k=_key, v=_value: f.context.__setitem__(k, v))
defect_case("context-readback-bytes", "C1", lambda f: setattr(f, "context_readback", b"{}\n"))
defect_case("installed-folder-present", "C1", lambda f: setattr(f, "absent", True), mutation="μ39-h")
defect_case("proof-apk-mismatch", "C1", lambda f: setattr(f, "proof_edit", lambda p: p["apks"].__setitem__("target", "aa" * 32)))
defect_case("proof-attempt-mismatch", "C1", proof_edit("attemptId", "old-attempt"))
defect_case("proof-nonce-mismatch", "C1", proof_edit("runnerNonce", "aa" * 16), mutation="μ39-g1")

# C2: exact grammar, JSON types and complete registration population.
defect_case("empty-registry", "C2", lambda f: f.registry.clear())
defect_case("duplicate-register", "C2", lambda f: f.registry.append(dict(f.register)))
defect_case("begin-not-first", "C2", lambda f: f.rows.insert(0, dict(f.rows[-1])), count=1)
defect_case("duplicate-begin", "C2", lambda f: f.rows.append(dict(f.rows[0])))
for _name, _raw in (
    ("duplicate-key", b'{"kind":"register","kind":"register"}\n'),
    ("unknown-key", None), ("unknown-kind", b'{"kind":"mystery"}\n'),
    ("nonfinite", b'{"kind":"register","schema":NaN}\n'),
    ("truncated", b'{"kind":"register"'), ("blank-line", b'\n'),
    ("invalid-utf8", b'\xff\n'), ("schema-bool", b'{"schema":true}\n'),
):
    if _name == "unknown-key":
        _edit = lambda f: f.register.__setitem__("extra", 1)
    else:
        _edit = lambda f, raw=_raw: f.raw_overrides.__setitem__("registry.jsonl", raw)
    defect_case(f"json-{_name}", "C2", _edit)

# C3: file set and identity projection are independent from physical bytes.
for _name in ("attempt.json", "registry.jsonl", f"instance-{INSTANCE}.jsonl"):
    defect_case(f"host-missing-{_name.split('.')[0]}", "C3", lambda f, n=_name: f.host_overrides.__setitem__(n, None), mutation="μ39-e")
defect_case("host-complete-last-line-missing", "C3", lambda f: f.host_overrides.__setitem__(
    f"instance-{INSTANCE}.jsonl", b"".join(map(encoded, f.rows[:-1]))), count=1, mutation="μ39-f")
defect_case("host-registry-suffix-missing", "C3", lambda f: f.host_overrides.__setitem__("registry.jsonl", b""))
defect_case("host-same-size-other-bytes", "C3", lambda f: f.host_overrides.__setitem__(
    f"instance-{INSTANCE}.jsonl", encoded(f.rows[0]).replace(b'"pid":1234', b'"pid":1235')), mutation="μ39-f")
defect_case("file-extra", "C3", lambda f: f.extra_files.__setitem__("unexpected.txt", b"unexpected\n"))
defect_case("file-alias", "C3", lambda f: f.extra_files.__setitem__(f"instance-{'AA' * 16}.jsonl", b"alias\n"))
defect_case("file-nested", "C3", lambda f: f.extra_files.__setitem__("nested/unexpected.jsonl", b"{}\n"))
defect_case("file-name-id-mismatch", "C3", lambda f: f.raw_overrides.update({
    f"instance-{INSTANCE}.jsonl": None, f"instance-{'55' * 16}.jsonl": encoded(f.rows[0])}))
defect_case("begin-register-mismatch", "C3", row_edit(0, "pid", 1235))
defect_case("proof-instance-list-shrunk", "C3", lambda f: setattr(f, "instances", []), mutation="μ40-f")
defect_case("inventory-pre-missing", "C3", lambda f: setattr(f, "inventory_pre_edit", lambda files: files.pop()))
defect_case("inventory-post-size", "C3", lambda f: setattr(f, "inventory_post_edit", lambda files: files[0].__setitem__("size", 999)))
defect_case("inventory-post-hash", "C3", lambda f: setattr(f, "inventory_post_edit", lambda files: files[0].__setitem__("sha256", "aa" * 32)))
defect_case("inventory-symlink", "C3", lambda f: setattr(f, "inventory_pre_edit", lambda files: files[0].__setitem__("type", "symlink")))

# C4: attemptState belongs only to begin/register; p/c carry the common four fields.
for _state in ("missing", "invalid", "unknown"):
    def _null_context(f, state=_state):
        for row in (f.register, f.rows[0]):
            row.update(attemptState=state, attemptId=None, runnerNonce=None, bootId=None)
    defect_case(f"attempt-state-{_state}", "C4", _null_context)
for _index, _kind in ((1, "prepare"), (2, "commit")):
    for _key, _value in (("schema", 1), ("instanceId", "55" * 16), ("attemptId", "old-attempt"), ("runnerNonce", "aa" * 16)):
        defect_case(f"{_kind}-{_key}", "C4", row_edit(_index, _key, _value), count=1,
                    mutation="μ39-g2" if _key == "attemptId" else "input-only")

# C5: exact alternation, not merely equal p/c counts.
for _name, _seq in (("bool", True), ("float", 1.0), ("zero", 0), ("negative", -1), ("overflow", MAX + 1), ("gap", 2)):
    defect_case(f"seq-{_name}", "C5", row_edit(1, "seq", _seq), count=1, mutation="μ39-j")
defect_case("seq-exponent", "C5", lambda f: f.raw_overrides.__setitem__(f"instance-{INSTANCE}.jsonl",
    b"".join(map(encoded, f.rows)).replace(b'"seq":1', b'"seq":1e0')), count=1)
defect_case("seq-duplicate", "C5", lambda f: f.rows.extend(copy.deepcopy(f.rows[1:])), count=1)
defect_case("seq-reversed", "C5", lambda f: f.rows.__setitem__(slice(1, 3), [f.rows[2], f.rows[1]]), count=1)
defect_case("seq-crossed", "C5", lambda f: f.rows.__setitem__(slice(1, 5), [f.rows[1], f.rows[3], f.rows[2], f.rows[4]]), count=2)
defect_case("seq-prepare-only", "C5", lambda f: f.rows.pop(), count=1)
defect_case("seq-commit-only", "C5", lambda f: f.rows.pop(1), count=1)

# C6: OS bytes are not inferred from PID, time, registry or the proof summary.
for _name, _offset, _byte in (("magic", 0, 0), ("version", 8, 2), ("reserved", 10, 1),
                              ("state", 9, 3), ("nonce", 12, 255), ("lifetime", 28, 0), ("instance", 44, 0)):
    def _corrupt(f, offset=_offset, byte=_byte):
        raw = bytearray(f.summary)
        raw[offset] = byte
        f.records[0]["summaryHex"] = raw.hex()
    defect_case(f"summary-{_name}", "C6", _corrupt, mutation="μ39-n")
for _name, _value in (("null", None), ("short", summary()[:-1].hex()), ("long", summary().hex() + "00"),
                      ("nonhex", "zz" * 64), ("uppercase", summary().hex().upper()),
                      ("busy", summary(1).hex()), ("poison", summary(2).hex()),
                      ("overflow", summary(count=MAX + 1).hex()), ("count-mismatch", summary(count=1).hex())):
    defect_case(f"summary-{_name}", "C6", record_edit("summaryHex", _value), mutation="μ39-m" if _name == "count-mismatch" else "μ39-n")
for _key, _value in (("pid", 999), ("processName", "other"), ("packageUid", UID + 1), ("realUid", UID + 1),
                     ("definingUid", UID + 1), ("timestampMs", 1099)):
    defect_case(f"record-{_key}", "C6", record_edit(_key, _value))
defect_case("record-time-after-final", "C6", record_edit("timestampMs", 2001))
defect_case("record-missing", "C6", lambda f: f.records.clear())
defect_case("record-duplicate", "C6", lambda f: f.records.append(dict(f.records[0], timestampMs=1901)))
defect_case("record-ring-overflow", "C6", lambda f: setattr(f, "records", [f.exit_record(None, pid=5000 + i) for i in range(64)]))
defect_case("record-delay-exhausted", "C6", lambda f: setattr(f, "record_queries", [[], [], []]))

# C7: each disallowed reason/status is independent from a BUSY rejection.
for _reason, _status in ((1, 86), (1, 87), (1, 88), (4, 0), (5, 0), (6, 0), (7, 0), (0, 0), (99, 0), (10, 1), (2, 0)):
    defect_case(f"exit-{_reason}-{_status}", "C7", lambda f, r=_reason, s=_status: f.records[0].update(reason=r, status=s), mutation="μ39-d")
for _reason, _status in ((10, 0), (11, 0), (2, 9), (2, 15)):
    def _allowed(self, r=_reason, s=_status):
        f = self.fixture()
        f.records[0].update(reason=r, status=s)
        self.positive(f, f"exit-allowed-{r}-{s}")
    register_case(ProofTest, f"exit-allowed-{_reason}-{_status}", "D17b-defects", "C7", _allowed)

# C8: original transcript, not re-summarized stage booleans.
for _name, _edit in (
    ("start-missing", lambda c: c.pop(0)), ("finish-missing", lambda c: c.pop(1)),
    ("seq-gap", lambda c: c[2].__setitem__("seq", 9)),
    ("op", lambda c: c[2].__setitem__("op", "inventory")),
    ("argv", lambda c: c[2].__setitem__("argv", ["adb", "shell", "true"])),
    ("nonce", lambda c: c[2].__setitem__("runnerNonce", "aa" * 16)),
    ("command-nonce", lambda c: next(e for e in c if e["kind"] == "start" and e["op"] == "absent").__setitem__("commandNonce", "aa" * 16)),
    ("time-reversed", lambda c: c[1].__setitem__("monoNs", 0)),
    ("overlap", lambda c: c[2].__setitem__("monoNs", c[1]["monoNs"] - 1)),
    ("timeout", lambda c: c[3].update(timedOut=True, returnCode=None)),
    ("return-code", lambda c: c[3].__setitem__("returnCode", 1)),
    ("stdout-missing", lambda c: c[3].pop("stdout")),
    ("stderr-missing", lambda c: c[3].pop("stderr")),
):
    defect_case(f"command-{_name}", "C8", lambda f, e=_edit: setattr(f, "command_edit", e), mutation="μ39-l")
for _role in ("boot-after", "install-identity-after", "live-final"):
    defect_case(f"final-{_role}-missing", "C8", lambda f, r=_role: f.order.remove(r))

# B1 is kept separate from prepare-only: raw is begin-only and all bytes agree.
for _state in (1, 2):
    defect_case(f"B1-zero-byte-prepare-state-{_state}", "C6", record_edit("summaryHex", summary(_state).hex()),
                row="D17-bridge / D17a-B1 Python", mutation="μ39-c2")
defect_case("B1-classified-before-prepare-kill", "C6", record_edit("summaryHex", summary(1).hex()),
            row="D17a-B1 Python", mutation="μ39-c2")

# B2: B passes PID/name/reason/time but cannot stand in for missing registered A.
for _name, _hex in (("null", None), ("other-lifetime", summary(life="55" * 16, instance="66" * 16).hex())):
    defect_case(f"B2-unregistered-reused-pid-{_name}", "C6", record_edit("summaryHex", _hex),
                row="D17b-B2", mutation="μ39-c1")


def add_second_lifetime(f):
    life, instance = "55" * 16, "66" * 16
    reg = dict(f.register, lifetimeId=life, instanceId=instance, processStartWallMs=1950)
    f.registry.append(reg)
    f.extra_files[f"instance-{instance}.jsonl"] = encoded(dict(reg, kind="begin"))
    f.records.append(f.exit_record(summary(life=life, instance=instance).hex(), timestampMs=1990))
    f.instances.append(dict(instanceId=instance, lifetimeId=life, commits=0))


def b2_positive(self):
    f = self.fixture()
    add_second_lifetime(f)
    self.positive(f, "B2-registered-reused-pid")


register_case(ProofTest, "B2-registered-reused-pid", "D17b-B2", "C6", b2_positive)
register_case(ProofTest, "B2-unregistered-failure-no-contribution", "D17b-B2", "C6",
              lambda self: self.positive_unregistered())


def positive_unregistered(self):
    f = self.fixture()
    f.records.append(f.exit_record(None, pid=999, reason=1, status=87))
    self.positive(f, "B2-unregistered-failure-no-contribution")


ProofTest.positive_unregistered = positive_unregistered


def early_inventory(f):
    # These three commands really precede stop in the raw transcript; no fake summary.
    for role in ("inventory-pre", "pull", "inventory-post"):
        f.order.remove(role)
    index = f.order.index("stop-clock")
    f.order[index:index] = ["inventory-pre", "pull", "inventory-post"]


defect_case("B3-lists-pull-before-late-write-stop", "C8", early_inventory, row="D17b-B3", mutation="μ39-b/l")
defect_case("B3-write-between-pull-post", "C3", lambda f: setattr(f, "inventory_post_edit", lambda files: files[-1].update(size=999)),
            row="D17b-B3", mutation="μ39-i")


def delayed_positive(self):
    f = self.fixture()
    index = f.order.index("exit-query")
    f.order[index:index] = ["exit-query"]
    f.record_queries = [[], f.records]
    self.positive(f, "exit-delay-success")


register_case(ProofTest, "exit-delay-success", "D17b-defects", "C6-C8", delayed_positive, "μ39-k")

# API: these are validator inputs; real runner/observer cases are below.
for _label, _raw in (("29", b"29\n"), ("empty", b""), ("multiple", b"30\n32\n"),
                     ("text", b"thirty\n"), ("decimal", b"30.0\n"), ("space", b" 30\n"),
                     ("extra-lf", b"30\n\n"), ("missing-lf", b"30")):
    defect_case(f"api-validator-{_label}", "C1", lambda f, raw=_raw: setattr(f, "api", raw), row="D17b-api", mutation="μ39-p2")
for _level in (30, 32, 33, 36):
    def _api_positive(self, level=_level):
        f = self.fixture()
        f.api = f"{level}\n".encode()
        self.positive(f, f"api-validator-{level}")
    register_case(ProofTest, f"api-validator-{_level}", "D17b-api", "C1-C8", _api_positive)
for _label, _edit in (
    ("role-missing", lambda f: setattr(f, "observation_edit", lambda obs: obs.pop(1))),
    ("role-forged", lambda f: setattr(f, "observation_edit", lambda obs: obs[1].__setitem__("role", "absent"))),
    ("failure-then-run", lambda f: setattr(f, "api_code", 1)),
    ("timeout-then-run", lambda f: (setattr(f, "api_timeout", True), setattr(f, "api_code", None))),
    ("late", lambda f: (f.order.remove("device-api"), f.order.insert(10, "device-api"))),
):
    defect_case(f"api-validator-{_label}", "C1", _edit, row="D17b-api", mutation="μ39-p2")


class ScriptedAdb:
    """Only transport is replaced. DeviceOps.observe must write the real envelope.

    Calls are recorded independently of commands.jsonl, so an observer which
    reorders or invents a command cannot satisfy the transcript comparison.
    """

    def __init__(self, device):
        self.device, self.serial = device, "R5TESTSERIAL"
        self.calls = []

    def run(self, *argv, check=False, timeout=None):
        self.calls.append(tuple(argv))
        d = self.device
        op = d.pending_op
        if d.fail_op == op or (d.fail_op == "stop" and op == "force-stop") or (d.fail_op == "live" and op == "live-after-stop") or (d.fail_op == "final" and op == "live-final"):
            raise dp.PreflightError(f"scripted {op} failure")
        if op == "instrumentation":
            result = FakeDevice.instrument(d, d.instrument_method, d.attempt_id)
            if d.fail_op == "summary-throttle":
                result.stdout += "\nD17_SUMMARY_THROTTLE admission RuntimeException fatal(88)\n"
            d.last_instrumentation = result
            return result
        if op == "install":
            FakeDevice.install(d, Path(argv[-1]))
            return subprocess.CompletedProcess(argv, 0, "Success\n", "")
        if op == "device-api":
            if d.api_timeout:
                raise subprocess.TimeoutExpired(argv, timeout, output=b"", stderr=b"api timeout")
            data = d.api_raw
            return subprocess.CompletedProcess(argv, d.api_code, data.decode(), "")
        if op in ("absent", "inventory", "exits"):
            nonce = argv[-1]
            if op == "absent":
                payload = {"exists": False}
            elif op == "inventory":
                payload = {"files": [dict(name=n, type="file", size=len(b), sha256=hashlib.sha256(b).hexdigest())
                                     for n, b in sorted(d.device_files.items())]}
            else:
                d.exit_queries += 1
                payload = {"records": [] if d.exit_queries <= d.exit_delay else d.exit_records}
            data = encoded(dict(schema=2, op=op, attemptId=d.attempt_id, runnerNonce=d.nonce,
                                commandNonce=nonce, collectorUid=2000, userId=0, payload=payload))
        elif op == "context-push":
            d.context_bytes = Path(argv[-2]).read_bytes()
            d.make_device_files()
            data = b"1 file pushed\n"
        elif op == "context-readback":
            data = d.context_bytes
        elif op in ("live-after-stop", "live-final"):
            data = b"USER PID NAME\n"
        elif op in ("boot-before", "boot-after"):
            data = (BOOT + "\n").encode()
        elif op in ("install-clock", "stop-clock", "after-stop-clock"):
            data = {"install-clock": b"1000\n", "stop-clock": b"1800\n", "after-stop-clock": b"2000\n"}[op]
        elif op.startswith("install-identity"):
            if tuple(argv[-2:]) == ("am", "get-current-user"):
                data = b"0\n"
            elif "path" in argv and "pm" in argv:
                data = b"package:/data/app/base.apk\n" if argv[-1] == dp.TARGET_PACKAGE else b"package:/data/app/test-base.apk\n"
            else:
                data = f"package:com.jay.fxi uid:{UID}\n".encode()
        elif op.startswith("apk-"):
            package, destination = d.pending_apk
            if d._code() == "r":
                return subprocess.CompletedProcess(argv, 1, "", "read-back denied")
            data_bytes = d.installed[package]
            destination.write_bytes(data_bytes[:3] if d._code() == "h" else data_bytes)
            return subprocess.CompletedProcess(argv, 1 if d._code() == "h" else 0, "1 file pulled\n", "")
        elif op == "pull":
            destination = Path(argv[-1])
            destination.mkdir(parents=True, exist_ok=True)
            for name, raw in d.device_files.items():
                (destination / name).write_bytes(raw)
            data = b"3 files pulled\n"
        else:
            data = b""
        return subprocess.CompletedProcess(argv, 0, data.decode(), "")


class D17FakeDevice(FakeDevice, runner.DeviceOps):
    """Extends the existing fake without changing any legacy test expectations.

    D17 operations pass through inherited DeviceOps.observe; the transport alone
    supplies bytes. This fake does not synthesize a commands.jsonl or purity.json.
    """

    def __init__(self, plan, *, state="zero", fail_op=None, api=b"33\n", api_code=0, api_timeout=False, exit_delay=0):
        FakeDevice.__init__(self, plan)
        # FakeDevice.serial is an attribute; DeviceOps.serial is a read-only property.
        self.state, self.fail_op = state, fail_op
        self.api_raw, self.api_code, self.api_timeout = api, api_code, api_timeout
        self.pending_op = None
        self.adb = ScriptedAdb(self)
        self.attempt_id, self.nonce = None, None
        self.context_bytes = b""
        self.device_files, self.exit_records = {}, []
        self.inventory_calls = 0
        self.exit_delay, self.exit_queries = exit_delay, 0
        self.apk_readbacks = {}
        self.pending_apk = None
        self.observed_ops = []

    @property
    def serial(self):
        return "R5TESTSERIAL"

    @serial.setter
    def serial(self, value):
        self.fake_serial = value

    def begin_purity_observation(self, artifact_root, folder, attempt_id, runner_nonce):
        self.attempt_id, self.nonce = attempt_id, runner_nonce
        self.apk_readbacks = {}
        return runner.DeviceOps.begin_purity_observation(self, artifact_root, folder, attempt_id, runner_nonce)

    def observed(self, op, argv, timeout=10):
        self.pending_op = op
        self.observed_ops.append(op)
        return self.observe(runner.PurityCommand(op, tuple(argv), os.urandom(16).hex(), timeout))

    def device_api(self):
        return self.observed("device-api", ["shell", "getprop", "ro.build.version.sdk"])

    def purity_probe(self, op, *, base_apk, user_id, attempt_id, runner_nonce):
        self.attempt_id, self.nonce = attempt_id, runner_nonce
        nonce = os.urandom(16).hex()
        self.pending_op = op
        self.observed_ops.append(op)
        argv = ("shell", "env", f"CLASSPATH={base_apk}", "app_process", "/system/bin",
                "com.jay.fxi.s5fixture.S5PurityProbe", op, str(user_id), attempt_id, runner_nonce, nonce)
        return self.observe(runner.PurityCommand(op, argv, nonce, 10))

    def push_purity_context(self, context):
        return self.observed("context-push", ["push", str(context), "/sdcard/Android/data/com.jay.fxi/files/s5purity/attempt.json"])

    def read_purity_context(self, destination):
        observation = self.observed("context-readback", ["shell", "cat", "/sdcard/Android/data/com.jay.fxi/files/s5purity/attempt.json"])
        destination.write_bytes(self.context_bytes)
        return observation

    def pull_purity_files(self, destination):
        return self.observed("pull", ["pull", "/sdcard/Android/data/com.jay.fxi/files/s5purity/.", str(destination)])

    def purity_force_stop(self, user_id):
        return self.observed("force-stop", ["shell", "am", "force-stop", "--user", str(user_id), dp.TARGET_PACKAGE])

    def purity_live(self, phase):
        return self.observed(phase, ["shell", "ps", "-A", "-o", "USER,PID,NAME"])

    def purity_identity(self, phase):
        first = self.observed(phase, ["shell", "cmd", "package", "list", "packages", "-U", dp.TARGET_PACKAGE])
        self.observed(phase, ["shell", "am", "get-current-user"])
        for package in (dp.TARGET_PACKAGE, dp.TEST_PACKAGE):
            self.observed(phase, ["shell", "pm", "path", package])
        return first  # Collector binds the phase's full seq set, not just this primary stdout.

    def pull_installed(self, package, destination, expected):
        number = self.apk_readbacks.get(package, 0)
        self.apk_readbacks[package] = number + 1
        phase = "before" if number == 0 else "after"
        key = "target" if package == dp.TARGET_PACKAGE else "test"
        device_path = "/data/app/base.apk" if key == "target" else "/data/app/test-base.apk"
        destination.parent.mkdir(parents=True, exist_ok=True)
        self.pending_apk = package, destination
        observation = self.observed(f"apk-{phase}-{key}", ["pull", device_path, str(destination)])
        if observation.timed_out or observation.return_code != 0:
            raise dp.PreflightError(f"cannot read back installed {package} APK")
        if dp.stable_file_identity(destination, expected["path"]) != expected:
            raise dp.PreflightError(f"installed {package} APK differs from staged bytes")

    def purity_clock(self, phase):
        return self.observed(phase, ["shell", "date", "+%s%3N"])

    def purity_boot_id(self, phase):
        return self.observed(phase, ["shell", "cat", "/proc/sys/kernel/random/boot_id"])

    def install(self, apk):
        self.observed("install", ["install", "--no-streaming", str(apk)])

    def instrument(self, method, attempt_id):
        self.instrument_method, self.attempt_id = method, attempt_id
        self.observed("instrumentation", ["shell", "am", "instrument", "-w", "-e", "s5.runId", attempt_id,
                                           dp.INSTRUMENTATION_COMPONENT], runner.INSTRUMENT_TIMEOUT_SECONDS)
        return self.last_instrumentation

    def make_device_files(self):
        context = json.loads(self.context_bytes)
        count = 1 if self.state == "nonzero" else 0
        register = dict(kind="register", schema=2, instanceId=INSTANCE, lifetimeId=LIFE,
                        attemptId=context["attemptId"], runnerNonce=context["runnerNonce"], attemptState="ok",
                        pid=1234, processName=dp.TARGET_PACKAGE, processStartWallMs=1100,
                        userId=0, packageUid=UID, bootId=context["bootId"])
        rows = [dict(register, kind="begin")]
        if count:
            fields = dict(schema=2, instanceId=INSTANCE, attemptId=context["attemptId"], runnerNonce=context["runnerNonce"], seq=1)
            rows += [dict(fields, kind="prepare", line="GET /unexpected"), dict(fields, kind="commit")]
        self.device_files = {"attempt.json": self.context_bytes, "registry.jsonl": encoded(register),
                             f"instance-{INSTANCE}.jsonl": b"".join(map(encoded, rows))}
        raw_summary = summary(1 if self.state == "incomplete" else 0, count, nonce=context["runnerNonce"])
        throttle = self.fail_op in ("summary-throttle", "summary-throttle-mixed")
        self.exit_records = [dict(pid=1234, processName=dp.TARGET_PACKAGE, realUid=UID, packageUid=UID,
                                  definingUid=UID, timestampMs=1900, reason=1 if throttle else 10,
                                  status=88 if throttle else 0, summaryHex=raw_summary.hex())]
        if self.fail_op == "summary-throttle-mixed":
            # A process that never published a summary comes first; it must not hide the fatal(88) record.
            self.exit_records.insert(0, dict(self.exit_records[0], pid=1233, timestampMs=1800, reason=10, status=0,
                                             summaryHex=None))


class PurityHarness(RunnerHarness):
    def __init__(self, test, plan=None, **options):
        super().__init__(test, plan or ALL_VALID)
        self.device = D17FakeDevice(plan or ALL_VALID, **options)
        self.device.on_pin = lambda: setattr(self, "ledger_lines_at_pin", self.lines())



class RunTest(ContractTest):
    def run_case(self, sub_id, *, state="zero", fail_op=None, plan=None, expected_status="complete", expected_code=0):
        h = PurityHarness(self, plan, state=state, fail_op=fail_op)
        code, report = self.invoke(sub_id, "real-runner", h.run)
        self.assertEqual((expected_code, expected_status), (code, report.get("status")), prefix(sub_id, "classification"))
        self.assertEqual({}, h.device.installed, prefix(sub_id, "cleanup-packages"))
        self.assertEqual({}, h.device.outputs, prefix(sub_id, "cleanup-output"))
        self.assertIsNone(h.device.journal, prefix(sub_id, "cleanup-settings"))
        if state != "zero" or (fail_op and fail_op not in ("install", "context-push", "context-readback")):
            starts = [e for e in h.entries() if e["type"] == "attempt-start"]
            self.assertEqual(1, len(starts), prefix(sub_id, "terminal-no-retry"))
        if fail_op in ("stop", "live", "inventory", "pull", "exits", "final") or state != "zero":
            self.assertIn("instrumentation", h.device.observed_ops, prefix(sub_id, "invoked-before-defect"))
        return h


for _state, _status, _code in (("zero", "complete", 0), ("nonzero", "aborted", 1), ("incomplete", "aborted", 1)):
    register_case(RunTest, f"run-{_state}", "D17b-run", "classification-cleanup",
                  lambda self, s=_state, status=_status, c=_code: self.run_case(f"run-{s}", state=s, expected_status=status, expected_code=c),
                  "μ39-a / μ40-j")
for _op in ("install", "context-push", "context-readback", "stop", "live", "inventory", "pull", "exits", "final"):
    def _failure(self, op=_op):
        sub_id = f"run-failure-{op}"
        # Establish the real success path before claiming the independently injected failure.
        self.run_case(sub_id)
        pre = op in ("install", "context-push", "context-readback")
        self.run_case(sub_id, fail_op=op, expected_status="attempts-exhausted" if pre else "aborted", expected_code=1)
    register_case(RunTest, f"run-failure-{_op}", "D17b-run", "failure-cleanup", _failure)


def output_failed_still_collects(self):
    h = self.run_case("run-output-pull-failure", plan={"tab-entry": "PPPPP"}, expected_status="attempts-exhausted", expected_code=1)
    self.assertIn("exits", h.device.observed_ops, prefix("run-output-pull-failure", "D17-after-output-failure"))


register_case(RunTest, "run-output-pull-failure", "D17b-run", "D17-after-output-failure", output_failed_still_collects)
for _failure in ("write", "hash"):
    def _evidence_failure(self, failure=_failure):
        sub_id = f"run-evidence-{failure}"
        self.run_case(sub_id)
        h = PurityHarness(self)
        original = runner._write_new if failure == "write" else runner._ref

        def broken(*args, **kwargs):
            if "purity" in str(args[-1] if failure == "hash" else args[0]) or "commands" in str(args[0]):
                raise runner.EvidenceIOError("injected D17 evidence failure")
            return original(*args, **kwargs)

        with unittest.mock.patch.object(runner, "_write_new" if failure == "write" else "_ref", broken):
            code, _ = h.run()
        self.assertEqual(2, code, prefix(sub_id, "EvidenceIOError-exit2"))
        self.assertEqual({}, h.device.installed, prefix(sub_id, "cleanup"))
    register_case(RunTest, f"run-evidence-{_failure}", "D17b-run", "exit2-cleanup", _evidence_failure)


class ApiRunTest(ContractTest):
    pass


for _label, _raw, _code, _timeout, _supported in (
    ("29", b"29\n", 0, False, False), ("30", b"30\n", 0, False, True),
    ("32", b"32\n", 0, False, True), ("33", b"33\n", 0, False, True),
    ("empty", b"", 0, False, False), ("multiple", b"30\n32\n", 0, False, False),
    ("text", b"unknown\n", 0, False, False), ("timeout", b"", None, True, False),
    ("nonzero", b"33\n", 1, False, False),
):
    def _api_run(self, label=_label, raw=_raw, code=_code, timeout=_timeout, supported=_supported):
        sub_id = f"api-run-{label}"
        h = PurityHarness(self, api=raw, api_code=code, api_timeout=timeout)
        result, report = self.invoke(sub_id, "runner-observe", h.run)
        self.assertEqual((0, "complete") if supported else (1, "attempts-exhausted"),
                         (result, report.get("status")), prefix(sub_id, "precondition-outcome"))
        entries = h.entries()
        self.assertEqual({}, h.device.installed, prefix(sub_id, "cleanup"))
        self.assertIn("device-api", h.device.observed_ops, prefix(sub_id, "actual-observe"))
        if not supported:
            forbidden = {"install", "context-push", "absent", "inventory", "exits", "instrumentation"}
            self.assertFalse(forbidden.intersection(h.device.observed_ops), prefix(sub_id, "NOT_INVOKED-forbidden-zero"))
            ends = [e for e in entries if e["type"] == "attempt-end"]
            self.assertEqual(["environment-invalid"] * 5, [e["reason"] for e in ends], prefix(sub_id, "existing-invalid-reason"))
        else:
            first = h.device.observed_ops.index("device-api")
            self.assertLess(first, h.device.observed_ops.index("install"), prefix(sub_id, "api-before-install"))
        transcripts = list(h.root.glob("attempts/*/commands.jsonl"))
        self.assertTrue(transcripts, prefix(sub_id, "raw-envelope-preserved"))
        for path in transcripts:
            events = [json.loads(line) for line in path.read_bytes().splitlines()]
            self.assertEqual(["start", "finish"] * (len(events) // 2), [e["kind"] for e in events], prefix(sub_id, "paired-seq"))
    register_case(ApiRunTest, f"api-run-{_label}", "D17b-api", "actual-observe-precondition", _api_run, "μ39-p1")


class ObserveTest(ContractTest):
    pass


def observe_real_order(self):
    sub_id = "observe-actual-wrong-order"
    f = self.fixture()
    d = D17FakeDevice(ALL_VALID)
    folder = f.root / f.folder
    folder.mkdir(parents=True)
    self.invoke(sub_id, "observer-bind", lambda: d.begin_purity_observation(f.root, folder, f.attempt, NONCE))
    # Deliberately wrong: both inventories and pull before force-stop.
    for op in ("inventory-pre", "pull", "inventory-post", "force-stop", "live-after-stop"):
        self.invoke(sub_id, "actual-observe", lambda op=op: d.observed(op, ["shell", "echo", op]))
    path = folder / "commands.jsonl"
    events = [json.loads(line) for line in path.read_bytes().splitlines()]
    starts = [e for e in events if e["kind"] == "start"]
    self.assertEqual(["inventory-pre", "pull", "inventory-post", "force-stop", "live-after-stop"],
                     [e["op"] for e in starts], prefix(sub_id, "no-order-rewrite"))
    for event, argv in zip(starts, d.adb.calls):
        self.assertEqual(list(argv), event["argv"][-len(argv):], prefix(sub_id, "transport-envelope-equality"))


register_case(ObserveTest, "observe-actual-wrong-order", "D17b-B3", "actual-observe-order", observe_real_order, "ι7")


class CrossTest(ContractTest):
    pass


BASE_OBSERVATION = dict(invocation=runner.INVOKED, behavior="success", snapshots_valid=True,
                        instrumentation_green=True, result="valid", traces_ok=True, purity_required=True)
CROSS_ROWS = []
for _purity in ("zero", "nonzero", "incomplete", None, "unknown"):
    for _name, _changes, _kind in (
        ("clean", {}, "valid" if _purity == "zero" else "aborted"),
        ("cleanup", {"cleanup_error": "left installed"}, "aborted"),
        ("apk", {"apks_changed": True}, "aborted"),
        ("behavior-failed", {"behavior": "failure"}, "behavior-failed"),
        ("behavior-failed-bad-snapshot", {"behavior": "failure", "snapshots_valid": False}, "aborted"),
        ("behavior-bad", {"behavior": "bad"}, "aborted"),
        ("behavior-absent", {"behavior": None}, "aborted"),
        ("environment", {"environment_error": "output pull failed"}, "invalid" if _purity == "zero" else "aborted"),
        ("not-green", {"instrumentation_green": False}, "aborted"),
        ("result-missing", {"result": None}, "aborted"),
        ("result-unreadable", {"result": "unreadable"}, "aborted"),
        ("result-malformed", {"result": "malformed"}, "invalid" if _purity == "zero" else "aborted"),
        ("trace-missing", {"traces_ok": False}, "aborted"),
    ):
        _sub = f"cross-{_purity or 'missing'}-{_name}"
        def _cross(self, sub=_sub, purity=_purity, changes=_changes, kind=_kind):
            o = runner.Observation(**dict(BASE_OBSERVATION, purity=purity, **changes))
            result = self.invoke(sub, "priority", lambda: runner.classify(o))
            self.assertEqual(kind, result.kind, prefix(sub, "priority"))
            if changes.get("cleanup_error"):
                self.assertIn("cleanup", result.reason, prefix(sub, "cleanup-first"))
            elif changes.get("apks_changed"):
                self.assertIn("APK", result.reason, prefix(sub, "APK-first"))
        register_case(CrossTest, _sub, "D17b-cross", "priority", _cross, "μ40-i/j")
for _invocation in (runner.INVOKED, runner.UNKNOWN):
    for _purity in (None, "unknown", "incomplete", "nonzero"):
        _sub = f"cross-{_invocation}-{_purity or 'missing'}"
        def _invoked(self, sub=_sub, invocation=_invocation, purity=_purity):
            result = self.invoke(sub, "terminal", lambda: runner.classify(runner.Observation(
                **dict(BASE_OBSERVATION, invocation=invocation, purity=purity))))
            self.assertEqual("aborted", result.kind, prefix(sub, "terminal"))
        register_case(CrossTest, _sub, "D17b-cross / D17b-api", "terminal", _invoked)


def not_invoked(self):
    sub = "cross-NOT_INVOKED-pre-injection"
    result = runner.classify(runner.Observation(invocation=runner.NOT_INVOKED, environment_error="API unsupported",
                                               purity_required=True))
    self.assertEqual(runner.Outcome("invalid", "environment-invalid"), result, prefix(sub, "existing-invalid"))


register_case(CrossTest, "cross-NOT_INVOKED-pre-injection", "D17b-cross", "existing-invalid", not_invoked)


def throttle_decision(self):
    sub = "cross-throttle-C9-R5"
    h = PurityHarness(self, state="incomplete", fail_op="summary-throttle")
    code, report = h.run()
    self.assertEqual((1, "aborted"), (code, report.get("status")), prefix(sub, "terminal-no-invalid-retry"))
    starts = [e for e in h.entries() if e["type"] == "attempt-start"]
    self.assertEqual(1, len(starts), prefix(sub, "retry-zero"))
    diagnostic = gate.load_json(h.root / "aborted.json")
    decision = diagnostic.get("purityDecision", {})
    self.assertEqual("incomplete", decision.get("d17"), prefix(sub, "D17-incomplete"))
    self.assertEqual("user-decision-required", decision.get("nextAction"), prefix(sub, "C9-decision-boundary"))
    self.assertTrue(decision.get("rawRefs"), prefix(sub, "C9-raw-ref"))
    self.assertIn("R-5", decision.get("measurementImpact", ""), prefix(sub, "R5-load-reason"))
    self.assertTrue(decision.get("goldenRegenerationReason"), prefix(sub, "golden-reason"))
    # The host test checks a decision record; it sends no user question or message.


register_case(CrossTest, "cross-throttle-C9-R5", "D17b-cross", "C9-R5-decision-record", throttle_decision, "ι12")


def throttle_decision_mixed(self):
    sub = "cross-throttle-C9-mixed-records"
    h = PurityHarness(self, state="incomplete", fail_op="summary-throttle-mixed")
    code, report = h.run()
    self.assertEqual((1, "aborted"), (code, report.get("status")), prefix(sub, "terminal-no-invalid-retry"))
    decision = gate.load_json(h.root / "aborted.json").get("purityDecision", {})
    self.assertEqual("user-decision-required", decision.get("nextAction"), prefix(sub, "C9-decision-after-null-record"))
    self.assertTrue(decision.get("rawRefs"), prefix(sub, "C9-raw-ref"))


register_case(CrossTest, "cross-throttle-C9-mixed-records", "D17b-cross", "C9-R5-decision-record", throttle_decision_mixed,
              "review-F2")


class VerdictFixture:
    """Other evidence is generated by the existing real Bench/ledger/gate path."""

    def __init__(self, test, tail_fail=False, plan=None):
        self.temp = tempfile.TemporaryDirectory(dir=REPO)
        test.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bench = Bench(self.root)
        self.bench.run(plan or ({"tab-entry": "fff", "period-switch": "vvv", "scroll": "vvv"} if tail_fail else ALL_VALID))
        self.entries = ledger.load_ledger(self.root)
        for key, package in (("targetBenchmarkApk", dp.TARGET_PACKAGE), ("macrobenchmarkTestApk", dp.TEST_PACKAGE)):
            ref = self.entries[0]["artifacts"][key]
            (self.root / ref["path"]).write_bytes(APKS[package])
            ref["sha256"] = hashlib.sha256(APKS[package]).hexdigest()
        self.bench.rewrite(self.entries)
        self.manifest = ledger.build_manifest(self.entries, self.root)
        self.fixtures = []
        for journey in self.manifest["journeys"]:
            for run in journey["runs"]:
                f = ProofFixture(self.root, run["runId"])
                # Unique public per-attempt nonces; no raw bytes from another attempt.
                f.rebind(hashlib.sha256(run["runId"].encode()).hexdigest()[:32])
                f.write()
                run["purity"] = f.proof_ref
                self.fixtures.append(f)
                for index, entry in enumerate(self.entries):
                    if entry.get("attemptId") == run["runId"] and entry["type"] == "attempt-start":
                        entry.update(runnerNonce=f.nonce, context=f.context_ref)
                        self.entries[index + 1]["purity"] = f.proof_ref
        self.bench.rewrite(self.entries)

    def cli(self):
        manifest = self.root / "manifest-under-test.json"
        manifest.write_bytes(encoded(self.manifest))
        # Actual interpreter and CLI, no patched main, parser, exit code or subprocess.
        return subprocess.run([sys.executable, str(Path(gate.__file__).resolve()), "validate-manifest", str(manifest),
                               "--artifact-root", str(self.root)], capture_output=True, text=True, timeout=30)


def rebind(self, nonce):
    self.nonce = nonce
    self.context["runnerNonce"] = nonce
    self.register["runnerNonce"] = nonce
    for row in self.rows:
        row["runnerNonce"] = nonce
    self.summary = summary(count=self.count, nonce=nonce)
    self.records[0]["summaryHex"] = self.summary.hex()


ProofFixture.rebind = rebind


class VerdictTest(ContractTest):
    def manifest_control(self, sub_id):
        v = VerdictFixture(self)
        self.invoke(sub_id, "standalone-positive", lambda: gate.validate_manifest(v.manifest, v.root))
        self.assertFalse((v.root / "ledger-required.json").exists(), prefix(sub_id, "standalone"))
        # Remove the actual ledger: the following validation must be independent.
        (v.root / ledger.LEDGER_NAME).unlink()
        self.invoke(sub_id, "standalone-without-ledger", lambda: gate.validate_manifest(v.manifest, v.root))
        return v

    def rejected_manifest(self, sub_id, edit):
        v = self.manifest_control(sub_id)
        edit(v)
        with self.assertRaises(gate.InvalidEvidence, msg=prefix(sub_id, "standalone-reject")):
            gate.validate_manifest(v.manifest, v.root)


def ledger_positive(self):
    sub = "ledger-positive-real-API"
    v = VerdictFixture(self)
    entries = self.invoke(sub, "load-ledger", lambda: ledger.load_ledger(v.root))
    self.invoke(sub, "assemble-manifest", lambda: ledger.build_manifest(entries, v.root))


register_case(VerdictTest, "ledger-positive-real-API", "D17b-verdict", "ref-and-semantics", ledger_positive)
for _kind in ("ref", "semantics", "start-nonce", "start-context"):
    def _ledger_negative(self, kind=_kind):
        sub = f"ledger-{kind}"
        v = VerdictFixture(self)
        self.invoke(sub, "positive-ledger", lambda: ledger.load_ledger(v.root))
        start = next(e for e in v.entries if e["type"] == "attempt-start")
        end = next(e for e in v.entries if e["type"] == "attempt-end")
        if kind == "ref":
            end["purity"] = dict(end["purity"], sha256="00" * 32)
        elif kind == "semantics":
            f = v.fixtures[0]
            f.records[0]["summaryHex"] = summary(1, nonce=f.nonce).hex()
            f.write()
            end["purity"] = f.proof_ref
        elif kind == "start-nonce":
            start["runnerNonce"] = "aa" * 16
        else:
            start["context"] = dict(start["context"], sha256="00" * 32)
        v.bench.rewrite(v.entries)
        with self.assertRaises(ledger.LedgerError, msg=prefix(sub, "real-ledger-reject")):
            ledger.load_ledger(v.root)
    register_case(VerdictTest, f"ledger-{_kind}", "D17b-verdict", "ref-and-semantics", _ledger_negative,
                  "μ40-a" if _kind == "ref" else "μ40-b")


def edit_first_proof(v, edit):
    f = v.fixtures[0]
    edit(f)
    f.write()
    v.manifest["journeys"][0]["runs"][0]["purity"] = f.proof_ref


for _role in ROLES:
    _sub = f"standalone-missing-role-{_role}"
    register_case(VerdictTest, _sub, "D17b-verdict", "C1-C8-raw-role-required",
                  lambda self, sub=_sub, role=_role: self.rejected_manifest(sub, lambda v: edit_first_proof(
                      v, lambda f: setattr(f, "observation_edit", lambda obs: obs.__setitem__(slice(None), [o for o in obs if o["role"] != role])))),
                  "μ40-c/e")
for _field in ("context", "commands", "deviceFiles", "instances", "apks", "observations", "result"):
    _sub = f"standalone-missing-{_field}"
    register_case(VerdictTest, _sub, "D17b-verdict", "required-raw-input",
                  lambda self, sub=_sub, field=_field: self.rejected_manifest(sub, lambda v: edit_first_proof(
                      v, lambda f: setattr(f, "proof_edit", lambda p: p.pop(field)))), "μ40-e")
for _name, _edit in (
    ("summary-null", lambda f: f.records[0].__setitem__("summaryHex", None)),
    ("aggregate-forged", lambda f: setattr(f, "proof_edit", lambda p: p["result"].__setitem__("actualCount", 7))),
    ("state-forged", lambda f: setattr(f, "proof_edit", lambda p: p["result"].__setitem__("state", "nonzero"))),
    ("instances-shrunk", lambda f: setattr(f, "instances", [])),
    ("files-shrunk", lambda f: setattr(f, "proof_edit", lambda p: p["deviceFiles"]["files"].pop())),
    ("commands-empty", lambda f: setattr(f, "command_edit", lambda c: c.clear())),
    ("old-raw-new-proof", lambda f: [row.__setitem__("runnerNonce", "aa" * 16) for row in [f.register, *f.rows]]),
):
    _sub = f"standalone-{_name}"
    register_case(VerdictTest, _sub, "D17b-verdict", "raw-recompute",
                  lambda self, sub=_sub, edit=_edit: self.rejected_manifest(sub, lambda v: edit_first_proof(v, edit)), "μ40-e/f")


def missing_all_purity(v):
    for journey in v.manifest["journeys"]:
        for run in journey["runs"]:
            run.pop("purity")


register_case(VerdictTest, "standalone-all-purity-missing", "D17b-verdict", "gate-required-independent-of-schema",
              lambda self: self.rejected_manifest("standalone-all-purity-missing", missing_all_purity), "μ40-c")
register_case(VerdictTest, "standalone-one-purity-missing", "D17b-verdict", "gate-required-independent-of-schema",
              lambda self: self.rejected_manifest("standalone-one-purity-missing", lambda v: v.manifest["journeys"][0]["runs"][0].pop("purity")), "μ40-c")
register_case(VerdictTest, "standalone-cross-run-reuse", "D17b-verdict", "run-reuse-reject",
              lambda self: self.rejected_manifest("standalone-cross-run-reuse", lambda v: v.manifest["journeys"][0]["runs"][1].__setitem__(
                  "purity", v.manifest["journeys"][0]["runs"][0]["purity"])), "μ40-h")


def root_escape(v):
    f = v.fixtures[0]
    outside = v.root.parent / (v.root.name + "-outside.json")
    outside.write_bytes(encoded(f.context))
    # Cleanup registration is handled by the test method below.
    f.proof_edit = lambda p: p.__setitem__("context", {"path": "../" + outside.name, "sha256": gate.sha256_file(outside)})
    f.write()
    v.manifest["journeys"][0]["runs"][0]["purity"] = f.proof_ref
    return outside


def containment(self):
    sub = "standalone-root-escape"
    v = self.manifest_control(sub)
    outside = root_escape(v)
    self.addCleanup(outside.unlink, missing_ok=True)
    with self.assertRaises(gate.InvalidEvidence, msg=prefix(sub, "root-containment")):
        gate.validate_manifest(v.manifest, v.root)


register_case(VerdictTest, "standalone-root-escape", "D17b-verdict", "root-containment", containment, "μ40-g")


def same_attempt_ref(self):
    sub = "standalone-same-attempt-ref-allowed"
    # context is referenced directly and through deviceFiles; observation stdout
    # is also referenced by its finish record. These deliberate repeated refs pass.
    self.manifest_control(sub)


register_case(VerdictTest, "standalone-same-attempt-ref-allowed", "D17b-verdict", "same-attempt-repeat-allowed", same_attempt_ref)
for _name, _expected in (("pass", 0), ("performance-fail", 1), ("evidence-defect", 2)):
    def _cli(self, name=_name, expected=_expected):
        sub = f"cli-{name}"
        v = VerdictFixture(self, tail_fail=name == "performance-fail")
        if name == "evidence-defect":
            control = v.cli()
            edit_first_proof(v, lambda f: f.records[0].__setitem__("summaryHex", None))
        process = v.cli()
        if name == "evidence-defect":
            self.assertEqual(0, control.returncode, prefix(sub, "actual-CLI-positive") + control.stderr + process.stderr)
        self.assertEqual(expected, process.returncode, prefix(sub, "actual-exit") + process.stdout + process.stderr)
        if expected in (0, 1):
            self.assertTrue(json.loads(process.stdout)["valid"], prefix(sub, "valid-numeric-verdict"))
        else:
            self.assertFalse(json.loads(process.stderr)["valid"], prefix(sub, "InvalidEvidence-not-performance-fail"))
    register_case(VerdictTest, f"cli-{_name}", "D17b-verdict", "actual-CLI-exit", _cli, "μ40-k")


class SchemaTest(ContractTest):
    pass


for _field in ("schema", "attemptId", "runnerNonce", "context", "apks", "observations", "commands", "deviceFiles", "instances", "result"):
    def _required(self, field=_field):
        sub = f"schema-required-{field}"
        schema = gate.load_json(REPO / "tools/performance/evidence-manifest.schema.json")
        self.assertIn(field, schema.get("$defs", {}).get("purityProof", {}).get("required", []), prefix(sub, "schema-required"))
        self.assertFalse(schema["$defs"]["purityProof"]["additionalProperties"], prefix(sub, "exact-keys"))
    register_case(SchemaTest, f"schema-required-{_field}", "D17b-verdict", "schema-required", _required, "μ40-d")


def schema_run_required(self):
    sub = "schema-run-purity-required"
    schema = gate.load_json(REPO / "tools/performance/evidence-manifest.schema.json")
    run = schema["properties"]["journeys"]["items"]["properties"]["runs"]["items"]
    self.assertIn("purity", run["required"], prefix(sub, "schema-required"))
    self.assertEqual({"$ref": "#/$defs/artifactRef"}, run["properties"]["purity"], prefix(sub, "ref-type"))


register_case(SchemaTest, "schema-run-purity-required", "D17b-verdict", "schema-required", schema_run_required, "μ40-d")


class BridgeTest(ContractTest):
    """Kotlin v1 ABI: device raw bytes, sibling OS getter output and independent host observations."""

    def sample(self, sub_id, name):
        catalog = BRIDGE_ROOT / "samples.json"
        self.assertTrue(catalog.is_file(), "precondition(D17-bridge): Kotlin exports required")
        self.assertEqual({"schema": 1, "samples": list(BRIDGE_NAMES)}, gate.load_json(catalog),
                         prefix(sub_id, "exact-sample-catalog"))
        folder = BRIDGE_ROOT / name
        self.assertTrue(folder.is_dir(), "precondition(D17-bridge): sample required: " + name)
        return folder

    def import_sample(self, sub, name):
        folder = self.sample(sub, name)
        device = folder / "device"
        null_context = name in ("missing-context", "invalid-context")
        expected_names = {"registry.jsonl", f"instance-{INSTANCE}.jsonl"}
        if not null_context:
            expected_names.add("attempt.json")
        self.assertEqual(expected_names, {p.name for p in device.iterdir()}, prefix(sub, "exact-device-files"))
        f = self.fixture(attempt="attempt-001")
        f.apks = {"target": "a" * 64, "test": "b" * 64}
        f.context.update(attemptId="attempt-001", runnerNonce=NONCE, bootId=BOOT,
                         targetApkSha256=f.apks["target"], testApkSha256=f.apks["test"])
        f.clock_values = {"install-clock": 1799999999000, "stop-clock": 1800000000900,
                          "after-stop-clock": 1800000002000}
        # Preserve the entire raw folder byte for byte, including a genuinely absent attempt.json.
        f.raw_overrides = {n: (device / n).read_bytes() if n in expected_names else None
                           for n in ("attempt.json", "registry.jsonl", f"instance-{INSTANCE}.jsonl")}
        if not null_context:
            f.context_bytes = f.raw_overrides["attempt.json"]
            f.context = gate.load_json(device / "attempt.json")
            f.context_readback = f.context_bytes
        # For null diagnostic exports, the separate host injection is valid. The collected
        # attempt.json is missing: first C1, with C4 tested independently by attempt-state-*.
        stdout = (folder / "exit-query.stdout.json").read_bytes()
        getter = json.loads(stdout)
        f.probe_nonces["exit-query"] = getter["commandNonce"]
        f.stream_edits["exit-query"] = lambda unused: stdout
        f.records = getter["payload"]["records"]
        raw_summary = (folder / "summary.bin").read_bytes()
        self.assertEqual(raw_summary.hex(), f.records[0]["summaryHex"], prefix(sub, "lossless-original-getter"))
        count = 70 if name == "nonzero-70" else 1 if name == "nonzero-1" else 0
        f.instances = [dict(instanceId=INSTANCE, lifetimeId=LIFE, commits=count)]
        f.state = "nonzero" if count else "zero"
        f.count = count
        return f, raw_summary


BRIDGE_NAMES = ("zero", "nonzero-1", "nonzero-70", "busy", "poison", "b1", "prepare-only", "crossed-pairs",
                "missing-context", "invalid-context", "max-boundary", "admit-preapply-kill",
                "admit-postapply-kill", "fatal88-idle")
BRIDGE_CASES = (
    ("zero", "zero", 0, None), ("nonzero-1", "nonzero", 1, None), ("nonzero-70", "nonzero", 70, None),
    ("busy", "incomplete", None, "C6"), ("poison", "incomplete", None, "C6"),
    ("b1", "incomplete", None, "C6"), ("prepare-only", "incomplete", None, "C5"),
    ("crossed-pairs", "incomplete", None, "C5"), ("missing-context", "incomplete", None, "C1"),
    ("invalid-context", "incomplete", None, "C1"), ("max-boundary", "incomplete", None, "C5"),
    ("admit-preapply-kill", "zero", 0, None), ("admit-postapply-kill", "incomplete", None, "C6"),
    ("fatal88-idle", "incomplete", None, "C7"),
)
for _name, _state, _count, _check in BRIDGE_CASES:
    def _bridge(self, name=_name, state=_state, count=_count, check=_check):
        sub = f"bridge-{name}"
        f, raw_summary = self.import_sample(sub, name)
        if name == "max-boundary":
            decoded = self.invoke(sub, "64byte-decode", lambda: gate.decode_purity_summary(raw_summary))
            self.assertEqual(("IDLE", NONCE, LIFE, INSTANCE, MAX),
                             (decoded.state, decoded.runner_nonce, decoded.lifetime_id, decoded.instance_id, decoded.counter),
                             prefix(sub, "MAX-codec-boundary"))
        f.write()
        value = self.read(f, sub, "Kotlin-bytes-common-reader")
        self.assertEqual((state, count), (value.state, value.actual_count), prefix(sub, "Kotlin-Python-agreement"))
        if check:
            self.assertTrue(value.defects, prefix(sub, "diagnostic-required"))
            self.assertEqual(check, value.defects[0]["check"], prefix(sub, "first-check"))
        else:
            self.assertEqual([], list(value.defects), prefix(sub, "complete-no-defects"))
        # Consumer expectations are literal above, never learned from producer expected.json.
    register_case(BridgeTest, f"bridge-{_name}", "D17-bridge", "Kotlin-Python-agreement", _bridge, "ι5")


# Additional independent defects, kept in the same catalog rather than hidden in loops.
defect_case("context-extra-key", "C1", lambda f: f.context.__setitem__("unknown", 1))
defect_case("context-apk-bool", "C1", lambda f: f.context.__setitem__("targetApkSha256", True))
defect_case("context-nonce-uppercase", "C1", lambda f: f.context.__setitem__("runnerNonce", "AA" * 16))
for _role in ("apk-before-target", "apk-before-test", "apk-after-target", "apk-after-test"):
    for _field, _value in (("actualSha256", "aa" * 32), ("matched", False), ("actualSize", -1)):
        defect_case(f"{_role}-{_field}", "C1", lambda f, role=_role, field=_field, value=_value:
                    f.readback_edits.__setitem__(role, lambda doc: doc.__setitem__(field, value)))
for _role, _value in (("boot-after", b"55555555-5555-4555-8555-555555555555\n"),
                      ("install-identity-after", b"package:com.jay.fxi uid:10124\n"),
                      ("after-stop-clock", b"1700\n")):
    defect_case(f"continuity-{_role}", "C1", lambda f, r=_role, v=_value: f.stream_edits.__setitem__(r, lambda raw: v), mutation="μ39-o")
for _key, _value in (("schema", True), ("pid", -1), ("pid", True), ("packageUid", -1), ("userId", 0.0),
                     ("processStartWallMs", 9223372036854775808)):
    _label = f"register-type-{_key}-{str(_value).replace('.', '_')}"
    def _register_type(f, k=_key, v=_value):
        f.register[k] = v
        f.rows[0][k] = v
    defect_case(_label, "C2", _register_type)


def same_lifetime(f):
    add_second_lifetime(f)
    f.registry[-1]["lifetimeId"] = LIFE
    instance = f.registry[-1]["instanceId"]
    f.extra_files[f"instance-{instance}.jsonl"] = encoded(dict(f.registry[-1], kind="begin"))


defect_case("lifetime-two-instances", "C2", same_lifetime)
defect_case("prepare-line-type", "C2", row_edit(1, "line", {}), count=1)
defect_case("commit-unknown-key", "C2", row_edit(2, "attemptState", "ok"), count=1)
defect_case("begin-instance-id", "C3", row_edit(0, "instanceId", "55" * 16))
for _kind in ("directory", "socket"):
    defect_case(f"inventory-type-{_kind}", "C3", lambda f, kind=_kind: setattr(f, "inventory_pre_edit", lambda files: files[0].__setitem__("type", kind)))
for _field, _value in (("seq", True), ("returnCode", True), ("returnCode", None), ("timedOut", "false")):
    defect_case(f"finish-type-{_field}-{_value}", "C8", command_edit(3, _field, _value))
defect_case("command-pair-seq-mismatch", "C8", command_edit(3, "seq", 9))
defect_case("command-unknown-key", "C8", command_edit(2, "unknown", 1))
for _role in ("absent", "inventory-pre", "inventory-post", "exit-query"):
    for _field, _value in (("schema", 1), ("op", "wrong-op"), ("attemptId", "old-attempt"),
                         ("runnerNonce", "aa" * 16), ("commandNonce", "aa" * 16),
                         ("collectorUid", UID), ("userId", 1)):
        def _probe_field(f, role=_role, field=_field, value=_value):
            def transform(raw):
                doc = json.loads(raw)
                doc[field] = value
                return encoded(doc)
            f.stream_edits[role] = transform
        defect_case(f"probe-{_role}-{_field}", "C8", _probe_field)
    defect_case(f"probe-{_role}-stderr", "C8", lambda f, r=_role: f.stderr_bytes.__setitem__(r, b"probe failure\n"))


def physical_symlink(self):
    sub = "host-physical-symlink"
    f = self.fixture()
    self.positive(f, sub)
    file = f.root / f.device_root / "registry.jsonl"
    moved = file.with_name("hidden-raw")
    file.rename(moved)
    file.symlink_to(moved.name)
    result = self.read(f, sub, "C3")
    self.assertEqual(("incomplete", None), (result.state, result.actual_count), prefix(sub, "C3"))


register_case(ProofTest, "host-physical-symlink", "D17b-defects", "C3", physical_symlink)


def stale_raw(self):
    self.defect("old-raw-new-proof", "C4", lambda f: [r.__setitem__("runnerNonce", "aa" * 16) for r in [f.register, *f.rows]], count=1)


register_case(ProofTest, "old-raw-new-proof", "D17b-defects", "C4", stale_raw, "μ39-g1")


def delay_deduplicate(self):
    sub = "exit-query-repeat-same-record"
    f = self.fixture()
    f.order.insert(f.order.index("exit-query"), "exit-query")
    f.record_queries = [f.records, f.records]
    self.positive(f, sub)


register_case(ProofTest, "exit-query-repeat-same-record", "D17b-defects", "C6-deduplicate", delay_deduplicate)


# The ledger must reject API defects too, separately from standalone parsing.
for _name in ("role-missing", "role-forged", "failure-then-run", "late"):
    def _api_ledger(self, name=_name):
        sub = f"api-ledger-{name}"
        v = VerdictFixture(self)
        self.invoke(sub, "positive-real-ledger", lambda: ledger.load_ledger(v.root))
        f = v.fixtures[0]
        if name == "role-missing":
            f.observation_edit = lambda obs: obs.__setitem__(slice(None), [o for o in obs if o["role"] != "device-api"])
        elif name == "role-forged":
            f.observation_edit = lambda obs: obs[1].__setitem__("role", "absent")
        elif name == "failure-then-run":
            f.api_code = 1
        else:
            f.order.remove("device-api")
            f.order.insert(10, "device-api")
        f.write()
        next(e for e in v.entries if e["type"] == "attempt-end")["purity"] = f.proof_ref
        v.bench.rewrite(v.entries)
        with self.assertRaises(ledger.LedgerError, msg=prefix(sub, "real-ledger-reject")):
            ledger.load_ledger(v.root)
    register_case(VerdictTest, f"api-ledger-{_name}", "D17b-api", "C1-C8-real-ledger", _api_ledger, "μ39-p2")


for _name, _plan, _status in (
    ("valid3-attempt5", {"tab-entry": "evmvv", "period-switch": "vvv", "scroll": "vvv"}, "complete"),
    ("attempt5-exhausted", {"tab-entry": "evvem"}, "attempts-exhausted"),
    ("threshold-miss-valid", {"tab-entry": "fff", "period-switch": "vvv", "scroll": "vvv"}, "complete"),
):
    def _limits(self, name=_name, plan=_plan, status=_status):
        sub = f"cross-{name}"
        h = PurityHarness(self, plan)
        code, report = h.run()
        self.assertEqual((1 if name != "valid3-attempt5" else 0, status), (code, report.get("status")), prefix(sub, "unchanged-attempt-rule"))
        ends = [e for e in h.entries() if e["type"] == "attempt-end"]
        tab = [e for e in ends if "tab-entry" in e["attemptId"]]
        self.assertEqual(5 if name != "threshold-miss-valid" else 3, len(tab), prefix(sub, "attempt-count"))
        if name == "threshold-miss-valid":
            self.assertEqual(["valid"] * 3, [e["outcome"] for e in tab], prefix(sub, "threshold-valid"))
    register_case(CrossTest, f"cross-{_name}", "D17b-cross", "valid3-attempt5-threshold", _limits)




def observe_envelope(self, timeout=False):
    sub = "observe-timeout" if timeout else "observe-envelope"
    f = self.fixture()
    folder = f.root / f.folder
    folder.mkdir(parents=True)
    d = D17FakeDevice(ALL_VALID, api_timeout=timeout)
    self.invoke(sub, "bind-observer", lambda: d.begin_purity_observation(f.root, folder, f.attempt, NONCE))
    original_run = d.adb.run
    seen_start = []

    def check_started(*args, **kwargs):
        # Inspect the persisted file at the actual transport entry, before completion.
        events = [json.loads(raw) for raw in (folder / "commands.jsonl").read_bytes().splitlines()]
        seen_start.append(events[-1])
        self.assertEqual("start", events[-1]["kind"], prefix(sub, "start-before-execution"))
        return original_run(*args, **kwargs)

    d.adb.run = check_started
    value = self.invoke(sub, "observe", d.device_api)
    events = [json.loads(raw) for raw in (folder / "commands.jsonl").read_bytes().splitlines()]
    self.assertEqual(["start", "finish"], [e["kind"] for e in events], prefix(sub, "paired-records"))
    self.assertEqual(1, len(seen_start), prefix(sub, "transport-called-once"))
    self.assertEqual((None, True) if timeout else (0, False), (value.return_code, value.timed_out), prefix(sub, "actual-result"))
    for key in ("stdout", "stderr"):
        ref = events[-1][key]
        file = gate.validate_artifact_ref(ref, f.root, sub + "/" + key)
        self.assertEqual(ref, getattr(value, key), prefix(sub, "returned-ref"))
        if not timeout:
            self.assertEqual(b"33\n" if key == "stdout" else b"", file.read_bytes(), prefix(sub, "lossless-stream"))


register_case(ObserveTest, "observe-envelope", "D17b-run / D17b-api", "start-raw-finish", observe_envelope, "ι7")
register_case(ObserveTest, "observe-timeout", "D17b-api", "timeout-raw-finish", lambda self: observe_envelope(self, True), "ι7")




def query_budget_overrun(f):
    f.order.insert(f.order.index("exit-query"), "exit-query")
    def edit(commands):
        queries = [i for i, event in enumerate(commands) if event["kind"] == "start" and event["op"] == "exits"]
        for event in commands[queries[1]:]:
            event["monoNs"] += 10_000_000_001
    f.command_edit = edit


defect_case("exit-query-budget-overrun", "C8", query_budget_overrun)





def zero_fixture(root, attempt_id, artifact_refs):
    """Fresh host input per attempt; equal stream bytes from independent collection remain legal."""
    f = ProofFixture(root, attempt_id)
    f.apks = {key: artifact_refs[artifact]["sha256"] for key, artifact in
              (("target", "targetBenchmarkApk"), ("test", "macrobenchmarkTestApk"))}
    f.context.update(targetApkSha256=f.apks["target"], testApkSha256=f.apks["test"])
    f.rebind(hashlib.sha256(attempt_id.encode()).hexdigest()[:32])
    return f.write()


class StrictEntryTest(ContractTest):
    def test_defaults_and_disable_cannot_bypass(self):
        import inspect  # Inspect the public signature at call time; no module-level test cycle.
        self.assertIs(True, inspect.signature(runner.run).parameters["purity_enabled"].default,
                      prefix("strict-default", "runner-default-true"))
        for enabled in (True, False):
            o = runner.Observation(**dict(BASE_OBSERVATION, purity=None, purity_required=enabled))
            self.assertEqual("aborted", runner.classify(o).kind,
                             prefix("strict-disable", "invoked-missing-always-aborted"))
        h = PurityHarness(self)
        original = runner.run
        def disabled(*args, **kwargs):
            return original(*args, **kwargs, purity_enabled=False)
        with unittest.mock.patch.object(runner, "run", disabled):
            code, report = h.run()
        self.assertEqual((2, False), (code, report.get("ledger", False)),
                         prefix("strict-disable", "disabled-rejected-before-session"))
        self.assertFalse(h.root.exists(), prefix("strict-disable", "disabled-no-evidence-root"))

    def test_runner_cli_uses_strict_default(self):
        seen = []
        def entry(root, run_id, **kwargs):
            seen.append(kwargs)
            return 0
        with unittest.mock.patch.object(runner, "run", entry):
            self.assertEqual(0, runner.main(["run", "--evidence-root", "/unused/s5-run-1", "--run-id", "s5-run-1"]))
        self.assertEqual(1, len(seen), prefix("strict-cli", "entry-called"))
        self.assertIsNot(False, seen[0].get("purity_enabled"), prefix("strict-cli", "no-opt-out"))

    def test_cli_all_purity_deleted_is_exit2(self):
        v = VerdictFixture(self)
        self.assertEqual(0, v.cli().returncode, prefix("strict-cli-delete", "positive"))
        missing_all_purity(v)
        self.assertEqual(2, v.cli().returncode, prefix("strict-cli-delete", "all-deleted-exit2"))

    def test_ledger_all_purity_deleted_is_rejected(self):
        v = VerdictFixture(self)
        ledger.load_ledger(v.root)
        for entry in v.entries:
            for field in ("purity", "runnerNonce", "context"):
                entry.pop(field, None)
        v.bench.rewrite(v.entries)
        with self.assertRaises(ledger.LedgerError, msg=prefix("strict-ledger-delete", "all-fields-required")):
            ledger.load_ledger(v.root)


class PlatformModelTest(ContractTest):
    def test_stale_idle_platform_violation_is_not_self_detectable(self):
        f = self.fixture()
        # These are well-formed apparent IDLE(0) bytes. The out-of-band model says
        # a BUSY update succeeded; that platform-assumption violation is not in raw proof.
        self.positive(f, "model-stale-idle")
        f.model_busy_returned_but_os_lied = True
        value = self.read(f, "model-stale-idle", "raw-cannot-prove-platform-honesty")
        self.assertEqual("zero", value.state, prefix("model-stale-idle", "explicit-assumption-limit"))


def run_exit_delay_success(self):
    sub = "run-exit-delay-success"
    h = PurityHarness(self, exit_delay=1)
    code, report = self.invoke(sub, "actual-collector", h.run)
    self.assertEqual((0, "complete"), (code, report.get("status")), prefix(sub, "delayed-exact-record-valid"))
    first = next(e["attemptId"] for e in h.entries() if e["type"] == "attempt-start")
    commands = [json.loads(line) for line in (h.root / "attempts" / first / "commands.jsonl").read_bytes().splitlines()]
    self.assertGreaterEqual(sum(e["kind"] == "start" and e["op"] == "exits" for e in commands), 2,
                            prefix(sub, "actual-requery-preserved"))


register_case(RunTest, "run-exit-delay-success", "D17b-run", "delayed-exact-record-valid", run_exit_delay_success, "μ39-k")


def b3_actual_runner_order(self):
    # D17b-B3: the real runner/DeviceOps order, read from the attempt's actual purity.json, not a static transcript.
    sub = "B3-actual-runner-order"
    h = PurityHarness(self)
    code, report = self.invoke(sub, "actual-collector", h.run)
    first = next(e["attemptId"] for e in h.entries() if e["type"] == "attempt-start")
    seqs = {}
    for item in gate.load_json(h.root / "attempts" / first / "purity.json")["observations"]:
        seqs.setdefault(item["role"], []).extend(item["seqs"])
    stop = [s for role in ("stop-clock", "force-stop", "live-after-stop") for s in seqs.get(role, [])]
    lists = [s for role in ("inventory-pre", "pull", "inventory-post") for s in seqs.get(role, [])]
    self.assertTrue(stop and lists and max(stop) < min(lists), prefix(sub, "actual-stop-live0-before-lists-pull"))
    self.assertEqual((0, "complete"), (code, report.get("status")), prefix(sub, "gate-accepts-actual-order"))


register_case(RunTest, "B3-actual-runner-order", "D17b-B3", "C8-actual-runner-order", b3_actual_runner_order, "μ39-b")


def ledger_api_late(self):
    sub = "ledger-api-validator-late"
    v = VerdictFixture(self)
    ledger.load_ledger(v.root)
    f = v.fixtures[0]
    f.order.remove("device-api")
    f.order.insert(10, "device-api")
    f.write()
    end = next(e for e in v.entries if e["type"] == "attempt-end")
    end["purity"] = f.proof_ref
    v.bench.rewrite(v.entries)
    with self.assertRaises(ledger.LedgerError, msg=prefix(sub, "C1-C8-required")):
        ledger.load_ledger(v.root)


register_case(VerdictTest, "ledger-api-validator-late", "D17b-api", "C1-C8-required", ledger_api_late, "μ39-p2")


if __name__ == "__main__":
    unittest.main()
