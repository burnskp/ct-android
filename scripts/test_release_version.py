"""Regression tests for the public release version contract."""
import importlib.util
import pathlib
import subprocess
import sys
import unittest

SCRIPT = pathlib.Path(__file__).with_name("release-version.py")
SPEC = importlib.util.spec_from_file_location("release_version", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleaseVersionTest(unittest.TestCase):
    def test_versions_and_boundaries(self):
        for tag, name, code in (
            ("v0.0.1", "0.0.1", 1),
            ("v0.1.0", "0.1.0", 1000),
            ("v1.2.3", "1.2.3", 1002003),
            ("v2099.999.999", "2099.999.999", 2099999999),
            ("v2100.0.0", "2100.0.0", 2100000000),
        ):
            with self.subTest(tag=tag):
                self.assertEqual(MODULE.release_version(tag), (name, code))

    def test_invalid_tags(self):
        for tag in (
            "", "1.2.3", "v1.2", "v01.2.3", "v1.02.3", "v1.2.03",
            "v1.2.3-rc.1", "v1.2.3+build", "v1.2.3\n", "v1.2.٣",
            "v-1.2.3", "v0.0.0", "v1.1000.0", "v1.0.1000",
            "v2100.0.1", "v999999999999999999.0.0",
        ):
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError):
                    MODULE.release_version(tag)

    def test_codes_increase_across_rollovers(self):
        tags = ("v0.1.0", "v0.1.1", "v0.1.999", "v0.2.0", "v0.999.999", "v1.0.0")
        codes = [MODULE.release_version(tag)[1] for tag in tags]
        self.assertTrue(all(left < right for left, right in zip(codes, codes[1:])))

    def test_cli_formats(self):
        for format_name, expected in (
            ("name", "1.2.3\n"),
            ("github", "releaseVersionName=1.2.3\nreleaseVersionCode=1002003\n"),
            ("gradle", "-PreleaseVersionName=1.2.3\n-PreleaseVersionCode=1002003\n"),
        ):
            with self.subTest(format=format_name):
                result = subprocess.run(
                    [sys.executable, str(SCRIPT), "v1.2.3", "--format", format_name],
                    capture_output=True, text=True, check=True,
                )
                self.assertEqual(result.stdout, expected)

    def test_invalid_cli_does_not_emit_properties(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "v1.0.1000", "--format", "github"],
            capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("minor and patch", result.stderr)


if __name__ == "__main__":
    unittest.main()
