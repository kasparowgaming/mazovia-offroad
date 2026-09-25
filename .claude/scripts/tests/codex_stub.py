#!/usr/bin/env python3
"""Controlled Codex CLI stand-in for codex_delegate tests. TEST HARNESS ONLY.

The runner accepts it only through --codex-exe-override for repositories under the system temp directory.
Behaviour comes from `<repo>.scenario.json` (a sibling of the repository, never inside it); every invocation is
logged to `<repo>.calls.jsonl` with environment NAMES and a few runner-set values.
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

LOGGED_ENV_VALUES = ("TEMP", "TMP", "GRADLE_USER_HOME", "GRADLE_RO_DEP_CACHE", "ANDROID_USER_HOME",
                     "MAZOVIA_CODEX_RUN_ID", "PYTHONHASHSEED")
FEATURES = ("apps", "browser_use", "browser_use_external", "computer_use", "hooks", "image_generation",
            "multi_agent", "memories", "goals", "plugins", "fast_mode")


def option(argv, name):
    return argv[argv.index(name) + 1] if name in argv else None


def scenario_for(repo):
    path = Path(str(repo) + ".scenario.json")
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}


def log_call(repo, argv, extra=None):
    if repo is None:
        return
    entry = {"argv": argv, "env_names": sorted(os.environ),
             "env_values": {k: os.environ[k] for k in LOGGED_ENV_VALUES if k in os.environ}}
    entry.update(extra or {})
    with open(str(repo) + ".calls.jsonl", "a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry) + "\n")


def heartbeat(path):
    code = f"import time\nwhile True:\n    open({str(path)!r}, 'a').write('.')\n    time.sleep(0.2)\n"
    flags = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP (no breakaway)
    subprocess.Popen([sys.executable, "-c", code], creationflags=flags, stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def apply_actions(actions, repo, run_dir):
    for action in actions:
        op = action["op"]
        base = Path(run_dir) if action.get("in_run_dir") else Path(repo)
        if op == "write":
            target = base / action["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(action.get("content", "stub\n").encode("utf-8"))
        elif op == "append":
            with open(base / action["path"], "ab") as fh:
                fh.write(action.get("content", "tamper\n").encode("utf-8"))
        elif op == "delete":
            (base / action["path"]).unlink()
        elif op == "rename":
            (base / action["dst"]).parent.mkdir(parents=True, exist_ok=True)
            os.replace(base / action["src"], base / action["dst"])
        elif op == "git":
            subprocess.run(["git", "-c", "user.name=stub", "-c", "user.email=stub@example.invalid", *action["args"]],
                           cwd=repo, check=True, capture_output=True)
        elif op == "heartbeat":
            heartbeat(action["path"])
        elif op == "sleep":
            time.sleep(action["seconds"])
        elif op == "hang":
            while True:
                time.sleep(1)
        else:
            raise SystemExit(f"unknown stub op {op}")


def cmd_exec(argv):
    repo, final = option(argv, "-C"), option(argv, "-o")
    prompt = sys.stdin.read()
    spec = scenario_for(repo).get("exec", {})
    log_call(repo, argv, {"mode": "exec", "prompt_chars": len(prompt), "prompt_head": prompt[:200]})
    print(json.dumps({"type": "thread.started", "stub": True}), flush=True)
    apply_actions(spec.get("actions", []), repo, Path(final).parent)
    print(json.dumps({"type": "turn.completed", "stub": True}), flush=True)
    response = spec.get("final", "default")
    if response == "default" or isinstance(response, dict) and response.get("_stub_task_status"):
        mode = "review" if "read-only" in argv else "implement"
        status = response.get("_stub_task_status", "COMPLETED") if isinstance(response, dict) else "COMPLETED"
        response = {"mode": mode, "task_status": status,
                    "verdict": "CLEAN" if mode == "review" and status == "COMPLETED" else "NOT_APPLICABLE",
                    "summary": "stub", "findings": [], "notes": "",
                    "open_decisions": [] if status != "OPEN_DECISION" else [
                        {"kind": "FROZEN CLASS EXCEPTION", "path": "core/F.kt", "reason": "stub needs a frozen change",
                         "proposed_change": "stub proposal", "alternatives": "none"}]}
    if response is not None:
        Path(final).write_text(response if isinstance(response, str) else json.dumps(response), encoding="utf-8")
    return spec.get("exit", 0)


def cmd_sandbox(argv):
    repo = option(argv, "-C")
    command = argv[argv.index("--") + 1:]
    spec = scenario_for(repo).get("sandbox", {})
    is_canary = any(str(a).endswith("canary.js") for a in command)
    log_call(repo, argv, {"mode": "sandbox", "canary": is_canary})
    if is_canary:
        outcome = {"temp_write": "OK", "git_write": "DENIED:EPERM", "sibling_write": "DENIED:EPERM",
                   "userprofile_write": "DENIED:EPERM", "network": "DENIED:EACCES"}
        if spec.get("canary") == "git_writable":
            outcome["git_write"] = "OK"
        apply_actions(spec.get("canary_actions", []), repo, None)  # side effects of the sandboxed canary
        print(json.dumps(outcome))
        return 0
    gradle = spec.get("gradle", {})
    if gradle.get("spawn_fail"):
        print("windows sandbox failed: runner failed during SpawnChild: CreateProcessAsUserW failed: 2", file=sys.stderr)
        return 1
    apply_actions(gradle.get("actions", []), repo, None)
    junit = gradle.get("junit")
    if junit:
        results = Path(repo) / "app" / "build" / "test-results" / "testDebugUnitTest"
        results.mkdir(parents=True, exist_ok=True)
        (results / "TEST-stub.xml").write_text(
            "<testsuite name=\"stub\"" if junit.get("malformed") else
            f'<testsuite name="stub" tests="{junit["tests"]}" failures="{junit["failures"]}" errors="0" skipped="0"/>',
            encoding="utf-8")
    print("BUILD " + ("SUCCESSFUL" if gradle.get("exit", 0) == 0 else "FAILED"))
    return gradle.get("exit", 0)


def main(argv):
    if argv[:1] == ["--version"]:
        version_file = Path(os.getcwd() + ".version")  # the runner's cwd is the temp repository
        print(f"codex-cli {version_file.read_text().strip() if version_file.is_file() else '0.155.1'}")
        return 0
    if argv[:2] == ["features", "list"]:
        for name in FEATURES:
            print(f"{name:<40} stable             false")
        return 0
    if argv[:2] == ["debug", "models"]:
        print(json.dumps({"models": [{"slug": "stub-model"}, {"slug": "gpt-6-astra"}]}))
        return 0
    if argv[:1] == ["exec"]:
        return cmd_exec(argv)
    if argv[:1] == ["sandbox"]:
        return cmd_sandbox(argv)
    print(f"stub: unsupported invocation {argv}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
