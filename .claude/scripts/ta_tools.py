#!/usr/bin/env python3
"""DEV-ENV-002B: ta-status, ta-handoff and ta-finalize for Mazovia TA/TASK work.

  status   [-Task ID] [-Json]                      read-only state and evidence summary (<= 30 lines)
  handoff  -Task ID [-Output PATH] [-Json]         compact handoff (<= 4096 UTF-8 bytes) written outside the repository
  finalize -Task ID [-Commit [-Push]] [-Message M] [-Json]
           default check-only with zero git mutation; -Commit and -Push each need their own interactive y/yes

Exit codes (every command): 0 healthy / successful; 1 unhealthy state or blocked operation; 2 usage / argument
error; 3 unavailable, malformed, conflicting or unsupported evidence; 4 internal tool error.

Evidence: codex_delegate run artifacts (%TEMP%\\mazovia-codex\\<run-id>\\report.json with schema_version and the
identity tuple) and ta-finalize test evidence (%TEMP%\\mazovia-ta\\evidence\\<id>\\report.json). An artifact counts
only when its canonical repository root, branch and task id match exactly; legacy, unsupported and malformed
artifacts are ignored. Newest valid = greatest run_end_utc, tie broken by the greatest run_id. Review and test
evidence count only on the exact current subject fingerprint; stale evidence is never partially trusted.
Authority precedence: frozen policy (.claude/frozen-paths.txt, parsed as codex_delegate parses it) > task contract of
valid implement runs on the current HEAD > review artifact on the exact fingerprint > CLI task id > UNKNOWN.
Missing, malformed or conflicting authority is UNKNOWN; UNKNOWN blocks finalize.
Never runs reset, restore, clean, stash, checkout, switch, rebase or a forced push; staging is exact literal paths.
Python >= 3.11 standard library only.
"""
from __future__ import annotations

import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_delegate as cd  # noqa: E402

SCHEMA_VERSION = 1
SUPPORTED_REPORT_SCHEMAS = (1,)
EXIT_OK, EXIT_UNHEALTHY, EXIT_USAGE, EXIT_EVIDENCE, EXIT_INTERNAL = 0, 1, 2, 3, 4
TASK_RE = re.compile(r"^(TA|TASK|DEV-ENV)-[0-9A-Z-]{1,20}$")
SHA1_RE, SHA256_RE = re.compile(r"^[0-9a-f]{40}$"), re.compile(r"^[0-9a-f]{64}$")
UTC_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")
EVIDENCE_ID_RE = re.compile(r"^\d{8}T\d{6}Z-(TA|TASK|DEV-ENV)-[0-9A-Z-]{1,20}-test-[0-9a-f]{8}$")
TEST_ITEM_KEYS = ("id", "command", "status", "exit_code", "subject_fingerprint")
HANDOFF_MAX_BYTES = 4096
TRUNCATION_MARKER = "[TRUNCATED TO 4096 UTF-8 BYTES]"
LOCK_NAME = "mazovia-ta-finalize.lock"
MESSAGE_MAX_CHARS = 4096

OPTIONS = {"status": {"task": "value", "json": "flag"},
           "handoff": {"task": "value", "output": "value", "json": "flag"},
           "finalize": {"task": "value", "commit": "flag", "push": "flag", "message": "value", "json": "flag"}}
REQUIRED_OPTIONS = {"handoff": ("task",), "finalize": ("task",)}

READ_ONLY_GIT = {"status", "rev-parse", "symbolic-ref", "for-each-ref", "hash-object", "diff", "log", "rev-list",
                 "show", "config", "ls-files"}
GIT_CALLS: list[list[str]] = []  # every git argv ta_tools issued itself (codex_delegate keeps its own list)


class UsageError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class Unsupported(Exception):
    """Artifact with a missing or unsupported schema_version (legacy evidence)."""


class GitError(Exception):
    pass


class LockBusy(Exception):
    pass


# --------------------------------------------------------------------------------------------- arguments

def parse_args(argv: list[str]) -> tuple[str, dict]:
    if not argv or argv[0] not in OPTIONS:
        raise UsageError("USAGE", "first argument must be one of: status, handoff, finalize")
    command, spec, opts = argv[0], OPTIONS[argv[0]], {}
    i = 1
    while i < len(argv):
        token = argv[i]
        name = token.lstrip("-").casefold() if token.startswith("-") else None
        if name not in spec:
            raise UsageError("UNKNOWN_ARGUMENT", f"unknown argument {token!r} for {command}")
        if name in opts:
            raise UsageError("DUPLICATE_ARGUMENT", f"duplicate option {token!r}")
        if spec[name] == "flag":
            opts[name] = True
            i += 1
            continue
        if i + 1 >= len(argv):
            raise UsageError("MISSING_VALUE", f"{token} requires a value")
        opts[name] = argv[i + 1]
        i += 2
    for name in REQUIRED_OPTIONS.get(command, ()):
        if name not in opts:
            raise UsageError("MISSING_ARGUMENT", f"-{name.title()} is required for {command}")
    if "task" in opts and not TASK_RE.match(opts["task"]):
        raise UsageError("INVALID_TASK", "task id must match (TA|TASK|DEV-ENV)-[0-9A-Z-]{1,20}")
    if opts.get("push") and not opts.get("commit"):
        raise UsageError("PUSH_WITHOUT_COMMIT", "-Push requires -Commit")
    if "message" in opts:
        message = opts["message"].strip()
        if not message or len(message) > MESSAGE_MAX_CHARS or any(ord(c) < 32 and c not in "\n\t" for c in message):
            raise UsageError("INVALID_MESSAGE", "commit message must be non-empty, <= 4096 chars, no control chars")
        opts["message"] = message
    return command, opts


# --------------------------------------------------------------------------------------------- git

def mutation_environment() -> dict:
    """Environment for the few mutating git calls: the user's own environment without any GIT_* variable."""
    return {k: v for k, v in os.environ.items() if not k.upper().startswith("GIT_")}


def _mutation_allowed(args: tuple) -> bool:
    sub = args[0]
    if sub == "add":
        return args == ("add", "--pathspec-from-file=-", "--pathspec-file-nul")
    if sub == "commit":
        return args == ("commit", "-F", "-")
    if sub in ("fetch", "push"):
        fixed = ("fetch", "--no-tags", "--no-recurse-submodules") if sub == "fetch" else ("push", "--porcelain")
        rest = args[len(fixed):]
        return args[:len(fixed)] == fixed and len(rest) == 2 and not rest[0].startswith("-") and \
            re.fullmatch(r"[0-9A-Za-z/._-]+:refs/[0-9A-Za-z/._-]+", rest[1]) is not None
    return False


def git(repo: Path, *args: str, check: bool = True, mutate: bool = False, stdin: bytes | None = None,
        timeout: int = 300) -> subprocess.CompletedProcess:
    """Read-only git by default (no optional locks, no GIT_* inheritance, neutralized helpers). mutate=True admits
    exactly the add / commit / fetch / push shapes ta-finalize uses; everything else is an AssertionError."""
    sub = args[0]
    if mutate:
        allowed = _mutation_allowed(args)
    else:
        allowed = sub in READ_ONLY_GIT and not (sub == "hash-object" and "-w" in args) and \
            not (sub == "config" and "--get" not in args)
    if not allowed:
        raise AssertionError(f"ta_tools attempted a disallowed git command: {args}")
    if sub in ("diff", "show"):
        args = (sub, *cd.GIT_DIFF_SAFE_FLAGS, *args[1:])
    GIT_CALLS.append(list(args))
    if mutate:
        argv = ["git", "--literal-pathspecs", "-C", str(repo), *args]
        env = mutation_environment()
    else:
        argv = ["git", "--no-optional-locks", "--literal-pathspecs", "-c", "core.quotepath=off", "-C", str(repo), *args]
        env = cd.git_environment(extra_config=cd.git_helper_overrides(repo))
    proc = subprocess.run(argv, input=stdin, stdin=None if stdin is not None else subprocess.DEVNULL,
                          capture_output=True, timeout=timeout, env=env)
    if check and proc.returncode != 0:
        raise GitError(f"git {' '.join(args[:3])} failed: {proc.stderr.decode('utf-8', 'replace').strip()[:300]}")
    return proc


