package com.newoether.agora.wear

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
     *
     * `callTimeout` bounds the **whole** call. `connectTimeout`/`readTimeout` bound one socket
     * operation each, so a server that accepts the connection and then dribbles one byte per read
     * timeout keeps the call alive for as long as it likes — and this call runs on a watch, on a
     * screen the user is staring at, with a queue behind it. The ceiling is what makes "the answer
     * never came" a state the UI can reach.
     */
    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

        /**
         * Ceiling on a response body; a watch cannot afford to buffer an unbounded one.
         *
         * Lowered from 1 MiB: a non-streaming chat completion that is larger than 256 KiB is already
         * unusable on a 384 px screen, and 1 MiB of UTF-16 in the buffer is ~2 MB of heap on a device
         * whose whole app budget is a few hundred MB. A body that big is a misconfiguration or a
         * hostile gateway, and both deserve a failure rather than an allocation.
         */
        const val MAX_RESPONSE_BYTES = 256 shl 10   // 256 KiB
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Sends one question with [coreContext] as the system prompt.
     *
     * @return the assistant text, or a [WearChatException] describing exactly what went wrong. Never
     *   throws except on cancellation: the caller is a watch UI that must show *something*.
     */
    fun ask(question: String, coreContext: String): Result<String> {
        if (!config.isValid()) {
            return Result.failure(WearChatException("not configured", WearChatException.Kind.NOT_CONFIGURED))
        }
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
        val endpoint = endpoint(config.baseUrl)
            ?: return Result.failure(
                WearChatException("bad base URL", WearChatException.Kind.NOT_CONFIGURED)
            )
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                // Status first, body second. The previous order read (and buffered) the whole body
                // before looking at the code, so a 401 page with a large HTML body was read into
                // memory on a watch only to be thrown away — and the failure the user saw was
                // whichever of "too large" / "HTTP 401" the buffer hit first.
                if (!response.isSuccessful) {
                    // Status and a bounded hint only — the body can echo the key in a proxy error page.
                    throw WearChatException(
                        message = "HTTP ${response.code}",
                        kind = WearChatException.Kind.HTTP_STATUS,
                        httpStatus = response.code,
                        retryAfterMs = response.header("Retry-After")?.let { parseRetryAfter(it) },
                    )
                }
                val text = readBounded(response)
                Result.success(parseContent(text) ?: throw WearChatException(
                    "unreadable response", WearChatException.Kind.PARSE,
                ))
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
        } catch (error: Exception) {
            // `Exception`, not `Throwable`. Catching `Throwable` also swallows `OutOfMemoryError`
            // and `StackOverflowError` — conditions under which the honest behaviour is to let the
            // process die, not to hand the user a "question held offline" for a heap that is gone.
            // Anything here (ConnectException, SocketTimeoutException, JSON errors) is summarised by
            // class name on purpose: those messages carry the full URL, and this string is rendered
            // on the watch screen.
            Result.failure(
                WearChatException(error.javaClass.simpleName, WearChatException.Kind.TRANSPORT)
            )
        }
    }

    /**
     * Reads the response body with a hard ceiling.
     *
     * `response.body?.string()` has no bound: a provider (or a captive portal, or a misconfigured
     * gateway) that streams an endless or enormous body makes the watch allocate it all — on a 2 GB
     * device that is an OOM in the one place the user cannot recover from. The cap is generous for a
     * non-streaming chat completion and small enough to stay safe.
     */
    private fun readBounded(response: okhttp3.Response): String {
        val source = response.body.source()
        source.request(MAX_RESPONSE_BYTES.toLong() + 1)
        val buffer = source.buffer
        if (buffer.size > MAX_RESPONSE_BYTES) {
            throw WearChatException("response too large", WearChatException.Kind.TOO_LARGE)
        }
        return buffer.clone().readString(Charsets.UTF_8)
    }

    /**
     * `[OI]`-compatible chat-completions URL, built from the user's base URL.
     *
     * Three shapes are handled because all three are what a user actually types: `…/v1`,
     * `…/v1/chat/completions`, and the bare host. The bare host used to produce
     * `https://api.openai.com/chat/completions`, which 404s with no hint that a `/v1` was missing —
     * the watch just said "HTTP 404". A path of `/` or nothing is the only case where `/v1` is
     * assumed; anything else is taken as intentional (Ollama's `/v1`, a gateway's own prefix).
     *
     * Built through `HttpUrl`, not string concatenation. Concatenation is how a base URL with a query
     * string (`…/v1?key=…`, or a gateway's `?api-version=…`) turned into
     * `…/v1?key=…/chat/completions` — a URL that is not what the user configured and that fails with
     * a provider-side 404 rather than anything this app could explain. `HttpUrl` also rejects a
     * malformed base instead of handing OkHttp something that throws from `Request.Builder.url`.
     *
     * @return the absolute URL, or null when the base URL cannot be parsed at all.
     */
    private fun endpoint(baseUrl: String): String? {
        val parsed = baseUrl.trim().toHttpUrlOrNull() ?: return null
        // HERMES INTEGRATION POINT: `endsWith("/chat/completions")` missed a trailing slash, so
        // `https://host/v1/chat/completions/` fell through and three segments were appended again —
        // `…/chat/completions/chat/completions`, a 404 the user cannot explain from their own config.
        // Trailing slashes are trimmed first (the caller already trims spaces).
        val path = parsed.encodedPath.trimEnd('/')
        if (path.endsWith("/chat/completions")) return parsed.toString()
        val builder = parsed.newBuilder()
        if (path.isBlank()) builder.addPathSegment("v1")
        builder.addPathSegment("chat")
        builder.addPathSegment("completions")
        return builder.build().toString()
    }

    /**
     * `Retry-After`, in milliseconds. Seconds-form only (the HTTP-date form is vanishingly rare for
     * an API and would need a parser on a watch); an absent or unparseable header is null, which the
     * caller reads as "no server-side hint".
     */
    private fun parseRetryAfter(value: String): Long? =
        value.trim().toLongOrNull()?.takeIf { it >= 0 }?.times(1_000L)

    /**
     * Extracts the assistant text from an OpenAI-compatible response.
     *
     * Three shapes are accepted: the current `choices[0].message.content`, the **content-part array**
     * (`content: [{type: "text", text: "…"}]`, which is what an OpenAI-compatible gateway returns
     * when it has been asked for anything multimodal-shaped, and which used to fail here as
     * "unreadable response" for an answer that was present and correct), and the legacy
     * `choices[0].text`. Anything else is null, which `ask` turns into "unreadable response".
     *
     * **The `is JsonNull` checks are not defensive noise — they fix a bug found on the device.**
     * `JsonNull` IS a `JsonPrimitive` in kotlinx.serialization, so the previous
     * `... as? JsonPrimitive` cast accepted a JSON `null`, `.content` returned the four characters
     * `null`, and `takeIf { it.isNotBlank() }` let them through because "null" is not blank. A user
     * whose provider answered `content: null` was shown the word "null" as the answer. A wrong
     * answer is worse than an honest failure, so a JSON null must fail here.
     *
     * **The answer is rendered before it is returned.** This is the one place provider text becomes
     * watch text: a model that answers in Markdown or LaTeX (`**Jawaban:**`, `$\frac{1}{2}$`) used to
     * put the markup itself on a 384 px screen, which has no renderer for either. [WearAnswerText]
     * does the conversion with no dependency; see it for the mapping and its limits.
     */
    private fun parseContent(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first["message"] as? JsonObject
        val candidate = message?.get("content") ?: first["text"] ?: return null
        val text = primitiveText(candidate) ?: return null
        return WearAnswerText.render(text).takeIf { it.isNotBlank() }
    }

    /**
     * The text of a `content` field, in either the string form or the content-part array form.
     *
     * A JSON null anywhere in the structure is null (never the string "null"), and a part whose type
     * is not text (an image part) is skipped rather than stringified — a part list with no text part
     * at all is an unreadable response, which is the truth about it.
     */
    private fun primitiveText(candidate: kotlinx.serialization.json.JsonElement): String? {
        if (candidate is JsonNull) return null
        (candidate as? JsonPrimitive)?.let { primitive ->
            if (primitive is JsonNull) return null
            return runCatching { primitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        val parts = candidate as? JsonArray ?: return null
        val joined = parts.mapNotNull { part ->
            val object_ = part as? JsonObject ?: return@mapNotNull null
            val type = (object_["type"] as? JsonPrimitive)?.content
            if (type != null && type != "text") return@mapNotNull null
            primitiveText(object_["text"] ?: return@mapNotNull null)
        }.joinToString("\n").trim()
        return joined.takeIf { it.isNotBlank() }
    }

}

/**
 * A watch-side failure that is safe to show the user: no URL, no key, no response body.
 *
 * [kind] exists so the queue can tell a **permanent** failure from a **temporary** one. Before it,
 * every failure was the same shape, and the queue spent three attempts and then silently deleted the
 * question — so a revoked API key (HTTP 401, which will never succeed) and a train tunnel (which
 * will) were indistinguishable to the code that decides whether to keep trying.
 */
class WearChatException(
    message: String,
    val kind: Kind = Kind.TRANSPORT,
    val httpStatus: Int? = null,
    /** Server-provided back-off (429/503 with `Retry-After`), in ms. Null = no hint. */
    val retryAfterMs: Long? = null,
) : Exception(message) {

    enum class Kind {
        /** No usable config on the watch: nothing to send to. */
        NOT_CONFIGURED,

        /** The provider answered with a non-2xx status. */
        HTTP_STATUS,

        /** The answer arrived but this build could not read it. */
        PARSE,

        /** The body exceeded the watch's buffer ceiling. */
        TOO_LARGE,

        /** Connect/read/TLS failure: the request never got an answer. */
        TRANSPORT,
    }

    /**
     * True when sending the **same** question again can plausibly succeed.
     *
     * A revoked key, a 404 (the endpoint does not exist), an unreadable body and an oversized body are
     * all permanent: retrying them burns the queue's attempt budget and ends in the same failure. A
     * transport error, a timeout, a 408 and any 5xx are temporary. 429 is temporary *with* the
     * server's own back-off attached.
     */
    val retryable: Boolean
        get() = when (kind) {
            Kind.NOT_CONFIGURED, Kind.PARSE, Kind.TOO_LARGE -> false
            Kind.TRANSPORT -> true
            Kind.HTTP_STATUS -> httpStatus == 408 || httpStatus == 429 || (httpStatus ?: 0) >= 500
        }
}

/** Logs a watch-side problem without ever touching the credential. */
internal object WearLog {
    private const val TAG = "HermesWear"

    fun w(message: String) = Log.w(TAG, message)
    fun e(message: String, error: Throwable? = null) =
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
}
