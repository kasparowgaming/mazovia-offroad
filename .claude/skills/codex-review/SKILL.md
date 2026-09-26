---
name: codex-review
description: Run an independent read-only Codex review of the current Mazovia change through the DEV-ENV-002A runner. Manual only; REVIEW CLEAN is not architectural approval.
argument-hint: "[--deep] <task-id> [subject-run-id]  (Claude prepares the review contract and instructions first)"
disable-model-invocation: true
---

# Codex review (DEV-ENV-002A)

Arguments: `$ARGUMENTS`

This skill prepares inputs, runs `.claude/scripts/codex_delegate.py review`, and reports the result. The runner
builds the evidence bundle outside the repository and runs Codex read-only. Codex has no write access, and the
runner verifies that the repository is unchanged afterwards. Never commit, push, reset, restore, clean or stash.

## 1. Resolve a supported Python

Use exactly the same order and check as `/codex-implement`:

1. `$env:MAZOVIA_PYTHON`
2. `py -3`
3. a real `python` (not the Store stub)
4. `$env:LOCALAPPDATA\MazoviaOffroad\terrain-build\tools\venv\Scripts\python.exe`

If none passes the check, report `PREREQUISITE MISSING: CPython >= 3.11 (64-bit)`.

## 2. Prepare inputs OUTSIDE the repository

Under `$env:TEMP\mazovia-codex-inputs\<task-id>\`:

- `prompt.txt`: the review instructions: what to check, the architectural constraints, and the task specification
  if there is no subject run.
- `contract.json`:

```json
{
  "schema_version": 1,
  "task_id": "TA-005",
  "mode": "review",
  "parent_run_id": null,
  "subject_run_id": "<implement or corrective run id, or null>",
  "model": "gpt-6-sol",
  "reasoning_effort": "medium",
  "allowed_paths": [],
  "frozen_paths_source": ".claude/frozen-paths.txt",
  "expected_docs": {"audit_git_blob": null, "design_git_blob": null},
  "required_tests": [],
  "timeout_seconds": 3600
}
```

Prefer a `subject_run_id`. The repository must then be exactly in that run's final state (otherwise exit 2
`SUBJECT_STATE_MISMATCH`), and the bundle carries the subject's prompt, contract, baseline and test evidence. After
a corrective pass, review the corrective run: an earlier review does not approve corrected code.

## 2a. Model and reasoning effort (project policy, conservative by default)

The skill controls the review workflow; it never escalates cost on its own. This project policy is authoritative
for `model` and `reasoning_effort`. Never read `$env:CODEX_HOME\config.toml` or `~\.codex\config.toml` to choose
them. Explicit user choices always win.

| Invocation | model | reasoning_effort |
|---|---|---|
| Ordinary `/codex-review <task>` | `gpt-6-sol` | `medium` |
| Deep: `--deep` in the arguments, or the user explicitly asks in this turn for a deep review, high effort, high reasoning effort, maximum reasoning, or to use Astra | `gpt-6-astra` | `high` |

- Never infer `--deep`; never add it yourself. Keep the ordinary policy even when the change is large or looks
  difficult, touches architecture or security-related code, is a release/review gate, or is a source review. None
  of these is a reason to escalate.
- If the user explicitly names a model, use it; the effort stays at the mode's default (`medium`, or `high` for
  deep) unless the user also names an effort. If the user explicitly names an effort, use it; the model stays at the
  mode's default unless the user also names one. Example: "using gpt-6-luna" → `gpt-6-luna` + `medium`.
- Verified models: `gpt-6-astra`, `gpt-6-sol`, `gpt-6-luna`. If the user names another slug, stop and ask them to
  confirm it. Accepted efforts are the contract schema's `low | medium | high | xhigh`; anything else is unsupported,
  so stop and say so.
- Never pick a model by ranking, priority, capability, price or `codex debug models`. The runner rejects slugs it
  does not list (exit `MODEL_UNKNOWN`); report that and do not substitute another model.
- `high`, `xhigh` or `gpt-6-astra` never appears in the contract unless the user explicitly requested it.

**Show the policy before launching.** Print exactly one line before running the runner, for example:

```
Codex review: gpt-6-sol, medium effort, read-only
Codex review: gpt-6-astra, high effort (--deep), read-only
Codex review: gpt-6-luna, medium effort (user model), read-only
```

## 3. Run (from the repository root)

```powershell
& <python> .claude\scripts\codex_delegate.py review --contract <abs contract.json> --prompt <abs prompt.txt>
```

## 4. Report

Print the runner summary and `show --run-id <run-id>`. Start the report with the machine outcome, copied verbatim:

```
execution:         SUCCESS | FAILED | TIMEOUT | INCOMPLETE
response:          PRESENT | MISSING | INCOMPLETE
task_status:       COMPLETED | BLOCKED | OPEN_DECISION   (null when the response is not PRESENT)
wrapper_exit_code: <integer>
```

`task_status` says whether the reviewer *completed the review*. It is the only completion classifier; never infer it
from free text. `verdict` (CLEAN / FINDINGS) only applies when task_status is COMPLETED.

| Exit | Meaning |
|---|---|
| 0 | task_status COMPLETED and REVIEW CLEAN (no reviewer findings). NOT architectural approval. |
| 7 | task_status COMPLETED with FINDINGS. List every finding with its severity, path, line and evidence. |
| 9 | Review not completed: task_status BLOCKED or OPEN_DECISION. REVIEW is INCOMPLETE; this is never reported as findings and never 0. List the blocker or every `open_decisions` record. Any schema-valid findings still appear in `show` with `findings_role: INFORMATIONAL`; list them as informational only. |
| 1 / 2 / 3 / 4 / 5 / 8 | As in `/codex-implement` (precedence 1 > 8 > 4 > 5 > 3 > 9 > 6 > 7 > 0). |

In review mode TESTS is always NOT_APPLICABLE. The subject's test evidence is in
`review-input\subject_tests.json`. Any test results the reviewer claims to have observed are informational only.
Whether findings need a corrective pass is Claude's or the user's decision. At most one corrective, never started
automatically.

## 5. Compact result (DEV-ENV-002B)

After the machine outcome, use the same compact sections as `/codex-implement` (STATUS with the review run id and
subject_fingerprint, FILES_CHANGED, TESTS, FINDINGS, OPEN_DECISIONS, NEXT_GATE from
`.\.claude\scripts\ta-status.ps1 -Task <task-id>`). A REVIEW CLEAN counts only for the exact subject fingerprint it
reviewed: any later change makes it STALE, and `ta-finalize` refuses stale reviews.
