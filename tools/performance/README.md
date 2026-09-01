# S0-f benchmark tooling

`benchmark_gate.py` is the checked-in, standard-library-only judge for the S5 absolute
and S11 regression gates. It is deliberately separate from the Android target and from
the server repository.

## CI self-test

```bash
python3 -m unittest discover -s tools/performance -p 'test_*.py'
python3 tools/performance/benchmark_gate.py check-fixtures \
  tools/performance/fixtures/gate-cases.json
```

The checked-in cases pin exact-boundary pass, epsilon fail, and missing P95/P99/frameCount
behavior. Unit tests also consume the AndroidX Benchmark 1.4.1 result shape and reject
wrong iteration counts, zero frames, duplicate traces, false reported percentiles,
duplicate JSON keys, and non-finite numbers.

Actual AndroidX result files are evaluated as three independent invocations per journey:

```bash
python3 tools/performance/benchmark_gate.py evaluate-runs run-set.json \
  --mode s5 --artifact-root evidence/
```

The run-set must contain exactly `tab-entry`, `period-switch`, and `scroll`; each records
AndroidX's separate `className` and method `name` fields plus three distinct result JSON
paths. Result/preflight/postflight/trace artifacts
may not be reused across runs or journeys, so one lucky invocation cannot masquerade as
three independent runs. Raw `context.build` must match the frozen model/fingerprint/API,
and `context.compilationMode` must be AndroidX's resolved `speed-profile` value for the
planned Partial compilation. Each journey also carries a 3-to-5-entry attempt ledger;
ordinals are consecutive, attempt IDs are globally unique, exactly three `valid` attempt
run IDs must equal the three evaluated runs, and the third valid attempt ends the ledger.
Invalid attempts are limited to machine-checked `environment-invalid` run evidence or a
`result-malformed` AndroidX document that the strict parser actually rejects. A structurally
valid threshold miss cannot be relabelled invalid. Until the functional S5 runner writes the
ledger append-only, this makes declared retries bounded and auditable; it does not prove that
an operator disclosed an attempt that was never recorded.
For S11, `--golden` and `--golden-artifact-root` are mandatory; B99 cannot be supplied as
a free CLI number and is read only from a validated, explicitly approved S5 manifest.

## Evidence manifest

The JSON Schema documents the envelope. The Python validator is authoritative because it
also re-hashes every artifact and enforces the frozen D31 measurement contract:

```bash
python3 tools/performance/benchmark_gate.py validate-manifest evidence.json \
  --artifact-root evidence/
```

Artifact hashes must refer to a single file. For a directory or a set of R8 inputs, first
create a canonical manifest consisting of sorted relative paths and each file's SHA-256,
then hash that manifest. A raw directory hash is not a reproducible identity.

For S11, pass both `--golden approved-s5.json` and the golden's own
`--golden-artifact-root`. The golden must be an explicitly approved, passing S5 manifest.
Test APK, deterministic fixture, parser, harness dependency graph, pinned library versions,
and measurement contract are protected. The source commit, target APK, production dependency
graph, and canonical R8-rule manifest are recorded for attribution but may legitimately
change in the candidate.

`thermalThrottleSleepSeconds` in AndroidX output is not accepted as thermal evidence.
Battery, power, posture, refresh, animation, and thermal observations come from the
separate physical-device preflight and must be stored per run.

## Physical-device preflight

`device_preflight.py` records no serial number and refuses a wrong fingerprint/posture,
display override, simulated battery (`UPDATES STOPPED`), charging source, low battery,
thermal status, or an existing target/test install for any Android user/profile. It journals
refresh/animation settings before mutation and restores them on normal exit, SIGINT/SIGTERM, or the next
invocation after an uncatchable termination. The lock and restore journal live in a
private user cache outside the checkout, so two Git worktrees cannot mutate the same
physical device concurrently or strand different restore owners.

A listed 120 Hz mode is not accepted as proof of an active 120 Hz panel. After settings
pinning, the runner waits read-only for two consecutive observations that agree between
DisplayManager's ON/internal/1080x2640 `renderFrameRate` and SurfaceFlinger's physical
`activeMode.vsyncRate`. It samples both oracles again immediately before and after direct
instrumentation, stores only the sanitized rates/timestamps, and fails on 60/96 Hz,
main/cover ambiguity, or oracle disagreement. It never synthesizes a tap to force 120 Hz.

