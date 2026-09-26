"""DEV-ENV-002B ta-status / ta-handoff / ta-finalize acceptance tests. Temporary repositories and a bare origin only.

Run: <py> -m pytest -p no:cacheprovider .claude/scripts/tests   (also runs under python -m unittest discover)
"""
import contextlib
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import codex_delegate as cd  # noqa: E402
import ta_tools as ta  # noqa: E402

FROZEN = ("# test frozen list\n.claude/settings.json\n.claude/frozen-paths.txt\n.claude/hooks/**\n"
          ".claude/scripts/**\ncore/F.kt\nfrozendir/**\n")
GITIGNORE = "build/\n*.log\n"
SECRET = "S3CR3T-SOURCE-CONTENT-DO-NOT-DUMP"
TEST_SPEC = {"id": "app-unit", "argv": ["gradlew.bat", ":app:testDebugUnitTest"], "cwd": ".", "timeout_seconds": 60,
             "env": {}}
FORBIDDEN_GIT = {"reset", "restore", "clean", "stash", "checkout", "switch", "rebase", "merge", "cherry-pick", "revert",
                 "update-ref", "branch", "tag", "rm", "mv"}
IDENTITY_KEYS = ["schema_version", "task_id", "repository_root_canonical", "branch", "baseline_head", "current_head",
                 "subject_fingerprint", "run_id", "run_start_utc", "run_end_utc", "mode", "verdict", "exit_code"]


def sh(cwd, *args, check=True):
    return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", *args], cwd=cwd,
                          check=check, capture_output=True, text=True).stdout.strip()


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))


def _rm_error(func, path, _exc):
    os.chmod(path, stat.S_IWRITE)
    func(path)


