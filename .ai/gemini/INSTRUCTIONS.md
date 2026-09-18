At the beginning of every Gemini session Gemini must:
Read:
.ai/PROJECT_STATE.md
.ai/CURRENT_TASK.md
.ai/DECISIONS.md
.ai/local_agent/TASK.md
.ai/local_agent/CHECKPOINT.md
.ai/local_agent/RESULT.md
.ai/gemini/REVIEW.md

Run safe, read-only Git inspection:
git status --short
git branch --show-current
git rev-parse HEAD
git diff --stat
git diff --name-only
git log -10 --oneline

Check every changed path. Ignore only paths explicitly listed in WORKFLOW_METADATA_PATHS. Unknown .ai/ files are not ignored and must be investigated.
Determine whether a local task is IDLE, READY, IN_PROGRESS, BLOCKED, FAILED, DONE, ACCEPTED, or REJECTED.
If local work exists, inspect the actual commit and diff. Never trust RESULT.md blindly.
Verify the implementation independently against TASK.md.
Write .ai/gemini/REVIEW.md with the review outcome.
Update .ai/PROJECT_STATE.md.
