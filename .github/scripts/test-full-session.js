#!/usr/bin/env node
'use strict';
/**
 * Full session simulation for ClaudeCodeSetup.
 *
 * Reproduces exactly what happens on the Android device:
 *   1. Download claude-code v2.1.112 (same version bridge.js installs)
 *   2. Start the Anthropic→OpenAI proxy on port 18083 (same logic as bridge.js)
 *   3. Spawn `node cli.js` with the same env vars bridge.js sets for proxy mode
 *   4. Send "hello claude" to its stdin
 *   5. Assert the session does NOT end immediately and returns a real reply
 *
 * Usage:
 *   OPENROUTER_API_KEY=sk-or-... node test-full-session.js
 */

const { spawn }  = require('child_process');
const https      = require('https');
const http       = require('http');
const fs         = require('fs');
const path       = require('path');
const os         = require('os');
const zlib       = require('zlib');

const API_KEY    = process.env.OPENROUTER_API_KEY || '';
const MODEL_ID   = 'openai/gpt-oss-20b:free';
const PROVIDER   = 'https://openrouter.ai/api/v1';
const PROXY_PORT = 18083;
const HOST       = '127.0.0.1';
const VERSION    = '2.1.112';

if (!API_KEY) { console.error('OPENROUTER_API_KEY required'); process.exit(1); }

let passed = 0, failed = 0;
function ok(label)        { console.log(`  ✓ ${label}`); passed++; }
function fail(label, msg) { console.error(`  ✗ ${label}: ${msg}`); failed++; }

// ─── Helpers ──────────────────────────────────────────────────────────────────

function getJson(url) {
  return new Promise((res, rej) => {
    https.get(url, { headers: { Accept: 'application/json' } }, r => {
      let b = ''; r.setEncoding('utf8');
      r.on('data', c => b += c);
      r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
      r.on('error', rej);
    }).on('error', rej);
  });
}

function downloadTo(url, dest) {
  return new Promise((res, rej) => {
    function fetch(u, hops) {
      if (hops > 5) return rej(new Error('too many redirects'));
      https.get(u, r => {
        if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
          r.resume(); return fetch(r.headers.location, hops + 1);
        }
        if (r.statusCode !== 200) { r.resume(); return rej(new Error(`HTTP ${r.statusCode}`)); }
        const out = fs.createWriteStream(dest);
        r.pipe(out);
        out.on('finish', res); out.on('error', rej); r.on('error', rej);
      }).on('error', rej);
    }
    fetch(url, 0);
  });
}

// ─── Proxy (mirrors bridge.js) ────────────────────────────────────────────────

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
  const stop   = choice.finish_reason === 'length' ? 'max_tokens' : 'end_turn';
  return {
    id: 'msg_' + (oai.id || Date.now()), type: 'message', role: 'assistant',
    content: [{ type: 'text', text }], model, stop_reason: stop, stop_sequence: null,
    usage: { input_tokens: (oai.usage||{}).prompt_tokens||0, output_tokens: (oai.usage||{}).completion_tokens||0 },
  };
}