The launcher smoke uses a benchmark-source-set-only no-data Activity; it does not enter
MainActivity or the app data plane. The runner installs a verified copy of the checked-in
non-production Firebase fixture for the benchmark build and removes that temporary copy.
It builds the two APKs first, copies them into a private read-only staging directory,
installs those exact bytes, reads both installed `base.apk` files back from the device,
and invokes the one test directly with `am instrument`. A shared Gradle connected-test
result is not accepted as launcher evidence:

```bash
python3 tools/performance/device_preflight.py \
  --run-launcher-smoke \
  --run-id launcher-smoke-01 \
  --evidence-dir build/benchmark-preflight/launcher-smoke-01
```

The `build/` result is provisional diagnostics and is intentionally ignored. A durable
S0-f attestation must be rerun **after the implementation commit** with an evidence
directory whose basename exactly equals the run id:

```bash
python3 tools/performance/device_preflight.py \
  --run-launcher-smoke \
  --require-clean-source \
  --run-id launcher-smoke-<commit-short-sha> \
  --evidence-dir build/benchmark-preflight/launcher-smoke-<commit-short-sha>
```

For this durable mode the runner materializes `git archive <sourceCommit>` in a private
temporary directory and builds that committed tree with a private Gradle user home (only
the dependency/wrapper caches are reused; user init scripts and user Gradle properties are
not). The current worktree must still be clean at both ends. This prevents a transient
worktree edit from being attributed to the committed SHA. The APKs, actual benchmark
Firebase input, installed device APKs, runner, and one sanitized green JUnit result are
bound by SHA-256 before and after instrumentation.

Validate the four-file bundle before promotion:

```bash
python3 tools/performance/launcher_evidence.py validate \
  build/benchmark-preflight/launcher-smoke-<commit-short-sha>
```

Only a validated bundle's `run.json`, `preflight.json`, `postflight.json`, and
`launcher-result.xml` may be copied to the tracked
`evidence/android-v2/s0-f/launcher-smoke/<run-id>/` directory in a separate evidence
commit. Raw UTP/instrumentation output must not be promoted because it can contain the ADB
serial, device paths, and local absolute paths.

The sealed S0-f launcher baseline is
`evidence/android-v2/s0-f/launcher-smoke/launcher-smoke-8c18667-r2/`, produced from
source commit `8c186678dd2e17ced4c7ea2249962cbdc8ee5be9`. The validator requires that
recorded commit to exist, requires clean and identical source state at both ends, and
re-hashes the bound runner/Firebase blobs from that commit. It deliberately does not
require the recorded source commit to equal the current `HEAD`, because this separate
evidence commit necessarily advances `HEAD`; reviewers must still confirm that the
recorded commit is the intended implementation baseline.

Promotion is allowed only when the checked-in validator says so. It requires
`verdict=pass`, `exitCode=0`, the same clean source commit at both ends, byte-identical
pre/post APK and Firebase inputs, matching installed APK bytes, restored settings, removal
of both temporary packages/output directories/source/staging directories, one direct green
instrumentation result, and valid hashes for every supporting evidence file. It also
re-hashes the runner and canonical Firebase fixture directly from the recorded Git commit.

S0-g remains the owner of D24 OFF admission for MainActivity/Root/FCM entrypoints; this
smoke does not claim that gate is installed. The script never calls `dumpsys battery unplug/set`,
`pm clear`, or an automated unlock gesture. It refuses to replace any pre-existing app,
then uninstalls only the exact target/test packages that this invocation installed and
verifies that both are absent for every Android user/profile.

`verify_benchmark_artifacts.py` inspects built APKs rather than trusting Gradle source. It
requires target package `com.jay.fxi`, non-debuggable/profileable-shell target wiring,
debug/ephemeral signing, ProfileInstaller initializer+receiver, the test runner/query,
and absence of profileable or benchmark admission in the public-like `ciMinified` APK.
It also consumes `dependencyInsight` output and requires the selected ProfileInstaller
version to be exactly 1.4.1; component presence alone is not sufficient because older
transitive versions also merge the same components.
