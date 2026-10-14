#!/usr/bin/env python3
"""S5-4 E slice 5: host tests of macrobenchmark's S5Behavior.kt and S5Poll.kt, without Gradle.

com.android.test modules have no unit-test source set, so this compiles the production files
(macrobenchmark/src/main/.../S5Behavior.kt and S5Poll.kt) together with hostTest/kotlin with the Kotlin 2.0.21 compiler and JUnit 4.13.2 the
build already cached, runs the tests on JDK 17, then checks every record the tests wrote with the S5 gate itself
(tools/performance/benchmark_gate.py): `<case>.success.json` must pass validate_behavior and `<case>.failure.json` must pass
check_failure_record. Exit 0 only when every test passed and every record was accepted.
"""
import glob
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PRODUCTION = [ROOT / "macrobenchmark/src/main/kotlin/com/jay/fxi/macrobenchmark" / name for name in ("S5Behavior.kt", "S5Poll.kt")]
TESTS = sorted((HERE / "kotlin").rglob("*.kt"))
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
COMPILER = [("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21"), ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
            ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.0.21"), ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.0.21"),
            ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"), ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"),
            ("org.jetbrains", "annotations", "13.0")]
RUNTIME = [("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"), ("junit", "junit", "4.13.2"), ("org.hamcrest", "hamcrest-core", "1.3")]
EXPECTED_RECORDS = 25  # 3 full successes + 6 warmup + 2 WARM pass + 5 measured + 7 kind + 2 partial-poll failures


def jar(group, artifact, version):
    found = glob.glob(str(CACHE / group / artifact / version / "*" / f"{artifact}-{version}.jar"))
    if len(found) != 1:
        sys.exit(f"cached jar {group}:{artifact}:{version}: {len(found)} found")
    return found[0]


def java17():
    home = os.environ.get("JAVA_HOME") or subprocess.run(["/usr/libexec/java_home", "-v", "17"], capture_output=True,
                                                          text=True, check=True).stdout.strip()
    java = Path(home) / "bin/java"
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True).stderr
    if '"17' not in version:
        sys.exit(f"JDK 17 needed, found: {version.splitlines()[0] if version else java}")
    return str(java)


def gate_expectations():
    sys.path.insert(0, str(ROOT / "tools/performance"))
    import benchmark_gate
    table = {j: {"lookups": sorted(e["lookups"]), "before": dict(sorted(e["before"].items())),
                 "after": dict(sorted(e["after"].items())), "during": e["during"]}
             for j, e in benchmark_gate.EXPECTED_BEHAVIOR.items()}
    return benchmark_gate, json.dumps(table, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def main():
    java = java17()
    gate, expected = gate_expectations()
    with tempfile.TemporaryDirectory() as tmp:
        classes, records = Path(tmp, "classes"), Path(tmp, "records")
        runtime = os.pathsep.join(jar(*a) for a in RUNTIME)
        compile_cmd = [java, "-cp", os.pathsep.join(jar(*a) for a in COMPILER), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                       "-no-reflect", "-no-stdlib", "-jvm-target", "17", "-classpath", runtime, "-d", str(classes),
                       *map(str, PRODUCTION), *map(str, TESTS)]
        compiled = subprocess.run(compile_cmd, capture_output=True, text=True)
        print(compiled.stdout + compiled.stderr, end="")
        if compiled.returncode != 0:
            return 1
        run = subprocess.run([java, f"-Ds5.host.out={records}", f"-Ds5.gate.expected={expected}", "-cp",
                              os.pathsep.join([str(classes), runtime]), "org.junit.runner.JUnitCore",
                              "com.jay.fxi.macrobenchmark.S5BehaviorHostTest"], capture_output=True, text=True)
        print(run.stdout + run.stderr, end="")
        if run.returncode != 0:
            return 1
        files = sorted(records.glob("*.json"))
        bad = []
        for path in files:
            document = json.loads(path.read_text(encoding="utf-8"))
            check = gate.validate_behavior if path.name.endswith(".success.json") else gate.check_failure_record
            try:
                check(document, run_id=document["runId"], journey_id=document["journey"], label=path.name)
            except gate.InvalidEvidence as error:
                bad.append(f"{path.name}: {error}")
        print(f"gate checked {len(files)} records, {len(bad)} rejected")
        for line in bad:
            print("  " + line)
        if len(files) != EXPECTED_RECORDS:
            print(f"expected {EXPECTED_RECORDS} records")
            return 1
        return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
