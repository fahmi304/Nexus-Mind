#!/usr/bin/env node
'use strict';
/**
 * HTTP MCP server test — Exa search.
 *
 * Connects to an HTTP MCP server using the Streamable HTTP transport
 * (MCP 2025-03-26) with SSE fallback (MCP 2024-11-05).
 *
 * Flow: initialize → notifications/initialized → tools/list → tools/call (search)
 *
 * Usage:
 *   node .github/scripts/test-mcp-http.js
 *   (API key is embedded in the URL for CI)
 */

const https = require('https');

const MCP_URL = 'https://mcp.exa.ai/mcp?exaApiKey=a964443e-e81f-4eac-8808-4848a088a9e6';

let passed = 0, failed = 0;
function ok(label)        { console.log('  ✓ ' + label); passed++; }
function fail(label, msg) { console.error('  ✗ ' + label + ': ' + msg); failed++; }

// ─── HTTP helpers ─────────────────────────────────────────────────────────────

// Send one JSON-RPC request, return parsed JSON response.
// Handles both plain JSON responses and SSE streams (picks first `data:` line).
function mcpPost(url, body, sessionId) {
    return new Promise((resolve, reject) => {
        const bodyStr = JSON.stringify(body);
        const parsed  = new URL(url);
        const headers = {
            'Content-Type':  'application/json',
            'Accept':        'application/json, text/event-stream',
            'Content-Length': Buffer.byteLength(bodyStr),
        };
        if (sessionId) headers['mcp-session-id'] = sessionId;

        const req = https.request({
            hostname: parsed.hostname,
            port:     443,
            path:     parsed.pathname + parsed.search,
            method:   'POST',
            headers,
        }, res => {
            const ct = (res.headers['content-type'] || '').toLowerCase();
            let buf = '';
            res.setEncoding('utf8');
            res.on('data', c => buf += c);
            res.on('end', () => {
                if (res.statusCode === 202) { resolve(null); return; } // accepted, no body
                if (res.statusCode < 200 || res.statusCode >= 300) {
                    return reject(new Error('HTTP ' + res.statusCode + ': ' + buf.slice(0, 200)));
                }
                if (ct.includes('text/event-stream')) {
                    // Parse SSE — collect all data: lines, return array of JSON objects
                    const events = [];
                    for (const line of buf.split('\n')) {
                        const t = line.trim();
                        if (!t.startsWith('data:')) continue;
                        const raw = t.slice(5).trim();
                        if (!raw || raw === '[DONE]') continue;
                        try { events.push(JSON.parse(raw)); } catch (_) {}
                    }
                    // Return the first JSON-RPC response (id !== undefined)
                    const rpc = events.find(e => e.id !== undefined);
                    resolve(rpc || events[0] || null);
                } else {
                    try { resolve(JSON.parse(buf)); } catch (e) { reject(new Error('bad JSON: ' + buf.slice(0, 200))); }
                }
            });
            res.on('error', reject);
        });
        req.setTimeout(20000, () => { req.destroy(new Error('timeout')); });
        req.on('error', reject);
        req.write(bodyStr);
        req.end();
    });
}

// Send notifications/initialized (fire-and-forget — server may return 202 or nothing)
function mcpNotify(url, method, params, sessionId) {
    return mcpPost(url, { jsonrpc: '2.0', method, params: params || {} }, sessionId).catch(() => {});
}

// ─── Main ─────────────────────────────────────────────────────────────────────

