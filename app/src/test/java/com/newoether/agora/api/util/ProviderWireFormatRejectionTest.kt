package com.newoether.agora.api.util

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiFunctionCall
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiRequestFunction
import com.newoether.agora.api.OpenAiRequestToolCall
import com.newoether.agora.api.anthropic.AnthropicContentPart
import com.newoether.agora.api.anthropic.AnthropicMessage
import com.newoether.agora.api.anthropic.AnthropicRequest
import com.newoether.agora.api.anthropic.requireValidWireFormat
import com.newoether.agora.api.gemini.ApiGenerateContentRequest
import com.newoether.agora.api.gemini.ApiRequestContent
import com.newoether.agora.api.gemini.ApiRequestPart
import com.newoether.agora.api.gemini.requireValidWireFormat
import com.newoether.agora.api.openai.requireValidWireFormat
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The *negative* half of `ProviderContinuationRequestValidatorTest`.
 *
 * The audit (Session 4) found that file — which exists upstream and is untouched — calls
 * `requireValidWireFormat(...)` five times and asserts nothing. Green therefore means only "did
 * not throw": no-op the validator (`if (violations.isNotEmpty())` deleted) and every one of its
 * tests still passes. It guards the happy path by accident, and nothing at all by intent.
 *
 * This file lives under the fork's own test tree (`api/util/`, where the production validator
 * lives) and adds what was missing: a request that **must** be refused, per provider. With these,
 * removing the throw turns them red — verified by running them against a mutated validator, not
 * assumed.
 *
 * Why not fix the upstream file in place: it is an upstream-owned test, and editing it would need
 * a touchpoint entry and a budget for a file whose five tests are still correct as far as they go.
 * Adding coverage beside it costs nothing upstream.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
class ProviderWireFormatRejectionTest {

    private val emptyObject = JsonObject(emptyMap())

    private fun text(value: String) = OpenAiContentPart(type = "text", text = value)

    @Test
    fun `an openai request with a blank model is refused`() {
        // The exact shape the positive test uses, with one field broken. If `requireValidWireFormat`
        // stops throwing, this test fails instead of the app opening a request the server will 400.
        val request = OpenAiChatRequest(
            model = " ",
            messages = listOf(OpenAiMessage("user", content = listOf(text("hello")))),
        )
        assertRefused("OpenAI") { request.requireValidWireFormat("DeepSeek") }
    }

    @Test
    fun `an openai tool result that names no pending call is refused`() {
        // A `tool` message whose toolCallId matches nothing: the wire grammar for every
        // OpenAI-compatible provider requires it to close a pending assistant tool call. This is
        // the class of request the original file was written to keep legal, so its illegal mirror
        // is the assertion that file is missing.
        val request = OpenAiChatRequest(
            model = "deepseek-chat",
            messages = listOf(
                OpenAiMessage("user", content = listOf(text("start"))),
                OpenAiMessage("tool", content = listOf(text("result")), toolCallId = "call_never_declared"),
            ),
        )
        assertRefused("OpenAI") { request.requireValidWireFormat("DeepSeek") }
    }

    @Test
    fun `an openai assistant message carrying a tool call id is refused`() {
        val request = OpenAiChatRequest(
            model = "deepseek-chat",
            messages = listOf(
                OpenAiMessage("user", content = listOf(text("start"))),
                OpenAiMessage(
                    "assistant",
                    content = listOf(text("answer")),
                    toolCallId = "call_1",
                ),
            ),
        )
        assertRefused("OpenAI") { request.requireValidWireFormat("DeepSeek") }
    }

    @Test
    fun `an anthropic tool result without a matching tool use is refused`() {
        val request = AnthropicRequest(
            model = "claude-sonnet-5",
            messages = listOf(
                AnthropicMessage("user", listOf(AnthropicContentPart("text", text = "start"))),
                AnthropicMessage(
                    "user",
                    listOf(
                        AnthropicContentPart(
                            type = "tool_result",
                            toolUseId = "call_never_declared",
                            content = "result",
                        )
                    ),
                ),
            ),
        )
        assertRefused("Anthropic") { request.requireValidWireFormat() }
    }

    @Test
    fun `an anthropic request with no messages is refused`() {
        val request = AnthropicRequest(model = "claude-sonnet-5", messages = emptyList())
        assertRefused("Anthropic") { request.requireValidWireFormat() }
    }

    @Test
    fun `a gemini request with a blank model is refused`() {
        val request = ApiGenerateContentRequest(
            contents = listOf(ApiRequestContent("user", listOf(ApiRequestPart(text = "hello")))),
        )
        assertRefused("Gemini") { request.requireValidWireFormat(" ") }
    }

    @Test
    fun `the refusal carries the provider and at least one reason`() {
        // The exception is the diagnostic: a bare "failed" would send the next reader back to the
        // code to find out what was wrong. Asserted separately so a regression that keeps throwing
        // but loses the reasons is also caught.
        val request = OpenAiChatRequest(
            model = " ",
            messages = emptyList(),
        )
        val error = runCatching { request.requireValidWireFormat("DeepSeek") }.exceptionOrNull()
        assertTrue(
            "expected a RequestFormatException, got ${error?.javaClass?.name}",
            error is RequestFormatException,
        )
        val typed = error as RequestFormatException
        assertTrue("the provider name must be carried", typed.provider.isNotBlank())
        assertTrue("at least one reason must be carried", typed.violations.isNotEmpty())
    }

    @Test
    fun `an openai request with an undefined required tool property is refused`() {
        // validateToolDefinitions is reached through the same entry point; a tool that requires a
        // property it does not define is a request the server rejects, so it must be caught locally.
        val request = OpenAiChatRequest(
            model = "deepseek-chat",
            messages = listOf(OpenAiMessage("user", content = listOf(text("hi")))),
            tools = listOf(
                com.newoether.agora.api.ToolDefinition(
                    type = "function",
                    function = com.newoether.agora.api.ToolFunction(
                        name = "file_read",
                        description = "reads a file",
                        parameters = com.newoether.agora.api.ToolParameters(
                            type = "object",
                            properties = emptyMap(),
                            required = listOf("path"),
                        ),
                    ),
                ),
            ),
        )
        assertRefused("OpenAI") { request.requireValidWireFormat("DeepSeek") }
    }

    private fun assertRefused(provider: String, block: () -> Unit) {
        try {
            block()
            fail("$provider accepted a request its own wire-format validator must refuse")
        } catch (expected: RequestFormatException) {
            // The validator reports the provider name it was *given* (e.g. "DeepSeek" for an
            // OpenAI-shaped request), so assert it is non-blank rather than matching the family
            // name — the reasons are what make the exception useful, and the dedicated test above
            // pins those.
            assertTrue("the refusal must name a provider", expected.provider.isNotBlank())
            assertTrue("the refusal must carry reasons", expected.violations.isNotEmpty())
        }
    }
}
