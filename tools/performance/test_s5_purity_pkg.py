"""L1 D17-pkg host contracts. Deliberately RED at the two fail-closed seams.

ZIP members contain synthetic dexdump text, not executable DEX. Only the external
transport is replaced; the verifier must choose the final APK and inspect it.
No host result proves R8 execution, Binder or shell app_process loading.
"""
import hashlib
import re
import subprocess
import tempfile
import unittest
import unittest.mock
import zipfile
from pathlib import Path

import s5_artifacts as artifacts
import verify_s5_benchmark_artifacts as packaging
from test_s5_artifacts import Snapshot
from test_verify_s5_benchmark_artifacts import NS, SOURCE_REPORT, target_manifest

FIX = "app/src/s5Benchmark/java/com/jay/fxi/s5fixture/"
JOURNAL = FIX + "S5PurityJournal.kt"
MODULES = FIX + "S5FixtureModules.kt"
SERVER = FIX + "S5FixtureServer.kt"
PROBE = "Lcom/jay/fxi/s5fixture/S5PurityProbe;"
# Final APK inputs exclude the fail-closed public release variant. Whether
# ciMinified discharges r4's separate release DEX duty needs a user decision.
VARIANTS = ("debug", "ciMinified", "benchmark")
SOURCE_VARIANTS = ("debug", "release", "ciMinified", "benchmark")
CASES = []

# Host source-scan inputs use the real Kotlin types/constructor/DI shape.
# Bodies below specify implementation obligations; they are not compiled Kotlin evidence.
JOURNAL_SOURCE = '''package com.jay.fxi.s5fixture
import android.app.ActivityManager
import android.os.Build
import androidx.annotation.Keep
interface ProcessSummarySink { fun replace(bytes: ByteArray) }
interface Fatal { fun terminate(code: Int): Nothing }
interface JournalIO
class PurityProcessScope(val lifetimeId: String)
class AttemptContext
interface S5PurityRuntime {
    val sdk: Int
    val io: JournalIO
    val process: PurityProcessScope
    val contextBytes: ByteArray?
    fun createApi30(): PurityMode.Supported
    fun diagnostic(line: String)
}
sealed interface PurityMode {
    class UnsupportedApi(val sdk: Int): PurityMode
    class Supported(val sink: ProcessSummarySink, val identity: () -> InstanceIdentity): PurityMode
}
class InstanceIdentity
object S5PurityPlatform {
    fun select(sdk: Int, createApi30: () -> PurityMode.Supported): PurityMode =
        if (sdk < 30) PurityMode.UnsupportedApi(sdk) else createApi30()
    interface IdentityCalls
    object AndroidIdentityCalls: IdentityCalls
    class Api30Impl(private val activityManager: ActivityManager?,
                    private val identityCalls: IdentityCalls = AndroidIdentityCalls): ProcessSummarySink {
        override fun replace(bytes: ByteArray) { activityManager!!.setProcessStateSummary(bytes) }
        fun identity(process: PurityProcessScope): InstanceIdentity = InstanceIdentity()
    }
}
class S5PurityJournal(val io: JournalIO, val mode: PurityMode, val fatal: Fatal,
                      val process: PurityProcessScope, val counter: java.util.concurrent.atomic.AtomicInteger) {
    fun registerBeforeBind(context: AttemptContext) { }
    fun <T> classifiedCallback(block: () -> T): T = block()
}
@Keep
object S5PurityProbe {
    @Keep @JvmStatic fun main(args: Array<String>) { check(Build.VERSION.SDK_INT >= 30) }
}
'''
MODULES_SOURCE = '''package com.jay.fxi.s5fixture
import android.app.ActivityManager
import android.content.Context
import dagger.Provides
object S5FixtureEndpointsModule {
    @Provides fun provideS5PurityRuntime(context: Context): S5PurityRuntime = object: S5PurityRuntime {
        override val sdk = android.os.Build.VERSION.SDK_INT
        override val io = AndroidJournalIO(context)
        override val process = processScope
        override val contextBytes = readAttemptBytes(context)
        override fun createApi30(): PurityMode.Supported {
            val adapter = S5PurityPlatform.Api30Impl(context.getSystemService(ActivityManager::class.java))
            return PurityMode.Supported(adapter) { adapter.identity(process) }
        }
        override fun diagnostic(line: String) { android.util.Log.e("S5Fixture", line) }
    }
    @Provides fun provideS5PurityJournal(runtime: S5PurityRuntime, fatal: Fatal): S5PurityJournal =
        S5PurityJournal(runtime.io, S5PurityPlatform.select(runtime.sdk, runtime::createApi30), fatal,
                        runtime.process, java.util.concurrent.atomic.AtomicInteger())
    @Provides fun provideS5AttemptContext(runtime: S5PurityRuntime): AttemptContext = AttemptContext.read(runtime.contextBytes)
    @Provides fun provideS5Fatal(): Fatal = Fatal { code -> Runtime.getRuntime().halt(code); error("halt returned") }
    @Provides fun provideS5FixtureServer(assets: S5FixtureAssets, purity: S5PurityJournal,
                                        attemptContext: AttemptContext, fatal: Fatal): S5FixtureServer =
        S5FixtureServer(load = assets::bytes, purity = purity, attemptContext = attemptContext, fatal = fatal)
}
'''
SERVER_SOURCE = '''package com.jay.fxi.s5fixture
class S5FixtureServer(val load: (String) -> ByteArray, val purity: S5PurityJournal,
                       val attemptContext: AttemptContext, val fatal: Fatal) {
    fun start() { purity.registerBeforeBind(attemptContext); bind() }
    fun http() = purity.classifiedCallback { routeHttp() }
    fun websocket() = purity.classifiedCallback { routeWebSocket() }
}
'''


def dex_class(descriptor, *, name="main", signature="([Ljava/lang/String;)V", access="PUBLIC STATIC", method=True):
    text = f"Class #0 header:\n  Class descriptor  : '{descriptor}'\n"
    text += "  Direct methods   -\n" if "STATIC" in access else "  Virtual methods  -\n"
    if method:
        flags = (1 if "PUBLIC" in access else 2) | (8 if "STATIC" in access else 0) | (16 if "FINAL" in access else 0)
        text += (f"    #0              : (in {descriptor[1:-1].replace('/', '.')})\n"
                 f"      name          : '{name}'\n      type          : '{signature}'\n"
                 f"      access        : 0x{flags:04x} ({access})\n")
    return text


