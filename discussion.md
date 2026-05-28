# Discussion — Multi-Model Debate

A standalone in-app feature where 2–4 AI models from any configured provider hold a structured discussion about a topic the user supplies. Models see each other's turns, push back when they disagree, and converge (or get capped). Calls every provider directly from Kotlin — never touches `bridge.js` or `claude-code`.

If you're using it: skip to [Quick start](#quick-start). If you're extending it, read top to bottom.

---

## 1. What it's for

The terminal (Chat Box / claude-code) gives you **one model with tools**. Discussion gives you **multiple models without tools**.

Use it when:
- You want a second opinion that genuinely pushes back, not a polite agreement.
- You're stuck on a decision and want pros/cons argued by separate models.
- You wrote code and want it critiqued by 2–3 reviewers in parallel.
- You're studying how different model families handle the same prompt.

Don't use it when:
- You need to run, read, or write files. (No tools, no MCP, no file access.)
- You want to verify a coding claim by executing it. (Drop into the terminal for that.)
- You want chat-style turn-by-turn back-and-forth with one model. (Use Chat Box.)

---

## 2. Modes

There are four. Mode picks the system prompt scaffolding and how roles are assigned.

| Mode | Best for | Structure |
|---|---|---|
| **Roundtable** | Open-ended questions | Free-form. Each speaker reads what others said, agrees or disagrees substantively, may shift position. |
| **Debate** | Decisions, "should we…" | Sides assigned. 2 → For / Against. 3 → For / Against / Moderator. 4 → 2v2. |
| **Critique** | "Review my plan" | First speaker proposes. Rest tear it apart and refine. |
| **Code Review** | Bugs, perf, design | Critique structure, but the per-speaker prompt focuses on correctness, edge cases, complexity, and concrete line references. UI biases the model picker toward `Cap.CODING` models. |

The **adversarial baseline** is shared across modes: *"You're in a panel discussion with other AI models. Read what the others said. Disagree when you think they're wrong and explain why with specifics. Update your own position if a counter-argument convinces you. Don't summarize, don't be polite for politeness' sake — but don't manufacture disagreement either."* Without this, models default to bland mutual agreement.

---

## 3. Quick start

1. Make sure you have at least one provider with an API key configured (Home → Setting → Provider, or first-launch login).
2. Home → tap the **Discussion** tile.
3. Type your topic (paste code, ask a question, propose a plan).
4. Pick a mode. Code Review shows an inline warning that models can't run your code.
5. Tap **SPEAKERS** → pick 2–4 models from the bottom sheet. The order matters for Debate / Critique (first = For / Proposer).
6. *(Optional)* Move the **Max turns** slider (default 6).
7. *(Optional)* Toggle **Final judge summary** — the first speaker gets one extra call at the end to summarize agreements / disagreements / strongest argument.
8. Tap **Start Discussion**.

The live screen takes over: turns stream in real time with token counters. The **Stop** button always works mid-turn. When the discussion ends (cap, converged, or stopped), the footer shows **New** (back to setup) and **Continue** (extend by one round).

---

## 4. How a turn happens

Every turn does the same thing:

```
1. Orchestrator picks next speaker (round-robin through state.speakers).
2. PromptBuilder.buildMessages(...):
     - system  = baseRules + modeOverlay + roster line ("you are X, the others are Y, Z").
     - user    = "## Topic\n<topic>\n\n## Discussion so far\n<all prior DONE turns
                  rendered as headed sections>\n\nNow you respond as **<speaker>**."
3. ProviderClient.streamChat(speaker, messages) → Flow<ChatChunk>.
     - OpenAI-format providers → POST /chat/completions, parse SSE.
     - Anthropic API (provider.id == "anthropic_api") → POST /messages,
       parse the Anthropic event stream (content_block_delta / message_delta).
4. Each Delta chunk appends to the in-progress turn's text. The UI sees
   the StateFlow update and rerenders that bubble.
5. On Done(prompt_tokens, completion_tokens) the turn flips to DONE and
   the loop re-evaluates: maxTurns reached? converged? otherwise next.
```

