#!/usr/bin/env python3
"""DEV-ENV-002A: bounded Codex delegation runner for Mazovia Offroad.

Modes: implement (optionally corrective via --parent-run-id) and review. The runner prepares a
bounded task, runs the pinned native Codex CLI inside a Windows Job Object with an isolated
configuration and a sanitized environment, captures evidence outside the repository, and validates
the final repository state. It never commits, pushes, resets, restores, cleans or stashes.

Exit codes (the highest-precedence applicable code wins: 1 > 8 > 4 > 5 > 3 > 9 > 6 > 7 > 0; 2 only before Codex starts):
  0  mechanical completion according to the contract; NOT product acceptance. Implement: EXECUTION SUCCESS,
     RESPONSE PRESENT, task_status COMPLETED, SCOPE PASS, FROZEN PASS, HEAD UNCHANGED, TESTS PASS|NOT_APPLICABLE.
     Review: the same with TESTS NOT_APPLICABLE and REVIEW CLEAN.
  1  internal runner error
  2  contract or precondition failure; Codex was not started
  3  Codex execution failure, or final response MISSING / INCOMPLETE (schema or cross-field rules violated)
  4  timeout with confirmed process-tree termination
  5  SCOPE FAIL, FROZEN FAIL or HEAD CHANGED
  6  required test FAIL or NOT_RUN (implement)
  7  review completed (task_status COMPLETED) with FINDINGS
  8  incomplete or unverifiable state: termination unconfirmed, manifest/evidence failure, artifact tampering
  9  task not completed: schema-valid response with task_status BLOCKED or OPEN_DECISION; report.json keeps the
     exact task_status. Never 0; never mapped to review FINDINGS.
The response field task_status (COMPLETED | BLOCKED | OPEN_DECISION) is the only task-completion classifier; free
text and open_decisions records are never parsed for it. No file is reverted, deleted or cleaned for any result.
Windows only; Python >= 3.11 standard library only.
"""
from __future__ import annotations

import argparse
import ctypes
import ctypes.wintypes as wt
import datetime as dt
import hashlib
import json
import os
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

VALIDATED_CODEX_VERSION = "0.155.1"
SCHEMA_VERSION = 1

EXIT_OK, EXIT_INTERNAL, EXIT_PRECONDITION, EXIT_CODEX, EXIT_TIMEOUT = 0, 1, 2, 3, 4
EXIT_SCOPE, EXIT_TESTS, EXIT_FINDINGS, EXIT_INCOMPLETE, EXIT_TASK_NOT_COMPLETED = 5, 6, 7, 8, 9
TASK_STATUSES = ("COMPLETED", "BLOCKED", "OPEN_DECISION")

DISABLED_FEATURES = ("apps", "browser_use", "browser_use_external", "computer_use", "hooks",
                     "image_generation", "multi_agent", "memories", "goals", "plugins")
POLICY_PROTECTED = (".git/**", ".claude/**", ".codex/**", "**/AGENTS.md", "**/CLAUDE.md", "**/.gitignore",
                    "**/.gitattributes", "gradlew", "gradlew.bat", "gradle/wrapper/**")
SENSITIVE_PATTERNS = ("**/.env", "**/.env.*", "**/*.jks", "**/*.keystore", "**/*.p12", "**/*.pfx", "**/*.pem",
                      "**/secrets.properties", "**/google-services.json", "local.properties", "**/.netrc",
                      "**/_netrc", "**/.npmrc", "**/.pypirc", "**/.git-credentials", "**/id_rsa", "**/id_ed25519")
GENERATED_PATTERNS = ("**/build/**", ".gradle/**", ".kotlin/**", ".tmp-gradle/**", "**/.cxx/**",
                      "**/__pycache__/**", ".opencode/node_modules/**")
SELF_PROTECTION = (".claude/settings.json", ".claude/frozen-paths.txt", ".claude/hooks/**", ".claude/scripts/**")
FROZEN_SOURCE = ".claude/frozen-paths.txt"
AUDIT_PATH, DESIGN_PATH = "docs/terrain-ahead/AUDIT.md", "docs/terrain-ahead/DESIGN.md"
WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties"

# Environment: explicit allowlist of inherited names; everything else is dropped.
ENV_PRESERVE = ("SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "PATH", "SYSTEMDRIVE", "PROGRAMDATA", "PROGRAMFILES",
                "PROGRAMFILES(X86)", "PROGRAMW6432", "COMMONPROGRAMFILES", "COMMONPROGRAMFILES(X86)",
                "COMMONPROGRAMW6432", "PROCESSOR_ARCHITECTURE", "NUMBER_OF_PROCESSORS", "OS", "USERPROFILE",
                "HOMEDRIVE", "HOMEPATH", "USERNAME", "USERDOMAIN", "COMPUTERNAME", "LOCALAPPDATA", "APPDATA",
                "CODEX_HOME", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT")
ENV_SENSITIVE_MARKERS = ("TOKEN", "SECRET", "PASSWORD", "PASSWD", "API_KEY", "APIKEY", "AUTH", "BEARER", "CREDENTIAL",
                         "PRIVATE_KEY", "ACCESS_KEY", "SESSION", "COOKIE", "CERT", "SIGNING", "KEYSTORE")
ENV_PROVIDER_PREFIXES = ("AWS_", "AZURE_", "ARM_", "GCP_", "GCLOUD_", "GOOGLE_", "GH_", "GITHUB_", "GITLAB_",
                         "OPENAI_", "ANTHROPIC_", "HF_", "HUGGINGFACE", "NPM_", "DOCKER_", "SSH_", "GPG_", "VAULT_",
                         "KUBE", "SENTRY_", "SLACK_", "STRIPE_", "TWILIO_", "DATABASE_", "PG", "MYSQL_")
CONTRACT_ENV_ALLOWLIST = {"PYTHONHASHSEED": {"0"}}

GRADLE_FLAGS = ("--rerun", "--rerun-tasks", "--continue", "--stacktrace", "--info", "--console=plain")
GRADLE_TASK_RE = re.compile(r"^:?[A-Za-z][A-Za-z0-9_-]*(:[A-Za-z][A-Za-z0-9_-]*)*$")
GRADLE_TEST_FILTER_RE = re.compile(r"^[A-Za-z0-9_.*$]{1,200}$")
GRADLE_MANDATORY = ("--offline", "--no-daemon", "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process")
RUN_ID_RE = re.compile(r"^\d{8}T\d{6}Z-(TA|TASK|DEV-ENV)-[0-9A-Z-]{1,20}-(implement|corrective|review)-[0-9a-f]{8}$")
MODEL_RE = re.compile(r"^[A-Za-z0-9._:-]{1,80}$")

PROMPT_MAX_BYTES = 256 * 1024
IGNORED_HASH_MAX_FILES, IGNORED_HASH_MAX_BYTES = 5000, 512 * 1024 * 1024
REVIEW_BUNDLE_MAX_BYTES = 50 * 1024 * 1024
JUNIT_MAX_BYTES = 20 * 1024 * 1024
TERMINATION_WAIT_SECONDS = 30
SCOPE_NOTE = ("SCOPE VALIDATION IS A FINAL-STATE RESULT CHECK. It does not prove that no transient out-of-scope "
              "write occurred, that nothing outside the repository was written beyond the verified sandbox "
              "denials, or that no unsafe operation was attempted.")
REPO_WRITE_NOTE = ("No runner artifacts are written into the repository. Codex implementation writes inside the "
                   "repository are allowed by allowed_paths and validated afterwards.")
PUSH_NOTE = ("push_prevention = MITIGATED_NOT_PROVEN: sandboxed commands cannot write .git or reach the network "
             "(verified for the sandbox mechanism); HEAD/refs unchanged does not prove that no push occurred.")
LIMITATIONS = (
    SCOPE_NOTE,
    PUSH_NOTE,
    "workspace-write lets Codex touch any repository path during the run, frozen files included; enforcement is the "
    "final-state check.",
    "Codex's own networked process and its non-shell edit path are enforced by Codex policy, not independently proven.",
    "Codex-modified build scripts run unsandboxed on later manual builds; review build-script diffs.",
    "events.jsonl and logs may contain command output produced during the run.",
    "Each sandbox run leaves persistent ACEs on its writable roots and ~/.codex/cap_sid entries (Codex behaviour).",
)

GIT_CALLS: list[list[str]] = []  # every git argv the runner issued (read-only allowlist enforced in git())


class RunnerError(Exception):
    """Precondition or contract failure detected before Codex starts."""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(f"{code}: {detail}" if detail else code)
        self.code, self.detail = code, detail


class ManifestError(Exception):
    pass


# --------------------------------------------------------------------------------------------- basics

def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fs(path) -> str:
    """Path usable beyond MAX_PATH on Windows (\\\\?\\ prefix for long absolute paths)."""
    s = os.path.abspath(str(path))
    if sys.platform == "win32" and len(s) >= 240 and not s.startswith("\\\\?\\"):
        return "\\\\?\\UNC\\" + s[2:] if s.startswith("\\\\") else "\\\\?\\" + s
    return s


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(fs(path), "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def canonical_json(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def write_json(path: Path, obj) -> None:
    path.write_text(json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=True) + "\n", encoding="utf-8")


def _reject_duplicates(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ValueError(f"duplicate JSON key: {key!r}")
        out[key] = value
    return out


def _reject_constant(name):
    raise ValueError(f"non-standard JSON constant: {name}")


def strict_json_loads(text: str):
    return json.loads(text, object_pairs_hook=_reject_duplicates, parse_constant=_reject_constant)


def long_path(p) -> str:
    """Absolute long-form path (expands 8.3 names such as DANIEL~1.DAN) when the path exists."""
    absolute = os.path.abspath(str(p))
    if sys.platform != "win32":
        return absolute
    buf = ctypes.create_unicode_buffer(32768)
    n = ctypes.windll.kernel32.GetLongPathNameW(absolute, buf, 32768)
    return buf.value if 0 < n < 32768 else absolute


def is_within(child, parent) -> bool:
    c, p = long_path(child).rstrip("\\/").casefold(), long_path(parent).rstrip("\\/").casefold()
    return c == p or c.startswith(p + os.sep) or c.startswith(p + "/")


def artifact_root() -> Path:
    return Path(long_path(tempfile.gettempdir())) / "mazovia-codex"


# --------------------------------------------------------------------------------------------- schema

_SCHEMA_KEYWORDS = {"$schema", "$id", "title", "description", "type", "enum", "const", "pattern", "minLength",
                    "maxLength", "minimum", "maximum", "required", "properties", "additionalProperties", "items",
                    "minItems", "maxItems", "uniqueItems"}
_TYPE_CHECKS = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
}


def json_equal(a, b) -> bool:
    """Equality with JSON type semantics: a boolean never equals a number (Python's True == 1 does not apply)."""
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool) and a is b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return a == b
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(json_equal(x, y) for x, y in zip(a, b))
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(json_equal(a[k], b[k]) for k in a)
    return type(a) is type(b) and a == b


