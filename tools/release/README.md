# S0-g release-admission verification

These tools verify D24 independently at build time and on the fixed Android device.
They do not arm, upload, or publish a public artifact.

## Local checks

```bash
python3 -m unittest discover -s tools/release -p 'test_*.py'
python3 tools/release/verify_release_admission_artifacts.py \
  --aapt2 "$ANDROID_HOME/build-tools/36.0.0/aapt2" \
  --artifact debug=off=app/build/outputs/apk/debug/app-debug.apk
```

An OFF APK must contain `TOPIC_V2_RELEASE_ON=false` and all three Firebase
Analytics/Crashlytics/Messaging auto-init markers set to binary `false`.

## Durable SM-F711N evidence

Run only from a clean implementation commit with exactly one authorized, awake,
unlocked SM-F711N in the frozen OPENED posture. The runner refuses existing target
or test-package installs, materializes the exact Git tree, builds and installs exact
APK bytes, runs the four S0-g tests directly, and then proves a process-cold OFF
surface. Network zero is fail-closed on two independent Android counters: forced-poll
untagged UID history and the live `mAppUidStatsMap` BPF counter. The process is stopped
before two identical final counter snapshots are accepted.

```bash
RUN_ID=release-admission-$(git rev-parse --short=7 HEAD)-r1
python3 tools/release/device_release_admission.py \
  --run-id "$RUN_ID" \
  --evidence-dir "build/release-admission/$RUN_ID"
python3 tools/release/release_admission_evidence.py validate \
  "build/release-admission/$RUN_ID"
```

Only a validator-green four-file bundle may be promoted to
`evidence/android-v2/s0-g/release-admission/<run-id>/`. Do not promote raw ADB,
UIAutomator, logcat, Gradle, or netstats output; the runner records only strict
sanitized claims and SHA-256 bindings. The transient UIAutomator hierarchy is read
back from one run-id-owned device path and its removal is mandatory. The validator
binds source files to the recorded clean commit, which intentionally need not equal
the later evidence commit.

## Sealed S0-g baseline

The current durable device baseline is
`evidence/android-v2/s0-g/release-admission/release-admission-b477c22-r1/`,
produced from clean source commit
`b477c22b597a1e5f5101e62cdd2b6a2cb8296c99`. The earlier
`release-admission-31df3b9-r1` bundle remains historical evidence for the initial
runner. The current baseline proves the exact four structural tests and the process-cold
notification-intent OFF window; it does not arm a public artifact or broaden release
authorization.

Advancing `HEAD` with only the validated bundle and its documentation does not require
another device run. Rerun when a later commit changes the bound runner, target/test source
or build inputs, Firebase/admission fixture, or measurement/device contract. A
validator-only change first revalidates the existing bundle; rerun only if the current
validator cannot establish the required claims from that bundle.
