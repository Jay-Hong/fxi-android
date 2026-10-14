import contextlib
import copy
import hashlib
import io
import json
import os
import tempfile
import unittest
import unittest.mock
from pathlib import Path

import benchmark_gate as gate
import s5_ledger as ledger
from test_benchmark_gate import BENCHMARK_METHODS, DEVICE_SNAPSHOT, behavior_doc, failure_doc, macro_result


class Bench:
    """Writes one root's evidence files the way the runner will and drives the ledger with ``next_step``.

    Outcome codes per attempt: ``v`` valid, ``f`` valid but over the P99 limit, ``e`` environment-invalid, ``m``
    result-malformed, ``b`` behavior-failed."""

    def __init__(self, root: Path):
        self.root = root
        self.minute = 0

    def put(self, name, data: bytes):
        path = self.root / name
        path.write_bytes(data)
        return {"path": name, "sha256": gate.sha256_file(path)}

    def put_json(self, name, value):
        return self.put(name, json.dumps(value, sort_keys=True).encode("utf-8"))

    def artifacts(self):
        return {
            key: self.put(f"artifact-{key}.bin", Path(gate.__file__).read_bytes() if key == "parser" else key.encode("utf-8"))
            for key in sorted(gate.ARTIFACT_KEYS)
        }

    def snapshots(self, attempt_id):
        self.minute += 1
        refs = {}
        for phase, second in (("preflight", "00"), ("postflight", "30")):
            refs[phase] = self.put_json(f"{attempt_id}-{phase}.json", {
                **copy.deepcopy(DEVICE_SNAPSHOT), "runId": attempt_id, "phase": phase,
                "observedAt": f"2026-10-14T{self.minute // 60:02d}:{self.minute % 60:02d}:{second}+00:00",
            })
        return refs

    def fields(self, code, journey, attempt_id):
        if code in "vf":
            prefix = f"{attempt_id}-trace"
            result = macro_result(tail=20.0 if code == "f" else 8.33, trace_prefix=prefix, benchmark_name=BENCHMARK_METHODS[journey])
            return {
                "outcome": "valid",
                "result": self.put_json(f"{attempt_id}-result.json", result),
                "behavior": self.put_json(f"{attempt_id}-behavior.json", behavior_doc(journey, attempt_id)),
                **self.snapshots(attempt_id),
                "traces": [self.put(f"{prefix}-{index}.perfetto-trace", f"{attempt_id}/{index}".encode()) for index in range(5)],
            }
        if code == "e":
            evidence = {"schemaVersion": 1, "runId": attempt_id, "serialRecorded": False, "error": "postflight drift"}
            return {"outcome": "invalid", "reason": "environment-invalid", "evidence": self.put_json(f"{attempt_id}-env.json", evidence)}
        if code == "m":
            broken = macro_result(benchmark_name=BENCHMARK_METHODS[journey])
            del broken["benchmarks"][0]["sampledMetrics"]["frameOverrunMs"]["P99"]
            return {"outcome": "invalid", "reason": "result-malformed", "evidence": self.put_json(f"{attempt_id}-result.json", broken)}
        if code == "b":
            record = failure_doc(journey, attempt_id, slot=4, stage="lookup", kind="operation-error",
                                 failed={"lookups": None, "before": None})
            return {"outcome": "behavior-failed", "behavior": self.put_json(f"{attempt_id}-behavior.json", record), **self.snapshots(attempt_id)}
        raise AssertionError(code)

    def run(self, plan, *, end=None):
        """``plan`` maps journey to its outcome codes. Attempts follow ``next_step``. With ``end=False`` no end is written and
        the writer is returned open; otherwise it is closed."""
        writer = ledger.LedgerWriter.create(self.root, source_commit="a" * 40, artifacts=self.artifacts())
        queues = {journey: list(codes) for journey, codes in plan.items()}
        while True:
            step = ledger.next_step(writer.entries)
            if step.status is not None or not queues.get(step.journey):
                break
            attempt_id = f"{step.journey}-a{step.ordinal}"
            writer.append("attempt-start", journey=step.journey, ordinal=step.ordinal, attemptId=attempt_id)
            writer.append("attempt-end", attemptId=attempt_id, **self.fields(queues[step.journey].pop(0), step.journey, attempt_id))
        if end is not False:
            status = end or ledger.next_step(writer.entries).status
            extra = {"evidence": self.put_json("aborted.json", {"error": "restore failed"})} if status == "aborted" else {}
            writer.append("end", status=status, **extra)
            writer.close()
        return writer

    def lines(self):
        return (self.root / ledger.LEDGER_NAME).read_bytes().split(b"\n")[:-1]

    def rewrite(self, entries, *, keep_seq=False):
        """Writes ``entries`` with a correct prev chain (and, unless ``keep_seq``, correct seq), so only the defect remains."""
        prev, out = None, b""
        for index, entry in enumerate(entries):
            entry = {**entry, "seq": entry["seq"] if keep_seq else index + 1, "prev": prev}
            line = ledger._line(entry)
            prev = hashlib.sha256(line).hexdigest()
            out += line
        (self.root / ledger.LEDGER_NAME).write_bytes(out)


