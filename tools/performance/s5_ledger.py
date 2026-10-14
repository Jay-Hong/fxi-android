#!/usr/bin/env python3
"""S5 attempt ledger and evidence assembly (S5-4 E slice 6a). Standard library only.

The S5 runner appends one JSON line per event to ``<root>/ledger.jsonl`` and never rewrites it. Each line carries ``seq``
(1, 2, ...) and ``prev`` (the SHA-256 of the previous line's bytes, null on the first), so a reordered, dropped or edited line
is detected when the ledger is read. That catches accidents, not a deliberate rewrite of the whole file; nothing on the
host can.

Lines, in order: one ``session`` (source commit, pinned libraries and measurement contract, the seven top-level artifacts),
then per attempt an ``attempt-start`` written before the device is touched and its ``attempt-end``, then one ``end``. The
journeys run in the order tab-entry, period-switch, scroll. A journey ends at its third valid attempt (a threshold miss is
valid); five attempts without three valid ones end the measurement (attempts-exhausted); a behavior failure ends it at once
(behavior-failed, README "A behavior failure is terminal"). Those are only candidates: the runner writes the ``end`` after
its cleanup and settings restore are read back, and anything it cannot finish or express ends as ``aborted`` with a
diagnostic file. Only ``aborted`` may close an attempt that has no ``attempt-end``. A root whose process ended is never
resumed: an unfinished attempt filled in later could not be told apart from a hidden one.

``assemble`` turns a complete ledger into the evidence manifest, or a behavior-failed one into the terminal failure report,
and checks the document with benchmark_gate itself before writing it. Exit codes follow benchmark_gate: 0 pass, 1 a
legitimate fail (including a run that could not finish), 2 defective evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence

import benchmark_gate as gate

LEDGER_NAME = "ledger.jsonl"
MANIFEST_NAME = "manifest.json"
FAILURE_REPORT_NAME = "failure-report.json"
SCHEMA_VERSION = 1
JOURNEY_ORDER = ("tab-entry", "period-switch", "scroll")
MAX_ATTEMPTS = gate.EXPECTED_MEASUREMENT_CONTRACT["maxAttempts"]
VALID_RUNS = gate.EXPECTED_VALID_RUNS
COMMON_KEYS = {"seq", "prev", "type", "at"}
ENTRY_KEYS = {
    "session": COMMON_KEYS | {"schemaVersion", "gate", "sourceCommit", "libraries", "measurementContract", "artifacts"},
    "attempt-start": COMMON_KEYS | {"journey", "ordinal", "attemptId"},
}
END_LINE_KEYS = {"aborted": COMMON_KEYS | {"status", "evidence"}}
END_LINE_DEFAULT = COMMON_KEYS | {"status"}
END_KEYS = {
    "valid": COMMON_KEYS | {"attemptId", "outcome", "result", "behavior", "preflight", "postflight", "traces"},
    "invalid": COMMON_KEYS | {"attemptId", "outcome", "reason", "evidence"},
    "behavior-failed": COMMON_KEYS | {"attemptId", "outcome", "behavior", "preflight", "postflight"},
}
INVALID_REASONS = {"environment-invalid", "result-malformed"}
END_STATUSES = {"complete", "behavior-failed", "attempts-exhausted", "aborted"}


class LedgerError(ValueError):
    """The ledger is not a well-formed record of one run."""


@dataclass(frozen=True)
class Step:
    """What the run does next: an attempt (journey, ordinal), or the end with its status."""

    journey: str | None
    ordinal: int | None
    status: str | None


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _line(entry: Mapping[str, Any]) -> bytes:
    return (json.dumps(entry, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


class LedgerWriter:
    """The one writer of a ledger: ``create`` makes the file (it must not exist), keeps that descriptor and writes the session.
    Every append writes one whole line and fsyncs before returning; ``entries`` is what has been written, for ``next_step``."""

    def __init__(self, path: Path, descriptor: int) -> None:
        self.path = path
        self.descriptor: int | None = descriptor
        self.prev: str | None = None
        self.entries: list[dict[str, Any]] = []

    @classmethod
    def create(cls, root: Path, *, source_commit: str, artifacts: Mapping[str, Any]) -> "LedgerWriter":
        path = root / LEDGER_NAME
        writer = cls(path, os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_EXCL, 0o600))
        try:
            writer.append(
                "session",
                schemaVersion=SCHEMA_VERSION,
                gate="S5",
                sourceCommit=source_commit,
                libraries=dict(gate.EXPECTED_LIBRARIES),
                measurementContract=dict(gate.EXPECTED_MEASUREMENT_CONTRACT),
                artifacts=dict(artifacts),
            )
        except BaseException:
            writer.close()
            raise
        return writer

    def append(self, entry_type: str, **fields: Any) -> dict[str, Any]:
        if self.descriptor is None:
            raise LedgerError("the ledger writer is closed")
        entry = {"seq": len(self.entries) + 1, "prev": self.prev, "type": entry_type, "at": _now(), **fields}
        data = _line(entry)
        written = 0
        while written < len(data):
            written += os.write(self.descriptor, data[written:])
        os.fsync(self.descriptor)
        self.prev = hashlib.sha256(data).hexdigest()
        self.entries.append(entry)
        return entry

    def close(self) -> None:
        if self.descriptor is not None:
            descriptor, self.descriptor = self.descriptor, None
            os.close(descriptor)


def next_step(entries: Sequence[Mapping[str, Any]]) -> Step:
    """The next attempt, or the measurement's end candidate, after a checked prefix. Raises if an attempt is unfinished."""
    if entries and entries[-1]["type"] == "end":
        return Step(None, None, entries[-1]["status"])
    starts: dict[str, Mapping[str, Any]] = {}
    per_journey: dict[str, list[str]] = {journey: [] for journey in JOURNEY_ORDER}
    pending: str | None = None
    for entry in entries[1:]:
        if entry["type"] == "attempt-start":
            starts[entry["attemptId"]] = entry
            pending = entry["attemptId"]
        elif entry["type"] == "attempt-end":
            per_journey[starts[entry["attemptId"]]["journey"]].append(entry["outcome"])
            pending = None
    if pending is not None:
        raise LedgerError(f"attempt {pending} has no end; no new attempt may start")
    for journey in JOURNEY_ORDER:
        outcomes = per_journey[journey]
        if outcomes and outcomes[-1] == "behavior-failed":
            return Step(None, None, "behavior-failed")
        if outcomes.count("valid") >= VALID_RUNS:
            continue
        if len(outcomes) >= MAX_ATTEMPTS:
            return Step(None, None, "attempts-exhausted")
        return Step(journey, len(outcomes) + 1, None)
    return Step(None, None, "complete")


