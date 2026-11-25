package com.example.data.model

enum class ModelProvider(val displayName: String, val iconColor: Long) {
    GEMINI("Google Gemini", 0xFF2563EB),
    FLOW("Google Flow (Veo)", 0xFF8B5CF6),
    IMAGEN("Imagen Studio", 0xFF06B6D4),
    VOICE("Gemini Live Voice", 0xFF10B981)
}

data class ModelInfo(
    val id: String,
    val name: String,
    val category: ModelCategory,
    val description: String,
    val isThinkingModel: Boolean = false,
    val badge: String? = null
) {
    val provider: ModelProvider get() = when (category) {
        ModelCategory.FLOW -> ModelProvider.FLOW
        ModelCategory.IMAGE_STUDIO -> ModelProvider.IMAGEN
        ModelCategory.VOICE_AUDIO -> ModelProvider.VOICE
        else -> ModelProvider.GEMINI
    }
}

enum class ModelCategory(val displayName: String, val badgeColor: Long) {
    CHAT_CODING("Gemini Models", 0xFF2563EB),
    FLOW("Google Flow & Video", 0xFF8B5CF6),
    IMAGE_STUDIO("Imagen Studio", 0xFF06B6D4),
    VOICE_AUDIO("Live Voice", 0xFF10B981),
    THINKING("Gemini Thinking", 0xFF7C3AED),
    DEEP_RESEARCH("Gemini Research", 0xFF0284C7)
}

object GeminiModelsList {
    val DEFAULT_MODEL: ModelInfo get() = CHAT_CODING_MODELS.first()

    // Pure Gemini Models Only
    val CHAT_CODING_MODELS = listOf(
        ModelInfo(
            id = "gemini-4-hydrogen",
            name = "Gemini 4 Hydrogen",
            category = ModelCategory.CHAT_CODING,
            description = "",
            isThinkingModel = true,
            badge = "Flagship"
        ),
        ModelInfo(
            id = "gemini-4-carbon",
            name = "Gemini 4 Carbon",
            category = ModelCategory.CHAT_CODING,
            description = "",
            isThinkingModel = true,
            badge = "Pro Core"
        ),
        ModelInfo(
            id = "gemini-4-argon",
            name = "Gemini 4 Argon",
            category = ModelCategory.DEEP_RESEARCH,
            description = "",
            isThinkingModel = true,
            badge = "Research"
        ),
        ModelInfo(
            id = "gemini-3.8-flash",
            name = "Gemini 3.8 Flash",
            category = ModelCategory.CHAT_CODING,
            description = "",
            badge = "Fast"
        ),
        ModelInfo(
            id = "gemini-3.8-live-extended-thinking",
            name = "Gemini 3.8 Thinking",
            category = ModelCategory.THINKING,
            description = "",
            isThinkingModel = true,
            badge = "Thinking"
        ),
        ModelInfo(
            id = "gemini-3.6-flash",
            name = "Gemini 3.6 Flash",
            category = ModelCategory.CHAT_CODING,
            description = "",
            badge = "Flash"
        ),
        ModelInfo(
            id = "gemini-3.5-flash-lite",
            name = "Gemini 3.5 Flash-Lite",
            category = ModelCategory.CHAT_CODING,
            description = "",
            badge = "Ultra Fast"
        ),
        ModelInfo(
            id = "gemini-3.1-pro-preview",
            name = "Gemini 3.1 Pro",
            category = ModelCategory.CHAT_CODING,
            description = "",
            isThinkingModel = true,
            badge = "Pro"
        ),
        ModelInfo(
            id = "gemini-3-flash",
            name = "Gemini 3 Flash",
            category = ModelCategory.CHAT_CODING,
            description = "",
            badge = "Flash V3"
        ),
        ModelInfo(
            id = "gemini-2.5-pro",
            name = "Gemini 2.5 Pro",
            category = ModelCategory.CHAT_CODING,
            description = "",
            isThinkingModel = true,
            badge = "Pro 2.5"
        ),
        ModelInfo(
            id = "gemini-2.5-flash",
            name = "Gemini 2.5 Flash",
            category = ModelCategory.CHAT_CODING,
            description = "",
            badge = "Flash 2.5"
        ),
        ModelInfo(
            id = "gemini-2.5-flash-lite",
            name = "Gemini 2.5 Flash-Lite",
            category = ModelCategory.CHAT_CODING,
            description = "",
            badge = "Lite 2.5"
        ),
        ModelInfo(
            id = "gemini-2.0-flash",
            name = "Gemini 2.0 Flash",
            category = ModelCategory.CHAT_CODING,
            description = "",
            badge = "Bypass 2.0"
        )
    )

    val VIDEO_MODELS: List<ModelInfo> = emptyList()
    val IMAGE_MODELS: List<ModelInfo> = emptyList()
    val VOICE_AUDIO_MODELS: List<ModelInfo> = emptyList()

    val ALL_MODELS: List<ModelInfo> = CHAT_CODING_MODELS
    val GEMINI_MODELS: List<ModelInfo> get() = CHAT_CODING_MODELS
    val FLOW_MODELS: List<ModelInfo> get() = emptyList()
    val IMAGE_STUDIO_MODELS: List<ModelInfo> get() = emptyList()
    val VOICE_MODELS: List<ModelInfo> get() = emptyList()
}
