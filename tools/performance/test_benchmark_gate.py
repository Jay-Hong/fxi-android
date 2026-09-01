import copy
import contextlib
import io
import json
import tempfile
import unittest
from unittest import mock
from pathlib import Path

import benchmark_gate
from benchmark_gate import (
    ARTIFACT_KEYS,
    EXPECTED_LIBRARIES,
    EXPECTED_MEASUREMENT_CONTRACT,
    InvalidEvidence,
    RunMetrics,
    evaluate_fixture_case,
    evaluate_run_set,
    evaluate_triplet,
    extract_macrobenchmark_run,
    load_json,
    percentile,
    s11_p99_limit,
    sha256_file,
    validate_manifest,
    validate_s11_against_golden,
)


ROOT = Path(__file__).resolve().parent


def macro_result(
    *,
    tail=8.33,
    trace_prefix="trace",
    benchmark_class_name="com.jay.fxi.macrobenchmark.TetherGraphBenchmark",
    benchmark_name="tabEntry",
):
    runs = [[0.0] * 20 for _ in range(4)] + [[0.0] * 16 + [tail] * 4]
    flat = [value for run in runs for value in run]
    return {
        "context": {
            "build": {
                "model": "SM-F711N",
                "fingerprint": EXPECTED_MEASUREMENT_CONTRACT["fingerprint"],
                "version": {"sdk": 35},
            },
            "compilationMode": "speed-profile",
        },
        "benchmarks": [
            {
                "className": benchmark_class_name,
                "name": benchmark_name,
                "repeatIterations": 5,
                "warmupIterations": 3,
                "sampledMetrics": {
                    "frameOverrunMs": {
                        "P95": percentile(flat, 95.0),
                        "P99": percentile(flat, 99.0),
                        "runs": runs,
                    }
                },
                "metrics": {"frameCount": {"runs": [20.0, 20.0, 20.0, 20.0, 20.0]}},
                "profilerOutputs": [
                    {
                        "type": "PerfettoTrace",
                        "label": f"Trace Iteration {index}",
                        "filename": f"{trace_prefix}-{index}.perfetto-trace",
                    }
                    for index in range(5)
                ],
            }
        ]
    }


class ThresholdFixtureTest(unittest.TestCase):
    def test_checked_in_pass_fail_and_missing_cases(self):
        document = load_json(ROOT / "fixtures" / "gate-cases.json")
        for case in document["cases"]:
            with self.subTest(case=case["id"]):
                try:
                    actual = evaluate_fixture_case(case).verdict
                except InvalidEvidence:
                    actual = "invalid"
                self.assertEqual(case["expected"], actual)

    def test_s11_floor_interior_and_cap(self):
        self.assertEqual(0.0, s11_p99_limit(-3.0))
        self.assertEqual(4.0, s11_p99_limit(2.0))
        self.assertEqual(8.33, s11_p99_limit(7.0))

    def test_wrong_run_count_is_invalid(self):
        with self.assertRaisesRegex(InvalidEvidence, "exactly 3"):
            evaluate_triplet([RunMetrics(0.0, 0.0, 1)] * 2, mode="s5")


