#!/usr/bin/env node
'use strict';
/**
 * Test: does --output-format stream-json work with cli.js v2.1.112?
 *
 * Spins up the same Anthropic→OpenAI proxy as test-full-session.js,
 * then spawns cli.js with --output-format stream-json instead of --print.
 * Parses every newline-delimited JSON event and reports:
 *   - what event types appear (tool_use, text, system, etc.)
 *   - whether tool call events are present
 *   - the full raw output so we can see the schema
 *
 * Usage:
 *   OPENROUTER_API_KEY=sk-or-... node test-stream-json.js [cli.js path]
 *   node test-stream-json.js [cli.js path]   # uses mock responses if no key
 */

const { spawn } = require('child_process');
const http      = require('http');
const https     = require('https');
const fs        = require('fs');
const path      = require('path');
const os        = require('os');

const API_KEY   = process.env.OPENROUTER_API_KEY || '';
const MODEL_ID  = 'openai/gpt-oss-20b:free';
const PROVIDER  = 'https://openrouter.ai/api/v1';
const PROXY_PORT = 19083;
const HOST      = '127.0.0.1';

// Allow passing a cli.js path as arg, or fall back to known cache locations
const CLI_JS = process.argv[2]
  || '/tmp/node_modules/@anthropic-ai/claude-code/cli.js'
  || '/tmp/sim-test/node_modules/@anthropic-ai/claude-code/cli.js';

// ─── Proxy helpers (mirrors bridge.js) ────────────────────────────────────────

function anthToOai(a, model) {
  const msgs = [];
  if (a.system) {
    const text = typeof a.system === 'string' ? a.system
      : (a.system || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
    if (text) msgs.push({ role: 'system', content: text });
  }
  for (const m of (a.messages || [])) {
    if (typeof m.content === 'string') msgs.push({ role: m.role, content: m.content });
    else msgs.push({ role: m.role, content: (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('') });
  }
  return { model, messages: msgs, max_tokens: a.max_tokens || 1024, stream: !!a.stream };
}

function oaiToAnth(oai, model) {
  const choice = (oai.choices || [])[0] || {};
  const text   = (choice.message || {}).content || '';
  return {
    id: 'msg_' + Date.now(), type: 'message', role: 'assistant',
    content: [{ type: 'text', text }], model,
    stop_reason: choice.finish_reason === 'length' ? 'max_tokens' : 'end_turn',
    stop_sequence: null,
    usage: { input_tokens: (oai.usage||{}).prompt_tokens||0, output_tokens: (oai.usage||{}).completion_tokens||0 },
  };
}

function forwardToProvider(oaiReq, res) {
  if (!API_KEY) {
    // No key — return a mock response
    const mockText = 'Hello! I can help you with that. Let me check what tools I have available.';
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(oaiToAnth({ choices: [{ message: { content: mockText }, finish_reason: 'stop' }] }, MODEL_ID)));
  }
  const body   = JSON.stringify(oaiReq);
  const target = new URL(PROVIDER + '/chat/completions');
  const provReq = https.request({
    hostname: target.hostname, port: 443, method: 'POST', path: target.pathname,
    headers: {
      'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body),
      'Authorization': 'Bearer ' + API_KEY,
      'HTTP-Referer': 'https://github.com/rektzy9903/ClaudeCodeSetup', 'X-Title': 'ClaudeCodeSetup',
    },
  }, provRes => {
    let data = ''; provRes.setEncoding('utf8');
    provRes.on('data', c => data += c);
    provRes.on('end', () => {
      try {
        const parsed = JSON.parse(data);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(oaiToAnth(parsed, MODEL_ID)));
      } catch (e) { try { res.writeHead(500); res.end('{}'); } catch(_){} }
    });
  });
  provReq.setTimeout(60000, () => provReq.destroy());
  provReq.on('error', () => { try { res.writeHead(502); res.end('{}'); } catch(_){} });
  provReq.write(body); provReq.end();
}

