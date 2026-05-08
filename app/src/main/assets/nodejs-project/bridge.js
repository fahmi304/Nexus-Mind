'use strict';

/**
 * bridge.js — runs inside the embedded Node.js runtime (libnode.so via JNI).
 *
 * Responsibilities:
 *   1. First run: download @anthropic-ai/claude-code directly from the npm
 *      registry (no npm binary needed — uses https + Android's /system/bin/tar).
 *   2. Once installed: open TCP port 8083. Each connection spawns one
 *      node cli.js process via the provided launcher binary, piping
 *      stdin/stdout to the socket.
 *
 * argv layout (set by NodeBridgeManager.kt):
 *   argv[0] = "node"          (executable label)
 *   argv[1] = <path>/bridge.js
 *   argv[2] = <filesDir>      (app's internal storage path)
 *   argv[3] = <nativeLibDir>/libnode-launcher.so  (node subprocess binary)
 */

const net    = require('net');
const path   = require('path');
const fs     = require('fs');
const https  = require('https');
const { spawn } = require('child_process');

// ─── Paths ────────────────────────────────────────────────────────────────────

const FILES_DIR  = process.argv[2] || '/data/data/com.claudecodesetup/files';
const LAUNCHER   = process.argv[3] || process.execPath;
const NATIVE_DIR = path.dirname(LAUNCHER);  // dir holding libnode.so for LD_LIBRARY_PATH

const NPM_PREFIX = path.join(FILES_DIR, 'npm-global');
const CLAUDE_CLI = path.join(
    NPM_PREFIX, 'lib', 'node_modules',
    '@anthropic-ai', 'claude-code', 'cli.js'
);
const CONFIG_FILE = path.join(FILES_DIR, 'bridge_config.json');
const SETUP_LOG   = path.join(FILES_DIR, 'setup.log');
const SETUP_DONE  = path.join(FILES_DIR, 'setup_done');

const PORT = 8083;
const HOST = '127.0.0.1';

// ─── Logging ──────────────────────────────────────────────────────────────────

function log(msg) {
    const line = msg.endsWith('\n') ? msg : msg + '\n';
    try { fs.appendFileSync(SETUP_LOG, line); } catch (_) {}
    process.stdout.write(line);
}

// ─── Config ───────────────────────────────────────────────────────────────────

function readConfig() {
    try { return JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); }
    catch (_) { return {}; }
}

// ─── Installation check ───────────────────────────────────────────────────────

function isClaudeInstalled() {
    return fs.existsSync(CLAUDE_CLI);
}

// ─── HTTP helpers (no external deps needed — Node.js built-ins only) ──────────

function httpsGet(url, opts) {
    return new Promise((resolve, reject) => {
        https.get(url, opts || {}, res => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return httpsGet(res.headers.location, opts).then(resolve).catch(reject);
            }
            resolve(res);
        }).on('error', reject);
    });
}

function fetchJson(url) {
    return new Promise(async (resolve, reject) => {
        try {
            const res = await httpsGet(url, { headers: { 'Accept': 'application/json' } });
            let body = '';
            res.setEncoding('utf8');
            res.on('data', c => { body += c; });
            res.on('end', () => {
                try { resolve(JSON.parse(body)); }
                catch (e) { reject(new Error('JSON parse failed: ' + e.message)); }
            });
            res.on('error', reject);
        } catch (e) { reject(e); }
    });
}

function downloadFile(url, dest) {
    return new Promise(async (resolve, reject) => {
        try {
            const res = await httpsGet(url);
            const out = fs.createWriteStream(dest);
            res.pipe(out);
            out.on('finish', () => out.close(resolve));
            out.on('error', err => { fs.unlink(dest, () => {}); reject(err); });
            res.on('error', err => { fs.unlink(dest, () => {}); reject(err); });
        } catch (e) { reject(e); }
    });
}

// ─── Install claude-code directly from npm registry ──────────────────────────
// Avoids needing an npm binary — downloads the tarball and extracts with
// Android's /system/bin/tar (part of toybox, available API 24+).