The **whole transcript** is rebuilt and sent every turn. This is what enables real argument — speaker B literally sees what speaker A claimed. Cost scales with `turns² × avg_turn_size`, which is why `maxTurns` caps at 12.

---

## 5. Convergence detection

`ConvergenceDetector.isConverged(turns)` runs after each completed turn. It's intentionally conservative — false negatives just mean the discussion hits `maxTurns` instead of stopping early.

Signal:
- The last 2 completed turns are both ≤ 160 characters (no new substance).
- At least one of them contains an agreement phrase ("i agree", "good point", "no objections", "nothing to add", "i concur", "i'm convinced", "well said", "you're right", "fair point", "agreed", "no further points").
- Neither contains a strong-disagreement phrase ("but", "however", "i disagree", "actually", "on the contrary", "i'd push back", "not quite", "incorrect", "i'd argue").

If all three hold, `state.converged = true` and the loop exits. The UI shows a green "● converged" badge in the status strip. The user can still tap **Continue** to override.

---

## 6. Error handling per turn

`ProviderClient` translates HTTP outcomes into typed `ChatChunk` terminals so the orchestrator can route them:

| HTTP | Chunk | Orchestrator behavior |
|---|---|---|
| 200 + SSE | `Delta` × N then `Done(p, c)` | Normal path — turn → DONE |
| 429 | `RateLimited(retryAfter)` | Turn → SKIPPED, 500 ms backoff, next speaker continues |
| 402 | `OutOfCredits(message)` | Turn → FAILED, **whole discussion stops** (`stoppedReason = "out of credits"`) |
| any 4xx / 5xx | `FailedRequest(message)` | Turn → FAILED, loop continues to next speaker |
| Cancellation | (caught) | Turn → STOPPED, loop unwinds cleanly |

The UI surfaces these inline on the bubble: red dot for failed, amber for skipped, gray for stopped, plus the trimmed error text.

---

## 7. Files

```
app/src/main/java/com/claudecodesetup/
├── discussion/                          ← pure logic, no UI
│   ├── DiscussionModels.kt
│   ├── PromptBuilder.kt
│   ├── ConvergenceDetector.kt
│   ├── ProviderClient.kt
│   └── DiscussionOrchestrator.kt
└── ui/
    ├── DiscussionActivity.kt
    ├── DiscussionScreen.kt
    ├── DiscussionSetupScreen.kt
    ├── DiscussionModelPicker.kt
    ├── DiscussionLiveScreen.kt
    └── DiscussionPersistence.kt
```

Plus three small edits:
- `HomeScreen.kt` — `onDiscussion` callback + Discussion menu card + `DiscussionIcon` (two overlapping speech bubbles).
- `HomeActivity.kt` — wires the callback to `DiscussionActivity`.
- `AppPreferences.kt` — `getDiscussionLastConfigJson / saveDiscussionLastConfigJson` slot.
- `AndroidManifest.xml` — `<activity android:name=".ui.DiscussionActivity">`.

---

## 8. Persistence

Option **3B** ("last config remembered"). After every successful **Start**, `DiscussionPersistence.save(prefs, cfg)` writes:

```json
{
  "topic": "…",
  "mode": "CODE_REVIEW",
  "maxTurns": 6,
  "enableJudge": false,
  "speakers": [
    { "providerId": "groq", "modelId": "llama-3.3-70b-versatile" },
    { "providerId": "deepseek", "modelId": "deepseek-chat" }
  ]
}
```

API keys + base URLs are **never** in this file — they're re-resolved from `AppPreferences` at load time. So rotating an API key never leaves a stale credential behind, and a deleted provider just drops its speaker silently on the next pre-fill.

Transcripts are **not** persisted (that would be option 3C). Closing the activity loses the in-progress and finished transcripts. If users start asking for history, we'll upgrade to 3C in a follow-up.

---

## 9. Provider support

