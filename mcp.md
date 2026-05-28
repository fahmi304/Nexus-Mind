# MCP — Model Context Protocol in Nexus Mind

This document covers everything the app does around **MCP** (Model Context Protocol) servers: how they're configured, how the bridge talks to them, how tool calls flow through claude-code, and what every MCP-* improvement (MCP-1 through MCP-9) added to the system.

If you only want to *use* MCP servers, skip to [Add and test a server](#add-and-test-a-server). If you want to understand the wiring (or extend it), read top to bottom.

---

## 1. What MCP is

[MCP](https://modelcontextprotocol.io) is a JSON-RPC 2.0 protocol that lets language models call external **tools** hosted in separate processes (stdio) or services (HTTP/SSE). A server exposes a `tools/list` method and a `tools/call` method; the model picks a tool, the runtime forwards the call, the server returns a result, the model continues.

In this app two engines speak MCP:

| Engine | Where it lives | When it talks to MCP |
|---|---|---|
| **claude-code** (print mode) | `cli.js` v2.1.112 spawned per message | When the user sends a chat message |
| **Bridge agentic loop** | `bridge.js runAgentic()` | When `!agentic on` is set, on every turn |

Both engines now have full access to **both transports** (stdio and HTTP) — see [MCP-1](#mcp-1--http-mcp-in-print-mode-via-stdio-shim) for the print-mode HTTP shim.

---

## 2. Server types

### Stdio
A local child process the bridge spawns. Communicates over its stdin/stdout in newline-delimited JSON-RPC. Lifecycle is owned by the bridge.

Config entry:
```json
{ "name": "fetch",
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-fetch"],
  "enabled": true }
```

Stored in the encrypted prefs as `mcp_stdio_servers` (JSON array), serialized to disk as `filesDir/mcp_stdio.json` by `NodeBridgeManager.writeMcpConfig()`.

### HTTP (Streamable HTTP / 2025-03-26)
A remote endpoint speaking MCP over HTTP POST. Honors the `mcp-session-id` header round-trip and `text/event-stream` SSE responses.

Config entry:
```json
{ "name": "exa",
  "url": "https://mcp.exa.ai/mcp?exaApiKey=…",
  "headers": { "Authorization": "Bearer …" },
  "enabled": true }
```

Stored as `mcp_http_servers` (JSON array), serialized to `filesDir/mcp_http.json`.

---

## 3. File layout (filesDir/)

| File | Owner | Purpose |
|---|---|---|
| `mcp_stdio.json` | Kotlin writes, bridge reads | stdio server entries `[{name, command, args[], enabled?}]` |
| `mcp_http.json` | Kotlin writes, bridge reads | HTTP server entries `[{name, url, headers?, enabled?}]` |
| `mcp_config.json` | Kotlin writes | Combined `{mcpServers}` shape claude-code understands |
| `mcp_tool_cache.json` | Bridge writes, Kotlin reads | `{servers: [{name, type, tools: [name…]}]}` — snapshot for the UI |
| `mcp_disabled_tools.json` | Kotlin writes, bridge reads | `{serverName: [toolName…]}` — per-server tool whitelist (MCP-9) |
| `mcp_reload_requested` | Kotlin touches | Marker file watched by bridge `fs.watch` to trigger soft-reload (MCP-6) |
| `auto_approve.json` | Both | Tool allow/deny lists (not MCP-specific but injected into permissions) |
| `settings.json` (under `.claude/`) | Bridge patches before each spawn | claude-code's settings — includes `mcpServers`, `permissions.allow/deny` |

The three-file split (`mcp_config.json`, `mcp_http.json`, `mcp_stdio.json`) exists for a reason — see CLAUDE.md invariant **51**.

---

## 4. How tool calls flow

### Print mode (default)
```
user message
  └─→ bridge.runMessage()
        └─→ spawn cli.js (claude-code) with patched settings.json
              ├─ settings.json mcpServers includes:
              │    • stdio servers directly
              │    • HTTP servers via mcp_http_proxy.js shim (MCP-1)
              ├─ permissions.allow has '*', 'mcp__*', explicit approveList
              └─ permissions.deny has disabledTools entries (MCP-9)
        └─→ claude-code talks JSON-RPC to each server
              (stdio: directly. HTTP: via the shim subprocess.)
```

### Agentic mode (`!agentic on`)
```
user message
  └─→ bridge.runAgentic()
        ├─→ getMcpStdioTools()  // filtered by disabledTools (MCP-9)
        ├─→ getMcpHttpTools()   // filtered by disabledTools (MCP-9)
        └─→ Anthropic API call with tools[] = built-in + MCP tools
              └─ on tool_use:
                    • mcp_<server>_<tool>  → callMcpStdioTool / callMcpHttpTool
                    • non-MCP             → built-in handler
```

---

## 5. MCP-1 — HTTP MCP in print mode via stdio shim

**Problem:** claude-code v2.1.112 hangs during spawn if `settings.json mcpServers` includes an HTTP entry that requires a network round-trip during its health check (see CLAUDE.md invariant **5**).

**Fix:** A small Node.js shim, `mcp_http_proxy.js`, is spawned by claude-code as a *stdio* server, but internally proxies every JSON-RPC message to the configured upstream HTTP MCP endpoint. The shim does **lazy upstream init** — no network during spawn, so the health check stays clean.

`patchSettings()` injects one shim entry per row of `mcp_http.json` into `settings.json mcpServers`:
```json
"mcpServers": {
  "exa": {
    "type": "stdio",
    "command": "<node>",
    "args": ["<filesDir>/mcp_http_proxy.js"],
    "env": { "MCP_HTTP_URL": "https://mcp.exa.ai/…",
             "MCP_HTTP_HEADERS": "{\"Authorization\":\"Bearer …\"}" }
  }
}
```

`NodeBridgeManager.ensureBridgeJs()` copies the shim asset alongside `bridge.js` on first install.

Result: **HTTP MCP works in both print mode and agentic mode** as of MCP-1. The "agentic only" label that used to appear in the terminal MCP popover is gone (MCP-4).

---

## 6. MCP-2 — Per-server headers / auth

**Problem:** Many HTTP MCP servers require auth (bearer tokens, API keys) sent as HTTP headers, not in the URL.

**Fix:** `McpServer` gained a `headers: String` field (compact JSON `{}` string). The add-server dialog has a multi-line "Key: Value" input, parsed by `parseHeadersInput()` into a `JSONObject`.

`NodeBridgeManager.writeMcpConfig()` writes the headers into `mcp_http.json` entries. `bridge.js mcpHttpPost()` accepts an `extraHeaders` arg propagated through:
- `startMcpHttpServer` (agentic mode), and
- `MCP_HTTP_HEADERS` env to each MCP-1 shim instance (print mode).

Same headers work in both modes. Empty headers behave identically to pre-MCP-2 configs.

---

## 7. MCP-3 — In-dialog "Test Connection"

**Problem:** Users only discovered config was broken when they tried to use the server in a message.

**Fix:** The Add Server dialog has a "Test Connection" button (HTTP only). It runs the same handshake as `bridge.js startMcpHttpServer`:
1. POST `initialize` → get session id + capabilities
2. POST `notifications/initialized` (fire-and-forget)
3. POST `tools/list` → list of tools

Result displays inline:
- `✓ N tools` + comma-separated names on success (green)
- `✗ failed` + trimmed error string on failure (red)

Implemented in `McpScreen.kt testMcpHttpServer()`. Honors the `mcp-session-id` round-trip and SSE event-stream responses. Editing the URL or headers resets the result so stale `✓` badges don't linger.

Stdio test was skipped — spawning processes from the dialog isn't reliable on Android.

---

## 8. MCP-4 — Connected / total status chip

**Problem:** The terminal chip showed total tool count, hid itself when no servers came up, and gave no signal that a configured server had failed to start.

**Fix:**
- `bridge.js` tracks failed servers in a new `mcpFailed` Map (`name → {type, error}`). Both `startMcpStdioServer` and `startMcpHttpServer` set the entry on error, clear it on success.
- `buildMcpPayload()` now includes failed entries with `status: 'failed'` and the captured error.
- Terminal chip text format flipped from `MCP ● N` (tools) to `MCP ● N/M` (connected over configured). Color:
  - **green** — all up
  - **amber** — some failed (`#mcp-chip.warn`)
  - **red** — all failed (`#mcp-chip.fail`)
- Popover header shows `N/M CONNECTED`; failed servers render with red `✗` + truncated error.

The stale "agentic only" label for HTTP was dropped — MCP-1 made HTTP work in both modes.

---

## 9. MCP-5 — Stderr capture + `!mcp-log`

**Problem:** stdio MCP stderr was previously truncated to 200 chars per chunk and inlined into setup.log. Multi-line stack traces got fragmented and were hard to find.

**Fix:**
- Each stdio server gets an in-memory ring buffer `stderrLines: string[]` (last 200 lines), line-split via a `stderrBuf` accumulator so multi-line traces stay grouped.
- Each line still goes to setup.log via the existing `log()` call.
- New terminal command **`!mcp-log [name|all]`** prints the buffered stderr. With no arg → all servers, last 50 lines each. With `all` → full ring. With a server name → only that server's lines.
- Failed servers (`mcpFailed` map from MCP-4) are also surfaced — `!mcp-log` shows their captured init error.

`!mcp` output also gained a failed-servers section. `!help` mentions the new command.

---

## 10. MCP-6 — Soft-reload on toggle

**Problem:** Toggling a server's enable switch in Settings rewrote the config files but the running session kept the old state until `!clear`.

**Fix:**
- `bridge.js reloadMcpServers()` diffs on-disk config against running maps. Stops servers no longer wanted (kills stdio procs, drops entries), starts newly-enabled ones, re-broadcasts `mcp-ready`. Terminal session keeps history.
- Two entry points trigger it:
  1. **`!mcp-reload`** command in the terminal (prints a green status line)
  2. **Marker file** `mcp_reload_requested` watched via `fs.watch(FILES_DIR)`. `NodeBridgeManager.writeMcpConfig()` creates this marker after each successful config write, so the Settings toggle row fires it automatically.

Intentional kills set `srv._intentionalKill = true` so the MCP-7 restart path doesn't fire.

---

## 11. MCP-7 — Auto-reconnect crashed stdio servers

**Problem:** If a stdio MCP server crashed (segfault, OOM, uncaught exception), it was silently gone — the user only saw "tool not found" the next time they invoked it.

**Fix:**
- The `proc.on('exit', …)` handler now calls `scheduleMcpStdioRestart(entry)` unless the kill was intentional.
- Exponential backoff: 1s → 2s → 4s → 8s → 16s, capped at 30s. Up to 5 attempts.
- After 5 failures the server is marked failed via the `mcpFailed` map so MCP-4's chip surfaces it.
- Restart counter resets after **5 minutes of stable uptime** — a server that crashes once a day stays auto-recovered indefinitely; a true crashloop is bounded.
- The scheduled restart re-reads `mcp_stdio.json` before respawning in case the entry was deleted while the timer was pending.

---

## 12. MCP-8 — Built-in server templates

**Problem:** Users new to MCP had to know the exact `npx` package names + args to set up common servers. High barrier to entry.

**Fix:** The Add Server dialog has a collapsed "Templates" row at the top. Expand → list of presets, tap one → form is prefilled.

Bundled templates:

| Template | Type | What it does | Extra setup |
|---|---|---|---|
| Filesystem | stdio | Read/write under a directory | Edit the path in args |
| Fetch | stdio | Fetch arbitrary URLs | None |
| GitHub | stdio | GitHub API access | `GITHUB_PERSONAL_ACCESS_TOKEN` env |
| Brave Search | stdio | Web search via Brave | `BRAVE_API_KEY` env |
| Memory | stdio | KV memory store | None |
| Exa | HTTP | AI-native search | Replace `YOUR_KEY` in URL |

Each template's description text in the dialog spells out what extra setup it needs so users aren't surprised.

---

## 13. MCP-9 — Per-server tool whitelist

**Problem:** A server might expose 10 tools but the user only wants 2 of them enabled. Previously all-or-nothing.

**Fix:**
- Each MCP server card has a **"tools"** affordance that opens a manager dialog listing every tool the server exposes (one row per tool, tap to toggle off/on).
- "Save" writes the disabled set to `mcp_disabled_tools.json` keyed by server name.

bridge.js applies the disabled list in three places:

1. **`getMcpStdioTools()` / `getMcpHttpTools()`** filter disabled tools out of the agentic loop's tool array.
2. **`patchSettings()`** injects `mcp__<server>__<tool>` entries into `permissions.deny` so claude-code rejects calls in print mode.
3. **`broadcastMcpReady()`** writes `mcp_tool_cache.json` so the Android UI can list available tools without re-doing the MCP handshake itself.

`McpDisabledTools.save()` touches the existing `mcp_reload_requested` marker (from MCP-6), so toggles take effect in live sessions without `!clear`.

**Limitation in print mode:** claude-code still sees disabled tools listed by the upstream server (no way to hide them at that layer) — they're just denied at call time. The agentic loop never sees them at all.

---

## 14. Terminal commands cheat sheet

All MCP-related slash commands available in the terminal:

| Command | Description | Added |
|---|---|---|
| `!mcp` | List connected (and failed) MCP servers + their tools | pre-MCP-1 |
| `!mcp-log [name\|all]` | Show captured stderr from stdio servers (default 50 lines) | MCP-5 |
| `!mcp-reload` | Apply Settings toggles without restarting the session | MCP-6 |

---

## 15. Add and test a server

1. Open the MCP screen (Home → MCP, or via Settings → Manage MCP Servers).
2. Tap **+ Add**.
3. *(Optional)* Expand **Templates** → tap a preset to prefill the form.
4. Choose **HTTP (Remote)** or **Stdio**.
5. Name + URL (or command + args). For HTTP, add any required auth headers in the **Headers** field, one `Key: Value` per line.
6. For HTTP: tap **Test Connection**. You should see `✓ N tools` with the tool names. If you see `✗ failed`, fix the URL or token and re-test.
7. Tap **Add**.

The server appears in the list. HTTP servers show a status dot (green=live, red=unreachable). The terminal chip updates automatically the next time the bridge soft-reloads (which is immediate via the marker file).

---

## 16. Disable a specific tool

1. From the MCP screen, tap the **tools** chip on a server's card.
2. The dialog lists every tool the server exposes. Bullet color:
   - **● green** = on (default)
   - **○ gray** = off
3. Tap any tool to toggle it. Tap **Save**.

Disabled tools are immediately excluded from the agentic loop and added to `permissions.deny` for the next claude-code spawn.

If the dialog says *"No tools discovered yet"*: the bridge hasn't connected to that server yet. Open the terminal once so the connection completes, then come back to the dialog.

---

## 17. Debugging checklist

When MCP isn't working:

1. Check the chip in the terminal header — is it amber/red? Tap it to see which server failed and why.
2. Run `!mcp` in the terminal — see connected + failed servers in detail.
3. Run `!mcp-log <name>` — see captured stderr from the failing server (often a missing env var or bad path).
4. Run `!mcp-reload` — re-read config and restart everything. Useful after editing env vars on disk.
5. Run `!log 100` — see broader bridge log around the MCP failure.
6. For HTTP servers: open Add Server dialog with the failing config, tap **Test Connection** to get a fresh error message.

---

## 18. What's still open

These items are tracked in `CLAUDE.md` under "TODO":

- The MCP popover currently doesn't expose the per-tool whitelist live to the terminal (read-only from there).
- No way yet to ship env vars per stdio server through the UI. Users have to set them via Termux or another mechanism before launching the app.
- HTTP MCP servers don't surface stderr — only their `start failed` HTTP error is captured. The MCP-1 shim's stderr is logged with a `[mcp-http-proxy:NAME]` tag but isn't yet routed into the `!mcp-log` ring.

If you pick one of these up, update this doc in the same PR.