def git_text(repo: Path, *args: str) -> str:
    return git(repo, *args).stdout.decode("utf-8", "surrogateescape").strip()


def git_paths(repo: Path, *args: str) -> list[str]:
    raw = git(repo, *args).stdout
    return sorted({t.decode("utf-8", "surrogateescape") for t in raw.split(b"\x00") if t})


def repo_root() -> Path:
    proc = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, timeout=60,
                          stdin=subprocess.DEVNULL, env=cd.git_environment())
    if proc.returncode != 0:
        raise UsageError("NOT_IN_GIT_REPOSITORY", "run from inside the repository")
    return Path(cd.long_path(proc.stdout.decode("utf-8", "surrogateescape").strip()))


# --------------------------------------------------------------------------------------------- repository state

def collect_state(repo: Path) -> dict:
    """Current repository facts: branch, HEAD, upstream sync, changed paths, subject fingerprint, frozen, DESIGN."""
    cd.git_helper_overrides(repo, refresh=True)
    state = {"repo": repo, "root": cd.canonical_repo_root(repo)}
    branch = git(repo, "symbolic-ref", "-q", "--short", "HEAD", check=False)
    state["branch"] = branch.stdout.decode("utf-8", "surrogateescape").strip() if branch.returncode == 0 else None
    state["head"] = git_text(repo, "rev-parse", "--verify", "HEAD")
    state["git_dir"] = Path(git_text(repo, "rev-parse", "--absolute-git-dir"))
    state.update(upstream=None, sync="UNKNOWN", ahead=None, behind=None, ahead_paths=[])
    if state["branch"]:
        up = git(repo, "rev-parse", "--symbolic-full-name", "@{upstream}", check=False)
        ref = up.stdout.decode("utf-8", "surrogateescape").strip()
        if up.returncode == 0 and ref.startswith("refs/remotes/"):
            remote = git(repo, "config", "--get", f"branch.{state['branch']}.remote", check=False)
            merge = git(repo, "config", "--get", f"branch.{state['branch']}.merge", check=False)
            state["upstream"] = {"ref": ref, "short": ref[len("refs/remotes/"):],
                                 "remote": remote.stdout.decode().strip() or None,
                                 "merge": merge.stdout.decode().strip() or None,
                                 "sha": git_text(repo, "rev-parse", "--verify", ref)}
            ahead, behind = (int(x) for x in git_text(repo, "rev-list", "--left-right", "--count",
                                                      f"HEAD...{ref}").split())
            state.update(ahead=ahead, behind=behind,
                         sync="PASS" if not ahead and not behind else "DIVERGED" if ahead and behind
                         else "AHEAD" if ahead else "BEHIND")
            if ahead:
                state["ahead_paths"] = git_paths(repo, "diff", "--name-only", "-z", "--no-renames", f"{ref}..HEAD")
    entries = cd.status_entries(repo)
    state["entries"] = sorted(entries, key=lambda e: (e["path"].encode("utf-8", "surrogateescape"), e["class"]))
    state["paths"] = sorted({e["path"] for e in entries})
    state["tree"] = "DIRTY" if entries else "CLEAN"
    state["fingerprint"] = cd.subject_fingerprint(state["root"], state["head"], entries)
    state["staged"] = git_paths(repo, "diff", "--cached", "--name-only", "-z", "--no-renames")
    try:
        state["frozen"], state["frozen_error"] = cd.parse_frozen((repo / cd.FROZEN_SOURCE).read_bytes()), None
    except (OSError, cd.RunnerError) as exc:
        state["frozen"], state["frozen_error"] = None, str(exc)
    design = repo / cd.DESIGN_PATH
    state["design_blob"] = git_text(repo, "hash-object", cd.DESIGN_PATH) if design.is_file() else None
    head_design = git(repo, "rev-parse", "--verify", "-q", f"HEAD:{cd.DESIGN_PATH}", check=False)
    state["head_design_blob"] = head_design.stdout.decode().strip() if head_design.returncode == 0 else None
    return state


# --------------------------------------------------------------------------------------------- artifacts

def runner_root() -> Path:
    return cd.artifact_root()


def evidence_root() -> Path:
    return Path(cd.long_path(tempfile.gettempdir())) / "mazovia-ta" / "evidence"


def _load_report(run_dir: Path) -> dict:
    report = cd.strict_json_loads((run_dir / "report.json").read_text(encoding="utf-8"))
    if not isinstance(report, dict):
        raise ValueError("report is not an object")
    if "schema_version" not in report:
        raise Unsupported("legacy report without schema_version")
    if type(report["schema_version"]) is not int:
        raise ValueError("schema_version is not an integer")
    if report["schema_version"] not in SUPPORTED_REPORT_SCHEMAS:
        raise Unsupported(f"unsupported schema_version {report['schema_version']}")
    return report


def _optional(regex):
    return lambda v: v is None or (isinstance(v, str) and regex.match(v) is not None)


def check_identity(identity, run_id: str, modes: tuple) -> dict:
    if not isinstance(identity, dict) or set(identity) != set(cd.IDENTITY_KEYS):
        raise ValueError("identity keys differ from the identity tuple")
    if type(identity["schema_version"]) is not int:
        raise ValueError("identity.schema_version is not an integer")
    if identity["schema_version"] not in SUPPORTED_REPORT_SCHEMAS:
        raise Unsupported("unsupported identity schema_version")
    text = lambda v: isinstance(v, str) and v != ""  # noqa: E731
    checks = {"task_id": lambda v: isinstance(v, str) and TASK_RE.match(v) is not None,
              "repository_root_canonical": text, "branch": lambda v: v is None or text(v),
              "baseline_head": _optional(SHA1_RE), "current_head": _optional(SHA1_RE),
              "subject_fingerprint": _optional(SHA256_RE), "run_id": lambda v: v == run_id,
              "run_start_utc": lambda v: isinstance(v, str) and UTC_RE.match(v) is not None,
              "run_end_utc": lambda v: isinstance(v, str) and UTC_RE.match(v) is not None,
              "mode": lambda v: v in modes, "verdict": text, "exit_code": lambda v: type(v) is int}
    for key, ok in checks.items():
        if not ok(identity[key]):
            raise ValueError(f"identity.{key} invalid")
    if identity["run_end_utc"] < identity["run_start_utc"]:
        raise ValueError("identity run ends before it starts")
    return identity


def _check_test_items(items) -> list[dict]:
    if not isinstance(items, list):
        raise ValueError("test evidence is not a list")
    for item in items:
        if not isinstance(item, dict) or set(item) != set(TEST_ITEM_KEYS) or not isinstance(item["command"], str) \
                or not isinstance(item["id"], str) or not isinstance(item["status"], str) \
                or not (item["exit_code"] is None or type(item["exit_code"]) is int) \
                or not _optional(SHA256_RE)(item["subject_fingerprint"]):
            raise ValueError("malformed test evidence item")
    return items


def _verified_json(run_dir: Path, name: str, hashes: dict):
    if name not in hashes or cd.sha256_file(run_dir / name) != hashes[name]:
        raise ValueError(f"{name} missing or tampered")
    return cd.strict_json_loads((run_dir / name).read_text(encoding="utf-8"))


def load_runner_artifact(run_dir: Path) -> dict:
    report = _load_report(run_dir)
    identity = check_identity(report.get("identity"), run_dir.name, ("implement", "review"))
    for key in ("run_id", "task_id", "mode", "exit_code"):
        if report.get(key) != identity[key]:
            raise ValueError(f"report.{key} disagrees with identity")
    hashes = report.get("artifact_hashes")
    if not isinstance(hashes, dict) or not hashes:
        raise ValueError("artifact hashes missing")
    raw_contract = _verified_json(run_dir, "contract.json", hashes)
    if not isinstance(raw_contract, dict):
        raise ValueError("contract is not an object")
    contract = cd.validate_contract(raw_contract, identity["mode"], raw_contract.get("parent_run_id"))
    if contract["task_id"] != identity["task_id"]:
        raise ValueError("contract task differs from identity")
    baseline_design = None
    if "baseline.json" in hashes:
        baseline_design = _verified_json(run_dir, "baseline.json", hashes)["docs"]["design"]["git_blob"]
    response = report.get("response") if (report.get("status") or {}).get("RESPONSE") == "PRESENT" else None
    return {"kind": "runner", "identity": identity, "contract": contract, "baseline_design": baseline_design,
            "tests": _check_test_items(report.get("test_evidence")),
            "response": response if isinstance(response, dict) else None}


