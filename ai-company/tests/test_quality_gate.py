"""Three model-free pilot fixtures for the deterministic quality gate.

This tests three independent tiny real Git commits, not fake LLM receipts.
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from quality_gate import verify_commit


class QualityGatePilots(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        self.git("init", "-q")
        (self.repo / "README.md").write_text("Pilot baseline\n", encoding="utf-8")
        self.git("add", "README.md")
        self.git("-c", "user.name=Smoke", "-c", "user.email=smoke@example.invalid",
                 "commit", "-qm", "baseline")
        self.base = self.git("rev-parse", "HEAD")

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.run(
            ["git", "-C", str(self.repo), *args], check=True,
            capture_output=True, text=True, encoding="utf-8"
        ).stdout.strip()

    def commit_pilot(self, source, tests):
        t = self.repo / "tests"
        t.mkdir(exist_ok=True)
        (t / "__init__.py").write_text("", encoding="utf-8")
        (self.repo / "module.py").write_text(source, encoding="utf-8")
        (t / "test_module.py").write_text(tests, encoding="utf-8")
        self.git("add", "module.py", "tests")
        self.git("-c", "user.name=Smoke", "-c", "user.email=smoke@example.invalid",
                 "commit", "-qm", "pilot implementation")
        return self.git("rev-parse", "HEAD")

    def test_three_distinct_small_pilots_have_real_verifiable_git_and_tests(self):
        pilots = [
            ("def slug(text):\n    return '-'.join(text.lower().split())\n",
             "from module import slug\nimport unittest\n"
             "class TestSlug(unittest.TestCase):\n"
             "    def test_normalize(self): self.assertEqual(slug('HELLO WORLD'), 'hello-world')\n"
             "    def test_empty(self): self.assertEqual(slug(''), '')\n"),
            ("def total(numbers):\n    return sum(numbers)\n",
             "from module import total\nimport unittest\n"
             "class TestTotal(unittest.TestCase):\n"
             "    def test_sum(self): self.assertEqual(total([2,3,4]), 9)\n"
             "    def test_empty(self): self.assertEqual(total([]), 0)\n"),
            ("def mirrored(text):\n    return text == text[::-1]\n",
             "from module import mirrored\nimport unittest\n"
             "class TestMirror(unittest.TestCase):\n"
             "    def test_true(self): self.assertTrue(mirrored('radar'))\n"
             "    def test_false(self): self.assertFalse(mirrored('hello'))\n"),
        ]
        for n, (source, suite) in enumerate(pilots):
            with self.subTest(pilot=n+1):
                if n > 0:
                    self.tearDown()
                    self.setUp()
                head = self.commit_pilot(source, suite)
                evidence = verify_commit(self.repo, self.base, head)
                self.assertEqual(evidence["sha"], head)
                self.assertEqual(evidence["tests_run"], 2)
                self.assertTrue(evidence["tests_passed"])
                self.assertEqual(evidence["git_diff_check"], "pass")
                self.assertIn("module.py", evidence["changed_files"])

    def test_untracked_python_bytecode_is_allowed_but_source_edits_are_not(self):
        from quality_gate import significant_git_status
        status = "?? __pycache__/module.cpython-311.pyc\n?? tests/__pycache__/test_module.pyc\n"
        self.assertEqual(significant_git_status(status), [])
        self.assertEqual(significant_git_status(status + "?? module2.py\n"), ["?? module2.py"])
        self.assertEqual(significant_git_status(" M tests/__pycache__/test_module.pyc\n"),
                         [" M tests/__pycache__/test_module.pyc"])
        head = self.commit_pilot(
            "def answer(): return 42\n",
            "from module import answer\nimport unittest\n"
            "class Tests(unittest.TestCase):\n"
            "    def test_expected(self): self.assertEqual(answer(), 42)\n")
        caches = self.repo / "tests" / "__pycache__"
        caches.mkdir()
        (caches / "test_module.cpython-311.pyc").write_bytes(b"generated")
        self.assertTrue(verify_commit(self.repo, self.base, head)["tests_passed"])

    def test_failure_does_not_approve_broken_tests(self):
        head = self.commit_pilot(
            "def answer(): return 0\n",
            "from module import answer\nimport unittest\n"
            "class Tests(unittest.TestCase):\n"
            "    def test_expected(self): self.assertEqual(answer(), 42)\n")
        with self.assertRaisesRegex(RuntimeError, "Quality gate failed"):
            verify_commit(self.repo, self.base, head)

    def test_zero_discovered_tests_is_not_a_pass(self):
        head = self.commit_pilot("def answer(): return 42\n", "# no tests\n")
        with self.assertRaisesRegex(RuntimeError, "nonempty"):
            verify_commit(self.repo, self.base, head)

    def test_sensitive_diff_requires_human_review(self):
        (self.repo / ".env").write_text("EXAMPLE_SECRET=foo\n", encoding="utf-8")
        self.git("add", ".env")
        self.git("-c", "user.name=Smoke", "-c", "user.email=smoke@example.invalid",
                 "commit", "-qm", "unsafe")
        with self.assertRaisesRegex(RuntimeError, "Sensitive file"):
            verify_commit(self.repo, self.base, self.git("rev-parse", "HEAD"))

    def test_uncommitted_changes_fail(self):
        head = self.commit_pilot("def answer(): return 1\n", "# no tests\n")
        (self.repo / "scratch.txt").write_text("dirty", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "clean"):
            verify_commit(self.repo, self.base, head)


if __name__ == "__main__":
    unittest.main()
