$ErrorActionPreference = 'Stop'

$RepoRoot = "C:\AI_Projects\MazoviaOffroad"
$OpenCodeCmd = "C:\Users\Daniel.DanielRGB\AppData\Roaming\npm\opencode.cmd"

# Verify OpenCode executable
if (-not (Test-Path $OpenCodeCmd)) {
    Write-Host "ERROR: OpenCode executable not found at $OpenCodeCmd"
    exit 1
}

# Verify endpoint
try {
    $Response = Invoke-RestMethod -Uri "http://127.0.0.1:8080/v1/models" -ErrorAction Stop
    $Models = $Response.data | Select-Object -ExpandProperty id
    if ($Models -notcontains "qwen35-9b") {
        Write-Host "ERROR: qwen35-9b not found in local endpoint models."
        exit 1
    }
} catch {
    Write-Host "ERROR: Failed to connect to local Qwen endpoint at http://127.0.0.1:8080/v1/models"
    exit 1
}

# Read Task State
$TaskPath = Join-Path $RepoRoot ".ai\local_agent\TASK.md"
if (-not (Test-Path $TaskPath)) {
    Write-Host "ERROR: TASK.md does not exist."
    exit 1
}

$TaskContent = Get-Content $TaskPath -Raw
$TaskIdMatch = [regex]::Match($TaskContent, '(?m)^TASK_ID:\s*(TASK-\d{8}-\d{3})\s*$')
$TaskTypeMatch = [regex]::Match($TaskContent, '(?m)^TASK_TYPE:\s*(IMPLEMENTATION|REPORT_ONLY)\s*$')
$StatusMatch = [regex]::Match($TaskContent, '(?m)^STATUS:\s*(READY|IN_PROGRESS)\s*$')
$ExpectedBranchMatch = [regex]::Match($TaskContent, '(?m)^EXPECTED_BRANCH:\s*(\S+)\s*$')
$BaseCommitMatch = [regex]::Match($TaskContent, '(?m)^BASE_COMMIT:\s*([0-9a-fA-F]{40})\s*$')

if (-not $TaskIdMatch.Success -or -not $TaskTypeMatch.Success -or -not $StatusMatch.Success) {
    Write-Host "ERROR: TASK.md is missing required fields or has invalid status."
    exit 1
}

if (-not $ExpectedBranchMatch.Success -or -not $BaseCommitMatch.Success) {
    Write-Host "ERROR: TASK.md is missing EXPECTED_BRANCH or BASE_COMMIT."
    exit 1
}

$ExpectedBranch = $ExpectedBranchMatch.Groups[1].Value
$BaseCommit = $BaseCommitMatch.Groups[1].Value

# Verify Git State
$CurrentBranch = (git branch --show-current).Trim()
$CurrentHead = (git rev-parse HEAD).Trim()

if ($CurrentBranch -ne $ExpectedBranch) {
    Write-Host "ERROR: Current branch '$CurrentBranch' does not match EXPECTED_BRANCH '$ExpectedBranch'."
    exit 1
}

if ($StatusMatch.Groups[1].Value -eq "READY") {
    if ($CurrentHead -ne $BaseCommit) {
        Write-Host "ERROR: Current HEAD '$CurrentHead' does not match BASE_COMMIT '$BaseCommit' for READY task."
        exit 1
    }
}

# We launch OpenCode
$Prompt = "EXECUTE NOW. Use your Read tool to read .ai/local_agent/INSTRUCTIONS.md, .ai/local_agent/TASK.md, .ai/PROJECT_STATE.md, and .ai/local_agent/CHECKPOINT.md. Read additional context only if TASK.md explicitly lists exact paths under CONTEXT_FILES_REQUIRED. Then execute the active TASK.md completely using tools. Do not greet, acknowledge, explain, or ask questions. Do not stop until the task is DONE or BLOCKED. Follow every Git, checkpoint, result, allowlist, and final-state requirement."

Write-Host "Starting Qwen session..."
& $OpenCodeCmd run --dir $RepoRoot --model localqwen/qwen35-9b --agent build --auto --format default $Prompt
$ExitCode = $LASTEXITCODE

# Validate Result
$ResultPath = Join-Path $RepoRoot ".ai\local_agent\RESULT.md"
if (-not (Test-Path $ResultPath)) {
    Write-Host "Task process exited. RESULT.md not found. Task might still be IN_PROGRESS."
    exit 0
}

$ResultContent = Get-Content $ResultPath -Raw

$ResTaskId = [regex]::Match($ResultContent, '(?m)^TASK_ID:\s*(TASK-\d{8}-\d{3})\s*$')
$ResTaskType = [regex]::Match($ResultContent, '(?m)^TASK_TYPE:\s*(IMPLEMENTATION|REPORT_ONLY)\s*$')
$ResStatus = [regex]::Match($ResultContent, '(?m)^STATUS:\s*(DONE|BLOCKED|FAILED)\s*$')
$ResCompleted = [regex]::Match($ResultContent, '(?m)^COMPLETED_AT:\s*(\S.*)\s*$')
$ResCommit = [regex]::Match($ResultContent, '(?m)^COMMIT_HASH:\s*([0-9a-fA-F]{40}|NONE)\s*$')

$Headers = @(
    "(?m)^SUMMARY:$", "(?m)^FILES_CHANGED:$", "(?m)^COMMANDS_RUN:$", "(?m)^TESTS_RUN:$",
    "(?m)^TEST_RESULTS:$", "(?m)^EVIDENCE:$", "(?m)^KNOWN_LIMITATIONS:$",
    "(?m)^UNRESOLVED_ISSUES:$", "(?m)^EXACT_BLOCKER:$", "(?m)^DECISION_REQUIRED_FROM_GEMINI:$",
    "(?m)^RECOMMENDED_GEMINI_REVIEW:$"
)

$Valid = $true
if (-not $ResTaskId.Success) { Write-Host "INVALID_RESULT_SCHEMA: TASK_ID missing or invalid"; $Valid = $false }
if (-not $ResTaskType.Success) { Write-Host "INVALID_RESULT_SCHEMA: TASK_TYPE missing or invalid"; $Valid = $false }
if (-not $ResStatus.Success) { Write-Host "INVALID_RESULT_SCHEMA: STATUS missing or invalid"; $Valid = $false }
if (-not $ResCompleted.Success) { Write-Host "INVALID_RESULT_SCHEMA: COMPLETED_AT missing"; $Valid = $false }
if (-not $ResCommit.Success) { Write-Host "INVALID_RESULT_SCHEMA: COMMIT_HASH missing or invalid"; $Valid = $false }

foreach ($h in $Headers) {
    if (-not ([regex]::Match($ResultContent, $h).Success)) {
        Write-Host "INVALID_RESULT_SCHEMA: Missing header $h"
        $Valid = $false
    }
}

if (-not $Valid) {
    exit 1
}

Write-Host "Session finished and RESULT.md validated successfully."
exit 0