GOOD_DEX = dex_class(PROBE)
CLEAN_DEX = dex_class("Lcom/jay/fxi/MainActivity;", method=False)


class PackageFixture:
    def __init__(self, base):
        self.snapshot = Snapshot(base)
        self.root = self.snapshot.root
        self.target = self.root / "app/build/outputs/apk/s5Benchmark/app-s5Benchmark.apk"
        self.production = {v: self.root / f"app/build/outputs/apk/{v}/app-{v}.apk" for v in VARIANTS}
        self.apk(self.target, {"classes.dex": GOOD_DEX.encode()})
        for apk in self.production.values():
            self.apk(apk, {"classes.dex": CLEAN_DEX.encode()})
        self.snapshot.write(JOURNAL, JOURNAL_SOURCE.encode())
        self.snapshot.write(MODULES, MODULES_SOURCE.encode())
        self.snapshot.write(SERVER, SERVER_SOURCE.encode())
        self.snapshot.write("app/src/main/java/com/jay/fxi/App.kt", b"package com.jay.fxi\nclass App\n")
        self.snapshot.write("app/src/productionBindings/java/com/jay/fxi/Bindings.kt", b"package com.jay.fxi\nobject Bindings\n")
        self.source_report = SOURCE_REPORT
        for variant in ("debug", "release", "benchmark"):
            self.source_report += (f"\n{variant}\n{'-' * len(variant)}\n"
                                   f"Java sources: [app/src/{variant}/java, app/src/productionBindings/java]\n"
                                   f"Kotlin sources: [app/src/{variant}/kotlin]\nAssets: [app/src/{variant}/assets]\n")
        self.classpath = [self.root / "candidate-classpath/annotation.jar"]
        self.apk(self.classpath[0], {"androidx/annotation/Keep.class": b"synthetic classpath entry"})
        self.keep_rule = self.snapshot.rule(
            self.snapshot.home / "caches/9.1.0/transforms/0123abcd/transformed/annotation/proguard.txt",
            b"-keep @androidx.annotation.Keep class * { *; }\n"
            b"-keepclassmembers class * { @androidx.annotation.Keep <methods>; }\n")
        self.snapshot.rules.append(self.keep_rule)
        self.snapshot.configure()
        self.configuration = self.root / artifacts.CONFIGURATION_PATH
        self.manifests = {self.target: target_manifest()}
        self.calls = []
        self.out = base / "evidence"
        self.out.mkdir()
        self.test_apk = self.root / "macrobenchmark/build/outputs/apk/s5Benchmark/macrobenchmark-s5Benchmark.apk"
        self.apk(self.test_apk, {"classes.dex": CLEAN_DEX.encode()})

    @staticmethod
    def apk(path, members):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as output:
            for name, data in members.items():
                output.writestr(name, data)

    def dex(self, tool, apk):
        self.calls.append(("dex", Path(apk)))
        with zipfile.ZipFile(apk) as source:
            names = sorted(n for n in source.namelist() if re.fullmatch(r"classes\d*\.dex", n))
            if not names:
                raise packaging.VerificationError("host fixture APK has no DEX")
            return "\n".join(source.read(n).decode() for n in names)

    def run(self, *arguments):
        self.calls.append(("run", arguments))
        if arguments[1:5] == ("dump", "xmltree", "--file", "AndroidManifest.xml"):
            return self.manifests[Path(arguments[-1])]
        raise AssertionError(f"unexpected external command {arguments}")

    def verify(self):
        return packaging.verify_d17_package(
            self.root, target_apk=self.target, production_apks=self.production,
            source_report=self.source_report, compile_classpath=self.classpath,
            r8_configuration=self.configuration, run=self.run, dex=self.dex)

    def refs(self):
        # All five are actually recollected. The two APK refs use actual ZIP bytes.
        refs = artifacts.collect(self.root, self.out, gradle_homes=[self.snapshot.home], run=self.snapshot.run)
        for key, path in (("targetBenchmarkApk", self.target), ("macrobenchmarkTestApk", self.test_apk)):
            data = path.read_bytes()
            output = self.out / (key + ".apk")
            output.write_bytes(data)
            refs[key] = {"path": output.name, "sha256": hashlib.sha256(data).hexdigest()}
        return refs

    def verify_refs(self, refs):
        return artifacts.verify_d17_artifact_refs(
            self.root, self.out, refs, target_apk=self.target, test_apk=self.test_apk)

    def replace(self, path, before, after):
        source = self.root / path
        text = source.read_text()
        if before not in text:
            raise AssertionError(f"fixture mutation did not match {path}")
        source.write_text(text.replace(before, after))


class ContractTest(unittest.TestCase):
    longMessage = False

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.f = PackageFixture(Path(self.temp.name))
        patch = unittest.mock.patch.object(packaging, "FIXTURE_ASSETS", self.f.snapshot.approved())
        patch.start()
        self.addCleanup(patch.stop)

    def positive(self, sub_id, duty, call):
        try:
            call()
        except Exception as error:
            self.fail(f"precondition(D17b/pkg-{sub_id}): {duty} positive control: {type(error).__name__}: {error}")

    def negative(self, sub_id, duty, call, error_type):
        try:
            call()
        except error_type:
            return
        except Exception as error:
            self.fail(f"contract(D17b):pkg-{sub_id}/{duty}: wrong failure: {type(error).__name__}: {error}")
        self.fail(f"contract(D17b):pkg-{sub_id}/{duty}: defective input accepted")


def package_case(sub_id, duty, damage=None, *, check_calls=False):
    def test(self):
        self.positive(sub_id, duty, self.f.verify)
        if check_calls:
            for path in (self.f.target, *self.f.production.values()):
                self.assertIn(("dex", path), self.f.calls,
                              f"contract(D17b):pkg-{sub_id}/{duty}: final APK not inspected: {path.name}")
        if damage is not None:
            damage(self.f)
            self.negative(sub_id, duty, self.f.verify, packaging.VerificationError)
    install_case(sub_id, duty, test, "package-input" if damage else "positive")


def install_case(sub_id, duty, test, kind):
    if any(row[0] == sub_id for row in CASES):
        raise ValueError(f"duplicate D17-pkg sub-ID: {sub_id}")
    test.__name__ = "test_pkg_" + sub_id.replace("-", "_")
    setattr(ContractTest, test.__name__, test)
    CASES.append((sub_id, duty, kind, test.__name__))


