At the beginning of every Qwen/OpenCode session Qwen must:
Read only the mandatory startup set first:
.ai/local_agent/INSTRUCTIONS.md
.ai/local_agent/TASK.md
.ai/PROJECT_STATE.md
.ai/local_agent/CHECKPOINT.md

Then read only the exact additional paths listed by TASK.md under CONTEXT_FILES_REQUIRED.
Run safe read-only git checks (status, branch, rev-parse HEAD).
Verify task status is READY or IN_PROGRESS. Work only on ACTIVE_TASK_ID.

Before changing anything, verify repository state:
For STATUS: READY:
- branch == EXPECTED_BRANCH
- HEAD == BASE_COMMIT
- Working tree clean (ignore explicit WORKFLOW_METADATA_PATHS)

For STATUS: IN_PROGRESS:
- BASE_COMMIT is ancestor of HEAD
- branch == EXPECTED_BRANCH

HARD ANTI-LOOP RULE:
If the same approach fails twice, STOP. Use a materially different method, create a checkpoint for a fresh session, or report BLOCKED.

COMPLETION PROTOCOL (IMPLEMENTATION):
1. Run tests. Verify changed files against FILES_ALLOWED.
2. Stage explicitly allowed implementation files (NO `git add .`).
3. Commit.
4. Replace COMMIT_HASH: NONE with real hash in RESULT.md.
5. Update CHECKPOINT.md to COMPLETED. Set ACTIVE_AGENT: GEMINI_REVIEW, LOCAL_TASK_STATUS: DONE.
6. STOP.

COMPLETION PROTOCOL (REPORT_ONLY):
1. Do not modify application source.
2. Write RESULT.md with COMMIT_HASH: NONE.
3. Update CHECKPOINT.md to COMPLETED. Set ACTIVE_AGENT: GEMINI_REVIEW, LOCAL_TASK_STATUS: DONE.
4. STOP.

BLOCKED / FAILED:
Write RESULT.md with STATUS: BLOCKED/FAILED, COMMIT_HASH: NONE. Update CHECKPOINT.md. Set ACTIVE_AGENT: GEMINI_REVIEW, LOCAL_TASK_STATUS: BLOCKED/FAILED. STOP.
