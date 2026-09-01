import unittest
from unittest import mock
import tempfile
from pathlib import Path

import device_preflight
from device_preflight import (
    Adb,
    PreflightError,
    acquire_run_lock,
    artifact_identity,
    capture_launcher_result,
    copy_stable_artifact,
    install_apk,
    installed_apk_identity,
    materialize_commit_snapshot,
    parse_active_main_refresh_rate,
    parse_active_internal_viewport,
    parse_battery,
    parse_device_state_name,
    parse_devices,
    parse_surfaceflinger_active_main_refresh_rate,
    parse_wm_size,
    prepare_nonproduction_firebase_fixture,
    release_run_lock,
    run_benchmark_build,
    run_launcher_smoke,
    assert_package_absent,
)


class DevicePreflightParserTest(unittest.TestCase):
    def test_devices_accepts_only_authorized_device_rows(self):
        output = """List of devices attached
serial-one device product:b2q model:SM_F711N
serial-two unauthorized
"""
        self.assertEqual(["serial-one"], parse_devices(output))

    def test_battery_requires_real_unplugged_state(self):
        battery = parse_battery(
            """Current Battery Service state:
  AC powered: false
  USB powered: false
  Wireless powered: false
  Dock powered: false
  level: 73
"""
        )
        self.assertEqual(73, battery["level"])
        self.assertFalse(any(battery["powered"].values()))
        with self.assertRaisesRegex(PreflightError, "UPDATES STOPPED"):
            parse_battery("UPDATES STOPPED\n  level: 73")
        with self.assertRaisesRegex(PreflightError, "non-boolean"):
            parse_battery(
                "AC powered: unknown\nUSB powered: false\nWireless powered: false\n"
                "Dock powered: false\nlevel: 80"
            )
        with self.assertRaisesRegex(PreflightError, "required power-source"):
            parse_battery(
                "AC powered: false\nUSB powered: false\nWireless powered: false\nlevel: 80"
            )

    def test_matching_stale_firebase_fixture_is_owned_for_cleanup(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            checked_in = root / "fixture.json"
            temporary = root / "app" / "google-services.json"
            checked_in.write_text('{"project_info": {}}', encoding="utf-8")
            temporary.parent.mkdir(parents=True)
            temporary.write_bytes(checked_in.read_bytes())
            with (
                mock.patch.object(device_preflight, "CI_FIREBASE_FIXTURE", checked_in),
                mock.patch.object(device_preflight, "BENCHMARK_FIREBASE_CONFIG", temporary),
            ):
                self.assertTrue(prepare_nonproduction_firebase_fixture())

    def test_failed_firebase_copy_does_not_publish_a_partial_destination(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            checked_in = root / "fixture.json"
            destination = root / "app" / "google-services.json"
            checked_in.write_text('{"project_info": {}}', encoding="utf-8")
            with (
                mock.patch.object(device_preflight, "CI_FIREBASE_FIXTURE", checked_in),
                mock.patch.object(device_preflight, "BENCHMARK_FIREBASE_CONFIG", destination),
                mock.patch.object(
                    device_preflight.shutil,
                    "copyfile",
                    side_effect=OSError("simulated short write"),
                ),
                self.assertRaises(PreflightError),
            ):
                prepare_nonproduction_firebase_fixture()
            self.assertFalse(destination.exists())
            self.assertEqual([], list(destination.parent.glob(".google-services.*.tmp")))

    def test_concurrent_preflight_cannot_claim_an_active_runner_lock(self):
        with tempfile.TemporaryDirectory() as temp, mock.patch.object(
            device_preflight, "RUN_LOCK", Path(temp) / "runner.lock"
        ):
            owner = acquire_run_lock()
            try:
                with self.assertRaisesRegex(PreflightError, "owns the runner lock"):
                    acquire_run_lock()
            finally:
                release_run_lock(owner)

    def test_device_lock_and_restore_journal_are_global_across_worktrees(self):
        self.assertNotIn(device_preflight.ROOT, device_preflight.RUN_LOCK.parents)
        self.assertEqual(device_preflight.RUN_LOCK.parent, device_preflight.JOURNAL.parent)

    def test_restore_journal_normalizes_non_object_and_non_utf8_failures(self):
        with tempfile.TemporaryDirectory() as temp:
            journal = Path(temp) / "restore.json"
            with mock.patch.object(device_preflight, "JOURNAL", journal):
                journal.write_text("[]\n", encoding="utf-8")
                with self.assertRaisesRegex(PreflightError, "JSON object"):
                    device_preflight.restore_from_journal(mock.Mock())
                journal.write_bytes(b"\xff")
                with self.assertRaisesRegex(PreflightError, "cannot read"):
                    device_preflight.restore_from_journal(mock.Mock())

    def test_restore_journal_unlink_failure_is_normalized(self):
        with tempfile.TemporaryDirectory() as temp:
            journal = Path(temp) / "restore.json"
            originals = {
                "system/min_refresh_rate": "null",
                "system/peak_refresh_rate": "null",
                "global/window_animation_scale": "1.0",
                "global/transition_animation_scale": "1.0",
                "global/animator_duration_scale": "1.0",
            }
            journal.write_text(
                device_preflight.json.dumps(
                    {"fingerprint": "fingerprint", "settings": originals}
                ),
                encoding="utf-8",
            )
            adb = mock.Mock()

            def shell(*args, **_kwargs):
                if args == ("getprop", "ro.build.fingerprint"):
                    return "fingerprint"
                if args[:2] == ("settings", "get"):
                    compound = f"{args[2]}/{args[3]}"
                    return originals[compound]
                return ""

            adb.shell.side_effect = shell
            with (
                mock.patch.object(device_preflight, "JOURNAL", journal),
                mock.patch.object(Path, "unlink", side_effect=OSError("blocked")),
                self.assertRaisesRegex(PreflightError, "cannot remove"),
            ):
                device_preflight.restore_from_journal(adb)

    def test_runner_lock_rejects_a_symlink_destination(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            target = root / "target"
            target.write_text("not a lock", encoding="utf-8")
            lock = root / "runner.lock"
            lock.symlink_to(target)
            with mock.patch.object(device_preflight, "RUN_LOCK", lock):
                with self.assertRaisesRegex(PreflightError, "secure global runner lock"):
                    acquire_run_lock()

    def test_private_temporary_directory_creates_and_secures_a_missing_parent(self):
        with tempfile.TemporaryDirectory() as temp:
            state = Path(temp) / "missing" / "device-state"
            with mock.patch.object(device_preflight, "DEVICE_STATE_DIR", state):
                context = device_preflight.private_temporary_directory("probe-")
                try:
                    self.assertEqual(state, Path(context.name).parent)
                    self.assertEqual(0o700, state.stat().st_mode & 0o777)
                finally:
                    context.cleanup()

    def test_private_temporary_directory_rejects_a_symlink_parent(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            target = root / "target"
            target.mkdir(mode=0o700)
            state = root / "device-state"
            state.symlink_to(target, target_is_directory=True)
            with (
                mock.patch.object(device_preflight, "DEVICE_STATE_DIR", state),
                self.assertRaisesRegex(PreflightError, "private user-owned directory"),
            ):
                device_preflight.private_temporary_directory("probe-")

    def test_private_temporary_directory_rejects_a_shared_parent(self):
        with tempfile.TemporaryDirectory() as temp:
            state = Path(temp) / "device-state"
            state.mkdir(mode=0o700)
            state.chmod(0o755)
            with (
                mock.patch.object(device_preflight, "DEVICE_STATE_DIR", state),
                self.assertRaisesRegex(PreflightError, "private user-owned directory"),
            ):
                device_preflight.private_temporary_directory("probe-")

    def test_display_size_preserves_override_information(self):
        self.assertEqual(("1080x2640", None), parse_wm_size("Physical size: 1080x2640"))
        self.assertEqual(
            ("1080x2640", "720x1760"),
            parse_wm_size("Physical size: 1080x2640\nOverride size: 720x1760"),
        )

    def test_active_refresh_uses_only_the_on_unfolded_main_display(self):
        dump = """  DisplayDeviceInfo{\"main\": uniqueId=\"local:1\", 1080 x 2640, modeId 1, renderFrameRate 120.00001, state ON, type INTERNAL}
  DisplayDeviceInfo{\"cover\": uniqueId=\"local:2\", 512 x 260, modeId 5, renderFrameRate 60.000004, state OFF, type INTERNAL}
"""
        self.assertAlmostEqual(120.00001, parse_active_main_refresh_rate(dump))
        active_60 = dump.replace("renderFrameRate 120.00001", "renderFrameRate 60.000004")
        self.assertAlmostEqual(60.000004, parse_active_main_refresh_rate(active_60))

        conflict = dump + dump.splitlines()[0].replace(
            "renderFrameRate 120.00001", "renderFrameRate 60.000004"
        )
        with self.assertRaisesRegex(PreflightError, "exactly one"):
            parse_active_main_refresh_rate(conflict)

    def test_surfaceflinger_refresh_uses_active_on_unfolded_block(self):
        dump = """Display 111
    connectionType=Internal
    powerMode=ON
    renderRate=120.00 Hz
    activeMode={id=0, resolution=1080x2640, vsyncRate=120.00 Hz}
Display 222
    connectionType=Internal
    powerMode=OFF
    renderRate=60.00 Hz
    activeMode={id=0, resolution=260x512, vsyncRate=60.00 Hz}
"""
        self.assertEqual(120.0, parse_surfaceflinger_active_main_refresh_rate(dump))
        with self.assertRaisesRegex(PreflightError, "conflicting"):
            parse_surfaceflinger_active_main_refresh_rate(
                dump.replace("renderRate=120.00 Hz", "renderRate=60.00 Hz", 1)
            )

    def test_refresh_stabilization_needs_two_consecutive_read_only_samples(self):
        with (
            mock.patch.object(
                device_preflight,
                "collect_active_refresh_rates",
                side_effect=[PreflightError("60Hz"), {}, {}],
            ) as collect,
            mock.patch.object(
                device_preflight.time,
                "monotonic",
                side_effect=[0.0, 0.1, 0.2, 0.3],
            ),
            mock.patch.object(device_preflight.time, "sleep"),
        ):
            device_preflight.wait_for_active_120hz(mock.Mock(), timeout_seconds=1.0)
        self.assertEqual(3, collect.call_count)

    def test_active_main_viewport_ignores_inactive_cover_and_duplicate_rows(self):
        dump = """Viewport INTERNAL: displayId=1, orientation=0, deviceSize=[512, 260], isActive=[0]
Viewport INTERNAL: displayId=0, orientation=0, deviceSize=[1080, 2640], isActive=[1]
Viewport INTERNAL: displayId=0, orientation=0, deviceSize=[1080, 2640], isActive=[1]
"""
        self.assertEqual((0, "1080x2640"), parse_active_internal_viewport(dump))

    def test_active_main_viewport_rejects_missing_or_conflicting_rows(self):
        with self.assertRaisesRegex(PreflightError, "cannot find active"):
            parse_active_internal_viewport(
                "Viewport INTERNAL: displayId=1, orientation=0, "
                "deviceSize=[512, 260], isActive=[1]"
            )
        with self.assertRaisesRegex(PreflightError, "inconsistent"):
            parse_active_internal_viewport(
                "Viewport INTERNAL: displayId=0, orientation=0, "
                "deviceSize=[1080, 2640], isActive=[1]\n"
                "Viewport INTERNAL: displayId=0, orientation=1, "
                "deviceSize=[2640, 1080], isActive=[1]"
            )
        with self.assertRaisesRegex(PreflightError, "fields are incomplete"):
            parse_active_internal_viewport(
                "Viewport INTERNAL: displayId=0, "
                "deviceSize=[1080, 2640], isActive=[1]"
            )

    def test_active_main_viewport_reports_landscape_for_preflight_rejection(self):
        dump = (
            "Viewport INTERNAL: displayId=0, orientation=1, "
            "deviceSize=[2640, 1080], isActive=[true]"
        )
        self.assertEqual((1, "2640x1080"), parse_active_internal_viewport(dump))

    def test_state_identifier_is_mapped_to_name_not_hardcoded(self):
        dump = """mCommittedState=3
DeviceState{identifier=0, name='CLOSED'}
DeviceState{identifier=3, name='OPENED'}
"""
        self.assertEqual("OPENED", parse_device_state_name(dump, "3"))

    def launcher_success(self) -> device_preflight.subprocess.CompletedProcess[str]:
        output = """INSTRUMENTATION_STATUS: class=com.jay.fxi.macrobenchmark.LauncherSmokeBenchmark
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: test=launcherSmoke
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: additionalTestOutputFile_trace=/sdcard/Android/media/com.jay.fxi.macrobenchmark/additional_test_output/launcher-smoke-01/trace.perfetto-trace
INSTRUMENTATION_STATUS_CODE: 2
INSTRUMENTATION_STATUS: time_to_initial_display_millis_median=254.23099
INSTRUMENTATION_STATUS_CODE: 2
INSTRUMENTATION_STATUS: class=com.jay.fxi.macrobenchmark.LauncherSmokeBenchmark
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: test=launcherSmoke
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=

Time: 10.692

OK (1 test)

INSTRUMENTATION_CODE: -1
"""
        return device_preflight.subprocess.CompletedProcess([], 0, output, "")

    def test_launcher_instrumentation_is_bound_to_selector_and_component(self):
        adb = mock.Mock()
        adb.run.return_value = self.launcher_success()
        result = run_launcher_smoke(adb, "launcher-smoke-01")
        self.assertEqual(0, result.returncode)
        arguments = adb.run.call_args.args
        self.assertIn(
            f"{device_preflight.LAUNCHER_CLASS}#{device_preflight.LAUNCHER_METHOD}",
            arguments,
        )
        self.assertIn(device_preflight.INSTRUMENTATION_COMPONENT, arguments)
        self.assertIn(
            f"{device_preflight.DEVICE_OUTPUT_ROOT}/launcher-smoke-01", arguments
        )
        self.assertEqual(660, adb.run.call_args.kwargs["timeout"])

    def test_launcher_result_is_exactly_one_green_sanitized_test(self):
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            captured = capture_launcher_result(
                Adb("adb", "private-serial"),
                evidence,
                self.launcher_success(),
                "launcher-smoke-01",
            )
            result = (evidence / "launcher-result.xml").read_text(encoding="utf-8")
        self.assertEqual(1, captured["tests"])
        self.assertEqual(0, captured["failures"])
        self.assertEqual(device_preflight.LAUNCHER_CLASS, captured["class"])
        self.assertIn('time="10.692"', result)
        self.assertNotIn("/sdcard/", result)

    def test_launcher_result_rejects_serial_failure_and_wrong_terminal_state(self):
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            serial = self.launcher_success()
            serial.stdout += "private-serial"
            with self.assertRaisesRegex(PreflightError, "ADB serial"):
                capture_launcher_result(
                    Adb("adb", "private-serial"),
                    evidence,
                    serial,
                    "launcher-smoke-01",
                )

        mutations = {
            "wrong final code": ("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
            "failed test": ("OK (1 test)", "FAILURES!!!"),
            "wrong class": (
                device_preflight.LAUNCHER_CLASS,
                "com.jay.fxi.macrobenchmark.OtherTest",
            ),
            "missing metric": (
                "INSTRUMENTATION_STATUS: time_to_initial_display_millis_median=254.23099\n",
                "",
            ),
            "duplicate pass": (
                "INSTRUMENTATION_STATUS_CODE: 0",
                "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_STATUS_CODE: 0",
            ),
        }
        for label, (old, new) in mutations.items():
            failed = self.launcher_success()
            failed.stdout = failed.stdout.replace(old, new)
            with (
                self.subTest(label=label),
                tempfile.TemporaryDirectory() as temp,
                self.assertRaisesRegex(PreflightError, "one green test"),
            ):
                capture_launcher_result(
                    Adb("adb", "private-serial"),
                    Path(temp),
                    failed,
                    "launcher-smoke-01",
                )

    def test_build_removes_stale_outputs_and_forces_benchmark_tasks(self):
        with tempfile.TemporaryDirectory(dir=device_preflight.ROOT) as temp:
            root = Path(temp)
            state = root / "missing" / "device-state"
            target = root / "target.apk"
            test = root / "test.apk"
            target.write_bytes(b"stale")
            test.write_bytes(b"stale")
            completed = mock.Mock(returncode=0)
            with (
                mock.patch.object(device_preflight, "TARGET_BENCHMARK_APK", target),
                mock.patch.object(device_preflight, "TEST_BENCHMARK_APK", test),
                mock.patch.object(device_preflight, "DEVICE_STATE_DIR", state),
                mock.patch.object(
                    device_preflight.subprocess, "run", return_value=completed
                ) as run,
            ):
                self.assertEqual(0, run_benchmark_build())
            self.assertFalse(target.exists())
            self.assertFalse(test.exists())
            self.assertTrue(state.is_dir())
        command = run.call_args.args[0]
        self.assertIn(":app:assembleBenchmark", command)
        self.assertIn(":macrobenchmark:assembleBenchmark", command)
        self.assertIn("--rerun-tasks", command)
        environment = run.call_args.kwargs["env"]
        self.assertNotEqual(
            str(Path.home() / ".gradle"), environment["GRADLE_USER_HOME"]
        )
        self.assertNotIn("GRADLE_OPTS", environment)
        self.assertFalse(
            any(key.startswith("ORG_GRADLE_PROJECT_") for key in environment)
        )

    def test_durable_source_snapshot_contains_exact_head_without_git_metadata(self):
        head = device_preflight.subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=device_preflight.ROOT,
            text=True,
            stdout=device_preflight.subprocess.PIPE,
            check=True,
        ).stdout.strip()
        expected = device_preflight.subprocess.run(
            ["git", "show", f"{head}:settings.gradle.kts"],
            cwd=device_preflight.ROOT,
            stdout=device_preflight.subprocess.PIPE,
            check=True,
        ).stdout
        with tempfile.TemporaryDirectory(dir=device_preflight.ROOT) as temp:
            state = Path(temp) / "missing" / "device-state"
            with mock.patch.object(device_preflight, "DEVICE_STATE_DIR", state):
                context, snapshot = materialize_commit_snapshot(head)
                try:
                    self.assertEqual(
                        expected, (snapshot / "settings.gradle.kts").read_bytes()
                    )
                    self.assertFalse((snapshot / ".git").exists())
                    self.assertTrue(state.is_dir())
                finally:
                    context.cleanup()

    def test_staged_artifact_is_byte_identical_and_read_only(self):
        with tempfile.TemporaryDirectory(dir=device_preflight.ROOT) as temp:
            root = Path(temp)
            source = root / "source.apk"
            destination = root / "staged.apk"
            source.write_bytes(b"sealed-apk")
            identity = copy_stable_artifact(source, destination)
            self.assertEqual(artifact_identity(source), identity)
            self.assertEqual(0o400, destination.stat().st_mode & 0o777)

    def test_install_uses_no_replace_or_test_only_override(self):
        adb = mock.Mock()
        adb.run.return_value = mock.Mock(returncode=0, stdout="Success\n")
        install_apk(adb, Path("/private/staged.apk"))
        arguments = adb.run.call_args.args
        self.assertEqual(("install", "--no-streaming", "/private/staged.apk"), arguments)
        self.assertNotIn("-r", arguments)
        self.assertNotIn("-t", arguments)

    def test_installed_apk_must_be_one_base_and_match_staged_bytes(self):
        with tempfile.TemporaryDirectory(dir=device_preflight.ROOT) as temp:
            root = Path(temp)
            source = root / "source.apk"
            pulled = root / "pulled.apk"
            source.write_bytes(b"sealed-apk")
            expected = artifact_identity(source)
            adb = mock.Mock()

            def run(*arguments, **_kwargs):
                if arguments[:3] == ("shell", "am", "get-current-user"):
                    return mock.Mock(returncode=0, stdout="0\n", stderr="")
                if arguments[:3] == ("shell", "pm", "path"):
                    return mock.Mock(
                        returncode=0,
                        stdout="package:/data/app/pkg/base.apk\n",
                        stderr="",
                    )
                if arguments[0] == "pull":
                    Path(arguments[2]).write_bytes(source.read_bytes())
                    return mock.Mock(returncode=0, stdout="", stderr="")
                self.fail(f"unexpected adb command: {arguments}")

            adb.run.side_effect = run
            adb.shell.side_effect = lambda *args, **kwargs: run(
                "shell", *args, **kwargs
            ).stdout.strip()
            actual = installed_apk_identity(adb, "pkg", expected, pulled)
            self.assertTrue(actual["matchesStagedArtifact"])
            self.assertEqual(expected["sha256"], actual["sha256"])

            split = mock.Mock()
            split.run.return_value = mock.Mock(
                returncode=0,
                stdout=(
                    "package:/data/app/pkg/base.apk\n"
                    "package:/data/app/pkg/split_config.apk\n"
                ),
                stderr="",
            )
            split.shell.return_value = "0"
            with self.assertRaisesRegex(PreflightError, "exactly one base"):
                installed_apk_identity(split, "pkg", expected, root / "split.apk")

    def test_artifact_identity_binds_relative_path_hash_and_size(self):
        with tempfile.TemporaryDirectory(dir=device_preflight.ROOT) as temp:
            artifact = Path(temp) / "artifact.bin"
            artifact.write_bytes(b"artifact")
            identity = artifact_identity(artifact)
        self.assertEqual(8, identity["size"])
        self.assertEqual(64, len(identity["sha256"]))
        self.assertFalse(identity["path"].startswith("/"))

    def test_adb_error_redacts_serial(self):
        completed = mock.Mock(
            returncode=1,
            stdout="",
            stderr="adb: device 'SERIAL-MUST-NOT-PERSIST' not found",
        )
        with mock.patch.object(device_preflight.subprocess, "run", return_value=completed):
            with self.assertRaises(PreflightError) as raised:
                Adb("adb", "SERIAL-MUST-NOT-PERSIST").shell("true")
        self.assertNotIn("SERIAL-MUST-NOT-PERSIST", str(raised.exception))
        self.assertIn("<redacted-device>", str(raised.exception))

    def test_restore_failure_changes_success_to_nonzero(self):
        fake_adb = mock.Mock()
        expected_settings = {
            f"{namespace}/{key}": target
            for namespace, key, target in device_preflight.SETTING_KEYS
        }
        with (
            tempfile.TemporaryDirectory() as temp,
            mock.patch.object(device_preflight, "connected_adb", return_value=fake_adb),
            mock.patch.object(
                device_preflight,
                "restore_from_journal",
                side_effect=[None, PreflightError("restore failed")],
            ),
            mock.patch.object(device_preflight, "assert_package_absent"),
            mock.patch.object(device_preflight, "collect_immutable_preflight", return_value={}),
            mock.patch.object(device_preflight, "prepare_settings", return_value={}),
            mock.patch.object(device_preflight, "collect_settings", return_value=expected_settings),
            mock.patch.object(device_preflight, "wait_for_active_120hz"),
        ):
            evidence = Path(temp) / "evidence"
            self.assertEqual(
                2,
                device_preflight.main(["--evidence-dir", str(evidence)]),
            )
            run = device_preflight.json.loads(
                (evidence / "run.json").read_text(encoding="utf-8")
            )
        self.assertEqual(2, run["exitCode"])
        self.assertEqual("fail", run["verdict"])
        self.assertEqual("restore failed", run["restoreError"])

    def test_existing_install_failure_does_not_force_stop_or_press_home(self):
        fake_adb = mock.Mock()
        with (
            tempfile.TemporaryDirectory() as temp,
            mock.patch.object(device_preflight, "connected_adb", return_value=fake_adb),
            mock.patch.object(device_preflight, "restore_from_journal", return_value=None),
            mock.patch.object(
                device_preflight,
                "assert_package_absent",
                side_effect=PreflightError("already installed"),
            ),
            mock.patch.object(device_preflight, "write_json_atomic"),
        ):
            self.assertEqual(
                2,
                device_preflight.main(["--evidence-dir", str(Path(temp) / "evidence")]),
            )
        fake_adb.shell.assert_not_called()

    def test_existing_evidence_directory_is_preserved_before_device_access(self):
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp) / "evidence"
            evidence.mkdir()
            sentinel = evidence / "run.json"
            sentinel.write_text("do-not-overwrite", encoding="utf-8")
            with (
                mock.patch.object(device_preflight, "connected_adb") as connected,
                mock.patch("builtins.print"),
            ):
                self.assertEqual(
                    2,
                    device_preflight.main(["--evidence-dir", str(evidence)]),
                )
            connected.assert_not_called()
            self.assertEqual("do-not-overwrite", sentinel.read_text(encoding="utf-8"))

    def test_clean_source_requirement_stops_before_device_access(self):
        dirty = {"commit": "a" * 40, "worktreeClean": False}
        with (
            tempfile.TemporaryDirectory() as temp,
            mock.patch.object(device_preflight, "RUN_LOCK", Path(temp) / "runner.lock"),
            mock.patch.object(device_preflight, "collect_source_state", return_value=dirty),
            mock.patch.object(device_preflight, "connected_adb") as connected,
            mock.patch("builtins.print"),
        ):
            self.assertEqual(
                2,
                device_preflight.main(
                    [
                        "--require-clean-source",
                        "--evidence-dir",
                        str(Path(temp) / "evidence"),
                    ]
                ),
            )
        connected.assert_not_called()

    def test_package_manager_failure_is_not_treated_as_package_absence(self):
        adb = Adb("adb", "private-serial")
        users = mock.Mock(
            returncode=0,
            stdout="Users:\n\tUserInfo{0:Owner:13} running\n",
            stderr="",
        )
        failure = mock.Mock(returncode=1, stdout="", stderr="device private-serial unavailable")
        with mock.patch.object(adb, "run", side_effect=[users, failure]):
            with self.assertRaisesRegex(PreflightError, "cannot verify") as raised:
                assert_package_absent(adb, "com.jay.fxi")
        self.assertNotIn("private-serial", str(raised.exception))

    def test_package_manager_contract_distinguishes_absent_and_installed(self):
        adb = Adb("adb", "private-serial")
        users = mock.Mock(
            returncode=0,
            stdout="Users:\n\tUserInfo{0:Owner:13} running\n",
            stderr="",
        )
        absent = mock.Mock(returncode=1, stdout="", stderr="")
        installed = mock.Mock(
            returncode=0,
            stdout="package:/data/app/com.jay.fxi/base.apk\n",
            stderr="",
        )
        with mock.patch.object(adb, "run", side_effect=[users, absent]):
            assert_package_absent(adb, "com.jay.fxi")
        with mock.patch.object(adb, "run", side_effect=[users, installed]):
            with self.assertRaisesRegex(PreflightError, "already installed"):
                assert_package_absent(adb, "com.jay.fxi")

    def test_package_presence_in_a_secondary_user_is_also_rejected(self):
        adb = Adb("adb", "private-serial")
        users = mock.Mock(
            returncode=0,
            stdout=(
                "Users:\n"
                "\tUserInfo{0:Owner:13} running\n"
                "\tUserInfo{150:Secure Folder:410} running\n"
            ),
            stderr="",
        )
        absent = mock.Mock(returncode=1, stdout="", stderr="")
        installed = mock.Mock(
            returncode=0,
            stdout="package:/data/app/com.jay.fxi/base.apk\n",
            stderr="",
        )
        with mock.patch.object(adb, "run", side_effect=[users, absent, installed]):
            with self.assertRaisesRegex(PreflightError, "already installed"):
                assert_package_absent(adb, "com.jay.fxi")


if __name__ == "__main__":
    unittest.main()