class RawMacrobenchmarkResultTest(unittest.TestCase):
    def test_extracts_actual_androidx_1_4_1_shape(self):
        result = extract_macrobenchmark_run(
            macro_result(),
            expected_identity=("com.jay.fxi.macrobenchmark.TetherGraphBenchmark", "tabEntry"),
        )
        self.assertEqual(100, result.frame_count)
        self.assertEqual(0.0, result.p95_ms)
        self.assertEqual(8.33, result.p99_ms)

    def test_class_name_and_method_name_are_both_exact(self):
        expected = ("com.jay.fxi.macrobenchmark.TetherGraphBenchmark", "tabEntry")
        wrong_class = macro_result(benchmark_class_name="com.jay.fxi.OtherBenchmark")
        wrong_method = macro_result(benchmark_name="periodSwitch")
        for document in (wrong_class, wrong_method):
            with self.subTest(document=document), self.assertRaisesRegex(
                InvalidEvidence, "identity mismatch"
            ):
                extract_macrobenchmark_run(document, expected_identity=expected)

    def test_missing_p95_p99_or_frame_count_is_invalid(self):
        mutations = []
        missing_p95 = macro_result()
        del missing_p95["benchmarks"][0]["sampledMetrics"]["frameOverrunMs"]["P95"]
        mutations.append(missing_p95)
        missing_p99 = macro_result()
        del missing_p99["benchmarks"][0]["sampledMetrics"]["frameOverrunMs"]["P99"]
        mutations.append(missing_p99)
        missing_count = macro_result()
        del missing_count["benchmarks"][0]["metrics"]["frameCount"]
        mutations.append(missing_count)
        for document in mutations:
            with self.subTest(document=document), self.assertRaises(InvalidEvidence):
                extract_macrobenchmark_run(document)

    def test_zero_frames_wrong_iterations_duplicate_trace_and_false_percentile_are_invalid(self):
        documents = []
        zero = macro_result()
        zero["benchmarks"][0]["metrics"]["frameCount"]["runs"][0] = 0
        documents.append(zero)
        wrong_iterations = macro_result()
        wrong_iterations["benchmarks"][0]["repeatIterations"] = 4
        documents.append(wrong_iterations)
        duplicate_trace = macro_result()
        duplicate_trace["benchmarks"][0]["profilerOutputs"][1]["filename"] = (
            duplicate_trace["benchmarks"][0]["profilerOutputs"][0]["filename"]
        )
        documents.append(duplicate_trace)
        false_p99 = macro_result()
        false_p99["benchmarks"][0]["sampledMetrics"]["frameOverrunMs"]["P99"] = 0.0
        documents.append(false_p99)
        for document in documents:
            with self.subTest(document=document), self.assertRaises(InvalidEvidence):
                extract_macrobenchmark_run(document)

    def test_nonfinite_and_duplicate_json_are_invalid(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "bad.json"
            path.write_text('{"x": NaN}', encoding="utf-8")
            with self.assertRaises(InvalidEvidence):
                load_json(path)

    def test_run_set_evaluates_three_independent_raw_results(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            journeys = []
            for journey_id in ("tab-entry", "period-switch", "scroll"):
                benchmark_name = {
                    "tab-entry": "tabEntry",
                    "period-switch": "periodSwitch",
                    "scroll": "scroll",
                }[journey_id]
                runs = []
                for index in range(3):
                    path = root / f"{journey_id}-run-{index}.json"
                    path.write_text(
                        json.dumps(
                            macro_result(
                                benchmark_name=benchmark_name,
                                trace_prefix=f"{journey_id}-{index}",
                            )
                        ),
                        encoding="utf-8",
                    )
                    runs.append(path.name)
                journeys.append(
                    {
                        "id": journey_id,
                        "benchmarkClassName": "com.jay.fxi.macrobenchmark.TetherGraphBenchmark",
                        "benchmarkName": benchmark_name,
                        "runs": runs,
                    }
                )
            results = evaluate_run_set(
                {"schemaVersion": 1, "journeys": journeys},
                mode="s5",
                artifact_root=root,
            )
            self.assertEqual(3, len(results))
            self.assertTrue(all(result["verdict"] == "pass" for result in results))
            self.assertTrue(all(result["medianP99Ms"] == 8.33 for result in results))
            path.write_text('{"x": 1, "x": 2}', encoding="utf-8")
            with self.assertRaises(InvalidEvidence):
                load_json(path)

    def test_run_set_rejects_three_paths_with_identical_result_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw = json.dumps(macro_result(), sort_keys=True)
            paths = []
            for index in range(3):
                path = root / f"copy-{index}.json"
                path.write_text(raw, encoding="utf-8")
                paths.append(path.name)
            journeys = []
            for journey_id, method in (
                ("tab-entry", "tabEntry"),
                ("period-switch", "periodSwitch"),
                ("scroll", "scroll"),
            ):
                journeys.append(
                    {
                        "id": journey_id,
                        "benchmarkClassName": "com.jay.fxi.macrobenchmark.TetherGraphBenchmark",
                        "benchmarkName": method,
                        "runs": paths,
                    }
                )
            with self.assertRaisesRegex(InvalidEvidence, "byte-identical"):
                evaluate_run_set(
                    {"schemaVersion": 1, "journeys": journeys},
                    mode="s5",
                    artifact_root=root,
                )


class EvidenceManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        artifacts = {}
        for key in sorted(ARTIFACT_KEYS):
            path = self.root / f"{key}.bin"
            path.write_bytes(
                Path(benchmark_gate.__file__).read_bytes()
                if key == "parser"
                else key.encode("utf-8")
            )
            artifacts[key] = {"path": path.name, "sha256": sha256_file(path)}
        preflight_snapshot = {
            "model": "SM-F711N",
            "fingerprint": EXPECTED_MEASUREMENT_CONTRACT["fingerprint"],
            "api": 35,
            "posture": "OPENED",
            "physicalSize": "1080x2640",
            "overrideSize": None,
            "orientation": 0,
            "supports120Hz": True,
            "lowPower": False,
            "batteryLevel": 73,
            "powered": {
                "ac powered": False,
                "usb powered": False,
                "wireless powered": False,
                "dock powered": False,
            },
            "thermalStatus": 0,
            "settings": {
                "system/peak_refresh_rate": "120.0",
                "system/min_refresh_rate": "120.0",
                "global/window_animation_scale": "1.0",
                "global/transition_animation_scale": "1.0",
                "global/animator_duration_scale": "1.0",
            },
            "serialRecorded": False,
        }
        journeys = []
        for journey_id in ("tab-entry", "period-switch", "scroll"):
            benchmark_name = {
                "tab-entry": "tabEntry",
                "period-switch": "periodSwitch",
                "scroll": "scroll",
            }[journey_id]
            runs = []
            for run_index in range(3):
                run_id = f"{journey_id}-run-{run_index}"
                prefix = f"{journey_id}-trace-{run_index}"
                result = self.root / f"{journey_id}-result-{run_index}.json"
                result.write_text(
                    json.dumps(
                        macro_result(trace_prefix=prefix, benchmark_name=benchmark_name),
                        sort_keys=True,
                    ),
                    encoding="utf-8",
                )
                preflight = self.root / f"{journey_id}-preflight-{run_index}.json"
                postflight = self.root / f"{journey_id}-postflight-{run_index}.json"
                pre_snapshot = {
                    **preflight_snapshot,
                    "runId": run_id,
                    "phase": "preflight",
                    "observedAt": f"2026-09-01T00:0{run_index}:00+00:00",
                }
                post_snapshot = {
                    **preflight_snapshot,
                    "runId": run_id,
                    "phase": "postflight",
                    "observedAt": f"2026-09-01T00:0{run_index}:30+00:00",
                }
                preflight.write_text(json.dumps(pre_snapshot), encoding="utf-8")
                postflight.write_text(json.dumps(post_snapshot), encoding="utf-8")
                traces = []
                for trace_index in range(5):
                    trace = self.root / f"{prefix}-{trace_index}.perfetto-trace"
                    trace.write_bytes(f"{journey_id}/{run_index}/{trace_index}".encode("utf-8"))
                    traces.append({"path": trace.name, "sha256": sha256_file(trace)})
                runs.append(
                    {
                        "runId": run_id,
                        "result": {"path": result.name, "sha256": sha256_file(result)},
                        "preflight": {"path": preflight.name, "sha256": sha256_file(preflight)},
                        "postflight": {"path": postflight.name, "sha256": sha256_file(postflight)},
                        "traces": traces,
                    }
                )
            journeys.append(
                {
                    "id": journey_id,
                    "medianP95Ms": 0.0,
                    "medianP99Ms": 8.33,
                    "frameCount": 300,
                    "verdict": "pass",
                    "runs": runs,
                    "attempts": [
                        {
                            "attemptId": f"{journey_id}-attempt-{index}",
                            "ordinal": index + 1,
                            "outcome": "valid",
                            "runId": f"{journey_id}-run-{index}",
                        }
                        for index in range(3)
                    ],
                }
            )
        self.manifest = {
            "schemaVersion": 1,
            "gate": "S5",
            "verdict": "pass",
            "approval": {
                "status": "approved",
                "approvedAt": "2026-09-01T00:00:00Z",
                "authority": "user",
            },
            "sourceCommit": "a" * 40,
            "libraries": copy.deepcopy(EXPECTED_LIBRARIES),
            "measurementContract": copy.deepcopy(EXPECTED_MEASUREMENT_CONTRACT),
            "artifacts": artifacts,
            "journeys": journeys,
        }

    def tearDown(self):
        self.temp.cleanup()

    def test_valid_manifest_and_every_required_artifact(self):
        validate_manifest(self.manifest, self.root)
        for key in sorted(ARTIFACT_KEYS):
            broken = copy.deepcopy(self.manifest)
            del broken["artifacts"][key]
            with self.subTest(key=key), self.assertRaises(InvalidEvidence):
                validate_manifest(broken, self.root)

    def test_bad_hash_missing_file_and_path_escape_are_invalid(self):
        key = "parser"
        for path, digest in [
            ("missing.bin", "0" * 64),
            ("../outside.bin", "0" * 64),
            (self.manifest["artifacts"][key]["path"], "0" * 64),
        ]:
            broken = copy.deepcopy(self.manifest)
            broken["artifacts"][key] = {"path": path, "sha256": digest}
            with self.subTest(path=path), self.assertRaises(InvalidEvidence):
                validate_manifest(broken, self.root)

    def test_semantic_artifact_keys_cannot_alias_one_file(self):
        broken = copy.deepcopy(self.manifest)
        shared = broken["artifacts"]["parser"]
        broken["artifacts"]["targetBenchmarkApk"] = copy.deepcopy(shared)
        with self.assertRaisesRegex(InvalidEvidence, "unique paths and bytes"):
            validate_manifest(broken, self.root)

    def test_parser_artifact_is_bound_to_the_executing_judge(self):
        rogue = self.root / "rogue-parser.py"
        rogue.write_text("print('not the judge')\n", encoding="utf-8")
        broken = copy.deepcopy(self.manifest)
        broken["artifacts"]["parser"] = {
            "path": rogue.name,
            "sha256": sha256_file(rogue),
        }
        with self.assertRaisesRegex(InvalidEvidence, "executing this verdict"):
            validate_manifest(broken, self.root)

    def test_top_level_artifact_cannot_alias_run_evidence(self):
        broken = copy.deepcopy(self.manifest)
        preflight = broken["journeys"][0]["runs"][0]["preflight"]
        broken["artifacts"]["fixturePayload"] = copy.deepcopy(preflight)
        with self.assertRaisesRegex(InvalidEvidence, "reused|byte-identical"):
            validate_manifest(broken, self.root)

    def test_s11_protected_artifact_and_contract_mismatch_are_invalid(self):
        golden_path = self.root / "golden.json"
        golden_path.write_text(json.dumps(self.manifest, sort_keys=True), encoding="utf-8")
        golden_sha = sha256_file(golden_path)
        candidate = copy.deepcopy(self.manifest)
        candidate["gate"] = "S11"
        candidate["goldenManifestSha256"] = golden_sha
        validate_s11_against_golden(candidate, self.manifest, golden_bytes_sha256=golden_sha)

        contract_mismatch = copy.deepcopy(candidate)
        contract_mismatch["measurementContract"]["startupMode"] = "COLD"
        with self.assertRaisesRegex(InvalidEvidence, "measurementContract"):
            validate_s11_against_golden(
                contract_mismatch, self.manifest, golden_bytes_sha256=golden_sha
            )

        artifact_mismatch = copy.deepcopy(candidate)
        artifact_mismatch["artifacts"]["parser"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(InvalidEvidence, "parser"):
            validate_s11_against_golden(
                artifact_mismatch, self.manifest, golden_bytes_sha256=golden_sha
            )

        missing_authority = copy.deepcopy(candidate)
        golden_without_authority = copy.deepcopy(self.manifest)
        del golden_without_authority["approval"]["authority"]
        with self.assertRaisesRegex(InvalidEvidence, "approval"):
            validate_s11_against_golden(
                missing_authority,
                golden_without_authority,
                golden_bytes_sha256=golden_sha,
            )

    def test_malformed_attempt_run_id_is_reported_as_invalid_not_a_traceback(self):
        broken = copy.deepcopy(self.manifest)
        broken["journeys"][0]["attempts"][0]["runId"] = []
        path = self.root / "malformed-attempt.json"
        path.write_text(json.dumps(broken), encoding="utf-8")
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(
                2,
                benchmark_gate.main(
                    [
                        "validate-manifest",
                        str(path),
                        "--artifact-root",
                        str(self.root),
                    ]
                ),
            )

    def test_unhashable_gate_verdict_and_reason_are_invalid_evidence(self):
        mutations = []
        for field in ("gate", "verdict"):
            broken = copy.deepcopy(self.manifest)
            broken[field] = []
            mutations.append(broken)
        invalid_reason = copy.deepcopy(self.manifest)
        invalid_reason["journeys"][0]["attempts"][0] = {
            "attemptId": "invalid-reason-attempt",
            "ordinal": 1,
            "outcome": "invalid",
            "reason": [],
            "evidence": copy.deepcopy(
                invalid_reason["journeys"][0]["runs"][0]["result"]
            ),
        }
        mutations.append(invalid_reason)
        for index, broken in enumerate(mutations):
            path = self.root / f"malformed-type-{index}.json"
            path.write_text(json.dumps(broken), encoding="utf-8")
            with self.subTest(index=index), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(
                    2,
                    benchmark_gate.main(
                        [
                            "validate-manifest",
                            str(path),
                            "--artifact-root",
                            str(self.root),
                        ]
                    ),
                )


class CommandLineContractTest(unittest.TestCase):
    def test_empty_or_incomplete_threshold_fixture_is_invalid(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "cases.json"
            for document in ({"cases": []}, {"cases": [{"id": "only-one"}]}):
                path.write_text(json.dumps(document), encoding="utf-8")
                with self.subTest(document=document), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(2, benchmark_gate.main(["check-fixtures", str(path)]))

    def test_required_case_id_cannot_keep_its_name_after_losing_the_boundary(self):
        document = load_json(ROOT / "fixtures" / "gate-cases.json")
        exact = next(case for case in document["cases"] if case["id"] == "s5-exact-boundary-pass")
        for run in exact["runs"]:
            run["p95Ms"] = -100.0
            run["p99Ms"] = -100.0
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "cases.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, benchmark_gate.main(["check-fixtures", str(path)]))

    def test_validate_manifest_returns_one_for_a_structurally_valid_fail_verdict(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = root / "manifest.json"
            manifest.write_text('{"gate":"S5","verdict":"fail"}', encoding="utf-8")
            with (
                mock.patch.object(benchmark_gate, "validate_manifest"),
                contextlib.redirect_stdout(io.StringIO()) as output,
            ):
                result = benchmark_gate.main(
                    [
                        "validate-manifest",
                        str(manifest),
                        "--artifact-root",
                        str(root),
                    ]
                )
            self.assertEqual(1, result)
            self.assertEqual("fail", json.loads(output.getvalue())["verdict"])

    def test_validate_manifest_uses_the_goldens_own_artifact_root(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            candidate_root = root / "candidate"
            golden_root = root / "golden"
            candidate_root.mkdir()
            golden_root.mkdir()
            candidate = root / "candidate.json"
            golden = root / "golden.json"
            candidate.write_text('{"gate":"S11","verdict":"pass"}', encoding="utf-8")
            golden.write_text('{"gate":"S5","verdict":"pass"}', encoding="utf-8")
            with (
                mock.patch.object(benchmark_gate, "validate_manifest") as validate,
                mock.patch.object(benchmark_gate, "validate_s11_against_golden"),
                mock.patch.object(benchmark_gate, "sha256_file", return_value="0" * 64),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                result = benchmark_gate.main(
                    [
                        "validate-manifest",
                        str(candidate),
                        "--artifact-root",
                        str(candidate_root),
                        "--golden",
                        str(golden),
                        "--golden-artifact-root",
                        str(golden_root),
                    ]
                )
            self.assertEqual(0, result)
            self.assertEqual(
                [(json.loads(candidate.read_text()), candidate_root),
                 (json.loads(golden.read_text()), golden_root)],
                [call.args for call in validate.call_args_list],
            )


if __name__ == "__main__":
    unittest.main()
