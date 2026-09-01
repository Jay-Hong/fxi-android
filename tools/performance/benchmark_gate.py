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
EXPECTED_RAW_COMPILATION_MODE = "speed-profile"
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
        "F711NKSSEKZE1:user/release-keys"
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


class InvalidEvidence(ValueError):
    """Input is missing, malformed, ambiguous, or internally inconsistent."""


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
    if context.get("compilationMode") != EXPECTED_RAW_COMPILATION_MODE:
        raise InvalidEvidence("Macrobenchmark compilationMode differs from measurement contract")
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
                or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", run_id)
                or run_id in seen_run_ids
            ):
                raise InvalidEvidence(f"{label}.runId must be globally unique and stable")
            seen_run_ids.add(run_id)
            result_path = validate_artifact_ref(run.get("result"), artifact_root, f"{label}.result")
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
            run_artifacts = [result_path, preflight_path, postflight_path, *trace_paths]
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
            extracted.append(extracted_run)

        calculated = evaluate_triplet(extracted, mode="s5")
        if not math.isclose(reported.p95_ms, calculated.median_p95_ms, abs_tol=1e-6):
            raise InvalidEvidence(f"journey medianP95Ms is inconsistent: {journey_id}")
        if not math.isclose(reported.p99_ms, calculated.median_p99_ms, abs_tol=1e-6):
            raise InvalidEvidence(f"journey medianP99Ms is inconsistent: {journey_id}")
        if reported.frame_count != sum(run.frame_count for run in extracted):
            raise InvalidEvidence(f"journey frameCount is inconsistent: {journey_id}")
        if manifest.get("gate") == "S5":
            if journey.get("verdict") != calculated.verdict:
                raise InvalidEvidence(f"S5 journey verdict is inconsistent: {journey_id}")
            journey_verdicts.append(calculated.verdict)
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
                if not isinstance(attempt_run_id, str) or not re.fullmatch(
                    r"[a-z0-9][a-z0-9._-]{0,63}", attempt_run_id
                ):
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
    if seen != EXPECTED_JOURNEY_IDS:
        raise InvalidEvidence(
            f"journey ids must equal {sorted(EXPECTED_JOURNEY_IDS)}, got {sorted(seen)}"
        )
    if manifest.get("gate") == "S5":
        aggregate = "pass" if all(value == "pass" for value in journey_verdicts) else "fail"
        if manifest.get("verdict") != aggregate:
            raise InvalidEvidence("S5 manifest verdict is inconsistent with journey verdicts")


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
        run_paths = journey.get("runs")
        if not isinstance(run_paths, list) or len(run_paths) != EXPECTED_VALID_RUNS:
            raise InvalidEvidence(f"{journey_id} must reference exactly {EXPECTED_VALID_RUNS} results")
        metrics: list[RunMetrics] = []
        for run_index, relative in enumerate(run_paths):
            result_path = _safe_artifact(
                artifact_root, relative, f"{journey_id}.runs[{run_index}]"
            )
            if not result_path.is_file():
                raise InvalidEvidence(f"run result does not exist: {relative}")
            resolved_result = result_path.resolve()
            if resolved_result in used_results:
                raise InvalidEvidence("three independent runs may not reuse a result JSON")
            used_results.add(resolved_result)
            result_digest = sha256_file(result_path)
            if result_digest in used_result_digests:
                raise InvalidEvidence("three independent runs may not reuse byte-identical results")
            used_result_digests.add(result_digest)
            raw = load_json(result_path)
            if not isinstance(raw, dict):
                raise InvalidEvidence(f"run result root must be an object: {relative}")
            metrics.append(extract_macrobenchmark_run(raw, expected_identity=benchmark_identity))
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
