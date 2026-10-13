import io
import json
import re
import tempfile
import unittest
import zipfile
from pathlib import Path

import verify_s5_benchmark_artifacts as v


def network(base="false", domains=(("127.0.0.1", "false"),), extra=""):
    text = "E: network-security-config (line=3)\n"
    text += f"    E: base-config (line=4)\n      A: cleartextTrafficPermitted={base}\n"
    text += "    E: domain-config (line=5)\n      A: cleartextTrafficPermitted=true\n"
    for name, sub in domains:
        text += f"        E: domain (line=6)\n          A: includeSubdomains={sub}\n            T: '{name}'\n"
    return text + extra


FIXTURE = json.dumps({
    "project_info": {"project_number": "111", "project_id": "fixture-project"},
    "client": [{"client_info": {"mobilesdk_app_id": "1:111:android:aaa", "android_client_info": {"package_name": "com.jay.fxi"}}}],
})


def resources(app_id="1:111:android:aaa", project="fixture-project", sender="111", extra=""):
    return (
        "    resource 0x7f0f004e string/gcm_defaultSenderId\n"
        f'      () "{sender}"\n'
        "    resource 0x7f0f0053 string/google_app_id\n"
        f'      () "{app_id}"\n'
        "    resource 0x7f0f0093 string/project_id\n"
        f'      () "{project}"\n'
        "    resource 0x7f120004 xml/network_security_config\n"
        "      () (file) res/8G.xml type=XML\n" + extra
    )


def apk_with(assets: dict[str, bytes]) -> zipfile.ZipFile:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        for name, data in assets.items():
            z.writestr("assets/" + name, data)
    buffer.seek(0)
    return zipfile.ZipFile(buffer)


SOURCE_REPORT = """main
----
Java sources: [app/src/main/java]
Kotlin sources: [app/src/main/kotlin, app/src/main/java]
Assets: [app/src/main/assets]

ciMinified
----------
Java sources: [app/src/ciMinified/java, app/src/productionBindings/java]
Kotlin sources: [app/src/ciMinified/kotlin, app/src/ciMinified/java, app/src/productionBindings/java]
Assets: [app/src/ciMinified/assets]

s5Benchmark
-----------
Java sources: [app/src/s5Benchmark/java]
Assets: [app/src/s5Benchmark/assets, app/src/benchmark/assets/s5_4]
"""


class FirebaseTest(unittest.TestCase):
    def test_matching_identifiers_pass_and_each_mismatch_fails_without_the_value(self):
        expected = v.expected_firebase(FIXTURE)
        v.check_firebase(resources(), expected)
        for wrong in (dict(app_id="1:222:android:bbb"), dict(project="other"), dict(sender="222")):
            with self.assertRaises(v.VerificationError) as caught:
                v.check_firebase(resources(**wrong), expected)
            self.assertNotIn("222", str(caught.exception))
            self.assertNotIn("other", str(caught.exception))

    def test_a_duplicated_identifier_fails(self):
        with self.assertRaises(v.VerificationError):
            v.resource_strings(resources(extra='    resource 0x7f0f0099 string/project_id\n      () "x"\n'), ["project_id"])

    def test_the_fixture_must_have_one_app_client(self):
        with self.assertRaises(v.VerificationError):
            v.expected_firebase(json.dumps({"project_info": {}, "client": []}))


class NetworkTest(unittest.TestCase):
    def test_the_reference_is_followed_to_the_packaged_file(self):
        manifest = "      A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f120004\n"
        self.assertEqual("res/8G.xml", v.resource_file(resources(), v.network_config_reference(manifest)))
        with self.assertRaises(v.VerificationError):
            v.network_config_reference(manifest * 2)
        with self.assertRaises(v.VerificationError):
            v.resource_file(resources(), "0x7f129999")

    def test_the_fixture_allows_cleartext_to_127_0_0_1_only(self):
        allowed = {"127.0.0.1": "false"}
        v.check_network(v.parse_network_config(network()), allowed, "s5")
        failing = [
            network(base="true"),
            network(domains=(("127.0.0.1", "false"), ("localhost", "false"))),
            network(domains=(("127.0.0.1", "true"),)),
            network(domains=(("::1", "false"),)),
            network(extra="    E: domain-config (line=9)\n      A: cleartextTrafficPermitted=true\n"),
            network(extra="    E: debug-overrides (line=9)\n"),
            network(extra="      E: trust-anchors (line=9)\n"),
        ]
        for text in failing:
            with self.assertRaises(v.VerificationError, msg=text):
                v.check_network(v.parse_network_config(text), allowed, "s5")

    def test_ci_minified_keeps_exactly_the_kb_image_exception(self):
        allowed = {v.KB_IMAGE_DOMAIN: "true"}
        v.check_network(v.parse_network_config(network(domains=((v.KB_IMAGE_DOMAIN, "true"),))), allowed, "ci")
        for text in (
            network(base="true", domains=((v.KB_IMAGE_DOMAIN, "true"),)),
            network(domains=((v.KB_IMAGE_DOMAIN, "true"), ("127.0.0.1", "false"))),
            network(domains=(("127.0.0.1", "false"),)),
            network(domains=((v.KB_IMAGE_DOMAIN, "false"),)),
            network(domains=(("localhost", "false"),)),
        ):
            with self.assertRaises(v.VerificationError, msg=text):
                v.check_network(v.parse_network_config(text), allowed, "ci")


