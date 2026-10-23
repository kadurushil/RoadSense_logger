"""
test_vision_dashboard_integration.py
------------------------------------
Unit tests for Web Dashboard 3D Vision multi-environment routing,
status detection, and UI element integration.
"""

import os
import sys
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.roadsense_web_server import (
    find_vision_python,
    get_python_for_script,
    get_vision_status,
)


class TestVisionDashboardIntegration(unittest.TestCase):
    def test_find_vision_python_detection(self):
        py_path = find_vision_python()
        self.assertIsNotNone(py_path)
        self.assertTrue(os.path.isfile(py_path), f"Resolved vision python '{py_path}' must exist")

    def test_get_python_for_script_routing(self):
        # Vision script should map to vision environment
        vision_py = find_vision_python()
        routed_vision = get_python_for_script("tools/vision/pipeline/perception_runner.py")
        self.assertEqual(os.path.normcase(routed_vision), os.path.normcase(vision_py))

        routed_eval = get_python_for_script(r"tools\vision\eval\evaluate_vision_vs_radar.py")
        self.assertEqual(os.path.normcase(routed_eval), os.path.normcase(vision_py))

        routed_baseline = get_python_for_script("tools/vision/generate_baseline_perception.py")
        self.assertEqual(os.path.normcase(routed_baseline), os.path.normcase(vision_py))

        # Standard non-vision script should route to sys.executable
        standard_routed = get_python_for_script("tools/convert_session_to_mcap.py")
        self.assertEqual(os.path.normcase(standard_routed), os.path.normcase(sys.executable))

        sync_routed = get_python_for_script("tools/sync_and_process_sessions.py")
        self.assertEqual(os.path.normcase(sync_routed), os.path.normcase(sys.executable))

    def test_get_vision_status_schema(self):
        status = get_vision_status()
        self.assertIn("available", status)
        self.assertIn("env_name", status)
        self.assertIn("python_path", status)
        self.assertIn("cuda_available", status)
        self.assertIsInstance(status["available"], bool)
        self.assertIsInstance(status["cuda_available"], bool)
        self.assertIsInstance(status["env_name"], str)

    def test_dashboard_html_contains_vision_elements(self):
        html_path = os.path.join(REPO_ROOT, "tools", "web_dashboard", "index.html")
        self.assertTrue(os.path.isfile(html_path))
        with open(html_path, "r", encoding="utf-8") as f:
            html = f.read()

        # Check Tab & Pills
        self.assertIn('id="visionStatusPill"', html)
        self.assertIn("switchTab('visionTab')", html)
        self.assertIn('id="visionTab"', html)

        # Check 4 Action cards
        self.assertIn('id="visionSessionSelect"', html)
        self.assertIn('runVisionPipeline()', html)
        self.assertIn('id="visionOptMcap"', html)
        self.assertIn('id="visionOptOpticalSizing"', html)

        self.assertIn('id="visionEvalSessionSelect"', html)
        self.assertIn('runVisionBenchmark()', html)

        self.assertIn('id="visionInjectSessionSelect"', html)
        self.assertIn('runVisionMcapInjection()', html)

        self.assertIn('id="visionBaselineSessionSelect"', html)
        self.assertIn('runBaselinePerception()', html)

        # Verify default settings
        self.assertIn('id="visionOptMcap" checked', html)
        self.assertIn('id="visionOptOpticalSizing" checked', html)
        self.assertIn('name="baselineMode" value="yolo" checked', html)


if __name__ == "__main__":
    unittest.main()
