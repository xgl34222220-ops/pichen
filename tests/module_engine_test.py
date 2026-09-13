"""Non-root regression tests. All simulated device paths stay in a temporary root."""
import json
import hashlib
import os
from pathlib import Path
import subprocess
import shlex
import shutil
import tempfile
import unittest


MODULE = Path(__file__).resolve().parents[1] / "module"


class ModuleEngineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bichen-module-", dir="/tmp")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {**os.environ, "BICHEN_TEST_ROOT": str(self.root)}
        self.run_cli("install")
        self.run_cli("apply")

    def run_cli(self, *args, ok=True, env=None):
        result = subprocess.run(
            [*shlex.split(os.environ.get("BICHEN_TEST_SHELL", "sh")), str(MODULE / "bin/bichen"), *args],
            env=self.env if env is None else env,
            text=True, capture_output=True, timeout=20,
        )
        data = json.loads(result.stdout)
        self.assertEqual(data["ok"], ok, (args, result.stdout, result.stderr))
        self.assertEqual(result.returncode == 0, ok, result.stderr)
        return data

    def current(self):
        return (self.root / "state/current").read_text().strip()

    def snapshot(self):
        return self.root / "state/generations" / self.current()

    def file(self, name, content):
        path = self.root / name
        path.write_text(content)
        return str(path)

    def test_export_and_sizes_match_committed_rules(self):
        status = self.run_cli("status")
        exported = self.run_cli("export-domains")
        self.assertEqual(status["version"], "0.3.0-beta.1")
        self.assertEqual(status["engine"], "hosts")
        self.assertEqual(len(exported["domains"]), status["ruleCount"])
        self.assertEqual(exported["configRevision"], status["configRevision"])
        self.assertEqual(status["hostsBytes"], (self.snapshot() / "hosts").stat().st_size)
        self.assertGreaterEqual(status["storageBytes"], status["hostsBytes"])
        self.assertTrue(status["mounted"])
        self.assertIn("export-domains", status["capabilities"])

    def test_reapply_preserves_snapshot_and_timestamp(self):
        before = self.run_cli("status")
        self.run_cli("apply")
        after = self.run_cli("status")
        self.assertEqual(before["configRevision"], after["configRevision"])
        self.assertEqual(before["updatedAt"], after["updatedAt"])

    def test_bad_batch_never_commits_any_source(self):
        before = self.current()
        valid = self.file("valid.txt", "0.0.0.0 fresh.ads.example\n")
        invalid = self.file("invalid.txt", "<html>network failure</html>\n")
        self.run_cli("import-batch", "adaway", valid, "china", invalid, ok=False)
        self.assertEqual(before, self.current())
        self.assertTrue(self.run_cli("status")["mounted"])
        self.assertFalse((self.root / "state/pending").exists())
        self.assertEqual(len(list((self.root / "state/generations").iterdir())), 1)

    def test_mount_failure_restores_previous_configuration(self):
        before = self.current()
        self.run_cli("add-block", "new.ads.example", ok=False,
                     env={**self.env, "BICHEN_TEST_FAIL_MOUNT": "1"})
        self.assertEqual(before, self.current())
        self.assertTrue(self.run_cli("status")["mounted"])
        self.assertFalse(self.run_cli("check-domain", "new.ads.example")["blocked"])

    def test_pause_resume_preserve_rule_backup_and_update_time(self):
        self.run_cli("add-block", "new.ads.example")
        updated = self.run_cli("status")["updatedAt"]
        self.run_cli("pause")
        self.run_cli("boot")
        paused = self.run_cli("status")
        self.assertFalse(paused["mounted"])
        self.assertFalse(paused["enabled"])
        self.assertEqual(paused["updatedAt"], updated)
        self.run_cli("enable")
        self.run_cli("rollback")
        self.assertFalse(self.run_cli("check-domain", "new.ads.example")["blocked"])
        self.assertTrue(self.run_cli("status")["mounted"])

    def test_lists_are_atomic_allow_wins_and_empty_clears(self):
        allow = self.file("allow.txt", "ads.example\n")
        block = self.file("block.txt", "ads.example\nextra.ads.example\n")
        self.run_cli("import-user-lists", allow, block)
        check = self.run_cli("check-domain", "ads.example")
        self.assertTrue(check["allowed"])
        self.assertTrue(check["customBlocked"])
        self.assertFalse(check["blocked"])
        config = self.run_cli("export-config")
        self.assertEqual(config["allow"], ["ads.example"])
        self.assertEqual(config["block"], ["ads.example", "extra.ads.example"])
        old = self.current()
        invalid = self.file("bad.txt", "https://invalid.example/path\n")
        self.run_cli("import-user-lists", allow, invalid, ok=False)
        self.assertEqual(self.current(), old)
        empty = self.file("empty.txt", "")
        self.run_cli("import-user-lists", empty, empty)
        config = self.run_cli("export-config")
        self.assertEqual(config["allow"], [])
        self.assertEqual(config["block"], [])

    def test_rollback_while_paused_never_reenables_hosts(self):
        # VPN mode pauses global hosts. Its rules rollback must retain that pause
        # even though the historical rules generation was captured while enabled.
        self.run_cli("add-block", "vpn-regression.ads.example")
        self.assertTrue(self.run_cli("status")["mounted"])
        self.run_cli("pause")
        self.run_cli("rollback")
        after = self.run_cli("status")
        self.assertFalse(after["enabled"])
        self.assertFalse(after["mounted"])
        self.assertFalse(self.run_cli("check-domain", "vpn-regression.ads.example")["blocked"])
        self.run_cli("boot")
        self.assertFalse(self.run_cli("status")["mounted"])

    def test_profile_import_preserves_lists_pause_and_one_step_rollback(self):
        # The App's preset operation uses the same atomic import-settings entry.
        self.run_cli("add-allow", "business.example")
        self.run_cli("add-block", "custom.ads.example")
        self.run_cli("pause")
        before = self.run_cli("export-config")
        generation = self.current()
        allow = self.file("profile-allow.txt", "business.example\n")
        block = self.file("profile-block.txt", "custom.ads.example\n")
        self.run_cli("import-settings", allow, block, "1", "1", "0", "1")
        after = self.run_cli("export-config")
        self.assertEqual(before["allow"], after["allow"])
        self.assertEqual(before["block"], after["block"])
        self.assertEqual((self.snapshot() / "parent").read_text().strip(), generation)
        self.assertFalse(self.run_cli("status")["enabled"])
        self.assertFalse(self.run_cli("status")["mounted"])
        self.run_cli("rollback")
        restored = self.run_cli("export-config")
        self.assertEqual(before["sources"], restored["sources"])
        self.assertEqual(before["allow"], restored["allow"])
        self.assertEqual(before["block"], restored["block"])
        self.assertFalse(self.run_cli("status")["mounted"])

    def test_shell_input_is_data_and_does_not_execute(self):
        marker = self.root / "injected"
        payload = "$(touch " + str(marker) + ").example"
        before = self.current()
        self.run_cli("add-block", payload, ok=False)
        self.assertFalse(marker.exists())
        self.assertEqual(before, self.current())

    def test_truncated_manifest_cannot_bypass_hosts_verification(self):
        manifest = self.snapshot() / "manifest.sha256"
        lines = manifest.read_text().splitlines()
        manifest.write_text("\n".join(line for line in lines if not line.endswith("  hosts")) + "\n")
        self.run_cli("apply", ok=False)
        self.run_cli("export-domains", ok=False)

    def test_foreign_mount_is_never_unmounted(self):
        (self.root / "foreign-mount").touch()
        before = self.current()
        self.run_cli("pause", ok=False)
        self.run_cli("uninstall", ok=False)
        self.assertEqual(before, self.current())
        self.assertTrue((self.root / "mounted").exists())
        self.assertTrue((self.root / "foreign-mount").exists())

    def test_uninstall_restores_original_hosts_and_preserves_user_config(self):
        self.run_cli("add-allow", "business.example")
        before = self.current()
        self.run_cli("uninstall")
        self.assertEqual((self.root / "base.hosts").read_bytes(),
                         (self.root / "system/etc/hosts").read_bytes())
        self.assertEqual(before, self.current())
        self.assertEqual(self.run_cli("export-config")["allow"], ["business.example"])

    def make_legacy_snapshot(self, directory):
        """Recreate the v0.2 schema: exactly its 14 sealed files, no fourth source."""
        (directory / "schema").write_text("1\n")
        for name in ("source-hagezi.domains", "source-hagezi.enabled"):
            (directory / name).unlink(missing_ok=True)
        files = "hosts domains allow block enabled schema source-adaway.domains source-adaway.enabled source-china.domains source-china.enabled source-tracking.domains source-tracking.enabled updatedAt parent".split()
        (directory / "manifest.sha256").write_text("".join(
            hashlib.sha256((directory / name).read_bytes()).hexdigest() + "  " + name + "\n"
            for name in files))

    def test_upgrade_preserves_legacy_settings_pause_timestamp_and_backup(self):
        self.run_cli("add-allow", "business.example")
        self.run_cli("add-block", "new.ads.example")
        self.run_cli("pause")
        before = self.run_cli("status")
        old = self.snapshot()
        parent = self.root / "state/generations" / (old / "parent").read_text().strip()
        parent_bytes = (parent / "domains").read_bytes()
        for directory in (old, parent):
            self.make_legacy_snapshot(directory)
        old_hashes = (old / "manifest.sha256").read_bytes()
        self.run_cli("install")
        after = self.run_cli("status")
        self.assertEqual(before["ruleCount"], after["ruleCount"])
        self.assertEqual(before["updatedAt"], after["updatedAt"])
        self.assertEqual(self.run_cli("export-config")["allow"], ["business.example"])
        self.assertFalse(after["enabled"])
        self.assertFalse(after["mounted"])
        self.assertEqual((self.snapshot() / "parent").read_text().strip(), parent.name)
        self.assertEqual((old / "manifest.sha256").read_bytes(), old_hashes)
        self.assertEqual((parent / "domains").read_bytes(), parent_bytes)
        hagezi = next(s for s in after["sources"] if s["id"] == "hagezi")
        self.assertFalse(hagezi["enabled"])
        self.assertEqual(hagezi["count"], 35280)
        self.run_cli("boot")
        self.assertFalse(self.run_cli("status")["mounted"])
        self.run_cli("rollback")
        self.assertFalse(self.run_cli("check-domain", "new.ads.example")["blocked"])
        self.assertEqual(self.run_cli("export-config")["allow"], ["business.example"])
        self.assertFalse(self.run_cli("status")["enabled"])
        self.assertEqual((self.snapshot() / "schema").read_text(), "2\n")

    def test_fourth_source_is_opt_in_and_reversible(self):
        before = self.run_cli("status")
        self.assertEqual(before["ruleCount"], 7081)
        self.run_cli("set-source", "hagezi", "1")
        self.assertEqual(self.run_cli("status")["ruleCount"], 40664)
        self.run_cli("rollback")
        self.assertEqual(self.run_cli("status")["ruleCount"], before["ruleCount"])

    def test_import_settings_is_atomic_and_does_not_resume_paused_hosts(self):
        allow = self.file("allow.txt", "business.example\n")
        block = self.file("block.txt", "custom.ads.example\n")
        invalid = self.file("invalid.txt", "<html>no rules</html>\n")
        old = self.current()
        self.run_cli("import-settings", allow, invalid, "0", "1", "1", "1", ok=False)
        self.assertEqual(old, self.current())
        self.run_cli("import-settings", allow, block, "0", "invalid", "1", "1", ok=False)
        self.assertEqual(old, self.current())
        self.run_cli("import-settings", allow, block, "0", "1", "1", "1", ok=False,
                     env={**self.env, "BICHEN_TEST_FAIL_MOUNT": "1"})
        self.assertEqual(old, self.current())
        self.run_cli("pause")
        self.run_cli("import-settings", allow, block, "0", "1", "1", "1")
        config = self.run_cli("export-config")
        self.assertEqual(config["allow"], ["business.example"])
        self.assertEqual(config["block"], ["custom.ads.example"])
        self.assertEqual({s["id"]: s["enabled"] for s in config["sources"]},
                         {"adaway": False, "china": True, "tracking": True, "hagezi": True})
        self.assertFalse(self.run_cli("status")["mounted"])
        # An older backup importer must not silently disable a new optional source.
        self.run_cli("import-settings", allow, block, "1", "1", "0")
        self.assertEqual((self.snapshot() / "source-hagezi.enabled").read_text(), "1\n")

    def test_health_distinguishes_normal_pause_from_damage_and_conflict(self):
        self.assertTrue(self.run_cli("health")["healthy"])
        self.run_cli("pause")
        self.assertTrue(self.run_cli("health")["healthy"])
        (self.root / "foreign-mount").touch()
        health = self.run_cli("health")
        self.assertFalse(health["healthy"])
        self.assertFalse(next(c for c in health["checks"] if c["id"] == "conflicts")["passed"])
        (self.root / "foreign-mount").unlink()
        (self.snapshot() / "domains").write_text("tampered.example\n")
        health = self.run_cli("health")
        self.assertFalse(next(c for c in health["checks"] if c["id"] == "snapshot")["passed"])
        (self.snapshot() / "manifest.sha256").unlink()
        result = subprocess.run(
            [*shlex.split(os.environ.get("BICHEN_TEST_SHELL", "sh")), str(MODULE / "bin/bichen"), "health"],
            env=self.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)
        self.assertFalse(json.loads(result.stdout)["healthy"])

    def test_install_never_overwrites_invalid_existing_pointer(self):
        pointer = self.root / "state/current"
        pointer.write_text("g.missing\n")
        self.run_cli("install", ok=False)
        self.assertEqual(pointer.read_text(), "g.missing\n")

    def test_preflight_rejects_incomplete_package_before_changing_user_state(self):
        self.run_cli("preflight")
        package = self.root / "module-copy"
        shutil.copytree(MODULE, package)
        original = self.current()
        (package / "rules/hagezi.txt").write_text("truncated.example\n")
        result = subprocess.run(
            [*shlex.split(os.environ.get("BICHEN_TEST_SHELL", "sh")), str(package / "bin/bichen"), "preflight"],
            env=self.env, text=True, capture_output=True, timeout=20)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(json.loads(result.stdout)["ok"])
        self.assertEqual(original, self.current())
        (package / "rules/hagezi.txt").write_bytes((MODULE / "rules/hagezi.txt").read_bytes())
        (package / "rules/checksums.sha256").write_text("")
        result = subprocess.run(
            [*shlex.split(os.environ.get("BICHEN_TEST_SHELL", "sh")), str(package / "bin/bichen"), "install"],
            env=self.env, text=True, capture_output=True, timeout=20)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(json.loads(result.stdout)["ok"])
        self.assertEqual(original, self.current())


if __name__ == "__main__":
    unittest.main(verbosity=2)
