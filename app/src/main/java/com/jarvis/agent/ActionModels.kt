package com.jarvis.agent

// ─── Agent Action (returned by AI) ───
data class AgentAction(
    val action: String = "FAIL",
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val text: String = "",
    val direction: String = "",
    val app: String = "",
    val reason: String = ""
)

// ─── Agent Request (sent to AI provider) ───
data class AgentRequest(
    val task: String,
    val step: Int,
    val uiTree: String,
    val base64Screenshot: String,
    val screenshotMime: String = "image/jpeg",
    val history: List<HistoryEntry> = emptyList()
)

// ─── History Entry (agent memory) ───
data class HistoryEntry(
    val step: Int,
    val action: String,
    val reason: String,
    val observation: String = ""
) {
    fun toPromptString(): String = "Step $step: $action — $reason${if (observation.isNotBlank()) " → $observation" else ""}"
}

// ─── API Validation Result ───
data class ValidationResult(
    val success: Boolean,
    val message: String,
    val detectedProvider: String? = null
)

// ─── Provider Config ───
data class ProviderConfig(
    val provider: String = "gemini",
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val supportsVision: Boolean = true
)