class AssetTest(unittest.TestCase):
    def setUp(self):
        self.real = {}
        root = Path(__file__).resolve().parents[2] / "app/src/benchmark/assets/s5_4"
        for name in v.FIXTURE_ASSETS:
            self.real[name] = (root / name).read_bytes()

    def test_the_constants_are_the_test_constants(self):
        repo = Path(__file__).resolve().parents[2]
        bf1 = (repo / "app/src/test/java/com/jay/fxi/ui/premium/graph/TetherBenchmarkFixtureTest.kt").read_text(encoding="utf-8")
        usd = (repo / "app/src/test/java/com/jay/fxi/ui/premium/graph/S5UsdFixtureAssetTest.kt").read_text(encoding="utf-8")
        for name, digest in v.FIXTURE_ASSETS.items():
            self.assertIn(digest, usd if name == "usd-1d.json" else bf1, name)

    def test_the_approved_bytes_pass_and_a_missing_or_changed_asset_fails(self):
        v.check_assets(apk_with(self.real), "s5")
        missing = dict(self.real)
        del missing["usd-1d.json"]
        changed = dict(self.real)
        changed["bundle.json"] = changed["bundle.json"] + b" "
        for assets in (missing, changed):
            with self.assertRaises(v.VerificationError):
                v.check_assets(apk_with(assets), "s5")

    def test_ci_minified_must_package_no_fixture_asset(self):
        v.check_no_assets(apk_with({"other.json": b"{}"}), "ci")
        for name in v.FIXTURE_ASSETS:
            with self.assertRaises(v.VerificationError):
                v.check_no_assets(apk_with({name: b"{}"}), "ci")


