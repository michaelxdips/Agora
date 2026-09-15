package com.newoether.agora.wear

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The watch's own LLM call.
 *
 * Standalone means **standalone**: the watch talks to the configured OpenAI-compatible endpoint
 * directly over OkHttp, with no phone relay, no Agora transport, no shared code with the phone's
 * provider stack. That is a deliberate duplication — the watch module must build and run without the
 * phone app's module graph, and a shared transport would drag llama.cpp, Room and the whole provider
 * registry onto a 2 GB watch.
 *
 * Non-streaming: a watch screen is small, answers are short, and streaming would add a connection to
 * babysit across wrist-down events for no visible benefit.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class WearChatClient(private val config: WearConfig) {

    /**
     * Shared, not per-instance. The first version built a new `OkHttpClient` per `WearChatClient`,
     * and `send()` constructs one client per question — so every question allocated a fresh
     * connection pool and dispatcher thread pool that OkHttp keeps alive until the client is
     * garbage-collected. On a 2 GB watch that is a leak that shows up as latency, not as a crash.
     *
     * A single client is also what OkHttp's own documentation asks for: it shares the connection
     * pool and the thread pool, so a follow-up question reuses the TLS session instead of
     * renegotiating it on a watch CPU.
     */
    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Sends one question with [coreContext] as the system prompt.
     *
     * @return the assistant text, or a [Failure] describing exactly what went wrong. Never throws:
     *   the caller is a watch UI that must show *something*.
     */
    fun ask(question: String, coreContext: String): Result<String> {
        if (!config.isValid()) return Result.failure(WearChatException("not configured"))
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", false)
            put(
                "messages",
                buildJsonArray {
                    if (coreContext.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", coreContext)
                            }
                        )
                    }
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", question)
                        }
                    )
                },
            )
        }
        val request = Request.Builder()
            .url(endpoint(config.baseUrl))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Status only — the body can echo the key in a proxy error page.
                    throw WearChatException("HTTP ${response.code}")
                }
                Result.success(parseContent(text) ?: throw WearChatException("unreadable response"))
            }
        } catch (cancelled: CancellationException) {
            // Cooperative cancellation is not a failure to report. `runCatching`/`recoverCatching`
            // would have caught this (CancellationException is an Exception) and converted it into a
            // Result.failure, so a cancelled ask would look like a transport error — and the caller
            // holds the question as "offline" instead of unwinding. The phone side already rethrows
            // it (ReflectionCaller, ReflectionWorker); this brings the watch in line.
            throw cancelled
        } catch (error: WearChatException) {
            // Already the user-safe shape. Returned as a failure, not thrown: `ask`'s contract is
            // "never throws", and the caller does `result.fold(...)` — the first version threw, so
            // every failure crashed straight past the offline-queue path instead of holding the
            // question. The specific message ("HTTP 401") is kept rather than replaced by a class
            // name, which tells the user nothing.
            Result.failure(error)
        } catch (error: Throwable) {
            // Anything else (ConnectException, SocketTimeoutException, JSON errors) is summarised by
            // class name on purpose: those messages carry the full URL, and this string is rendered
            // on the watch screen.
            Result.failure(WearChatException(error.javaClass.simpleName))
        }
    }

    /** OpenAI-compatible chat-completions path, appended to the user's base URL. */
    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun parseContent(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first["message"] as? JsonObject
        val content = (message?.get("content") ?: first["text"]) as? JsonPrimitive ?: return null
        return runCatching { content.content }.getOrNull()?.takeIf { it.isNotBlank() }
    }

}

/** A watch-side failure that is safe to show the user: no URL, no key, no response body. */
class WearChatException(message: String) : Exception(message)

/** Logs a watch-side problem without ever touching the credential. */
internal object WearLog {
    private const val TAG = "HermesWear"

    fun w(message: String) = Log.w(TAG, message)
    fun e(message: String, error: Throwable? = null) =
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
}
