package com.newoether.agora.autopilot

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ProviderRegistry
import kotlinx.coroutines.CancellationException

/**
 * One reflection request against the cheapest configured model.
 *
 * Reuses Agora's provider stack (`ProviderRegistry` + `LlmProvider.generateResponse`) exactly like
 * the existing title generator — no second Provider call path, no custom HTTP client (N4).
 *
 * Failure is always silent: the caller gets null and logs, never a user-visible error.
 */
class ReflectionCaller(
    private val settings: SettingsRepository,
    private val providers: ProviderRegistry,
    /** Cheap model override; falls back to Agora's title-generation model, then the selected model. */
    private val configuredModel: () -> String? = { null },
) {
    /** Resolves the model id used for reflection, or null when nothing is configured. */
    fun resolveModelId(): String? {
        val candidates = sequenceOf(
            configuredModel(),
            settings.titleGenerationModel.value,
            settings.selectedModel.value,
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }
    }

    /**
     * Runs one reflection pass.
     *
     * @return the raw model reply, or null on any failure (unconfigured provider, transport error,
     *   provider error event). Never throws except on cancellation.
     */
    suspend fun reflect(transcript: String, existingFiles: List<String>): String? {
        val modelId = resolveModelId() ?: return null
        val providerName = providers.providerForModel(modelId)
        val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
            ?: settings.resolveActiveKey(providerName).orEmpty()
        if (!providers.isConfigured(providerName, activeKey)) {
            DebugLog.w(TAG, "reflection skipped: provider not configured ($providerName)")
            return null
        }
        val provider: LlmProvider = providers.getInstanceOrNull(providerName) ?: return null
        val config = ProviderConfig(
            apiKey = activeKey,
            modelId = ModelId.parse(providers.canonicalModelId(modelId)).modelName,
            systemPrompt = null,
            maxContextWindow = ContextBudget.MIN_TOKENS,
            thinkingEnabled = false,
            baseUrl = providers.getEffectiveBaseUrl(providerName),
        )
        val prompt = listOf(
            ChatMessage(
                text = ReflectionProtocol.extractionPrompt(transcript, existingFiles),
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
            )
        )

        val reply = StringBuilder()
        var providerError: String? = null
        try {
            HttpClient.withStreamScope(scope = null, requestTrace = null) {
                provider.generateResponse(prompt, config).collect { event ->
                    when (event) {
                        is StreamEvent.TextChunk -> reply.append(event.text)
                        is StreamEvent.Error -> providerError = event.message
                        else -> Unit
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.e(TAG, "reflection call failed for provider=$providerName", error)
            return null
        }
        providerError?.let {
            DebugLog.w(TAG, "reflection provider error: $it")
            return null
        }
        return reply.toString().ifBlank { null }
    }

    private companion object {
        const val TAG = "AutopilotReflection"
    }
}