def validate_schema(value, schema: dict, where: str = "$") -> list[str]:
    """Validate against the small JSON Schema subset the runner's schemas use. Unknown keywords are errors."""
    errors: list[str] = []
    unknown = set(schema) - _SCHEMA_KEYWORDS
    if unknown:
        return [f"{where}: unsupported schema keyword(s) {sorted(unknown)}"]
    if "type" in schema:
        types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(_TYPE_CHECKS[t](value) for t in types):
            return [f"{where}: expected type {types}"]
    if "const" in schema and not json_equal(value, schema["const"]):
        errors.append(f"{where}: expected constant {schema['const']!r}")
    if "enum" in schema and not any(json_equal(value, option) for option in schema["enum"]):
        errors.append(f"{where}: value not in enum {schema['enum']}")
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(f"{where}: shorter than {schema['minLength']}")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errors.append(f"{where}: longer than {schema['maxLength']}")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errors.append(f"{where}: does not match {schema['pattern']}")
    if _TYPE_CHECKS["number"](value):
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{where}: below minimum {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{where}: above maximum {schema['maximum']}")
    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            errors.append(f"{where}: fewer than {schema['minItems']} items")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            errors.append(f"{where}: more than {schema['maxItems']} items")
        if schema.get("uniqueItems") and len({canonical_json(v) for v in value}) != len(value):
            errors.append(f"{where}: items are not unique")
        if "items" in schema:
            for i, item in enumerate(value):
                errors += validate_schema(item, schema["items"], f"{where}[{i}]")
    if isinstance(value, dict):
        props = schema.get("properties", {})
        for key in schema.get("required", []):
            if key not in value:
                errors.append(f"{where}: missing required field {key!r}")
        if schema.get("additionalProperties") is False:
            for key in value:
                if key not in props:
                    errors.append(f"{where}: unknown field {key!r}")
        for key, sub in props.items():
            if key in value:
                errors += validate_schema(value[key], sub, f"{where}.{key}")
    return errors


def load_schema(name: str) -> dict:
    return strict_json_loads((Path(__file__).resolve().parent / name).read_text(encoding="utf-8"))


# --------------------------------------------------------------------------------------------- paths / globs

_FORBIDDEN_PATTERN_CHARS = set('?[]{}!:"<>|')


def canonical_pattern(raw, *, allow_glob: bool = True, allow_dot_git: bool = False) -> str:
    """Canonical repo-relative policy path/pattern, or ValueError."""
    if not isinstance(raw, str) or not raw:
        raise ValueError("empty or non-string path")
    if any(ord(c) < 32 for c in raw):
        raise ValueError("control character or NUL in path")
    s = raw.replace("\\", "/")
    if s.startswith("//"):
        raise ValueError("UNC path")
    if re.match(r"^[A-Za-z]:", s):
        raise ValueError("drive-qualified path")
    if s.startswith("/"):
        raise ValueError("absolute path")
    if s.startswith("./"):
        s = s[2:]
    segments = s.split("/")
    for seg in segments:
        if seg in ("", ".", ".."):
            raise ValueError(f"empty, '.' or '..' segment in {raw!r}")
        if seg.casefold() == ".git" and not allow_dot_git:
            raise ValueError(f"'.git' segment in {raw!r}")
        if _FORBIDDEN_PATTERN_CHARS & set(seg):
            raise ValueError(f"unsupported character in {raw!r}")
        if "*" in seg:
            if not allow_glob:
                raise ValueError(f"wildcard not allowed in {raw!r}")
            if "**" in seg and seg != "**":
                raise ValueError(f"'**' must be a whole segment in {raw!r}")
        if seg != seg.strip() or seg.endswith("."):
            raise ValueError(f"segment with trailing space or dot in {raw!r}")
    return "/".join(segments)


def _segment_matches(pattern_seg: str, seg: str) -> bool:
    if "*" not in pattern_seg:
        return pattern_seg.casefold() == seg.casefold()
    regex = "[^/]*".join(re.escape(part) for part in pattern_seg.casefold().split("*"))
    return re.fullmatch(regex, seg.casefold()) is not None


def glob_match(pattern: str, path: str) -> bool:
    """literal / '*' within one segment / '**' as a whole segment for zero or more segments; case-insensitive."""
    pat, segs = pattern.split("/"), path.split("/")
    memo: dict[tuple[int, int], bool] = {}

    def m(i: int, j: int) -> bool:
        if (i, j) in memo:
            return memo[(i, j)]
        if i == len(pat):
            result = j == len(segs)
        elif pat[i] == "**":
            result = m(i + 1, j) or (j < len(segs) and m(i, j + 1))
        else:
            result = j < len(segs) and _segment_matches(pat[i], segs[j]) and m(i + 1, j + 1)
        memo[(i, j)] = result
        return result

    return m(0, 0)


def match_any(patterns, path: str) -> str | None:
    for p in patterns:
        if glob_match(p, path):
            return p
    return None


def classify(path: str, allowed, frozen) -> str:
    if match_any(POLICY_PROTECTED, path):
        return "POLICY_PROTECTED"
    if match_any(frozen, path):
        return "FROZEN"
    if match_any(allowed, path):
        return "ALLOWED"
    return "OUT_OF_SCOPE"


def is_sensitive(path: str) -> bool:
    return match_any(SENSITIVE_PATTERNS, path) is not None


def is_generated(path: str) -> bool:
    return match_any(GENERATED_PATTERNS, path) is not None


def _literal_prefix(pattern: str) -> list[str]:
    out = []
    for seg in pattern.split("/"):
        if "*" in seg:
            break
        out.append(seg)
    return out


def _covers_everything_under(policy_pattern: str, prefix: list[str]) -> bool:
    """True when every path below `prefix` matches policy_pattern (e.g. 'routing/**' covers 'routing/x')."""
    probe = "/".join(prefix + ["zz-probe-a", "zz-probe-b.kt"]) if prefix else None
    if probe is None:
        return False
    return glob_match(policy_pattern, "/".join(prefix + ["zz-probe-a"])) and glob_match(policy_pattern, probe)


def check_allowed_overlap(allowed: list[str], frozen: list[str]) -> list[str]:
    """Reject allowed patterns that lie inside protected/frozen areas; return 'contains frozen' warnings."""
    warnings = []
    for pattern in allowed:
        prefix = _literal_prefix(pattern)
        whole = "*" not in pattern
        for policy, label in [(p, "POLICY_PROTECTED") for p in POLICY_PROTECTED] + [(f, "FROZEN") for f in frozen]:
            if (whole and glob_match(policy, pattern)) or (not whole and _covers_everything_under(policy, prefix)):
                raise RunnerError("ALLOWED_PATH_INSIDE_" + label, f"{pattern!r} is inside {policy!r}")
        for f in frozen:
            probe = f[:-3] + "/zz-probe.kt" if f.endswith("/**") else f
            if glob_match(pattern, probe):
                warnings.append(f"allowed {pattern!r} contains frozen {f!r}; frozen wins")
    return warnings


# --------------------------------------------------------------------------------------------- environment

def _is_sensitive_env_name(name: str) -> bool:
    upper = name.upper()
    return any(m in upper for m in ENV_SENSITIVE_MARKERS) or upper.startswith(ENV_PROVIDER_PREFIXES)


def sanitize_environment(source: dict, overrides: dict, contract_env: dict | None = None) -> tuple[dict, dict]:
    """Explicit child environment: allowlisted inherited names + runner overrides + allowlisted contract values.

    Returns (env, report). The report holds NAMES only (plus runner-set values, which are runner paths/ids).
    """
    by_upper = {k.upper(): k for k in source}
    env, preserved, dropped = {}, [], []
    for name in ENV_PRESERVE:
        real = by_upper.get(name)
        if real is None:
            continue
        if _is_sensitive_env_name(real):
            dropped.append(real)
            continue
        env[real] = source[real]
        preserved.append(real)
    kept_upper = {k.upper() for k in env}
    dropped += sorted(k for k in source if k.upper() not in kept_upper and k not in dropped)
    for key, value in overrides.items():
        for existing in [k for k in env if k.upper() == key.upper()]:
            del env[existing]
        env[key] = value
    contract_names = []
    for key, value in (contract_env or {}).items():
        if key not in CONTRACT_ENV_ALLOWLIST or value not in CONTRACT_ENV_ALLOWLIST[key] or _is_sensitive_env_name(key):
            raise RunnerError("CONTRACT_ENV_REJECTED", key)
        env[key] = value
        contract_names.append(key)
    report = {"preserved": sorted(preserved), "runner_set": {k: overrides[k] for k in sorted(overrides)},
              "contract_set": sorted(contract_names), "dropped": sorted(set(dropped))}
    return env, report


# --------------------------------------------------------------------------------------------- git (read-only)

_GIT_ALLOWED = {"status", "ls-files", "rev-parse", "symbolic-ref", "for-each-ref", "hash-object", "diff", "version",
                "config", "stash", "worktree"}
# Runner-owned git never inherits GIT_* variables (GIT_EXTERNAL_DIFF, GIT_DIR, GIT_CONFIG_PARAMETERS, ...). HOME and
# XDG_CONFIG_HOME are kept so git reads the same global config (core.autocrlf etc.) the user's own git reads.
GIT_ENV_EXTRA_PRESERVE = ("HOME", "XDG_CONFIG_HOME")
# Command-line-scope config (GIT_CONFIG_COUNT) that beats every config file: no fsmonitor hook, no colour or
# prefix/relative rewriting of evidence patches.
GIT_SAFE_CONFIG = (("core.fsmonitor", "false"), ("color.ui", "false"), ("diff.noprefix", "false"),
                   ("diff.mnemonicPrefix", "false"), ("diff.relative", "false"))
# Every runner diff is evidence: never run external diff programs (GIT_EXTERNAL_DIFF, diff.external,
# diff.<driver>.command) or textconv helpers, and never colour the output.
GIT_DIFF_SAFE_FLAGS = ("--no-ext-diff", "--no-textconv", "--no-color")
_FILTER_KEY_RE = re.compile(r"^filter\.(.+)\.(clean|smudge|process)$", re.IGNORECASE | re.DOTALL)
_GIT_HELPER_OVERRIDES: dict[str, list[tuple[str, str]]] = {}


def git_environment(source: dict | None = None, extra_config=()) -> dict:
    """Explicit environment for runner-owned git: allowlisted names only, no GIT_*, runner-controlled config."""
    source = dict(os.environ if source is None else source)
    by_upper = {k.upper(): k for k in source}
    env = {}
    for name in ENV_PRESERVE + GIT_ENV_EXTRA_PRESERVE:
        real = by_upper.get(name)
        if real is not None and not _is_sensitive_env_name(real):
            env[real] = source[real]
    config = [*GIT_SAFE_CONFIG, *extra_config]
    env["GIT_CONFIG_COUNT"] = str(len(config))
    for i, (key, value) in enumerate(config):
        env[f"GIT_CONFIG_KEY_{i}"], env[f"GIT_CONFIG_VALUE_{i}"] = key, value
    env["GIT_TERMINAL_PROMPT"] = "0"
    return env


def git_helper_overrides(repo, refresh: bool = False) -> list[tuple[str, str]]:
    """Neutralize every configured filter driver (clean/smudge/process helpers) for runner-owned git.

    Filter helpers can run during status (racy entries) and hash-object. An empty command disables a driver and
    required=false keeps git from failing on the disabled driver, so content is hashed and diffed as stored.
    """
    key = long_path(repo).casefold()
    if refresh or key not in _GIT_HELPER_OVERRIDES:
        argv = ["git", "--no-optional-locks", "-C", str(repo), "config", "--null", "--name-only", "--get-regexp",
                r"^filter\..*\.(clean|smudge|process)$"]
        GIT_CALLS.append(argv[4:])
        proc = subprocess.run(argv, capture_output=True, timeout=120, env=git_environment())
        if proc.returncode not in (0, 1):  # 1 = no matching key
            raise ManifestError(f"git config --get-regexp failed: {proc.stderr.decode('utf-8', 'replace').strip()}")
        drivers = set()
        for token in proc.stdout.split(b"\x00"):
            match = _FILTER_KEY_RE.match(token.decode("utf-8", "surrogateescape").strip())
            if match:
                drivers.add(match.group(1))
        overrides = []
        for driver in sorted(drivers):
            overrides += [(f"filter.{driver}.clean", ""), (f"filter.{driver}.smudge", ""),
                          (f"filter.{driver}.process", ""), (f"filter.{driver}.required", "false")]
        _GIT_HELPER_OVERRIDES[key] = overrides
    return _GIT_HELPER_OVERRIDES[key]


def git(repo, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    sub = args[0]
    if sub not in _GIT_ALLOWED or (sub == "stash" and args[1:2] != ("list",)) or \
            (sub == "worktree" and args[1:2] != ("list",)) or (sub == "config" and "--get" not in args) or \
            (sub == "hash-object" and "-w" in args):
        raise AssertionError(f"runner attempted a non-read-only git command: {args}")
    if sub == "diff":
        args = ("diff", *GIT_DIFF_SAFE_FLAGS, *args[1:])
    GIT_CALLS.append(list(args))
    argv = ["git", "--no-optional-locks", "-c", "core.quotepath=off", "-C", str(repo), *args]
    env = git_environment(extra_config=git_helper_overrides(repo))
    proc = subprocess.run(argv, capture_output=True, timeout=300, env=env)
    if check and proc.returncode != 0:
        raise ManifestError(f"git {' '.join(args[:3])} failed: {proc.stderr.decode('utf-8', 'replace').strip()}")
    return proc


def git_text(repo, *args: str) -> str:
    return git(repo, *args).stdout.decode("utf-8", "surrogateescape").strip()


def repo_root_from_cwd() -> Path:
    proc = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, timeout=60,
                          env=git_environment())
    if proc.returncode != 0:
        raise RunnerError("NOT_IN_GIT_REPOSITORY")
    GIT_CALLS.append(["rev-parse", "--show-toplevel"])
    return Path(long_path(proc.stdout.decode("utf-8", "surrogateescape").strip()))


def head_snapshot(repo: Path) -> dict:
    branch = git(repo, "symbolic-ref", "-q", "HEAD", check=False)
    refs = git_text(repo, "for-each-ref", "--format=%(refname)%00%(objectname)")
    stash = git_text(repo, "stash", "list")
    git_dir = Path(git_text(repo, "rev-parse", "--absolute-git-dir"))
    config = git_dir / "config"
    return {
        "branch": branch.stdout.decode("utf-8", "surrogateescape").strip() if branch.returncode == 0 else None,
        "head": git_text(repo, "rev-parse", "HEAD"),
        "refs": sorted(line.replace("\x00", " ") for line in refs.splitlines() if line),
        "stash_count": len([line for line in stash.splitlines() if line]),
        "worktrees": git_text(repo, "worktree", "list", "--porcelain"),
        "git_config_sha256": sha256_file(config) if config.is_file() else None,
    }


# --------------------------------------------------------------------------------------------- manifest

FILE_ATTRIBUTE_REPARSE_POINT = 0x400


def file_state(repo: Path, rel: str, *, hash_content: bool = True) -> dict:
    full = fs(repo / rel)
    try:
        st = os.lstat(full)
    except FileNotFoundError:
        return {"absent": True}
    attrs = getattr(st, "st_file_attributes", 0)
    if stat.S_ISLNK(st.st_mode) or attrs & FILE_ATTRIBUTE_REPARSE_POINT:
        try:
            target = os.readlink(full)
        except OSError:
            target = None
        return {"link": True, "target": target, "size": st.st_size}
    if stat.S_ISDIR(st.st_mode):
        return {"directory": True}
    state = {"size": st.st_size, "fs_mode": oct(st.st_mode)}
    if hash_content:
        state["sha256"] = sha256_file(full)
    return state


def _status_class(xy: str) -> str:
    x, y = xy[0], xy[1]
    if x == "D":
        return "deleted_staged"
    if y == "D":
        return "deleted_unstaged"
    if x != "." and y != ".":
        return "staged+unstaged"
    return "staged" if x != "." else "unstaged"


def _modes(head, index, worktree) -> dict:
    """HEAD, index and worktree modes are kept separately: a staged-only mode change (100644 -> 100755 in the index)
    must stay visible even when the worktree mode does not change (core.filemode=false, deleted worktree file)."""
    return {"mode_head": head, "mode_index": index, "mode_worktree": worktree}


def capture_manifest(repo: Path, frozen) -> dict:
    """Content manifest. `frozen` is the parsed frozen list: ignored FROZEN / POLICY_PROTECTED files are never
    omitted as generated output (classification precedence POLICY_PROTECTED > FROZEN > ALLOWED > OUT_OF_SCOPE)."""
    raw = git(repo, "status", "--porcelain=v2", "-z", "--untracked-files=all", "--renames").stdout
    tokens = raw.split(b"\x00")
    entries: list[dict] = []
    i = 0
    while i < len(tokens):
        token = tokens[i]
        i += 1
        if not token:
            continue
        line = token.decode("utf-8", "surrogateescape")
        kind = line[0]
        if kind == "1":
            _, xy, sub, mh, mi, mw, hh, hi, path = line.split(" ", 8)
            entry = {"path": path, "class": _status_class(xy), "xy": xy, "head_blob": hh, "index_blob": hi,
                     **_modes(mh, mi, mw)}
            if sub != "N...":
                entry["submodule"] = sub
            entry.update(file_state(repo, path))
            entries.append(entry)
        elif kind == "2":
            _, xy, sub, mh, mi, mw, hh, hi, score, path = line.split(" ", 9)
            if i >= len(tokens):
                raise ManifestError("rename record without source path")
            orig = tokens[i].decode("utf-8", "surrogateescape")
            i += 1
            pair = {"src": orig, "dst": path, "score": score}
            dst = {"path": path, "class": "rename_dst", "xy": xy, "head_blob": None, "index_blob": hi,
                   **_modes(None, mi, mw), "rename_pair": pair}
            dst.update(file_state(repo, path))
            src = {"path": orig, "class": "rename_src", "xy": xy, "head_blob": hh, "index_blob": None,
                   **_modes(mh, None, None), "rename_pair": pair}
            src.update(file_state(repo, orig))
            if sub != "N...":
                dst["submodule"] = src["submodule"] = sub
            entries += [dst, src]
        elif kind == "u":
            parts = line.split(" ", 10)
            entry = {"path": parts[10], "class": "unmerged", "xy": parts[1], "head_blob": None, "index_blob": None,
                     "stage_modes": parts[3:6], "stage_blobs": parts[7:10], **_modes(None, None, parts[6])}
            entry.update(file_state(repo, parts[10]))
            entries.append(entry)
        elif kind == "?":
            path = line[2:]
            entry = {"path": path.rstrip("/"), "class": "untracked", "xy": "??", "head_blob": None,
                     "index_blob": None, **_modes(None, None, None)}
            entry.update(file_state(repo, path.rstrip("/")))
            entries.append(entry)
        elif kind == "#":
            continue
        else:
            raise ManifestError(f"unexpected porcelain v2 record {line[:20]!r}")
    for entry in entries:
        entry["sensitive"] = is_sensitive(entry["path"])

    ignored_raw = git(repo, "ls-files", "-z", "--others", "--ignored", "--exclude-standard").stdout
    ignored, generated_files, generated_bytes, hashed_bytes = [], 0, 0, 0
    for token in ignored_raw.split(b"\x00"):
        if not token:
            continue
        path = token.decode("utf-8", "surrogateescape").rstrip("/")
        sensitive = is_sensitive(path)
        policy = classify(path, [], frozen)  # POLICY_PROTECTED / FROZEN decided before any generated omission
        protected = policy in ("POLICY_PROTECTED", "FROZEN")
        if not sensitive and not protected and is_generated(path):
            generated_files += 1
            try:
                generated_bytes += os.lstat(fs(repo / path)).st_size
            except OSError:
                pass
            continue
        state = file_state(repo, path)
        hashed_bytes += state.get("size", 0) or 0
        entry = {"path": path, "class": "sensitive" if sensitive else "ignored_relevant", "xy": "!!",
                 "head_blob": None, "index_blob": None, **_modes(None, None, None), "sensitive": sensitive,
                 "ignored": True}
        entry.update(state)
        ignored.append(entry)
        if len(ignored) > IGNORED_HASH_MAX_FILES or hashed_bytes > IGNORED_HASH_MAX_BYTES:
            raise ManifestError("ignored-relevant hashing budget exceeded")
    key = lambda e: (e["path"].casefold(), e["class"])  # noqa: E731
    return {"entries": sorted(entries, key=key), "ignored": sorted(ignored, key=key),
            "generated": {"files": generated_files, "approx_bytes": generated_bytes}}


def _keyed(manifest: dict) -> dict:
    out = {}
    for e in manifest["entries"] + manifest["ignored"]:
        out[(e["path"].casefold(), "src" if e["class"] == "rename_src" else "main")] = e
    return out


def manifest_equal(a: dict, b: dict) -> bool:
    return canonical_json(_keyed_list(a)) == canonical_json(_keyed_list(b))


def _keyed_list(m: dict):
    return sorted([list(k), v] for k, v in _keyed(m).items())


def manifest_diff(reference: dict, current: dict) -> list[dict]:
    ref, cur = _keyed(reference), _keyed(current)
    changed = []
    for key in sorted(set(ref) | set(cur)):
        before, after = ref.get(key), cur.get(key)
        if canonical_json(before) != canonical_json(after):
            changed.append({"path": (after or before)["path"], "role": key[1], "before": before, "after": after})
    return changed


def evaluate_scope(changes: list[dict], allowed, frozen) -> dict:
    violations, frozen_hits, details = [], [], []
    for change in changes:
        path = change["path"]
        sides = [s for s in (change["before"], change["after"]) if s]
        cls = classify(path, allowed, frozen)
        sensitive = is_sensitive(path)
        reasons = []
        if any(s.get("link") or s.get("directory") or s.get("submodule") for s in sides):
            reasons.append("LINK_OR_UNHASHABLE")
        if cls == "POLICY_PROTECTED":
            reasons.append("POLICY_PROTECTED")
        if sensitive:
            reasons.append("SENSITIVE")
        if cls == "OUT_OF_SCOPE":
            reasons.append("OUT_OF_SCOPE")
        if cls == "FROZEN":
            frozen_hits.append(path)
        side = change["after"] or change["before"]
        details.append({"path": path, "role": change["role"], "class": side["class"], "policy": cls,
                        "sensitive": sensitive, "violations": reasons})
        if reasons:
            violations.append({"path": path, "role": change["role"], "reasons": reasons})
    return {"scope": "FAIL" if violations else "PASS", "frozen": "FAIL" if frozen_hits else "PASS",
            "violations": violations, "frozen_hits": sorted(set(frozen_hits)), "changes": details}


# --------------------------------------------------------------------------------------------- Windows Job Object

JobObjectBasicAccountingInformation, JobObjectExtendedLimitInformation = 1, 9
JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
CREATE_SUSPENDED, CREATE_NEW_PROCESS_GROUP = 0x4, 0x200


class _IoCounters(ctypes.Structure):
    _fields_ = [(n, ctypes.c_ulonglong) for n in ("ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                                                    "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]


class _BasicLimit(ctypes.Structure):
    _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong), ("PerJobUserTimeLimit", ctypes.c_longlong),
                ("LimitFlags", wt.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wt.DWORD),
                ("Affinity", ctypes.c_size_t), ("PriorityClass", wt.DWORD), ("SchedulingClass", wt.DWORD)]


