package fm.aftertaste

object IntegrationStatuses {
    fun current(): List<IntegrationStatus> =
        listOf(
            IntegrationStatus("llm", "LLM", Env.value("LLM_API_KEY") != null || Env.value("ANTHROPIC_API_KEY") != null),
            IntegrationStatus("fish", "Fish TTS", Env.value("FISH_API_KEY") != null),
            IntegrationStatus("fish-zh", "Fish TTS (Chinese)", Env.value("FISH_API_KEY_ZH") != null || Env.value("FISH_API_KEY") != null),
            IntegrationStatus("netease", "Netease cookie", Env.value("NETEASE_COOKIE") != null),
            IntegrationStatus("openweather", "OpenWeather", Env.value("OPENWEATHER_API_KEY") != null)
        )
}