function tryOptimize(anthReq) {
  function getSys(a) {
    if (!a.system) return '';
    return typeof a.system === 'string' ? a.system
      : (a.system || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
  }
  const sys = getSys(anthReq).toLowerCase();
  if (sys.length > 800) return null;
  if ((sys.includes('title') && (sys.includes('generate') || sys.includes('concise'))) ||
      sys.includes('short title')) return 'Claude Code Session';
  if ((sys.includes('follow-up') || sys.includes('follow up')) && sys.includes('question')) return '';
  if (sys.includes('suggest') && sys.includes('next action')) return '';
  if (sys.includes('file path') && (sys.includes('extract') || sys.includes('identify'))) return '[]';
  if (sys.includes('compact') && (sys.includes('conversation') || sys.includes('context'))) return '';
  return null;
}

function startProxy() {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      if (req.method === 'POST' && req.url.includes('/count_tokens')) {
        let b = ''; req.on('data', c => b += c);
        req.on('end', () => { res.writeHead(200, {'Content-Type':'application/json'}); res.end(JSON.stringify({input_tokens:1000})); });
        return;
      }
      if ((req.method === 'HEAD' || req.method === 'OPTIONS') && req.url.includes('/messages')) {
        res.writeHead(200, {'Content-Type':'application/json','Access-Control-Allow-Origin':'*',
          'Access-Control-Allow-Methods':'POST,GET,OPTIONS,HEAD',
          'Access-Control-Allow-Headers':'Content-Type,Authorization,x-api-key,anthropic-version,anthropic-beta'});
        res.end('{}'); return;
      }
      if (req.method === 'GET' && req.url.startsWith('/v1/models')) {
        res.writeHead(200, {'Content-Type':'application/json'});
        return res.end(JSON.stringify({data:[{id:'claude-3-5-sonnet-20241022',display_name:'claude-3-5-sonnet-20241022',created_at:''}]}));
      }
      if (req.method === 'POST' && req.url.includes('/messages')) {
        let body = '';
        req.on('data', c => body += c);
        req.on('end', () => {
          try {
            const anthReq = JSON.parse(body);
            const mock = tryOptimize(anthReq);
            if (mock !== null) {
              res.writeHead(200, {'Content-Type':'application/json'});
              return res.end(JSON.stringify({id:'msg_opt',type:'message',role:'assistant',
                content:[{type:'text',text:mock}],model:'claude-3-5-sonnet-20241022',
                stop_reason:'end_turn',stop_sequence:null,usage:{input_tokens:10,output_tokens:5}}));
            }
            forwardToProvider(anthToOai(anthReq, MODEL_ID), res);
          } catch(e) { try { res.writeHead(400); res.end('{}'); } catch(_){} }
        });
        return;
      }
      res.writeHead(200, {'Content-Type':'application/json'}); res.end('{}');
    });
    server.listen(PROXY_PORT, HOST, () => resolve(server));
    server.on('error', reject);
  });
}

// ─── Run cli.js with --output-format stream-json ──────────────────────────────

function runStreamJson(cliJs, tmpDir, message) {
  return new Promise((resolve) => {
    const env = {
      HOME: tmpDir, TERM: 'xterm-256color', LANG: 'en_US.UTF-8',
      LINES: '50', COLUMNS: '160', PATH: process.env.PATH,
      ANTHROPIC_API_KEY:   'sk-ant-proxy000',
      ANTHROPIC_BASE_URL:  `http://${HOST}:${PROXY_PORT}`,
      ANTHROPIC_MODEL:     'claude-3-5-sonnet-20241022',
      DISABLE_AUTOUPDATER: '1',
      TMPDIR: tmpDir, TEMP: tmpDir, TMP: tmpDir,
    };

    const cliUrl  = 'file://' + cliJs;
    const evalCode =
      'process.argv[1]=' + JSON.stringify(cliJs) + ';' +
      'process.argv[2]="--output-format";' +
      'process.argv[3]="stream-json";' +
      'process.argv[4]="--print";' +
      'process.argv[5]="--verbose";' +
      'process.argv[6]=' + JSON.stringify(message) + ';' +
      'process.argv.length=7;' +
      'import(' + JSON.stringify(cliUrl) + ')' +
      '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1)});';

    console.log('\n  Spawning: node -e <evalCode>');
    console.log('  Args:     --output-format stream-json --print --verbose "' + message + '"');
    console.log('  Proxy:    http://' + HOST + ':' + PROXY_PORT + '\n');

    const child = spawn('node', ['-e', evalCode], { env, cwd: tmpDir });
    let rawOutput = '';
    let stdoutLines = [];
    let stderrChunks = [];
    let exited = false;

    child.stdout.on('data', d => {
      const chunk = d.toString();
      rawOutput += chunk;
      stdoutLines.push(...chunk.split('\n').filter(l => l.trim()));
    });

    child.stderr.on('data', d => {
      const chunk = d.toString();
      stderrChunks.push(chunk);
    });

    child.on('close', code => {
      exited = true;
      resolve({ rawOutput, stdoutLines, stderrChunks, exitCode: code });
    });

    child.on('error', err => {
      resolve({ rawOutput, stdoutLines, stderrChunks, exitCode: -1, spawnError: err.message });
    });

    setTimeout(() => {
      if (!exited) { child.kill('SIGTERM'); resolve({ rawOutput, stdoutLines, stderrChunks, exitCode: null, timedOut: true }); }
    }, 60000);
  });
}