class _ExtendedLimit(ctypes.Structure):
    _fields_ = [("BasicLimitInformation", _BasicLimit), ("IoInfo", _IoCounters),
                ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]


class _Accounting(ctypes.Structure):
    _fields_ = [("TotalUserTime", ctypes.c_longlong), ("TotalKernelTime", ctypes.c_longlong),
                ("ThisPeriodTotalUserTime", ctypes.c_longlong), ("ThisPeriodTotalKernelTime", ctypes.c_longlong),
                ("TotalPageFaultCount", wt.DWORD), ("TotalProcesses", wt.DWORD),
                ("ActiveProcesses", wt.DWORD), ("TotalTerminatedProcesses", wt.DWORD)]


class Job:
    """KILL_ON_JOB_CLOSE job without breakaway; processes start suspended and resume only after assignment."""

    def __init__(self):
        self.k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self.k32.CreateJobObjectW.restype = wt.HANDLE
        self.handle = self.k32.CreateJobObjectW(None, None)
        if not self.handle:
            raise OSError(ctypes.get_last_error(), "CreateJobObjectW")
        limit = _ExtendedLimit()
        limit.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.k32.SetInformationJobObject(wt.HANDLE(self.handle), JobObjectExtendedLimitInformation,
                                                ctypes.byref(limit), ctypes.sizeof(limit)):
            raise OSError(ctypes.get_last_error(), "SetInformationJobObject")

    def spawn(self, argv, cwd, env, stdin, stdout, stderr) -> subprocess.Popen:
        proc = subprocess.Popen(argv, cwd=str(cwd), env=env, stdin=stdin, stdout=stdout, stderr=stderr,
                                creationflags=CREATE_SUSPENDED | CREATE_NEW_PROCESS_GROUP)
        handle = wt.HANDLE(int(proc._handle))  # noqa: SLF001 - documented CPython attribute on Windows
        if not self.k32.AssignProcessToJobObject(wt.HANDLE(self.handle), handle):
            error = ctypes.get_last_error()
            self.k32.TerminateProcess(handle, 1)
            raise OSError(error, "AssignProcessToJobObject")
        status = ctypes.WinDLL("ntdll").NtResumeProcess(handle)
        if status != 0:
            self.terminate_and_confirm()
            raise OSError(status, "NtResumeProcess")
        return proc

    def active(self) -> int:
        info = _Accounting()
        if not self.k32.QueryInformationJobObject(wt.HANDLE(self.handle), JobObjectBasicAccountingInformation,
                                                  ctypes.byref(info), ctypes.sizeof(info), None):
            raise OSError(ctypes.get_last_error(), "QueryInformationJobObject")
        return int(info.ActiveProcesses)

    def terminate_and_confirm(self, wait_seconds: float = TERMINATION_WAIT_SECONDS) -> bool:
        self.k32.TerminateJobObject(wt.HANDLE(self.handle), 1)
        deadline = time.monotonic() + wait_seconds
        while time.monotonic() < deadline:
            try:
                if self.active() == 0:
                    return True
            except OSError:
                return False
            time.sleep(0.2)
        return False

    def close(self) -> None:
        if self.handle:
            self.k32.CloseHandle(wt.HANDLE(self.handle))
            self.handle = None


