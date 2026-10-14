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
AndroidX's separate `className` and method `name` fields plus three runs, each
`{runId, result, behavior}`: a distinct result JSON path and that run's behavior record (each
journey selector looked up exactly once, the expected start and end screen state, the action
completed, and for scroll the observed move and return). Result/behavior/preflight/postflight/trace
artifacts may not be reused across runs or journeys, so one lucky invocation cannot masquerade as
three independent runs. Raw `context.build` must match the frozen model/fingerprint/API.
Raw `context.compilationMode` is not judged: AndroidX writes there the ART state of the
instrumentation's target context, which for this self-instrumenting module is the test APK
itself (observed `run-from-apk` on the device), not the measured app. The planned Partial
compilation is carried by `warmupIterations`, which Macrobenchmark writes only for
`CompilationMode.Partial` (3 here, otherwise 0). Each journey also carries a 3-to-5-entry attempt ledger;
ordinals are consecutive, attempt IDs are globally unique, exactly three `valid` attempt
run IDs must equal the three evaluated runs, and the third valid attempt ends the ledger.
Invalid attempts are limited to machine-checked `environment-invalid` run evidence or a
`result-malformed` AndroidX document that the strict parser actually rejects. A structurally
valid threshold miss cannot be relabelled invalid. A journey that does not behave as agreed is
not a manifest attempt: no manifest accepts a `behavior-failed` outcome. Until the functional
S5 runner writes the
ledger append-only, this makes declared retries bounded and auditable; it does not prove that
an operator disclosed an attempt that was never recorded.

A behavior failure is terminal. The journey stops at once and the gate fails without other
journeys, a manifest or a golden. The failing attempt's record keeps the eight planned slots: the
slots before the failure completed and met every expectation, the failed slot holds what was
observed (null where nothing was) with the stage, kind and reason of the failure, and the slots
after it are `not-run`. A mismatch must show in the declared stage's own observations; an
operation error names the operation; an action failure is recorded as `action: failed`. The
terminal report binds that record to the attempt's preflight and postflight:

```bash
python3 tools/performance/benchmark_gate.py validate-failure failure-report.json \
  --artifact-root evidence/
```

A valid report prints `"verdict": "fail"` and exits 1; a defective one exits 2; there is no
passing outcome. The report does not carry earlier attempts, so it cannot prove that none was
hidden or that nothing ran after the failure; that is the runner's append-only journal.
For S11, `--golden` and `--golden-artifact-root` are mandatory; B99 cannot be supplied as
a free CLI number and is read only from a validated, explicitly approved S5 manifest.

## Evidence manifest

The JSON Schema documents the envelope. The Python validator is authoritative because it
also re-hashes every artifact and enforces the frozen D31 measurement contract:

```bash
python3 tools/performance/benchmark_gate.py validate-manifest evidence.json \
  --artifact-root evidence/
```

`s5_ledger.py` defines the append-only attempt ledger the S5 runner keeps in the evidence
root (`ledger.jsonl`: one session line with the seven artifacts, an `attempt-start` before
each attempt touches the device and its `attempt-end`, one `end` line; each line carries the
SHA-256 of the line before it). It assembles a finished ledger into this manifest, or a
behavior-failed one into the terminal failure report, checks the document with this judge and
publishes it without overwriting anything. `attempts-exhausted` and `aborted` runs get no
document. The chain catches an edited, dropped or reordered line, not a deliberate rewrite
of the whole file.

```bash
python3 tools/performance/s5_ledger.py assemble --root evidence/
```

Artifact hashes must refer to a single file. For a directory or a set of R8 inputs, first
create a canonical manifest consisting of sorted relative paths and each file's SHA-256,
then hash that manifest. A raw directory hash is not a reproducible identity.

`s5_artifacts.py collect` builds the five non-APK top-level artifacts this way from one built
source snapshot: the snapshot's own `benchmark_gate.py`; the fixture payload (every file under
`app/src/s5Benchmark` and `app/src/benchmark/assets/s5_4`, plus
`ci/google-services.ci-fixture.json`, whose s5Benchmark build copy must be the same bytes and
is not listed itself); the `:macrobenchmark` `s5BenchmarkRuntimeClasspath` and `:app`
`releaseRuntimeClasspath` dependency reports, normalized, failing on an unresolved entry or a
host path; and the R8 rule-input manifest, one line per rule file AGP listed in the s5Benchmark
`configuration.txt`, hashed from the file's own bytes, which must still be the section the
build used. Gradle transform inputs are labelled without their cache hash. Nothing is published
until all five are built and checked, and nothing existing is overwritten.

