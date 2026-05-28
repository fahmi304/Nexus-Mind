package com.claudecodesetup.discussion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One streaming chunk from a provider, or a terminal signal.
 *
 * Sealed so the orchestrator can pattern-match without sentinel strings.
 * Tokens are reported by the provider in the final chunk (OAI usage block,
 * Anthropic message_delta) and may be 0 if the provider doesn't return them.
 */
sealed class ChatChunk {
    data class Delta(val text: String) : ChatChunk()
    data class Done(val promptTokens: Int, val completionTokens: Int) : ChatChunk()
    data class RateLimited(val retryAfterMs: Long?) : ChatChunk()        // 429
    data class OutOfCredits(val message: String) : ChatChunk()           // 402
    data class FailedRequest(val message: String) : ChatChunk()          // 4xx / 5xx other
}

/**
 * Provider-agnostic streaming chat client. Used by Discussion (and later
 * Quick Ask). Calls providers DIRECTLY — bypasses bridge.js / claude-code
 * entirely. Anthropic-format providers use /messages; everyone else uses
 * the OpenAI /chat/completions shape.
 */
object ProviderClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // long enough for a slow Sonnet turn
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun streamChat(speaker: Speaker, messages: List<ChatMessage>): Flow<ChatChunk> {
        val providerId = speaker.provider.id
        return if (providerId == "anthropic_api") streamAnthropic(speaker, messages)
               else streamOpenAi(speaker, messages)
    }

    // ── OpenAI-format ────────────────────────────────────────────────────────
    private fun streamOpenAi(speaker: Speaker, messages: List<ChatMessage>): Flow<ChatChunk> = flow {
        val url = speaker.baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", speaker.model.modelId)
            put("stream", true)
            put("messages", JSONArray().apply {
                for (m in messages) put(JSONObject().apply {
                    put("role", m.role); put("content", m.content)
                })
            })
            // Ask for usage in the stream (OpenAI/Groq/Together honor this).
            put("stream_options", JSONObject().apply { put("include_usage", true) })
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer " + speaker.apiKey)
            .header("Accept", "text/event-stream")
            .build()

        http.newCall(req).execute().use { res ->
            val code = res.code
            if (code == 429) {
                val retry = res.header("retry-after")?.toLongOrNull()?.let { it * 1000 }
                emit(ChatChunk.RateLimited(retry)); return@use
            }
            if (code == 402) {
                emit(ChatChunk.OutOfCredits(res.body?.string()?.take(400) ?: "402 Out of credits"))
                return@use
            }
            if (!res.isSuccessful) {
                val raw = res.body?.string()?.take(500) ?: ""
                emit(ChatChunk.FailedRequest("HTTP $code: ${stripTags(raw)}"))
                return@use
            }
            var pTok = 0; var cTok = 0
            val src = res.body?.source() ?: run {
                emit(ChatChunk.FailedRequest("no body")); return@use
            }
            while (!src.exhausted()) {
                val raw = src.readUtf8Line() ?: break
                val line = raw.trim()
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val obj = try { JSONObject(payload) } catch (_: Exception) { continue }
                // Some providers send usage as a separate trailing chunk with empty choices.
                obj.optJSONObject("usage")?.let {
                    pTok = it.optInt("prompt_tokens", pTok)
                    cTok = it.optInt("completion_tokens", cTok)
                }
                val choices = obj.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val delta = choices.getJSONObject(0).optJSONObject("delta")?.optString("content").orEmpty()
                if (delta.isNotEmpty()) emit(ChatChunk.Delta(delta))
            }
            emit(ChatChunk.Done(pTok, cTok))
        }
    }.flowOn(Dispatchers.IO)

    // ── Anthropic API (/messages) ────────────────────────────────────────────
    // System prompt is its own field; only user/assistant roles allowed inside
    // messages. We collapse our internal ChatMessage list accordingly.
    private fun streamAnthropic(speaker: Speaker, messages: List<ChatMessage>): Flow<ChatChunk> = flow {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val convo = messages.filter { it.role != "system" }
        // Anthropic baseUrl is just the host ("https://api.anthropic.com") with no
        // /v1 suffix, unlike the OAI providers whose baseUrl already includes /v1.
        // Without this, we'd POST to /messages and Anthropic returns 404.
        val url = speaker.baseUrl.trimEnd('/') + "/v1/messages"
        val body = JSONObject().apply {
            put("model", speaker.model.modelId)
            put("stream", true)
            put("max_tokens", 1024)
            if (system.isNotEmpty()) put("system", system)
            put("messages", JSONArray().apply {
                for (m in convo) put(JSONObject().apply {
                    put("role", if (m.role == "assistant") "assistant" else "user")
                    put("content", m.content)
                })
            })
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("x-api-key", speaker.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .build()

        http.newCall(req).execute().use { res ->
            val code = res.code
            if (code == 429) {
                val retry = res.header("retry-after")?.toLongOrNull()?.let { it * 1000 }
                emit(ChatChunk.RateLimited(retry)); return@use
            }
            if (code == 402) {
                emit(ChatChunk.OutOfCredits(res.body?.string()?.take(400) ?: "402 Out of credits"))
                return@use
            }
            if (!res.isSuccessful) {
                val raw = res.body?.string()?.take(500) ?: ""
                emit(ChatChunk.FailedRequest("HTTP $code: ${stripTags(raw)}"))
                return@use
            }
            var pTok = 0; var cTok = 0
            val src = res.body?.source() ?: run {
                emit(ChatChunk.FailedRequest("no body")); return@use
            }
            while (!src.exhausted()) {
                val raw = src.readUtf8Line() ?: break
                val line = raw.trim()
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                val obj = try { JSONObject(payload) } catch (_: Exception) { continue }
                when (obj.optString("type")) {
                    "content_block_delta" -> {
                        val d = obj.optJSONObject("delta")?.optString("text").orEmpty()
                        if (d.isNotEmpty()) emit(ChatChunk.Delta(d))
                    }
                    "message_start" -> {
                        obj.optJSONObject("message")?.optJSONObject("usage")?.let {
                            pTok = it.optInt("input_tokens", pTok)
                        }
                    }
                    "message_delta" -> {
                        obj.optJSONObject("usage")?.let { cTok = it.optInt("output_tokens", cTok) }
                    }
                    "message_stop" -> {}
                    "error" -> {
                        val msg = obj.optJSONObject("error")?.optString("message") ?: "Anthropic stream error"
                        emit(ChatChunk.FailedRequest(msg)); return@use
                    }
                }
            }
            emit(ChatChunk.Done(pTok, cTok))
        }
    }.flowOn(Dispatchers.IO)

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
}