Discussion v1 supports all providers in `Providers.ALL` that:
- Have an API key configured, **AND**
- Speak either the OpenAI `/chat/completions` shape or the Anthropic `/messages` shape.

That's: Groq, Gemini (OpenAI-compatible endpoint), OpenRouter, Anthropic API, DeepSeek, Kimi, NVIDIA NIM, Meta Llama, Ollama.

**Not supported in v1:**
- **Anthropic subscription (OAuth)** — requires routing through claude-code's proxy. Skipped for v1; users with only an Anthropic subscription should add a free provider (Groq / OpenRouter / Gemini) to use Discussion.
- **Local Llama (on-device GGUF)** — not yet wired through `ProviderClient`. Easy to add later (just route to the local server URL).

---

## 10. Code Review mode — known limitations

The Code Review mode prompt explicitly tells the model to push back on claims it can't verify by reading the code alone. But the model is still inferring behavior — it cannot run anything. This means:

- *"This function would crash on empty input"* — claim, not verified.
- *"This is O(n²)"* — usually correct on simple loops, can be wrong on libraries with hidden complexity.
- *"This passes all the test cases"* — never trust this from Code Review mode. Models sometimes hallucinate test results.

The setup screen surfaces this with an amber warning when Code Review is selected. The follow-up workflow we recommend: run a discussion to surface candidate fixes, then drop into Chat Box (claude-code) and have it actually run / test the changes.

---

## 11. Costs and rate limits

A 6-turn discussion makes 6 API calls (or 7 with judge). Token cost per turn grows with transcript size — the last turn sees the full prior transcript as input.

Rough envelope for a typical 6-turn discussion at ~300 output tokens per turn:
- Turn 1 input: topic only (~100 tokens)
- Turn 6 input: topic + 5 prior turns (~2000 tokens)
- Total input across all turns: ~6000 tokens
- Total output across all turns: ~1800 tokens

The live screen shows running totals (`↑prompt ↓completion`). 429s skip a single speaker. 402 ends the whole discussion with a clear banner — re-read the error and switch to a free model.

---

## 12. What's deliberately out of scope for v1

- Tool use (no MCP, no web search, no file access).
- Persistent transcripts / history screen.
- Audience interjection (mid-discussion user input).
- Real-time voting between turns.
- Speech synthesis.
- Long-press a turn → regenerate.

Most of these are easy to add later if the format gets used. Keeping v1 small forces us to ship something testable instead of building speculatively.

---

## 13. Extending it

**Add a new mode:** add an enum to `DiscussionMode`, add a `when` branch in `PromptBuilder.systemPromptFor` and `PromptBuilder.assignRoles`, add a label in `DiscussionLiveScreen.modeBadge`. No other touch-points.

**Add a new provider:** as long as the provider exists in `Providers.ALL` and `ProvidersRepository.fetchModels` knows about it, Discussion automatically lists it in the picker. If the provider speaks something other than OAI/Anthropic, add a third branch in `ProviderClient.streamChat`.

**Persist history (3C):** create `DiscussionHistory.kt` that serializes `DiscussionState` to `filesDir/discussions/<timestamp>.json`. Add a `DiscussionHistoryScreen.kt`. Hook a third state to `DiscussionScreen`'s router. Estimated +250 LOC.

**Reuse `ProviderClient` for Quick Ask:** it's already provider-agnostic and streaming. Quick Ask just feeds a single-speaker, multi-turn message list. ~half-day once Discussion ships.

---

## 14. Open follow-ups

- `Cap.CODING` filter in the picker is visual only — selecting a non-coding model in Code Review mode is allowed (just not recommended). If usage data shows people miss this, we can hard-filter.
- The judge speaker is currently always `speakers.first()`. A future setup screen could let the user pick any model (including one not in the panel) explicitly.
- Topic field has no character cap. A very long topic (10+ KB) will inflate every turn's input — consider a soft cap with a warning.
- No "stop just this speaker" affordance. The Stop button kills the whole discussion.

Track these in `CLAUDE.md` if they become real asks.