class DependencyAndSourceTest(unittest.TestCase):
    def test_profileinstaller_must_resolve_1_4_1(self):
        v.check_dependency_resolved("androidx.profileinstaller:profileinstaller:1.4.1\n", "androidx.profileinstaller:profileinstaller", "1.4.1", "s5")
        with self.assertRaises(v.VerificationError):
            v.check_dependency_resolved("androidx.profileinstaller:profileinstaller:1.4.0\n", "androidx.profileinstaller:profileinstaller", "1.4.1", "s5")

    def test_mockwebserver_must_be_absent_from_ci_minified(self):
        absent = "No dependencies matching given input were found in configuration ':app:ciMinifiedRuntimeClasspath'\n"
        v.check_dependency_absent(absent, "com.squareup.okhttp3:mockwebserver", "ci")
        for report in ("com.squareup.okhttp3:mockwebserver:4.12.0\n", "", "something else\n"):
            with self.assertRaises(v.VerificationError):
                v.check_dependency_absent(report, "com.squareup.okhttp3:mockwebserver", "ci")

    def _repo(self, root: Path) -> None:
        for path in ("app/src/main/java/com/jay/fxi/App.kt", "app/src/ciMinified/java/X.kt", "app/src/productionBindings/java/Y.kt",
                     "app/src/s5Benchmark/java/S5.kt", "app/src/benchmark/assets/s5_4/bundle.json"):
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            (root / path).write_text("x", encoding="utf-8")

    def test_collected_files_from_main_and_ci_minified_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            self._repo(repo)
            listing = v.collect_source_files(SOURCE_REPORT, repo)
            self.assertEqual(v.SOURCE_LIST_HEADER, listing.splitlines()[0])
            self.assertIn("file\tapp/src/main/java/com/jay/fxi/App.kt", listing)
            self.assertIn("file\tapp/src/productionBindings/java/Y.kt", listing)
            self.assertNotIn("S5.kt", listing)
            v.check_source_inputs(SOURCE_REPORT, listing, repo)

    def test_a_file_symlink_inside_a_normal_root_that_reaches_the_fixture_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            self._repo(repo)
            (repo / "app/src/ciMinified/java/Leak.kt").symlink_to(repo / "app/src/s5Benchmark/java/S5.kt")
            listing = v.collect_source_files(SOURCE_REPORT, repo)
            self.assertIn("file\tapp/src/ciMinified/java/Leak.kt", listing)
            with self.assertRaises(v.VerificationError):
                v.check_source_inputs(SOURCE_REPORT, listing, repo)

    def test_a_directory_symlink_is_walked_and_a_fixture_link_inside_it_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            self._repo(repo)
            allowed = repo / "app/src/main/java/com/jay/fxi/more"
            allowed.mkdir()
            (allowed / "Deep.kt").symlink_to(repo / "app/src/s5Benchmark/java/S5.kt")
            (repo / "app/src/ciMinified/java/dirlink").symlink_to(allowed, target_is_directory=True)
            listing = v.collect_source_files(SOURCE_REPORT, repo)
            self.assertIn("file\tapp/src/ciMinified/java/dirlink", listing)
            self.assertIn("file\tapp/src/ciMinified/java/dirlink/Deep.kt", listing)
            with self.assertRaises(v.VerificationError):
                v.check_source_inputs(SOURCE_REPORT, listing, repo)

    def test_a_cycle_or_a_broken_link_fails_the_collection(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            self._repo(repo)
            (repo / "app/src/ciMinified/java/loop").symlink_to(repo / "app/src/ciMinified", target_is_directory=True)
            with self.assertRaisesRegex(v.VerificationError, "symlink cycle"):
                v.collect_source_files(SOURCE_REPORT, repo)
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            self._repo(repo)
            (repo / "app/src/ciMinified/java/gone.kt").symlink_to(repo / "nowhere.kt")
            with self.assertRaisesRegex(v.VerificationError, "broken symlink"):
                v.collect_source_files(SOURCE_REPORT, repo)

    def test_a_fixture_root_a_missing_section_or_a_bad_list_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            self._repo(repo)
            good = v.collect_source_files(SOURCE_REPORT, repo)
            leaked = SOURCE_REPORT.replace("Assets: [app/src/ciMinified/assets]", "Assets: [app/src/ciMinified/assets, app/src/benchmark/assets/s5_4]")
            via_dots = SOURCE_REPORT.replace("[app/src/ciMinified/java,", "[app/src/ciMinified/../s5Benchmark/java,")
            no_ci = SOURCE_REPORT.replace("ciMinified\n----------", "benchmark\n---------")
            no_main = SOURCE_REPORT.replace("main\n----", "debug\n-----")
            for report in (leaked, via_dots):
                with self.assertRaises(v.VerificationError, msg=report[:40]):
                    v.check_source_inputs(report, v.collect_source_files(report, repo), repo)
            for report in (no_ci, no_main, ""):
                with self.assertRaises(v.VerificationError):
                    v.collect_source_files(report, repo)
            other_variant = good.replace(v.SOURCE_LIST_HEADER, "project=:app variant=benchmark")
            no_files = "\n".join(line for line in good.splitlines() if not line.startswith("file\t")) + "\n"
            wrong_roots = good.replace("root\tapp/src/main/java\n", "")
            for listing in ("", other_variant, no_files, wrong_roots, good + "junk\n"):
                with self.assertRaises(v.VerificationError, msg=listing[:40]):
                    v.check_source_inputs(SOURCE_REPORT, listing, repo)


class ObfuscatedLeakTest(unittest.TestCase):
    def test_the_mapping_and_dex_must_not_name_fixture_classes(self):
        v.check_mapping("com.jay.fxi.MainActivity -> com.jay.fxi.MainActivity:\n")
        v.check_dex_classes("  Class descriptor  : 'Lcom/jay/fxi/MainActivity;'\n")
        for mapping in ("com.jay.fxi.s5fixture.S5FixtureServer -> a.b:\n", "okhttp3.mockwebserver.MockWebServer -> c.d:\n"):
            with self.assertRaises(v.VerificationError):
                v.check_mapping(mapping)
        for dex in ("  Class descriptor  : 'Lcom/jay/fxi/s5fixture/S5FixtureServer;'\n", "  Class descriptor  : 'Lokhttp3/mockwebserver/MockWebServer;'\n"):
            with self.assertRaises(v.VerificationError):
                v.check_dex_classes(dex)

    def test_the_benchmark_targets_the_app(self):
        v.check_target_package_constant('private const val TARGET_PACKAGE = "com.jay.fxi"')
        with self.assertRaises(v.VerificationError):
            v.check_target_package_constant('private const val TARGET_PACKAGE = "com.jay.fxi.macrobenchmark"')


NS = "http://schemas.android.com/apk/res/android:"


def meta(name: str, value: str) -> str:
    return (f"          E: meta-data (line=1)\n            A: {NS}name(0x01010003)=\"{name}\" (Raw: \"{name}\")\n"
            f"            A: {NS}value(0x01010024)={value}\n")


def target_manifest(marker="true", flags=("false", "false", "false"), profileable=True):
    text = "E: manifest (line=2)\n  A: package=\"com.jay.fxi\" (Raw: \"com.jay.fxi\")\n      E: application (line=20)\n"
    text += f"        A: {NS}networkSecurityConfig(0x01010527)=@0x7f120004\n"
    if profileable:
        text += f"          E: profileable (line=59)\n            A: {NS}shell(0x01010594)=true\n"
    for name, value in zip(v.FIREBASE_FLAGS, flags):
        text += meta(name, value)
    return text + meta("com.jay.fxi.TOPIC_V2_RELEASE_ON", marker)


def test_manifest(runner="androidx.test.runner.AndroidJUnitRunner", target="com.jay.fxi.macrobenchmark", query="com.jay.fxi", extra=""):
    return ("E: manifest (line=2)\n      E: queries (line=9)\n          E: package (line=10)\n"
            f"            A: {NS}name(0x01010003)=\"{query}\" (Raw: \"{query}\")\n"
            "      E: instrumentation (line=16)\n"
            f"        A: {NS}name(0x01010003)=\"{runner}\" (Raw: \"{runner}\")\n"
            f"        A: {NS}targetPackage(0x01010021)=\"{target}\" (Raw: \"{target}\")\n" + extra)


class VerifyTest(unittest.TestCase):
    """verify() end to end with the external tools replaced: one passing set of outputs, then one change per case."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        repo = Path(__file__).resolve().parents[2]
        assets = {name: (repo / "app/src/benchmark/assets/s5_4" / name).read_bytes() for name in v.FIXTURE_ASSETS}
        self.files = {}
        for name, contents in (("target.apk", assets), ("test.apk", {}), ("ci.apk", {"other.json": b"{}"})):
            path = root / name
            with zipfile.ZipFile(path, "w") as z:
                for asset, data in contents.items():
                    z.writestr("assets/" + asset, data)
            self.files[name] = path
        for name, text in (
            ("mapping.txt", "com.jay.fxi.MainActivity -> com.jay.fxi.MainActivity:\n"),
            ("fixture.json", FIXTURE),
            ("profileinstaller.txt", "androidx.profileinstaller:profileinstaller:1.4.1\n"),
            ("mockwebserver.txt", "No dependencies matching given input were found in configuration ':app:ciMinifiedRuntimeClasspath'\n"),
            ("sourcesets.txt", SOURCE_REPORT),
            ("Bench.kt", 'private const val TARGET_PACKAGE = "com.jay.fxi"\n'),
        ):
            (root / name).write_text(text, encoding="utf-8")
            self.files[name] = root / name
        self.repo = root / "repo"
        DependencyAndSourceTest._repo(None, self.repo)
        (root / "files.txt").write_text(v.collect_source_files(SOURCE_REPORT, self.repo), encoding="utf-8")
        self.files["files.txt"] = root / "files.txt"
        self.outputs = {
            ("badging", "target.apk"): "package: name='com.jay.fxi' versionCode='15'\n",
            ("manifest", "target.apk"): target_manifest(),
            ("certs", "target.apk"): "Signer #1 certificate DN: C=US, O=Android, CN=Android Debug\n",
            ("resources", "target.apk"): resources(),
            ("network", "target.apk"): network(),
            ("badging", "test.apk"): "package: name='com.jay.fxi.macrobenchmark'\n",
            ("manifest", "test.apk"): test_manifest(),
            ("manifest", "ci.apk"): target_manifest(),
            ("resources", "ci.apk"): resources(),
            ("network", "ci.apk"): network(domains=((v.KB_IMAGE_DOMAIN, "true"),)),
        }

    def tearDown(self):
        self.tmp.cleanup()

    def run_tool(self, *arguments):
        apk = Path(arguments[-1]).name
        if arguments[1:3] == ("dump", "badging"):
            key = "badging"
        elif arguments[1:5] == ("dump", "xmltree", "--file", "AndroidManifest.xml"):
            key = "manifest"
        elif arguments[1:3] == ("dump", "resources"):
            key = "resources"
        elif arguments[1:4] == ("dump", "xmltree", "--file"):
            key = "network"
        elif arguments[1:3] == ("verify", "--print-certs"):
            key = "certs"
        else:
            raise AssertionError(f"unexpected tool call {arguments}")
        return self.outputs[(key, apk)]

    def verify(self):
        f = self.files
        args = v.build_parser().parse_args([
            "verify", "--aapt2", "aapt2", "--apksigner", "apksigner", "--dexdump", "dexdump", "--repo-root", str(self.repo),
            "--s5-target-apk", str(f["target.apk"]), "--s5-test-apk", str(f["test.apk"]), "--ci-minified-apk", str(f["ci.apk"]),
            "--ci-minified-mapping", str(f["mapping.txt"]), "--firebase-fixture", str(f["fixture.json"]),
            "--s5-dependency-report", str(f["profileinstaller.txt"]), "--ci-minified-dependency-report", str(f["mockwebserver.txt"]),
            "--ci-minified-source-report", str(f["sourcesets.txt"]), "--ci-minified-source-files", str(f["files.txt"]),
            "--benchmark-source", str(f["Bench.kt"]),
        ])
        v.verify(args, run=self.run_tool, dex=lambda dexdump, apk: "  Class descriptor  : 'Lcom/jay/fxi/MainActivity;'\n")

    def test_the_passing_outputs_pass(self):
        self.verify()

    def test_each_target_and_test_apk_condition_fails_on_its_own(self):
        flags_true = [tuple("true" if i == n else "false" for i in range(3)) for n in range(3)]
        cases = {
            "debuggable": (("badging", "target.apk"), "package: name='com.jay.fxi'\napplication-debuggable\n", "target is debuggable"),
            "profileable missing": (("manifest", "target.apk"), target_manifest(profileable=False), "profileable android:shell"),
            "marker OFF": (("manifest", "target.apk"), target_manifest(marker="false"), "marker is not ON"),
            "other signer": (("certs", "target.apk"), "Signer #1 certificate DN: CN=Release\n", "not debug-signed"),
            "analytics on": (("manifest", "target.apk"), target_manifest(flags=flags_true[0]), "firebase_analytics_collection_enabled is not false"),
            "crashlytics on": (("manifest", "target.apk"), target_manifest(flags=flags_true[1]), "firebase_crashlytics_collection_enabled is not false"),
            "messaging on": (("manifest", "target.apk"), target_manifest(flags=flags_true[2]), "firebase_messaging_auto_init_enabled is not false"),
            "target package": (("manifest", "test.apk"), test_manifest(target="com.jay.fxi"), "not self-instrumenting"),
            "runner elsewhere": (("manifest", "test.apk"), test_manifest(
                runner="androidx.test.runner.Other",
                extra=f"      E: meta-data (line=30)\n        A: {NS}value(0x01010024)=\"androidx.test.runner.AndroidJUnitRunner\"\n"),
                "runner is not AndroidJUnitRunner"),
            "query elsewhere": (("manifest", "test.apk"), test_manifest(
                query="com.other",
                extra=f"      E: uses-library (line=31)\n        A: {NS}name(0x01010003)=\"com.jay.fxi\" (Raw: \"com.jay.fxi\")\n"),
                "does not query com.jay.fxi"),
            "test package": (("badging", "test.apk"), "package: name='com.jay.fxi'\n", "test APK package"),
            "two instrumentations": (("manifest", "test.apk"), test_manifest(
                extra="      E: instrumentation (line=40)\n"
                      f"        A: {NS}name(0x01010003)=\"androidx.test.runner.AndroidJUnitRunner\" (Raw: \"x\")\n"
                      f"        A: {NS}targetPackage(0x01010021)=\"com.jay.fxi\" (Raw: \"com.jay.fxi\")\n"),
                "must declare one instrumentation"),
        }
        passing = dict(self.outputs)
        for case, (key, output, reason) in cases.items():
            self.outputs = dict(passing)
            self.outputs[key] = output
            with self.assertRaisesRegex(v.VerificationError, re.escape(reason), msg=case):
                self.verify()


if __name__ == "__main__":
    unittest.main()