ALL_VALID = {"tab-entry": "vvv", "period-switch": "vvv", "scroll": "vvv"}


class S5LedgerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bench = Bench(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def assemble(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = ledger.main(["assemble", "--root", str(self.root)])
        return code, json.loads(out.getvalue())

    def rejects(self, text):
        with self.assertRaises(ledger.LedgerError) as caught:
            ledger.load_ledger(self.root)
        self.assertIn(text, str(caught.exception))

    # --- the rule -------------------------------------------------------------------------------------------------
    def test_a_complete_run_is_assembled_into_a_manifest_the_gate_accepts(self):
        self.bench.run(ALL_VALID)
        code, summary = self.assemble()
        self.assertEqual((0, "complete", "pass"), (code, summary["status"], summary["verdict"]))
        manifest = gate.load_json(self.root / ledger.MANIFEST_NAME)
        gate.validate_manifest(manifest, self.root)
        self.assertEqual(list(ledger.JOURNEY_ORDER), [journey["id"] for journey in manifest["journeys"]])
        for journey in manifest["journeys"]:
            self.assertEqual([attempt["runId"] for attempt in journey["attempts"]], [run["runId"] for run in journey["runs"]])
            self.assertEqual([attempt["attemptId"] for attempt in journey["attempts"]], [run["runId"] for run in journey["runs"]])
            self.assertEqual(300, journey["frameCount"])
        self.assertNotIn("approval", manifest)

    def test_invalid_attempts_count_and_the_third_valid_on_the_fifth_attempt_ends_the_journey(self):
        self.bench.run({"tab-entry": "evmvv", "period-switch": "vvv", "scroll": "vvv"})
        code, _ = self.assemble()
        self.assertEqual(0, code)
        tab = gate.load_json(self.root / ledger.MANIFEST_NAME)["journeys"][0]
        self.assertEqual(["invalid", "valid", "invalid", "valid", "valid"], [attempt["outcome"] for attempt in tab["attempts"]])
        self.assertEqual(["environment-invalid", "result-malformed"], [a["reason"] for a in tab["attempts"] if a["outcome"] == "invalid"])
        self.assertEqual([1, 2, 3, 4, 5], [attempt["ordinal"] for attempt in tab["attempts"]])

    def test_a_threshold_miss_is_a_valid_run_and_fails_the_complete_manifest(self):
        self.bench.run({"tab-entry": "vfv", "period-switch": "vvv", "scroll": "fff"})
        code, summary = self.assemble()
        self.assertEqual((1, "complete", "fail"), (code, summary["status"], summary["verdict"]))
        manifest = gate.load_json(self.root / ledger.MANIFEST_NAME)
        self.assertEqual(["pass", "pass", "fail"], [journey["verdict"] for journey in manifest["journeys"]])

    def test_five_attempts_with_two_valid_end_the_measurement_without_a_document(self):
        self.bench.run({"tab-entry": "evvem", "period-switch": "vvv"})
        self.assertEqual("attempts-exhausted", ledger.load_ledger(self.root)[-1]["status"])
        code, summary = self.assemble()
        self.assertEqual((1, "attempts-exhausted", None), (code, summary["status"], summary["document"]))
        self.assertEqual({ledger.LEDGER_NAME}, {p.name for p in self.root.iterdir() if p.name in (ledger.LEDGER_NAME, ledger.MANIFEST_NAME, ledger.FAILURE_REPORT_NAME)})

    def test_a_behavior_failure_ends_everything_and_is_reported(self):
        self.bench.run({"tab-entry": "vvv", "period-switch": "vb", "scroll": "vvv"})
        entries = ledger.load_ledger(self.root)
        self.assertNotIn("scroll", {entry.get("journey") for entry in entries})
        code, summary = self.assemble()
        self.assertEqual((1, "behavior-failed", ledger.FAILURE_REPORT_NAME), (code, summary["status"], summary["document"]))
        report = gate.load_json(self.root / ledger.FAILURE_REPORT_NAME)
        gate.validate_failure_report(report, self.root)
        self.assertEqual(("period-switch", "period-switch-a2", 2), (report["journey"], report["attemptId"], report["ordinal"]))
        self.assertFalse((self.root / ledger.MANIFEST_NAME).exists())

    def test_aborted_needs_its_evidence_may_close_an_unfinished_attempt_and_has_no_document(self):
        writer = self.bench.run({"tab-entry": "v"}, end=False)
        writer.append("attempt-start", journey="tab-entry", ordinal=2, attemptId="tab-entry-a2")
        writer.append("end", status="aborted", evidence=self.bench.put_json("aborted.json", {"error": "SIGTERM"}))
        writer.close()
        code, summary = self.assemble()
        self.assertEqual((1, "aborted", None), (code, summary["status"], summary["document"]))
        entries = ledger.load_ledger(self.root)
        self.bench.rewrite(entries[:-1] + [{key: value for key, value in entries[-1].items() if key != "evidence"}])
        self.rejects("must have exactly")

    def test_aborted_after_the_rule_ended_the_measurement_is_allowed(self):
        self.bench.run(ALL_VALID, end="aborted")
        self.assertEqual("aborted", ledger.load_ledger(self.root)[-1]["status"])
        self.assertEqual(1, self.assemble()[0])
        self.assertFalse((self.root / ledger.MANIFEST_NAME).exists())

    def test_an_unfinished_attempt_stops_the_prefix_and_the_root_is_not_assembled(self):
        writer = self.bench.run({"tab-entry": "v"}, end=False)
        writer.append("attempt-start", journey="tab-entry", ordinal=2, attemptId="tab-entry-a2")
        writer.close()
        prefix = ledger.read_ledger(self.root)
        self.assertEqual("attempt-start", prefix[-1]["type"])
        with self.assertRaises(ledger.LedgerError):
            ledger.next_step(prefix)
        self.rejects("has no end")
        self.assertEqual(2, self.assemble()[0])

    def test_a_session_only_prefix_starts_the_first_journey(self):
        self.bench.run({}, end=False).close()
        self.assertEqual(ledger.Step("tab-entry", 1, None), ledger.next_step(ledger.read_ledger(self.root)))
        self.rejects("no end line")

    # --- ledger defects -------------------------------------------------------------------------------------------
    def test_the_chain_catches_edited_dropped_reordered_and_partial_lines(self):
        self.bench.run(ALL_VALID)
        lines = self.bench.lines()
        path = self.root / ledger.LEDGER_NAME
        cases = {
            "edited": lines[:1] + [lines[1].replace(b'"journey":"tab-entry"', b'"journey":"tab-entrx"')] + lines[2:],
            # Only the chain sees this one: the time stays a valid UTC time and nothing else reads it.
            "edited time": lines[:1] + [lines[1].replace(b'"at":"20', b'"at":"19')] + lines[2:],
            "dropped": lines[:3] + lines[4:],
            "swapped": lines[:3] + [lines[4], lines[3]] + lines[5:],
        }
        for name, case in cases.items():
            self.assertNotEqual(lines, case, name)
            path.write_bytes(b"\n".join(case) + b"\n")
            with self.subTest(name), self.assertRaises(ledger.LedgerError):
                ledger.load_ledger(self.root)
        path.write_bytes(b"\n".join(lines))
        self.rejects("partial line")
        path.write_bytes(b"\n".join(lines[:2] + [b""] + lines[2:]) + b"\n")
        self.rejects("line 3")
        path.write_bytes(b"")
        self.rejects("empty")

    def test_semantic_defects_are_refused_even_with_a_correct_chain(self):
        self.bench.run(ALL_VALID)
        good = ledger.load_ledger(self.root)
        start = next(index for index, entry in enumerate(good) if entry["type"] == "attempt-start")

        def with_entry(index, **changes):
            entries = copy.deepcopy(good)
            entries[index] = {**entries[index], **changes}
            return entries

        invalid = {key: value for key, value in good[start + 1].items() if key not in ("result", "behavior", "preflight", "postflight", "traces")}
        invalid.update(outcome="invalid", reason="flaky", evidence=good[start + 1]["behavior"])
        cases = {
            "second session": (good[:2] + [good[0]] + good[2:], "second session"),
            "after end": (good + [good[-1]], "follows the end"),
            "journey order": (with_entry(start, journey="scroll"), "the rule says"),
            "ordinal skip": (with_entry(start, ordinal=2), "the rule says"),
            "bool ordinal": (with_entry(start, ordinal=True), "#True"),
            "float ordinal": (with_entry(start, ordinal=1.0), "#1.0"),
            "duplicate id": (with_entry(start + 2, attemptId=good[start]["attemptId"]), "used once"),
            "bad id": (with_entry(start, attemptId="Upper"), "used once"),
            "other end": (with_entry(start + 1, attemptId="tab-entry-a9"), "not the one just started"),
            "fourth tab-entry": (good[:start + 6] + [{**good[start], "ordinal": 4, "attemptId": "extra"}] + good[start + 6:],
                                 "journey='period-switch', ordinal=1"),
            "fourth valid": (good[:-1] + [{**good[start], "ordinal": 4, "attemptId": "extra", "journey": "scroll"}, good[-1]],
                             "status='complete'"),
            "end status": (with_entry(len(good) - 1, status="attempts-exhausted"), "the attempts say"),
            "local time": (with_entry(1, at="2026-10-14T10:00:00+09:00"), "explicit UTC"),
            # A zero offset spelled -00:00 parses as UTC; only the spelling rule (the gate's Z / +00:00) refuses it.
            "minus zero offset": (with_entry(1, at="2026-10-14T10:00:00-00:00"), "explicit UTC"),
            # Ends in +00:00 yet parses as a naive datetime (Python 3.13 fromisoformat), so only the parsed-offset check refuses it.
            "naive with utc suffix": (with_entry(1, at="2026-10-14+00:00"), "explicit UTC"),
            "extra key": (with_entry(1, note="x"), "must have exactly"),
            "unknown outcome": (with_entry(start + 1, outcome="skipped"), "has outcome"),
            "unknown reason": (good[:start + 1] + [invalid] + good[start + 2:], "reason must be one of"),
            "session commit": (with_entry(0, sourceCommit="A" * 40), "40-hex"),
            "session contract": (with_entry(0, measurementContract={**good[0]["measurementContract"], "maxAttempts": 6}),
                                 "measurement contract"),
            "session artifacts": (with_entry(0, artifacts={k: v for k, v in good[0]["artifacts"].items() if k != "parser"}),
                                  "artifacts must be exactly"),
            "session first": (good[1:2] + good[:1] + good[2:], "first line must be the session"),
            "session gate": (with_entry(0, gate="S11"), "schema 1 for gate S5"),
            "start after start": (good[:start + 1] + [{**good[start], "attemptId": "again"}] + good[start + 1:], "no new attempt"),
            "four traces": (with_entry(start + 1, traces=good[start + 1]["traces"][:4]), "traces must list 5"),
            "end unknown": (with_entry(len(good) - 1, status="done"), "status must be one of"),
            "end inside attempt": (good[:start + 1] + [good[-1]], "only aborted may"),
        }
        for name, (entries, text) in cases.items():
            self.bench.rewrite(entries)
            with self.subTest(name):
                self.rejects(text)

    def test_a_wrong_seq_is_refused_even_when_the_chain_is_correct(self):
        self.bench.run(ALL_VALID)
        entries = ledger.load_ledger(self.root)
        entries[3] = {**entries[3], "seq": 9}
        self.bench.rewrite(entries, keep_seq=True)
        self.rejects("line 4 has seq 9")

    def test_fields_of_the_wrong_json_type_are_refused_not_crashed(self):
        self.bench.run({"tab-entry": "evvv", "period-switch": "vvv", "scroll": "vvv"})
        good = ledger.load_ledger(self.root)
        cases = {
            "type": (1, {"type": ["attempt-start"]}, "type must be a string"),
            "outcome": (2, {"outcome": ["invalid"]}, "outcome must be a string"),
            "status": (len(good) - 1, {"status": ["complete"]}, "status must be a string"),
            "reason": (2, {"reason": ["environment-invalid"]}, "reason must be one of"),
        }
        for name, (index, change, text) in cases.items():
            entries = copy.deepcopy(good)
            entries[index] = {**entries[index], **change}
            self.bench.rewrite(entries)
            with self.subTest(name):
                self.rejects(text)
        self.bench.rewrite(good)
        self.assertEqual(0, self.assemble()[0])

    def test_times_must_parse_as_utc(self):
        self.bench.run({"tab-entry": "v"}, end="aborted")
        good = ledger.load_ledger(self.root)
        for at in ("nonsenseZ", "Z", "2026-99-99T25:61:61+00:00", "2026-10-14T10:00:00+01:00Z"):
            entries = copy.deepcopy(good)
            entries[1] = {**entries[1], "at": at}
            self.bench.rewrite(entries)
            with self.subTest(at):
                self.rejects("malformed UTC time")

    def test_a_sealed_ledger_offers_no_further_step(self):
        self.bench.run({"tab-entry": "v"}, end="aborted")
        self.assertEqual(ledger.Step(None, None, "aborted"), ledger.next_step(ledger.load_ledger(self.root)))
        with tempfile.TemporaryDirectory() as temp:
            bench = Bench(Path(temp))
            bench.run(ALL_VALID, end="aborted")
            self.assertEqual(ledger.Step(None, None, "aborted"), ledger.next_step(ledger.load_ledger(bench.root)))

    def test_aborted_evidence_must_be_a_json_object(self):
        self.bench.run({"tab-entry": "v"}, end="aborted")
        entries = ledger.load_ledger(self.root)
        for name, data, error in (("array", b"[1]", ledger.LedgerError), ("source", b"print('x')\n", gate.InvalidEvidence)):
            entries[-1] = {**entries[-1], "evidence": self.bench.put(f"aborted-{name}.json", data)}
            self.bench.rewrite(entries)
            with self.subTest(name), self.assertRaises(error):
                ledger.load_ledger(self.root)
            self.assertEqual(2, self.assemble()[0])

    def test_a_result_that_is_not_an_object_is_defective_evidence(self):
        for value in (b"[]", b"null", b'"text"', b"3"):
            with self.subTest(value), tempfile.TemporaryDirectory() as temp:
                bench = Bench(Path(temp))
                original = bench.fields

                def replace(code, journey, attempt_id, bench=bench, original=original, value=value):
                    fields = original(code, journey, attempt_id)
                    if attempt_id == "period-switch-a2":
                        fields["result"] = bench.put(fields["result"]["path"], value)
                    return fields

                bench.fields = replace
                bench.run(ALL_VALID)
                with self.assertRaises(gate.InvalidEvidence) as caught:
                    ledger.build_manifest(ledger.load_ledger(bench.root), bench.root)
                self.assertIn("result root must be an object", str(caught.exception))

    def test_nothing_may_start_after_a_behavior_failure(self):
        self.bench.run({"tab-entry": "b"})
        entries = ledger.load_ledger(self.root)
        self.bench.rewrite(entries[:-1] + [{**entries[1], "ordinal": 2, "attemptId": "retry"}, entries[-1]])
        self.rejects("status='behavior-failed'")

    def test_duplicate_keys_nonfinite_numbers_and_seq_types_are_refused(self):
        self.bench.run({"tab-entry": "v"}, end=False).close()
        lines = self.bench.lines()
        path = self.root / ledger.LEDGER_NAME
        for name, line in {
            "duplicate key": lines[1].replace(b'"seq":2', b'"seq":2,"seq":2'),
            "nonfinite": lines[1].replace(b'"ordinal":1', b'"ordinal":NaN'),
        }.items():
            self.assertNotEqual(lines[1], line, name)
            path.write_bytes(b"\n".join([lines[0], line] + lines[2:]) + b"\n")
            with self.subTest(name), self.assertRaises(ledger.LedgerError):
                ledger.read_ledger(self.root)
        # true == 1 and 1.0 == 1 in Python, so only the type check refuses a session line whose seq is either.
        for value, shown in ((b"true", "seq True"), (b"1.0", "seq 1.0")):
            session = lines[0].replace(b'"seq":1', b'"seq":' + value)
            self.assertNotEqual(lines[0], session)
            path.write_bytes(session + b"\n")
            with self.subTest(shown), self.assertRaises(ledger.LedgerError) as caught:
                ledger.read_ledger(self.root)
            self.assertIn(shown, str(caught.exception))

    def test_every_referenced_file_is_bound_by_hash(self):
        cases = {
            "session artifact": (ALL_VALID, None, "artifact-fixturePayload.bin"),
            "valid result": (ALL_VALID, None, "tab-entry-a2-result.json"),
            "valid behavior": (ALL_VALID, None, "scroll-a1-behavior.json"),
            "valid snapshot": (ALL_VALID, None, "period-switch-a3-postflight.json"),
            "valid trace": (ALL_VALID, None, "tab-entry-a1-trace-3.perfetto-trace"),
            "invalid evidence": ({"tab-entry": "evvv", "period-switch": "vvv", "scroll": "vvv"}, None, "tab-entry-a1-env.json"),
            "failed behavior": ({"tab-entry": "b"}, None, "tab-entry-a1-behavior.json"),
            "aborted evidence": ({"tab-entry": "v"}, "aborted", "aborted.json"),
        }
        for name, (plan, end, victim) in cases.items():
            with self.subTest(name), tempfile.TemporaryDirectory() as temp:
                bench = Bench(Path(temp))
                bench.run(plan, end=end)
                ledger.load_ledger(bench.root)
                (bench.root / victim).write_bytes(b"changed")
                with self.assertRaises(ledger.LedgerError) as caught:
                    ledger.load_ledger(bench.root)
                self.assertIn("hash mismatch", str(caught.exception))

    # --- assembly against the gate --------------------------------------------------------------------------------
    def test_the_gate_still_refuses_what_the_ledger_cannot_see(self):
        """Faults inside the files: the ledger binds them by hash, the gate's own check refuses the manifest."""
        def refuses(mutate, text):
            temp = tempfile.TemporaryDirectory()
            self.addCleanup(temp.cleanup)
            bench = Bench(Path(temp.name))
            bench.fields = (lambda original: (lambda code, journey, attempt_id: mutate(bench, original(code, journey, attempt_id), journey, attempt_id)))(bench.fields)
            bench.run(ALL_VALID)
            with self.assertRaises(gate.InvalidEvidence) as caught:
                ledger.build_manifest(ledger.load_ledger(bench.root), bench.root)
            self.assertIn(text, str(caught.exception))
            self.assertFalse((bench.root / ledger.MANIFEST_NAME).exists())

        def snapshot(field, value):
            def mutate(bench, fields, journey, attempt_id):
                if fields["outcome"] == "valid" and attempt_id == "scroll-a2":
                    document = gate.load_json(bench.root / fields["preflight"]["path"])
                    document[field] = value
                    fields["preflight"] = bench.put_json(fields["preflight"]["path"], document)
                return fields
            return mutate

        def identity(bench, fields, journey, attempt_id):
            if attempt_id == "period-switch-a1":
                fields["result"] = bench.put_json(fields["result"]["path"], macro_result(trace_prefix=f"{attempt_id}-trace", benchmark_name="tabEntry"))
            return fields

        def reversed_traces(bench, fields, journey, attempt_id):
            if attempt_id == "tab-entry-a3":
                fields["traces"] = list(reversed(fields["traces"]))
            return fields

        def reused_bytes(bench, fields, journey, attempt_id):
            if attempt_id == "tab-entry-a2":
                fields["traces"][1] = bench.put("copy.perfetto-trace", (bench.root / fields["traces"][0]["path"]).read_bytes())
            return fields

        refuses(snapshot("runId", "someone-else"), "runId does not match")
        refuses(snapshot("phase", "postflight"), "phase does not match")
        refuses(snapshot("settings", {}), "settings")
        refuses(snapshot("observedAt", "2026-10-14T23:59:59+00:00"), "observation timestamps")  # the gate rewraps its own order error
        refuses(identity, "benchmark identity mismatch")
        refuses(reversed_traces, "profilerOutputs filenames")
        refuses(reused_bytes, "byte-identical")

    def test_a_parser_artifact_other_than_the_running_gate_is_refused(self):
        bench = self.bench
        original = bench.artifacts
        bench.artifacts = lambda: {**original(), "parser": bench.put("other-parser.py", b"# not the gate\n")}
        bench.run(ALL_VALID)
        with self.assertRaises(gate.InvalidEvidence):
            ledger.build_manifest(ledger.load_ledger(self.root), self.root)

    def test_a_malformed_label_on_a_wellformed_result_is_refused(self):
        original = self.bench.fields

        def disguise(code, journey, attempt_id):
            fields = original(code, journey, attempt_id)
            if code == "m":
                good = macro_result(benchmark_name=BENCHMARK_METHODS[journey], trace_prefix=f"{attempt_id}-x")
                fields["evidence"] = self.bench.put_json(f"{attempt_id}-result.json", good)
            return fields

        self.bench.fields = disguise
        self.bench.run({"tab-entry": "mvvv", "period-switch": "vvv", "scroll": "vvv"})
        with self.assertRaises(gate.InvalidEvidence) as caught:
            ledger.build_manifest(ledger.load_ledger(self.root), self.root)
        self.assertIn("structurally valid", str(caught.exception))

    def test_unreadable_malformed_evidence_is_refused(self):
        original = self.bench.fields

        def garble(code, journey, attempt_id):
            fields = original(code, journey, attempt_id)
            if code == "m":
                fields["evidence"] = self.bench.put(f"{attempt_id}-result.json", b'{"benchmarks": [1, 2')
            return fields

        self.bench.fields = garble
        self.bench.run({"tab-entry": "mvvv", "period-switch": "vvv", "scroll": "vvv"})
        with self.assertRaises(gate.InvalidEvidence) as caught:
            ledger.build_manifest(ledger.load_ledger(self.root), self.root)
        self.assertIn("strict JSON", str(caught.exception))

    def test_a_document_created_just_before_publication_is_not_overwritten(self):
        self.bench.run(ALL_VALID)
        real_link = os.link

        def racing_link(source, destination):
            Path(destination).write_bytes(b"someone else\n")
            return real_link(source, destination)

        with unittest.mock.patch.object(ledger.os, "link", racing_link):
            self.assertEqual(2, self.assemble()[0])
        self.assertEqual(b"someone else\n", (self.root / ledger.MANIFEST_NAME).read_bytes())
        self.assertEqual([], [p.name for p in self.root.iterdir() if p.name.endswith(".tmp")])

    def test_a_failure_report_needs_valid_snapshots(self):
        original = self.bench.fields

        def drift(code, journey, attempt_id):
            fields = original(code, journey, attempt_id)
            if code == "b":
                document = gate.load_json(self.root / fields["postflight"]["path"])
                document["thermalStatus"] = 2
                fields["postflight"] = self.bench.put_json(fields["postflight"]["path"], document)
            return fields

        self.bench.fields = drift
        self.bench.run({"tab-entry": "b"})
        with self.assertRaises(gate.InvalidEvidence):
            ledger.build_failure_report(ledger.load_ledger(self.root), self.root)
        before = (self.root / ledger.LEDGER_NAME).read_bytes()
        self.assertEqual(2, self.assemble()[0])
        self.assertFalse((self.root / ledger.FAILURE_REPORT_NAME).exists())
        self.assertEqual(before, (self.root / ledger.LEDGER_NAME).read_bytes())

    def test_a_missing_failure_snapshot_is_defective_evidence(self):
        self.bench.run({"tab-entry": "vb"})
        (self.root / "tab-entry-a2-postflight.json").unlink()
        before = (self.root / ledger.LEDGER_NAME).read_bytes()
        self.assertEqual(2, self.assemble()[0])
        self.assertFalse((self.root / ledger.FAILURE_REPORT_NAME).exists())
        self.assertEqual(before, (self.root / ledger.LEDGER_NAME).read_bytes())

    def test_each_document_builder_refuses_a_run_of_another_status(self):
        self.bench.run({"tab-entry": "b"})
        with self.assertRaises(ledger.LedgerError):
            ledger.build_manifest(ledger.load_ledger(self.root), self.root)
        with tempfile.TemporaryDirectory() as temp:
            bench = Bench(Path(temp))
            bench.run(ALL_VALID)
            with self.assertRaises(ledger.LedgerError):
                ledger.build_failure_report(ledger.load_ledger(bench.root), bench.root)

    def test_documents_are_never_overwritten(self):
        self.bench.run(ALL_VALID)
        self.assertEqual(0, self.assemble()[0])
        first = (self.root / ledger.MANIFEST_NAME).read_bytes()
        self.assertEqual(2, self.assemble()[0])
        self.assertEqual(first, (self.root / ledger.MANIFEST_NAME).read_bytes())
        (self.root / ledger.MANIFEST_NAME).unlink()
        (self.root / ledger.MANIFEST_NAME).symlink_to(self.root / "elsewhere.json")
        self.assertEqual(2, self.assemble()[0])
        self.assertFalse((self.root / "elsewhere.json").exists())
        self.assertEqual([], [p.name for p in self.root.iterdir() if p.name.endswith(".tmp")])

    def test_the_writer_refuses_an_existing_ledger_and_appends_whole_lines(self):
        writer = self.bench.run({}, end=False)
        with self.assertRaises(FileExistsError):
            ledger.LedgerWriter.create(self.root, source_commit="a" * 40, artifacts=self.bench.artifacts())
        self.assertEqual(1, len(self.bench.lines()))
        writer.close()
        with self.assertRaises(ledger.LedgerError):
            writer.append("end", status="complete")
        self.assertEqual(1, len(self.bench.lines()))


if __name__ == "__main__":
    unittest.main()