def _ref(value: Any, root: Path, label: str) -> None:
    try:
        gate.validate_artifact_ref(value, root, label)
    except gate.InvalidEvidence as error:
        raise LedgerError(str(error)) from error


def read_ledger(root: Path) -> list[dict[str, Any]]:
    """Reads and checks a ledger as far as it goes: chain, exact fields, order, pairs, the rule for every start, artifact
    hashes. It may stop without an end, with at most one unfinished attempt-start last."""
    path = root / LEDGER_NAME
    try:
        data = path.read_bytes()
    except OSError as error:
        raise LedgerError(f"cannot read {LEDGER_NAME}: {error}") from error
    if not data.endswith(b"\n"):
        raise LedgerError("the ledger is empty or ends with a partial line")
    entries: list[dict[str, Any]] = []
    prev: str | None = None
    attempt_ids: set[str] = set()
    for index, raw in enumerate(data[:-1].split(b"\n")):
        line = raw + b"\n"
        try:
            entry = json.loads(raw.decode("utf-8"), object_pairs_hook=gate._unique_object,
                               parse_constant=gate._reject_constant)
        except (UnicodeError, ValueError) as error:
            raise LedgerError(f"line {index + 1} is not one JSON object: {error}") from error
        if not isinstance(entry, dict):
            raise LedgerError(f"line {index + 1} is not a JSON object")
        if type(entry.get("seq")) is not int or entry["seq"] != index + 1:
            raise LedgerError(f"line {index + 1} has seq {entry.get('seq')!r}")
        if entry.get("prev") != prev:
            raise LedgerError(f"line {index + 1} does not follow the line before it")
        prev = hashlib.sha256(line).hexdigest()
        entry_type = entry.get("type")
        if not isinstance(entry_type, str):
            raise LedgerError(f"line {index + 1} type must be a string")
        if index == 0 and entry_type != "session":
            raise LedgerError("the first line must be the session")
        if index > 0 and entry_type == "session":
            raise LedgerError(f"line {index + 1} is a second session")
        if entries and entries[-1]["type"] == "end":
            raise LedgerError(f"line {index + 1} follows the end")
        if entry_type == "attempt-end":
            if not isinstance(entry.get("outcome"), str):
                raise LedgerError(f"line {index + 1} outcome must be a string")
            keys = END_KEYS.get(entry.get("outcome"))
            if keys is None:
                raise LedgerError(f"line {index + 1} has outcome {entry.get('outcome')!r}")
        elif entry_type == "end":
            if not isinstance(entry.get("status"), str):
                raise LedgerError(f"line {index + 1} status must be a string")
            keys = END_LINE_KEYS.get(entry.get("status"), END_LINE_DEFAULT)
        else:
            keys = ENTRY_KEYS.get(entry_type)
            if keys is None:
                raise LedgerError(f"line {index + 1} has type {entry_type!r}")
        if set(entry) != keys:
            raise LedgerError(f"line {index + 1} ({entry_type}) must have exactly {sorted(keys)}")
        at = entry["at"]
        if not isinstance(at, str) or not at.endswith(("Z", "+00:00")):
            raise LedgerError(f"line {index + 1} needs an explicit UTC time")
        try:
            parsed_at = datetime.fromisoformat(at.replace("Z", "+00:00"))
        except ValueError as error:
            raise LedgerError(f"line {index + 1} has a malformed UTC time") from error
        if parsed_at.tzinfo is None or parsed_at.utcoffset() != timezone.utc.utcoffset(parsed_at):
            raise LedgerError(f"line {index + 1} needs an explicit UTC time")
        label = f"line {index + 1}"
        if entry_type == "session":
            if entry["schemaVersion"] != SCHEMA_VERSION or entry["gate"] != "S5":
                raise LedgerError("the session must be schema 1 for gate S5")
            commit = entry["sourceCommit"]
            if not isinstance(commit, str) or not gate.GIT_SHA_RE.fullmatch(commit):
                raise LedgerError("the session sourceCommit must be a lowercase 40-hex Git SHA")
            if entry["libraries"] != gate.EXPECTED_LIBRARIES or entry["measurementContract"] != gate.EXPECTED_MEASUREMENT_CONTRACT:
                raise LedgerError("the session libraries and measurement contract must be the gate's")
            artifacts = entry["artifacts"]
            if not isinstance(artifacts, dict) or set(artifacts) != gate.ARTIFACT_KEYS:
                raise LedgerError(f"the session artifacts must be exactly {sorted(gate.ARTIFACT_KEYS)}")
            for key in sorted(artifacts):
                _ref(artifacts[key], root, f"{label}.artifacts.{key}")
        elif entry_type == "attempt-start":
            step = next_step(entries)
            ordinal = entry["ordinal"]
            if type(ordinal) is not int or step.status is not None or (entry["journey"], ordinal) != (step.journey, step.ordinal):
                raise LedgerError(f"{label} starts {entry['journey']!r} #{entry['ordinal']!r}; the rule says {step}")
            attempt_id = entry["attemptId"]
            if not isinstance(attempt_id, str) or not gate.RUN_ID_RE.fullmatch(attempt_id) or attempt_id in attempt_ids:
                raise LedgerError(f"{label} attemptId must be a stable id used once")
            attempt_ids.add(attempt_id)
        elif entry_type == "attempt-end":
            if entries[-1]["type"] != "attempt-start" or entries[-1]["attemptId"] != entry["attemptId"]:
                raise LedgerError(f"{label} ends an attempt that is not the one just started")
            outcome = entry["outcome"]
            if outcome == "invalid":
                if not isinstance(entry["reason"], str) or entry["reason"] not in INVALID_REASONS:
                    raise LedgerError(f"{label} reason must be one of {sorted(INVALID_REASONS)}")
                _ref(entry["evidence"], root, f"{label}.evidence")
            else:
                roles = ("result", "behavior", "preflight", "postflight") if outcome == "valid" else ("behavior", "preflight", "postflight")
                for role in roles:
                    _ref(entry[role], root, f"{label}.{role}")
                if outcome == "valid":
                    traces = entry["traces"]
                    if not isinstance(traces, list) or len(traces) != gate.EXPECTED_ITERATIONS:
                        raise LedgerError(f"{label}.traces must list {gate.EXPECTED_ITERATIONS} files")
                    for trace_index, trace in enumerate(traces):
                        _ref(trace, root, f"{label}.traces[{trace_index}]")
        else:  # end
            status = entry["status"]
            if status not in END_STATUSES:
                raise LedgerError(f"{label} status must be one of {sorted(END_STATUSES)}")
            if status == "aborted":
                _ref(entry["evidence"], root, f"{label}.evidence")
                diagnostic = gate.load_json(root / entry["evidence"]["path"])
                if not isinstance(diagnostic, dict):
                    raise LedgerError(f"{label}.evidence root must be an object")
            elif entries[-1]["type"] == "attempt-start":
                raise LedgerError(f"{label} ends as {status} inside attempt {entries[-1]['attemptId']}; only aborted may")
            elif status != next_step(entries).status:
                raise LedgerError(f"{label} says {status}; the attempts say {next_step(entries)}")
        entries.append(entry)
    return entries