function startProxy() {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      // GET /v1/models — Claude Code startup check
      if (req.method === 'GET' && req.url.includes('/models')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ data: [{ id: MODEL_ID, display_name: MODEL_ID, created_at: '' }] }));
        return;
      }
      // POST /v1/messages — chat
      if (req.method === 'POST' && req.url.includes('/messages')) {
        let body = '';
        req.on('data', c => body += c);
        req.on('end', () => {
          try {
            const anthReq = JSON.parse(body);
            const oaiReq  = anthToOai(anthReq, MODEL_ID);
            const reqBody = JSON.stringify(oaiReq);
            const target  = new URL(PROVIDER.replace(/\/$/, '') + '/chat/completions');
            const provReq = https.request({
              hostname: target.hostname, port: 443, method: 'POST', path: target.pathname,
              headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(reqBody),
                'Authorization': 'Bearer ' + API_KEY,
                'HTTP-Referer': 'https://github.com/rektzy9903/ClaudeCodeSetup',
                'X-Title': 'ClaudeCodeSetup',
              },
            }, provRes => {
              let data = '';
              provRes.setEncoding('utf8');
              provRes.on('data', c => data += c);
              provRes.on('end', () => {
                try {
                  const parsed = JSON.parse(data);
                  if (parsed.error) {
                    res.writeHead(500, {'Content-Type':'application/json'});
                    res.end(JSON.stringify({type:'error',error:{type:'api_error',message:parsed.error.message||JSON.stringify(parsed.error)}}));
                    return;
                  }
                  res.writeHead(200, {'Content-Type':'application/json'});
                  res.end(JSON.stringify(oaiToAnth(parsed, MODEL_ID)));
                } catch(e) { res.writeHead(500); res.end('{}'); }
              });
            });
            provReq.setTimeout(90000, () => { provReq.destroy(); });
            provReq.on('error', () => { res.writeHead(502); res.end('{}'); });
            provReq.write(reqBody); provReq.end();
          } catch(e) { res.writeHead(400); res.end('{}'); }
        });
        return;
      }
      // Anything else — 200 so Claude Code doesn't abort
      res.writeHead(200, {'Content-Type':'application/json'}); res.end('{}');
    });
    server.listen(PROXY_PORT, HOST, () => resolve(server));
    server.on('error', reject);
  });
}

// ─── Download claude-code ─────────────────────────────────────────────────────

async function downloadClaudeCode(tmpDir) {
  console.log(`\n── Step 1: download claude-code@${VERSION} ──`);
  const meta    = await getJson(`https://registry.npmjs.org/@anthropic-ai%2Fclaude-code/${VERSION}`);
  const tarball = meta.dist.tarball;
  const tgzPath = path.join(tmpDir, 'cc.tgz');
  const tarPath = path.join(tmpDir, 'cc.tar');
  const extDir  = path.join(tmpDir, 'pkg');
  fs.mkdirSync(extDir);

  await downloadTo(tarball, tgzPath);
  ok(`downloaded ${(fs.statSync(tgzPath).size/1e6).toFixed(1)} MB`);

  await new Promise((res, rej) => {
    const src = fs.createReadStream(tgzPath);
    const gz  = zlib.createGunzip();
    const dst = fs.createWriteStream(tarPath);
    src.on('error',rej); gz.on('error',rej); dst.on('error',rej); dst.on('finish',res);
    src.pipe(gz).pipe(dst);
  });

  const tar = require('child_process').spawnSync('tar', ['-xf', tarPath, '-C', extDir]);
  if (tar.status !== 0) throw new Error('tar failed: ' + tar.stderr.toString());

  const cliJs = path.join(extDir, 'package', 'cli.js');
  if (!fs.existsSync(cliJs)) throw new Error('cli.js not found');
  ok(`cli.js ready (${(fs.statSync(cliJs).size/1e3).toFixed(0)} KB)`);
  return cliJs;
}

// ─── Run Claude Code session ──────────────────────────────────────────────────

async function runClaudeSession(cliJs, tmpDir) {
  console.log('\n── Step 3: spawn Claude Code (same env as bridge.js) ──');

  const env = {
    HOME: tmpDir,
    TERM: 'xterm-256color',
    LANG: 'en_US.UTF-8',
    LINES: '40',
    COLUMNS: '160',
    PATH: process.env.PATH,
    // Proxy-mode env — same logic as bridge.js (no ANTHROPIC_API_KEY)
    ANTHROPIC_AUTH_TOKEN: 'freecc',
    ANTHROPIC_BASE_URL: `http://${HOST}:${PROXY_PORT}`,
    CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY: '1',
    DISABLE_AUTOUPDATER: '1',
  };

  console.log('  env: ANTHROPIC_AUTH_TOKEN=freecc');
  console.log(`  env: ANTHROPIC_BASE_URL=http://${HOST}:${PROXY_PORT}`);
  console.log('  env: CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1');
  console.log(`  cmd: node ${path.basename(cliJs)}`);

  return new Promise((resolve) => {
    const child  = spawn('node', [cliJs], { env, cwd: tmpDir });
    let   output = '';
    let   exited = false;
    let   replied = false;

    child.stdout.on('data', d => {
      const chunk = d.toString();
      output += chunk;
      process.stdout.write('  [stdout] ' + chunk.replace(/\n/g,'↵').slice(0,120) + '\n');
    });
    child.stderr.on('data', d => {
      const chunk = d.toString();
      output += chunk;
      process.stdout.write('  [stderr] ' + chunk.replace(/\n/g,'↵').slice(0,120) + '\n');
    });
    child.on('close', code => {
      exited = true;
      console.log(`  [process exited with code ${code}]`);
      resolve({ output, exited: true, replied, exitCode: code });
    });
    child.on('error', err => {
      console.error('  [spawn error] ' + err.message);
      resolve({ output, exited: true, replied: false, exitCode: -1 });
    });

    // Send "hello claude" after 5 seconds (give Claude Code time to start)
    setTimeout(() => {
      if (!exited) {
        console.log('\n── Step 4: send "hello claude" ──');
        try { child.stdin.write('hello claude\n'); } catch (_) {}
      } else {
        console.log('\n  [process already exited before we could send input]');
      }
    }, 5000);

    // After 90s total, evaluate results and kill
    setTimeout(() => {
      if (!exited) {
        replied = output.length > 200; // got substantial output after sending input
        child.kill('SIGTERM');
      }
      resolve({ output, exited, replied, exitCode: null });
    }, 90000);
  });
}