// ─── Parse and analyse the stream-json output ─────────────────────────────────

function analyseOutput(stdoutLines) {
  const events = [];
  const parseErrors = [];

  for (const line of stdoutLines) {
    if (!line.trim()) continue;
    try {
      const obj = JSON.parse(line);
      events.push(obj);
    } catch (e) {
      parseErrors.push(line);
    }
  }

  const typeCount = {};
  const toolNames = new Set();
  let hasToolUse = false;
  let textContent = '';

  for (const ev of events) {
    const t = ev.type || '(no type)';
    typeCount[t] = (typeCount[t] || 0) + 1;

    if (t === 'tool_use' || (ev.type === 'content_block_start' && ev.content_block?.type === 'tool_use')) {
      hasToolUse = true;
      const name = ev.name || ev.content_block?.name;
      if (name) toolNames.add(name);
    }
    if (t === 'assistant' && Array.isArray(ev.message?.content)) {
      for (const block of ev.message.content) {
        if (block.type === 'text') textContent += block.text;
        if (block.type === 'tool_use') { hasToolUse = true; toolNames.add(block.name); }
      }
    }
    if (t === 'result' && ev.result) textContent += ev.result;
  }

  return { events, parseErrors, typeCount, hasToolUse, toolNames: [...toolNames], textContent };
}

// ─── Main ─────────────────────────────────────────────────────────────────────

