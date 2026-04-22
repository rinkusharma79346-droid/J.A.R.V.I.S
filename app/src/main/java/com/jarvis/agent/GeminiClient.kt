package com.jarvis.agent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GeminiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private const val SYSTEM_PROMPT = """
You are JARVIS, an autonomous Android agent. 
You will be provided with the current user task, the step number, the screen UI tree, and a screenshot.
Determine the next single action to accomplish the task.
Output ONLY a JSON object matching this exact format:
{ "action": "TAP", "x": 540, "y": 960, "x2": 0, "y2": 0, "text": "", "direction": "", "app": "", "reason": "tapping the YouTube icon" }

Valid actions: TAP, SWIPE, TYPE, SCROLL, OPEN_APP, PRESS_BACK, PRESS_HOME, DONE, FAIL.
- For TAP: provide x, y
- For SWIPE/SCROLL: provide x, y (start) and x2, y2 (end)
- For TYPE: provide x, y (to click first) and text (to type)
- For OPEN_APP: provide app (name of the app)
- For DONE/FAIL/PRESS_BACK/PRESS_HOME: only the action and reason is needed
"""

    fun getNextAction(task: String, step: Int, uiTree: String, base64Screenshot: String): AgentAction {
        val jsonPayload = JsonObject().apply {
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", SYSTEM_PROMPT) })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("responseMimeType", "application/json")
            })
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", "Task: $task\nStep: $step\nUI Tree:\n$uiTree")
                        })
                        add(JsonObject().apply {
                            add("inlineData", JsonObject().apply {
                                addProperty("mimeType", "image/png")
                                addProperty("data", base64Screenshot)
                            })
                        })
                    })
                })
            })
        }

        val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${BuildConfig.GEMINI_API_KEY}"
        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return AgentAction(action = "FAIL", reason = "HTTP ${response.code}")
                val respString = response.body?.string() ?: return AgentAction(action = "FAIL")
                val root = gson.fromJson(respString, JsonObject::class.java)
                val textResponse = root.getAsJsonArray("candidates")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                    ?.get(0)?.asJsonObject
                    ?.get("text")?.asString ?: return AgentAction(action = "FAIL")
                
                return gson.fromJson(textResponse, AgentAction::class.java)
            }
        } catch (e: Exception) {
            return AgentAction(action = "FAIL", reason = e.message.toString())
        }
    }
}