def load_ledger(root: Path) -> list[dict[str, Any]]:
    """A finished ledger: ``read_ledger`` and an end line last."""
    entries = read_ledger(root)
    if entries[-1]["type"] != "end":
        last = entries[-1]
        if last["type"] == "attempt-start":
            raise LedgerError(f"attempt {last['attemptId']} has no end: the run stopped mid-attempt and this root is not resumed")
        raise LedgerError("the ledger has no end line: the run did not finish")
    return entries


def _attempts(entries: Sequence[Mapping[str, Any]]) -> list[tuple[Mapping[str, Any], Mapping[str, Any]]]:
    return [(entries[index - 1], entry) for index, entry in enumerate(entries) if entry["type"] == "attempt-end"]


def build_manifest(entries: Sequence[Mapping[str, Any]], root: Path) -> dict[str, Any]:
    """The evidence manifest of a complete ledger, checked by benchmark_gate.validate_manifest before it is returned."""
    if entries[-1]["status"] != "complete":
        raise LedgerError(f"only a complete run has a manifest; this one is {entries[-1]['status']}")
    session = entries[0]
    journeys = []
    verdicts = []
    for journey_id in JOURNEY_ORDER:
        identity = gate.EXPECTED_BENCHMARK_IDENTITIES[journey_id]
        runs, attempts, metrics = [], [], []
        for start, end in _attempts(entries):
            if start["journey"] != journey_id:
                continue
            if end["outcome"] == "valid":
                runs.append({"runId": end["attemptId"], **{role: end[role] for role in ("result", "behavior", "preflight", "postflight", "traces")}})
                attempts.append({"attemptId": end["attemptId"], "ordinal": start["ordinal"], "outcome": "valid", "runId": end["attemptId"]})
                raw = gate.load_json(root / end["result"]["path"])
                if not isinstance(raw, dict):
                    raise gate.InvalidEvidence(f"attempt {end['attemptId']} result root must be an object")
                metrics.append(gate.extract_macrobenchmark_run(raw, expected_identity=identity))
            else:
                attempts.append({"attemptId": end["attemptId"], "ordinal": start["ordinal"], "outcome": "invalid",
                                 "reason": end["reason"], "evidence": end["evidence"]})
        verdict = gate.evaluate_triplet(metrics, mode="s5")
        verdicts.append(verdict.verdict)
        journeys.append({
            "id": journey_id,
            "medianP95Ms": verdict.median_p95_ms,
            "medianP99Ms": verdict.median_p99_ms,
            "frameCount": sum(run.frame_count for run in metrics),
            "verdict": verdict.verdict,
            "runs": runs,
            "attempts": attempts,
        })
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "gate": "S5",
        "verdict": "pass" if all(value == "pass" for value in verdicts) else "fail",
        "sourceCommit": session["sourceCommit"],
        "libraries": session["libraries"],
        "measurementContract": session["measurementContract"],
        "artifacts": session["artifacts"],
        "journeys": journeys,
    }
    gate.validate_manifest(manifest, root)
    return manifest


