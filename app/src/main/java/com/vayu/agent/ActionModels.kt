package com.vayu.agent

// ─── Agent Action (returned by AI / sent via MCP) ───
data class AgentAction(
    val action: String = "FAIL",
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val text: String = "",
    val direction: String = "",
    val app: String = "",
    val reason: String = "",
    val delay: Long = 0L
)

// ─── Sequence Action (for batch commands) ───
data class SequenceAction(
    val action: String,       // TAP, SWIPE, TYPE, LONG_PRESS, PRESS_BACK, PRESS_HOME, PRESS_RECENTS, WAIT
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val text: String = "",
    val app: String = "",
    val delay: Long = 0L,     // Override delay after this action (ms)
    val duration: Long = 0L   // Swipe/gesture duration (ms)
) {
    fun toAgentAction(): AgentAction = AgentAction(
        action = action, x = x, y = y, x2 = x2, y2 = y2,
        text = text, app = app, delay = delay
    )
}

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

// ─── MCP Command State (for PiP overlay) ───
data class McpCommandState(
    val isExecuting: Boolean = false,
    val currentCommand: String = "",
    val commandCount: Int = 0,
    val lastAction: String = ""
)
