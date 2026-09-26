---
name: codex-implement
description: Delegate one bounded Mazovia implementation task (or its single corrective pass) to the local Codex CLI through the DEV-ENV-002A runner. Manual only; the runner validates scope, Claude and the user decide acceptance.
argument-hint: "[--deep] <task-id> [parent-run-id]  (Claude prepares the contract and prompt first)"
disable-model-invocation: true
---

# Codex implement (DEV-ENV-002A)

Arguments: `$ARGUMENTS`

This skill only prepares inputs, runs `.claude/scripts/codex_delegate.py`, and reports its result. All scope,
frozen, policy, sensitive-file, environment and process-tree logic lives in the runner. Do not reimplement or
second-guess it here. Never commit, push, reset, restore, clean or stash, and never "repair" a violation by
reverting files: report it and let the user decide.

## 1. Resolve a supported Python (CPython >= 3.11, 64-bit)

Try in order and use the first candidate whose check prints `True`:

1. `$env:MAZOVIA_PYTHON`, if set.
2. `py -3` (only if the `py` launcher exists).
3. `python`, only if it really starts CPython; the 0-byte `WindowsApps\python.exe` Store stub fails the check.
4. Fallback: `$env:LOCALAPPDATA\MazoviaOffroad\terrain-build\tools\venv\Scripts\python.exe`.

Check (PowerShell), replacing `<candidate>` with the executable (and `-3` for `py`):

```powershell
& <candidate> -c "import sys,struct; print(sys.platform=='win32' and sys.version_info>=(3,11) and struct.calcsize('P')==8)"
```

If no candidate prints `True`, stop and report: `PREREQUISITE MISSING: CPython >= 3.11 (64-bit)`.

## 2. Prepare inputs OUTSIDE the repository