def load_evidence_artifact(run_dir: Path) -> dict:
    report = _load_report(run_dir)
    identity = check_identity(report.get("identity"), run_dir.name, ("test",))
    return {"kind": "evidence", "identity": identity, "contract": None, "baseline_design": None,
            "tests": _check_test_items(report.get("tests")), "response": None}


def scan_artifacts() -> tuple[list[dict], dict]:
    """Every valid artifact plus counts of ignored ones. Runs without report.json (in progress) are not evidence."""
    artifacts, ignored = [], {"unsupported": 0, "malformed": 0}
    for root, pattern, loader in ((runner_root(), cd.RUN_ID_RE, load_runner_artifact),
                                  (evidence_root(), EVIDENCE_ID_RE, load_evidence_artifact)):
        if not root.is_dir():
            continue
        for run_dir in sorted(root.iterdir(), key=lambda p: p.name):
            if not pattern.match(run_dir.name) or not (run_dir / "report.json").is_file():
                continue
            try:
                artifacts.append(loader(run_dir))
            except Unsupported:
                ignored["unsupported"] += 1
            except (ValueError, TypeError, KeyError, OSError, UnicodeDecodeError, cd.RunnerError):
                ignored["malformed"] += 1
    return artifacts, ignored


def newest(artifacts: list[dict]) -> dict | None:
    return max(artifacts, key=lambda a: (a["identity"]["run_end_utc"], a["identity"]["run_id"])) if artifacts else None


def run_summary(artifact: dict | None) -> dict | None:
    if artifact is None:
        return None
    i = artifact["identity"]
    return {"run_id": i["run_id"], "mode": i["mode"], "verdict": i["verdict"], "exit_code": i["exit_code"],
            "run_end_utc": i["run_end_utc"], "subject_fingerprint": i["subject_fingerprint"]}


# --------------------------------------------------------------------------------------------- evaluation

def resolve_authority(state: dict, implements: list[dict], review: dict | None) -> dict:
    """Task contract authority for the current HEAD; any disagreement is UNKNOWN, never a guess."""
    candidates = [a for a in implements if a["identity"]["baseline_head"] == state["head"]]
    if not candidates:
        return {"state": "UNKNOWN", "reason": "NO_TASK_CONTRACT_FOR_HEAD"}

    def key(a):
        c = a["contract"]
        return cd.canonical_json({"allowed": sorted(p.casefold() for p in c["allowed_paths"]),
                                  "tests": c["required_tests"], "docs": c["expected_docs"],
                                  "design": a["baseline_design"]})

    if len({key(a) for a in candidates}) != 1:
        return {"state": "UNKNOWN", "reason": "CONFLICTING_TASK_CONTRACTS"}
    if review is not None and review["identity"]["subject_fingerprint"] == state["fingerprint"]:
        subject = review["contract"]["subject_run_id"]
        if subject is not None and subject not in {a["identity"]["run_id"] for a in candidates}:
            return {"state": "UNKNOWN", "reason": "REVIEW_SUBJECT_CONFLICT"}
    source = newest(candidates)
    contract = source["contract"]
    return {"state": "VALID", "reason": None, "source_run": source["identity"]["run_id"], "source": source,
            "allowed": contract["allowed_paths"], "required_tests": contract["required_tests"],
            "expected_design": contract["expected_docs"]["design_git_blob"],
            "baseline_design": source["baseline_design"], "branch": source["identity"]["branch"]}


def review_summary(state: dict, review: dict | None) -> dict:
    if review is None:
        return {"state": "UNKNOWN", "currency": "UNKNOWN", "run_id": None, "fingerprint": None,
                "findings": [], "open_decisions": []}
    i, response = review["identity"], review["response"] or {}
    verdict = "CLEAN" if i["verdict"] == "CLEAN" and i["exit_code"] == cd.EXIT_OK else \
        "FINDINGS" if i["verdict"] == "FINDINGS" else "BLOCKED"
    fingerprint = i["subject_fingerprint"]
    return {"state": verdict, "currency": "CURRENT" if fingerprint and fingerprint == state["fingerprint"] else "STALE",
            "run_id": i["run_id"], "fingerprint": fingerprint,
            "findings": [f for f in response.get("findings") or [] if isinstance(f, dict)],
            "open_decisions": [d for d in response.get("open_decisions") or [] if isinstance(d, dict)]}


def frozen_eval(state: dict) -> dict:
    if state["frozen"] is None:
        return {"state": "UNKNOWN", "hits": [], "reason": state["frozen_error"]}
    hits = sorted({p for p in state["paths"] + state["ahead_paths"] if cd.match_any(state["frozen"], p)})
    return {"state": "FAIL" if hits else "PASS", "hits": hits, "reason": None}


def scope_eval(state: dict, authority: dict) -> dict:
    if authority["state"] != "VALID" or state["frozen"] is None:
        return {"state": "UNKNOWN", "violations": [],
                "reason": authority["reason"] if authority["state"] != "VALID" else "FROZEN_POLICY_UNAVAILABLE"}
    violations = []
    for entry in state["entries"]:
        path, reasons = entry["path"], []
        policy = cd.classify(path, authority["allowed"], state["frozen"])
        if policy != "ALLOWED":
            reasons.append(policy)
        if cd.is_sensitive(path):
            reasons.append("SENSITIVE")
        if entry.get("link") or entry.get("directory") or entry.get("submodule"):
            reasons.append("LINK_OR_UNHASHABLE")
        if entry["class"] == "unmerged":
            reasons.append("UNMERGED")
        if reasons:
            violations.append({"path": path, "reasons": reasons})
    return {"state": "FAIL" if violations else "PASS", "violations": violations, "reason": None}


def design_eval(state: dict, authority: dict) -> dict:
    if authority["state"] == "VALID":
        expected = authority["expected_design"] or authority["baseline_design"]
        source = "CONTRACT" if authority["expected_design"] else "BASELINE"
    else:
        expected, source = state["head_design_blob"], "HEAD"
    current = state["design_blob"]
    result = "UNKNOWN" if current is None or expected is None else "PASS" if current == expected else "CHANGED"
    return {"state": result, "blob": current, "expected": expected, "source": source}


def tests_eval(state: dict, authority: dict, evidence: list[dict]) -> dict:
    """Required tests from the authoritative contract; evidence only for the exact normalized command on the exact
    current fingerprint. The newest evidence on the current fingerprint decides (a newer FAIL beats an older PASS)."""
    if authority["state"] != "VALID":
        return {"state": "UNKNOWN", "items": []}
    required = authority["required_tests"]
    if not required:
        return {"state": "NOT_APPLICABLE", "items": []}
    ordered = sorted(evidence, key=lambda a: (a["identity"]["run_end_utc"], a["identity"]["run_id"]), reverse=True)
    items = []
    for spec in required:
        command = cd.normalized_test_command(spec)
        seen = current = None
        for artifact in ordered:
            for item in artifact["tests"]:
                if item["command"] != command:
                    continue
                seen = seen or artifact
                if current is None and artifact["identity"]["subject_fingerprint"] == state["fingerprint"]:
                    current = (artifact, item)
        if current is not None:
            artifact, item = current
            ok = item["status"] == "PASS" and item["exit_code"] == 0 and item["subject_fingerprint"] == state["fingerprint"]
            items.append({"id": spec["id"], "state": "CURRENT" if ok else "FAIL",
                          "run_id": artifact["identity"]["run_id"]})
        else:
            items.append({"id": spec["id"], "state": "STALE" if seen else "UNKNOWN",
                          "run_id": seen["identity"]["run_id"] if seen else None})
    states = {i["state"] for i in items}
    overall = "FAIL" if "FAIL" in states else "CURRENT" if states == {"CURRENT"} else \
        "STALE" if "STALE" in states else "UNKNOWN"
    return {"state": overall, "items": items}


