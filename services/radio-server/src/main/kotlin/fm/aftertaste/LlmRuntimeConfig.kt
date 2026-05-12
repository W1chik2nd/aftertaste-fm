package fm.aftertaste

enum class LlmUseCase {
    Planner,
    Chat
}

enum class LlmApiProvider(
    val displayName: String,
    val defaultBaseUrl: String
) {
    OpenAiResponses("openai-responses", "https://api.openai.com/v1"),
    OpenAiCompatible("openai-compatible", "https://api.openai.com/v1"),
    Anthropic("anthropic", "https://api.anthropic.com")
}

data class LlmRuntimeConfig(
    val provider: LlmApiProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String
) {
    val displayName: String = "${provider.displayName}/$model"

    fun endpoint(path: String): String = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    companion object {
        fun fromEnvironment(useCase: LlmUseCase): LlmRuntimeConfig? {
            val provider = resolveProvider()
            val apiKey = when (provider) {
                LlmApiProvider.Anthropic -> Env.value("LLM_API_KEY") ?: Env.value("ANTHROPIC_API_KEY")
                else -> Env.value("LLM_API_KEY")
            } ?: return null
            val model = when (useCase) {
                LlmUseCase.Chat -> Env.value("LLM_CHAT_MODEL")
                    ?: Env.value("LLM_MODEL")
                    ?: defaultModel(provider)
                LlmUseCase.Planner -> Env.value("LLM_MODEL") ?: defaultModel(provider)
            }
            val baseUrl = Env.value("LLM_BASE_URL") ?: provider.defaultBaseUrl
            return LlmRuntimeConfig(provider, apiKey, model, baseUrl)
        }

        private fun resolveProvider(): LlmApiProvider {
            val providerName = Env.value("LLM_PROVIDER")?.lowercase()
            return when (providerName) {
                "openai", "openai-responses", "responses" -> LlmApiProvider.OpenAiResponses
                "compatible", "openai-compatible", "chat-completions",
                "minimax", "deepseek", "qwen", "moonshot" -> LlmApiProvider.OpenAiCompatible
                "anthropic", "claude" -> LlmApiProvider.Anthropic
                else -> when {
                    Env.value("ANTHROPIC_API_KEY") != null && Env.value("LLM_API_KEY") == null -> LlmApiProvider.Anthropic
                    Env.value("LLM_BASE_URL") != null -> LlmApiProvider.OpenAiCompatible
                    else -> LlmApiProvider.OpenAiResponses
                }
            }
        }

        private fun defaultModel(provider: LlmApiProvider): String =
            when (provider) {
                LlmApiProvider.Anthropic -> "claude-3-5-haiku-latest"
                else -> "gpt-5.2"
            }
    }
}
