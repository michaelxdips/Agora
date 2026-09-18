package com.newoether.agora.autopilot

import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ProviderRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `getEffectiveBaseUrl` must return the URL a request would actually use.
 *
 * HERMES INTEGRATION POINT: this is the regression test for a defect found while auditing the
 * phone→watch push. The method used to return **null for every built-in provider with no explicitly
 * configured base URL** (`takeIf { !isBuiltIn(providerName) }`), on the assumption that the provider
 * object would supply its own default further down the stack. `GenerationRequestBuilder`,
 * `ConversationTitleGenerator` and `ReflectionCaller` do fall back that way — but any consumer that
 * asks *the registry* for a URL does not, and the watch push is one of them: a user chatting happily
 * with OpenAI or Anthropic was told "No base URL or model selected. Configure a provider first."
 * when they pressed Send to watch.
 *
 * The assertion is written against the provider's own `defaultBaseUrl` rather than a literal, so it
 * cannot drift when upstream changes a default.
 *
 * Lives under `autopilot/` (the fork-only test directory) because `ProviderRegistry.kt` is an
 * upstream file: the edit is a registered touchpoint and its test must not add a second upstream
 * file to the registry.
 */
class ProviderBaseUrlResolutionTest {

    private class Fixture(testScope: kotlinx.coroutines.test.TestScope) {
        val settings = mockk<SettingsRepository>()
        val conversations = mockk<ConversationRepository>()
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScope.testScheduler))
        val registry: ProviderRegistry

        init {
            every { settings.customProviders } returns MutableStateFlow<List<CustomProviderConfig>>(emptyList())
            every { settings.apiKeys } returns MutableStateFlow<List<ApiKeyEntry>>(emptyList())
            every { settings.activeApiKeyIds } returns MutableStateFlow(emptyMap())
            // The exact state the bug needed: nothing configured, so the built-in's own default is the
            // only possible answer.
            every { settings.providerBaseUrls } returns MutableStateFlow(emptyMap())
            registry = ProviderRegistry(
                settings = settings,
                conversations = conversations,
                localProvider = mockk<LocalProvider>(),
                scope = scope,
            )
        }

        fun close() = scope.cancel()
    }

    @Test
    fun `a built-in provider with nothing configured resolves to its own default base URL`() = runTest {
        val fixture = Fixture(this)
        val resolved = fixture.registry.getEffectiveBaseUrl(Constants.PROVIDER_OPENAI)

        assertEquals(
            "an unconfigured built-in must resolve to the same URL its provider would use, not null",
            fixture.registry.getInstance(Constants.PROVIDER_OPENAI).defaultBaseUrl,
            resolved,
        )
        fixture.close()
    }

    @Test
    fun `every built-in chat provider resolves to a non-blank base URL when nothing is configured`() = runTest {
        val fixture = Fixture(this)
        // Ollama's default is loopback and the local provider has none, so both are excluded by name:
        // the claim under test is "a remote built-in is never silently URL-less", not "every string in
        // the map is https".
        val remoteBuiltIns = listOf(
            Constants.PROVIDER_OPENAI,
            Constants.PROVIDER_ANTHROPIC,
            Constants.PROVIDER_GOOGLE,
            Constants.PROVIDER_DEEPSEEK,
            Constants.PROVIDER_QWEN,
            Constants.PROVIDER_GROQ,
            Constants.PROVIDER_OPEN_ROUTER,
            Constants.PROVIDER_OPENCODE_GO,
        )
        remoteBuiltIns.forEach { name ->
            val resolved = fixture.registry.getEffectiveBaseUrl(name)
            assertEquals(
                "$name must resolve to its provider default, not null — a null here is what made the " +
                    "watch push report 'No base URL or model selected' for a working provider",
                fixture.registry.getInstance(name).defaultBaseUrl,
                resolved,
            )
        }
        fixture.close()
    }

    @Test
    fun `a configured base URL still wins over the provider default`() = runTest {
        val fixture = Fixture(this)
        every { fixture.settings.providerBaseUrls } returns
            MutableStateFlow(mapOf(Constants.PROVIDER_OPENAI to "https://gateway.example/v1"))

        assertEquals(
            "the user's own URL must never be replaced by the built-in default",
            "https://gateway.example/v1",
            fixture.registry.getEffectiveBaseUrl(Constants.PROVIDER_OPENAI),
        )
        fixture.close()
    }

    @Test
    fun `an unknown provider name is still null rather than a fabricated URL`() = runTest {
        val fixture = Fixture(this)
        assertNull(
            "an unregistered provider must not invent a base URL",
            fixture.registry.getEffectiveBaseUrl("not-a-provider"),
        )
        fixture.close()
    }
}