def evaluate(state: dict, task: str | None, artifacts: list[dict], ignored: dict) -> dict:
    mine = [a for a in artifacts if a["identity"]["repository_root_canonical"] == state["root"]
            and a["identity"]["branch"] == state["branch"] and (task is None or a["identity"]["task_id"] == task)]
    runs = [a for a in mine if a["kind"] == "runner"]
    implements = [a for a in runs if a["identity"]["mode"] == "implement"]
    reviews = [a for a in runs if a["identity"]["mode"] == "review"]
    ev = {"task": task, "ignored": ignored, "latest_run": run_summary(newest(runs)), "frozen": frozen_eval(state)}
    if task is None:
        authority = {"state": "UNKNOWN", "reason": "NO_TASK"}
        ev.update(latest_implement=None, review=review_summary(state, None))
    else:
        latest_review = newest(reviews)
        authority = resolve_authority(state, implements, latest_review)
        ev.update(latest_implement=run_summary(newest(implements)), review=review_summary(state, latest_review))
    ev["authority"] = authority
    ev["scope"] = scope_eval(state, authority)
    ev["design"] = design_eval(state, authority)
    ev["tests"] = tests_eval(state, authority, [a for a in mine if a["kind"] == "evidence" or a in implements])
    review_currency, tests_state = ev["review"]["currency"], ev["tests"]["state"]
    ev["evidence"] = "CURRENT" if review_currency == "CURRENT" and tests_state in ("CURRENT", "NOT_APPLICABLE") else \
        "STALE" if "STALE" in (review_currency, tests_state) else "UNKNOWN"
    ev["next_gate"] = next_gate(state, ev)
    return ev


def next_gate(state: dict, ev: dict) -> str:
    task, review = ev["task"] or "<task>", ev["review"]
    if state["sync"] in ("BEHIND", "DIVERGED"):
        return "SYNC: upstream moved; reconcile manually (no automatic reset/rebase)"
    if ev["frozen"]["state"] == "FAIL":
        return "OPEN DECISION: FROZEN CLASS EXCEPTION"
    if ev["design"]["state"] == "CHANGED":
        return "BLOCKED: DESIGN.md differs from its authority"
    if state["tree"] == "CLEAN":
        if state["sync"] == "AHEAD":
            return "PUSH: explicit push approval required"
        return "NONE: tree clean and synced" if state["sync"] == "PASS" else "SYNC: no upstream tracking branch"
    if ev["task"] is None:
        return "STATUS: pass -Task to evaluate review, scope and evidence"
    if ev["authority"]["state"] != "VALID":
        return f"SCOPE AUTHORITY: {ev['authority']['reason']} (run /codex-implement for {task} on this HEAD)"
    if ev["scope"]["state"] == "FAIL":
        return "SCOPE: remove or explicitly authorize out-of-scope paths"
    if review["state"] == "UNKNOWN":
        return f"REVIEW: /codex-review {task} with subject run {ev['authority']['source_run']}"
    if review["currency"] == "STALE":
        return f"RE-REVIEW: tree changed since review {review['run_id']}"
    if review["state"] == "FINDINGS":
        return "CORRECTIVE: address the review findings, then re-review"
    if review["state"] == "BLOCKED":
        return "REVIEW BLOCKED: resolve the review blocker or open decision"
    if ev["tests"]["state"] == "FAIL":
        return "TESTS: required tests fail on the current state"
    if ev["tests"]["state"] in ("STALE", "UNKNOWN"):
        return f"TESTS: ta-finalize -Task {task} -Commit reruns the authoritative tests first"
    return f"FINALIZE: ta-finalize -Task {task} -Commit (explicit approval)"


def status_exit(state: dict, ev: dict) -> int:
    review = ev["review"]
    unhealthy = state["sync"] in ("BEHIND", "DIVERGED") or ev["design"]["state"] == "CHANGED" or \
        ev["frozen"]["state"] == "FAIL" or ev["scope"]["state"] == "FAIL" or ev["tests"]["state"] == "FAIL" or \
        (review["state"] in ("FINDINGS", "BLOCKED") and review["currency"] == "CURRENT") or \
        (state["tree"] == "DIRTY" and ev["evidence"] == "STALE")
    if unhealthy:
        return EXIT_UNHEALTHY
    unavailable = state["sync"] == "UNKNOWN" or "UNKNOWN" in (ev["frozen"]["state"], ev["design"]["state"]) or \
        (ev["task"] is not None and state["tree"] == "DIRTY" and
         "UNKNOWN" in (ev["authority"]["state"], review["state"], ev["evidence"]))
    return EXIT_EVIDENCE if unavailable else EXIT_OK


# --------------------------------------------------------------------------------------------- output helpers

