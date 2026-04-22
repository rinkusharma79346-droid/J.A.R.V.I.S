package com.jarvis.agent
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
