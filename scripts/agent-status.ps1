$ErrorActionPreference = 'SilentlyContinue'
$RepoRoot = "C:\AI_Projects\MazoviaOffroad"

$ProjState = Get-Content (Join-Path $RepoRoot ".ai\PROJECT_STATE.md") -Raw
$CurrentTask = Get-Content (Join-Path $RepoRoot ".ai\CURRENT_TASK.md") -Raw
$LocalTask = Get-Content (Join-Path $RepoRoot ".ai\local_agent\TASK.md") -Raw
$LocalCheckpoint = Get-Content (Join-Path $RepoRoot ".ai\local_agent\CHECKPOINT.md") -Raw
$LocalResult = Get-Content (Join-Path $RepoRoot ".ai\local_agent\RESULT.md") -Raw

$Proj = ([regex]::Match($ProjState, '(?m)^PROJECT:\s*(.*)$')).Groups[1].Value
$Goal = ([regex]::Match($ProjState, '(?m)^CURRENT_MAJOR_GOAL:\s*(.*)$')).Groups[1].Value
$Blocker = ([regex]::Match($ProjState, '(?m)^CURRENT_BLOCKER:\s*(.*)$')).Groups[1].Value
$NextAction = "WAIT FOR REVIEW"
if ($ProjState -match 'ACTIVE_AGENT:\s*GEMINI') { $NextAction = "RUN GEMINI" }
if ($ProjState -match 'ACTIVE_AGENT:\s*LOCAL') { $NextAction = "RUN LOCAL AGENT" }

$LocId = ([regex]::Match($LocalTask, '(?m)^TASK_ID:\s*(.*)$')).Groups[1].Value
$LocStatus = ([regex]::Match($LocalTask, '(?m)^STATUS:\s*(.*)$')).Groups[1].Value
$LocBase = ([regex]::Match($LocalTask, '(?m)^BASE_COMMIT:\s*(.*)$')).Groups[1].Value
$LocBranch = ([regex]::Match($LocalTask, '(?m)^EXPECTED_BRANCH:\s*(.*)$')).Groups[1].Value
$LocResStatus = ([regex]::Match($LocalResult, '(?m)^STATUS:\s*(.*)$')).Groups[1].Value
$LocResHash = ([regex]::Match($LocalResult, '(?m)^COMMIT_HASH:\s*(.*)$')).Groups[1].Value

$GitBranch = (git branch --show-current).Trim()
$GitHead = (git rev-parse HEAD).Trim()
$GitDirty = (git status --short)
$GitChanged = if ([string]::IsNullOrWhiteSpace($GitDirty)) { "No" } else { "Yes" }
$GitLast = (git log -1 --oneline).Trim()

Write-Host "PROJECT STATE"
Write-Host "Project: $Proj"
Write-Host "Major goal: $Goal"
Write-Host "Current blocker: $Blocker"
Write-Host ""
Write-Host "GEMINI"
Write-Host "Current task: (Check .ai\CURRENT_TASK.md)"
Write-Host ""
Write-Host "LOCAL AGENT"
Write-Host "Task ID: $LocId"
Write-Host "Status: $LocStatus"
Write-Host "Base commit: $LocBase"
Write-Host "Expected branch: $LocBranch"
Write-Host "Last checkpoint: (Check .ai\local_agent\CHECKPOINT.md)"
Write-Host "Last result: $LocResStatus"
Write-Host "Implementation commit: $LocResHash"
Write-Host ""
Write-Host "GIT"
Write-Host "Branch: $GitBranch"
Write-Host "HEAD: $GitHead"
Write-Host "Dirty: $GitChanged"
Write-Host "Changed files:"
Write-Host $GitDirty
Write-Host "Last commit: $GitLast"
Write-Host ""
Write-Host "NEXT ACTION: $NextAction"