class Base(unittest.TestCase):
    """Temporary repository `repo` tracking a bare `origin`; artifacts and evidence roots redirected to the temp dir."""

    def setUp(self):
        self.tmp = Path(cd.long_path(tempfile.mkdtemp(prefix="ta-tools-")))
        self.addCleanup(shutil.rmtree, self.tmp, onerror=_rm_error)
        self.origin, self.repo = self.tmp / "origin.git", self.tmp / "repo"
        sh(self.tmp, "init", "-q", "--bare", "-b", "main", str(self.origin))
        self.repo.mkdir()
        sh(self.repo, "init", "-q", "-b", "main")
        for key, value in (("core.autocrlf", "false"), ("user.name", "t"), ("user.email", "t@example.invalid"),
                           ("commit.gpgsign", "false")):
            sh(self.repo, "config", key, value)
        files = {"docs/terrain-ahead/AUDIT.md": "audit\n", "docs/terrain-ahead/DESIGN.md": "design\n",
                 ".claude/frozen-paths.txt": FROZEN, ".gitignore": GITIGNORE, "src/a.txt": "a\n",
                 "other/b.txt": "b\n", "core/F.kt": "frozen\n"}
        for rel, text in files.items():
            write(self.repo / rel, text)
        sh(self.repo, "add", "-A")
        sh(self.repo, "commit", "-q", "-m", "init")
        sh(self.repo, "remote", "add", "origin", str(self.origin))
        sh(self.repo, "push", "-q", "-u", "origin", "main")
        self.artifacts, self.evidence = self.tmp / "artifacts", self.tmp / "evidence"
        for target, value in ((cd, "artifact_root"), (ta, "evidence_root")):
            patcher = mock.patch.object(target, value, return_value=self.artifacts if value == "artifact_root" else self.evidence)
            patcher.start()
            self.addCleanup(patcher.stop)
        old_cwd = os.getcwd()
        os.chdir(self.repo)
        self.addCleanup(os.chdir, old_cwd)
        self.answers, self.prompts = [], []
        for name, value in (("IS_INTERACTIVE", lambda: True), ("READ_ANSWER", self._answer)):
            patcher = mock.patch.object(ta, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        ta.GIT_CALLS.clear()
        cd.GIT_CALLS.clear()
        self.counter = 0

    # ---------------------------------------------------------------- helpers
    def _answer(self, prompt):
        self.prompts.append(prompt)
        answer = self.answers.pop(0)
        return answer() if callable(answer) else answer

    def tool(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = ta.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def status_json(self, *extra):
        code, out, _ = self.tool("status", *extra, "-Json")
        return code, json.loads(out)

    def state(self):
        return ta.collect_state(self.repo)

    def head(self):
        return sh(self.repo, "rev-parse", "HEAD")

    def make_run(self, task="TA-900", mode="implement", verdict=None, exit_code=0, fingerprint="current",
                 head=None, allowed=("src/**",), tests=(), items=None, subject=None, response=None, run_id=None,
                 end=None, root=None, branch="main", schema=1, design=None, mutate=None):
        self.counter += 1
        state = self.state()
        kind = "review" if mode == "review" else "implement"
        run_id = run_id or f"20260101T{self.counter:06d}Z-{task}-{kind}-{self.counter:08x}"
        run_dir = self.artifacts / run_id
        run_dir.mkdir(parents=True)
        contract = {"schema_version": 1, "task_id": task, "mode": mode, "parent_run_id": None,
                    "subject_run_id": subject if mode == "review" else None, "model": "stub-model",
                    "reasoning_effort": "low", "allowed_paths": list(allowed) if mode == "implement" else [],
                    "frozen_paths_source": ".claude/frozen-paths.txt",
                    "expected_docs": {"audit_git_blob": None, "design_git_blob": None},
                    "required_tests": list(tests) if mode == "implement" else [], "timeout_seconds": 120}
        write(run_dir / "contract.json", json.dumps(contract))
        write(run_dir / "baseline.json", json.dumps({"docs": {"design": {"git_blob": design or state["design_blob"]}}}))
        fp = state["fingerprint"] if fingerprint == "current" else fingerprint
        end = end or f"2026-01-01T00:00:{self.counter:02d}.000000Z"
        identity = {"schema_version": 1, "task_id": task,
                    "repository_root_canonical": root or state["root"], "branch": branch,
                    "baseline_head": head or state["head"], "current_head": state["head"], "subject_fingerprint": fp,
                    "run_id": run_id, "run_start_utc": "2026-01-01T00:00:00.000000Z", "run_end_utc": end,
                    "mode": mode, "verdict": verdict or ("CLEAN" if mode == "review" else "COMPLETED"),
                    "exit_code": exit_code}
        if items is None:
            items = [{"id": t["id"], "command": cd.normalized_test_command(t), "status": "PASS", "exit_code": 0,
                      "subject_fingerprint": fp} for t in tests] if mode == "implement" else []
        report = {"schema_version": schema, "identity": identity, "run_id": run_id, "task_id": task, "mode": mode,
                  "exit_code": exit_code, "status": {"RESPONSE": "PRESENT"},
                  "response": response or {"summary": "tighten the widget", "findings": [], "open_decisions": []},
                  "test_evidence": items,
                  "artifact_hashes": {n: cd.sha256_file(run_dir / n) for n in ("contract.json", "baseline.json")}}
        if mutate:
            mutate(report)
        write(run_dir / "report.json", json.dumps(report))
        return run_id

    def ready(self, tests=(), items=None, content="changed\n"):
        write(self.repo / "src/a.txt", content)
        implement = self.make_run(tests=tests, items=items)
        review = self.make_run(mode="review", subject=implement)
        return implement, review

    def git_snapshot(self):
        return {p.relative_to(self.repo).as_posix(): (p.stat().st_mtime_ns, cd.sha256_file(p))
                for p in sorted((self.repo / ".git").rglob("*")) if p.is_file()}

    def assert_no_forbidden_git(self):
        for call in ta.GIT_CALLS + cd.GIT_CALLS:
            self.assertNotIn(call[0], FORBIDDEN_GIT, call)
            self.assertFalse(call[0] == "add" and ("." in call or "-A" in call or "--all" in call), call)
            self.assertFalse(call[0] == "push" and any(a.startswith("-f") or a.startswith("--force") or a.startswith("+")
                                                       for a in call[1:]), call)

    def other_clone_push(self, name="remote.txt"):
        clone = self.tmp / f"clone-{name}"
        sh(self.tmp, "clone", "-q", str(self.origin), str(clone))
        write(clone / name, "remote\n")
        sh(clone, "add", name)
        sh(clone, "commit", "-q", "-m", "remote change")
        sh(clone, "push", "-q", "origin", "main")


# ================================================================================================ ta-status

class StatusTests(Base):
    def test_clean_synced(self):
        code, data = self.status_json()
        self.assertEqual(code, 0)
        self.assertEqual((data["sync"], data["tree"], data["design"]["state"], data["frozen"]["state"]),
                         ("PASS", "CLEAN", "PASS", "PASS"))
        self.assertEqual(data["head"], data["origin"]["sha"])
        self.assertTrue(data["next_gate"].startswith("NONE"))

    def test_dirty_without_evidence_is_unknown(self):
        write(self.repo / "src/a.txt", "dirty\n")
        code, data = self.status_json("-Task", "TA-900")
        self.assertEqual(code, ta.EXIT_EVIDENCE)
        self.assertEqual((data["tree"], data["review"]["state"], data["scope"]["state"], data["evidence"]),
                         ("DIRTY", "UNKNOWN", "UNKNOWN", "UNKNOWN"))
        self.assertEqual(data["authority"]["reason"], "NO_TASK_CONTRACT_FOR_HEAD")

    def test_ahead(self):
        write(self.repo / "src/a.txt", "local\n")
        sh(self.repo, "commit", "-qam", "local")
        code, data = self.status_json()
        self.assertEqual((data["sync"], data["ahead"], data["behind"]), ("AHEAD", 1, 0))
        self.assertEqual(code, 0)
        self.assertTrue(data["next_gate"].startswith("PUSH"))

    def test_behind(self):
        self.other_clone_push()
        sh(self.repo, "fetch", "-q", "origin")
        code, data = self.status_json()
        self.assertEqual((data["sync"], data["ahead"], data["behind"], code), ("BEHIND", 0, 1, ta.EXIT_UNHEALTHY))

    def test_diverged(self):
        self.other_clone_push()
        sh(self.repo, "fetch", "-q", "origin")
        write(self.repo / "src/a.txt", "local\n")
        sh(self.repo, "commit", "-qam", "local")
        code, data = self.status_json()
        self.assertEqual((data["sync"], data["ahead"], data["behind"], code), ("DIVERGED", 1, 1, ta.EXIT_UNHEALTHY))

    def test_newest_exact_task_match(self):
        write(self.repo / "src/a.txt", "changed\n")
        implement = self.make_run()
        self.make_run(mode="review", subject=implement, verdict="CLEAN")
        newest = self.make_run(mode="review", subject=implement, verdict="FINDINGS", exit_code=7)
        self.make_run(task="TA-901", mode="review", verdict="CLEAN")                 # other task
        self.make_run(mode="review", verdict="CLEAN", root=str(self.tmp / "elsewhere"))  # other repository
        self.make_run(mode="review", verdict="CLEAN", branch="feature")              # other branch
        code, data = self.status_json("-Task", "TA-900")
        self.assertEqual((data["review"]["run_id"], data["review"]["state"]), (newest, "FINDINGS"))
        self.assertEqual(code, ta.EXIT_UNHEALTHY)

    def test_tie_breaks_on_run_id(self):
        write(self.repo / "src/a.txt", "changed\n")
        implement = self.make_run()
        end = "2026-02-02T00:00:00.000000Z"
        low = self.make_run(mode="review", subject=implement, verdict="FINDINGS", exit_code=7, end=end,
                            run_id="20260101T000000Z-TA-900-review-aaaaaaaa")
        high = self.make_run(mode="review", subject=implement, verdict="CLEAN", end=end,
                             run_id="20260101T000000Z-TA-900-review-bbbbbbbb")
        artifacts, _ = ta.scan_artifacts()
        for order in (artifacts, list(reversed(artifacts))):
            self.assertEqual(ta.newest([a for a in order if a["identity"]["mode"] == "review"])["identity"]["run_id"], high)
        self.assertNotEqual(low, high)

    def test_malformed_and_legacy_artifacts_ignored(self):
        write(self.repo / "src/a.txt", "changed\n")
        good = self.make_run()
        self.make_run(mode="review", subject=good)
        bad = self.artifacts / "20260101T999999Z-TA-900-review-deadbeef"
        write(bad / "report.json", "{not json")
        self.make_run(mode="review", mutate=lambda r: r.pop("schema_version"))                   # legacy
        self.make_run(mode="review", mutate=lambda r: r.__setitem__("schema_version", 2))        # unsupported
        self.make_run(mode="review", mutate=lambda r: r.__setitem__("schema_version", "1"))      # malformed type
        self.make_run(mode="review", mutate=lambda r: r["identity"].__setitem__("extra", 1))     # identity keys
        self.make_run(mode="review", mutate=lambda r: r["identity"].pop("subject_fingerprint"))
        tampered = self.make_run(mode="review")
        write(self.artifacts / tampered / "contract.json", "{}")
        code, data = self.status_json("-Task", "TA-900")
        self.assertEqual(data["ignored_artifacts"], {"unsupported": 2, "malformed": 5})
        self.assertEqual((data["review"]["state"], data["evidence"], code), ("CLEAN", "CURRENT", 0))

    def test_stale_evidence(self):
        self.ready()
        write(self.repo / "src/a.txt", "changed again\n")
        code, data = self.status_json("-Task", "TA-900")
        self.assertEqual((data["review"]["state"], data["review"]["currency"], data["evidence"]), ("CLEAN", "STALE", "STALE"))
        self.assertEqual(code, ta.EXIT_UNHEALTHY)
        self.assertTrue(data["next_gate"].startswith("RE-REVIEW"))

    def test_current_evidence_ready_to_finalize(self):
        self.ready()
        code, data = self.status_json("-Task", "TA-900")
        self.assertEqual((data["review"]["state"], data["scope"]["state"], data["evidence"], data["tests"]["state"]),
                         ("CLEAN", "PASS", "CURRENT", "NOT_APPLICABLE"))
        self.assertEqual(code, 0)
        self.assertTrue(data["next_gate"].startswith("FINALIZE"))

    def test_json_output_is_json_only_with_stable_keys(self):
        self.ready()
        code, out, err = self.tool("status", "-Task", "TA-900", "-Json")
        data = json.loads(out)
        self.assertEqual(sorted(data), sorted(
            ["schema_version", "status", "exit_code", "task", "repository_root", "branch", "head", "origin", "sync",
             "ahead", "behind", "tree", "changed_paths", "subject_fingerprint", "design", "frozen", "latest_run",
             "review", "scope", "authority", "evidence", "tests", "next_gate", "ignored_artifacts"]))
        self.assertEqual((data["schema_version"], data["exit_code"], code), (1, 0, 0))
        self.assertEqual(err, "")
        code, out, _ = self.tool("status", "-Bogus", "-Json")
        self.assertEqual(code, 2)
        self.assertEqual(sorted(json.loads(out)), ["error_code", "exit_code", "message", "schema_version", "status"])

    def test_frozen_violation(self):
        write(self.repo / "core/F.kt", "changed\n")
        code, data = self.status_json()
        self.assertEqual((data["frozen"]["state"], data["frozen"]["hits"], code), ("FAIL", ["core/F.kt"], 1))

    def test_human_output_is_short(self):
        self.ready()
        for i in range(40):
            write(self.repo / f"other/x{i}.txt", "x\n")
        code, out, _ = self.tool("status", "-Task", "TA-900")
        self.assertLessEqual(len(out.splitlines()), 30)
        self.assertIn("SCOPE       FAIL", out)
        self.assertEqual(code, 1)

    def test_status_is_read_only(self):
        self.ready()
        before = self.git_snapshot()
        self.tool("status", "-Task", "TA-900")
        self.assertEqual(self.git_snapshot(), before)
        self.assertFalse([c for c in ta.GIT_CALLS if c[0] not in ta.READ_ONLY_GIT])


# ================================================================================================ ta-handoff

class HandoffTests(Base):
    def setUp(self):
        super().setUp()
        self.systemp = self.tmp / "systemp"
        self.systemp.mkdir()
        patcher = mock.patch.object(ta.tempfile, "gettempdir", return_value=str(self.systemp))
        patcher.start()
        self.addCleanup(patcher.stop)

    def handoff(self, *extra):
        code, out, err = self.tool("handoff", "-Task", "TA-900", *extra)
        return code, out, err

    def test_default_output_outside_repo_and_small(self):
        self.ready()
        code, out, _ = self.handoff("-Json")
        data = json.loads(out)
        target = Path(data["output"])
        self.assertEqual(code, 0)
        self.assertEqual(target, self.systemp / "mazovia-handoff" / "TA-900.md")
        self.assertFalse(cd.is_within(target, self.repo))
        raw = target.read_bytes()
        self.assertLessEqual(len(raw), ta.HANDOFF_MAX_BYTES)
        self.assertEqual(len(raw), data["bytes"])
        text = raw.decode("utf-8")
        for field in ("TASK: TA-900", "REPO:", "BRANCH: main", "HEAD:", "ORIGIN:", "DESIGN:", "TREE: DIRTY",
                      "LATEST REVIEW VERDICT: CLEAN", "SUBJECT FINGERPRINT:", "NEXT_GATE:", "CHANGED PATHS"):
            self.assertIn(field, text)
        self.assertEqual(sh(self.repo, "status", "--porcelain"), "M src/a.txt")

    def test_truncation_keeps_core_fields(self):
        findings = [{"severity": "LOW", "path": f"src/f{i}.kt", "line": i, "summary": "s" * 300,
                     "evidence": "EVIDENCE-SNIPPET " * 20} for i in range(40)]
        for i in range(250):
            write(self.repo / f"src/deep/{'long-directory-name/' * 3}file-{i:04d}.txt", "x\n")
        write(self.repo / "src/a.txt", SECRET + "\n")
        implement = self.make_run()
        self.make_run(mode="review", subject=implement, verdict="FINDINGS", exit_code=7,
                      response={"summary": "s", "findings": findings, "open_decisions": []})
        code, out, _ = self.handoff("-Json")
        data = json.loads(out)
        text = Path(data["output"]).read_bytes().decode("utf-8")
        self.assertTrue(data["truncated"])
        self.assertLessEqual(len(text.encode("utf-8")), 4096)
        self.assertTrue(text.rstrip("\n").endswith(ta.TRUNCATION_MARKER))
        state = self.state()
        ev = ta.evaluate(state, "TA-900", *ta.scan_artifacts())
        for line in ta.handoff_core(state, ev):
            self.assertIn(line + "\n", text)  # every core line complete and untruncated
        self.assertNotIn("EVIDENCE-SNIPPET", text)
        self.assertNotIn(SECRET, text)

    def test_no_logs_or_source_dumps(self):
        write(self.repo / "src/a.txt", SECRET + "\n")
        implement = self.make_run()
        finding = {"severity": "HIGH", "path": "src/a.txt", "line": 1, "summary": "bad", "evidence": SECRET}
        self.make_run(mode="review", subject=implement, verdict="FINDINGS", exit_code=7,
                      response={"summary": SECRET, "findings": [finding], "open_decisions": []})
        write(self.artifacts / implement / "events.jsonl", SECRET)
        code, out, _ = self.handoff("-Json")
        text = Path(json.loads(out)["output"]).read_text(encoding="utf-8")
        self.assertNotIn(SECRET, text)
        self.assertNotIn(str(self.artifacts), text)
        self.assertIn("HIGH src/a.txt:1: bad", text)

    def test_repo_contained_output_rejected(self):
        self.ready()
        for target in (self.repo / "handoff.md", self.repo / "sub" / "x.md", Path("relative.md")):
            code, out, _ = self.handoff("-Output", str(target), "-Json")
            self.assertEqual((code, json.loads(out)["error_code"]), (2, "OUTPUT_INSIDE_REPOSITORY"))
        self.assertEqual(sh(self.repo, "status", "--porcelain", "--untracked-files=all"), "M src/a.txt")

    @unittest.skipUnless(sys.platform == "win32", "junctions are Windows-only")
    def test_junction_into_repo_rejected(self):
        self.ready()
        link = self.tmp / "junction"
        subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(self.repo / "src")], check=True, capture_output=True)
        code, out, _ = self.handoff("-Output", str(link / "x.md"), "-Json")
        self.assertEqual((code, json.loads(out)["error_code"]), (2, "OUTPUT_INSIDE_REPOSITORY"))
        self.assertFalse((self.repo / "src" / "x.md").exists())

    def test_stale_review_and_tests_marked(self):
        self.ready(tests=[TEST_SPEC])
        write(self.repo / "src/a.txt", "moved on\n")
        code, out, _ = self.handoff("-Json")
        text = Path(json.loads(out)["output"]).read_text(encoding="utf-8")
        self.assertIn("evidence STALE", text)
        self.assertIn("TESTS: STALE", text)
        self.assertIn("NEXT_GATE: RE-REVIEW", text)

    def test_clean_tree_lists_committed_paths(self):
        code, out, _ = self.handoff("-Json")
        text = Path(json.loads(out)["output"]).read_text(encoding="utf-8")
        self.assertIn("COMMITTED PATHS (HEAD)", text)
        self.assertIn("- src/a.txt", text)