package_case("final-dex-positive", "exact-probe-public-static-main", check_calls=True)
for sub_id, text in (
    ("probe-removed", CLEAN_DEX),
    ("probe-renamed", dex_class(PROBE.replace("S5PurityProbe", "a"))),
    ("main-removed", dex_class(PROBE, method=False)),
    ("main-renamed", dex_class(PROBE, name="a")),
    ("main-instance", dex_class(PROBE, access="PUBLIC")),
    ("main-private", dex_class(PROBE, access="PRIVATE STATIC")),
    ("main-no-args", dex_class(PROBE, signature="()V")),
    ("main-wrong-return", dex_class(PROBE, signature="([Ljava/lang/String;)I")),
    ("main-wrong-owner", dex_class(PROBE, method=False) + dex_class("Lother/Probe;")),
    ("probe-string-only", CLEAN_DEX + f"String #1 : '{PROBE}'\n"),
):
    package_case(sub_id, "exact-probe-public-static-main",
                 lambda f, text=text: f.apk(f.target, {"classes.dex": text.encode()}))
package_case("target-no-dex", "final-dex-required", lambda f: f.apk(f.target, {"classes.jar": GOOD_DEX.encode()}))
package_case("target-missing", "final-apk-required", lambda f: f.target.unlink())
package_case("main-reference-only", "defined-main-not-method-reference", lambda f: f.apk(f.target, {
    "classes.dex": (dex_class(PROBE, method=False) + "Method #2: S5PurityProbe.main:([Ljava/lang/String;)V\n").encode()}))


def multidex(self):
    self.f.apk(self.f.target, {"classes.dex": CLEAN_DEX.encode(), "classes2.dex": GOOD_DEX.encode()})
    self.positive("multidex-positive", "all-final-dex-members", self.f.verify)
install_case("multidex-positive", "all-final-dex-members", multidex, "positive")


def final_access(self):
    self.f.apk(self.f.target, {"classes.dex": dex_class(PROBE, access="PUBLIC STATIC FINAL").encode()})
    self.positive("main-final-positive", "public-static-may-be-final", self.f.verify)
install_case("main-final-positive", "public-static-may-be-final", final_access, "positive")

# These three are tool negatives (iota10), not product mutation kills. The final
# target is defective while an attractive non-final substitute remains present.
for sub_id, relative in (
    ("i10-source-only", JOURNAL),
    ("i10-unshrunk-only", "app/build/intermediates/javac/s5Benchmark/classes/com/jay/fxi/s5fixture/S5PurityProbe.class"),
    ("i10-old-graph-only", "evidence/old-s5BenchmarkCompileClasspath.txt"),
):
    def damage(f, relative=relative):
        f.snapshot.write(relative, JOURNAL_SOURCE.encode() if relative == JOURNAL else GOOD_DEX.encode())
        f.apk(f.target, {"classes.dex": CLEAN_DEX.encode()})
    package_case(sub_id, "iota10-final-dex-not-substitute", damage)
    CASES[-1] = (*CASES[-1][:2], "tool-negative-iota10", CASES[-1][3])

for variant in VARIANTS:
    for name in ("S5PurityJournal", "S5PurityProbe", "S5FixtureServer"):
        package_case(f"leak-{variant}-{name}", "production-dex-fixture-zero",
                     lambda f, v=variant, n=name: f.apk(f.production[v], {
                         "classes.dex": CLEAN_DEX.encode(),
                         "classes2.dex": dex_class(f"Lcom/jay/fxi/s5fixture/{n};").encode()}))
    package_case(f"variant-missing-{variant}", "all-production-variants-required",
                 lambda f, v=variant: f.production.pop(v))
    package_case(f"variant-no-dex-{variant}", "production-final-dex-required",
                 lambda f, v=variant: f.apk(f.production[v], {"assets/other.json": b"{}"}))
# Keep release source-input coverage independently of available final APKs.
for variant in SOURCE_VARIANTS:
    for name in ("S5PurityJournal", "S5PurityProbe"):
        package_case(f"source-{variant}-{name}", "production-source-purity-zero",
                     lambda f, v=variant, n=name: f.snapshot.write(
                         f"app/src/{v}/java/com/jay/fxi/{n}.kt", f"package com.jay.fxi\nobject {n}\n".encode()))
package_case("source-main-journal", "production-source-purity-zero",
             lambda f: f.snapshot.write("app/src/main/java/com/jay/fxi/Copied.kt", JOURNAL_SOURCE.encode()))
package_case("source-shared-probe", "production-source-purity-zero",
             lambda f: f.snapshot.write("app/src/productionBindings/java/com/jay/fxi/Copied.kt", JOURNAL_SOURCE.encode()))
package_case("source-report-missing", "current-source-inputs-required", lambda f: setattr(f, "source_report", ""))
package_case("journal-source-missing", "journal-source-required", lambda f: (f.root / JOURNAL).unlink())

for sub_id, attribute, owner in (
    ("manifest-app-process", "process", "application"),
    ("manifest-service-process", "process", "service"),
    ("manifest-provider-process", "process", "provider"),
    ("manifest-shared-uid", "sharedUserId", "manifest"),
    ("manifest-isolated", "isolatedProcess", "service"),
):
    def damage(f, attribute=attribute, owner=owner):
        value = "true" if attribute == "isolatedProcess" else '":worker"'
        # Separate aapt2 tree with the forbidden attribute on its actual owner.
        f.manifests[f.target] += f"  E: {owner} (line=99)\n    A: {NS}{attribute}={value}\n"
    package_case(sub_id, "single-process-sharedUid-zero", damage)

for sub_id, path, before, after in (
    ("di-journal-missing", MODULES, "purity = purity", "purity = null"),
    ("di-fake-sink", MODULES, "PurityMode.Supported(adapter)", "PurityMode.Supported(NoopSink)"),
    ("di-fake-fatal", MODULES, "fatal = fatal", "fatal = LoggingFatal"),
    ("di-provider-disconnected", MODULES, "S5FixtureServer(load = assets::bytes, purity = purity",
     "S5FixtureServer(load = assets::bytes, purity = NoopJournal"),
    ("sink-not-activitymanager", JOURNAL, "activityManager!!.setProcessStateSummary(bytes)", "report(bytes)"),
    ("fatal-no-halt", MODULES, "Runtime.getRuntime().halt(code)", "report(code)"),
    ("fatal-exit-only", MODULES, "Runtime.getRuntime().halt(code)", "System.exit(code)"),
    ("register-unwired", SERVER, "purity.registerBeforeBind(attemptContext); bind()", "bind()"),
    ("http-guard-unwired", SERVER, "purity.classifiedCallback { routeHttp() }", "routeHttp()"),
    ("ws-guard-unwired", SERVER, "purity.classifiedCallback { routeWebSocket() }", "routeWebSocket()"),
):
    package_case(sub_id, "modules-journal-guard-real-sink-halt", lambda f, p=path, b=before, a=after: f.replace(p, b, a))