def run_contained(argv, cwd, env, timeout: float, stdout_path: Path, stderr_path: Path,
                  stdin_bytes: bytes | None = None) -> dict:
    """Run argv inside a fresh Job Object. termination_confirmed is True only when ActiveProcesses reached 0."""
    result = {"argv": [str(a) for a in argv], "start_utc": utc_now(), "exit_code": None, "timed_out": False,
              "termination_confirmed": False, "stragglers_terminated": 0, "spawn_error": None}
    job = Job()
    try:
        with open(stdout_path, "wb") as out, open(stderr_path, "wb") as err:
            try:
                proc = job.spawn(argv, cwd, env, subprocess.PIPE if stdin_bytes is not None else subprocess.DEVNULL,
                                 out, err)
            except OSError as exc:
                result["spawn_error"] = f"{type(exc).__name__}: {exc}"
                result["termination_confirmed"] = job.terminate_and_confirm()
                return result
            if stdin_bytes is not None:
                def feed():
                    try:
                        proc.stdin.write(stdin_bytes)
                        proc.stdin.close()
                    except OSError:
                        pass
                threading.Thread(target=feed, daemon=True).start()
            try:
                result["exit_code"] = proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                result["timed_out"] = True
                result["termination_confirmed"] = job.terminate_and_confirm()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass
                return result
            try:
                remaining = job.active()
            except OSError:
                remaining = None  # unknown: terminate and require confirmation instead of assuming an empty job
            if remaining == 0:
                result["termination_confirmed"] = True
            else:
                result["stragglers_terminated"] = remaining or 0
                result["termination_confirmed"] = job.terminate_and_confirm()
            return result
    finally:
        result["end_utc"] = utc_now()
        job.close()


# --------------------------------------------------------------------------------------------- Codex resolution

def codex_prefix(override: str | None) -> tuple[list[str], str]:
    if override:
        path = Path(override)
        return ([sys.executable, str(path)] if path.suffix.lower() == ".py" else [str(path)]), "OVERRIDE"
    roots = []
    shim = shutil.which("codex")
    if shim:
        roots.append(Path(shim).parent)
    if os.environ.get("APPDATA"):
        roots.append(Path(os.environ["APPDATA"]) / "npm")
    rel = Path("node_modules/@openai/codex/node_modules/@openai/codex-win32-x64/vendor/x86_64-pc-windows-msvc/bin/codex.exe")
    for root in roots:
        candidate = root / rel
        if candidate.is_file():
            return [long_path(candidate)], "DEFAULT"
    raise RunnerError("CODEX_NOT_FOUND", "native codex.exe not found under the npm installation")


def run_small(argv, env, timeout=120) -> str:
    proc = subprocess.run(argv, capture_output=True, env=env, timeout=timeout)
    if proc.returncode != 0:
        raise RunnerError("CODEX_PREFLIGHT_FAILED", f"{argv[-2:]} exited {proc.returncode}")
    return proc.stdout.decode("utf-8", "replace")


def codex_preflight(prefix, env) -> dict:
    version_out = run_small([*prefix, "--version"], env).strip()
    match = re.fullmatch(r"codex-cli (\S+)", version_out)
    if not match or match.group(1) != VALIDATED_CODEX_VERSION:
        raise RunnerError("CODEX_VERSION_UNVALIDATED", f"found {version_out!r}, pinned {VALIDATED_CODEX_VERSION}")
    features = {line.split()[0] for line in run_small([*prefix, "features", "list"], env).splitlines() if line.strip()}
    missing = [f for f in DISABLED_FEATURES if f not in features]
    if missing:
        raise RunnerError("CODEX_FEATURE_UNKNOWN", ",".join(missing))
    try:
        models_json = strict_json_loads(run_small([*prefix, "debug", "models"], env))
    except ValueError as exc:
        raise RunnerError("CODEX_MODEL_LIST_UNAVAILABLE", str(exc)) from exc
    items = models_json.get("models", models_json) if isinstance(models_json, dict) else models_json
    items = items if isinstance(items, list) else list(items.values()) if isinstance(items, dict) else []
    slugs = sorted({m.get("slug") or m.get("id") for m in items if isinstance(m, dict) and (m.get("slug") or m.get("id"))})
    return {"version": match.group(1), "models": slugs}


def codex_exec_argv(prefix, mode: str, contract: dict, repo: Path, run_dir: Path, schema_path: Path) -> list[str]:
    argv = [*prefix, "exec", "--ignore-user-config", "--ignore-rules", "--strict-config", "--ephemeral",
            "-s", "workspace-write" if mode == "implement" else "read-only",
            "-c", 'approval_policy="never"', "-c", 'web_search="disabled"', "-c", 'windows.sandbox="elevated"',
            "-m", contract["model"], "-c", f'model_reasoning_effort="{contract["reasoning_effort"]}"']
    for feature in DISABLED_FEATURES:
        argv += ["--disable", feature]
    argv += ["--json", "-o", str(run_dir / "final.txt"), "--output-schema", str(schema_path), "-C", str(repo), "-"]
    return argv


# --------------------------------------------------------------------------------------------- contract

def validate_contract(contract, cli_mode: str, cli_parent: str | None) -> dict:
    errors = validate_schema(contract, load_schema("codex_contract.schema.json"))
    if errors:
        raise RunnerError("CONTRACT_INVALID", "; ".join(errors[:10]))
    mode = contract["mode"]
    if mode != cli_mode:
        raise RunnerError("MODE_MISMATCH", f"cli {cli_mode} vs contract {mode}")
    if (cli_parent or None) != contract["parent_run_id"]:
        raise RunnerError("PARENT_MISMATCH", "--parent-run-id and contract.parent_run_id differ")
    if mode == "review":
        if contract["parent_run_id"] is not None:
            raise RunnerError("CONTRACT_INVALID", "parent_run_id is only valid in implement mode")
        if contract["allowed_paths"] or contract["required_tests"]:
            raise RunnerError("CONTRACT_INVALID", "review requires allowed_paths [] and required_tests []")
    else:
        if contract["subject_run_id"] is not None:
            raise RunnerError("CONTRACT_INVALID", "subject_run_id is only valid in review mode")
        if not 1 <= len(contract["allowed_paths"]) <= 64:
            raise RunnerError("CONTRACT_INVALID", "implement requires 1..64 allowed_paths")
    for key in ("parent_run_id", "subject_run_id"):
        if contract[key] is not None and not RUN_ID_RE.match(contract[key]):
            raise RunnerError("CONTRACT_INVALID", f"{key} is not a run id")
    if not MODEL_RE.match(contract["model"]):
        raise RunnerError("CONTRACT_INVALID", "model contains unsupported characters")
    canonical = []
    for raw in contract["allowed_paths"]:
        try:
            canonical.append(canonical_pattern(raw))
        except ValueError as exc:
            raise RunnerError("ALLOWED_PATH_INVALID", str(exc)) from exc
    if len({c.casefold() for c in canonical}) != len(canonical):
        raise RunnerError("ALLOWED_PATH_INVALID", "duplicate allowed path after canonicalization")
    ids = set()
    for test in contract["required_tests"]:
        if test["id"] in ids:
            raise RunnerError("REQUIRED_TEST_INVALID", f"duplicate id {test['id']}")
        ids.add(test["id"])
        validate_gradle_argv(test["argv"])
        if test["cwd"] != ".":
            raise RunnerError("REQUIRED_TEST_INVALID", "cwd must be '.'")
        for key, value in test["env"].items():
            if key not in CONTRACT_ENV_ALLOWLIST or value not in CONTRACT_ENV_ALLOWLIST[key]:
                raise RunnerError("CONTRACT_ENV_REJECTED", key)
    out = dict(contract)
    out["allowed_paths"] = canonical
    return out


def validate_gradle_argv(argv: list[str]) -> None:
    if not argv or argv[0] != "gradlew.bat":
        raise RunnerError("EXECUTABLE_NOT_SANDBOX_VERIFIED", repr(argv[:1]))
    i = 1
    while i < len(argv):
        arg = argv[i]
        if arg == "--tests":
            if i + 1 >= len(argv) or not GRADLE_TEST_FILTER_RE.match(argv[i + 1]):
                raise RunnerError("REQUIRED_TEST_INVALID", "--tests requires a valid filter")
            i += 2
            continue
        if arg in GRADLE_FLAGS or (GRADLE_TASK_RE.match(arg) and not arg.startswith("-")):
            i += 1
            continue
        raise RunnerError("REQUIRED_TEST_INVALID", f"argument not allowed: {arg!r}")


def parse_frozen(raw: bytes) -> list[str]:
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise RunnerError("FROZEN_POLICY_MALFORMED", "not UTF-8") from exc
    entries = []
    for number, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            entries.append(canonical_pattern(line))
        except ValueError as exc:
            raise RunnerError("FROZEN_POLICY_MALFORMED", f"line {number}: {exc}") from exc
    if not entries:
        raise RunnerError("FROZEN_POLICY_MALFORMED", "no entries")
    folded = {e.casefold() for e in entries}
    missing = [s for s in SELF_PROTECTION if s.casefold() not in folded]
    if missing:
        raise RunnerError("FROZEN_POLICY_INCOMPLETE", ",".join(missing))
    return entries


# --------------------------------------------------------------------------------------------- prompts

def _bullets(items) -> str:
    return "\n".join(f"  - {i}" for i in items) or "  (none)"