# ================================================================================================ ta-finalize

class FinalizeTests(Base):
    def finalize(self, *extra):
        code, out, err = self.tool("finalize", "-Task", "TA-900", *extra, "-Json")
        return code, json.loads(out), err

    def gate(self, data, name):
        return next(g for g in data["gates"] if g["gate"] == name)

    def test_check_only_zero_mutation(self):
        self.ready()
        before = self.git_snapshot()
        code, data, _ = self.finalize()
        self.assertEqual((code, data["status"], data["commit"]), (0, "ok", None))
        self.assertEqual(self.git_snapshot(), before)
        self.assertFalse([c for c in ta.GIT_CALLS if c[0] not in ta.READ_ONLY_GIT])
        self.assertEqual(self.prompts, [])

    def test_push_without_commit_is_usage_error(self):
        code, data, _ = self.finalize("-Push")
        self.assertEqual((code, data["error_code"]), (2, "PUSH_WITHOUT_COMMIT"))

    def test_stale_review_clean_rejected(self):
        self.ready()
        write(self.repo / "src/a.txt", "edited after review\n")
        code, data, _ = self.finalize("-Commit")
        self.assertEqual(code, 1)
        self.assertEqual(self.gate(data, "review_fingerprint")["result"], "FAIL")
        self.assertEqual(self.head(), sh(self.repo, "rev-parse", "origin/main"))
        self.assertEqual(self.prompts, [])

    def test_mismatched_fingerprint_rejected(self):
        write(self.repo / "src/a.txt", "changed\n")
        implement = self.make_run()
        self.make_run(mode="review", subject=implement, fingerprint="f" * 64)
        code, data, _ = self.finalize()
        self.assertEqual((code, self.gate(data, "review_fingerprint")["result"]), (1, "FAIL"))

    def test_conflicting_authority_rejected(self):
        write(self.repo / "src/a.txt", "changed\n")
        first = self.make_run(allowed=("src/**",))
        self.make_run(allowed=("src/**", "other/**"))
        self.make_run(mode="review", subject=first)
        code, data, _ = self.finalize()
        self.assertEqual(code, ta.EXIT_EVIDENCE)
        self.assertEqual(self.gate(data, "scope_authority")["detail"], "CONFLICTING_TASK_CONTRACTS")

    def test_malformed_authority_rejected(self):
        write(self.repo / "src/a.txt", "changed\n")
        implement = self.make_run()
        write(self.artifacts / implement / "contract.json", '{"schema_version": 1}')  # hash no longer matches
        self.make_run(mode="review", subject=implement)
        code, data, _ = self.finalize()
        self.assertEqual(code, ta.EXIT_EVIDENCE)
        self.assertEqual(self.gate(data, "scope_authority")["result"], "UNKNOWN")

    def test_unexpected_path_rejected(self):
        write(self.repo / "src/a.txt", "changed\n")
        write(self.repo / "other/b.txt", "changed\n")
        self.make_run(mode="review", subject=self.make_run())
        code, data, _ = self.finalize()
        self.assertEqual((code, self.gate(data, "scope")["result"]), (1, "FAIL"))
        self.assertIn("other/b.txt OUT_OF_SCOPE", self.gate(data, "scope")["detail"])

    def test_frozen_path_rejected(self):
        write(self.repo / "core/F.kt", "changed\n")
        self.make_run(mode="review", subject=self.make_run(allowed=("core/**",)))
        code, data, _ = self.finalize()
        self.assertEqual(code, 1)
        self.assertEqual((self.gate(data, "frozen")["result"], self.gate(data, "scope")["result"]), ("FAIL", "FAIL"))

    def test_diff_check_failure_rejected(self):
        self.ready(content="trailing whitespace   \n")
        code, data, _ = self.finalize()
        self.assertEqual((code, self.gate(data, "diff_check")["result"]), (1, "FAIL"))

    def test_design_change_rejected(self):
        write(self.repo / "docs/terrain-ahead/DESIGN.md", "design edited\n")
        write(self.repo / "src/a.txt", "changed\n")
        base_design = sh(self.repo, "rev-parse", "HEAD:docs/terrain-ahead/DESIGN.md")
        self.make_run(mode="review", subject=self.make_run(allowed=("src/**", "docs/**"), design=base_design))
        code, data, _ = self.finalize()
        self.assertEqual((code, self.gate(data, "design")["result"]), (1, "FAIL"))

    def test_valid_test_evidence_reused(self):
        self.ready(tests=[TEST_SPEC])
        self.answers = ["y"]
        with mock.patch.object(ta, "run_authoritative_test", side_effect=AssertionError("must not rerun")):
            code, data, _ = self.finalize("-Commit")
        self.assertEqual((code, data["status"]), (0, "ok"))
        self.assertEqual(self.gate(data, "tests")["detail"], "CURRENT")
        self.assertNotIn("tests_rerun", data)

    def test_wrong_test_fingerprint_not_reused(self):
        stale = [{"id": "app-unit", "command": cd.normalized_test_command(TEST_SPEC), "status": "PASS", "exit_code": 0,
                  "subject_fingerprint": "e" * 64}]
        write(self.repo / "src/a.txt", "changed\n")
        implement = self.make_run(tests=[TEST_SPEC], items=stale, fingerprint="e" * 64)
        self.make_run(mode="review", subject=implement)
        state = self.state()
        ev = ta.evaluate(state, "TA-900", *ta.scan_artifacts())
        self.assertEqual(ev["tests"]["state"], "STALE")
        calls = []
        self.answers = ["y"]
        with mock.patch.object(ta, "run_authoritative_test", side_effect=lambda repo, spec, log: calls.append(spec) or 1):
            code, data, _ = self.finalize("-Commit")
        self.assertEqual((code, data["error_code"]), (1, "TESTS_BLOCKED"))
        self.assertEqual(calls, [TEST_SPEC])
        self.assertEqual(self.head(), state["head"])
        self.assertEqual(self.prompts, [])

    def test_wrong_test_command_not_reused(self):
        other = dict(TEST_SPEC, argv=["gradlew.bat", ":other:testDebugUnitTest"])
        items = [{"id": "app-unit", "command": cd.normalized_test_command(other), "status": "PASS", "exit_code": 0,
                  "subject_fingerprint": None}]
        self.ready(tests=[TEST_SPEC], items=items)
        ev = ta.evaluate(self.state(), "TA-900", *ta.scan_artifacts())
        self.assertEqual(ev["tests"]["state"], "UNKNOWN")

    def test_missing_evidence_reruns_only_authoritative_tests(self):
        self.ready(tests=[TEST_SPEC], items=[])
        calls = []
        self.answers = ["y"]
        with mock.patch.object(ta, "run_authoritative_test", side_effect=lambda repo, spec, log: calls.append(spec) or 0):
            code, data, _ = self.finalize("-Commit")
        self.assertEqual(code, 0, data)
        self.assertEqual(calls, [TEST_SPEC])
        self.assertEqual([i["status"] for i in data["tests_rerun"]], ["PASS"])
        evidence = list(self.evidence.iterdir())
        self.assertEqual(len(evidence), 1)
        self.assertFalse(cd.is_within(evidence[0], self.repo))

    def test_missing_authority_never_reruns(self):
        write(self.repo / "src/a.txt", "changed\n")
        self.make_run(mode="review")
        self.answers = ["y"]
        with mock.patch.object(ta, "run_authoritative_test", side_effect=AssertionError("no authority")):
            code, data, _ = self.finalize("-Commit")
        self.assertEqual(code, ta.EXIT_EVIDENCE)
        self.assertEqual(self.prompts, [])

    def test_exact_allowlist_staging(self):
        write(self.repo / "src/a.txt", "changed\n")
        write(self.repo / "src/new.txt", "new\n")
        (self.repo / "src" / "gone.txt").write_text("x\n")
        sh(self.repo, "add", "src/gone.txt")
        sh(self.repo, "commit", "-qm", "gone")
        sh(self.repo, "push", "-q")
        (self.repo / "src" / "gone.txt").unlink()
        write(self.repo / "build/ignored.bin", "ignored\n")
        self.make_run(mode="review", subject=self.make_run())
        self.answers = ["y"]
        code, data, _ = self.finalize("-Commit")
        self.assertEqual(code, 0, data)
        committed = sh(self.repo, "show", "--name-status", "--format=", "HEAD").splitlines()
        self.assertEqual(sorted(committed), ["A\tsrc/new.txt", "D\tsrc/gone.txt", "M\tsrc/a.txt"])
        adds = [c for c in ta.GIT_CALLS if c[0] == "add"]
        self.assertEqual(adds, [["add", "--pathspec-from-file=-", "--pathspec-file-nul"]])
        self.assert_no_forbidden_git()
        self.assertTrue((self.repo / "build/ignored.bin").is_file())
        self.assertEqual(sh(self.repo, "status", "--porcelain"), "")

    def test_git_add_dot_is_impossible(self):
        for args in (("add", "."), ("add", "-A"), ("add", "--all"), ("add", "--", "src/a.txt"),
                     ("reset", "--hard"), ("push", "--force", "origin", "x:refs/heads/main"),
                     ("push", "--porcelain", "origin", "+x:refs/heads/main"), ("commit", "-am", "x")):
            with self.assertRaises(AssertionError):
                ta.git(self.repo, *args, mutate=True)
        for args in (("add", "."), ("commit", "-m", "x"), ("hash-object", "-w", "x"), ("config", "user.name", "x")):
            with self.assertRaises(AssertionError):
                ta.git(self.repo, *args)

    def test_noninteractive_commit_blocked(self):
        self.ready()
        with mock.patch.object(ta, "IS_INTERACTIVE", lambda: False):
            code, data, _ = self.finalize("-Commit")
        self.assertEqual((code, data["error_code"]), (1, "NONINTERACTIVE"))
        self.assertEqual(self.prompts, [])
        self.assertEqual(sh(self.repo, "diff", "--cached", "--name-only"), "")

    def test_redirected_stdin_is_not_interactive(self):
        with mock.patch.object(sys, "stdin", io.StringIO("y\n")):
            self.assertFalse(ta.stdin_is_console())
        with open(os.devnull, "r") as null, mock.patch.object(sys, "stdin", null):
            self.assertFalse(ta.stdin_is_console())

    def test_invalid_confirmations_blocked(self):
        self.ready()
        head = self.head()
        for answer in ("", "n", "no", "yes please", "ye", "Y es", "1", EOFError, KeyboardInterrupt):
            self.answers = [answer if isinstance(answer, str) else (lambda exc=answer: (_ for _ in ()).throw(exc()))]
            code, data, _ = self.finalize("-Commit")
            self.assertEqual((code, data["error_code"]), (1, "COMMIT_NOT_CONFIRMED"), answer)
            self.assertEqual(self.head(), head)
            self.assertEqual(sh(self.repo, "diff", "--cached", "--name-only"), "")

    def test_commit_requires_confirmation_and_accepts_yes(self):
        self.ready()
        self.answers = ["  YES \n"]
        code, data, _ = self.finalize("-Commit")
        self.assertEqual(code, 0, data)
        self.assertEqual(self.prompts, ["Proceed with COMMIT? [y/N] "])
        self.assertEqual(sh(self.repo, "log", "-1", "--format=%s"), "TA-900: tighten the widget")
        self.assertEqual(sh(self.repo, "rev-parse", "origin/main"), sh(self.repo, "rev-parse", "HEAD^"))
        self.assertFalse(data["pushed"])

    def test_custom_message(self):
        self.ready()
        self.answers = ["y"]
        code, _, _ = self.finalize("-Commit", "-Message", "TA-900: exact message")
        self.assertEqual((code, sh(self.repo, "log", "-1", "--format=%s")), (0, "TA-900: exact message"))

    def test_push_requires_separate_confirmation(self):
        self.ready()
        self.answers = ["y", "n"]
        code, data, _ = self.finalize("-Commit", "-Push")
        sha = data["commit"]
        self.assertEqual((code, data["error_code"], data["pushed"]), (1, "PUSH_NOT_CONFIRMED", False))
        self.assertEqual(self.prompts, ["Proceed with COMMIT? [y/N] ", f"Proceed with PUSH of {sha} to origin/main? [y/N] "])
        self.assertNotEqual(sh(self.origin, "rev-parse", "main"), sha)
        self.assertEqual(self.head(), sha)

    def test_commit_and_push(self):
        self.ready()
        self.answers = ["y", "y"]
        code, data, _ = self.finalize("-Commit", "-Push")
        self.assertEqual((code, data["pushed"]), (0, True), data)
        self.assertEqual(sh(self.origin, "rev-parse", "main"), data["commit"])
        self.assert_no_forbidden_git()

    def test_state_changed_before_commit_blocked(self):
        self.ready()
        head = self.head()

        def edit_then_yes():
            write(self.repo / "src/a.txt", "sneaky edit\n")
            return "y"
        self.answers = [edit_then_yes]
        code, data, _ = self.finalize("-Commit")
        self.assertEqual((code, data["error_code"]), (1, "STATE_CHANGED_BEFORE_COMMIT"))
        self.assertIn("fingerprint", data["message"])
        self.assertEqual(self.head(), head)
        self.assertEqual(sh(self.repo, "diff", "--cached", "--name-only"), "")

    def test_new_review_before_commit_blocked(self):
        implement, _ = self.ready()

        def review_then_yes():
            self.make_run(mode="review", subject=implement)
            return "y"
        self.answers = [review_then_yes]
        code, data, _ = self.finalize("-Commit")
        self.assertEqual((code, data["error_code"]), (1, "STATE_CHANGED_BEFORE_COMMIT"))
        self.assertIn("review", data["message"])

    def test_remote_changed_before_push_blocked(self):
        self.ready()

        def remote_moves_then_yes():
            self.other_clone_push()
            return "y"
        self.answers = ["y", remote_moves_then_yes]
        code, data, _ = self.finalize("-Commit", "-Push")
        self.assertEqual((code, data["error_code"], data["pushed"]), (1, "REMOTE_CHANGED_BEFORE_PUSH", False))
        self.assertEqual(self.head(), data["commit"])
        self.assertNotEqual(sh(self.origin, "rev-parse", "main"), data["commit"])
        self.assert_no_forbidden_git()

    def test_failed_commit_never_resets(self):
        self.ready()
        hook = self.repo / ".git" / "hooks" / "pre-commit"
        write(hook, "#!/bin/sh\necho rejected by hook >&2\nexit 1\n")
        self.answers = ["y"]
        code, data, _ = self.finalize("-Commit")
        self.assertEqual((code, data["error_code"]), (1, "COMMIT_FAILED"))
        self.assertIn("nothing reset", data["message"])
        self.assertEqual(sh(self.repo, "diff", "--cached", "--name-only"), "src/a.txt")  # left staged, not reset
        self.assertEqual((self.repo / "src/a.txt").read_text(), "changed\n")
        self.assert_no_forbidden_git()

    def test_failed_push_never_resets(self):
        self.ready()
        write(self.origin / "hooks" / "pre-receive", "#!/bin/sh\necho denied >&2\nexit 1\n")
        self.answers = ["y", "y"]
        code, data, _ = self.finalize("-Commit", "-Push")
        self.assertEqual((code, data["error_code"]), (1, "PUSH_FAILED"))
        self.assertEqual(self.head(), data["commit"])
        self.assert_no_forbidden_git()

    def test_commit_evidence_rejects_other_repo_root(self):
        write(self.repo / "src/a.txt", "changed\n")
        implement = self.make_run(root=str(self.tmp / "other-root"))
        self.make_run(mode="review", subject=implement, root=str(self.tmp / "other-root"))
        code, data, _ = self.finalize()
        self.assertEqual(code, ta.EXIT_EVIDENCE)
        self.assertEqual(self.gate(data, "review_clean")["result"], "UNKNOWN")


