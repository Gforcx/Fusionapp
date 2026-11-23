"""Regression tests for the external ADB catalogue preparation barrier."""
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import check_binding as binding


def catalogue_dump(count, user=0):
    return ("ACCESSIBILITY MANAGER (dumpsys accessibility)\ncurrentUserId=0\n"
            f"User state[\n attributes:{{id={user}, installedServiceCount={count}, "
            "magnificationModes={0=1}}\n Bound services:{}\n Enabled services:{}]\n")


PM_SERVICES = "\n".join([f"com.example{i}/.Service" for i in range(5)]
                        + [binding.SHORT_COMPONENT])


class CatalogueTests(unittest.TestCase):
    def test_component_normalization(self):
        parsed = binding.parse_service_components(PM_SERVICES)
        self.assertTrue(parsed["formatRecognized"])
        self.assertTrue(parsed["targetPresent"])
        self.assertEqual(6, parsed["count"])
        self.assertIn(binding.COMPONENT, parsed["components"])
        self.assertEqual(binding.COMPONENT, binding.canonical_component(binding.COMPONENT))

    def test_unknown_or_truncated_pm_output_is_rejected(self):
        self.assertFalse(binding.parse_service_components("Permission denied\n" + PM_SERVICES)["formatRecognized"])
        self.assertFalse(binding.parse_service_components("7 services found:\n" + PM_SERVICES)["formatRecognized"])
        self.assertFalse(binding.parse_service_components("fr.roaddeck.launcher.evil/.SplitScreenService")["targetPresent"])
        self.assertFalse(binding.parse_service_components("No services found")["targetPresent"])

    def test_current_user_count_is_not_another_users_count(self):
        dump = catalogue_dump(99, 10) + catalogue_dump(6, 0)
        self.assertEqual(6, binding.parse_installed_service_count(dump, "0"))
        self.assertEqual(99, binding.parse_installed_service_count(dump, "10"))
        self.assertIsNone(binding.parse_installed_service_count(dump, "20"))
        self.assertIsNone(binding.parse_installed_service_count("installedServiceCount=6", "0"))

    def run_barrier(self, counts, duration=20):
        with tempfile.TemporaryDirectory() as output:
            check = binding.Check(SimpleNamespace(output=output, adb="adb", serial=None))
            clock = SimpleNamespace(value=0.0)
            calls = []
            sample = [0]

            def shell(args, name=None, timeout=8):
                calls.append(args)
                clock.value += min(0.05, timeout)
                if args[:3] == ["cmd", "package", "query-services"]:
                    return PM_SERVICES, 0
                count = counts[min(sample[0], len(counts) - 1)]
                sample[0] += 1
                return catalogue_dump(count), 0

            check.shell = shell
            check.started = 0.0
            with patch.object(binding.time, "monotonic", side_effect=lambda: clock.value), \
                    patch.object(binding.time, "sleep", side_effect=lambda seconds: setattr(clock, "value", clock.value + seconds)):
                result = check.await_catalogue(duration)
            self.assertTrue(all(args[0] != "settings" for args in calls))
            self.assertLessEqual(clock.value, duration + 0.001)
            self.assertIn(str(binding.ACCESSIBILITY_QUERY_FLAGS), calls[0])
            return result

    def test_stale_cache_must_catch_up_twice(self):
        result = self.run_barrier([5, 5, 6, 6])
        self.assertTrue(result["passed"])
        self.assertEqual([0, 0, 1, 2], [s["consecutiveMatchingSamples"] for s in result["samples"]])

    def test_intervening_mismatch_resets_confirmation(self):
        result = self.run_barrier([6, 5, 6, 6])
        self.assertTrue(result["passed"])
        self.assertEqual([1, 0, 1, 2], [s["consecutiveMatchingSamples"] for s in result["samples"]])

    def test_unready_catalogue_times_out_without_activation(self):
        result = self.run_barrier([5], 2)
        self.assertFalse(result["passed"])
        self.assertEqual(0, result["last"]["consecutiveMatchingSamples"])


if __name__ == "__main__":
    unittest.main()
