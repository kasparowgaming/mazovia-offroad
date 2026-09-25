#!/usr/bin/env node
// Direct edits to frozen paths DENY; shell writes to frozen destinations ASK.
'use strict';

const fs = require('fs');
const path = require('path');
const REASON = 'OPEN DECISION — FROZEN CLASS EXCEPTION';
const MAX_NESTING = 4;
const POSIX_SHELLS = ['bash', 'sh', 'zsh', 'dash', 'ksh', 'fish', 'ash'];
const EXEC_SHELLS = [...POSIX_SHELLS, 'powershell', 'pwsh', 'cmd'];
const WRITE_COMMANDS = ['cp', 'copy', 'install', 'mv', 'move', 'rename', 'rm', 'del', 'erase', 'unlink', 'touch', 'truncate',
  'chmod', 'tee', 'dd', 'sed', 'perl', 'copy-item', 'move-item', 'rename-item', 'remove-item', 'set-content', 'add-content',
  'clear-content', 'out-file', 'new-item'];
const isCommandOption = (t) => /^-[a-z]*c$/i.test(t); // -c, -lc, -ec, -xc ...
const projectDir = (input) => process.env.CLAUDE_PROJECT_DIR || (input && input.cwd) || process.cwd();
const norm = (p) => String(p).replace(/\\/g, '/').replace(/\/+/g, '/').replace(/^['"]|['"]$/g, '').toLowerCase();

function loadPatterns(root) {
  return fs.readFileSync(path.join(root, '.claude', 'frozen-paths.txt'), 'utf8').split(/\r?\n/)
    .map((l) => l.trim()).filter((l) => l && !l.startsWith('#'))
    .map((l) => {
      const n = norm(l).replace(/^\.\//, '');
      return n.endsWith('/**') ? { raw: l, prefix: n.slice(0, -2) } : { raw: l, exact: n };
    });
}
function relToRoot(root, p) {
  const source = String(p);
  const msys = /^\/([a-z])\/(.*)$/i.exec(norm(source));
  const adjusted = msys ? `${msys[1]}:/${msys[2]}` : source;
  const abs = norm(path.resolve(root, adjusted));
  const r = norm(path.resolve(root)).replace(/\/$/, '') + '/';
  return abs.startsWith(r) ? abs.slice(r.length) : null;
}
function matchRel(rel, patterns) {
  if (rel == null) return null;
  for (const pt of patterns) {
    if (pt.exact && rel === pt.exact) return pt.raw;
    if (pt.prefix && rel.startsWith(pt.prefix)) return pt.raw;
  }
  return null;
}
const matchPath = (root, value, patterns) => matchRel(relToRoot(root, value), patterns);
const commandName = (token) => norm(token).split('/').pop().replace(/\.exe$/, '');
// `{`/`}` group commands only as standalone words; inside a word (`-I{}`, `@{...}`) they are literal.
function isSeparator(command, i) {
  const c = command[i];
  if (c !== '{' && c !== '}') return '();'.includes(c);
  return (i === 0 || /[\s;|&(]/.test(command[i - 1])) && (i + 1 >= command.length || /[\s;|&)]/.test(command[i + 1]));
}
const shellForExecutable = (name) => ['powershell', 'pwsh'].includes(name) ? 'powershell' : name === 'cmd' ? 'cmd' : 'posix';

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
  const segments = shellSegments(stage, shell, true);
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
    const operands = tokens.slice(i + 1).filter((t) => !t.startsWith('-') && t !== '<' && t !== '>' && t !== '>>');
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
  const tokens = stageTokens(raw, shell), name = commandName(tokens[0] || '');
  if (name === 'echo') {
    let args = tokens.slice(1); if (args[0] === '-n') args = args.slice(1);
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
        if (stages[i - 1].includes('<<')) continue;
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
      if (EXEC_SHELLS.includes(name)) executable.push({ shell: shellForExecutable(name), command: body.join('\n') });
      else {
        for (const group of pipelineGroups(line, 'posix')) {
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

function shellSegments(command, shell = 'posix', heredocsProcessed = false) {
  if (typeof command !== 'string') throw new Error('command is not a string');
  if (shell === 'posix' && !heredocsProcessed) command = extractHeredocs(command).command;
  const out = [];
  let tokens = [], token = '', quote = null, started = false, escaped = false;
  const pt = () => { if (started) tokens.push(token); token = ''; started = false; };
  const ps = () => { pt(); if (tokens.length) out.push(tokens); tokens = []; };
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
    if (/\s/.test(c)) { pt(); if (c === '\n' || c === '\r') ps(); continue; }
    // Descriptor duplication (`2>&1`, `*>&1`, `>&2`) writes no file: keep it as one inert token.
    if (c === '>' && command[i + 1] === '&') {
      pt(); let j = i + 2; while (j < command.length && /[0-9-]/.test(command[j])) j++;
      tokens.push(command.slice(i, j)); i = j - 1; continue;
    }
    // `&>file` / `&>>file` redirect both streams into a file: a write.
    if (c === '&' && command[i + 1] === '>') { pt(); i++; const op = command[i + 1] === '>' ? (i++, '>>') : '>'; tokens.push(op); continue; }
    if (c === '>') { pt(); const op = command[i + 1] === '>' ? (i++, '>>') : '>'; tokens.push(op); continue; }
    if (c === '|' || isSeparator(command, i)) { ps(); if (command[i + 1] === c) i++; continue; }
    if (c === '&') { if (shell === 'powershell' && !tokens.length && !started) continue; ps(); if (command[i + 1] === '&') i++; continue; }
    if (shell === 'powershell' && c === ',') { pt(); tokens.push(','); continue; }
    token += c; started = true;
  }
  if (quote || escaped) throw new Error('unterminated shell token');
  ps();
  return out;
}

function nestedInvocation(tokens, shell) {
  let i = 0;
  if ((tokens[i] || '').toLowerCase() === 'command') i++;
  const name = commandName(tokens[i] || ''), option = (tokens[i + 1] || '').toLowerCase();
  if (name === 'eval') {
    if (tokens.length <= i + 1) throw new Error('eval command missing');
    return { shell, command: tokens.slice(i + 1).join(' ') };
  }
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

// Operands without options, redirections (`>`/`>>` plus target, `n>&m`) or the fd number in front of a redirection.
function nonOptions(tokens) {
  const out = [];
  for (let i = 1; i < tokens.length; i++) {
    const t = tokens[i], next = tokens[i + 1] || '';
    if (t === '>' || t === '>>') { i++; continue; }
    if (t.startsWith('>&') || t === ',') continue;
    if (/^[0-9*]$/.test(t) && (next === '>' || next === '>>' || next.startsWith('>&'))) continue;
    if (!t.startsWith('-')) out.push(t);
  }
  return out;
}
function namedValue(tokens, names) {
  for (let i = 1; i < tokens.length - 1; i++) if (names.includes(tokens[i].toLowerCase())) return tokens[i + 1];
  return null;
}
function firstHit(values, root, patterns) {
  for (const value of values.filter((x) => typeof x === 'string')) {
    const hit = matchPath(root, value, patterns);
    if (hit) return hit;
  }
  return null;
}
function staticFileHit(command, root, patterns) {
  const re = /\[(?:System\.)?IO\.File\]\s*::\s*(?:WriteAllText|WriteAllBytes|WriteLines|AppendAllText|AppendAllLines)\s*\(\s*(?:(["'])(.*?)\1|([^,\s)]+))/ig;
  let m;
  while ((m = re.exec(command))) {
    const hit = matchPath(root, m[2] || m[3], patterns);
    if (hit) return hit;
  }
  return null;
}

function segmentHit(tokens, root, patterns) {
  for (let i = 0; i < tokens.length - 1; i++) if (tokens[i] === '>' || tokens[i] === '>>') {
    const hit = matchPath(root, tokens[i + 1], patterns); if (hit) return hit;
  }
  const cmd = commandName(tokens[0] || ''), args = nonOptions(tokens), first = args[0], last = args[args.length - 1];
  if (['cp', 'copy', 'install'].includes(cmd) && args.length >= 2) return matchPath(root, last, patterns);
  if (cmd === 'copy-item') {
    const dest = namedValue(tokens, ['-destination', '-dest']) || last;
    return dest && dest !== first ? matchPath(root, dest, patterns) : null;
  }
  if (['mv', 'move', 'rename'].includes(cmd) && args.length >= 2) return firstHit([first, last], root, patterns);
  if (['move-item', 'rename-item'].includes(cmd)) {
    const source = namedValue(tokens, ['-path', '-literalpath']) || first;
    const dest = namedValue(tokens, ['-destination', '-newname']) || last;
    return firstHit([source, dest], root, patterns);
  }
  if (['rm', 'del', 'erase', 'remove-item', 'unlink', 'touch', 'truncate', 'chmod'].includes(cmd)) return firstHit(args, root, patterns);
  if (['set-content', 'add-content', 'clear-content', 'out-file', 'new-item'].includes(cmd)) {
    const dest = namedValue(tokens, ['-path', '-literalpath', '-filepath']) || first;
    return dest ? matchPath(root, dest, patterns) : null;
  }
  if (cmd === 'tee') return firstHit(args, root, patterns);
  if (cmd === 'dd') {
    for (const token of tokens.slice(1)) if (/^of=/i.test(token)) { const hit = matchPath(root, token.slice(3), patterns); if (hit) return hit; }
  }
  if (['sed', 'perl'].includes(cmd) && tokens.some((t) => /^-[^-]*i/.test(t))) return firstHit(args, root, patterns);
  return null;
}

function inspect(command, shell, root, patterns, depth) {
  if (depth > MAX_NESTING) throw new Error('nested shell recursion limit');
  const heredocs = shell === 'posix' ? extractHeredocs(command) : { command, executable: [] };
  for (const item of heredocs.executable) {
    if (depth === MAX_NESTING) throw new Error('nested shell recursion limit');
    const hit = inspect(item.command, item.shell, root, patterns, depth + 1); if (hit) return hit;
  }
  for (const item of shellStdin(heredocs.command, shell)) {
    if (item.command == null) return '(opaque executable shell stdin)';
    if (depth === MAX_NESTING) throw new Error('nested shell recursion limit');
    const hit = inspect(item.command, item.shell, root, patterns, depth + 1); if (hit) return hit;
  }
  if (shell === 'powershell') { const hit = staticFileHit(heredocs.command, root, patterns); if (hit) return hit; }
  for (const tokens of shellSegments(heredocs.command, shell, true)) {
    // xargs builds the command line from stdin at run time: a shell or writer behind it may target frozen files.
    if (commandName(tokens[0] || '') === 'xargs' && tokens.slice(1).some((t) => [...EXEC_SHELLS, ...WRITE_COMMANDS].includes(commandName(t)))) {
      return '(xargs runs a shell or file writer with dynamic arguments)';
    }
    const nested = nestedInvocation(tokens, shell);
    if (nested) {
      if (depth === MAX_NESTING) throw new Error('nested shell recursion limit');
      const hit = inspect(nested.command, nested.shell, root, patterns, depth + 1); if (hit) return hit;
      continue;
    }
    const hit = segmentHit(tokens, root, patterns); if (hit) return hit;
  }
  return null;
}

function shellDestinationHit(command, root, patterns, shell = 'posix') { return inspect(command, shell, root, patterns, 0); }

function decide(input, root = projectDir(input)) {
  try {
    if (!input || typeof input.tool_name !== 'string' || !input.tool_input || typeof input.tool_input !== 'object') throw new Error('malformed hook input');
    const patterns = loadPatterns(root), tool = input && input.tool_name || '', ti = input && input.tool_input || {};
    if (tool === 'Bash' || tool === 'PowerShell') {
      if (typeof ti.command !== 'string') throw new Error('missing command');
      const hit = shellDestinationHit(ti.command, root, patterns, tool === 'PowerShell' ? 'powershell' : 'posix');
      return hit ? { decision: 'ask', hit } : { decision: 'allow' };
    }
    const targets = [ti.file_path, ti.notebook_path, ti.path].filter((x) => typeof x === 'string');
    if (Array.isArray(ti.edits)) for (const e of ti.edits) if (e && typeof e.file_path === 'string') targets.push(e.file_path);
    for (const target of targets) { const hit = matchPath(root, target, patterns); if (hit) return { decision: 'deny', hit }; }
    return { decision: 'allow' };
  } catch { return { decision: 'ask', hit: '(guard input, nested shell, parser, or frozen list unavailable)' }; }
}

function emit(outcome) {
  if (outcome.decision === 'allow') return;
  process.stdout.write(JSON.stringify({ hookSpecificOutput: {
    hookEventName: 'PreToolUse', permissionDecision: outcome.decision,
    permissionDecisionReason: `${REASON}\nFrozen path: ${outcome.hit}\n${outcome.decision === 'deny' ? 'Direct editing of this frozen path is blocked.' : 'This shell command may write to a frozen destination and requires explicit approval.'}`,
  } }));
}

if (require.main === module) {
  let raw = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (c) => { raw += c; });
  process.stdin.on('end', () => {
    let input;
    try { input = JSON.parse(raw); } catch { emit({ decision: 'ask', hit: '(malformed hook input)' }); return; }
    emit(decide(input));
  });
}

module.exports = { decide, loadPatterns, relToRoot, matchRel, shellDestinationHit, shellSegments, extractHeredocs };