```bash
python3 tools/performance/s5_artifacts.py collect --build-root <snapshot> \
  --gradle-home <temporary-gradle-home> --gradle-home ~/.gradle --out evidence/
```

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

The current sealed S0-f launcher baseline is
`evidence/android-v2/s0-f/launcher-smoke/launcher-smoke-a074f3c-r1/`, produced on the device
contract revised in S5-4 slice 1b (`…/F711NKSSFKZH5:user/release-keys`) from source commit
`a074f3c22a9fad2a6bfcdc0e1a83bc3a8b8221df` (2026-10-15). The earlier
`launcher-smoke-b477c22-r1` and `launcher-smoke-8c18667-r2` bundles remain historical evidence
of the previous firmware contract (`…/F711NKSSEKZE1:user/release-keys`). They are not
revalidated with the current validator, whose device contract is the revised one; revalidate
them with the validator of their sealing commit.
The validator requires that recorded commit to exist, requires clean and identical source
state at both ends, and re-hashes the bound runner/Firebase blobs from that commit. It
deliberately does not require the recorded source commit to equal the current `HEAD`,
because this separate evidence commit necessarily advances `HEAD`; reviewers must still
confirm that the recorded commit is the intended implementation baseline.

Advancing `HEAD` with only the validated bundle and its documentation does not require
another device run. Rerun when a later commit changes the bound runner, target/test source
or build inputs, Firebase fixture, or measurement/device contract. A validator-only change
first revalidates the existing bundle; rerun only if the current validator cannot establish
the required claims from that bundle.

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

`verify_s5_benchmark_artifacts.py` (S5-4 4c) does the same for the S5 fixture build type. It
requires an ON, non-debuggable, profileable, debug-signed `s5Benchmark` target with the three
Firebase flags off, Firebase identifiers equal to the checked-in CI fixture (compared, never
printed), a network config that allows cleartext to `127.0.0.1` only (followed from the
manifest reference, because R8 renames the resource file), the five approved fixture assets by
SHA-256, ProfileInstaller 1.4.1 and a self-instrumenting test APK. For the public-like
`ciMinified` APK it requires the base network config with only the KB image exception and none
of the fixture: no fixture source root or file (listed from the `sourceSets` report, symlinks
followed), no MockWebServer dependency, no fixture asset, and no fixture class in the R8
mapping or the DEX. `ciMinified` is public-like evidence only; the public AAB is proven in S11.

## S5 runner

`s5_runner.py run` measures the three `TetherGraphBenchmark` journeys on the frozen SM-F711N
into a new evidence root whose name is the run id (at most 40 characters). It never runs on an
emulator and has no option to relax the device contract. It reuses `device_preflight.py` by
import (lock, restore journal, device checks, install, read-back) and changes nothing in it.

It takes the lock and recovers stale settings first; a recovery ends the run without
measuring. A clean source, the absent packages and the read-only device contract follow, then
the committed snapshot is built once (`s5Benchmark`, the CI Firebase fixture copied in, a private
Gradle home on the real caches), both APKs are staged read-only and `s5_artifacts.py collect`
writes the other five artifacts. Only then is the ledger's session line written, and only after
it are settings pinned. Each attempt (by `s5_ledger.next_step`) installs both APKs, reads them
back, takes a preflight snapshot, runs the one journey with `am instrument` and the
`s5.runId`/`additionalTestOutputDir` arguments, takes a postflight snapshot, collects the
AndroidX result, its five traces and the behavior record, reads the APKs back again and removes
what it installed. A valid behavior failure is terminal; a confirmed environment failure before
the instrumentation, or after it with a successful behavior record, is `environment-invalid`;
an object the strict parser refuses is `result-malformed`; anything it cannot classify, a
cleanup or settings-restore failure, or an interruption ends the run as `aborted` with a
diagnostic. The end line is written after cleanup and the restore read-back, then the ledger is
assembled. Before the session line a failure exits 2 with no ledger.

```bash
python3 tools/performance/s5_runner.py run --run-id s5-<sha7>-r1 \
  --evidence-root build/s5/s5-<sha7>-r1
```