(async () => {
    console.log('\nClaudeCodeSetup — HTTP MCP server test (Exa)');
    console.log('=============================================');
    console.log('  Endpoint : ' + MCP_URL.replace(/exaApiKey=[^&]+/, 'exaApiKey=***') + '\n');

    // ── Step 1: initialize ────────────────────────────────────────────────────
    console.log('── Step 1: initialize ──');
    let sessionId = null;
    let initResult;
    try {
        const initReq = {
            jsonrpc: '2.0', id: 1, method: 'initialize',
            params: {
                protocolVersion: '2025-03-26',
                capabilities: { tools: {} },
                clientInfo: { name: 'ClaudeCodeSetup-CI', version: '1.0' },
            },
        };
        const initRes = await mcpPost(MCP_URL, initReq, null);
        if (!initRes) throw new Error('empty response from initialize');
        if (initRes.error) throw new Error(JSON.stringify(initRes.error));
        initResult = initRes.result || initRes;
        ok('initialize — protocolVersion: ' + (initResult.protocolVersion || '?'));

        // Extract session ID if the server provided one (Streamable HTTP spec)
        if (initRes._sessionId) sessionId = initRes._sessionId;
        // Some servers send it in a header — we can't read that here, so skip
    } catch (e) {
        fail('initialize', e.message);
        console.log('\n── Summary ──');
        console.log('  Passed: ' + passed + '   Failed: ' + failed);
        process.exit(1);
    }

    // ── Step 2: notifications/initialized ────────────────────────────────────
    await mcpNotify(MCP_URL, 'notifications/initialized', {}, sessionId);
    ok('notifications/initialized sent');

    // ── Step 3: tools/list ────────────────────────────────────────────────────
    console.log('\n── Step 2: tools/list ──');
    let tools = [];
    try {
        const res = await mcpPost(MCP_URL, { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }, sessionId);
        if (!res) throw new Error('empty response');
        if (res.error) throw new Error(JSON.stringify(res.error));
        const result = res.result || res;
        tools = result.tools || [];
        if (tools.length === 0) throw new Error('server returned 0 tools');
        ok('tools/list — ' + tools.length + ' tool(s) available');
        for (const t of tools.slice(0, 5)) {
            console.log('    • ' + t.name + (t.description ? ' — ' + t.description.slice(0, 60) : ''));
        }
    } catch (e) {
        fail('tools/list', e.message);
        console.log('\n── Summary ──');
        console.log('  Passed: ' + passed + '   Failed: ' + failed);
        process.exit(1);
    }

    // ── Step 4: pick a search tool and call it ────────────────────────────────
    console.log('\n── Step 3: tools/call ──');
    // Prefer tools with "search" in the name; fall back to first tool
    const searchTool = tools.find(t => /search/i.test(t.name)) || tools[0];
    console.log('  Calling tool: ' + searchTool.name);

    // Build minimal valid arguments from the tool's input schema
    const schema     = searchTool.inputSchema || {};
    const props      = schema.properties || {};
    const required   = schema.required || [];
    const callArgs   = {};
    for (const key of required) {
        const p = props[key] || {};
        if (p.type === 'string' || !p.type) {
            // Fill in sensible defaults
            if (/query|q|text|input|keyword|search/i.test(key)) callArgs[key] = 'hello world latest news';
            else if (/url/i.test(key)) callArgs[key] = 'https://example.com';
            else if (/num|count|limit|max/i.test(key)) callArgs[key] = 3;
            else callArgs[key] = 'test';
        } else if (p.type === 'number' || p.type === 'integer') {
            callArgs[key] = 3;
        } else if (p.type === 'boolean') {
            callArgs[key] = false;
        }
    }
    // If no required fields were found but there's a query-like property, add it
    if (Object.keys(callArgs).length === 0) {
        const queryProp = Object.keys(props).find(k => /query|q|text|input/i.test(k));
        if (queryProp) callArgs[queryProp] = 'hello world latest news';
    }
    console.log('  Arguments: ' + JSON.stringify(callArgs));

    try {
        const res = await mcpPost(MCP_URL, {
            jsonrpc: '2.0', id: 3, method: 'tools/call',
            params: { name: searchTool.name, arguments: callArgs },
        }, sessionId);
        if (!res) throw new Error('empty response');
        if (res.error) throw new Error(JSON.stringify(res.error));
        const result = res.result || res;
        const content = result.content || [];
        if (content.length === 0) throw new Error('tool returned empty content array');
        const text = content.map(c => c.text || JSON.stringify(c)).join('').trim();
        if (!text) throw new Error('tool returned content with no text');
        ok('tools/call: ' + searchTool.name + ' → ' + text.length + ' chars returned');
        console.log('  preview: "' + text.slice(0, 200).replace(/\n/g, ' ') + '"');
    } catch (e) {
        fail('tools/call: ' + searchTool.name, e.message);
    }

    console.log('\n── Summary ──');
    console.log('  Passed: ' + passed + '   Failed: ' + failed);

    if (process.env.GITHUB_STEP_SUMMARY) {
        const status = failed === 0 ? '✅ PASSED' : '❌ FAILED';
        const lines = [
            '## HTTP MCP Test (Exa)',
            '',
            '| | |',
            '|---|---|',
            '| Endpoint | mcp.exa.ai |',
            '| Transport | Streamable HTTP (2025-03-26) |',
            '| Tools found | ' + tools.length + ' |',
            '| Result | ' + status + ' — ' + passed + ' passed, ' + failed + ' failed |',
        ];
        require('fs').appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
    }

    process.exit(failed > 0 ? 1 : 0);
})();
