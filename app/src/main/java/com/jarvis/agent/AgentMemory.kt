package com.jarvis.agent

import android.content.SharedPreferences

class AgentMemory(private val prefs: SharedPreferences) {

    private val entries = mutableListOf<HistoryEntry>()
    private var currentTask: String = ""

    companion object {
        private const val MAX_HISTORY = 10
        private const val PREF_LAST_TASK = "last_task"
        private const val PREF_TASK_COUNT = "task_count"
    }

    fun startTask(task: String) {
        currentTask = task
        entries.clear()
        prefs.edit().putString(PREF_LAST_TASK, task).apply()
        incrementTaskCount()
    }

    fun record(step: Int, action: AgentAction, observation: String = "") {
        val entry = HistoryEntry(step = step, action = action.action, reason = action.reason, observation = observation)
        if (entries.size >= MAX_HISTORY) entries.removeAt(0)
        entries.add(entry)
    }

    fun getHistory(): List<HistoryEntry> = entries.toList()

    fun getHistoryPrompt(): String {
        if (entries.isEmpty()) return "No previous actions taken yet."
        return entries.joinToString("\n") { it.toPromptString() }
    }

    fun getCurrentTask(): String = currentTask
    fun getLastTask(): String = prefs.getString(PREF_LAST_TASK, "") ?: ""
    fun getTaskCount(): Int = prefs.getInt(PREF_TASK_COUNT, 0)

    private fun incrementTaskCount() {
        prefs.edit().putInt(PREF_TASK_COUNT, prefs.getInt(PREF_TASK_COUNT, 0) + 1).apply()
    }

    fun clear() { entries.clear(); currentTask = "" }
}