function installClaudeCode(onDone) {
    fs.mkdirSync(NPM_PREFIX, { recursive: true });

    (async () => {
        log('Fetching @anthropic-ai/claude-code package metadata...\n');

        // npm registry endpoint for scoped packages
        const meta = await fetchJson(
            'https://registry.npmjs.org/@anthropic-ai/claude-code/latest'
        );
        const tarball = meta.dist && meta.dist.tarball;
        if (!tarball) throw new Error('No tarball URL in registry response');

        const sizeMB = Math.round((meta.dist.unpackedSize || 0) / 1e6) || '~50';
        log(`Downloading ${tarball.split('/').pop()} (${sizeMB} MB)...\n`);

        const tgzPath = path.join(FILES_DIR, 'claude-code.tgz');
        await downloadFile(tarball, tgzPath);
        log('Download complete. Extracting...\n');

        const destDir = path.join(
            NPM_PREFIX, 'lib', 'node_modules', '@anthropic-ai', 'claude-code'
        );
        fs.mkdirSync(destDir, { recursive: true });

        // Android toybox tar is at /system/bin/tar (API 24+ / Android 7+)
        const tarEnv = { PATH: '/system/bin:/system/xbin' };
        const tarChild = spawn('/system/bin/tar', [
            '-xzf', tgzPath, '-C', destDir, '--strip-components=1'
        ], { env: tarEnv, cwd: FILES_DIR });

        tarChild.stderr.on('data', d => log(d.toString()));
        tarChild.on('error', err => {
            fs.unlink(tgzPath, () => {});
            throw new Error('tar error: ' + err.message);
        });

        await new Promise((res, rej) => {
            tarChild.on('close', code => code === 0 ? res() : rej(new Error(`tar exit ${code}`)));
        });

        fs.unlink(tgzPath, () => {});

        if (!isClaudeInstalled()) {
            throw new Error('cli.js not found after extraction');
        }

        log('\n✓ Claude Code installed successfully!\n');
        fs.writeFileSync(SETUP_DONE, 'true');
        onDone(true);
    })().catch(err => {
        log(`\n✗ Installation failed: ${err.message}\n`);
        try { fs.writeFileSync(path.join(FILES_DIR, 'setup_failed'), 'true'); } catch (_) {}
        onDone(false);
    });
}

// ─── TCP bridge server ────────────────────────────────────────────────────────

function startBridgeServer() {
    const server = net.createServer(socket => {
        if (!isClaudeInstalled()) {
            socket.write('\r\n\x1b[31mClaude Code not installed — run Setup from the app.\x1b[0m\r\n');
            socket.end();
            return;
        }

        const cfg = readConfig();

        const env = {
            HOME: FILES_DIR,
            TERM: 'xterm-256color',
            LANG: 'en_US.UTF-8',
            LINES: '50',
            COLUMNS: '160',
            PATH: process.env.PATH || '/system/bin:/system/xbin',
            // libnode.so lives alongside the launcher; child processes need it
            LD_LIBRARY_PATH: NATIVE_DIR,
        };

        if (cfg.apiKey)    env.ANTHROPIC_API_KEY    = cfg.apiKey;
        if (cfg.baseUrl)   env.ANTHROPIC_BASE_URL   = cfg.baseUrl;
        if (cfg.authToken) env.ANTHROPIC_AUTH_TOKEN = cfg.authToken;
        if (cfg.modelId)   env.ANTHROPIC_MODEL      = cfg.modelId;

        // Spawn a child Node.js process via the standalone launcher binary
        const child = spawn(LAUNCHER, [CLAUDE_CLI], { env, cwd: FILES_DIR });

        socket.on('data', d => { try { child.stdin.write(d); } catch (_) {} });
        child.stdout.on('data', d => { try { socket.write(d); } catch (_) {} });
        child.stderr.on('data', d => { try { socket.write(d); } catch (_) {} });

        const cleanup = () => {
            try { child.kill('SIGTERM'); } catch (_) {}
            try { socket.destroy(); } catch (_) {}
        };

        child.on('close', () => { try { socket.end(); } catch (_) {} });
        child.on('error', err => {
            try {
                socket.write(
                    `\r\n\x1b[31mFailed to start Claude: ${err.message}\x1b[0m\r\n`
                );
            } catch (_) {}
            cleanup();
        });

        socket.on('close', cleanup);
        socket.on('error', cleanup);
    });

    server.on('error', err => {
        process.stderr.write('Bridge server error: ' + err.message + '\n');
        setTimeout(startBridgeServer, 3000);
    });

    server.listen(PORT, HOST, () => {
        log(`Bridge ready on ${HOST}:${PORT}\n`);
    });
}

// ─── Entry point ──────────────────────────────────────────────────────────────

try { fs.writeFileSync(SETUP_LOG, ''); } catch (_) {}

log(`Node.js bridge starting (launcher: ${LAUNCHER})\n`);

if (isClaudeInstalled()) {
    log('Claude Code found — starting bridge server.\n');
    try { fs.writeFileSync(SETUP_DONE, 'true'); } catch (_) {}
    startBridgeServer();
} else {
    log('Claude Code not found — running first-time install.\n');
    log('Downloading from npm registry (~50 MB), please keep the app open...\n\n');
    installClaudeCode(ok => {
        if (ok) {
            startBridgeServer();
        } else {
            log('\nSetup failed. Tap "Try again" in the app.\n');
        }
    });
}