package_case("writer-duplicate-journal", "summary-writer-unique", lambda f: f.snapshot.write(
    JOURNAL, (JOURNAL_SOURCE + "\nfun other(m: ActivityManager, b: ByteArray) { m.setProcessStateSummary(b) }\n").encode()))
package_case("writer-other-source", "summary-writer-unique", lambda f: f.snapshot.write(
    "app/src/main/java/com/jay/fxi/Writer.kt",
    b"fun replace(m: android.app.ActivityManager, b: ByteArray) { m.setProcessStateSummary(b) }\n"))


def comment_not_writer(self):
    self.f.snapshot.write("app/src/main/java/com/jay/fxi/Comment.kt",
                          b"// setProcessStateSummary is owned by the fixture adapter.\nclass Comment\n")
    self.positive("writer-comment-positive", "count-calls-not-comment-tokens", self.f.verify)
install_case("writer-comment-positive", "count-calls-not-comment-tokens", comment_not_writer, "positive")
package_case("keep-classpath-empty", "current-Keep-class-required", lambda f: setattr(f, "classpath", []))
package_case("keep-class-missing", "current-Keep-class-required", lambda f: f.apk(f.classpath[0], {"other.class": b"x"}))
package_case("keep-rules-missing", "current-merged-Keep-rules-required", lambda f: f.configuration.unlink())
package_case("keep-rules-ineffective", "current-merged-Keep-rules-required", lambda f: (
    f.keep_rule.write_bytes(b"-dontwarn androidx.annotation.Keep\n"), f.snapshot.configure()))


def annotation_protected_survival(self):
    self.f.replace(JOURNAL, "@Keep", "")
    self.positive("annotation-survival", "final-dex-controls-mu38q-kill", self.f.verify)
install_case("annotation-survival", "final-dex-controls-mu38q-kill", annotation_protected_survival, "protected-survival-model")


def artifact_case(sub_id, duty, damage=None):
    def test(self):
        refs = self.f.refs()
        self.positive(sub_id, duty, lambda: self.f.verify_refs(refs))
        if damage:
            damage(self.f, refs)
            self.negative(sub_id, duty, lambda: self.f.verify_refs(refs), artifacts.ArtifactError)
    install_case(sub_id, duty, test, "artifact-input" if damage else "positive")

artifact_case("refs-positive", "seven-current-candidate-artifacts")
for key in ("fixturePayload", "parser", "targetBenchmarkApk", "macrobenchmarkTestApk", "harnessResolvedGraph",
            "productionResolvedGraph", "r8RulesCanonicalManifest"):
    artifact_case(f"ref-missing-{key}", "seven-current-candidate-artifacts", lambda f, refs, k=key: refs.pop(k))
    artifact_case(f"ref-hash-{key}", "artifact-ref-actual-hash", lambda f, refs, k=key: refs[k].update(sha256="0" * 64))
artifact_case("fixture-new-source-stale", "journal-fixturePayload-current", lambda f, refs: f.replace(
    JOURNAL, "class S5PurityJournal", "/* new bytes */ class S5PurityJournal"))
artifact_case("target-ref-stale", "targetBenchmarkApk-exact-candidate", lambda f, refs: f.apk(
    f.target, {"classes.dex": (GOOD_DEX + "\nnew candidate\n").encode()}))
artifact_case("test-ref-stale", "test-apk-independently-compared", lambda f, refs: f.apk(
    f.test_apk, {"classes.dex": (CLEAN_DEX + "\nnew test candidate\n").encode()}))
artifact_case("parser-ref-stale", "parser-current-candidate", lambda f, refs: f.snapshot.write(
    artifacts.PARSER_PATH, (f.root / artifacts.PARSER_PATH).read_bytes() + b"\n# changed candidate\n"))


def omit_journal(f, refs):
    path = f.out / refs["fixturePayload"]["path"]
    path.write_bytes(b"".join(line for line in path.read_bytes().splitlines(keepends=True) if JOURNAL.encode() not in line))
    refs["fixturePayload"]["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
artifact_case("fixture-journal-omitted", "journal-listed-with-actual-hash", omit_journal)


def wrong_journal_hash(f, refs):
    path = f.out / refs["fixturePayload"]["path"]
    rows = path.read_text().splitlines(keepends=True)
    path.write_text("".join("0" * 64 + "  " + JOURNAL + "\n" if row.endswith("  " + JOURNAL + "\n") else row
                            for row in rows))
    refs["fixturePayload"]["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
artifact_case("fixture-journal-wrong-hash", "journal-listed-with-actual-hash", wrong_journal_hash)


def wrong_apk_ref(f, refs):
    refs["targetBenchmarkApk"] = dict(refs["macrobenchmarkTestApk"])
artifact_case("target-ref-swapped", "target-ref-not-test-apk", wrong_apk_ref)


def unchanged_recollection(self):
    first = self.f.refs()
    other = self.f.out.parent / "evidence-second"
    other.mkdir()
    self.f.out = other
    second = self.f.refs()
    self.assertEqual(first, second,
                     "contract(D17b):pkg-equal-recollected/seven-artifacts-may-have-equal-bytes")
    self.assertEqual(4, len(self.f.snapshot.calls),
                     "contract(D17b):pkg-equal-recollected/graphs-recollected-both-times")
    self.positive("equal-recollected", "seven-artifacts-may-have-equal-bytes", lambda: self.f.verify_refs(second))
install_case("equal-recollected", "seven-artifacts-may-have-equal-bytes", unchanged_recollection, "positive")


def escape_ref(f, refs):
    path = f.out.parent / "escaped.apk"
    path.write_bytes(f.target.read_bytes())
    refs["targetBenchmarkApk"]["path"] = "../escaped.apk"
artifact_case("ref-escape", "artifact-root-containment", escape_ref)


def symlink_ref(f, refs):
    path = f.out / refs["targetBenchmarkApk"]["path"]
    path.unlink()
    path.symlink_to(f.target)
artifact_case("ref-symlink", "artifact-regular-file-no-alias", symlink_ref)


def payload_and_collection(self):
    sub_id = "collection-fresh"
    duty = "new-sources-parser-two-apks-graphs-rules"
    refs = self.f.refs()
    payload = (self.f.out / refs["fixturePayload"]["path"]).read_text()
    for relative in (JOURNAL, MODULES, SERVER):
        expected = hashlib.sha256((self.f.root / relative).read_bytes()).hexdigest() + "  " + relative
        self.assertIn(expected + "\n", payload, f"contract(D17b):pkg-{sub_id}/{duty}: {relative}")
    self.assertEqual(2, len(self.f.snapshot.calls), f"contract(D17b):pkg-{sub_id}/{duty}: recollect both graphs")
    for key, apk in (("targetBenchmarkApk", self.f.target), ("macrobenchmarkTestApk", self.f.test_apk)):
        self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), refs[key]["sha256"],
                         f"contract(D17b):pkg-{sub_id}/{duty}: {key}")
    self.positive(sub_id, duty, lambda: self.f.verify_refs(refs))
