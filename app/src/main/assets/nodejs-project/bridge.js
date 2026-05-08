'use strict';

/**
 * bridge.js — runs inside nodejs-mobile-android.
 *
 * Responsibilities:
 *   1. On first run: install @anthropic-ai/claude-code via npm, write
 *      progress to SETUP_LOG so SetupActivity can show real progress.
 *   2. Once installed: open a TCP server on port 8083. Each incoming
 *      connection spawns one `node cli.js` process (claude-code), piping
 *      its stdin/stdout to the socket — exactly what socat used to do.
 *
 * Communication with Android (config):
 *   Android writes <filesDir>/bridge_config.json before starting this
 *   script. bridge.js reads it on every new connection so provider
 *   changes take effect without restarting Node.js.
 */

const net    = require('net');
const path   = require('path');
const fs     = require('fs');
const { spawn } = require('child_process');

// ─── Paths ────────────────────────────────────────────────────────────────────

// Android passes filesDir as argv[2] so debug/release builds both work.
const FILES_DIR  = process.argv[2] || '/data/data/com.claudecodesetup/files';
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

// ─── Logging (written to file so Android can poll it) ─────────────────────────

function log(msg) {
    const line = (msg.endsWith('\n') ? msg : msg + '\n');
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

// ─── npm install ──────────────────────────────────────────────────────────────

function findNpmCli() {
    const nodeDir = path.dirname(process.execPath);
    const candidates = [
        // nodejs-mobile bundles npm here
        path.join(nodeDir, '..', 'lib', 'node_modules', 'npm', 'bin', 'npm-cli.js'),
        // standard node install layout
        path.join(nodeDir, 'npm'),
        path.join(nodeDir, 'npm.js'),
    ];
    for (const p of candidates) {
        try { fs.accessSync(p, fs.constants.R_OK); return p; } catch (_) {}
    }
    return null; // fall back to PATH 'npm'
}

function runNpmInstall(onDone) {
    try { fs.mkdirSync(NPM_PREFIX, { recursive: true }); } catch (_) {}
    try { fs.mkdirSync(path.join(FILES_DIR, 'npm-cache'), { recursive: true }); } catch (_) {}

    const npmCli  = findNpmCli();
    const isScript = npmCli && npmCli.endsWith('.js');
    const exe  = isScript ? process.execPath : (npmCli || 'npm');
    const args = isScript ? [npmCli] : [];
    args.push(
        'install', '-g', '@anthropic-ai/claude-code',
        '--prefix', NPM_PREFIX,
        '--no-audit', '--no-fund',
        '--loglevel', 'warn'
    );

    const env = {
        HOME: FILES_DIR,
        PATH: process.env.PATH || '/system/bin:/system/xbin',
        npm_config_cache: path.join(FILES_DIR, 'npm-cache'),
        npm_config_prefix: NPM_PREFIX,
    };

    log('Running npm install -g @anthropic-ai/claude-code ...\n');

    const child = spawn(exe, args, { env, cwd: FILES_DIR });
    child.stdout.on('data', d => log(d.toString()));
    child.stderr.on('data', d => log(d.toString()));

    child.on('close', code => {
        if (code === 0 && isClaudeInstalled()) {
            log('\n✓ Claude Code installed successfully!\n');
            try { fs.writeFileSync(SETUP_DONE, 'true'); } catch (_) {}
            onDone(true);
        } else {
            log(`\n✗ npm install failed (exit ${code}). Check your internet connection and retry.\n`);
            onDone(false);
        }
    });

    child.on('error', err => {
        log(`\n✗ Could not run npm: ${err.message}\n`);
        onDone(false);
    });
}

// ─── TCP bridge server ────────────────────────────────────────────────────────

function startBridgeServer() {
    const server = net.createServer(socket => {
        if (!isClaudeInstalled()) {
            socket.write('\r\n\x1b[31mClaude Code not installed — please run Setup from the app.\x1b[0m\r\n');
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
            PATH: process.env.PATH || '/system/bin',
        };

        // Provider-specific env vars — only set non-empty values
        if (cfg.apiKey)    env.ANTHROPIC_API_KEY    = cfg.apiKey;
        if (cfg.baseUrl)   env.ANTHROPIC_BASE_URL   = cfg.baseUrl;
        if (cfg.authToken) env.ANTHROPIC_AUTH_TOKEN = cfg.authToken;
        if (cfg.modelId)   env.ANTHROPIC_MODEL      = cfg.modelId;

        const child = spawn(process.execPath, [CLAUDE_CLI], {
            env,
            cwd: FILES_DIR,
        });

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
                socket.write(`\r\n\x1b[31mFailed to start Claude: ${err.message}\x1b[0m\r\n`);
            } catch (_) {}
            cleanup();
        });

        socket.on('close', cleanup);
        socket.on('error', cleanup);
    });

    server.on('error', err => {
        process.stderr.write('Bridge server error: ' + err.message + '\n');
        // Retry after 3 s (e.g. if port was briefly occupied)
        setTimeout(startBridgeServer, 3000);
    });

    server.listen(PORT, HOST, () => {
        log(`Bridge ready on ${HOST}:${PORT}\n`);
    });
}

// ─── Entry point ──────────────────────────────────────────────────────────────

// Clear previous setup log on each start so Android gets a fresh view
try { fs.writeFileSync(SETUP_LOG, ''); } catch (_) {}

log('Node.js bridge starting...\n');

if (isClaudeInstalled()) {
    log('Claude Code found — starting bridge server.\n');
    try { fs.writeFileSync(SETUP_DONE, 'true'); } catch (_) {}
    startBridgeServer();
} else {
    log('Claude Code not found — running first-time install.\n');
    log('Downloading ~50 MB, please keep the app open...\n\n');
    runNpmInstall(ok => {
        if (ok) {
            startBridgeServer();
        } else {
            log('\nSetup failed. Tap "Try again" in the app.\n');
            // Write a sentinel so SetupActivity can detect failure
            try { fs.writeFileSync(path.join(FILES_DIR, 'setup_failed'), 'true'); } catch (_) {}
        }
    });
}