def implement_prompt(run_id: str, contract: dict, frozen: list[str], task_prompt: str) -> str:
    return f"""=== MAZOVIA CODEX DELEGATION POLICY (runner-generated; authoritative over the task text) ===
Run: {run_id}   Task: {contract['task_id']}   Mode: implement
You are a bounded implementer. You do not own architecture decisions, commit decisions or push decisions.
The runner validates the final repository state independently; your report is informational, not evidence.

ALLOWED PATHS (the only paths you may create, modify, delete or rename):
{_bullets(contract['allowed_paths'])}
POLICY_PROTECTED (never create, modify, delete or rename):
{_bullets(POLICY_PROTECTED)}
FROZEN architecture (never create, modify, delete or rename):
{_bullets(frozen)}
SENSITIVE (never create, modify, print or copy these files or their contents):
{_bullets(SENSITIVE_PATTERNS)}
Glob grammar: literal path; '*' = any characters within one path segment; '**' = zero or more whole segments.

FORBIDDEN: git commit, git push, git add, git stash, git reset, git restore, git clean, git checkout, git switch,
git rebase, git merge, git cherry-pick, git revert, git tag, creating or deleting branches, any network access,
dependency downloads, and any write outside the allowed paths.
If the task appears to require a FROZEN path: stop that part and add an open_decisions entry with kind
"FROZEN CLASS EXCEPTION" (affected path, reason, proposed architectural change, alternatives considered).
If it appears to require a POLICY_PROTECTED path: stop that part and add an open_decisions entry with kind
"POLICY-PROTECTED PATH". Never widen the scope yourself.
Temporary files belong in %TEMP%. If you run Gradle yourself, use --offline; such results are informational only;
the runner executes required tests separately.
FINAL RESPONSE: one JSON object matching the provided output schema with mode "implement" and verdict
"NOT_APPLICABLE". task_status is authoritative:
  "COMPLETED"     = the requested task was completed and no unresolved decision prevents completion
                    (open_decisions must then be empty);
  "BLOCKED"       = a policy, safety, capability or prerequisite blocker prevented completion;
  "OPEN_DECISION" = completion needs an explicit user/architecture decision (at least one open_decisions entry).
Findings may be empty.

=== TASK PROMPT (from Claude) ===
{task_prompt}
"""


def review_prompt(run_id: str, contract: dict, frozen: list[str], bundle: Path, task_prompt: str) -> str:
    return f"""=== MAZOVIA CODEX REVIEW POLICY (runner-generated; authoritative over the task text) ===
Run: {run_id}   Task: {contract['task_id']}   Mode: review (read-only)
You are an independent reviewer. Do not modify any file. Do not run git commands that change state. No network.
The evidence bundle is at:
  {bundle}
Read bundle-index.json first. It lists the task prompt and contract, baseline, frozen snapshot, full diffs
(HEAD, staged, unstaged), name-status, the complete untracked list, full copies of new non-sensitive files,
the final content manifest, subject test evidence (subject_tests.json) and the implementer's final response.
The implementer's response is INFORMATIONAL ONLY and is not evidence of correctness.
Do not rely on the diff alone: inspect the current source in the repository where needed.
Sensitive files are listed by hash only; their contents are intentionally withheld.
Frozen architecture (a change to any of these is at least a HIGH finding):
{_bullets(frozen)}
POLICY_PROTECTED paths:
{_bullets(POLICY_PROTECTED)}
You cannot run Gradle here; do not claim test results you did not observe. Frozen or policy concerns belong in
open_decisions ("FROZEN CLASS EXCEPTION" / "POLICY-PROTECTED PATH").
FINAL RESPONSE: one JSON object matching the provided output schema with mode "review".
If you completed the review: task_status "COMPLETED" and verdict "CLEAN" (findings empty) or "FINDINGS" (at least
one finding with severity HIGH, MEDIUM or LOW); open_decisions empty.
If you could not complete the review: task_status "BLOCKED" (a blocker prevented it) or "OPEN_DECISION" (an explicit
decision is needed; at least one open_decisions entry), with verdict "NOT_APPLICABLE".

=== REVIEW INSTRUCTIONS (from Claude) ===
{task_prompt}
"""


# --------------------------------------------------------------------------------------------- run context

class Run:
    def __init__(self, mode: str, kind: str, contract: dict, repo: Path, run_dir: Path, run_id: str):
        self.mode, self.kind, self.contract, self.repo, self.run_dir, self.run_id = mode, kind, contract, repo, run_dir, run_id
        self.status = {"EXECUTION": "INCOMPLETE", "RESPONSE": "MISSING", "SCOPE": "INCOMPLETE", "FROZEN": "INCOMPLETE",
                       "HEAD": "INCOMPLETE", "TESTS": "NOT_APPLICABLE" if mode == "review" else "INCOMPLETE",
                       "REVIEW": "NOT_APPLICABLE" if mode == "implement" else "INCOMPLETE"}
        self.report: dict = {"run_id": run_id, "mode": mode, "kind": kind, "task_id": contract["task_id"],
                             "parent_run_id": contract.get("parent_run_id"),
                             "subject_run_id": contract.get("subject_run_id"), "started_utc": utc_now(),
                             "limitations": list(LIMITATIONS), "scope_note": SCOPE_NOTE,
                             "repository_writes_note": REPO_WRITE_NOTE, "push_prevention": "MITIGATED_NOT_PROVEN",
                             "codex_started": False, "reasons": []}
        self.pre_hashes: dict[str, str] = {}
        self.evidence_failure = False
        self.tampered = False
        self.task_status: str | None = None  # set only from a schema-valid, cross-field-valid response

    def write_tracked(self, name: str, data: bytes) -> Path:
        path = self.run_dir / name
        os.makedirs(fs(path.parent), exist_ok=True)
        with open(fs(path), "wb") as fh:
            fh.write(data)
        self.pre_hashes[name] = sha256_bytes(data)
        return path

    def write_tracked_json(self, name: str, obj) -> Path:
        return self.write_tracked(name, (json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=True) + "\n").encode())

    def reason(self, text: str) -> None:
        self.report["reasons"].append(text)


def new_run_dir(root: Path, task_id: str, kind: str) -> tuple[str, Path]:
    root.mkdir(parents=True, exist_ok=True)
    for _ in range(5):
        run_id = f"{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{task_id}-{kind}-{secrets.token_hex(4)}"
        try:
            (root / run_id).mkdir()
            return run_id, root / run_id
        except FileExistsError:
            continue
    raise RuntimeError("run id collision retries exhausted")


def artifact_hashes(run_dir: Path) -> dict:
    out = {}
    for path in sorted(run_dir.rglob("*")):
        rel = path.relative_to(run_dir).as_posix()
        if not path.is_file() or rel == "report.json" or rel.split("/")[0] in ("codex-tmp", "test-tmp"):
            continue
        out[rel] = sha256_file(path)
    return out


def load_verified_run(run_id: str, role: str) -> tuple[Path, dict]:
    if not RUN_ID_RE.match(run_id):
        raise RunnerError(f"{role}_INVALID", "bad run id")
    run_dir = artifact_root() / run_id
    report_path = run_dir / "report.json"
    if not report_path.is_file():
        raise RunnerError(f"{role}_NOT_FOUND", run_id)
    report = strict_json_loads(report_path.read_text(encoding="utf-8"))
    for rel, digest in report.get("artifact_hashes", {}).items():
        path = run_dir / rel
        if not path.is_file() or sha256_file(path) != digest:
            raise RunnerError(f"{role}_ARTIFACTS_TAMPERED", rel)
    if not report.get("artifact_hashes"):
        raise RunnerError(f"{role}_ARTIFACTS_MISSING", run_id)
    return run_dir, report


def final_state_of(run_dir: Path, report: dict) -> dict:
    name = "post-test-state.json" if report.get("tests", {}).get("post_test_state_captured") else "post-state.json"
    state = strict_json_loads((run_dir / name).read_text(encoding="utf-8"))
    if not state.get("stable"):
        raise RunnerError("PRIOR_STATE_UNSTABLE", name)
    return state


def capture_state(repo: Path, stable: bool, frozen) -> dict:
    git_helper_overrides(repo, refresh=True)
    return {"captured_utc": utc_now(), "stable": stable, "head": head_snapshot(repo),
            "manifest": capture_manifest(repo, frozen)}


# --------------------------------------------------------------------------------------------- baseline

def capture_baseline(run: Run, frozen_raw: bytes, prompt_bytes: bytes, contract_bytes: bytes, tools: dict) -> dict:
    repo = run.repo
    git_helper_overrides(repo, refresh=True)
    frozen = parse_frozen(frozen_raw)
    docs = {}
    for label, rel in (("audit", AUDIT_PATH), ("design", DESIGN_PATH)):
        path = repo / rel
        if not path.is_file():
            raise RunnerError("DOC_MISSING", rel)
        docs[label] = {"path": rel, "raw_sha256": sha256_file(path), "git_blob": git_text(repo, "hash-object", rel)}
    expected = run.contract["expected_docs"]
    for label, key in (("audit", "audit_git_blob"), ("design", "design_git_blob")):
        if expected[key] is not None and expected[key] != docs[label]["git_blob"]:
            raise RunnerError("DOC_BLOB_MISMATCH", f"{label}: expected {expected[key]}, found {docs[label]['git_blob']}")
    head = head_snapshot(repo)
    if head["branch"] is None:
        raise RunnerError("DETACHED_HEAD")
    origin = git(repo, "rev-parse", "--verify", "-q", "refs/remotes/origin/main", check=False)
    wrapper = repo / WRAPPER_PROPERTIES
    config = {k: (git(repo, "config", "--get", k, check=False).stdout.decode().strip() or None)
              for k in ("core.autocrlf", "core.ignorecase", "core.symlinks")}
    return {
        "run_id": run.run_id, "task_id": run.contract["task_id"], "mode": run.mode, "kind": run.kind,
        "parent_run_id": run.contract["parent_run_id"], "subject_run_id": run.contract["subject_run_id"],
        "captured_utc": utc_now(), "repo_root": str(repo),
        "origin_main": origin.stdout.decode().strip() if origin.returncode == 0 else "MISSING",
        "head": head, "docs": docs,
        "frozen": {"source": FROZEN_SOURCE, "sha256": sha256_bytes(frozen_raw), "entries": frozen},
        "prompt_sha256": sha256_bytes(prompt_bytes), "contract_sha256": sha256_bytes(contract_bytes),
        "tools": tools, "git_config": config,
        "gradle_wrapper": {"path": WRAPPER_PROPERTIES, "sha256": sha256_file(wrapper),
                           "distribution_url": _distribution_url(wrapper.read_bytes())} if wrapper.is_file() else None,
        "manifest": capture_manifest(repo, frozen),
    }


def _distribution_url(raw: bytes) -> str | None:
    for line in raw.decode("utf-8", "replace").splitlines():
        if line.strip().startswith("distributionUrl="):
            return line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\=", "=")
    return None


# --------------------------------------------------------------------------------------------- tests (sandboxed)

CANARY_JS = r"""'use strict';
const fs = require('fs'), path = require('path');
const [tempDir, repo, runDir, userProbe, rid] = process.argv.slice(2);
const res = {};
function w(key, p) { try { fs.writeFileSync(p, 'canary'); res[key] = 'OK'; } catch (e) { res[key] = 'DENIED:' + (e.code || e.message); } }
w('temp_write', path.join(tempDir, 'canary-temp-' + rid + '.txt'));
w('git_write', path.join(repo, '.git', 'mazovia-canary-' + rid));
w('sibling_write', path.join(runDir, 'canary-sibling-' + rid + '.txt'));
w('userprofile_write', userProbe);
const ac = new AbortController();
const timer = setTimeout(() => ac.abort(), 10000);
fetch('https://example.com', { signal: ac.signal })
  .then((r) => { res.network = 'OK:' + r.status; })
  .catch((e) => { res.network = 'DENIED:' + ((e.cause && e.cause.code) || e.name); })
  .finally(() => { clearTimeout(timer); process.stdout.write(JSON.stringify(res) + '\n'); });
"""
CANARY_EXPECTED = {"temp_write": "OK", "git_write": "DENIED", "sibling_write": "DENIED",
                   "userprofile_write": "DENIED", "network": "DENIED"}


