#!/usr/bin/env node
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const gitGuard = require('../hooks/git-guard.js');
const frozenGuard = require('../hooks/frozen-guard.js');

const root = path.resolve(__dirname, '..', '..');
const frozen = 'routing/src/main/java/pl/mazovia/offroad/routing/TerrainRadarCalculator.kt';
const terrain = 'terrain/src/main/java/pl/mazovia/offroad/terrain/TestOnly.kt';
let passed = 0;

function expect(label, actual, expected) {
  assert.strictEqual(actual, expected, `${label}: expected ${expected}, got ${actual}`);
  passed++;
}
function git(label, command, expected, remote = false) {
  expect(label, gitGuard.check(command, remote).decision, expected);
}
function frozenDecision(label, toolName, toolInput, expected) {
  expect(label, frozenGuard.decide({ tool_name: toolName, tool_input: toolInput }, root).decision, expected);
}
function shellQuote(value) { return `'${value.replace(/'/g, `'"'"'`)}'`; }
function hookJsonDecision(label, hook, input, expected) {
  const output = execFileSync(process.execPath, [path.join(root, '.claude', 'hooks', hook)], { cwd: root, input, encoding: 'utf8' });
  expect(label, JSON.parse(output).hookSpecificOutput.permissionDecision, expected);
}

// Required Git matrix plus parser false-positive/global-option coverage.
git('quoted git words', 'echo "git commit"', 'allow');
git('grep value', 'git log --grep commit', 'allow');
git('ordinary commit', 'git commit -m test', 'ask');
git('git.exe commit with globals', 'git.exe -c user.name=test commit -m test', 'ask');
git('ordinary push', 'git push origin main', 'ask');
git('force push', 'git push --force origin main', 'deny');
git('force-with-lease push', 'git push --force-with-lease origin main', 'deny');
git('reset hard', 'git reset --hard HEAD', 'deny');
git('reset merge', 'git reset --merge HEAD', 'deny');
git('reset keep', 'git reset --keep HEAD', 'deny');
git('clean -xfd', 'git clean -xfd', 'deny');
git('clean dry run', 'git clean -n', 'allow');
git('stash list', 'git stash list', 'allow');
git('stash show', 'git stash show', 'allow');
git('stash mutation', 'git stash push -m test', 'deny');
git('restore', 'git restore file.txt', 'deny');
git('checkout path restoration', 'git checkout -- file.txt', 'deny');
git('rebase', 'git rebase main', 'deny');
git('forced branch deletion', 'git branch -D old', 'deny');
git('merge', 'git merge topic', 'ask');
git('cherry-pick', 'git cherry-pick HEAD~1', 'ask');
git('revert', 'git revert HEAD', 'ask');
git('switch', 'git switch topic', 'ask');
git('revision checkout', 'git checkout main', 'ask');
git('new branch checkout', 'git checkout -b topic main', 'ask');
git('malformed shell', 'git commit "unterminated', 'ask');
git('remote ordinary commit', 'git commit -m test', 'allow', true);
git('remote ordinary push', 'git push origin main', 'allow', true);
git('remote force push', 'git push --force origin main', 'deny', true);
git('remote reset hard', 'git reset --hard HEAD', 'deny', true);

// DEV-ENV-001C: nested shells, shell-specific quoting, checkout paths, and heredocs.
git('nested bash force push', 'bash -c "git push --force"', 'deny');
git('nested sh hard reset', "sh -c 'git reset --hard'", 'deny');
git('nested PowerShell force push', 'powershell -Command "git push -f"', 'deny');
git('nested PowerShell options force push', 'powershell -NoProfile -Command "git push -f"', 'deny');
git('nested cmd forced clean', 'cmd /c "git clean -fdx"', 'deny');
git('nested ordinary commit', 'bash -c "git commit -m x"', 'ask');
git('nested quoted git prose', 'bash -c "echo git commit"', 'allow');
git('nested eval force push', 'eval "git push --force"', 'deny');
git('PowerShell quoted Windows git force push', String.raw`& "C:\Program Files\Git\cmd\git.exe" push --force`, 'deny');
git('PowerShell quoted Windows git commit', String.raw`& 'C:\Program Files\Git\cmd\git.exe' commit -m x`, 'ask');
git('checkout dot', 'git checkout .', 'deny');
git('checkout root pathspec', 'git checkout :/', 'deny');
git('checkout separator dot', 'git checkout -- .', 'deny');
git('checkout revision path', 'git checkout HEAD -- file.kt', 'deny');
git('checkout glob pathspec', 'git checkout "*.kt"', 'deny');
git('checkout branch remains approval', 'git checkout feature', 'ask');
git('commit amend remains approval', 'git commit --amend', 'ask');
git('harmless quoted heredoc prose', "cat <<'EOF'\ndon't\ngit commit\ngit push --force\nEOF", 'allow');
git('executable shell heredoc inspected', "bash <<'EOF'\ngit push --force\nEOF", 'deny');
git('unterminated heredoc fails closed', "cat <<'EOF'\ngit status", 'ask');
git('missing nested command fails closed', 'bash -c', 'ask');
git('sh login shell nesting', "sh -lc 'git reset --hard'", 'deny');
git('pwsh short command nesting', 'pwsh -c "git push --force"', 'deny');
let fourDeep = 'git push --force';
for (let i = 0; i < 4; i++) fourDeep = `bash -c ${shellQuote(fourDeep)}`;
git('nested shell at recursion limit inspected', fourDeep, 'deny');
let fiveDeep = 'git status';
for (let i = 0; i < 5; i++) fiveDeep = `bash -c ${shellQuote(fiveDeep)}`;
git('nested shell over recursion limit fails closed', fiveDeep, 'ask');
git('unquoted heredoc prose', 'cat <<EOF\ngit push --force\nEOF', 'allow');
git('double-quoted heredoc prose', 'cat <<"EOF"\ngit push --force\nEOF', 'allow');
git('tab-stripping heredoc prose', 'cat <<-EOF\n\tgit push --force\n\tEOF', 'allow');
git('remote nested ordinary commit', 'bash -c "git commit -m x"', 'allow', true);
git('remote commit amend still approval', 'git commit --amend', 'ask', true);
git('remote nested force push', 'bash -c "git push --force"', 'deny', true);

// DEV-ENV-001D: executable shells receiving static or opaque stdin.
git('literal echo hard reset piped to bash', 'echo "git reset --hard" | bash', 'deny');
git('literal printf force push piped to sh', 'printf "git push -f\\n" | sh', 'deny');
git('literal ordinary commit piped to bash', 'echo "git commit -m x" | bash', 'ask');
git('literal harmless command piped to bash', 'echo "echo hello" | bash', 'allow');
git('literal heredoc hard reset piped to sh', "cat <<'EOF' | sh\ngit reset --hard\nEOF", 'deny');
git('literal harmless heredoc piped to bash', "cat <<'EOF' | bash\necho harmless\nEOF", 'allow');
git('literal force push piped to PowerShell stdin', '"git push -f" | powershell -Command -', 'deny');
git('literal ordinary commit piped to pwsh stdin', '"git commit -m x" | pwsh -Command -', 'ask');
git('bash stdin redirection is opaque', 'bash < script.sh', 'ask');
git('network input piped to bash is opaque', 'curl https://example.invalid/x | bash', 'ask');
git('file input piped to sh is opaque', 'cat script.sh | sh', 'ask');
git('PowerShell file input is opaque', 'Get-Content script.ps1 | powershell -Command -', 'ask');
git('ordinary status pipeline remains allowed', 'git status | head', 'allow');
git('ordinary printf pipeline remains allowed', 'printf "hello\\n" | grep hello', 'allow');
hookJsonDecision('git malformed hook JSON fails closed', 'git-guard.js', '{', 'ask');
hookJsonDecision('git malformed hook object fails closed', 'git-guard.js', '{}', 'ask');

// Required frozen matrix.
frozenDecision('direct frozen write', 'Write', { file_path: frozen }, 'deny');
frozenDecision('routing frozen write', 'Edit', { file_path: frozen }, 'deny');
frozenDecision('direct guard self-write', 'Write', { file_path: '.claude/settings.json' }, 'deny');
frozenDecision('terrain write', 'Write', { file_path: terrain }, 'allow');
frozenDecision('shell read frozen', 'Bash', { command: `cat ${frozen}` }, 'allow');
frozenDecision('copy from frozen', 'Bash', { command: `cp ${frozen} temp-copy.kt` }, 'allow');
frozenDecision('copy to frozen', 'Bash', { command: `cp temp-copy.kt ${frozen}` }, 'ask');
frozenDecision('sed in-place frozen', 'Bash', { command: `sed -i s/a/b/ ${frozen}` }, 'ask');
frozenDecision('tee to frozen', 'Bash', { command: `printf x | tee ${frozen}` }, 'ask');
frozenDecision('tee append to frozen', 'Bash', { command: `printf x | tee -a ${frozen}` }, 'ask');
frozenDecision('dd to frozen', 'Bash', { command: `dd if=temp.bin of=${frozen}` }, 'ask');
frozenDecision('PowerShell backslash frozen write', 'PowerShell', { command: String.raw`Set-Content 'routing\src\main\java\pl\mazovia\offroad\routing\TerrainRadarCalculator.kt' x` }, 'ask');
frozenDecision('PowerShell WriteAllText frozen', 'PowerShell', { command: String.raw`[IO.File]::WriteAllText('routing\src\main\java\pl\mazovia\offroad\routing\TerrainRadarCalculator.kt', 'x')` }, 'ask');
frozenDecision('PowerShell WriteAllBytes frozen', 'PowerShell', { command: String.raw`[IO.File]::WriteAllBytes('routing\src\main\java\pl\mazovia\offroad\routing\TerrainRadarCalculator.kt', $bytes)` }, 'ask');
frozenDecision('PowerShell WriteLines frozen', 'PowerShell', { command: String.raw`[IO.File]::WriteLines('routing\src\main\java\pl\mazovia\offroad\routing\TerrainRadarCalculator.kt', $lines)` }, 'ask');
frozenDecision('PowerShell AppendAllText frozen', 'PowerShell', { command: String.raw`[IO.File]::AppendAllText('routing\src\main\java\pl\mazovia\offroad\routing\TerrainRadarCalculator.kt', 'x')` }, 'ask');
frozenDecision('PowerShell AppendAllLines frozen', 'PowerShell', { command: String.raw`[IO.File]::AppendAllLines('routing\src\main\java\pl\mazovia\offroad\routing\TerrainRadarCalculator.kt', $lines)` }, 'ask');
frozenDecision('nested bash sed frozen', 'Bash', { command: `bash -c "sed -i s/a/b/ ${frozen}"` }, 'ask');
frozenDecision('nested PowerShell Set-Content frozen', 'Bash', { command: `powershell -Command "Set-Content '${frozen.replace(/\//g, '\\')}' x"` }, 'ask');
frozenDecision('nested PowerShell options frozen', 'Bash', { command: `powershell -NoProfile -Command "Set-Content '${frozen.replace(/\//g, '\\')}' x"` }, 'ask');
frozenDecision('nested cmd copy to frozen', 'Bash', { command: `cmd /c "copy temp-copy.kt ${frozen}"` }, 'ask');
frozenDecision('PowerShell copy from frozen', 'PowerShell', { command: String.raw`Copy-Item 'routing\src\main\java\pl\mazovia\offroad\routing\TerrainRadarCalculator.kt' temp-copy.kt` }, 'allow');
frozenDecision('PowerShell copy to frozen', 'PowerShell', { command: String.raw`Copy-Item temp-copy.kt 'routing\src\main\java\pl\mazovia\offroad\routing\TerrainRadarCalculator.kt'` }, 'ask');
frozenDecision('frozen harmless heredoc prose', 'Bash', { command: "cat <<'EOF'\ndon't\nsed -i frozen\ngit push --force\nEOF" }, 'allow');
frozenDecision('frozen executable heredoc inspected', 'Bash', { command: `bash <<'EOF'\nsed -i s/a/b/ ${frozen}\nEOF` }, 'ask');
frozenDecision('frozen unterminated heredoc fails closed', 'Bash', { command: "cat <<'EOF'\ntext" }, 'ask');
frozenDecision('frozen missing nested command fails closed', 'Bash', { command: 'bash -c' }, 'ask');
frozenDecision('redirect overwrite frozen', 'Bash', { command: `printf x > ${frozen}` }, 'ask');
frozenDecision('redirect append frozen', 'Bash', { command: `printf x >> ${frozen}` }, 'ask');
frozenDecision('remove frozen', 'Bash', { command: `rm ${frozen}` }, 'ask');
frozenDecision('move from frozen mutates source', 'Bash', { command: `mv ${frozen} temp-copy.kt` }, 'ask');
frozenDecision('move to frozen mutates destination', 'Bash', { command: `mv temp-copy.kt ${frozen}` }, 'ask');
frozenDecision('PowerShell Add-Content frozen', 'PowerShell', { command: `Add-Content '${frozen}' x` }, 'ask');
frozenDecision('PowerShell Out-File frozen', 'PowerShell', { command: `Write-Output x | Out-File '${frozen}'` }, 'ask');
frozenDecision('frozen shell over recursion limit fails closed', 'Bash', { command: fiveDeep }, 'ask');
hookJsonDecision('frozen malformed hook JSON fails closed', 'frozen-guard.js', '{', 'ask');
hookJsonDecision('frozen malformed hook object fails closed', 'frozen-guard.js', '{}', 'ask');
process.env.CLAUDE_CODE_REMOTE = 'true';
frozenDecision('remote direct frozen write', 'Write', { file_path: frozen }, 'deny');
frozenDecision('remote nested frozen write', 'Bash', { command: `bash -c "sed -i s/a/b/ ${frozen}"` }, 'ask');
delete process.env.CLAUDE_CODE_REMOTE;

// DEV-ENV-001D: frozen protection follows executable-shell stdin.
frozenDecision('literal shell stdin writes frozen path', 'Bash', { command: `echo "sed -i s/a/b/ ${frozen}" | bash` }, 'ask');
frozenDecision('opaque script piped to bash needs approval', 'Bash', { command: 'cat script.sh | bash' }, 'ask');
frozenDecision('stdin hardening keeps plain frozen read allowed', 'Bash', { command: `cat ${frozen}` }, 'allow');
frozenDecision('stdin hardening keeps copy from frozen allowed', 'Bash', { command: `cp ${frozen} temp-copy.kt` }, 'allow');

// DEV-ENV-001E: descriptor redirections, here-strings, more shells, xargs, literal braces.
function gitIn(label, shell, command, expected, remote = false) {
  expect(label, gitGuard.check(command, remote, shell).decision, expected);
}
const frozenWin = frozen.replace(/\//g, '\\');
gitIn('PowerShell 2>&1 is a redirection, not a separator', 'powershell', 'git status 2>&1', 'allow');
gitIn('PowerShell gradle 2>&1 piped to Select-Object', 'powershell', '.\\gradlew.bat :terrain:testDebugUnitTest 2>&1 | Select-Object -Last 15', 'allow');
gitIn('PowerShell *>&1 redirection', 'powershell', 'git log -1 *>&1', 'allow');
gitIn('PowerShell 2>$null redirection', 'powershell', 'git status 2>$null', 'allow');
gitIn('bash 2>&1 piped to tail', 'posix', 'git status 2>&1 | tail -5', 'allow');
gitIn('bash &> redirection', 'posix', 'git log -1 &> /tmp/log.txt', 'allow');
gitIn('redirection does not hide a later force push', 'posix', 'git status 2>&1; git push --force', 'deny');
gitIn('here-string literal reset --hard to bash', 'posix', 'bash <<< "git reset --hard"', 'deny');
gitIn('here-string literal commit to sh', 'posix', "sh <<< 'git commit -m x'", 'ask');
gitIn('here-string harmless literal', 'posix', 'bash <<< "echo hello"', 'allow');
gitIn('here-string dynamic word is opaque', 'posix', 'bash <<< "$CMD"', 'ask');
gitIn('here-string to a non-shell is data', 'posix', 'grep reset <<< "git reset --hard"', 'allow');
gitIn('here-string with unquoted word is not a heredoc', 'posix', 'bash <<< EOF', 'allow');
gitIn('here-string glued to the operator', 'posix', 'bash<<<"git reset --hard"', 'deny');
gitIn('here-string followed by a redirection', 'posix', 'bash <<< "git reset --hard" 2>&1', 'deny');
gitIn('here-string with trailing output file', 'posix', 'bash <<< "echo hi" > /tmp/out.txt', 'allow');
gitIn('zsh receives literal reset --hard', 'posix', 'echo "git reset --hard" | zsh', 'deny');
gitIn('dash receives literal force push', 'posix', "printf 'git push -f\\n' | dash", 'deny');
gitIn('ksh -c nested force push', 'posix', 'ksh -c "git push --force"', 'deny');
gitIn('bash -ec option cluster', 'posix', 'bash -ec "git reset --hard"', 'deny');
gitIn('xargs into bash -c', 'posix', 'echo "git reset --hard" | xargs -I{} bash -c "{}"', 'ask');
gitIn('xargs into git', 'posix', 'echo --hard | xargs git reset', 'ask');
gitIn('xargs into a non-executor', 'posix', 'git ls-files | xargs wc -l', 'allow');
gitIn('literal braces inside a word are not separators', 'posix', 'git log --format=%H{} -1', 'allow');
gitIn('standalone brace group still separates', 'posix', '{ git status; git push --force; }', 'deny');
gitIn('PowerShell hashtable braces', 'powershell', 'git status; $h = @{a=1}', 'allow');
frozenDecision('frozen: cp with 2>/dev/null keeps frozen destination', 'Bash', { command: `cp tmp.kt ${frozen} 2>/dev/null` }, 'ask');
frozenDecision('frozen: 2>&1 is not a write target', 'Bash', { command: `cat ${frozen} 2>&1 | head` }, 'allow');
frozenDecision('frozen: &> into frozen is a write', 'Bash', { command: `echo x &> ${frozen}` }, 'ask');
frozenDecision('frozen: PowerShell Copy-Item to frozen with 2>&1', 'PowerShell', { command: `Copy-Item tmp.kt ${frozenWin} 2>&1` }, 'ask');
frozenDecision('frozen: PowerShell read with 2>&1', 'PowerShell', { command: `Get-Content ${frozenWin} 2>&1 | Select-Object -First 3` }, 'allow');
frozenDecision('frozen: here-string literal write to bash', 'Bash', { command: `bash <<< "sed -i s/a/b/ ${frozen}"` }, 'ask');
frozenDecision('frozen: here-string literal read to bash', 'Bash', { command: `bash <<< "cat ${frozen}"` }, 'allow');
frozenDecision('frozen: zsh receives literal write', 'Bash', { command: `echo "rm ${frozen}" | zsh` }, 'ask');
frozenDecision('frozen: find | xargs sed -i', 'Bash', { command: "find routing -name '*.kt' | xargs sed -i s/a/b/" }, 'ask');
frozenDecision('frozen: xargs into sh -c', 'Bash', { command: 'cat list.txt | xargs -I{} sh -c "echo {}"' }, 'ask');
frozenDecision('frozen: xargs into a reader', 'Bash', { command: 'git ls-files | xargs wc -l' }, 'allow');

// Bidirectional DESIGN §24 comparison. Project self-protection entries are checked separately.
const design24 = [
  'navigation/src/main/java/pl/mazovia/offroad/navigation/NavigationManager.kt',
  'navigation/src/main/java/pl/mazovia/offroad/navigation/GpxNavigator.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/model/NavigationState.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/model/Route.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/model/RouteSegment.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/model/RouteMetrics.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/model/Maneuver.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/model/GeoPoint.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/navigation/OffRouteDetector.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/routing/RoutingEngine.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/gpx/GpxRoute.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/gpx/GpxParser.kt',
  'domain/src/main/java/pl/mazovia/offroad/domain/gpx/GpxWriter.kt',
  'routing/src/main/java/pl/mazovia/offroad/routing/**',
  'app/src/main/java/pl/mazovia/offroad/location/AndroidLocationClient.kt',
  'app/src/main/java/pl/mazovia/offroad/service/TrackRecordingService.kt',
  'data/src/main/java/pl/mazovia/offroad/data/repository/SessionRepository.kt',
  'app/src/main/java/javax/lang/model/SourceVersion.java',
  'domain/src/main/java/pl/mazovia/offroad/domain/readiness/RidePackEvaluator.kt',
];
const listText = fs.readFileSync(path.join(root, '.claude', 'frozen-paths.txt'), 'utf8');
const listed = listText.split(/\r?\n/).map((x) => x.trim()).filter((x) => x && !x.startsWith('#'));
const architecture = listed.filter((x) => !x.startsWith('.claude/'));
expect('DESIGN §24 missing from list', design24.filter((x) => !architecture.includes(x)).join(','), '');
expect('frozen architecture absent from DESIGN §24', architecture.filter((x) => !design24.includes(x)).join(','), '');
for (const self of ['.claude/settings.json', '.claude/frozen-paths.txt', '.claude/hooks/**', '.claude/scripts/**']) {
  expect(`self-protection ${self}`, listed.includes(self), true);
}
const expectedBlob = 'b1de916ce20e06e9b3616434c165d6151a813846';
const actualBlob = execFileSync('git', ['hash-object', 'docs/terrain-ahead/DESIGN.md'], { cwd: root, encoding: 'utf8' }).trim();
expect('DESIGN blob', actualBlob, expectedBlob);
expect('DESIGN blob recorded', listText.includes(`DESIGN blob: ${expectedBlob}`), true);

const settings = JSON.parse(fs.readFileSync(path.join(root, '.claude', 'settings.json'), 'utf8'));
JSON.parse(fs.readFileSync(path.join(root, '.claude', 'settings.local.json'), 'utf8'));
passed += 2;
expect('PYTHONHASHSEED retained', settings.env.PYTHONHASHSEED, '0');
expect('MSYS_NO_PATHCONV not project-global', Object.hasOwn(settings.env, 'MSYS_NO_PATHCONV'), false);
expect('no project permission deny rules', Object.hasOwn(settings.permissions, 'deny'), false);
expect('no ordinary commit/push permission allow', settings.permissions.allow.some((x) => /git (?:commit|push)/i.test(x)), false);
console.log(`PASS: ${passed} deterministic guard/config checks`);