Write both files under `$env:TEMP\mazovia-codex-inputs\<task-id>\` (never inside the repository):

- `prompt.txt`: the bounded task specification written by Claude (UTF-8, at most 256 KiB).
- `contract.json`: strict v1 contract; unknown or missing fields fail. Template:

```json
{
  "schema_version": 1,
  "task_id": "TA-005",
  "mode": "implement",
  "parent_run_id": null,
  "subject_run_id": null,
  "model": "gpt-6-sol",
  "reasoning_effort": "medium",
  "allowed_paths": ["terrain/**"],
  "frozen_paths_source": ".claude/frozen-paths.txt",
  "expected_docs": {"audit_git_blob": null, "design_git_blob": null},
  "required_tests": [
    {"id": "terrain-unit", "argv": ["gradlew.bat", ":terrain:testDebugUnitTest"], "cwd": ".",
     "timeout_seconds": 900, "env": {}}
  ],
  "timeout_seconds": 3600
}
```

Use the task prompt's scope for `allowed_paths` and its stated AUDIT/DESIGN blobs for `expected_docs`. Never add
POLICY_PROTECTED or FROZEN paths; if the task needs them, stop with an OPEN DECISION instead.

A first implement run requires a completely clean repository. A corrective pass is allowed only when Claude or the
user explicitly decides on one: set `parent_run_id` in the contract and pass the same id with `--parent-run-id`.
The repository must still be exactly in the parent's final state. At most one corrective per parent.

## 2a. Model and reasoning effort (project policy, conservative by default)

This project policy is authoritative for `model` and `reasoning_effort`. Never read `$env:CODEX_HOME\config.toml`
or `~\.codex\config.toml` to choose them. Explicit user choices always win.

| Invocation | model | reasoning_effort |
|---|---|---|
| Ordinary `/codex-implement <task>` | `gpt-6-sol` | `medium` |
| Deep: `--deep` in the arguments, or the user explicitly asks in this turn for deep implementation, high effort, maximum reasoning, or to use Astra | `gpt-6-astra` | `high` |

- Never infer `--deep`; never add it yourself. Do not escalate because the task is large or difficult, involves
  architecture or security-related code, is a release gate, or is followed by source review.
- If the user explicitly names a model, use it; the effort stays at the mode's default (`medium`, or `high` for
  deep) unless the user also names an effort. If the user explicitly names an effort, use it; the model stays at the
  mode's default unless the user also names one. Example: "using gpt-6-astra at low effort" → `gpt-6-astra` + `low`.
- Verified models: `gpt-6-astra`, `gpt-6-sol`, `gpt-6-luna`. If the user names another slug, stop and ask them to
  confirm it. Accepted efforts are the contract schema's `low | medium | high | xhigh`; anything else is unsupported,
  so stop and say so.
- Never pick a model by ranking, priority, capability, price or `codex debug models`. The runner rejects slugs it
  does not list (exit `MODEL_UNKNOWN`); report that and do not substitute another model.
- `high`, `xhigh` or `gpt-6-astra` never appears in the contract unless the user explicitly requested it.

**Show the policy before launching.** Print exactly one line before running the runner, for example:

```
Codex implement: gpt-6-sol, medium effort
Codex implement: gpt-6-astra, high effort (--deep)
Codex implement: gpt-6-astra, low effort (user request)
```

## 3. Run (from the repository root)

```powershell
& <python> .claude\scripts\codex_delegate.py implement --contract <abs contract.json> --prompt <abs prompt.txt>
& <python> .claude\scripts\codex_delegate.py implement --contract <abs> --prompt <abs> --parent-run-id <run-id>
```

## 4. Report

Print the runner's JSON summary, then `& <python> .claude\scripts\codex_delegate.py show --run-id <run-id>`.
Always start the report with the machine outcome, copied verbatim from `outcome` / `show`:

```
execution:         SUCCESS | FAILED | TIMEOUT | INCOMPLETE
response:          PRESENT | MISSING | INCOMPLETE
task_status:       COMPLETED | BLOCKED | OPEN_DECISION   (null when the response is not PRESENT)
wrapper_exit_code: <integer>
```

`task_status` comes from Codex's schema-validated final response and is the only task-completion classifier:

- COMPLETED: the task was completed with no unresolved decision.
- BLOCKED: a policy, safety, capability or prerequisite blocker prevented completion.
- OPEN_DECISION: completion needs an explicit user/architecture decision; list every `open_decisions` record
  (FROZEN CLASS EXCEPTION / POLICY-PROTECTED PATH / OTHER).

Never infer it from notes or summary text.

| Exit | Meaning |
|---|---|
| 0 | Mechanical completion: SUCCESS, PRESENT, task_status COMPLETED, SCOPE/FROZEN PASS, HEAD UNCHANGED, TESTS PASS or NOT_APPLICABLE. NOT product acceptance. |
| 1 | Internal runner error |
| 2 | Contract or precondition failure; Codex was not started |
| 3 | Codex execution failure, or final response MISSING/INCOMPLETE (schema or task_status rules violated) |
| 4 | Timeout; the process tree was terminated |
| 5 | SCOPE / FROZEN / HEAD failure |
| 6 | Required test FAIL or NOT_RUN |
| 8 | Incomplete or unverifiable state, or artifact tampering |
| 9 | Task not completed: task_status BLOCKED or OPEN_DECISION (the report keeps the exact value); never 0 |

Precedence when several apply: 1 > 8 > 4 > 5 > 3 > 9 > 6 > 7 > 0. Nothing is reverted, deleted or cleaned for any
exit code, BLOCKED and OPEN_DECISION included. The partial work and all artifacts stay for the user to decide.

Also list scope violations, frozen hits, the TESTS status and reason (`GATED_TASK_NOT_COMPLETED` when task_status is
not COMPLETED), and the run directory. Treat Codex's own summary as informational only. Continue with the task's
review step (Claude review, and for CRITICAL tasks `/codex-review`). Do not start another Codex run
automatically. An OPEN_DECISION goes to the user.

## 5. Compact result (DEV-ENV-002B)

After the machine outcome, keep the rest of the report to these sections:

```
STATUS          <exit meaning, run id, subject_fingerprint>
FILES_CHANGED   <paths from the scope evaluation>
TESTS           <per test: id / count / PASS>  or failing test(s) + short relevant excerpt + gate state
FINDINGS        <none | compact list>
OPEN_DECISIONS  <none | each record>
NEXT_GATE       <from .claude\scripts\ta-status.ps1 -Task <task-id>>
```

Do not repeat the task prompt, DESIGN sections, full Gradle logs or successful command output. On PASS, give the
command, the test count and PASS. For a later session, `.\.claude\scripts\ta-handoff.ps1 -Task <task-id>` writes a
<= 4096-byte handoff outside the repository instead of re-pasting prompts or logs.