def build_failure_report(entries: Sequence[Mapping[str, Any]], root: Path) -> dict[str, Any]:
    """The terminal report of a behavior-failed ledger, checked by benchmark_gate.validate_failure_report."""
    if entries[-1]["status"] != "behavior-failed":
        raise LedgerError(f"only a behavior-failed run has a failure report; this one is {entries[-1]['status']}")
    start, end = _attempts(entries)[-1]
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "terminal-behavior-failure",
        "gate": "S5",
        "sourceCommit": entries[0]["sourceCommit"],
        "journey": start["journey"],
        "attemptId": end["attemptId"],
        "ordinal": start["ordinal"],
        "behavior": end["behavior"],
        "preflight": end["preflight"],
        "postflight": end["postflight"],
        "verdict": "fail",
    }
    gate.validate_failure_report(report, root)
    return report


def publish_new_json(path: Path, value: Any) -> None:
    """Writes a private temporary file in the same folder and links it to ``path``: the link fails if anything (a file or a
    symlink) is already there, so nothing is overwritten and ``path`` never shows a partial document."""
    data = (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        try:
            written = 0
            while written < len(data):
                written += os.write(descriptor, data[written:])
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.link(temporary, path)
    finally:
        os.unlink(temporary)


def assemble(root: Path) -> tuple[int, dict[str, Any]]:
    entries = load_ledger(root)
    status = entries[-1]["status"]
    if status == "complete":
        manifest = build_manifest(entries, root)
        publish_new_json(root / MANIFEST_NAME, manifest)
        return (0 if manifest["verdict"] == "pass" else 1), {"status": status, "verdict": manifest["verdict"], "document": MANIFEST_NAME}
    if status == "behavior-failed":
        publish_new_json(root / FAILURE_REPORT_NAME, build_failure_report(entries, root))
        return 1, {"status": status, "verdict": "fail", "document": FAILURE_REPORT_NAME}
    return 1, {"status": status, "verdict": "fail", "document": None}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("assemble", help="write the manifest or failure report of a finished ledger")
    command.add_argument("--root", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        code, summary = assemble(args.root)
    except (LedgerError, gate.InvalidEvidence, OSError) as error:
        print(json.dumps({"error": str(error)}, ensure_ascii=False))
        return 2
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
