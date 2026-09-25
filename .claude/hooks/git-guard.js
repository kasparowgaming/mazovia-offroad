#!/usr/bin/env node
// PreToolUse guard for Git commands issued through POSIX shells, PowerShell, or cmd.
'use strict';

const HEAD = 'Mazovia Git workflow guard';
const MAX_NESTING = 4;
const POSIX_SHELLS = ['bash', 'sh', 'zsh', 'dash', 'ksh', 'fish', 'ash'];
const EXEC_SHELLS = [...POSIX_SHELLS, 'powershell', 'pwsh', 'cmd'];
const isCommandOption = (t) => /^-[a-z]*c$/i.test(t); // -c, -lc, -ec, -xc ...
const result = (decision, rule) => ({ decision, rule });

function commandName(token) {
  return String(token).replace(/\\/g, '/').split('/').pop().replace(/\.exe$/i, '').toLowerCase();
}
// `{`/`}` group commands only as standalone words; inside a word (`-I{}`, `@{...}`) they are literal.
function isSeparator(command, i) {
  const c = command[i];
  if (c !== '{' && c !== '}') return '();'.includes(c);
  return (i === 0 || /[\s;|&(]/.test(command[i - 1])) && (i + 1 >= command.length || /[\s;|&)]/.test(command[i + 1]));
}
function shellForExecutable(name) {
  return ['powershell', 'pwsh'].includes(name) ? 'powershell' : name === 'cmd' ? 'cmd' : 'posix';
}
function pipelineGroups(command, shell) {
  const groups = [];
  let stages = [], stage = '', quote = null, escaped = false;
  const pushStage = () => { if (stage.trim()) stages.push(stage.trim()); stage = ''; };
  const pushGroup = () => { pushStage(); if (stages.length) groups.push(stages); stages = []; };
  for (let i = 0; i < command.length; i++) {
    const c = command[i];
    if (escaped) { stage += c; escaped = false; continue; }
    if (quote) {
      stage += c;
      if (shell === 'powershell' && quote === "'" && c === "'" && command[i + 1] === "'") { stage += command[++i]; continue; }
      if (c === quote) quote = null;
      else if ((shell === 'posix' && c === '\\' && quote === '"') || (shell === 'powershell' && c === '`' && quote === '"')) escaped = true;
      continue;
    }
    if (c === '"' || c === "'") { quote = c; stage += c; continue; }
    if ((shell === 'posix' && c === '\\') || (shell === 'powershell' && c === '`')) { escaped = true; stage += c; continue; }
    if (c === '|' && command[i + 1] !== '|') { pushStage(); continue; }
    if ((c === '|' || c === '&') && command[i + 1] === c) { pushGroup(); i++; continue; }
    if (c === '\n' || c === '\r' || isSeparator(command, i)) { pushGroup(); continue; }
    stage += c;
  }
  if (quote || escaped) throw new Error('unterminated shell pipeline');
  pushGroup();
  return groups;
}
function stageTokens(stage, shell) {
  const segments = lex(stage, shell, true);
  if (segments.length !== 1) throw new Error('ambiguous pipeline stage');
  return segments[0];
}
function stdinShell(stage, shell) {
  const tokens = stageTokens(stage, shell);
  let i = 0;
  if ((tokens[i] || '').toLowerCase() === 'command') i++;
  const name = commandName(tokens[i] || '');
  if (POSIX_SHELLS.includes(name)) {
    if (tokens.slice(i + 1).some(isCommandOption)) return null;
    if (hasStdinRedirect(stage, shell)) return 'posix';
    const operands = tokens.slice(i + 1).filter((t) => !t.startsWith('-') && t !== '<');
    return operands.length ? null : 'posix';
  }
  if (['powershell', 'pwsh'].includes(name)) {
    const ci = tokens.findIndex((t, n) => n > i && ['-command', '-c'].includes(t.toLowerCase()));
    return ci >= 0 && tokens[ci + 1] === '-' ? 'powershell' : null;
  }
  return null;
}
// Index of the first unquoted occurrence of `needle` in a stage, or -1.
function findUnquoted(stage, shell, needle) {
  let quote = null, escaped = false;
  for (let i = 0; i < stage.length; i++) {
    const c = stage[i];
    if (escaped) { escaped = false; continue; }
    if (quote) {
      if (c === quote) quote = null;
      else if ((shell === 'posix' && c === '\\' && quote === '"') || (shell === 'powershell' && c === '`' && quote === '"')) escaped = true;
      continue;
    }
    if (c === '"' || c === "'") { quote = c; continue; }
    if ((shell === 'posix' && c === '\\') || (shell === 'powershell' && c === '`')) { escaped = true; continue; }
    if (stage.startsWith(needle, i)) return i;
  }
  return -1;
}
// A plain `<` input redirection; `<<` (heredoc) and `<<<` (here-string) are handled separately.
function hasStdinRedirect(stage, shell) {
  let from = 0;
  for (;;) {
    const i = findUnquoted(stage.slice(from), shell, '<');
    if (i < 0) return false;
    const at = from + i;
    if (stage[at + 1] === '<') { from = at + (stage[at + 2] === '<' ? 3 : 2); continue; }
    if (stage[at + 1] === '(') { from = at + 2; continue; } // process substitution is an operand, not stdin
    return true;
  }
}
// `shell <<< word`: returns { receiver, command } with the literal word, or command null when it is dynamic.
function hereStringInput(stage, shell) {
  const at = findUnquoted(stage, shell, '<<<');
  if (at < 0) return null;
  const receiver = stdinShell(stage.slice(0, at), shell);
  if (!receiver) return { receiver: null };
  let raw = stage.slice(at + 3).trim(), before;
  // Drop trailing redirections of the receiving shell (`2>&1`, `> out.txt`); they are not part of the word.
  do { before = raw; raw = raw.replace(/\s+(?:[0-9*&]?>{1,2}(?:&[0-9-]+|\s*[^\s"'<>|;&]+))$/, '').trim(); } while (raw !== before);
  if (!raw || /[\$`]/.test(raw)) return { receiver, command: null };
  const words = stageTokens(raw, shell);
  return { receiver, command: words.length === 1 ? words[0] : null };
}
function staticProducer(stage, shell) {
  const raw = stage.trim();
  if (shell === 'powershell') {
    const literal = /^(["'])([\s\S]*)\1$/.exec(raw);
    if (literal && !/[\$`]/.test(literal[2])) return literal[2];
  }
  if (/[\$`]|\$\(|<</.test(raw)) return null;
  const tokens = stageTokens(raw, shell);
  const name = commandName(tokens[0] || '');
  if (name === 'echo') {
    let args = tokens.slice(1);
    if (args[0] === '-n') args = args.slice(1);
    return args.join(' ');
  }
  if (name === 'printf' && tokens.length >= 2) {
    const format = tokens[1];
    if (/%(?!%)/.test(format) || tokens.length !== 2) return null;
    return format.replace(/%%/g, '%').replace(/\\n/g, '\n').replace(/\\r/g, '\r').replace(/\\t/g, '\t').replace(/\\\\/g, '\\');
  }
  return null;
}
function shellStdin(command, shell) {
  const items = [];
  for (const stages of pipelineGroups(command, shell)) {
    for (let i = 0; i < stages.length; i++) {
      const here = hereStringInput(stages[i], shell);
      if (here) {
        if (here.receiver) items.push({ shell: here.receiver, command: here.command });
        continue;
      }
      const receiver = stdinShell(stages[i], shell);
      if (!receiver) continue;
      if (i > 0) {
        if (stages[i - 1].includes('<<')) continue; // extractHeredocs owns literal heredoc input.
        items.push({ shell: receiver, command: staticProducer(stages[i - 1], shell) });
      } else if (!stages[i].includes('<<') && (hasStdinRedirect(stages[i], shell) || stages.length === 1)) {
        items.push({ shell: receiver, command: null });
      }
    }
  }
  return items;
}
function extractHeredocs(command) {
  const lines = command.split(/\r?\n/), kept = [], executable = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const specs = [...line.matchAll(/(?<!<)<<(?!<)(\-)?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\2/g)];
    kept.push(line);
    for (const spec of specs) {
      const body = [];
      let found = false;
      while (++i < lines.length) {
        const candidate = spec[1] ? lines[i].replace(/^\t+/, '') : lines[i];
        if (candidate === spec[3]) { found = true; break; }
        body.push(lines[i]);
      }
      if (!found) throw new Error('unterminated heredoc');
      const prefix = line.slice(0, spec.index).trim();
      const match = /(?:^|[;|&]\s*)(?:command\s+)?([^\s]+)(?:\s.*)?$/.exec(prefix);
      const name = match ? commandName(match[1]) : '';
      if (EXEC_SHELLS.includes(name)) {
        executable.push({ shell: shellForExecutable(name), command: body.join('\n') });
      } else {
        const stages = pipelineGroups(line, 'posix');
        for (const group of stages) {
          const producer = group.findIndex((stage) => stage.includes(spec[0]));
          if (producer >= 0 && producer + 1 < group.length) {
            const receivingShell = stdinShell(group[producer + 1], 'posix');
            if (receivingShell) executable.push({ shell: receivingShell, command: body.join('\n') });
          }
        }
      }
    }
  }
  return { command: kept.join('\n'), executable };
}

function lex(command, shell = 'posix', heredocsProcessed = false) {
  if (typeof command !== 'string') throw new Error('command is not a string');
  if (shell === 'posix' && !heredocsProcessed) command = extractHeredocs(command).command;
  const segments = [];
  let tokens = [], token = '', quote = null, escaped = false, started = false;
  const pushToken = () => { if (started) tokens.push(token); token = ''; started = false; };
  const pushSegment = () => { pushToken(); if (tokens.length) segments.push(tokens); tokens = []; };
  for (let i = 0; i < command.length; i++) {
    const c = command[i];
    if (escaped) { token += c; started = true; escaped = false; continue; }
    if (quote) {
      if (shell === 'powershell' && quote === "'" && c === "'" && command[i + 1] === "'") { token += "'"; i++; continue; }
      if (c === quote) { quote = null; started = true; continue; }
      if (shell === 'posix' && c === '\\' && quote === '"') {
        if (/[$`"\\\n]/.test(command[i + 1] || '')) { escaped = true; continue; }
        token += c; started = true; continue;
      }
      if (shell === 'powershell' && c === '`' && quote === '"') { escaped = true; continue; }
      token += c; started = true; continue;
    }
    if (c === '"' || c === "'") { quote = c; started = true; continue; }
    if ((shell === 'posix' && c === '\\') || (shell === 'powershell' && c === '`')) { escaped = true; started = true; continue; }
    if (/\s/.test(c)) { pushToken(); if (c === '\n' || c === '\r') pushSegment(); continue; }
    if (c === '|' || isSeparator(command, i)) { pushSegment(); if ((c === '|' || c === '&') && command[i + 1] === c) i++; continue; }
    // `2>&1`, `*>&1`, `>&2`, `&>file`: redirection syntax, not a command separator.
    if (c === '&' && (command[i - 1] === '>' || command[i + 1] === '>')) { token += c; started = true; continue; }
    if (c === '&') {
      if (shell === 'powershell' && !tokens.length && !started) continue;
      pushSegment(); if (command[i + 1] === '&') i++; continue;
    }
    token += c; started = true;
  }
  if (quote || escaped) throw new Error('unterminated shell token');
  pushSegment();
  return segments;
}

const isAssignment = (t) => /^[A-Za-z_][A-Za-z0-9_]*=.*/.test(t);
function inferredShell(command, requested) {
  if (requested) return requested;
  return /(^|[;&|]\s*)&\s*["']|\b(?:powershell|pwsh)(?:\.exe)?\s+-(?:command|c)\b|\[[\w.]+\]::|[A-Za-z]:\\/i.test(command) ? 'powershell' : 'posix';
}
function isGitExecutable(token) { return commandName(token) === 'git'; }
function gitStart(tokens) {
  let i = 0;
  while (i < tokens.length && isAssignment(tokens[i])) i++;
  if (tokens[i] && tokens[i].toLowerCase() === 'command') i++;
  if (tokens[i] && tokens[i].toLowerCase() === 'env') {
    i++;
    while (i < tokens.length && isAssignment(tokens[i])) i++;
  }
  return isGitExecutable(tokens[i]) ? i : -1;
}

const GLOBAL_VALUE = new Set(['-C', '-c', '--git-dir', '--work-tree', '--namespace', '--super-prefix', '--config-env', '--exec-path']);
const GLOBAL_FLAG = new Set(['-p', '-P', '--paginate', '--no-pager', '--bare', '--no-replace-objects', '--literal-pathspecs', '--glob-pathspecs', '--noglob-pathspecs', '--icase-pathspecs', '--no-optional-locks', '--no-lazy-fetch', '--version', '--help']);

function parseGit(tokens, start) {
  let i = start + 1;
  while (i < tokens.length) {
    const t = tokens[i];
    if (!t.startsWith('-') || t === '-') break;
    if (/^-C.+/.test(t) || /^-c.+/.test(t) || /^--(?:git-dir|work-tree|namespace|super-prefix|config-env|exec-path)=/.test(t)) { i++; continue; }
    if (GLOBAL_VALUE.has(t)) {
      if (i + 1 >= tokens.length) throw new Error('missing git global option value');
      i += 2; continue;
    }
    if (GLOBAL_FLAG.has(t)) { i++; continue; }
    throw new Error('unknown git global option');
  }
  if (i >= tokens.length) return { subcommand: null, args: [] };
  return { subcommand: tokens[i].toLowerCase(), args: tokens.slice(i + 1) };
}

function hasFlag(args, longNames, shortLetter) {
  return args.some((a) => {
    const lower = a.toLowerCase();
    if (longNames.some((n) => lower === n || lower.startsWith(`${n}=`))) return true;
    return Boolean(shortLetter && /^-[^-]+$/.test(a) && a.slice(1).includes(shortLetter));
  });
}
function checkoutIsPathRestore(args) {
  if (hasFlag(args, ['--patch', '--pathspec-from-file'], 'p')) return true;
  const dash = args.indexOf('--');
  if (dash >= 0) return dash < args.length - 1;
  const createsBranch = hasFlag(args, ['--branch', '--orphan'], 'b') || args.some((x) => /^-[^-]*B/.test(x));
  if (createsBranch) return false;
  const positional = args.filter((x) => !x.startsWith('-'));
  if (!positional.length) return false;
  if (positional.length > 1) return true;
  const target = positional[0];
  return target === '.' || target === '..' || target === ':/' || target.startsWith(':(') || /[*?\[]/.test(target) || /[\\/]/.test(target);
}

function classifyGit(parsed, remote) {
  const sub = parsed.subcommand, a = parsed.args;
  if (!sub || sub === 'help' || sub === 'version') return result('allow', 'git information');
  if (sub === 'push') {
    const force = hasFlag(a, ['--force', '--force-with-lease', '--force-if-includes'], 'f') || a.some((x) => /^\+[^+]/.test(x));
    return force ? result('deny', 'force push') : result(remote ? 'allow' : 'ask', 'ordinary push');
  }
  if (sub === 'commit') {
    if (hasFlag(a, ['--amend'], null)) return result('ask', 'commit --amend');
    return result(remote ? 'allow' : 'ask', 'ordinary commit');
  }
  if (sub === 'reset') {
    if (hasFlag(a, ['--hard', '--merge', '--keep'], null)) return result('deny', 'destructive reset');
    return result('ask', 'git reset');
  }
  if (sub === 'clean') return hasFlag(a, ['--force'], 'f') ? result('deny', 'forced git clean') : result('allow', 'non-forced git clean');
  if (sub === 'stash') {
    const action = (a.find((x) => !x.startsWith('-')) || 'push').toLowerCase();
    return ['list', 'show'].includes(action) ? result('allow', `stash ${action}`) : result('deny', 'stash mutation');
  }
  if (sub === 'restore') return result('deny', 'git restore');
  if (sub === 'rebase') return result('deny', 'git rebase');
  if (sub === 'branch') {
    const hardDelete = a.includes('-D') || (hasFlag(a, ['--delete'], null) && hasFlag(a, ['--force'], 'f'));
    return hardDelete ? result('deny', 'forced branch deletion') : result('allow', 'git branch');
  }
  if (sub === 'checkout') {
    if (checkoutIsPathRestore(a)) return result('deny', 'checkout path restoration');
    if (!a.length) throw new Error('checkout target missing');
    return result('ask', 'checkout changing HEAD or ambiguous target');
  }
  if (sub === 'switch') {
    if (!a.length) throw new Error('switch target missing');
    return result('ask', 'switch changing HEAD');
  }
  if (['merge', 'cherry-pick', 'revert'].includes(sub)) return result('ask', `git ${sub}`);
  return result('allow', `git ${sub}`);
}

function nestedInvocation(tokens, shell) {
  let i = 0;
  if (tokens[i] && tokens[i].toLowerCase() === 'command') i++;
  const name = commandName(tokens[i] || '');
  if (name === 'eval') {
    if (tokens.length <= i + 1) throw new Error('eval command missing');
    return { shell, command: tokens.slice(i + 1).join(' ') };
  }
  const option = (tokens[i + 1] || '').toLowerCase();
  if (POSIX_SHELLS.includes(name) && isCommandOption(option)) {
    if (tokens.length <= i + 2) throw new Error('nested command missing');
    return { shell: 'posix', command: tokens[i + 2] };
  }
  if (['powershell', 'pwsh'].includes(name)) {
    const commandIndex = tokens.findIndex((token, index) => index > i && ['-command', '-c'].includes(token.toLowerCase()));
    if (commandIndex >= 0) {
      if (tokens.length <= commandIndex + 1) throw new Error('nested command missing');
      return { shell: 'powershell', command: tokens.slice(commandIndex + 1).join(' ') };
    }
  }
  if (name === 'cmd' && option === '/c') {
    if (tokens.length <= i + 2) throw new Error('nested command missing');
    return { shell: 'cmd', command: tokens.slice(i + 2).join(' ') };
  }
  return null;
}

function stronger(current, candidate) {
  const rank = { allow: 0, ask: 1, deny: 2 };
  return rank[candidate.decision] > rank[current.decision] ? candidate : current;
}
function inspect(command, remote, shell, depth) {
  if (depth > MAX_NESTING) throw new Error('nested shell recursion limit');
  let choice = result('allow', 'no guarded git command');
  const heredocs = shell === 'posix' ? extractHeredocs(command) : { command, executable: [] };
  for (const item of heredocs.executable) {
    if (depth === MAX_NESTING) throw new Error('nested shell recursion limit');
    choice = stronger(choice, inspect(item.command, remote, item.shell, depth + 1));
    if (choice.decision === 'deny') return choice;
  }
  for (const item of shellStdin(heredocs.command, shell)) {
    if (item.command == null) { choice = stronger(choice, result('ask', 'opaque executable shell stdin')); continue; }
    if (depth === MAX_NESTING) throw new Error('nested shell recursion limit');
    choice = stronger(choice, inspect(item.command, remote, item.shell, depth + 1));
    if (choice.decision === 'deny') return choice;
  }
  for (const tokens of lex(heredocs.command, shell, true)) {
    // xargs builds the command line from stdin at run time: a shell or git behind it cannot be inspected.
    if (commandName(tokens[0] || '') === 'xargs' && tokens.slice(1).some((t) => [...EXEC_SHELLS, 'git'].includes(commandName(t)))) {
      choice = stronger(choice, result('ask', 'xargs runs a shell or git with dynamic input'));
      continue;
    }
    const nested = nestedInvocation(tokens, shell);
    if (nested) {
      if (depth === MAX_NESTING) throw new Error('nested shell recursion limit');
      choice = stronger(choice, inspect(nested.command, remote, nested.shell, depth + 1));
      if (choice.decision === 'deny') return choice;
      continue;
    }
    const start = gitStart(tokens);
    if (start < 0) continue;
    choice = stronger(choice, classifyGit(parseGit(tokens, start), remote));
    if (choice.decision === 'deny') return choice;
  }
  return choice;
}

function check(command, remote = process.env.CLAUDE_CODE_REMOTE === 'true', shell) {
  try { return inspect(command, remote, inferredShell(command, shell), 0); }
  catch { return result('ask', 'malformed command, ambiguous nested shell, or parser error'); }
}

function emit(outcome) {
  if (outcome.decision === 'allow') return;
  const verb = outcome.decision === 'deny' ? 'blocked' : 'requires explicit approval';
  process.stdout.write(JSON.stringify({ hookSpecificOutput: {
    hookEventName: 'PreToolUse', permissionDecision: outcome.decision,
    permissionDecisionReason: `${HEAD}: ${outcome.rule} ${verb}.`,
  } }));
}

if (require.main === module) {
  let raw = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (c) => { raw += c; });
  process.stdin.on('end', () => {
    let input;
    try { input = JSON.parse(raw); } catch { emit(result('ask', 'malformed hook input')); return; }
    if (!input || !input.tool_input || typeof input.tool_input.command !== 'string') { emit(result('ask', 'malformed hook input')); return; }
    const shell = input.tool_name === 'PowerShell' ? 'powershell' : input.tool_name === 'Bash' ? 'posix' : undefined;
    emit(check(input.tool_input.command, process.env.CLAUDE_CODE_REMOTE === 'true', shell));
  });
}

module.exports = { check, lex, parseGit, classifyGit, checkoutIsPathRestore, extractHeredocs };