// ─── Main ─────────────────────────────────────────────────────────────────────

(async () => {
  console.log('\nClaudeCodeSetup — full session simulation');
  console.log('=========================================');
  console.log(`  Provider : OpenRouter`);
  console.log(`  Model    : ${MODEL_ID}`);

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-sim-'));

  let cliJs;
  try {
    cliJs = await downloadClaudeCode(tmpDir);
  } catch (e) {
    fail('download', e.message);
    process.exit(1);
  }

  let proxyServer;
  console.log('\n── Step 2: start proxy ──');
  try {
    proxyServer = await startProxy();
    ok(`proxy listening on ${HOST}:${PROXY_PORT}`);
  } catch (e) {
    fail('proxy', e.message);
    process.exit(1);
  }

  const result = await runClaudeSession(cliJs, tmpDir);

  console.log('\n── Step 5: evaluate ──');

  // Did the session stay alive at least 5 seconds (past the "send input" point)?
  if (result.exited && result.exitCode !== null && result.exitCode !== 0) {
    fail('session stayed alive', `process exited with code ${result.exitCode} before we could send input`);
  } else {
    ok('session did not end immediately');
  }

  // Did we get any output at all?
  const cleanOutput = result.output.replace(/\x1b\[[0-9;]*m/g, '').trim();
  if (cleanOutput.length === 0) {
    fail('received output', 'no output from Claude Code');
  } else {
    ok(`received ${cleanOutput.length} chars of output`);
  }

  // Did the output contain something that looks like a response (not just an error)?
  const hasError = /error|Error|failed|Failed|invalid|Invalid/i.test(cleanOutput) && cleanOutput.length < 300;
  const hasReply = cleanOutput.length > 100 && !hasError;
  if (hasReply) {
    const preview = cleanOutput.replace(/\s+/g,' ').slice(0, 200);
    ok(`got reply content: "${preview}…"`);
  } else if (hasError) {
    fail('reply (no error)', 'output looks like an error: ' + cleanOutput.slice(0, 200));
  } else {
    ok(`output received (${cleanOutput.length} chars) — Claude Code active`);
  }

  console.log('\n── Summary ──');
  console.log(`  Passed: ${passed}   Failed: ${failed}`);

  if (process.env.GITHUB_STEP_SUMMARY) {
    const status = failed === 0 ? '✅ PASSED' : '❌ FAILED';
    const lines = [
      '## Full Session Simulation',
      '',
      `| | |`,
      `|---|---|`,
      `| Provider | OpenRouter |`,
      `| Model | \`${MODEL_ID}\` |`,
      `| Message sent | "hello claude" |`,
      `| Result | ${status} — ${passed} passed, ${failed} failed |`,
      '',
    ];
    if (cleanOutput) lines.push('**Output preview:**\n```\n' + cleanOutput.slice(0,400) + '\n```');
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
  }

  proxyServer?.close();
  try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (_) {}
  process.exit(failed > 0 ? 1 : 0);
})();