install_case("collection-fresh", "new-sources-parser-two-apks-graphs-rules", payload_and_collection, "positive")


def dex_transport(self):
    # Exercise the existing real ZIP extraction layer, replacing only subprocess.
    sub_id, duty = "dex-transport", "extract-each-final-apk-dex"
    self.f.apk(self.f.target, {"classes.dex": CLEAN_DEX.encode(), "classes2.dex": GOOD_DEX.encode()})
    members = []
    def run(arguments, **kwargs):
        path = Path(arguments[1])
        members.append(path.name)
        return subprocess.CompletedProcess(arguments, 0, path.read_bytes(), b"")
    with unittest.mock.patch.object(packaging.subprocess, "run", side_effect=run):
        text = packaging.dex_dump(Path("dexdump"), self.f.target)
    self.assertEqual(["classes.dex", "classes2.dex"], members, f"contract(D17b):pkg-{sub_id}/{duty}")
    self.assertIn(GOOD_DEX, text, f"contract(D17b):pkg-{sub_id}/{duty}")
install_case("dex-transport", "extract-each-final-apk-dex", dex_transport, "host-transport-only")





class PackageEntryTest(unittest.TestCase):
    longMessage = False

    def test_existing_verify_cli_calls_package_seam_and_propagates_failure(self):
        # Deferred import avoids the legacy VerifyTest -> PackageFixture fixture cycle.
        from test_verify_s5_benchmark_artifacts import VerifyTest
        old = VerifyTest()
        old.setUp()
        self.addCleanup(old.tearDown)
        seen = []
        real_verify = packaging.verify
        def entry(args):
            return real_verify(args, run=old.run_tool, dex=old.dex_tool)
        def capture(repo, **kwargs):
            seen.append((repo, kwargs))
        with unittest.mock.patch.object(packaging, "verify", side_effect=entry), \
                unittest.mock.patch.object(packaging, "verify_d17_package", side_effect=capture):
            self.assertEqual(0, packaging.main(old.arguments()), "contract(D17b):pkg-cli/package-entry-positive")
        self.assertEqual(1, len(seen), "contract(D17b):pkg-cli/package-seam-once")
        repo, kwargs = seen[0]
        self.assertEqual(old.repo, Path(repo), "contract(D17b):pkg-cli/candidate-repo")
        self.assertEqual(old.files["target.apk"], Path(kwargs["target_apk"]), "contract(D17b):pkg-cli/current-final-target")
        self.assertEqual(set(VARIANTS), set(kwargs["production_apks"]), "contract(D17b):pkg-cli/all-final-production")
        self.assertTrue(kwargs["compile_classpath"], "contract(D17b):pkg-cli/current-Keep-classpath")
        self.assertEqual(old.pkg.configuration, Path(kwargs["r8_configuration"]), "contract(D17b):pkg-cli/current-merged-rules")
        with unittest.mock.patch.object(packaging, "verify", side_effect=entry), \
                unittest.mock.patch.object(packaging, "verify_d17_package", side_effect=packaging.VerificationError("D17 injected")):
            self.assertEqual(2, packaging.main(old.arguments()), "contract(D17b):pkg-cli/package-defect-exit2")


    def ci_arguments(self, old):
        # Read the actual caller; only shell variables/path transports are substituted.
        # Deferred import keeps shell parsing local to this caller-fixture helper.
        import shlex
        workflow = Path(packaging.__file__).resolve().parents[2] / ".github/workflows/android.yml"
        step = workflow.read_text().split("      - name: Verify S5 benchmark artifacts\n", 1)[1]
        step = step.split("\n      - name:", 1)[0]
        command = step.split("          python3 tools/performance/verify_s5_benchmark_artifacts.py verify", 1)[1]
        tokens = shlex.split("verify" + command.replace(chr(92) + "\n", " "))
        fixture = old.arguments()
        values = dict(zip(fixture[1::2], fixture[2::2]))
        supplied_paths = {
            "--debug-apk": "app/build/outputs/apk/debug/app-debug.apk",
            "--benchmark-apk": "app/build/outputs/apk/benchmark/app-benchmark.apk",
            "--s5-source-report": "build/reports/s5/sourcesets.txt",
            "--s5-r8-configuration": "app/build/outputs/mapping/s5Benchmark/configuration.txt",
            "--ci-minified-source-report": "build/reports/s5/sourcesets.txt",
        }
        argv, index = ["verify"], 1
        while index < len(tokens):
            flag = tokens[index]
            if flag == "${S5_CLASSPATH_ARGS[@]}":
                argv += ["--s5-compile-classpath-entry", values["--s5-compile-classpath-entry"]]
                index += 1
                continue
            self.assertIn(flag, values, "contract(D17b):pkg-ci/known-verify-argument")
            self.assertLess(index + 1, len(tokens), "contract(D17b):pkg-ci/argument-value")
            if flag in supplied_paths:
                self.assertEqual(supplied_paths[flag], tokens[index + 1],
                                 "contract(D17b):pkg-ci/current-build-output-path")
            argv += [flag, values[flag]]
            index += 2
        self.assertEqual(sorted(fixture[1::2]), sorted(argv[1::2]),
                         "contract(D17b):pkg-ci/existing-and-required-arguments")
        self.assertIn("compileS5BenchmarkJavaWithJavac", step,
                      "contract(D17b):pkg-ci/current-compiler-classpath")
        self.assertIn('.get().classpath.files', step, "contract(D17b):pkg-ci/task-input-classpath")
        self.assertIn('rm -f build/reports/s5/compile-classpath.txt', step,
                      "contract(D17b):pkg-ci/no-old-report")
        self.assertIn('rm -f build/reports/s5/compile-classpath-omitted.txt', step,
                      "contract(D17b):pkg-ci/no-old-omission-report")
        self.assertIn('test -f build/reports/s5/compile-classpath-omitted.txt', step,
                      "contract(D17b):pkg-ci/empty-omission-report-is-valid")
        return argv

    def test_ci_workflow_argv_success_and_missing_inputs_fail_closed(self):
        # Deferred import avoids the legacy VerifyTest/PackageFixture cycle.
        from test_verify_s5_benchmark_artifacts import VerifyTest
        old = VerifyTest()
        old.setUp()
        self.addCleanup(old.tearDown)
        argv = self.ci_arguments(old)
        real_verify = packaging.verify
        def entry(args):
            return real_verify(args, run=old.run_tool, dex=old.dex_tool)
        with unittest.mock.patch.object(packaging, "verify", side_effect=entry), \
                unittest.mock.patch.object(packaging, "verify_d17_package") as seam:
            self.assertEqual(0, packaging.main(argv), "contract(D17b):pkg-ci/verify-success")
            self.assertEqual(1, seam.call_count, "contract(D17b):pkg-ci/seam-once")
            kwargs = seam.call_args.kwargs
            self.assertEqual(old.files["sourcesets.txt"].read_text(), kwargs["source_report"],
                             "contract(D17b):pkg-ci/same-current-source-report")
            for flag in ("--debug-apk", "--benchmark-apk", "--s5-source-report",
                         "--s5-compile-classpath-entry", "--s5-r8-configuration"):
                with self.subTest(missing=flag):
                    path = Path(argv[argv.index(flag) + 1])
                    data = path.read_bytes()
                    path.unlink()
                    seam.reset_mock()
                    try:
                        self.assertEqual(2, packaging.main(argv),
                                         "contract(D17b):pkg-ci/missing-input-exit2")
                        seam.assert_not_called()
                    finally:
                        path.write_bytes(data)
                    incomplete = list(argv)
                    index = incomplete.index(flag)
                    del incomplete[index:index + 2]
                    with self.assertRaises(SystemExit) as caught:
                        packaging.main(incomplete)
                    self.assertEqual(2, caught.exception.code,
                                     "contract(D17b):pkg-ci/missing-argument-exit2")
        with unittest.mock.patch.object(packaging, "verify", side_effect=entry), \
                unittest.mock.patch.object(packaging, "verify_d17_package",
                                           side_effect=packaging.VerificationError("D17 CI injected")):
            self.assertEqual(2, packaging.main(argv), "contract(D17b):pkg-ci/seam-failure-exit2")

    def test_runner_prepare_calls_seven_ref_seam_and_propagates_failure(self):
        # Deferred import avoids the purity -> RunnerHarness test module cycle.
        from test_s5_runner import PrepareArtifactsTest
        old = PrepareArtifactsTest()
        old.setUp()
        self.addCleanup(old.doCleanups)
        old.prepare()
        self.assertEqual(1, len(old.d17_calls), "contract(D17b):pkg-prepare/ref-seam-once")
        root, evidence, refs, kwargs = old.d17_calls[0]
        self.assertEqual(old.snapshot, root, "contract(D17b):pkg-prepare/built-candidate")
        self.assertEqual(old.root, evidence, "contract(D17b):pkg-prepare/artifact-root")
        self.assertEqual(7, len(refs), "contract(D17b):pkg-prepare/seven-assembled-before-check")
        self.assertTrue(all(r["path"].startswith("artifacts/") for r in refs.values()),
                        "contract(D17b):pkg-prepare/root-relative-refs")
        # New fixture: do not overwrite a prior immutable publication.
        broken = PrepareArtifactsTest()
        broken.setUp()
        self.addCleanup(broken.doCleanups)
        broken.d17_error = artifacts.ArtifactError("injected ref defect")
        with self.assertRaises((artifacts.ArtifactError, __import__("s5_runner").PreSession)) as caught:
            broken.prepare()
        self.assertIn("injected ref defect", str(caught.exception), "contract(D17b):pkg-prepare/ref-defect-propagates")
        self.assertEqual([True], broken.cleaned, "contract(D17b):pkg-prepare/snapshot-finally-cleaned")

    def test_collect_cli_checks_all_seven_candidate_refs(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        f = PackageFixture(Path(temp.name))
        seen = []
        def capture(root, evidence, refs, **kwargs):
            seen.append((root, evidence, refs, kwargs))
        argv = ["collect", "--build-root", str(f.root), "--gradle-home", str(f.snapshot.home),
                "--out", str(f.out)]
        with unittest.mock.patch.object(packaging, "FIXTURE_ASSETS", f.snapshot.approved()), \
                unittest.mock.patch.object(artifacts, "_run", f.snapshot.run), \
                unittest.mock.patch.object(artifacts, "verify_d17_artifact_refs", side_effect=capture):
            # collect's default process runner is bound at definition; explicitly route only transport.
            real_collect = artifacts.collect
            def collect(*args, **kwargs):
                kwargs["run"] = f.snapshot.run
                return real_collect(*args, **kwargs)
            with unittest.mock.patch.object(artifacts, "collect", side_effect=collect):
                self.assertEqual(0, artifacts.main(argv), "contract(D17b):pkg-collect/cli-positive")
        self.assertEqual(1, len(seen), "contract(D17b):pkg-collect/ref-seam-once")
        self.assertEqual(7, len(seen[0][2]), "contract(D17b):pkg-collect/seven-before-success")
        temp2 = tempfile.TemporaryDirectory()
        self.addCleanup(temp2.cleanup)
        f2 = PackageFixture(Path(temp2.name))
        argv2 = ["collect", "--build-root", str(f2.root), "--gradle-home", str(f2.snapshot.home), "--out", str(f2.out)]
        with unittest.mock.patch.object(packaging, "FIXTURE_ASSETS", f2.snapshot.approved()), \
                unittest.mock.patch.object(artifacts, "verify_d17_artifact_refs", side_effect=artifacts.ArtifactError("D17 injected")):
            def collect2(*args, **kwargs):
                kwargs["run"] = f2.snapshot.run
                return real_collect(*args, **kwargs)
            with unittest.mock.patch.object(artifacts, "collect", side_effect=collect2):
                self.assertEqual(2, artifacts.main(argv2), "contract(D17b):pkg-collect/ref-defect-exit2")






class CandidateManifestTest(unittest.TestCase):
    """Manifest preconditions with placeholder bytes; no real APK target runs."""

    def setUp(self):
        # Deferred helper-only imports keep the candidate TestCase out of discovery.
        from candidate_pkg_check import validate_candidate_inputs, load_candidate_inputs
        self.validate = validate_candidate_inputs
        self.load = load_candidate_inputs
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name).resolve() / "repo"
        files = (
            "app/src/s5Benchmark/java/com/jay/fxi/s5fixture/S5PurityJournal.kt",
            "app/src/main/java/com/jay/fxi/App.kt",
            "macrobenchmark/src/main/kotlin/com/jay/fxi/macrobenchmark/TetherGraphBenchmark.kt",
            "tools/performance/verify_s5_benchmark_artifacts.py",
            "tools/performance/s5_artifacts.py", "tools/performance/benchmark_gate.py",
            "tools/performance/evidence-manifest.schema.json",
            "app/build.gradle.kts", "app/proguard-rules.pro",
            "build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml",
            ".github/workflows/android.yml",
        )
        for relative in files:
            self.write(relative)
        values = {
            "--aapt2": "aapt2", "--apksigner": "apksigner", "--dexdump": "dexdump",
            "--repo-root": str(self.repo),
            "--s5-target-apk": "app/build/outputs/apk/s5Benchmark/app-s5Benchmark.apk",
            "--s5-test-apk": "macrobenchmark/build/outputs/apk/s5Benchmark/macrobenchmark-s5Benchmark.apk",
            "--debug-apk": "app/build/outputs/apk/debug/app-debug.apk",
            "--ci-minified-apk": "app/build/outputs/apk/ciMinified/app-ciMinified.apk",
            "--benchmark-apk": "app/build/outputs/apk/benchmark/app-benchmark.apk",
            "--ci-minified-mapping": "app/build/outputs/mapping/ciMinified/mapping.txt",
            "--firebase-fixture": "ci/google-services.ci-fixture.json",
            "--s5-dependency-report": "build/reports/s5/profileinstaller.txt",
            "--ci-minified-dependency-report": "build/reports/s5/ci-minified-mockwebserver.txt",
            "--ci-minified-source-report": "build/reports/s5/sourcesets.txt",
            "--ci-minified-source-files": "build/reports/s5/ci-minified-source-files.txt",
            "--benchmark-source": "macrobenchmark/src/main/kotlin/com/jay/fxi/macrobenchmark/TetherGraphBenchmark.kt",
            "--s5-source-report": "build/reports/s5/sourcesets.txt",
            "--s5-compile-classpath-entry": "candidate-classpath/annotation.jar",
            "--s5-r8-configuration": "app/build/outputs/mapping/s5Benchmark/configuration.txt",
        }
        argv = ["verify"]
        apks, sources = {}, {}
        for flag, value in values.items():
            if flag not in ("--aapt2", "--apksigner", "--dexdump", "--repo-root"):
                path = self.write(value)
                value = str(path)
                (apks if flag.endswith("-apk") else sources)[value] = self.digest(path)
            argv += [flag, value]
        # Real sourceSets syntax, including a current generated source outside app/src.
        report = SOURCE_REPORT.replace(
            "Java sources: [app/src/s5Benchmark/java]",
            "Java sources: [app/src/s5Benchmark/java, app/build/generated/d17]")
        for variant in ("debug", "release", "benchmark"):
            report += (f"\n{variant}\n{'-' * len(variant)}\n"
                       f"Java sources: [app/src/{variant}/java]\nAssets: [app/src/{variant}/assets]\n")
        report_path = self.repo / "build/reports/s5/sourcesets.txt"
        report_path.write_text(report, encoding="utf-8")
        sources[str(report_path)] = self.digest(report_path)
        generated = self.write("app/build/generated/d17/Generated.kt")
        sources[str(generated)] = self.digest(generated)
        classpath_report = self.write("build/reports/s5/compile-classpath.txt")
        classpath_report.write_text(str(self.repo / "candidate-classpath/annotation.jar") + "\n", encoding="utf-8")
        sources[str(classpath_report)] = self.digest(classpath_report)
        omitted = self.write("build/reports/s5/compile-classpath-omitted.txt")
        omitted.write_text("", encoding="utf-8")
        sources[str(omitted)] = self.digest(omitted)
        sources.update({str(self.repo / p): self.digest(self.repo / p) for p in files})
        self.inputs = {"sourceHashes": sources, "apkHashes": apks, "argv": argv}

    def write(self, relative):
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(("placeholder: " + relative).encode())
        return path

    @staticmethod
    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def reject(self, inputs):
        with self.assertRaisesRegex(AssertionError, r"^precondition\(D17-pkg\):"):
            self.validate(inputs, self.repo)

    def test_binding_control_is_precondition_only(self):
        self.assertEqual(self.inputs["argv"], self.validate(self.inputs, self.repo))
        # Equal overlaps are allowed; conflicts can never be hidden by a merge.
        path, digest = next(iter(self.inputs["apkHashes"].items()))
        self.inputs["sourceHashes"][path] = digest
        self.assertEqual(self.inputs["argv"], self.validate(self.inputs, self.repo))

    def test_each_wrong_subcommand_schema_or_binding_is_precondition_failure(self):
        # Local import keeps this fixture's copying dependency next to its use.
        import copy
        def changed():
            return copy.deepcopy(self.inputs)
        cases = []
        bad = changed()
        bad["argv"] = ["collect-source-files", "--repo-root", str(self.repo),
                       "--ci-minified-source-report", str(self.repo / "build/reports/s5/sourcesets.txt"),
                       "--out", str(self.repo / "unwritten-list.txt")]
        cases.append(("wrong-subcommand", bad))
        for name, value in (("empty", []), ("parser-error", ["verify"]), ("type", "verify")):
            bad = changed()
            bad["argv"] = value
            cases.append((name, bad))
        bad = changed()
        bad["argv"][bad["argv"].index("--repo-root") + 1] = str(self.repo.parent)
        cases.append(("different-repo", bad))
        for path in self.inputs["apkHashes"]:
            bad = changed()
            del bad["apkHashes"][path]
            cases.append(("missing-apk-binding/" + path, bad))
        for flag in ("--s5-target-apk", "--s5-test-apk", "--debug-apk",
                     "--ci-minified-apk", "--benchmark-apk"):
            bad = changed()
            bad["argv"][bad["argv"].index(flag) + 1] = str(self.repo / "app/src/main/java/com/jay/fxi/App.kt")
            cases.append(("wrong-apk-path/" + flag, bad))
            bad = changed()
            original = bad["argv"][bad["argv"].index(flag) + 1]
            alternate = str(self.repo / "app/src/main/java/com/jay/fxi/App.kt")
            bad["argv"][bad["argv"].index(flag) + 1] = alternate
            del bad["apkHashes"][original]
            bad["apkHashes"][alternate] = self.digest(Path(alternate))
            cases.append(("rebound-to-noncandidate-apk/" + flag, bad))
        for path in self.inputs["sourceHashes"]:
            bad = changed()
            del bad["sourceHashes"][path]
            cases.append(("missing-source-binding/" + path, bad))
        for field in ("sourceHashes", "apkHashes"):
            bad = changed()
            path = next(iter(bad[field]))
            bad[field][path] = "0" * 64
            cases.append(("digest-mismatch/" + field, bad))
            bad = changed()
            bad[field] = {}
            cases.append(("empty-map/" + field, bad))
            bad = changed()
            bad[field] = []
            cases.append(("wrong-map-type/" + field, bad))
        bad = changed()
        path = next(iter(bad["apkHashes"]))
        bad["sourceHashes"][path] = "0" * 64
        cases.append(("conflicting-overlap", bad))
        bad = changed()
        bad["argv"][bad["argv"].index("--debug-apk") + 1] = "app/build/outputs/apk/debug/app-debug.apk"
        cases.append(("relative-argv-path", bad))
        bad = changed()
        bad["sourceHashes"]["relative.kt"] = "0" * 64
        cases.append(("relative-binding", bad))
        bad = changed()
        bad["unexpected"] = 1
        cases.append(("unknown-schema-key", bad))
        for name, inputs in cases:
            with self.subTest(case=name):
                self.reject(inputs)

    def test_classpath_must_match_current_task_report(self):
        path = self.repo / "build/reports/s5/compile-classpath.txt"
        path.write_bytes(b"")
        self.inputs["sourceHashes"][str(path)] = self.digest(path)
        self.reject(self.inputs)

    def test_filtered_classpath_and_omission_report_are_bound_separately(self):
        omitted = self.repo / "build/reports/s5/compile-classpath-omitted.txt"
        missing = self.repo / "app/build/generated/ksp/s5Benchmark/classes"
        self.assertFalse(missing.exists())
        omitted.write_text("missing-optional-ksp-classes\t" + str(missing) + "\n", encoding="utf-8")
        self.inputs["sourceHashes"][str(omitted)] = self.digest(omitted)
        self.assertEqual(self.inputs["argv"], self.validate(self.inputs, self.repo))
        self.assertNotIn(str(missing), self.inputs["argv"])
        self.inputs["argv"] += ["--s5-compile-classpath-entry", str(missing)]
        self.reject(self.inputs)

    def test_missing_or_changed_omission_report_is_rejected(self):
        path = self.repo / "build/reports/s5/compile-classpath-omitted.txt"
        del self.inputs["sourceHashes"][str(path)]
        self.reject(self.inputs)
        self.inputs["sourceHashes"][str(path)] = self.digest(path)
        path.write_bytes(b"changed")
        self.reject(self.inputs)
        path.unlink()
        self.reject(self.inputs)

    def test_classpath_order_and_missing_jar_still_fail_closed(self):
        jar = self.write("candidate-classpath/second.jar")
        self.inputs["sourceHashes"][str(jar)] = self.digest(jar)
        self.inputs["argv"] += ["--s5-compile-classpath-entry", str(jar)]
        first = self.repo / "candidate-classpath/annotation.jar"
        report = self.repo / "build/reports/s5/compile-classpath.txt"
        report.write_text(str(first) + "\n" + str(jar) + "\n", encoding="utf-8")
        self.inputs["sourceHashes"][str(report)] = self.digest(report)
        self.assertEqual(self.inputs["argv"], self.validate(self.inputs, self.repo))
        report.write_text(str(jar) + "\n" + str(first) + "\n", encoding="utf-8")
        self.inputs["sourceHashes"][str(report)] = self.digest(report)
        self.reject(self.inputs)
        report.write_text(str(first) + "\n" + str(jar) + "\n", encoding="utf-8")
        self.inputs["sourceHashes"][str(report)] = self.digest(report)
        del self.inputs["sourceHashes"][str(jar)]
        jar.unlink()
        self.reject(self.inputs)

    def test_conflicting_overlap_is_rejected_explicitly(self):
        path = next(iter(self.inputs["apkHashes"]))
        self.inputs["sourceHashes"][path] = "0" * 64
        with self.assertRaisesRegex(AssertionError, "conflicting digest across maps"):
            self.validate(self.inputs, self.repo)

    def test_new_unbound_source_and_changed_bytes_are_precondition_failures(self):
        extra = self.write("app/src/debug/java/com/jay/fxi/Unbound.kt")
        self.reject(self.inputs)
        extra.unlink()
        path = self.repo / "app/src/s5Benchmark/java/com/jay/fxi/s5fixture/S5PurityJournal.kt"
        path.write_bytes(b"changed source")
        self.reject(self.inputs)

    def test_missing_malformed_or_duplicate_manifest_is_precondition_failure(self):
        for value in (None, str(self.repo / "missing.json")):
            with self.subTest(path=value), self.assertRaisesRegex(AssertionError, r"^precondition\(D17-pkg\):"):
                self.load(value, self.repo)
        path = self.repo / "inputs.json"
        for raw in (b"{", b'{"sourceHashes":{},"sourceHashes":{},"apkHashes":{},"argv":[]}'):
            path.write_bytes(raw)
            with self.assertRaisesRegex(AssertionError, r"^precondition\(D17-pkg\):"):
                self.load(str(path), self.repo)


if __name__ == "__main__":
    unittest.main()