(async () => {
  console.log('\n══ test-stream-json: --output-format stream-json feasibility ══');
  console.log(`  cli.js : ${CLI_JS}`);
  console.log(`  API key: ${API_KEY ? 'present (' + API_KEY.slice(0,8) + '...)' : 'MISSING — using mock responses'}`);

  if (!fs.existsSync(CLI_JS)) {
    console.error('\n✗ cli.js not found at ' + CLI_JS);
    console.error('  Run test-full-session.js first to download it, or pass the path as an argument.');
    process.exit(1);
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-sjson-'));

  let proxy;
  try {
    proxy = await startProxy();
    console.log(`\n  ✓ proxy up on ${HOST}:${PROXY_PORT}`);
  } catch (e) {
    console.error('  ✗ proxy failed: ' + e.message);
    process.exit(1);
  }

  // Test 1: simple message
  console.log('\n── Test 1: simple message "list files in current directory" ──');
  const result = await runStreamJson(CLI_JS, tmpDir, 'list files in current directory');

  console.log(`  exit code : ${result.exitCode}${result.timedOut ? ' (timed out)' : ''}`);
  if (result.spawnError) console.log('  spawn err : ' + result.spawnError);

  if (result.stderrChunks.length > 0) {
    console.log('\n  ── stderr (first 800 chars) ──');
    console.log('  ' + result.stderrChunks.join('').slice(0, 800).replace(/\n/g, '\n  '));
  }

  console.log('\n  ── raw stdout lines ──');
  if (result.stdoutLines.length === 0) {
    console.log('  (no stdout)');
  } else {
    result.stdoutLines.slice(0, 30).forEach((l, i) => {
      console.log(`  [${i}] ${l.slice(0, 200)}`);
    });
    if (result.stdoutLines.length > 30) console.log(`  ... (${result.stdoutLines.length - 30} more lines)`);
  }

  const analysis = analyseOutput(result.stdoutLines);

  console.log('\n  ── analysis ──');
  console.log('  Total JSON events parsed : ' + analysis.events.length);
  console.log('  Parse errors (non-JSON)  : ' + analysis.parseErrors.length);
  if (analysis.parseErrors.length > 0) {
    analysis.parseErrors.slice(0, 3).forEach(l => console.log('    ! ' + l.slice(0, 120)));
  }
  console.log('  Event types seen         :');
  for (const [type, count] of Object.entries(analysis.typeCount)) {
    console.log(`    ${count}x  ${type}`);
  }
  console.log('  Tool use events found    : ' + (analysis.hasToolUse ? '✓ YES — tools: ' + (analysis.toolNames.join(', ') || '(unnamed)') : '✗ NO'));
  if (analysis.textContent) {
    console.log('  Text content             : "' + analysis.textContent.replace(/\n/g,' ').slice(0, 200) + '"');
  }

  // Test 2: check if --output-format alone (no --print) works differently
  console.log('\n── Test 2: same but check argv difference — no --print flag ──');
  const cliUrl2  = 'file://' + CLI_JS;
  const tmpDir2  = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-sjson2-'));
  const message2 = 'say hello';
  const evalCode2 =
    'process.argv[1]=' + JSON.stringify(CLI_JS) + ';' +
    'process.argv[2]="--output-format";' +
    'process.argv[3]="stream-json";' +
    'process.argv[4]="--print";' +
    'process.argv[5]="--verbose";' +
    'process.argv[6]=' + JSON.stringify(message2) + ';' +
    'process.argv.length=7;' +
    'import(' + JSON.stringify(cliUrl2) + ')' +
    '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1)});';

  const env2 = {
    HOME: tmpDir2, TERM: 'xterm-256color', LANG: 'en_US.UTF-8',
    LINES: '50', COLUMNS: '160', PATH: process.env.PATH,
    ANTHROPIC_API_KEY: 'sk-ant-proxy000',
    ANTHROPIC_BASE_URL: `http://${HOST}:${PROXY_PORT}`,
    ANTHROPIC_MODEL: 'claude-3-5-sonnet-20241022',
    DISABLE_AUTOUPDATER: '1',
    TMPDIR: tmpDir2, TEMP: tmpDir2, TMP: tmpDir2,
  };

  const result2 = await new Promise(resolve => {
    const child = spawn('node', ['-e', evalCode2], { env: env2, cwd: tmpDir2 });
    let raw = '', lines = [], err = [], exited = false;
    child.stdout.on('data', d => { raw += d; lines.push(...d.toString().split('\n').filter(l=>l.trim())); });
    child.stderr.on('data', d => { err.push(d.toString()); });
    child.on('close', code => { exited = true; resolve({raw,lines,err,exitCode:code}); });
    child.on('error', e => resolve({raw,lines,err,exitCode:-1,spawnError:e.message}));
    setTimeout(() => { if(!exited){child.kill();resolve({raw,lines,err,exitCode:null,timedOut:true});} }, 45000);
  });

  console.log(`  exit code : ${result2.exitCode}${result2.timedOut ? ' (timed out)' : ''}`);
  const a2 = analyseOutput(result2.lines);
  console.log('  JSON events: ' + a2.events.length + '  event types: ' + Object.keys(a2.typeCount).join(', '));
  console.log('  Tool use   : ' + (a2.hasToolUse ? '✓ YES — ' + a2.toolNames.join(', ') : '✗ NO'));
  if (a2.textContent) console.log('  Text       : "' + a2.textContent.replace(/\n/g,' ').slice(0,150) + '"');
  if (result2.err.length) console.log('  stderr     : ' + result2.err.join('').slice(0,200).replace(/\n/g,' '));

  // ── Verdict ──────────────────────────────────────────────────────────────────
  console.log('\n══ VERDICT ══');
  const works = result.exitCode === 0 && analysis.events.length > 0;
  const hasTools = analysis.hasToolUse || a2.hasToolUse;
  console.log('  stream-json produces parseable JSON events : ' + (analysis.events.length > 0 ? '✓ YES' : '✗ NO'));
  console.log('  cli.js exits cleanly (code 0)              : ' + (result.exitCode === 0 ? '✓ YES' : '✗ NO (code ' + result.exitCode + ')'));
  console.log('  tool_use events visible in output          : ' + (hasTools ? '✓ YES' : '✗ NO'));
  console.log('  feasible for Android bridge                : ' + (works ? '✓ YES — implement Option A' : '✗ NO — fallback to Option B (timer only)'));

  proxy?.close();
  try { fs.rmSync(tmpDir,  {recursive:true,force:true}); } catch(_){}
  try { fs.rmSync(tmpDir2, {recursive:true,force:true}); } catch(_){}
  process.exit(works ? 0 : 1);
})();