def resolve_gradle_distribution(distribution_url: str | None) -> tuple[Path, Path]:
    """(gradle.bat, read-only dependency cache) from the user's existing wrapper distribution cache; no download."""
    if not distribution_url:
        raise RunnerError("GRADLE_DISTRIBUTION_UNRESOLVED", "no distributionUrl at baseline")
    name = distribution_url.rsplit("/", 1)[-1]
    m = re.fullmatch(r"(gradle-([0-9][0-9A-Za-z.\-]*)-(bin|all))\.zip", name)
    if not m:
        raise RunnerError("GRADLE_DISTRIBUTION_UNRESOLVED", f"unsupported distribution {name!r}")
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME") or Path(os.environ["USERPROFILE"]) / ".gradle")
    dist_root = gradle_home / "wrapper" / "dists" / m.group(1)
    candidates = []
    if dist_root.is_dir():
        for hash_dir in dist_root.iterdir():
            bat = hash_dir / f"gradle-{m.group(2)}" / "bin" / "gradle.bat"
            if (hash_dir / f"{name}.ok").is_file() and bat.is_file():
                candidates.append(bat)
    if len(candidates) != 1:
        raise RunnerError("GRADLE_DISTRIBUTION_UNRESOLVED", f"{len(candidates)} candidates for {name}")
    caches = gradle_home / "caches"
    if not (caches / "modules-2").is_dir():
        raise RunnerError("GRADLE_DISTRIBUTION_UNRESOLVED", "no read-only dependency cache")
    return Path(long_path(candidates[0])), Path(long_path(caches))


def test_environment(run: Run, dep_cache: Path, contract_env: dict) -> tuple[dict, dict]:
    tmp = run.run_dir / "test-tmp"
    overrides = {"TEMP": str(tmp), "TMP": str(tmp), "GRADLE_USER_HOME": str(tmp / "gradle-home"),
                 "GRADLE_RO_DEP_CACHE": str(dep_cache), "ANDROID_USER_HOME": str(tmp / "android"),
                 "MAZOVIA_CODEX_RUN_ID": run.run_id}
    return sanitize_environment(dict(os.environ), overrides, contract_env)


def run_canary(run: Run, prefix, env) -> dict:
    node = shutil.which("node")
    if not node:
        return {"passed": False, "detail": "node not found"}
    canary = run.write_tracked("canary.js", CANARY_JS.encode())
    probe = Path(os.environ["USERPROFILE"]) / f"mazovia-canary-{run.run_id}.txt"
    argv = [*prefix, "sandbox", "-P", ":workspace", "-C", str(run.repo), "--", long_path(node), str(canary),
            str(run.run_dir / "test-tmp"), str(run.repo), str(run.run_dir), str(probe), run.run_id]
    result = run_contained(argv, run.repo, env, 90, run.run_dir / "canary.stdout.log", run.run_dir / "canary.stderr.log")
    observed = {}
    try:
        lines = (run.run_dir / "canary.stdout.log").read_text(encoding="utf-8", errors="replace").strip().splitlines()
        observed = strict_json_loads(lines[-1]) if lines else {}
    except ValueError:
        observed = {}
    passed = result["exit_code"] == 0 and result["termination_confirmed"] and all(
        str(observed.get(k, "")).split(":")[0] == v for k, v in CANARY_EXPECTED.items())
    outcome = {"passed": passed, "observed": observed, "expected": CANARY_EXPECTED, "process": result,
               "side_effect_paths_checked": [str(probe), str(run.repo / ".git" / f"mazovia-canary-{run.run_id}")]}
    run.write_tracked_json("canary.json", outcome)
    return outcome


def junit_evidence(repo: Path, since_epoch: float) -> dict:
    files, tests, failures, errors, skipped, unreadable = 0, 0, 0, 0, 0, 0
    roots = [repo / "build"] + [p / "build" for p in repo.iterdir() if p.is_dir() and p.name != ".git"]
    for root in roots:
        results = root / "test-results"
        if not results.is_dir():
            continue
        for xml in results.rglob("*.xml"):
            try:
                st = os.stat(fs(xml))
                if st.st_mtime < since_epoch - 2:
                    continue
                if st.st_size > JUNIT_MAX_BYTES:
                    unreadable += 1
                    continue
                with open(fs(xml), "rb") as fh:
                    suite = ET.parse(fh).getroot()
            except (OSError, ET.ParseError):
                unreadable += 1
                continue
            suites = [suite] if suite.tag == "testsuite" else suite.findall("testsuite")
            files += 1
            for s in suites:
                tests += int(s.get("tests", 0))
                failures += int(s.get("failures", 0))
                errors += int(s.get("errors", 0))
                skipped += int(s.get("skipped", 0))
    return {"files": files, "tests": tests, "failures": failures, "errors": errors, "skipped": skipped,
            "unreadable": unreadable}


def run_required_tests(run: Run, prefix, baseline: dict) -> dict:
    tests = run.contract["required_tests"]
    out = {"status": "NOT_APPLICABLE", "reason": None, "results": [], "canary": None, "post_test_state_captured": False,
           "sandbox_started": False}
    if not tests:
        return out
    try:
        gradle_bat, dep_cache = resolve_gradle_distribution((baseline.get("gradle_wrapper") or {}).get("distribution_url"))
    except RunnerError as exc:
        out.update(status="NOT_RUN", reason="SANDBOX_UNAVAILABLE", detail=exc.code + ": " + exc.detail)
        return out
    base_env, env_report = test_environment(run, dep_cache, {})
    (run.run_dir / "test-tmp").mkdir(exist_ok=True)
    out["environment"] = env_report
    try:
        canary = run_canary(run, prefix, base_env)
    except OSError as exc:  # whether a sandboxed process started is unknown: treat as started and unconfirmed
        canary = {"passed": False, "observed": {}, "detail": f"canary evidence failure: {type(exc).__name__}: {exc}",
                  "process": {"exit_code": None, "timed_out": False, "termination_confirmed": False,
                              "stragglers_terminated": 0, "spawn_error": f"{type(exc).__name__}: {exc}"}}
    process = canary.get("process")
    # Once the canary process exists, sandboxed execution has begun: the caller must capture post-test state, and the
    # canary's termination evidence is kept whatever the canary outcome.
    out["sandbox_started"] = process is not None
    out["canary"] = {"passed": canary["passed"], "observed": canary.get("observed"), "process": process,
                     "detail": canary.get("detail")}
    if process is not None and not process["termination_confirmed"]:
        out.update(status="INCOMPLETE", reason="CANARY_TERMINATION_UNCONFIRMED",
                   detail="runtime canary process-tree termination not confirmed; state unverifiable")
        write_json(run.run_dir / "tests.json", out)
        return out
    if not canary["passed"]:
        out.update(status="NOT_RUN", reason="SANDBOX_UNAVAILABLE", detail="runtime canary failed")
        write_json(run.run_dir / "tests.json", out)
        return out
    (run.run_dir / "tests").mkdir(exist_ok=True)
    statuses = []
    for test in tests:
        env, _ = test_environment(run, dep_cache, test["env"])
        extra = [flag for flag in GRADLE_MANDATORY if flag not in test["argv"]]
        argv = [*prefix, "sandbox", "-P", ":workspace", "-C", str(run.repo), "--", str(gradle_bat),
                *test["argv"][1:], *extra]
        started = time.time()
        stdout_path, stderr_path = run.run_dir / "tests" / f"{test['id']}.stdout.log", run.run_dir / "tests" / f"{test['id']}.stderr.log"
        result = run_contained(argv, run.repo, env, test["timeout_seconds"], stdout_path, stderr_path)
        stderr_text = stderr_path.read_text(encoding="utf-8", errors="replace") if stderr_path.is_file() else ""
        junit = junit_evidence(run.repo, started)
        if not result["termination_confirmed"] or result["spawn_error"] or "windows sandbox failed:" in stderr_text:
            status = "INCOMPLETE"
        elif result["timed_out"] or result["exit_code"] != 0 or junit["failures"] or junit["errors"]:
            status = "FAIL"
        elif not stdout_path.is_file() or not stderr_path.is_file() or junit["unreadable"]:
            status = "INCOMPLETE"
        else:
            status = "PASS"
        statuses.append(status)
        out["results"].append({"id": test["id"], "status": status, "junit": junit,
                               "stdout_log": stdout_path.name, "stderr_log": stderr_path.name, **result})
        if not result["termination_confirmed"]:
            break
    if len(statuses) < len(tests) or "INCOMPLETE" in statuses:
        out["status"] = "INCOMPLETE"
    elif "FAIL" in statuses:
        out["status"] = "FAIL"
    else:
        out["status"] = "PASS"
    write_json(run.run_dir / "tests.json", out)
    return out


# --------------------------------------------------------------------------------------------- response

def validate_response(run: Run, execution: str) -> tuple[str, dict | None]:
    final = run.run_dir / "final.txt"
    if not final.is_file() or not final.read_bytes().strip():
        return "MISSING", None
    try:
        data = strict_json_loads(final.read_text(encoding="utf-8"))
    except (ValueError, UnicodeDecodeError):
        return "INCOMPLETE", None
    errors = validate_schema(data, load_schema("codex_response.schema.json"))
    if errors or response_rule_violation(run.mode, data):
        return "INCOMPLETE", data
    if execution != "SUCCESS":
        return "INCOMPLETE", data
    return "PRESENT", data


def response_rule_violation(mode: str, data: dict) -> str | None:
    """Cross-field rules for a schema-valid response; task_status is the only completion classifier."""
    status, verdict = data["task_status"], data["verdict"]
    if data["mode"] != mode:
        return "mode mismatch"
    if status == "OPEN_DECISION" and not data["open_decisions"]:
        return "OPEN_DECISION requires at least one open_decisions record"
    if status == "COMPLETED" and data["open_decisions"]:
        return "COMPLETED must not carry unresolved open_decisions"
    if status != "COMPLETED" or mode == "implement":
        return None if verdict == "NOT_APPLICABLE" else "verdict must be NOT_APPLICABLE"
    if verdict == "CLEAN" and not data["findings"]:
        return None
    if verdict == "FINDINGS" and data["findings"]:
        return None
    return "review verdict inconsistent with findings"


# --------------------------------------------------------------------------------------------- review bundle

def sensitive_pathspecs() -> list[str]:
    return [f":(exclude,icase,glob){p}" for p in SENSITIVE_PATTERNS]