def emit_json(obj) -> None:
    sys.stdout.write(json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def error_result(code: int, error_code: str, message: str) -> dict:
    return {"schema_version": SCHEMA_VERSION, "status": "error", "exit_code": code, "error_code": error_code,
            "message": message}


def fail(want_json: bool, code: int, error_code: str, message: str) -> int:
    if want_json:
        emit_json(error_result(code, error_code, message))
    print(f"{error_code}: {message}", file=sys.stderr)
    return code


def short(text, limit: int) -> str:
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[:max(limit - 3, 0)].rstrip() + "..."


def status_payload(state: dict, ev: dict, code: int) -> dict:
    up = state["upstream"]
    return {"schema_version": SCHEMA_VERSION, "status": {0: "healthy", 1: "unhealthy", 3: "unavailable"}[code],
            "exit_code": code, "task": ev["task"], "repository_root": state["root"], "branch": state["branch"],
            "head": state["head"], "origin": {"ref": up["short"], "sha": up["sha"]} if up else None,
            "sync": state["sync"], "ahead": state["ahead"], "behind": state["behind"], "tree": state["tree"],
            "changed_paths": len(state["paths"]), "subject_fingerprint": state["fingerprint"],
            "design": ev["design"], "frozen": {"state": ev["frozen"]["state"], "hits": ev["frozen"]["hits"]},
            "latest_run": ev["latest_run"],
            "review": {k: ev["review"][k] for k in ("state", "currency", "run_id", "fingerprint")},
            "scope": {"state": ev["scope"]["state"], "reason": ev["scope"]["reason"],
                      "violations": ev["scope"]["violations"]},
            "authority": {"state": ev["authority"]["state"], "reason": ev["authority"]["reason"],
                          "source_run": ev["authority"].get("source_run")},
            "evidence": ev["evidence"], "tests": ev["tests"], "next_gate": ev["next_gate"],
            "ignored_artifacts": ev["ignored"]}


def status_lines(state: dict, ev: dict) -> list[str]:
    up, review, latest = state["upstream"], ev["review"], ev["latest_run"]
    sync = state["sync"] + (f" (+{state['ahead']}/-{state['behind']})" if state["sync"] not in ("PASS", "UNKNOWN") else "")
    lines = [f"TASK        {ev['task'] or 'UNKNOWN (no -Task)'}",
             f"BRANCH      {state['branch'] or 'DETACHED'}",
             f"HEAD        {state['head']}",
             f"ORIGIN      {up['sha'] + ' (' + up['short'] + ')' if up else 'UNKNOWN (no upstream)'}",
             f"SYNC        {sync}",
             f"TREE        {state['tree']}" + (f" ({len(state['paths'])} paths)" if state["paths"] else ""),
             f"DESIGN      {ev['design']['blob'] or '-'} {ev['design']['state']} (vs {ev['design']['source']})",
             f"FROZEN      {ev['frozen']['state']}" + (f" {', '.join(ev['frozen']['hits'][:3])}" if ev["frozen"]["hits"] else ""),
             f"LATEST_RUN  " + (f"{latest['run_id']} {latest['verdict']} exit {latest['exit_code']}" if latest else "NONE"),
             f"REVIEW      {review['state']}" + (f" ({review['run_id']})" if review["run_id"] else ""),
             f"SCOPE       {ev['scope']['state']}" + (f" {ev['scope']['reason']}" if ev["scope"]["reason"] else "")
             + (f" {len(ev['scope']['violations'])} violation(s)" if ev["scope"]["violations"] else ""),
             f"EVIDENCE    {ev['evidence']} (review {review['currency']}, tests {ev['tests']['state']})",
             f"FINGERPRINT {state['fingerprint']}",
             f"NEXT_GATE   {ev['next_gate']}"]
    for violation in ev["scope"]["violations"][:5]:
        lines.append(f"  - {violation['path']}: {','.join(violation['reasons'])}")
    if ev["ignored"]["unsupported"] or ev["ignored"]["malformed"]:
        lines.append(f"NOTES       ignored artifacts: {ev['ignored']['unsupported']} unsupported/legacy, "
                     f"{ev['ignored']['malformed']} malformed")
    return lines


# --------------------------------------------------------------------------------------------- status

def cmd_status(repo: Path, opts: dict) -> int:
    state = collect_state(repo)
    artifacts, ignored = scan_artifacts()
    ev = evaluate(state, opts.get("task"), artifacts, ignored)
    code = status_exit(state, ev)
    if opts.get("json"):
        emit_json(status_payload(state, ev, code))
    else:
        print("\n".join(status_lines(state, ev)))
    return code


# --------------------------------------------------------------------------------------------- handoff

def handoff_core(state: dict, ev: dict) -> list[str]:
    up, review = state["upstream"], ev["review"]
    return [f"# HANDOFF {ev['task']}",
            f"TASK: {ev['task']}",
            f"REPO: {state['root']}",
            f"BRANCH: {state['branch'] or 'DETACHED'}",
            f"HEAD: {state['head']}",
            f"ORIGIN: {up['sha'] + ' (' + up['short'] + ')' if up else 'UNKNOWN'} SYNC {state['sync']}",
            f"DESIGN: {ev['design']['blob'] or '-'} {ev['design']['state']}",
            f"TREE: {state['tree']} ({len(state['paths'])} changed paths)",
            f"LATEST REVIEW VERDICT: {review['state']} (run {review['run_id'] or '-'}, evidence {review['currency']})",
            f"SUBJECT FINGERPRINT: {state['fingerprint']}",
            f"NEXT_GATE: {ev['next_gate']}"]


def handoff_facts(repo: Path, state: dict) -> dict:
    last = git_text(repo, "log", "-1", "--format=%H%x00%s").split("\x00", 1)
    if state["tree"] == "DIRTY":
        title, paths = "CHANGED PATHS", state["paths"]
    else:
        title, paths = "COMMITTED PATHS (HEAD)", git_paths(repo, "show", "--name-only", "--format=", "-z", "HEAD")
    return {"last_commit": f"{last[0][:12]} {short(last[1] if len(last) > 1 else '', 80)}",
            "paths_title": title, "paths": paths}


def handoff_sections(facts: dict, ev: dict, *, notes: bool, summary_len: int, max_items: int | None,
                     max_paths: int | None, context: bool) -> list[str]:
    out = []
    if context:
        out.append(f"LAST_COMMIT: {facts['last_commit']}")
        impl = ev["latest_implement"]
        out.append("IMPLEMENT RUN: " + (f"{impl['run_id']} {impl['verdict']} exit {impl['exit_code']}" if impl else "NONE"))
        out.append(f"SCOPE: {ev['scope']['state']}" + (f" ({ev['scope']['reason']})" if ev["scope"]["reason"] else "")
                   + f"; FROZEN: {ev['frozen']['state']}; EVIDENCE: {ev['evidence']}")
        out.append(f"TESTS: {ev['tests']['state']}")
        out += [f"- {i['id']}: {i['state']} ({i['run_id'] or '-'})" for i in ev["tests"]["items"]]

    def listing(title, rows):
        if not rows:
            return
        shown = rows if max_items is None else rows[:max_items]
        out.append(f"{title}:")
        out.extend(shown)
        if len(shown) < len(rows):
            out.append(f"- (+{len(rows) - len(shown)} more)")

    def text(value):
        return f": {short(value, summary_len)}" if summary_len > 0 else ""

    review = ev["review"]
    listing("FINDINGS", [f"- {f.get('severity', '?')} {f.get('path', '?')}:{f.get('line') or '-'}{text(f.get('summary', ''))}"
                         for f in review["findings"]])
    listing("OPEN_DECISIONS", [f"- {d.get('kind', '?')} {d.get('path', '?')}{text(d.get('reason', ''))}"
                               for d in review["open_decisions"]])
    for violation in ev["scope"]["violations"]:
        out.append(f"SCOPE VIOLATION: {violation['path']} {','.join(violation['reasons'])}")
    title, paths = facts["paths_title"], facts["paths"]
    if paths:
        shown = paths if max_paths is None else paths[:max_paths]
        out.append(f"{title} ({len(paths)}):")
        out.extend(f"- {p}" for p in shown)
        if len(shown) < len(paths):
            out.append(f"- (+{len(paths) - len(shown)} more)")
    if notes:
        out.append("NOTES:")
        out.append(f"- ignored artifacts: {ev['ignored']['unsupported']} unsupported/legacy, "
                   f"{ev['ignored']['malformed']} malformed")
        out.append("- evidence counts only on the exact subject fingerprint; logs and sources are not included")
    return out


def render_handoff(repo: Path, state: dict, ev: dict, limit: int = HANDOFF_MAX_BYTES) -> tuple[str, bool]:
    """Core fields are never truncated. Reduction order: notes, findings summaries, changed-path display, then the
    remaining optional context; a truncated handoff ends with TRUNCATION_MARKER and stays <= limit bytes."""
    core = "\n".join(handoff_core(state, ev)) + "\n"
    marker = TRUNCATION_MARKER + "\n"
    facts = handoff_facts(repo, state)
    n_paths = len(facts["paths"])
    n_items = len(ev["review"]["findings"]) + len(ev["review"]["open_decisions"])
    plans = [dict(notes=True, summary_len=120, max_items=None, max_paths=None, context=True),
             dict(notes=False, summary_len=120, max_items=None, max_paths=None, context=True)]
    plans += [dict(notes=False, summary_len=s, max_items=None, max_paths=None, context=True) for s in (60, 30, 0)]
    items = n_items
    while items > 0:
        items //= 2
        plans.append(dict(notes=False, summary_len=0, max_items=items, max_paths=None, context=True))
    paths = n_paths
    while paths > 0:
        paths //= 2
        plans.append(dict(notes=False, summary_len=0, max_items=0, max_paths=paths, context=True))
    plans.append(dict(notes=False, summary_len=0, max_items=0, max_paths=0, context=False))
    for index, plan in enumerate(plans):
        body = "\n".join(handoff_sections(facts, ev, **plan))
        text = core + (body + "\n" if body else "")
        if index == 0 and len(text.encode("utf-8")) <= limit:
            return text, False
        if len((text + marker).encode("utf-8")) <= limit:
            return text + marker, True
    if len((core + marker).encode("utf-8")) > limit:
        raise RuntimeError("handoff core fields exceed the size limit")
    return core + marker, True


def resolve_output(repo: Path, task: str, output: str | None) -> Path:
    """Canonical handoff path; anything that is (or resolves through a link/junction to) the repository is refused."""
    if output is None:
        target = Path(cd.long_path(tempfile.gettempdir())) / "mazovia-handoff" / f"{task}.md"
    else:
        if any(ord(c) < 32 for c in output):
            raise UsageError("OUTPUT_INVALID", "control character in -Output")
        target = Path(os.path.abspath(output))
    real_repo = os.path.realpath(repo)
    for candidate in (str(target), os.path.realpath(target)):
        if cd.is_within(candidate, repo) or cd.is_within(candidate, real_repo):
            raise UsageError("OUTPUT_INSIDE_REPOSITORY", f"refusing to write the handoff inside the repository: {target}")
    if os.path.lexists(target):
        st = os.lstat(target)
        if not os.path.isfile(target) or os.path.islink(target) or \
                getattr(st, "st_file_attributes", 0) & cd.FILE_ATTRIBUTE_REPARSE_POINT:
            raise UsageError("OUTPUT_NOT_REGULAR_FILE", f"-Output exists and is not a regular file: {target}")
    return target


def write_handoff(repo: Path, target: Path, text: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    real_parent = os.path.realpath(target.parent)
    if cd.is_within(real_parent, repo) or cd.is_within(real_parent, os.path.realpath(repo)):
        raise UsageError("OUTPUT_INSIDE_REPOSITORY", "handoff directory resolves into the repository")
    temp = target.with_name(f".{target.name}.{secrets.token_hex(4)}.tmp")
    temp.write_bytes(text.encode("utf-8"))
    os.replace(temp, target)


def cmd_handoff(repo: Path, opts: dict) -> int:
    target = resolve_output(repo, opts["task"], opts.get("output"))
    state = collect_state(repo)
    artifacts, ignored = scan_artifacts()
    ev = evaluate(state, opts["task"], artifacts, ignored)
    text, truncated = render_handoff(repo, state, ev)
    write_handoff(repo, target, text)
    size = len(text.encode("utf-8"))
    if opts.get("json"):
        emit_json({"schema_version": SCHEMA_VERSION, "status": "written", "exit_code": EXIT_OK, "task": opts["task"],
                   "output": str(target), "bytes": size, "truncated": truncated, "next_gate": ev["next_gate"]})
    else:
        print(f"HANDOFF {opts['task']}: {target} ({size} bytes{', truncated' if truncated else ''})")
        print(f"NEXT_GATE {ev['next_gate']}")
    return EXIT_OK


# --------------------------------------------------------------------------------------------- finalize lock

def pid_alive(pid: int) -> bool:
    """True when a process with this id exists (or cannot be ruled out)."""
    if pid <= 0:
        return False
    if sys.platform == "win32":
        import ctypes
        import ctypes.wintypes as wt
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.OpenProcess.restype = wt.HANDLE
        handle = k32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return ctypes.get_last_error() != 87  # ERROR_INVALID_PARAMETER: no such process
        try:
            code = wt.DWORD()
            return not k32.GetExitCodeProcess(wt.HANDLE(handle), ctypes.byref(code)) or code.value == 259
        finally:
            k32.CloseHandle(wt.HANDLE(handle))
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


class FinalizeLock:
    """Repository-local lock in the git directory (never part of the worktree, so never tracked).

    Created atomically (O_CREAT|O_EXCL) with pid + start UTC and kept open while held; on Windows the open handle
    (no FILE_SHARE_DELETE) makes a live holder's lock impossible to rename or delete. A lock whose pid is alive
    blocks. A dead holder's lock is broken only by an atomic rename that fails while any process holds it open.
    Off Windows, stale locks are never broken automatically (fail closed; remove it manually after checking)."""

    def __init__(self, git_dir: Path):
        self.path = Path(git_dir) / LOCK_NAME
        self.fd: int | None = None
        self.token: str | None = None

    def holder(self) -> dict:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
        return data if isinstance(data, dict) and type(data.get("pid")) is int else {}

    def _break_stale(self) -> bool:
        if sys.platform != "win32":
            return False
        aside = self.path.with_name(f"{LOCK_NAME}.stale-{secrets.token_hex(4)}")
        try:
            os.rename(self.path, aside)
        except OSError:
            return False
        try:
            os.remove(aside)
        except OSError:
            pass
        return True

    def acquire(self) -> dict:
        for _ in range(2):
            try:
                fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_BINARY", 0))
            except FileExistsError:
                holder = self.holder()
                if holder and pid_alive(holder["pid"]):
                    raise LockBusy(f"finalizer pid {holder['pid']} (started {holder.get('start_utc')}) holds {self.path}")
                if not self._break_stale():
                    raise LockBusy(f"finalize lock {self.path} is held or cannot be verified stale")
                continue
            self.token = secrets.token_hex(8)
            payload = {"pid": os.getpid(), "start_utc": cd.utc_now(), "token": self.token}
            try:
                os.write(fd, (json.dumps(payload) + "\n").encode("utf-8"))
                os.fsync(fd)
            except OSError:
                os.close(fd)
                os.remove(self.path)
                raise
            self.fd = fd
            return payload
        raise LockBusy(f"finalize lock {self.path} reappeared while breaking a stale lock")

    def release(self) -> None:
        if self.fd is None:
            return
        os.close(self.fd)
        self.fd = None
        if self.holder().get("token") == self.token:
            try:
                os.remove(self.path)
            except OSError:
                pass


# --------------------------------------------------------------------------------------------- confirmation

def stdin_is_console() -> bool:
    """A real interactive console. Redirected stdin, pipes and the NUL device (a character device) do not count."""
    try:
        if sys.stdin is None or sys.stdin.closed:
            return False
        if sys.platform == "win32":
            import ctypes
            import ctypes.wintypes as wt
            import msvcrt
            handle = msvcrt.get_osfhandle(sys.stdin.fileno())
            mode = wt.DWORD()
            return bool(ctypes.WinDLL("kernel32").GetConsoleMode(wt.HANDLE(handle), ctypes.byref(mode)))
        return sys.stdin.isatty()
    except (OSError, ValueError, AttributeError):
        return False


def read_answer(prompt: str) -> str:
    sys.stderr.write(prompt)
    sys.stderr.flush()
    line = sys.stdin.readline()
    if line == "":
        raise EOFError
    return line


IS_INTERACTIVE = stdin_is_console
READ_ANSWER = read_answer


def confirm(prompt: str) -> tuple[bool, str]:
    """Only a trimmed, case-folded exact "y" / "yes" is consent; EOF, interrupt and non-interactive stdin are NO."""
    if not IS_INTERACTIVE():
        return False, "NONINTERACTIVE"
    try:
        answer = READ_ANSWER(prompt)
    except (EOFError, KeyboardInterrupt, OSError):
        return False, "NO_ANSWER"
    return (answer.strip().casefold() in ("y", "yes")), "ANSWERED"


# --------------------------------------------------------------------------------------------- authoritative tests

def run_authoritative_test(repo: Path, spec: dict, log_path: Path) -> int:
    """Run exactly one contract-defined Gradle test (argv validated as the runner validates it). Returns exit code."""
    cd.validate_gradle_argv(spec["argv"])
    if spec["cwd"] != ".":
        raise cd.RunnerError("REQUIRED_TEST_INVALID", "cwd must be '.'")
    argv = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(repo / "gradlew.bat"), *spec["argv"][1:]]
    env = mutation_environment()
    env.update(spec["env"])
    with open(log_path, "wb") as log:
        try:
            return subprocess.run(argv, cwd=str(repo), stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                  env=env, timeout=spec["timeout_seconds"]).returncode
        except subprocess.TimeoutExpired:
            return -1


def rerun_tests(repo: Path, state: dict, task: str, required: list[dict]) -> tuple[dict, list[dict]]:
    """Rerun the authoritative required tests and record test evidence (outside the repository)."""
    root = evidence_root()
    if cd.is_within(root, repo):
        raise RuntimeError("evidence root is inside the repository")
    root.mkdir(parents=True, exist_ok=True)
    run_id = f"{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-{task}-test-{secrets.token_hex(4)}"
    run_dir = root / run_id
    run_dir.mkdir()
    start, results, hashes = cd.utc_now(), [], {}
    for spec in required:
        log = run_dir / f"{spec['id']}.log"
        code = run_authoritative_test(repo, spec, log)
        results.append((spec, code))
        hashes[log.name] = cd.sha256_file(log) if log.is_file() else None
    after = collect_state(repo)
    unchanged = after["fingerprint"] == state["fingerprint"]
    items = [{"id": spec["id"], "command": cd.normalized_test_command(spec),
              "status": "PASS" if code == 0 else "FAIL", "exit_code": code,
              "subject_fingerprint": state["fingerprint"] if code == 0 and unchanged else None}
             for spec, code in results]
    passed = unchanged and all(code == 0 for _, code in results)
    identity = {"schema_version": cd.REPORT_SCHEMA_VERSION, "task_id": task, "repository_root_canonical": state["root"],
                "branch": state["branch"], "baseline_head": state["head"], "current_head": after["head"],
                "subject_fingerprint": after["fingerprint"] if unchanged else None, "run_id": run_id,
                "run_start_utc": start, "run_end_utc": cd.utc_now(), "mode": "test",
                "verdict": "PASS" if passed else "FAIL", "exit_code": 0 if passed else 1}
    cd.write_json(run_dir / "report.json", {"schema_version": cd.REPORT_SCHEMA_VERSION, "identity": identity,
                                            "tests": items, "artifact_hashes": hashes,
                                            "tree_changed_by_tests": not unchanged})
    return after, items


# --------------------------------------------------------------------------------------------- finalize

def partially_staged(state: dict) -> list[str]:
    return sorted(e["path"] for e in state["entries"] if e["xy"][0] not in ".?!" and e["xy"][1] != ".")


def finalize_gates(repo: Path, state: dict, ev: dict) -> list[dict]:
    gates = []

    def add(name, ok, detail, unknown=False):
        gates.append({"gate": name, "result": "PASS" if ok else "UNKNOWN" if unknown else "FAIL", "detail": detail})

    auth, up, review, tests = ev["authority"], state["upstream"], ev["review"], ev["tests"]
    add("branch", auth["state"] == "VALID" and state["branch"] is not None and state["branch"] == auth["branch"],
        f"{state['branch']} (authority {auth.get('branch')})", unknown=auth["state"] != "VALID")
    add("upstream", up is not None, up["short"] if up else "no upstream tracking branch")
    add("head_equals_upstream", up is not None and state["head"] == up["sha"],
        f"HEAD {state['head'][:12]} upstream {up['sha'][:12] if up else '-'}")
    add("sync", state["sync"] == "PASS", state["sync"])
    add("changes_present", state["tree"] == "DIRTY", f"{len(state['paths'])} changed paths")
    staged_conflict = partially_staged(state)
    add("index_state", not staged_conflict and not any(e["class"] == "unmerged" for e in state["entries"]),
        "partially staged: " + ", ".join(staged_conflict[:5]) if staged_conflict else "ok")
    add("review_clean", review["state"] == "CLEAN", f"{review['state']} ({review['run_id'] or 'no review'})",
        unknown=review["state"] == "UNKNOWN")
    add("review_fingerprint", review["currency"] == "CURRENT", review["currency"], unknown=review["currency"] == "UNKNOWN")
    add("scope_authority", auth["state"] == "VALID", auth.get("source_run") or auth["reason"],
        unknown=auth["state"] != "VALID")
    add("scope", ev["scope"]["state"] == "PASS",
        ev["scope"]["reason"] or "; ".join(f"{v['path']} {','.join(v['reasons'])}" for v in ev["scope"]["violations"][:5]) or "ok",
        unknown=ev["scope"]["state"] == "UNKNOWN")
    add("frozen", ev["frozen"]["state"] == "PASS", ", ".join(ev["frozen"]["hits"][:5]) or ev["frozen"]["state"],
        unknown=ev["frozen"]["state"] == "UNKNOWN")
    check = git(repo, "diff", "HEAD", "--check", check=False)
    add("diff_check", check.returncode == 0, "ok" if check.returncode == 0 else
        short(check.stdout.decode("utf-8", "replace"), 200))
    rerun = tests["state"] in ("STALE", "UNKNOWN") and auth["state"] == "VALID" and bool(auth["required_tests"])
    add("tests", tests["state"] in ("CURRENT", "NOT_APPLICABLE") or rerun,
        "RERUN REQUIRED before commit" if rerun else tests["state"], unknown=tests["state"] == "UNKNOWN" and not rerun)
    add("design", ev["design"]["state"] == "PASS",
        f"{ev['design']['blob']} vs {ev['design']['source']} {ev['design']['expected']}",
        unknown=ev["design"]["state"] == "UNKNOWN")
    return gates


def gates_exit(gates: list[dict]) -> int:
    results = {g["result"] for g in gates}
    return EXIT_UNHEALTHY if "FAIL" in results else EXIT_EVIDENCE if "UNKNOWN" in results else EXIT_OK


def default_message(task: str, ev: dict) -> str:
    source = ev["authority"].get("source") or {}
    summary = ((source.get("response") or {}).get("summary") or "").strip()
    line = " ".join(summary.splitlines()[0].split()) if summary else ""
    return short(f"{task}: {line}" if line else f"{task}: apply reviewed change", 72)


def entry_letter(entry: dict) -> str:
    if entry.get("absent"):
        return "D"
    return "A" if entry["class"] in ("untracked", "rename_dst") or not (entry.get("head_blob") or "").strip("0") else "M"


class Finalizer:
    def __init__(self, repo: Path, opts: dict):
        self.repo, self.opts, self.task = repo, opts, opts["task"]
        self.mode = "commit+push" if opts.get("push") else "commit" if opts.get("commit") else "check"
        self.out = sys.stderr if opts.get("json") else sys.stdout
        self.result = {"schema_version": SCHEMA_VERSION, "task": self.task, "mode": self.mode, "gates": [],
                       "commit": None, "pushed": False, "files": [], "subject_fingerprint": None}

    def say(self, text: str = "") -> None:
        print(text, file=self.out)
        self.out.flush()

    def finish(self, code: int, error_code: str | None = None, message: str = "") -> int:
        self.result.update(exit_code=code, status="ok" if code == EXIT_OK else "blocked" if code in (1, 3) else "error",
                           error_code=error_code, message=message)
        if self.opts.get("json"):
            emit_json(self.result)
        self.say(f"RESULT      {'OK' if code == EXIT_OK else 'BLOCKED'}" + (f" {error_code}" if error_code else "")
                 + (f": {message}" if message else ""))
        return code

    def evaluate(self) -> tuple[dict, dict, list[dict]]:
        state = collect_state(self.repo)
        artifacts, ignored = scan_artifacts()
        ev = evaluate(state, self.task, artifacts, ignored)
        return state, ev, finalize_gates(self.repo, state, ev)

    def show_gates(self, gates: list[dict]) -> None:
        for gate in gates:
            self.say(f"GATE {gate['gate']:<20} {gate['result']:<8} {gate['detail']}")

    def run(self) -> int:
        self.say(f"TA-FINALIZE {self.task} ({self.mode})")
        state, ev, gates = self.evaluate()
        self.result.update(gates=gates, subject_fingerprint=state["fingerprint"], files=state["paths"])
        self.show_gates(gates)
        code = gates_exit(gates)
        if code != EXIT_OK:
            return self.finish(code, "PREFLIGHT_BLOCKED",
                               ", ".join(g["gate"] for g in gates if g["result"] != "PASS"))
        if self.mode == "check":
            return self.finish(EXIT_OK, None, "all checks pass (check-only: no git mutation)")
        if not IS_INTERACTIVE():
            return self.finish(EXIT_UNHEALTHY, "NONINTERACTIVE", "commit requires an interactive console")
        lock = FinalizeLock(state["git_dir"])
        try:
            lock.acquire()
        except LockBusy as exc:
            return self.finish(EXIT_UNHEALTHY, "CONCURRENT_FINALIZE", str(exc))
        try:
            return self.commit_and_push(state, ev)
        finally:
            lock.release()

    def commit_and_push(self, state: dict, ev: dict) -> int:
        if ev["tests"]["state"] in ("STALE", "UNKNOWN"):
            self.say("TESTS       rerunning authoritative required tests")
            _, items = rerun_tests(self.repo, state, self.task, ev["authority"]["required_tests"])
            self.result["tests_rerun"] = items
            state, ev, gates = self.evaluate()
            self.result["gates"] = gates
            if gates_exit(gates) != EXIT_OK or ev["tests"]["state"] != "CURRENT":
                self.show_gates(gates)
                return self.finish(EXIT_UNHEALTHY, "TESTS_BLOCKED", f"tests {ev['tests']['state']} after rerun")
        message = self.opts.get("message") or default_message(self.task, ev)
        review = ev["review"]
        self.say("")
        self.say(f"TASK                {self.task}")
        self.say(f"HEAD                {state['head']}")
        self.say(f"REVIEW RUN          {review['run_id']}")
        self.say(f"REVIEW VERDICT      {review['state']}")
        self.say(f"SUBJECT FINGERPRINT {state['fingerprint']}")
        self.say(f"TESTS               {ev['tests']['state']}" +
                 "".join(f" {i['id']}={i['state']}" for i in ev["tests"]["items"]))
        self.say(f"FILES TO COMMIT     ({len(state['paths'])})")
        for entry in state["entries"]:
            self.say(f"  {entry_letter(entry)} {entry['path']}")
        self.say("PROPOSED COMMIT MESSAGE")
        for line in message.splitlines():
            self.say(f"  {line}")
        ok, why = confirm("Proceed with COMMIT? [y/N] ")
        if not ok:
            return self.finish(EXIT_UNHEALTHY, "COMMIT_NOT_CONFIRMED", why)
        now, now_ev, now_gates = self.evaluate()
        changed = self.changes_since(state, ev, now, now_ev, now_gates)
        if changed:
            return self.finish(EXIT_UNHEALTHY, "STATE_CHANGED_BEFORE_COMMIT", ", ".join(changed))
        intended = state["paths"]
        git(self.repo, "add", "--pathspec-from-file=-", "--pathspec-file-nul", mutate=True,
            stdin=b"".join(p.encode("utf-8", "surrogateescape") + b"\x00" for p in intended))
        cached = git_paths(self.repo, "diff", "--cached", "--name-only", "-z", "--no-renames")
        if cached != intended:
            return self.finish(EXIT_UNHEALTHY, "STAGED_SET_MISMATCH",
                               f"staged {len(cached)} vs intended {len(intended)}; index left as is, nothing reset")
        cached_check = git(self.repo, "diff", "--cached", "--check", check=False)
        if cached_check.returncode != 0:
            return self.finish(EXIT_UNHEALTHY, "CACHED_DIFF_CHECK_FAILED",
                               short(cached_check.stdout.decode("utf-8", "replace"), 200) + "; index left staged")
        staged_state = collect_state(self.repo)
        if staged_state["fingerprint"] != state["fingerprint"] or staged_state["head"] != state["head"]:
            return self.finish(EXIT_UNHEALTHY, "STATE_CHANGED_BEFORE_COMMIT", "fingerprint changed while staging")
        commit = git(self.repo, "commit", "-F", "-", mutate=True, check=False, stdin=message.encode("utf-8") + b"\n")
        if commit.returncode != 0:
            return self.finish(EXIT_UNHEALTHY, "COMMIT_FAILED",
                               short(commit.stderr.decode("utf-8", "replace"), 300) + "; index left staged, nothing reset")
        sha = git_text(self.repo, "rev-parse", "--verify", "HEAD")
        self.result["commit"] = sha
        parent = git_text(self.repo, "rev-parse", "--verify", "HEAD^")
        committed = git_paths(self.repo, "diff", "--name-only", "-z", "--no-renames", f"{state['head']}..{sha}")
        if parent != state["head"] or committed != intended:
            return self.finish(EXIT_UNHEALTHY, "COMMIT_VERIFY_FAILED", f"commit {sha} differs from the intended set")
        self.say(f"COMMITTED   {sha}")
        if self.mode != "commit+push":
            return self.finish(EXIT_OK, None, f"committed {sha}; not pushed")
        return self.push(state, sha)

    def changes_since(self, state, ev, now, now_ev, now_gates) -> list[str]:
        diffs = []
        for key in ("branch", "head", "fingerprint", "staged"):
            if state[key] != now[key]:
                diffs.append(key)
        if (state["upstream"] or {}).get("sha") != (now["upstream"] or {}).get("sha"):
            diffs.append("upstream")
        if (ev["review"]["run_id"], ev["review"]["fingerprint"]) != (now_ev["review"]["run_id"], now_ev["review"]["fingerprint"]):
            diffs.append("review")
        if now_ev["tests"]["state"] not in ("CURRENT", "NOT_APPLICABLE") or \
                [i["run_id"] for i in ev["tests"]["items"]] != [i["run_id"] for i in now_ev["tests"]["items"]]:
            diffs.append("tests")
        diffs += [f"gate:{g['gate']}" for g in now_gates if g["result"] != "PASS"]
        return diffs

    def push(self, state: dict, sha: str) -> int:
        up = state["upstream"]
        remote, merge = up["remote"], up["merge"]
        if not remote or not merge or not merge.startswith("refs/heads/") or up["ref"] != f"refs/remotes/{remote}/{merge[len('refs/heads/'):]}":
            return self.finish(EXIT_UNHEALTHY, "UPSTREAM_UNSUPPORTED", f"upstream {up['short']} is not a plain remote branch")
        ok, why = confirm(f"Proceed with PUSH of {sha} to {up['short']}? [y/N] ")
        if not ok:
            return self.finish(EXIT_UNHEALTHY, "PUSH_NOT_CONFIRMED", f"{why}; commit {sha} stays local")
        fetch = git(self.repo, "fetch", "--no-tags", "--no-recurse-submodules", remote, f"{merge}:{up['ref']}",
                    mutate=True, check=False)
        if fetch.returncode != 0:
            return self.finish(EXIT_UNHEALTHY, "REMOTE_CHANGED_BEFORE_PUSH",
                               "fetch refused (remote rewritten?): " + short(fetch.stderr.decode("utf-8", "replace"), 200))
        now = collect_state(self.repo)
        diffs = []
        if now["head"] != sha or now["branch"] != state["branch"]:
            diffs.append("local HEAD/branch")
        if not now["upstream"] or (now["upstream"]["ref"], now["upstream"]["remote"], now["upstream"]["merge"]) != \
                (up["ref"], remote, merge):
            diffs.append("upstream identity")
        elif now["upstream"]["sha"] != up["sha"]:
            diffs.append("remote moved")
        if now["tree"] != "CLEAN":
            diffs.append("unexpected working-tree changes")
        if diffs:
            return self.finish(EXIT_UNHEALTHY, "REMOTE_CHANGED_BEFORE_PUSH" if "remote moved" in diffs else
                               "STATE_CHANGED_BEFORE_PUSH", ", ".join(diffs) + f"; commit {sha} stays local, nothing reset")
        push = git(self.repo, "push", "--porcelain", remote, f"{sha}:{merge}", mutate=True, check=False)
        if push.returncode != 0:
            return self.finish(EXIT_UNHEALTHY, "PUSH_FAILED",
                               short(push.stderr.decode("utf-8", "replace") or push.stdout.decode("utf-8", "replace"), 300)
                               + f"; commit {sha} stays local, nothing reset")
        self.result["pushed"] = True
        self.say(f"PUSHED      {sha} -> {up['short']}")
        return self.finish(EXIT_OK, None, f"committed and pushed {sha}")


def cmd_finalize(repo: Path, opts: dict) -> int:
    return Finalizer(repo, opts).run()


# --------------------------------------------------------------------------------------------- main

def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    want_json = any(a.casefold() in ("-json", "--json") for a in argv)
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass
    try:
        command, opts = parse_args(argv)
        repo = repo_root()
        return {"status": cmd_status, "handoff": cmd_handoff, "finalize": cmd_finalize}[command](repo, opts)
    except UsageError as exc:
        return fail(want_json, EXIT_USAGE, exc.code, str(exc))
    except Exception as exc:  # noqa: BLE001 - internal errors map to exit 4; nothing is reset or cleaned
        return fail(want_json, EXIT_INTERNAL, "INTERNAL", f"{type(exc).__name__}: {exc}")


if __name__ == "__main__":
    sys.exit(main())