# ================================================================================================ lock

HOLDER = ("import sys, time; sys.path.insert(0, sys.argv[1]); import ta_tools as ta; from pathlib import Path; "
          "lock = ta.FinalizeLock(Path(sys.argv[2])); lock.acquire(); print('LOCKED', flush=True); time.sleep(120)")


class LockTests(Base):
    def holder(self):
        proc = subprocess.Popen([sys.executable, "-c", HOLDER, str(SCRIPTS), str(self.repo / ".git")],
                                stdout=subprocess.PIPE, text=True, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"))
        self.addCleanup(lambda: (proc.kill(), proc.wait()))
        self.assertEqual(proc.stdout.readline().strip(), "LOCKED")
        return proc

    def test_concurrent_finalizer_blocked(self):
        self.ready()
        proc = self.holder()
        lock_path = self.repo / ".git" / ta.LOCK_NAME
        before = lock_path.read_bytes()
        self.answers = ["y"]
        code, out, _ = self.tool("finalize", "-Task", "TA-900", "-Commit", "-Json")
        data = json.loads(out)
        self.assertEqual((code, data["error_code"]), (1, "CONCURRENT_FINALIZE"))
        self.assertIn(f"pid {json.loads(before)['pid']} ", data["message"])  # venv launchers re-spawn: not proc.pid
        self.assertIsNone(proc.poll())
        self.assertEqual(lock_path.read_bytes(), before)  # the live holder's lock is untouched
        self.assertEqual(self.prompts, [])

    def test_lock_is_atomic_json_and_released(self):
        lock = ta.FinalizeLock(self.repo / ".git")
        payload = lock.acquire()
        self.assertEqual(payload["pid"], os.getpid())
        self.assertRegex(payload["start_utc"], ta.UTC_RE)
        with self.assertRaises(ta.LockBusy):
            ta.FinalizeLock(self.repo / ".git").acquire()
        lock.release()
        self.assertFalse(lock.path.exists())
        self.assertIn(".git", lock.path.parts)  # inside the git dir: never part of the worktree

    @unittest.skipUnless(sys.platform == "win32", "stale-lock breaking is Windows-only by design")
    def test_stale_lock_from_dead_process_is_replaced(self):
        proc = self.holder()
        lock = ta.FinalizeLock(self.repo / ".git")
        pid = lock.holder()["pid"]
        os.kill(pid, 9)  # the real interpreter (a venv launcher re-spawns it), then the launcher
        proc.kill()
        proc.wait()
        for _ in range(100):
            if not ta.pid_alive(pid):
                break
            time.sleep(0.1)
        self.assertFalse(ta.pid_alive(pid))
        self.assertEqual(lock.acquire()["pid"], os.getpid())
        lock.release()

    @unittest.skipUnless(sys.platform == "win32", "open-handle protection is Windows-only")
    def test_lock_held_open_with_dead_pid_is_not_broken(self):
        path = self.repo / ".git" / ta.LOCK_NAME
        write(path, json.dumps({"pid": 999999999, "start_utc": "x"}))
        with open(path, "rb"):
            with self.assertRaises(ta.LockBusy):
                ta.FinalizeLock(self.repo / ".git").acquire()
        self.assertTrue(path.exists())


# ================================================================================================ fingerprint / identity

class FingerprintTests(Base):
    def test_stable_and_order_independent(self):
        write(self.repo / "src/a.txt", "changed\n")
        write(self.repo / "src/new.txt", "new\n")
        first, second = self.state(), self.state()
        self.assertEqual(first["fingerprint"], second["fingerprint"])
        entries = cd.status_entries(self.repo)
        self.assertEqual(cd.subject_fingerprint(first["root"], first["head"], list(reversed(entries))),
                         first["fingerprint"])

    def test_staging_does_not_change_fingerprint(self):
        write(self.repo / "src/a.txt", "changed\n")
        write(self.repo / "src/new.txt", "new\n")
        (self.repo / "other/b.txt").unlink()
        before = self.state()["fingerprint"]
        sh(self.repo, "add", "-A")
        self.assertEqual(self.state()["fingerprint"], before)

    def test_fingerprint_covers_head_content_state_and_root(self):
        write(self.repo / "src/a.txt", "changed\n")
        base = self.state()
        write(self.repo / "src/a.txt", "changed2\n")
        self.assertNotEqual(self.state()["fingerprint"], base["fingerprint"])
        write(self.repo / "src/a.txt", "changed\n")
        self.assertEqual(self.state()["fingerprint"], base["fingerprint"])
        entries = cd.status_entries(self.repo)
        self.assertNotEqual(cd.subject_fingerprint(base["root"], "0" * 40, entries), base["fingerprint"])
        self.assertNotEqual(cd.subject_fingerprint(base["root"] + "x", base["head"], entries), base["fingerprint"])
        untracked = [dict(entries[0], head_blob=None, mode_head=None, **{"class": "untracked"})]
        self.assertNotEqual(cd.subject_fingerprint(base["root"], base["head"], untracked), base["fingerprint"])
        moded = [dict(entries[0], mode_worktree="100755")]
        self.assertNotEqual(cd.subject_fingerprint(base["root"], base["head"], moded), base["fingerprint"])

    def test_clean_tree_fingerprint_is_head_bound(self):
        before = self.state()["fingerprint"]
        write(self.repo / "src/a.txt", "x\n")
        sh(self.repo, "commit", "-qam", "x")
        self.assertNotEqual(self.state()["fingerprint"], before)

    def test_identity_tuple_required(self):
        write(self.repo / "src/a.txt", "changed\n")
        for key in IDENTITY_KEYS:
            self.make_run(mode="review", mutate=lambda r, k=key: r["identity"].pop(k))
        artifacts, ignored = ta.scan_artifacts()
        self.assertEqual(artifacts, [])
        self.assertEqual(ignored, {"unsupported": 0, "malformed": len(IDENTITY_KEYS)})
        self.assertEqual(list(cd.IDENTITY_KEYS), IDENTITY_KEYS)

    def test_identity_run_id_must_match_directory(self):
        write(self.repo / "src/a.txt", "changed\n")
        self.make_run(mode="review", mutate=lambda r: r["identity"].__setitem__("run_id", "20260101T000000Z-TA-900-review-00000000"))
        self.assertEqual(ta.scan_artifacts()[1]["malformed"], 1)

    def test_git_star_inheritance_blocked(self):
        injected = {"GIT_DIR": str(self.tmp / "nowhere"), "GIT_WORK_TREE": str(self.tmp), "GIT_INDEX_FILE": "x",
                    "GIT_EXTERNAL_DIFF": "evil", "GIT_CONFIG_PARAMETERS": "'core.pager=evil'"}
        with mock.patch.dict(os.environ, injected):
            self.assertFalse(any(k.upper().startswith("GIT_") for k in ta.mutation_environment()))
            read_env = cd.git_environment()
            for key in injected:
                self.assertNotIn(key, read_env)
            state = self.state()  # still reads this repository, not GIT_DIR
        self.assertEqual(state["head"], self.head())


# ================================================================================================ wrappers

@unittest.skipUnless(sys.platform == "win32", "PowerShell wrappers are Windows-only")
class WrapperTests(Base):
    def ps(self, script, *args, stdin=subprocess.DEVNULL):
        env = dict(os.environ, MAZOVIA_PYTHON=sys.executable, PYTHONDONTWRITEBYTECODE="1", TEMP=str(self.tmp / "wt"),
                   TMP=str(self.tmp / "wt"))
        (self.tmp / "wt").mkdir(exist_ok=True)
        proc = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                               str(SCRIPTS / script), *args], cwd=self.repo, capture_output=True, stdin=stdin, env=env,
                              timeout=120)
        return proc.returncode, proc.stdout.decode("utf-8"), proc.stderr.decode("utf-8", "replace")

    def test_status_wrapper_json(self):
        code, out, _ = self.ps("ta-status.ps1", "-Json")
        data = json.loads(out)
        self.assertEqual((code, data["exit_code"], data["tree"]), (0, 0, "CLEAN"))

    def test_wrapper_usage_errors(self):
        code, out, _ = self.ps("ta-finalize.ps1", "-Task", "TA-900", "-Push", "-Json")
        self.assertEqual((code, json.loads(out)["error_code"]), (2, "PUSH_WITHOUT_COMMIT"))
        code, _, err = self.ps("ta-status.ps1", "-Task", "bad id")
        self.assertEqual(code, 2)
        self.assertIn("INVALID_TASK", err)

    def test_wrapper_commit_with_redirected_stdin_is_blocked(self):
        self.ready()
        shutil.copytree(self.artifacts, self.tmp / "wt" / "mazovia-codex")  # the wrapper's %TEMP% artifact root
        code, out, _ = self.ps("ta-finalize.ps1", "-Task", "TA-900", "-Json")
        self.assertEqual((code, json.loads(out)["status"]), (0, "ok"))
        for stdin in (subprocess.DEVNULL, subprocess.PIPE):
            code, out, err = self.ps("ta-finalize.ps1", "-Task", "TA-900", "-Commit", "-Json", stdin=stdin)
            self.assertEqual((code, json.loads(out)["error_code"]), (1, "NONINTERACTIVE"))
            self.assertNotIn("Proceed with COMMIT", err)
        self.assertEqual(self.head(), sh(self.repo, "rev-parse", "origin/main"))
        self.assertEqual(sh(self.repo, "diff", "--cached", "--name-only"), "")

    def test_wrapper_handoff_writes_outside_repo(self):
        code, out, _ = self.ps("ta-handoff.ps1", "-Task", "TA-900", "-Json")
        data = json.loads(out)
        self.assertEqual(code, 0)
        self.assertTrue(Path(data["output"]).is_file())
        self.assertFalse(cd.is_within(data["output"], self.repo))
        self.assertLessEqual(data["bytes"], 4096)