def build_review_bundle(run: Run, subject: tuple[Path, dict] | None, state: dict) -> dict:
    bundle = run.run_dir / "review-input"
    bundle.mkdir()
    total = 0
    index = {"files": {}, "sensitive_files": [], "untracked": [], "notes": []}

    def put(name: str, data: bytes) -> None:
        nonlocal total
        total += len(data)
        if total > REVIEW_BUNDLE_MAX_BYTES:
            raise ManifestError("review bundle exceeds 50 MiB")
        run.write_tracked(f"review-input/{name}", data)
        index["files"][name] = sha256_bytes(data)

    if subject:
        sdir, sreport = subject
        for name, target in (("prompt.txt", "subject-prompt.txt"), ("contract.json", "subject-contract.json"),
                             ("baseline.json", "subject-baseline.json")):
            put(target, (sdir / name).read_bytes())
        final = sdir / "final.txt"
        subject_tests = sreport.get("tests") or {"status": "NOT_APPLICABLE"}
        subject_tests = {"status": sreport.get("status", {}).get("TESTS", subject_tests.get("status")),
                         "reason": subject_tests.get("reason"), "per_test": subject_tests.get("results", []),
                         "subject_run_id": sreport["run_id"]}
        if final.is_file():
            put("implementer-final.txt", b"INFORMATIONAL - NOT EVIDENCE OF CORRECTNESS\n\n" + final.read_bytes())
    else:
        subject_tests = {"status": "NOT_RUN", "reason": "NO_SUBJECT_RUN", "per_test": []}
        index["notes"].append("No subject run: ignored-file changes cannot be attributed without a baseline.")
    put("subject_tests.json", (json.dumps(subject_tests, indent=2, sort_keys=True) + "\n").encode())
    run.write_tracked("subject_tests.json", (json.dumps(subject_tests, indent=2, sort_keys=True) + "\n").encode())
    put("frozen-paths.txt", (run.repo / FROZEN_SOURCE).read_bytes())
    excludes = ["--", ".", *sensitive_pathspecs()]
    put("diff-head.patch", git(run.repo, "diff", "HEAD", "-M", "--binary", *excludes).stdout)
    put("diff-staged.patch", git(run.repo, "diff", "--cached", "-M", "--binary", *excludes).stdout)
    put("diff-unstaged.patch", git(run.repo, "diff", "-M", "--binary", *excludes).stdout)
    put("name-status.txt", git(run.repo, "diff", "HEAD", "-M", "--name-status", *excludes).stdout)
    manifest = state["manifest"]
    reference = None
    if subject:
        reference = strict_json_loads((subject[0] / "baseline.json").read_text(encoding="utf-8"))["manifest"]
    changed_ignored = {c["path"] for c in manifest_diff(reference, manifest)} if reference else set()
    untracked_list = []
    for entry in manifest["entries"] + manifest["ignored"]:
        new_file = entry["class"] == "untracked" or (entry.get("ignored") and entry["path"] in changed_ignored)
        if entry["class"] == "untracked":
            untracked_list.append(entry["path"])
        if entry.get("sensitive") or is_sensitive(entry["path"]):
            index["sensitive_files"].append({k: entry.get(k) for k in ("path", "class", "xy", "size", "sha256", "absent")})
            continue
        if new_file and not entry.get("absent") and not entry.get("link") and not entry.get("directory"):
            with open(fs(run.repo / entry["path"]), "rb") as fh:
                put(f"untracked/{entry['path']}", fh.read())
            index["untracked"].append(entry["path"])
    put("untracked-files.txt", ("\n".join(untracked_list) + ("\n" if untracked_list else "")).encode())
    put("post-state.json", (json.dumps(state, indent=2, sort_keys=True, ensure_ascii=True) + "\n").encode())
    put("bundle-index.json", (json.dumps(index, indent=2, sort_keys=True, ensure_ascii=True) + "\n").encode())
    return {"path": str(bundle), "bytes": total, "files": len(index["files"]), "sensitive_withheld": len(index["sensitive_files"])}


# --------------------------------------------------------------------------------------------- exit code

def compute_exit(run: Run) -> int:
    s = run.status
    if run.tampered or run.evidence_failure or "INCOMPLETE" in (s["EXECUTION"], s["SCOPE"], s["FROZEN"], s["HEAD"], s["TESTS"]):
        return EXIT_INCOMPLETE
    if s["EXECUTION"] == "TIMEOUT":
        return EXIT_TIMEOUT
    if s["SCOPE"] == "FAIL" or s["FROZEN"] == "FAIL" or s["HEAD"] == "CHANGED":
        return EXIT_SCOPE
    if s["EXECUTION"] == "FAILED" or s["RESPONSE"] != "PRESENT":
        return EXIT_CODEX
    if run.task_status != "COMPLETED":
        return EXIT_TASK_NOT_COMPLETED
    if run.mode == "implement" and s["TESTS"] in ("FAIL", "NOT_RUN"):
        return EXIT_TESTS
    if run.mode == "review" and s["REVIEW"] == "FINDINGS":
        return EXIT_FINDINGS
    if run.mode == "review" and s["REVIEW"] != "CLEAN":
        return EXIT_INCOMPLETE
    return EXIT_OK


def finalize(run: Run, exit_code: int | None = None) -> int:
    for name, digest in run.pre_hashes.items():
        path = run.run_dir / name
        if not path.is_file() or sha256_file(path) != digest:
            run.tampered = True
            run.reason(f"ARTIFACTS TAMPERED: {name}")
    if run.tampered:
        run.report["artifacts"] = "TAMPERED"
    code = exit_code if exit_code is not None and not run.tampered else compute_exit(run)
    outcome = {"execution": run.status["EXECUTION"], "response": run.status["RESPONSE"],
               "task_status": run.task_status, "wrapper_exit_code": code}
    run.report.update(status=run.status, exit_code=code, wrapper_exit_code=code, task_status=run.task_status,
                      outcome=outcome, finished_utc=utc_now(), artifact_hashes=artifact_hashes(run.run_dir))
    run.report["response_findings"] = response_findings(run.report)
    run.report.setdefault("artifacts", "VERIFIED")
    write_json(run.run_dir / "report.json", run.report)
    print(json.dumps({"run_id": run.run_id, "run_dir": str(run.run_dir), "exit_code": code, "outcome": outcome,
                      "status": run.status, "reasons": run.report["reasons"]}, indent=2))
    return code


# --------------------------------------------------------------------------------------------- main flow

def execute(args, contract: dict, prompt_bytes: bytes, contract_bytes: bytes, repo: Path, prefix, binary_kind) -> int:
    mode = contract["mode"]
    kind = "review" if mode == "review" else ("corrective" if contract["parent_run_id"] else "implement")
    root = artifact_root()
    if is_within(root, repo):
        raise RunnerError("ARTIFACT_ROOT_INSIDE_REPOSITORY")
    parent = subject = None
    if contract["parent_run_id"]:
        parent = load_verified_run(contract["parent_run_id"], "PARENT")
        check_parent(parent, contract, root)
    if contract["subject_run_id"]:
        subject = load_verified_run(contract["subject_run_id"], "SUBJECT")
    tools_env, env_report = sanitize_environment(dict(os.environ), {})
    preflight = codex_preflight(prefix, tools_env)
    if contract["model"] not in preflight["models"]:
        raise RunnerError("MODEL_UNKNOWN", contract["model"])
    frozen_path = repo / FROZEN_SOURCE
    if not frozen_path.is_file():
        raise RunnerError("FROZEN_POLICY_MISSING", FROZEN_SOURCE)
    frozen_raw = frozen_path.read_bytes()
    frozen = parse_frozen(frozen_raw)
    warnings = check_allowed_overlap(contract["allowed_paths"], frozen)

    run_id, run_dir = new_run_dir(root, contract["task_id"], kind)
    run = Run(mode, kind, contract, repo, run_dir, run_id)
    run.report.update(codex_binary=binary_kind, codex_version=preflight["version"], frozen_overlap_warnings=warnings)
    try:
        run.write_tracked("contract.json", contract_bytes)
        run.write_tracked("prompt.txt", prompt_bytes)
        run.write_tracked("frozen-paths.txt", frozen_raw)
        node = shutil.which("node")
        tools = {"codex_path": prefix[-1], "codex_binary": binary_kind, "codex_version": preflight["version"],
                 "python": sys.version, "git": git_text(repo, "version"),
                 "node": subprocess.run([node, "--version"], capture_output=True, text=True, timeout=60).stdout.strip() if node else "MISSING",
                 "response_schema_sha256": sha256_file(Path(__file__).resolve().parent / "codex_response.schema.json")}
        try:
            baseline = capture_baseline(run, frozen_raw, prompt_bytes, contract_bytes, tools)
        except RunnerError:
            raise
        except (ManifestError, OSError, subprocess.SubprocessError) as exc:
            run.evidence_failure = True
            run.reason(f"BASELINE_INCOMPLETE: {exc}")
            return finalize(run)
        run.write_tracked_json("baseline.json", baseline)
        precondition_state(run, baseline, parent, subject)
    except RunnerError as exc:
        run.reason(f"{exc.code}: {exc.detail}")
        run.report["precondition_failure"] = exc.code
        return finalize(run, EXIT_PRECONDITION)
    return run_codex_and_validate(run, prefix, baseline, frozen, parent, subject)


def check_parent(parent, contract, root: Path) -> None:
    pdir, preport = parent
    pcontract = strict_json_loads((pdir / "contract.json").read_text(encoding="utf-8"))
    if preport.get("mode") != "implement" or pcontract.get("parent_run_id") is not None:
        raise RunnerError("PARENT_NOT_ELIGIBLE", "parent must be a first implement run")
    if preport.get("exit_code") in (EXIT_INTERNAL, EXIT_PRECONDITION, EXIT_INCOMPLETE) or not preport.get("codex_started"):
        raise RunnerError("PARENT_NOT_ELIGIBLE", "parent did not complete with a stable state")
    if pcontract["task_id"] != contract["task_id"]:
        raise RunnerError("PARENT_TASK_MISMATCH")
    canon = lambda paths: sorted(canonical_pattern(p).casefold() for p in paths)  # noqa: E731
    if canon(pcontract["allowed_paths"]) != canon(contract["allowed_paths"]):
        raise RunnerError("PARENT_SCOPE_MISMATCH", "allowed_paths must be identical")
    for other in root.iterdir() if root.is_dir() else []:
        marker, ocontract = other / "codex-started", other / "contract.json"
        if marker.is_file() and ocontract.is_file():
            try:
                if strict_json_loads(ocontract.read_text(encoding="utf-8")).get("parent_run_id") == preport["run_id"]:
                    raise RunnerError("CORRECTIVE_ALREADY_USED", other.name)
            except ValueError:
                continue


def precondition_state(run: Run, baseline: dict, parent, subject) -> None:
    manifest = baseline["manifest"]
    if run.mode == "implement" and parent is None:
        if manifest["entries"]:
            raise RunnerError("REPOSITORY_NOT_CLEAN", f"{len(manifest['entries'])} porcelain entries")
    if parent is not None:
        pdir, preport = parent
        pbase = strict_json_loads((pdir / "baseline.json").read_text(encoding="utf-8"))
        pfinal = final_state_of(pdir, preport)
        if canonical_json(baseline["head"]) != canonical_json(pbase["head"]):
            raise RunnerError("PARENT_HEAD_MISMATCH")
        if not manifest_equal(manifest, pfinal["manifest"]):
            raise RunnerError("PARENT_STATE_MISMATCH", "current content differs from the parent's final state")
    if subject is not None:
        sdir, sreport = subject
        if sreport.get("mode") != "implement":
            raise RunnerError("SUBJECT_NOT_ELIGIBLE", "subject must be an implement run")
        sfinal = final_state_of(sdir, sreport)
        if canonical_json(baseline["head"]) != canonical_json(sfinal["head"]) or \
                not manifest_equal(manifest, sfinal["manifest"]):
            raise RunnerError("SUBJECT_STATE_MISMATCH")


