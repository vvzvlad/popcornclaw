#!/usr/bin/env python3
"""Runs omarchy-link's own PC test-suite against the launcher's real LinkServer (JVM harness) instead of
its Python mock TV. Read-only use of /workspace/omarchy-link: mock_tv is swapped in sys.modules.

    ./gradlew compileDebugUnitTestJavaWithJavac && python3 tools/link-e2e/run_pc_suite.py [-k pattern]
"""
import os
import sys
import types
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
LINK = Path(os.environ.get("OMARCHY_LINK", "/workspace/omarchy-link"))
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(LINK / "tests"))
sys.path.insert(0, str(LINK / "lib"))
import javatv  # noqa: E402

shim = types.ModuleType("mock_tv")
shim.MockTV = javatv.JavaTV
sys.modules["mock_tv"] = shim
os.chdir(LINK / "tests")
names = sys.argv[1:] or ["test_e2e", "test_assistant.AssistantOverLink", "test_ai", "test_mirror.Mirror.test_tv_gets_the_same_numbers",
                          "test_mirror.Mirror.test_tv_switches_sources",
                          # Send to TV over the Link channel (PROTOCOL.md Cast) against the real LinkServer
                          "test_cast.CastE2E.test_cast_falls_back_to_link", "test_cast.CastE2E.test_link_kinds_and_upload",
                          "test_cast.CastE2E.test_link_serves_big_file_only_with_consent", "test_cast.CastE2E.test_screen_experimental",
                          "test_cast.CastE2E.test_old_launcher_and_clipboard",
                          # Agent monitor (PROTOCOL.md §6 Agents) + prompt push
                          "test_agents.OverLink"]
suite = unittest.defaultTestLoader.loadTestsFromNames(names)
r = unittest.TextTestRunner(verbosity=2).run(suite)
sys.exit(0 if r.wasSuccessful() else 1)
