"""DEV-ENV-002A runner acceptance tests (S1-S53, review-corrective C1-C6). Temporary repositories and the Codex stub only.

Run: <py> -m pytest -p no:cacheprovider .claude/scripts/tests
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import codex_delegate as cd  # noqa: E402

STUB = str(Path(__file__).resolve().parent / "codex_stub.py")
FROZEN = ("# test frozen list\n.claude/settings.json\n.claude/frozen-paths.txt\n.claude/hooks/**\n"
          ".claude/scripts/**\ncore/F.kt\nfrozendir/**\n")
GITIGNORE = "build/\n.env\n*.log\nlocal.properties\n.claude/settings.local.json\n.opencode/\n"
SECRET = "S3CR3T-VALUE-DO-NOT-LEAK"


def sh(repo, *args):
    return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", *args], cwd=repo,
                          check=True, capture_output=True, text=True).stdout


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


@pytest.fixture
def env(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    repo.mkdir()
    sh(repo, "init", "-q", "-b", "main")
    sh(repo, "config", "core.autocrlf", "false")
    files = {"docs/terrain-ahead/AUDIT.md": "audit\n", "docs/terrain-ahead/DESIGN.md": "design\n",
             ".claude/frozen-paths.txt": FROZEN, ".gitignore": GITIGNORE,
             "gradle/wrapper/gradle-wrapper.properties":
                 "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n",
             "src/a.txt": "a\n", "other/b.txt": "b\n", "x/b.txt": "xb\n", "core/F.kt": "frozen\n",
             "frozendir/G.kt": "g\n", "tools/build/keep.txt": "tracked under build\n",
             "config/tracked.pem": "public placeholder\n"}
    for rel, text in files.items():
        write(repo / rel, text)
    sh(repo, "add", "-A")
    sh(repo, "add", "-f", "tools/build/keep.txt")
    sh(repo, "commit", "-q", "-m", "init")
    monkeypatch.chdir(repo)
    monkeypatch.setattr(cd, "artifact_root", lambda: tmp_path / "artifacts")
    cd.GIT_CALLS.clear()
    return Harness(tmp_path, repo, monkeypatch)


class Harness:
    def __init__(self, tmp, repo, monkeypatch):
        self.tmp, self.repo, self.mp = tmp, repo, monkeypatch
        self.inputs = tmp / "inputs"
        self.inputs.mkdir()
        self.n = 0

    def contract(self, **kw):
        base = {"schema_version": 1, "task_id": "TA-TEST", "mode": "implement", "parent_run_id": None,
                "subject_run_id": None, "model": "stub-model", "reasoning_effort": "low",
                "allowed_paths": ["src/**"], "frozen_paths_source": ".claude/frozen-paths.txt",
                "expected_docs": {"audit_git_blob": None, "design_git_blob": None}, "required_tests": [],
                "timeout_seconds": 120}
        base.update(kw)
        return base

    def run(self, contract, scenario=None, capsys=None, raw_contract=None, prompt="Implement the task.", extra=()):
        self.n += 1
        cpath, ppath = self.inputs / f"c{self.n}.json", self.inputs / f"p{self.n}.txt"
        cpath.write_text(raw_contract if raw_contract is not None else json.dumps(contract), encoding="utf-8")
        ppath.write_text(prompt, encoding="utf-8")
        Path(str(self.repo) + ".scenario.json").write_text(json.dumps(scenario or {}), encoding="utf-8")
        mode = "review" if contract and contract.get("mode") == "review" else "implement"
        argv = [mode, "--contract", str(cpath), "--prompt", str(ppath), "--codex-exe-override", STUB, *extra]
        code = cd.main(argv)
        out = json.loads(capsys.readouterr().out) if capsys else {}
        report = None
        if out.get("run_dir"):
            report = json.loads((Path(out["run_dir"]) / "report.json").read_text(encoding="utf-8"))
        return code, out, report

    def settle(self):
        """Commit the temp repository so the next first-implement run starts clean."""
        sh(self.repo, "add", "-A")
        sh(self.repo, "commit", "-q", "--allow-empty", "-m", "settle")

    def calls(self):
        path = Path(str(self.repo) + ".calls.jsonl")
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] if path.is_file() else []

    def gradle(self, fake=True):
        if fake:
            self.mp.setattr(cd, "resolve_gradle_distribution",
                            lambda url: (self.tmp / "fake-gradle" / "gradle.bat", self.tmp / "fake-caches"))


def write_action(path, content="changed\n"):
    return {"op": "write", "path": path, "content": content}


def exec_actions(*actions, **kw):
    spec = {"actions": list(actions)}
    spec.update(kw)
    return {"exec": spec}


def violations(report):
    return {(v["path"], tuple(v["reasons"])) for v in report["scope"]["violations"]}


def all_run_bytes(run_dir: Path) -> bytes:
    return b"".join(p.read_bytes() for p in Path(run_dir).rglob("*") if p.is_file())


# ----------------------------------------------------------------------------------------- S1-S11 scope / HEAD

def test_s1_allowed_modification(env, capsys):
    code, out, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    assert code == 0 and rep["status"]["SCOPE"] == "PASS" and rep["status"]["EXECUTION"] == "SUCCESS"
    assert rep["status"]["RESPONSE"] == "PRESENT" and rep["status"]["HEAD"] == "UNCHANGED"
    assert rep["scope_note"].startswith("SCOPE VALIDATION IS A FINAL-STATE RESULT CHECK")
    assert rep["push_prevention"] == "MITIGATED_NOT_PROVEN"


def test_s2_out_of_scope_modification(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(write_action("other/b.txt")), capsys)
    assert code == 5 and rep["status"]["SCOPE"] == "FAIL"
    assert ("other/b.txt", ("OUT_OF_SCOPE",)) in violations(rep)
    assert (env.repo / "other/b.txt").read_text() == "changed\n"


def test_s3_frozen_modification(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(write_action("core/F.kt")), capsys)
    assert code == 5 and rep["status"]["FROZEN"] == "FAIL" and "core/F.kt" in rep["scope"]["frozen_hits"]


def test_s4_allowed_untracked(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(write_action("src/new.txt")), capsys)
    assert code == 0 and rep["status"]["SCOPE"] == "PASS"


def test_s5_out_of_scope_untracked(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(write_action("x/new.txt")), capsys)
    assert code == 5 and ("x/new.txt", ("OUT_OF_SCOPE",)) in violations(rep)


def test_s6_delete_allowed_and_out_of_scope(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions({"op": "delete", "path": "src/a.txt"}), capsys)
    assert code == 0
    env.settle()
    code, _, rep = env.run(env.contract(), exec_actions({"op": "delete", "path": "other/b.txt"}), capsys)
    assert code == 5 and ("other/b.txt", ("OUT_OF_SCOPE",)) in violations(rep)


def test_s7_rename_source_violation(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions({"op": "git", "args": ["mv", "x/b.txt", "src/b.txt"]}), capsys)
    assert code == 5
    roles = {(v["path"], v["role"]) for v in rep["scope"]["violations"]}
    assert ("x/b.txt", "src") in roles and not any(p == "src/b.txt" for p, _ in roles)


def test_s8_rename_destination_violation(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions({"op": "git", "args": ["mv", "src/a.txt", "x/a.txt"]}), capsys)
    assert code == 5
    roles = {(v["path"], v["role"]) for v in rep["scope"]["violations"]}
    assert ("x/a.txt", "main") in roles and not any(p == "src/a.txt" for p, _ in roles)


def test_s9_staged_modification(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt"),
                                                        {"op": "git", "args": ["add", "src/a.txt"]}), capsys)
    assert code == 0
    assert [c["class"] for c in rep["scope"]["changes"] if c["path"] == "src/a.txt"] == ["staged"]


def test_s10_branch_movement(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions({"op": "git", "args": ["switch", "-q", "-c", "other"]}), capsys)
    assert code == 5 and rep["status"]["HEAD"] == "CHANGED"


def test_s11_head_movement_commit(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt"), {"op": "git", "args": ["add", "-A"]},
                                                        {"op": "git", "args": ["commit", "-q", "-m", "x"]}), capsys)
    assert code == 5 and rep["status"]["HEAD"] == "CHANGED"


# ----------------------------------------------------------------------------------------- S12-S16 process / response

def _heartbeat_stopped(path: Path) -> bool:
    if not path.exists():
        return True
    size = path.stat().st_size
    time.sleep(1.0)
    return path.stat().st_size == size


def test_s12_s13_timeout_kills_tree(env, capsys):
    hb = env.tmp / "hb-timeout.txt"
    code, _, rep = env.run(env.contract(timeout_seconds=60),
                           exec_actions({"op": "heartbeat", "path": str(hb)}, {"op": "hang"}), capsys)
    assert code == 4 and rep["status"]["EXECUTION"] == "TIMEOUT"
    assert rep["codex_process"]["termination_confirmed"] is True
    assert hb.exists() and _heartbeat_stopped(hb)


def test_s13_detached_grandchild_after_exit(env, capsys):
    hb = env.tmp / "hb-straggler.txt"
    code, _, rep = env.run(env.contract(), exec_actions({"op": "heartbeat", "path": str(hb)}, {"op": "sleep", "seconds": 1}), capsys)
    assert code == 0 and rep["codex_process"]["stragglers_terminated"] >= 1
    assert rep["codex_process"]["termination_confirmed"] is True and _heartbeat_stopped(hb)


def test_s14_missing_final(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt"), final=None), capsys)
    assert code == 3 and rep["status"]["RESPONSE"] == "MISSING"


def test_s14b_invalid_final_schema(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(final={"mode": "implement", "verdict": "COMPLETED"}), capsys)
    assert code == 3 and rep["status"]["RESPONSE"] == "INCOMPLETE"


def test_s15_nonzero_exec(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(exit=3), capsys)
    assert code == 3 and rep["status"]["EXECUTION"] == "FAILED" and rep["status"]["RESPONSE"] == "INCOMPLETE"


def test_s16_artifacts_preserved_after_failure(env, capsys):
    code, out, rep = env.run(env.contract(), exec_actions(write_action("other/b.txt")), capsys)
    run_dir = Path(out["run_dir"])
    for name in ("contract.json", "prompt.txt", "prompt-effective.txt", "frozen-paths.txt", "baseline.json",
                 "codex-argv.json", "events.jsonl", "stderr.log", "final.txt", "post-state.json", "report.json"):
        assert (run_dir / name).is_file(), name
    assert code == 5 and (env.repo / "other/b.txt").read_text() == "changed\n"


# ----------------------------------------------------------------------------------------- S17-S22 preconditions / state

def test_s17_dirty_tracked_rejected(env, capsys):
    write(env.repo / "src/a.txt", "dirty\n")
    code, _, rep = env.run(env.contract(), exec_actions(), capsys)
    assert code == 2 and rep["precondition_failure"] == "REPOSITORY_NOT_CLEAN" and rep["codex_started"] is False
    assert not env.calls() or all(c["mode"] != "exec" for c in env.calls())


def test_s18_untracked_rejected(env, capsys):
    write(env.repo / "new.txt", "u\n")
    code, _, rep = env.run(env.contract(), exec_actions(), capsys)
    assert code == 2 and rep["precondition_failure"] == "REPOSITORY_NOT_CLEAN"


def _parent(env, capsys):
    code, out, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt", "parent\n")), capsys)
    assert code == 0
    return out["run_id"]


def test_s19_corrective_exact_state_accepted(env, capsys):
    parent = _parent(env, capsys)
    code, _, rep = env.run(env.contract(parent_run_id=parent), exec_actions(write_action("src/c.txt")), capsys,
                           extra=("--parent-run-id", parent))
    assert code == 0 and rep["kind"] == "corrective"
    assert rep["incremental"] == ["src/c.txt"]
    assert {c["path"] for c in rep["scope"]["changes"]} == {"src/a.txt", "src/c.txt"}


def test_s20_corrective_byte_mismatch_rejected(env, capsys):
    parent = _parent(env, capsys)
    write(env.repo / "src/a.txt", "parenT\n")
    code, _, rep = env.run(env.contract(parent_run_id=parent), exec_actions(), capsys, extra=("--parent-run-id", parent))
    assert code == 2 and rep["precondition_failure"] == "PARENT_STATE_MISMATCH"


def test_s21_incomplete_baseline_cannot_pass(env, capsys, monkeypatch):
    def boom(repo, frozen):
        raise cd.ManifestError("injected baseline failure")
    monkeypatch.setattr(cd, "capture_manifest", boom)
    code, _, rep = env.run(env.contract(), exec_actions(), capsys)
    assert code == 8 and rep["codex_started"] is False and rep["status"]["SCOPE"] == "INCOMPLETE"


def test_s22_incomplete_post_state_cannot_pass(env, capsys, monkeypatch):
    real, calls = cd.capture_manifest, {"n": 0}

    def flaky(repo, frozen):
        calls["n"] += 1
        if calls["n"] >= 2:
            raise cd.ManifestError("injected post-state failure")
        return real(repo, frozen)
    monkeypatch.setattr(cd, "capture_manifest", flaky)
    code, _, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    assert code == 8 and rep["status"]["SCOPE"] == "INCOMPLETE"


# ----------------------------------------------------------------------------------------- S23-S28 contract / frozen

def test_s23_unknown_field(env, capsys):
    code, out, _ = env.run(env.contract(extra=1), None, capsys)
    assert code == 2 and out["error"] == "CONTRACT_INVALID"


def test_s24_missing_field(env, capsys):
    c = env.contract()
    del c["timeout_seconds"]
    code, out, _ = env.run(c, None, capsys)
    assert code == 2 and out["error"] == "CONTRACT_INVALID"


@pytest.mark.parametrize("patch", [{"mode": "deploy"}, {"timeout_seconds": "60"}, {"timeout_seconds": 30},
                                   {"reasoning_effort": "max"}, {"schema_version": 2}, {"allowed_paths": "src/**"}])
def test_s25_bad_enum_or_type(env, capsys, patch):
    code, out, _ = env.run(env.contract(**patch), None, capsys)
    assert code == 2


def test_s26_duplicate_key(env, capsys):
    raw = json.dumps(env.contract())[:-1] + ', "model": "stub-model"}'
    code, out, _ = env.run(env.contract(), None, capsys, raw_contract=raw)
    assert code == 2 and "duplicate" in out["detail"]


def test_s27_frozen_overrides_allowed(env, capsys):
    code, _, rep = env.run(env.contract(allowed_paths=["core/**"]), exec_actions(write_action("core/F.kt")), capsys)
    assert code == 5 and rep["status"]["FROZEN"] == "FAIL" and rep["frozen_overlap_warnings"]
    code, out, _ = env.run(env.contract(allowed_paths=["frozendir/**"]), None, capsys)
    assert code == 2 and out["error"] == "ALLOWED_PATH_INSIDE_FROZEN"
    code, out, _ = env.run(env.contract(allowed_paths=[".claude/x.txt"]), None, capsys)
    assert code == 2 and out["error"] == "ALLOWED_PATH_INSIDE_POLICY_PROTECTED"


@pytest.mark.parametrize("frozen", ["C:/x\n" + FROZEN, "../x\n" + FROZEN, "a/[b]\n" + FROZEN, "# only comments\n",
                                    "core/F.kt\n"])
def test_s28_malformed_frozen_source(env, capsys, frozen):
    write(env.repo / ".claude/frozen-paths.txt", frozen)
    sh(env.repo, "commit", "-q", "-am", "frozen variant")
    code, out, _ = env.run(env.contract(), None, capsys)
    assert code == 2 and out["error"].startswith("FROZEN_POLICY")


# ----------------------------------------------------------------------------------------- S29-S31, S38, S43 tests

GRADLE_TEST = {"id": "unit", "argv": ["gradlew.bat", ":app:testDebugUnitTest"], "cwd": ".", "timeout_seconds": 60,
               "env": {"PYTHONHASHSEED": "0"}}


def test_s29_required_test_pass(env, capsys):
    env.gradle()
    scenario = {**exec_actions(write_action("src/a.txt")),
                "sandbox": {"gradle": {"exit": 0, "junit": {"tests": 3, "failures": 0}}}}
    code, out, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 0 and rep["status"]["TESTS"] == "PASS"
    assert rep["tests"]["results"][0]["junit"]["tests"] == 3 and rep["tests"]["canary"]["passed"]
    sandbox_calls = [c for c in env.calls() if c["mode"] == "sandbox" and not c["canary"]]
    argv = sandbox_calls[0]["argv"]
    assert argv[:4] == ["sandbox", "-P", ":workspace", "-C"] and "--offline" in argv and "--no-daemon" in argv
    assert "-Pkotlin.compiler.execution.strategy=in-process" in argv
    values = sandbox_calls[0]["env_values"]
    run_dir = Path(out["run_dir"])
    assert Path(values["TEMP"]) == run_dir / "test-tmp" and Path(values["GRADLE_USER_HOME"]) == run_dir / "test-tmp" / "gradle-home"
    assert values["PYTHONHASHSEED"] == "0" and values["MAZOVIA_CODEX_RUN_ID"] == out["run_id"]


def test_s30_required_test_fail(env, capsys):
    env.gradle()
    scenario = {**exec_actions(write_action("src/a.txt")), "sandbox": {"gradle": {"exit": 1}}}
    code, _, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 6 and rep["status"]["TESTS"] == "FAIL"


def test_s30b_unreadable_junit_is_incomplete(env, capsys):
    env.gradle()
    scenario = {**exec_actions(write_action("src/a.txt")),
                "sandbox": {"gradle": {"exit": 0, "junit": {"tests": 1, "failures": 0, "malformed": True}}}}
    code, _, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 8 and rep["status"]["TESTS"] == "INCOMPLETE"
    assert rep["tests"]["results"][0]["junit"]["unreadable"] == 1


def test_s34b_long_paths_are_hashed(tmp_path):
    deep = tmp_path / ("d" * 60) / ("e" * 60) / ("f" * 60) / ("g" * 60)
    os.makedirs(cd.fs(deep), exist_ok=True)
    target = deep / "long-file.txt"
    assert len(str(target)) > 260
    with open(cd.fs(target), "wb") as fh:
        fh.write(b"long")
    rel = target.relative_to(tmp_path).as_posix()
    state = cd.file_state(tmp_path, rel)
    assert state["size"] == 4 and state["sha256"] == cd.sha256_bytes(b"long")
    assert cd.sha256_file(target) == cd.sha256_bytes(b"long")


def test_s31_sandbox_unavailable_or_gated(env, capsys):
    env.gradle()
    scenario = {**exec_actions(write_action("src/a.txt")), "sandbox": {"canary": "git_writable"}}
    code, _, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 6 and rep["status"]["TESTS"] == "NOT_RUN" and rep["tests"]["reason"] == "SANDBOX_UNAVAILABLE"
    assert not [c for c in env.calls() if c["mode"] == "sandbox" and not c["canary"]]
    env.settle()
    code, _, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), exec_actions(write_action("x/z.txt")), capsys)
    assert code == 5 and rep["status"]["TESTS"] == "NOT_RUN" and rep["tests"]["reason"] == "GATED"


def test_s31b_gradle_distribution_unresolved(env, capsys, monkeypatch):
    def unresolved(url):
        raise cd.RunnerError("GRADLE_DISTRIBUTION_UNRESOLVED", "0 candidates")
    monkeypatch.setattr(cd, "resolve_gradle_distribution", unresolved)
    code, _, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), exec_actions(write_action("src/a.txt")), capsys)
    assert code == 6 and rep["tests"]["reason"] == "SANDBOX_UNAVAILABLE"


def test_s38_test_modifies_source(env, capsys):
    env.gradle()
    scenario = {**exec_actions(write_action("src/a.txt")),
                "sandbox": {"gradle": {"exit": 0, "actions": [write_action("src/from-test.txt")]}}}
    code, _, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 8 and rep["status"]["TESTS"] == "INCOMPLETE"
    assert rep["tests"]["introduced_by_tests"] == ["src/from-test.txt"]


def test_s43_sandbox_spawn_failure(env, capsys):
    env.gradle()
    scenario = {**exec_actions(write_action("src/a.txt")), "sandbox": {"gradle": {"spawn_fail": True}}}
    code, _, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 8 and rep["status"]["TESTS"] == "INCOMPLETE"


# ----------------------------------------------------------------------------------------- review S32, S40

def review_contract(env, subject):
    return env.contract(mode="review", subject_run_id=subject, allowed_paths=[])


def test_s32_review_gets_full_untracked_content(env, capsys):
    payload = "line one\nline two with content\n"
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/new.txt", payload)), capsys)
    assert code == 0
    subject = out["run_id"]
    code, out, rep = env.run(review_contract(env, subject), {}, capsys, prompt="Review the change.")
    assert code == 0 and rep["status"]["REVIEW"] == "CLEAN" and rep["status"]["TESTS"] == "NOT_APPLICABLE"
    bundle = Path(out["run_dir"]) / "review-input"
    assert (bundle / "untracked/src/new.txt").read_text(encoding="utf-8") == payload
    assert json.loads((bundle / "subject_tests.json").read_text())["status"] == "NOT_APPLICABLE"
    assert "INFORMATIONAL" in (bundle / "implementer-final.txt").read_text()
    exec_call = [c for c in env.calls() if c["mode"] == "exec"][-1]
    assert "read-only" in exec_call["argv"] and "MAZOVIA CODEX REVIEW POLICY" in exec_call["prompt_head"]


def test_s32b_review_findings_exit_7(env, capsys):
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    finding = {"mode": "review", "task_status": "COMPLETED", "verdict": "FINDINGS", "summary": "s", "notes": "",
               "open_decisions": [],
               "findings": [{"severity": "MEDIUM", "path": "src/a.txt", "line": 1, "summary": "x", "evidence": "y"}]}
    code, _, rep = env.run(review_contract(env, out["run_id"]), exec_actions(final=finding), capsys)
    assert code == 7 and rep["status"]["REVIEW"] == "FINDINGS"


def test_s40_subject_state_mismatch(env, capsys):
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    write(env.repo / "src/a.txt", "moved on\n")
    code, _, rep = env.run(review_contract(env, out["run_id"]), {}, capsys)
    assert code == 2 and rep["precondition_failure"] == "SUBJECT_STATE_MISMATCH"


# ----------------------------------------------------------------------------------------- S33-S37, S39 policy

def test_s33_no_recovery_commands(env, capsys):
    env.run(env.contract(), exec_actions(write_action("other/b.txt")), capsys)
    env.run(env.contract(timeout_seconds=60), exec_actions(exit=1), capsys)
    forbidden = {"reset", "restore", "clean", "checkout", "switch", "commit", "push", "add", "rm", "mv"}
    assert cd.GIT_CALLS and not [c for c in cd.GIT_CALLS if c[0] in forbidden]
    assert all(c[1:2] == ["list"] for c in cd.GIT_CALLS if c[0] in ("stash", "worktree"))
    with pytest.raises(AssertionError):
        cd.git(env.repo, "stash", "push")


@pytest.mark.parametrize("raw,expected", [("A\\B", "A/B"), ("./src/**", "src/**"), ("src/*.kt", "src/*.kt")])
def test_s34_normalization_accepts(raw, expected):
    assert cd.canonical_pattern(raw) == expected


@pytest.mark.parametrize("raw", ["../x", "C:\\x", "C:x", "\\\\srv\\share", "/abs", "a//b", "a/./b", "a/../b", "a\x00b",
                                 "a/.git/b", ".GIT/x", "a/**b", "a?b", "a/[b]", "{a,b}", "!a", "a/b "])
def test_s34_normalization_rejects(raw):
    with pytest.raises(ValueError):
        cd.canonical_pattern(raw)


def test_s34_glob_semantics_and_case():
    assert cd.glob_match("src/**", "SRC/A/b.txt") and cd.glob_match("**/AGENTS.md", "agents.md")
    assert cd.glob_match("src/*.kt", "src/A.KT") and not cd.glob_match("src/*.kt", "src/x/A.kt")
    assert cd.glob_match("a/**/b", "a/b") and cd.glob_match("a/**/b", "a/x/y/b")
    assert cd.classify(".git/config", ["**"], []) == "POLICY_PROTECTED"
    assert cd.classify("core/F.kt", ["core/**"], ["core/F.kt"]) == "FROZEN"
    assert cd.is_sensitive("config/Secrets.Properties") and cd.is_sensitive(".env.local")


def test_s35_artifact_tampering(env, capsys):
    tamper = {"op": "append", "path": "baseline.json", "in_run_dir": True}
    code, _, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt"), tamper), capsys)
    assert code == 8 and rep["artifacts"] == "TAMPERED"


def test_s36_generated_cache_policy(env, capsys):
    scenario = exec_actions(write_action("src/a.txt"), write_action("app/build/out.bin"),
                            write_action(".opencode/node_modules/x/index.js"))
    code, out, rep = env.run(env.contract(), scenario, capsys)
    assert code == 0
    post = json.loads((Path(out["run_dir"]) / "post-state.json").read_text())
    assert post["manifest"]["generated"]["files"] >= 2
    assert not [e for e in post["manifest"]["ignored"] if "build/" in e["path"] or "node_modules" in e["path"]]
    env.settle()
    code, _, rep = env.run(env.contract(), exec_actions(write_action("tools/build/keep.txt")), capsys)
    assert code == 5 and ("tools/build/keep.txt", ("OUT_OF_SCOPE",)) in violations(rep)


def test_s37_ignored_sensitive_and_protected(env, capsys):
    code, _, rep = env.run(env.contract(), exec_actions(write_action("local.properties", SECRET)), capsys)
    assert code == 5 and ("local.properties", ("SENSITIVE", "OUT_OF_SCOPE")) in violations(rep)
    sh(env.repo, "status")
    (env.repo / "local.properties").unlink()
    code, _, rep = env.run(env.contract(), exec_actions(write_action(".claude/settings.local.json", "{}")), capsys)
    assert code == 5 and (".claude/settings.local.json", ("POLICY_PROTECTED",)) in violations(rep)
    (env.repo / ".claude/settings.local.json").unlink()
    code, _, rep = env.run(env.contract(), exec_actions(write_action("debug.log", "log")), capsys)
    assert code == 5 and ("debug.log", ("OUT_OF_SCOPE",)) in violations(rep)


def test_s39_policy_protected_mutation(env, capsys):
    code, _, rep = env.run(env.contract(allowed_paths=["**"]), exec_actions(write_action(".gitignore", "x\n")), capsys)
    assert code == 5 and (".gitignore", ("POLICY_PROTECTED",)) in violations(rep)


def test_s39b_wrapper_is_policy_protected(env, capsys):
    code, _, rep = env.run(env.contract(allowed_paths=["**"]),
                           exec_actions(write_action("gradle/wrapper/gradle-wrapper.properties", "x=1\n")), capsys)
    assert code == 5 and ("gradle/wrapper/gradle-wrapper.properties", ("POLICY_PROTECTED",)) in violations(rep)


# ----------------------------------------------------------------------------------------- S41-S46 inputs / identity

@pytest.mark.parametrize("argv,error", [(["python", "-m", "pytest"], "EXECUTABLE_NOT_SANDBOX_VERIFIED"),
                                        (["gradlew.bat", ":a:test", "-Dx=1"], "REQUIRED_TEST_INVALID"),
                                        (["gradlew.bat", "--init-script", "x.gradle"], "REQUIRED_TEST_INVALID"),
                                        (["gradlew.bat", ":a:test", "--offline"], "REQUIRED_TEST_INVALID"),
                                        (["gradlew.bat", ":a:test&calc"], "REQUIRED_TEST_INVALID"),
                                        (["cmd.exe", "/c", "echo"], "EXECUTABLE_NOT_SANDBOX_VERIFIED")])
def test_s41_invalid_test_argv(env, capsys, argv, error):
    test = {**GRADLE_TEST, "argv": argv}
    code, out, _ = env.run(env.contract(required_tests=[test]), None, capsys)
    assert code == 2 and out["error"] == error


def test_s42_codex_version_mismatch(env, capsys):
    Path(str(env.repo) + ".version").write_text("0.156.0")
    code, out, _ = env.run(env.contract(), None, capsys)
    assert code == 2 and out["error"] == "CODEX_VERSION_UNVALIDATED"


def test_s44_override_outside_temp_rejected(env, capsys, monkeypatch):
    elsewhere = env.tmp / "not-temp-root"
    elsewhere.mkdir()
    monkeypatch.setattr(cd.tempfile, "gettempdir", lambda: str(elsewhere))
    code, out, _ = env.run(env.contract(), None, capsys)
    assert code == 2 and out["error"] == "OVERRIDE_REFUSED"


def test_s45_mode_parent_subject_mismatch(env, capsys):
    c = env.contract()
    code, out, _ = env.run({**c, "mode": "implement"}, None, capsys, extra=("--parent-run-id", "20260101T000000Z-TA-TEST-implement-00000000"))
    assert code == 2 and out["error"] == "PARENT_MISMATCH"
    code, out, _ = env.run(env.contract(subject_run_id="20260101T000000Z-TA-TEST-implement-00000000"), None, capsys)
    assert code == 2 and out["error"] == "CONTRACT_INVALID"
    cpath = env.inputs / "mismatch.json"
    cpath.write_text(json.dumps(env.contract()))
    ppath = env.inputs / "mismatch.txt"
    ppath.write_text("x")
    code = cd.main(["review", "--contract", str(cpath), "--prompt", str(ppath), "--codex-exe-override", STUB])
    assert code == 2 and json.loads(capsys.readouterr().out)["error"] == "MODE_MISMATCH"


def test_s46_second_corrective_rejected(env, capsys):
    parent = _parent(env, capsys)
    code, _, _ = env.run(env.contract(parent_run_id=parent), exec_actions(write_action("src/c.txt")), capsys,
                         extra=("--parent-run-id", parent))
    assert code == 0
    code, _, rep = env.run(env.contract(parent_run_id=parent), exec_actions(), capsys, extra=("--parent-run-id", parent))
    assert code == 2


# ----------------------------------------------------------------------------------------- S47-S53 amendments

def test_s47_sanitizer_drops_secret_like_variables(env, capsys, monkeypatch):
    for name in ("FAKE_API_TOKEN", "MY_PASSWORD", "AWS_ACCESS_KEY_ID", "GITHUB_TOKEN", "SESSION_COOKIE", "RANDOM_VAR"):
        monkeypatch.setenv(name, SECRET)
    code, out, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    assert code == 0
    exec_call = [c for c in env.calls() if c["mode"] == "exec"][-1]
    upper = {n.upper() for n in exec_call["env_names"]}
    assert not upper & {"FAKE_API_TOKEN", "MY_PASSWORD", "AWS_ACCESS_KEY_ID", "GITHUB_TOKEN", "SESSION_COOKIE", "RANDOM_VAR"}
    assert {"FAKE_API_TOKEN", "MY_PASSWORD", "RANDOM_VAR"} <= set(rep["environment"]["codex"]["dropped"])
    assert SECRET.encode() not in all_run_bytes(Path(out["run_dir"]))


def test_s47_sanitizer_unit():
    source = {"Path": "C:\\bin", "SystemRoot": "C:\\Windows", "JAVA_HOME": "C:\\jdk", "OPENAI_API_KEY": "k",
              "SOME_AUTH_HEADER": "h", "NPM_CONFIG_X": "n", "EDITOR": "vim"}
    envd, report = cd.sanitize_environment(source, {"TEMP": "C:\\run\\tmp"})
    assert envd == {"Path": "C:\\bin", "SystemRoot": "C:\\Windows", "JAVA_HOME": "C:\\jdk", "TEMP": "C:\\run\\tmp"}
    assert set(report["dropped"]) == {"OPENAI_API_KEY", "SOME_AUTH_HEADER", "NPM_CONFIG_X", "EDITOR"}


def test_s48_runner_overrides_survive(env, capsys, monkeypatch):
    monkeypatch.setenv("TEMP", "C:\\user-temp")
    monkeypatch.setenv("GRADLE_USER_HOME", str(env.tmp / "user-gradle"))
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    values = [c for c in env.calls() if c["mode"] == "exec"][-1]["env_values"]
    run_dir = Path(out["run_dir"])
    assert Path(values["TEMP"]) == run_dir / "codex-tmp" and Path(values["TMP"]) == run_dir / "codex-tmp"
    assert Path(values["GRADLE_USER_HOME"]) == run_dir / "codex-tmp" / "gradle-home"
    assert Path(values["ANDROID_USER_HOME"]) == run_dir / "codex-tmp" / "android"
    assert values["MAZOVIA_CODEX_RUN_ID"] == out["run_id"]
    envd, _ = cd.sanitize_environment({"TEMP": "C:\\x", "Path": "p"}, {"TEMP": "C:\\run"})
    assert envd["TEMP"] == "C:\\run"


def test_s49_contract_cannot_inject_env(env, capsys):
    for bad in ({"EVIL": "1"}, {"PYTHONHASHSEED": "1"}, {"PATH": "C:\\evil"}):
        code, out, _ = env.run(env.contract(required_tests=[{**GRADLE_TEST, "env": bad}]), None, capsys)
        assert code == 2
    with pytest.raises(cd.RunnerError):
        cd.sanitize_environment({}, {}, {"PATH": "x"})


def test_s50_ignored_sensitive_content_never_copied(env, capsys):
    code, out, rep = env.run(env.contract(), exec_actions(write_action(".env", SECRET)), capsys)
    assert code == 5 and (".env", ("SENSITIVE", "OUT_OF_SCOPE")) in violations(rep)
    impl_dir = Path(out["run_dir"])
    code, out, rep = env.run(review_contract(env, rep["run_id"]), {}, capsys)
    review_dir = Path(out["run_dir"])
    index = json.loads((review_dir / "review-input/bundle-index.json").read_text())
    assert [s["path"] for s in index["sensitive_files"]] == [".env"]
    assert SECRET.encode() not in all_run_bytes(impl_dir) and SECRET.encode() not in all_run_bytes(review_dir)


def test_s51_non_ignored_untracked_sensitive_never_copied(env, capsys):
    code, out, rep = env.run(env.contract(allowed_paths=["config/**"]),
                             exec_actions(write_action("config/secrets.properties", SECRET)), capsys)
    assert code == 5 and ("config/secrets.properties", ("SENSITIVE",)) in violations(rep)
    code, out2, rep2 = env.run(review_contract(env, out["run_id"]), {}, capsys)
    review_dir = Path(out2["run_dir"])
    assert not (review_dir / "review-input/untracked/config/secrets.properties").exists()
    index = json.loads((review_dir / "review-input/bundle-index.json").read_text())
    assert [s["path"] for s in index["sensitive_files"]] == ["config/secrets.properties"]
    assert SECRET.encode() not in all_run_bytes(review_dir) and SECRET.encode() not in all_run_bytes(Path(out["run_dir"]))


def test_s51b_tracked_sensitive_change_withheld_from_diffs(env, capsys):
    code, out, rep = env.run(env.contract(allowed_paths=["config/**", "src/**"]),
                             exec_actions(write_action("config/tracked.pem", SECRET), write_action("src/a.txt")), capsys)
    assert code == 5 and ("config/tracked.pem", ("SENSITIVE",)) in violations(rep)
    code, out2, _ = env.run(review_contract(env, out["run_id"]), {}, capsys)
    review_dir = Path(out2["run_dir"])
    assert b"src/a.txt" in (review_dir / "review-input/diff-head.patch").read_bytes()
    assert SECRET.encode() not in all_run_bytes(review_dir)
    index = json.loads((review_dir / "review-input/bundle-index.json").read_text())
    assert "config/tracked.pem" in [s["path"] for s in index["sensitive_files"]]


def test_s52_non_ignored_sensitive_change_fails_scope(env, capsys):
    code, _, rep = env.run(env.contract(allowed_paths=["src/**"]), exec_actions(write_action("src/keys/app.pem", "k")), capsys)
    assert code == 5 and ("src/keys/app.pem", ("SENSITIVE",)) in violations(rep)


def test_s53_no_runner_artifacts_in_repository(env, capsys):
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    assert code == 0
    assert sh(env.repo, "status", "--porcelain=v1", "--untracked-files=all", "--ignored").splitlines() == [" M src/a.txt"]
    run_dir = Path(out["run_dir"])
    assert not cd.is_within(run_dir, env.repo)
    names = {"report.json", "baseline.json", "events.jsonl", "final.txt", "post-state.json", "codex-argv.json"}
    assert not [p for p in env.repo.rglob("*") if p.name in names and ".git" not in p.parts]


# ----------------------------------------------------------------------------------------- task_status (corrective)

STAGING_OR_CLAUDE_ROOT = Path(__file__).resolve().parents[2]  # staging root, or .claude after installation


def implement_response(status, **kw):
    base = {"mode": "implement", "task_status": status, "verdict": "NOT_APPLICABLE", "summary": "s", "findings": [],
            "notes": "", "open_decisions": []}
    if status == "OPEN_DECISION":
        base["open_decisions"] = [{"kind": "POLICY-PROTECTED PATH", "path": ".gitignore", "reason": "r",
                                   "proposed_change": "p", "alternatives": "a"}]
    base.update(kw)
    return base


def test_task_completed_returns_exit_0(env, capsys):
    code, out, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt"), final=implement_response("COMPLETED")), capsys)
    assert code == 0 and rep["task_status"] == "COMPLETED" and rep["wrapper_exit_code"] == 0
    assert out["outcome"] == {"execution": "SUCCESS", "response": "PRESENT", "task_status": "COMPLETED", "wrapper_exit_code": 0}


@pytest.mark.parametrize("status", ["BLOCKED", "OPEN_DECISION"])
def test_task_not_completed_never_exit_0(env, capsys, status):
    code, out, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt", "partial work\n"),
                                                          final=implement_response(status)), capsys)
    assert code == cd.EXIT_TASK_NOT_COMPLETED == 9 and code != 0
    assert rep["status"]["RESPONSE"] == "PRESENT" and rep["status"]["EXECUTION"] == "SUCCESS"
    assert rep["task_status"] == status and rep["outcome"]["task_status"] == status and rep["wrapper_exit_code"] == 9
    assert rep["status"]["REVIEW"] == "NOT_APPLICABLE" and rep["status"]["SCOPE"] == "PASS"
    # no revert / delete / clean: the partial work and every artifact are preserved
    assert (env.repo / "src/a.txt").read_text() == "partial work\n"
    assert sh(env.repo, "status", "--porcelain=v1").splitlines() == [" M src/a.txt"]
    run_dir = Path(out["run_dir"])
    for name in ("contract.json", "prompt.txt", "prompt-effective.txt", "baseline.json", "codex-argv.json",
                 "events.jsonl", "stderr.log", "final.txt", "post-state.json", "report.json"):
        assert (run_dir / name).is_file(), name
    assert json.loads((run_dir / "final.txt").read_text())["task_status"] == status
    assert not [c for c in cd.GIT_CALLS if c[0] in ("reset", "restore", "clean", "stash", "checkout") and c[1:2] != ["list"]]
    assert cd.main(["show", "--run-id", out["run_id"]]) == 0
    shown = json.loads(capsys.readouterr().out)
    assert shown["task_status"] == status and shown["wrapper_exit_code"] == 9 and shown["outcome"]["response"] == "PRESENT"


@pytest.mark.parametrize("status", ["BLOCKED", "OPEN_DECISION"])
def test_task_not_completed_gates_tests_and_beats_exit_6(env, capsys, status):
    env.gradle()
    scenario = {**exec_actions(write_action("src/a.txt"), final=implement_response(status)),
                "sandbox": {"gradle": {"exit": 0, "junit": {"tests": 1, "failures": 0}}}}
    code, _, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 9 and rep["status"]["TESTS"] == "NOT_RUN" and rep["tests"]["reason"] == "GATED_TASK_NOT_COMPLETED"
    assert not [c for c in env.calls() if c["mode"] == "sandbox"]


@pytest.mark.parametrize("response", [
    implement_response("OPEN_DECISION", open_decisions=[]),
    implement_response("COMPLETED", open_decisions=implement_response("OPEN_DECISION")["open_decisions"]),
    implement_response("COMPLETED", verdict="CLEAN"),
    {k: v for k, v in implement_response("COMPLETED").items() if k != "task_status"},
    implement_response("DONE"),
])
def test_task_status_rule_violations_are_incomplete_response(env, capsys, response):
    code, _, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt"), final=response), capsys)
    assert code == 3 and rep["status"]["RESPONSE"] == "INCOMPLETE" and rep["task_status"] is None


@pytest.mark.parametrize("status", ["BLOCKED", "OPEN_DECISION"])
def test_review_not_completed_is_exit_9_not_findings(env, capsys, status):
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    code, _, rep = env.run(review_contract(env, out["run_id"]), exec_actions(final={"_stub_task_status": status}), capsys)
    assert code == 9 and rep["status"]["REVIEW"] == "INCOMPLETE" and rep["task_status"] == status
    assert rep["status"]["RESPONSE"] == "PRESENT"


def test_review_completed_clean_exit_0(env, capsys):
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    code, _, rep = env.run(review_contract(env, out["run_id"]), exec_actions(final={"_stub_task_status": "COMPLETED"}), capsys)
    assert code == 0 and rep["status"]["REVIEW"] == "CLEAN" and rep["task_status"] == "COMPLETED"


def test_exit_0_only_for_mechanically_completed_success():
    """Exhaustive: every status combination; exit 0 implies the full success conjunction for its mode."""
    import itertools

    class Fake:
        tampered = evidence_failure = False

    statuses = {"EXECUTION": ["SUCCESS", "FAILED", "TIMEOUT", "INCOMPLETE"], "RESPONSE": ["PRESENT", "MISSING", "INCOMPLETE"],
                "SCOPE": ["PASS", "FAIL", "INCOMPLETE"], "FROZEN": ["PASS", "FAIL", "INCOMPLETE"],
                "HEAD": ["UNCHANGED", "CHANGED", "INCOMPLETE"],
                "TESTS": ["PASS", "FAIL", "NOT_RUN", "INCOMPLETE", "NOT_APPLICABLE"],
                "REVIEW": ["CLEAN", "FINDINGS", "INCOMPLETE", "NOT_APPLICABLE"]}
    zeros = 0
    for mode in ("implement", "review"):
        for combo in itertools.product(*statuses.values()):
            for task in (None, *cd.TASK_STATUSES):
                run = Fake()
                run.mode, run.task_status, run.status = mode, task, dict(zip(statuses, combo))
                code = cd.compute_exit(run)
                if task in ("BLOCKED", "OPEN_DECISION"):
                    assert code != 0
                if code == 0:
                    zeros += 1
                    s = run.status
                    assert (s["EXECUTION"], s["RESPONSE"], task, s["SCOPE"], s["FROZEN"], s["HEAD"]) == \
                        ("SUCCESS", "PRESENT", "COMPLETED", "PASS", "PASS", "UNCHANGED")
                    if mode == "implement":
                        assert s["TESTS"] in ("PASS", "NOT_APPLICABLE")
                    else:
                        assert s["REVIEW"] == "CLEAN" and s["TESTS"] != "INCOMPLETE"
    assert zeros > 0


def test_skills_surface_task_status_and_exit_9():
    for skill in ("codex-implement", "codex-review"):
        text = (STAGING_OR_CLAUDE_ROOT / "skills" / skill / "SKILL.md").read_text(encoding="utf-8")
        for needle in ("task_status", "COMPLETED", "BLOCKED", "OPEN_DECISION", "| 9 |", "wrapper_exit_code"):
            assert needle in text, (skill, needle)
    doc = cd.__doc__
    assert "9  task not completed" in doc and "1 > 8 > 4 > 5 > 3 > 9 > 6 > 7 > 0" in doc
    schema = cd.load_schema("codex_response.schema.json")
    assert "task_status" in schema["required"] and schema["properties"]["task_status"]["enum"] == list(cd.TASK_STATUSES)


def test_show_is_read_only(env, capsys):
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    before = cd.artifact_hashes(Path(out["run_dir"]))
    assert cd.main(["show", "--run-id", out["run_id"]]) == 0
    assert json.loads(capsys.readouterr().out)["exit_code"] == 0
    assert cd.artifact_hashes(Path(out["run_dir"])) == before


# ----------------------------------------------------------------------------------------- review corrective C1-C6

def _marker_cmd(marker: Path, tail: str) -> str:
    """Shell command git runs for a helper: records the call in `marker`, then behaves like `tail`."""
    return f"echo hit >> '{marker.as_posix()}'; {tail}"


def test_c1_git_environment_drops_inherited_git_variables(monkeypatch):
    injected = {"GIT_EXTERNAL_DIFF": "evil", "GIT_DIR": "C:/elsewhere", "GIT_WORK_TREE": "C:/elsewhere",
                "GIT_CONFIG_PARAMETERS": "'diff.external'='evil'", "GIT_CONFIG_COUNT": "1",
                "GIT_CONFIG_KEY_0": "diff.external", "GIT_CONFIG_VALUE_0": "evil", "GIT_PAGER": "evil"}
    for name, value in injected.items():
        monkeypatch.setenv(name, value)
    envd = cd.git_environment(extra_config=[("filter.x.clean", "")])
    config = list(cd.GIT_SAFE_CONFIG) + [("filter.x.clean", "")]
    assert int(envd["GIT_CONFIG_COUNT"]) == len(config)
    assert [(envd[f"GIT_CONFIG_KEY_{i}"], envd[f"GIT_CONFIG_VALUE_{i}"]) for i in range(len(config))] == config
    expected_git = {"GIT_CONFIG_COUNT", "GIT_TERMINAL_PROMPT"} | \
        {f"GIT_CONFIG_{kind}_{i}" for kind in ("KEY", "VALUE") for i in range(len(config))}
    assert {k.upper() for k in envd if k.upper().startswith("GIT_")} == expected_git
    assert "evil" not in envd.values()


def test_c1_evidence_generation_never_runs_git_helpers(env, capsys):
    markers = {name: env.tmp / f"helper-{name}.txt" for name in ("ext", "command", "textconv", "clean")}
    write(env.repo / "src/b.kt", "b\n")  # no diff driver: GIT_EXTERNAL_DIFF applies (a driver command would win)
    sh(env.repo, "add", "src/b.kt")
    sh(env.repo, "commit", "-q", "-m", "b")
    info = env.repo / ".git" / "info"
    info.mkdir(exist_ok=True)
    (info / "attributes").write_text("*.txt diff=evil filter=evil\n*.md filter=evil\n", encoding="utf-8")
    sh(env.repo, "config", "diff.evil.command", _marker_cmd(markers["command"], "true"))
    sh(env.repo, "config", "diff.evil.textconv", _marker_cmd(markers["textconv"], "cat"))
    sh(env.repo, "config", "filter.evil.clean", _marker_cmd(markers["clean"], "cat"))
    sh(env.repo, "config", "filter.evil.smudge", "cat")
    external = {**os.environ, "GIT_EXTERNAL_DIFF": _marker_cmd(markers["ext"], "true")}

    # Positive control: every configured helper really runs under plain git in this fixture.
    write(env.repo / "src/a.txt", "control\n")
    write(env.repo / "src/b.kt", "control\n")
    for argv, run_env in ((["diff", "--", "src/b.kt"], external), (["diff", "--", "src/a.txt"], dict(os.environ)),
                          (["diff", "--no-ext-diff"], dict(os.environ)),
                          (["hash-object", "docs/terrain-ahead/AUDIT.md"], dict(os.environ))):
        subprocess.run(["git", *argv], cwd=env.repo, env=run_env, check=True, capture_output=True)
    assert all(m.is_file() for m in markers.values()), {n: m.is_file() for n, m in markers.items()}
    for marker in markers.values():
        marker.unlink()
    write(env.repo / "src/a.txt", "a\n")
    write(env.repo / "src/b.kt", "b\n")

    env.mp.setenv("GIT_EXTERNAL_DIFF", external["GIT_EXTERNAL_DIFF"])
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt", "changed\n"),
                                                        write_action("src/b.kt", "changed-kt\n")), capsys)
    assert code == 0
    cd.GIT_CALLS.clear()
    code, out2, rep = env.run(review_contract(env, out["run_id"]), {}, capsys)
    assert code == 0 and rep["status"]["REVIEW"] == "CLEAN"
    assert not [n for n, m in markers.items() if m.exists()], "a git helper ran during evidence generation"
    patch = (Path(out2["run_dir"]) / "review-input/diff-head.patch").read_text(encoding="utf-8")
    assert "-a\n" in patch and "+changed\n" in patch and "a/src/a.txt" in patch
    assert "-b\n" in patch and "+changed-kt\n" in patch and "a/src/b.kt" in patch
    diffs = [c for c in cd.GIT_CALLS if c[0] == "diff"]
    assert diffs and all({"--no-ext-diff", "--no-textconv", "--no-color"} <= set(c) for c in diffs)


def test_c1_textconv_never_runs_in_evidence_diffs(env, capsys):
    marker = env.tmp / "helper-textconv-only.txt"
    info = env.repo / ".git" / "info"
    info.mkdir(exist_ok=True)
    (info / "attributes").write_text("*.txt diff=conv\n", encoding="utf-8")
    sh(env.repo, "config", "diff.conv.textconv", _marker_cmd(marker, "sed s/^/CONV:/"))
    write(env.repo / "src/a.txt", "control\n")
    subprocess.run(["git", "diff"], cwd=env.repo, check=True, capture_output=True)  # positive control
    assert marker.is_file()
    marker.unlink()
    write(env.repo / "src/a.txt", "a\n")
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt", "changed\n")), capsys)
    code, out2, _ = env.run(review_contract(env, out["run_id"]), {}, capsys)
    assert code == 0 and not marker.exists()
    bundle = Path(out2["run_dir"]) / "review-input"
    for name in ("diff-head.patch", "diff-staged.patch", "diff-unstaged.patch"):
        assert "CONV:" not in (bundle / name).read_text(encoding="utf-8")
    assert "+changed\n" in (bundle / "diff-head.patch").read_text(encoding="utf-8")


def test_c2_ignored_frozen_file_is_represented_and_fails_frozen(env, capsys):
    code, out, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt"),
                                                          write_action("frozendir/build/Gen.kt")), capsys)
    assert code == 5 and rep["status"]["FROZEN"] == "FAIL" and "frozendir/build/Gen.kt" in rep["scope"]["frozen_hits"]
    post = json.loads((Path(out["run_dir"]) / "post-state.json").read_text())
    assert "frozendir/build/Gen.kt" in [e["path"] for e in post["manifest"]["ignored"]]
    manifest = cd.capture_manifest(env.repo, cd.parse_frozen(FROZEN.encode()))
    assert "frozendir/build/Gen.kt" in [e["path"] for e in manifest["ignored"]]
    assert "frozendir/build/Gen.kt" not in [e["path"] for e in cd.capture_manifest(env.repo, [".claude/**"])["ignored"]]


def test_c2_ignored_policy_protected_file_is_represented_and_fails_scope(env, capsys):
    code, out, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt"),
                                                          write_action(".claude/hooks/build/hook.js")), capsys)
    assert code == 5 and (".claude/hooks/build/hook.js", ("POLICY_PROTECTED",)) in violations(rep)
    post = json.loads((Path(out["run_dir"]) / "post-state.json").read_text())
    assert ".claude/hooks/build/hook.js" in [e["path"] for e in post["manifest"]["ignored"]]
    # ordinary ignored generated output is still omitted
    assert not [e for e in post["manifest"]["ignored"] if e["path"].startswith("app/build/")]


def test_c3_canary_failure_termination_confirmed_keeps_evidence(env, capsys):
    env.gradle()
    scenario = {**exec_actions(write_action("src/a.txt")), "sandbox": {"canary": "git_writable"}}
    code, out, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 6 and rep["status"]["TESTS"] == "NOT_RUN" and rep["tests"]["reason"] == "SANDBOX_UNAVAILABLE"
    process = rep["tests"]["canary"]["process"]
    assert process["termination_confirmed"] is True and process["exit_code"] == 0
    assert rep["tests"]["sandbox_started"] is True and rep["tests"]["post_test_state_captured"] is True
    run_dir = Path(out["run_dir"])
    assert json.loads((run_dir / "post-test-state.json").read_text())["stable"] is True
    for name in ("canary.json", "canary.js", "canary.stdout.log", "canary.stderr.log", "tests.json"):
        assert name in rep["artifact_hashes"], name
    assert not [c for c in env.calls() if c["mode"] == "sandbox" and not c["canary"]]


def test_c3_canary_side_effect_captured_in_post_test_state(env, capsys):
    env.gradle()
    scenario = {**exec_actions(write_action("src/a.txt")),
                "sandbox": {"canary": "git_writable", "canary_actions": [write_action("src/canary-leak.txt")]}}
    code, out, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 8 and rep["status"]["TESTS"] == "INCOMPLETE"
    assert rep["tests"]["introduced_by_tests"] == ["src/canary-leak.txt"]
    post_test = json.loads((Path(out["run_dir"]) / "post-test-state.json").read_text())
    assert "src/canary-leak.txt" in [e["path"] for e in post_test["manifest"]["entries"]]


@pytest.mark.parametrize("canary", ["git_writable", "ok"])
def test_c3_canary_termination_unconfirmed_is_exit_8(env, capsys, monkeypatch, canary):
    env.gradle()
    real = cd.run_contained

    def unconfirmed_canary(argv, *a, **kw):
        result = real(argv, *a, **kw)
        if any(str(x).endswith("canary.js") for x in argv):
            result["termination_confirmed"] = False
        return result
    monkeypatch.setattr(cd, "run_contained", unconfirmed_canary)
    scenario = {**exec_actions(write_action("src/a.txt")), "sandbox": {"canary": canary}}
    code, out, rep = env.run(env.contract(required_tests=[GRADLE_TEST]), scenario, capsys)
    assert code == 8 and code != 6
    assert rep["status"]["TESTS"] == "INCOMPLETE" and rep["tests"]["reason"] == "CANARY_TERMINATION_UNCONFIRMED"
    assert rep["tests"]["canary"]["process"]["termination_confirmed"] is False
    assert rep["status"]["SCOPE"] == "INCOMPLETE" and rep["tests"]["post_test_state_captured"] is True
    run_dir = Path(out["run_dir"])
    assert json.loads((run_dir / "post-test-state.json").read_text())["stable"] is False
    assert "canary.json" in rep["artifact_hashes"] and (run_dir / "post-state.json").is_file()
    assert not [c for c in env.calls() if c["mode"] == "sandbox" and not c["canary"]]


def test_c3_job_query_failure_is_unconfirmed(tmp_path, monkeypatch):
    monkeypatch.setattr(cd.Job, "active", lambda self: (_ for _ in ()).throw(OSError(5, "QueryInformationJobObject")))
    result = cd.run_contained([sys.executable, "-c", "pass"], tmp_path, dict(os.environ), 60,
                              tmp_path / "o.log", tmp_path / "e.log")
    assert result["exit_code"] == 0 and result["termination_confirmed"] is False


def _staged_only_mode_change(repo: Path, rel: str) -> None:
    blob = sh(repo, "rev-parse", f":{rel}").strip()
    sh(repo, "update-index", "--cacheinfo", f"100755,{blob},{rel}")


def test_c4_manifest_keeps_index_mode_separately(env):
    write(env.repo / "src/a.txt", "staged\n")
    sh(env.repo, "add", "src/a.txt")
    (env.repo / "src/a.txt").unlink()
    before = cd.capture_manifest(env.repo, [])
    _staged_only_mode_change(env.repo, "src/a.txt")
    after = cd.capture_manifest(env.repo, [])
    [b] = [e for e in before["entries"] if e["path"] == "src/a.txt"]
    [a] = [e for e in after["entries"] if e["path"] == "src/a.txt"]
    assert (b["xy"], b["index_blob"], b["mode_worktree"]) == (a["xy"], a["index_blob"], a["mode_worktree"])
    assert (b["mode_head"], b["mode_index"], a["mode_index"]) == ("100644", "100644", "100755")
    assert not cd.manifest_equal(before, after)
    assert [c["path"] for c in cd.manifest_diff(before, after)] == ["src/a.txt"]


def test_c4_corrective_rejects_staged_only_mode_change(env, capsys):
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt", "parent\n"),
                                                        {"op": "git", "args": ["add", "src/a.txt"]},
                                                        {"op": "delete", "path": "src/a.txt"}), capsys)
    assert code == 0
    parent = out["run_id"]
    _staged_only_mode_change(env.repo, "src/a.txt")
    code, _, rep = env.run(env.contract(parent_run_id=parent), exec_actions(), capsys, extra=("--parent-run-id", parent))
    assert code == 2 and rep["precondition_failure"] == "PARENT_STATE_MISMATCH"
    code, _, rep = env.run(review_contract(env, parent), {}, capsys)
    assert code == 2 and rep["precondition_failure"] == "SUBJECT_STATE_MISMATCH"


def test_c4_review_evidence_shows_staged_mode_change(env, capsys):
    code, out, _ = env.run(env.contract(), exec_actions({"op": "git", "args": ["update-index", "--chmod=+x", "src/a.txt"]}),
                           capsys)
    assert code == 0
    code, out2, _ = env.run(review_contract(env, out["run_id"]), {}, capsys)
    bundle = Path(out2["run_dir"]) / "review-input"
    assert "new mode 100755" in (bundle / "diff-staged.patch").read_text(encoding="utf-8")
    state = json.loads((bundle / "post-state.json").read_text())
    [entry] = [e for e in state["manifest"]["entries"] if e["path"] == "src/a.txt"]
    assert (entry["mode_head"], entry["mode_index"]) == ("100644", "100755")


@pytest.mark.parametrize("schema,value", [({"const": 1}, True), ({"const": 0}, False), ({"const": True}, 1),
                                          ({"const": False}, 0), ({"enum": [1, 2]}, True), ({"enum": [0]}, False),
                                          ({"enum": [True]}, 1), ({"const": [1]}, [True]), ({"const": {"a": 0}}, {"a": False})])
def test_c5_bool_never_matches_number(schema, value):
    assert cd.validate_schema(value, schema)


@pytest.mark.parametrize("schema,value", [({"const": 1}, 1), ({"const": 1}, 1.0), ({"const": True}, True),
                                          ({"const": False}, False), ({"enum": [0, False]}, False), ({"enum": [0]}, 0),
                                          ({"const": {"a": [1, "x", None]}}, {"a": [1, "x", None]})])
def test_c5_json_equal_values_still_match(schema, value):
    assert cd.validate_schema(value, schema) == []


def test_c5_contract_schema_version_true_rejected(env, capsys):
    code, out, _ = env.run(env.contract(schema_version=True), None, capsys)
    assert code == 2 and out["error"] == "CONTRACT_INVALID"


REVIEW_FINDING = {"severity": "HIGH", "path": "src/a.txt", "line": 1, "summary": "informational", "evidence": "e"}


@pytest.mark.parametrize("status", ["BLOCKED", "OPEN_DECISION"])
def test_c6_uncompleted_review_findings_shown_as_informational(env, capsys, status):
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    response = {"mode": "review", "task_status": status, "verdict": "NOT_APPLICABLE", "summary": "s", "notes": "",
                "findings": [REVIEW_FINDING], "open_decisions": []}
    if status == "OPEN_DECISION":
        response["open_decisions"] = implement_response("OPEN_DECISION")["open_decisions"]
    code, out2, rep = env.run(review_contract(env, out["run_id"]), exec_actions(final=response), capsys)
    assert code == 9 and rep["status"]["REVIEW"] == "INCOMPLETE" and rep["status"]["RESPONSE"] == "PRESENT"
    assert rep["response_findings"] == {"verdict": "NOT_APPLICABLE", "findings": [REVIEW_FINDING],
                                        "findings_role": "INFORMATIONAL"}
    assert cd.main(["show", "--run-id", out2["run_id"]]) == 0
    shown = json.loads(capsys.readouterr().out)
    assert shown["findings"] == [REVIEW_FINDING] and shown["findings_role"] == "INFORMATIONAL"
    assert shown["task_status"] == status and shown["wrapper_exit_code"] == 9 and shown["status"]["REVIEW"] == "INCOMPLETE"


def test_c6_completed_review_findings_are_verdict(env, capsys):
    code, out, _ = env.run(env.contract(), exec_actions(write_action("src/a.txt")), capsys)
    response = {"mode": "review", "task_status": "COMPLETED", "verdict": "FINDINGS", "summary": "s", "notes": "",
                "findings": [REVIEW_FINDING], "open_decisions": []}
    code, out2, rep = env.run(review_contract(env, out["run_id"]), exec_actions(final=response), capsys)
    assert code == 7 and rep["status"]["REVIEW"] == "FINDINGS"
    assert cd.main(["show", "--run-id", out2["run_id"]]) == 0
    shown = json.loads(capsys.readouterr().out)
    assert shown["findings"] == [REVIEW_FINDING] and shown["findings_role"] == "VERDICT"


def test_c6_invalid_response_findings_not_shown(env, capsys):
    bad = {"mode": "implement", "task_status": "OPEN_DECISION", "verdict": "NOT_APPLICABLE", "summary": "s", "notes": "",
           "findings": [REVIEW_FINDING], "open_decisions": []}
    code, out, rep = env.run(env.contract(), exec_actions(write_action("src/a.txt"), final=bad), capsys)
    assert code == 3 and rep["response_findings"]["findings"] is None
