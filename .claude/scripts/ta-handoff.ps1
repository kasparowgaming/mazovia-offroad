# DEV-ENV-002B ta-handoff: thin wrapper; all logic lives in ta_tools.py.
#   .\.claude\scripts\ta-handoff.ps1 -Task <id> [-Output <path outside the repository>] [-Json]
#   Default output: %TEMP%\mazovia-handoff\<id>.md, at most 4096 UTF-8 bytes.
# Exit codes: 0 healthy, 1 unhealthy, 2 usage, 3 unavailable/malformed/conflicting evidence, 4 internal error.
# Python: $env:MAZOVIA_PYTHON, py -3, python, then the terrain venv (CPython >= 3.11, 64-bit, verified first).
$ErrorActionPreference = 'Stop'
$command = 'handoff'
$check = 'import sys,struct; print(sys.version_info>=(3,11) and struct.calcsize(''P'')==8)'
$candidates = @()
if ($env:MAZOVIA_PYTHON) { $candidates += , @($env:MAZOVIA_PYTHON) }
if (Get-Command py.exe -ErrorAction SilentlyContinue) { $candidates += , @('py.exe', '-3') }
if (Get-Command python.exe -ErrorAction SilentlyContinue) { $candidates += , @('python.exe') }
if ($env:LOCALAPPDATA) { $candidates += , @("$env:LOCALAPPDATA\MazoviaOffroad\terrain-build\tools\venv\Scripts\python.exe") }
$python = $null
foreach ($candidate in $candidates) {
    try {
        $prefix = @($candidate | Select-Object -Skip 1)
        # -I -B: isolated (cwd not on sys.path, PYTHON* ignored), no bytecode; a repository struct.py is never read.
        $ok = & $candidate[0] @prefix -I -B -c $check 2>$null
        if ($LASTEXITCODE -eq 0 -and "$ok".Trim() -eq 'True') { $python = $candidate; break }
    } catch { continue }
}
if (-not $python) { [Console]::Error.WriteLine('PREREQUISITE MISSING: CPython >= 3.11 (64-bit)'); exit 4 }
$saved = @{ PYTHONHASHSEED = $env:PYTHONHASHSEED; PYTHONUTF8 = $env:PYTHONUTF8; PYTHONIOENCODING = $env:PYTHONIOENCODING }
$savedEncoding = [Console]::OutputEncoding
try {
    $env:PYTHONHASHSEED = '0'; $env:PYTHONUTF8 = '1'; $env:PYTHONIOENCODING = 'utf-8'
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $prefix = @($python | Select-Object -Skip 1)
    # -B: no bytecode (__pycache__/*.pyc) is ever written into the repository, codex_delegate import included.
    & $python[0] @prefix -B (Join-Path $PSScriptRoot 'ta_tools.py') $command @args
    $code = $LASTEXITCODE
} finally {
    foreach ($name in $saved.Keys) {
        if ($null -eq $saved[$name]) { Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item -Path "Env:$name" -Value $saved[$name] }
    }
    [Console]::OutputEncoding = $savedEncoding
}
exit $code