# ================================================================================================ bytecode (HIGH-1)

@unittest.skipUnless(sys.platform == "win32", "PowerShell wrappers are Windows-only")
class WrapperBytecodeTests(Base):
    """The real wrappers, run from scripts committed inside the repository, write no __pycache__ / *.pyc (nor any
    other repository file). PYTHONDONTWRITEBYTECODE is removed from the child environment, never pre-set."""

    FILES = ("ta_tools.py", "codex_delegate.py", "ta-status.ps1", "ta-handoff.ps1", "ta-finalize.ps1")

    def setUp(self):
        super().setUp()
        (self.repo / ".claude" / "scripts").mkdir()
        for name in self.FILES:
            shutil.copyfile(SCRIPTS / name, self.repo / ".claude" / "scripts" / name)
        sh(self.repo, "add", "--", ".claude/scripts")
        sh(self.repo, "commit", "-q", "-m", "scripts inside the repository")
        sh(self.repo, "push", "-q")
        (self.tmp / "wt").mkdir()

    def bytecode(self):
        return sorted(p.relative_to(self.repo).as_posix() for p in self.repo.rglob("*")
                      if p.name == "__pycache__" or p.suffix == ".pyc")

    def tree(self):
        return sh(self.repo, "status", "--porcelain=v1", "--untracked-files=all", "--ignored")

    def env(self, python):
        env = {k: v for k, v in os.environ.items() if k.upper() != "PYTHONDONTWRITEBYTECODE"}
        env.update(MAZOVIA_PYTHON=str(python), TEMP=str(self.tmp / "wt"), TMP=str(self.tmp / "wt"))
        return env

    def run_wrappers(self, env):
        """Runs the three wrappers from the repository; each leaves no bytecode, no repository file, no INTERNAL."""
        scripts, runs = self.repo / ".claude" / "scripts", []
        for script, args in (("ta-status.ps1", ["-Json"]), ("ta-handoff.ps1", ["-Task", "TA-900", "-Json"]),
                             ("ta-finalize.ps1", ["-Task", "TA-900", "-Json"])):
            self.assertEqual((self.bytecode(), self.tree()), ([], ""), script)
            proc = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                   str(scripts / script), *args], cwd=self.repo, capture_output=True,
                                  stdin=subprocess.DEVNULL, env=env, timeout=120)
            data = json.loads(proc.stdout.decode("utf-8"))
            self.assertNotEqual(data.get("error_code"), "INTERNAL", (script, data))
            self.assertEqual(self.bytecode(), [], script)
            self.assertEqual(self.tree(), "", script)
            runs.append((script, data))
        return runs

    def test_wrappers_write_no_bytecode_or_repository_files(self):
        runs = self.run_wrappers(self.env(sys.executable))
        self.assertIsNone(runs[-1][1]["commit"])  # finalize ran check-only

    # A repository-root struct.py that, once imported, drops a marker; calcsize keeps the version probe passing.
    MARKER = "struct-shadow-imported.marker"
    SHADOW = ("import os\n"
              "open(os.path.join(os.path.dirname(os.path.abspath(__file__)), %r), 'w').close()\n"
              "def calcsize(fmt):\n    return 8\n" % MARKER)

    def test_probe_never_imports_repository_struct(self):
        """-I: the version probe runs with the repository as cwd, yet never imports a repository-root struct.py."""
        canary = self.tmp / "canary"
        write(canary / "struct.py", self.SHADOW)
        subprocess.run([sys.executable, "-B", "-c", "import struct"], cwd=canary, check=True, capture_output=True)
        self.assertTrue((canary / self.MARKER).is_file())  # fixture sanity: a probe without -I imports it
        write(self.repo / "struct.py", self.SHADOW)
        sh(self.repo, "add", "--", "struct.py")
        sh(self.repo, "commit", "-q", "-m", "shadowing struct.py")
        sh(self.repo, "push", "-q")
        self.run_wrappers(self.env(sys.executable))
        self.assertFalse((self.repo / self.MARKER).exists())

    # MAZOVIA_PYTHON shim: records each argv (one JSON line), then runs the real interpreter with it unchanged.
    SHIM = ("import json, os, subprocess, sys\n"
            "with open(os.environ['ARGV_LOG'], 'a', encoding='utf-8') as log:\n"
            "    log.write(json.dumps(sys.argv[1:]) + '\\n')\n"
            "sys.exit(subprocess.run([os.environ['ARGV_REAL'], *sys.argv[1:]]).returncode)\n")
    CHECK = "import sys,struct; print(sys.version_info>=(3,11) and struct.calcsize('P')==8)"

    def test_wrapper_python_argv(self):
        """-B: the actual wrapper invocations, observed through the shim: the probe is exactly -I -B -c <check>, and
        ta_tools.py runs as -B <script> without -I."""
        shim = self.tmp / "shim"
        write(shim / "argv_shim.py", self.SHIM)
        write(shim / "python.cmd", f'@"{sys.executable}" -B "%~dp0argv_shim.py" %*\r\n@exit /b %ERRORLEVEL%\r\n')
        log = self.tmp / "argv.jsonl"
        env = self.env(shim / "python.cmd")
        env.update(ARGV_LOG=str(log), ARGV_REAL=sys.executable)
        runs = self.run_wrappers(env)
        calls = [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(calls), 2 * len(runs), calls)  # one probe + one main run per wrapper
        script = os.path.normcase(str(self.repo / ".claude" / "scripts" / "ta_tools.py"))
        for (name, _), probe, main in zip(runs, calls[0::2], calls[1::2]):
            self.assertEqual(probe, ["-I", "-B", "-c", self.CHECK], name)
            self.assertEqual([main[0], os.path.normcase(main[1])], ["-B", script], name)


if __name__ == "__main__":
    unittest.main()