def run_codex_and_validate(run: Run, prefix, baseline: dict, frozen: list[str], parent, subject) -> int:
    repo, run_dir, contract = run.repo, run.run_dir, run.contract
    codex_tmp = run_dir / "codex-tmp"
    codex_tmp.mkdir()
    overrides = {"TEMP": str(codex_tmp), "TMP": str(codex_tmp), "GRADLE_USER_HOME": str(codex_tmp / "gradle-home"),
                 "ANDROID_USER_HOME": str(codex_tmp / "android"), "MAZOVIA_CODEX_RUN_ID": run.run_id}
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME") or Path(os.environ.get("USERPROFILE", "")) / ".gradle")
    if (gradle_home / "caches" / "modules-2").is_dir():
        overrides["GRADLE_RO_DEP_CACHE"] = long_path(gradle_home / "caches")
    env, env_report = sanitize_environment(dict(os.environ), overrides)
    run.report["environment"] = {"codex": env_report}
    task_prompt = (run_dir / "prompt.txt").read_text(encoding="utf-8")
    if run.mode == "review":
        pre_state = capture_state(repo, True, frozen)
        try:
            run.report["review_bundle"] = build_review_bundle(run, subject, pre_state)
        except (ManifestError, OSError) as exc:
            run.evidence_failure = True
            run.status["REVIEW"] = "INCOMPLETE"
            run.reason(f"REVIEW_EVIDENCE_INCOMPLETE: {exc}")
            return finalize(run)
        effective = review_prompt(run.run_id, contract, frozen, run_dir / "review-input", task_prompt)
    else:
        pre_state = {"head": baseline["head"], "manifest": baseline["manifest"]}
        effective = implement_prompt(run.run_id, contract, frozen, task_prompt)
    run.write_tracked("prompt-effective.txt", effective.encode("utf-8"))
    schema_path = Path(__file__).resolve().parent / "codex_response.schema.json"
    argv = codex_exec_argv(prefix, run.mode, contract, repo, run_dir, schema_path)
    run.write_tracked_json("codex-argv.json", {"argv": argv, "env_names": sorted(env), "cwd": str(repo)})
    (run_dir / "codex-started").write_text(utc_now() + "\n", encoding="utf-8")
    run.report["codex_started"] = True
    result = run_contained(argv, repo, env, contract["timeout_seconds"], run_dir / "events.jsonl",
                           run_dir / "stderr.log", stdin_bytes=effective.encode("utf-8"))
    run.report["codex_process"] = result
    if not result["termination_confirmed"]:
        run.status["EXECUTION"] = "INCOMPLETE"
    elif result["spawn_error"]:
        run.status["EXECUTION"] = "FAILED"
    elif result["timed_out"]:
        run.status["EXECUTION"] = "TIMEOUT"
    else:
        run.status["EXECUTION"] = "SUCCESS" if result["exit_code"] == 0 else "FAILED"
    stable = result["termination_confirmed"]

    try:
        post = capture_state(repo, stable, frozen)
        write_json(run_dir / ("post-review-state.json" if run.mode == "review" else "post-state.json"), post)
    except (ManifestError, OSError, subprocess.SubprocessError) as exc:
        run.evidence_failure = True
        run.reason(f"POST_STATE_INCOMPLETE: {exc}")
        record_response(run)
        return finalize(run)

    reference = baseline["manifest"]
    if parent is not None:
        reference = strict_json_loads((parent[0] / "baseline.json").read_text(encoding="utf-8"))["manifest"]
    allowed = contract["allowed_paths"]
    if run.mode == "review":
        evaluation = evaluate_scope(manifest_diff(pre_state["manifest"], post["manifest"]), [], frozen)
    else:
        evaluation = evaluate_scope(manifest_diff(reference, post["manifest"]), allowed, frozen)
        if parent is not None:
            run.report["incremental"] = [c["path"] for c in manifest_diff(baseline["manifest"], post["manifest"])]
    apply_evaluation(run, evaluation, baseline, pre_state, post, stable)
    response = record_response(run)

    if run.mode == "implement":
        gate = (run.status["EXECUTION"] == "SUCCESS" and run.status["SCOPE"] == "PASS"
                and run.status["FROZEN"] == "PASS" and run.status["HEAD"] == "UNCHANGED"
                and run.status["RESPONSE"] == "PRESENT" and run.task_status == "COMPLETED")
        if not contract["required_tests"]:
            run.status["TESTS"] = "NOT_APPLICABLE"
            run.report["tests"] = {"status": "NOT_APPLICABLE"}
        elif not gate:
            run.status["TESTS"] = "NOT_RUN"
            reason = "GATED_TASK_NOT_COMPLETED" if run.task_status in ("BLOCKED", "OPEN_DECISION") else "GATED"
            run.report["tests"] = {"status": "NOT_RUN", "reason": reason}
        else:
            tests = run_required_tests(run, prefix, baseline)
            run.status["TESTS"] = tests["status"]
            if tests.get("sandbox_started"):
                # Sandboxed execution began (canary and/or required tests): the final state is always re-captured,
                # and it is stable only when every sandboxed process tree was confirmed terminated.
                canary_process = (tests.get("canary") or {}).get("process") or {}
                test_stable = bool(canary_process.get("termination_confirmed")) and \
                    all(r["termination_confirmed"] for r in tests["results"])
                try:
                    post_test = capture_state(repo, test_stable, frozen)
                    write_json(run_dir / "post-test-state.json", post_test)
                    tests["post_test_state_captured"] = True
                    introduced = [c["path"] for c in manifest_diff(post["manifest"], post_test["manifest"])]
                    tests["introduced_by_tests"] = introduced
                    if introduced:
                        run.status["TESTS"] = "INCOMPLETE"
                    final_eval = evaluate_scope(manifest_diff(reference, post_test["manifest"]), allowed, frozen)
                    apply_evaluation(run, final_eval, baseline, pre_state, post_test, test_stable)
                except (ManifestError, OSError, subprocess.SubprocessError) as exc:
                    run.evidence_failure = True
                    run.status["TESTS"] = "INCOMPLETE"
                    run.reason(f"POST_TEST_STATE_INCOMPLETE: {exc}")
            run.report["tests"] = tests
    if run.mode == "review":
        if run.status["RESPONSE"] != "PRESENT" or run.task_status != "COMPLETED":
            run.status["REVIEW"] = "INCOMPLETE"  # an uncompleted review is never FINDINGS; exit 9 keeps task_status
        else:
            bundle_ok = all((run_dir / n).is_file() and sha256_file(run_dir / n) == d
                            for n, d in run.pre_hashes.items() if n.startswith("review-input/"))
            unchanged = run.status["SCOPE"] == "PASS" and run.status["HEAD"] == "UNCHANGED"
            if not bundle_ok or not unchanged:
                run.status["REVIEW"] = "INCOMPLETE"
                if not bundle_ok:
                    run.evidence_failure = True
            else:
                run.status["REVIEW"] = "CLEAN" if response["verdict"] == "CLEAN" else "FINDINGS"
    return finalize(run)


def record_response(run: Run) -> dict | None:
    """Validate final.txt once; task_status is taken only from a PRESENT (schema- and rule-valid) response."""
    run.status["RESPONSE"], response = validate_response(run, run.status["EXECUTION"])
    run.report["response"] = response
    run.task_status = response["task_status"] if run.status["RESPONSE"] == "PRESENT" else None
    if run.status["RESPONSE"] == "INCOMPLETE" and isinstance(response, dict):
        try:
            if not validate_schema(response, load_schema("codex_response.schema.json")):
                run.report["response_rule_violation"] = response_rule_violation(run.mode, response)
        except (KeyError, TypeError):
            pass
    return response


def apply_evaluation(run: Run, evaluation: dict, baseline: dict, pre_state: dict, post: dict, stable: bool) -> None:
    run.report["scope"] = evaluation
    if not stable:
        run.status.update(SCOPE="INCOMPLETE", FROZEN="INCOMPLETE", HEAD="INCOMPLETE")
        run.reason("post-state unstable: process-tree termination not confirmed")
        return
    frozen_now = run.repo / FROZEN_SOURCE
    frozen_changed = not frozen_now.is_file() or sha256_file(frozen_now) != baseline["frozen"]["sha256"]
    run.status["SCOPE"] = evaluation["scope"]
    run.status["FROZEN"] = "FAIL" if evaluation["frozen"] == "FAIL" or frozen_changed else "PASS"
    run.status["HEAD"] = "UNCHANGED" if canonical_json(pre_state["head"]) == canonical_json(post["head"]) else "CHANGED"


def cmd_show(run_id: str) -> int:
    if not RUN_ID_RE.match(run_id):
        print("invalid run id", file=sys.stderr)
        return EXIT_PRECONDITION
    path = artifact_root() / run_id / "report.json"
    if not path.is_file():
        print(f"no report at {path}", file=sys.stderr)
        return EXIT_PRECONDITION
    report = strict_json_loads(path.read_text(encoding="utf-8"))
    summary = {k: report.get(k) for k in ("run_id", "mode", "kind", "task_id", "task_status", "wrapper_exit_code",
                                          "exit_code", "outcome", "status", "reasons", "artifacts",
                                          "push_prevention", "scope_note")}
    summary["violations"] = (report.get("scope") or {}).get("violations")
    response = report.get("response") or {}
    summary["open_decisions"] = response.get("open_decisions")
    summary.update(response_findings(report))
    print(json.dumps(summary, indent=2))
    return EXIT_OK


def response_findings(report: dict) -> dict:
    """Findings of a PRESENT (schema- and rule-valid) response. They set REVIEW FINDINGS only when task_status is
    COMPLETED; with BLOCKED / OPEN_DECISION they are shown as INFORMATIONAL and REVIEW stays INCOMPLETE (exit 9)."""
    if (report.get("status") or {}).get("RESPONSE") != "PRESENT":
        return {"verdict": None, "findings": None, "findings_role": None}
    response = report.get("response") or {}
    role = "VERDICT" if report.get("mode") == "review" and report.get("task_status") == "COMPLETED" else "INFORMATIONAL"
    return {"verdict": response.get("verdict"), "findings": response.get("findings"), "findings_role": role}


def assert_platform() -> None:
    if sys.platform != "win32" or sys.version_info < (3, 11) or sys.maxsize <= 2 ** 32:
        raise RunnerError("UNSUPPORTED_PLATFORM", "requires 64-bit CPython >= 3.11 on Windows")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="codex_delegate.py", description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("implement", "review"):
        p = sub.add_parser(name)
        p.add_argument("--contract", required=True)
        p.add_argument("--prompt", required=True)
        p.add_argument("--codex-exe-override", help="TEST HARNESS ONLY; accepted only for repositories under the system temp directory")
        if name == "implement":
            p.add_argument("--parent-run-id")
    show = sub.add_parser("show")
    show.add_argument("--run-id", required=True)
    args = parser.parse_args(argv)
    try:
        assert_platform()
        if args.command == "show":
            return cmd_show(args.run_id)
        for label in ("contract", "prompt"):
            if not os.path.isabs(getattr(args, label)):
                raise RunnerError("PATH_NOT_ABSOLUTE", label)
        repo = repo_root_from_cwd()
        if args.codex_exe_override and not is_within(repo, tempfile.gettempdir()):
            raise RunnerError("OVERRIDE_REFUSED", "--codex-exe-override is only accepted for temp repositories")
        contract_bytes = Path(args.contract).read_bytes()
        try:
            contract_raw = strict_json_loads(contract_bytes.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            raise RunnerError("CONTRACT_INVALID", str(exc)) from exc
        contract = validate_contract(contract_raw, args.command, getattr(args, "parent_run_id", None))
        prompt_bytes = Path(args.prompt).read_bytes()
        try:
            if not prompt_bytes.decode("utf-8").strip() or len(prompt_bytes) > PROMPT_MAX_BYTES:
                raise RunnerError("PROMPT_INVALID", "empty or larger than 256 KiB")
        except UnicodeDecodeError as exc:
            raise RunnerError("PROMPT_INVALID", "not UTF-8") from exc
        prefix, binary_kind = codex_prefix(args.codex_exe_override)
        return execute(args, contract, prompt_bytes, contract_bytes, repo, prefix, binary_kind)
    except RunnerError as exc:
        print(json.dumps({"exit_code": EXIT_PRECONDITION, "error": exc.code, "detail": exc.detail}, indent=2))
        return EXIT_PRECONDITION
    except Exception as exc:  # noqa: BLE001 - internal errors map to exit 1 with no repository recovery
        print(json.dumps({"exit_code": EXIT_INTERNAL, "error": "INTERNAL", "detail": f"{type(exc).__name__}: {exc}"}))
        return EXIT_INTERNAL


if __name__ == "__main__":
    sys.exit(main())
