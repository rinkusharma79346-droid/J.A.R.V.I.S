package com.jarvis.agent

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val TAG = "GeminiClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private const val SYSTEM_PROMPT = """You are JARVIS, an autonomous Android agent.
You will be provided with the current user task, the step number, the screen UI tree, and a screenshot.
Determine the next single action to accomplish the task.
Output ONLY a JSON object matching this exact format:
{ "action": "TAP", "x": 540, "y": 960, "x2": 0, "y2": 0, "text": "", "direction": "", "app": "", "reason": "tapping the YouTube icon" }

Valid actions: TAP, SWIPE, TYPE, SCROLL, OPEN_APP, PRESS_BACK, PRESS_HOME, DONE, FAIL.
- For TAP: provide x, y
- For SWIPE/SCROLL: provide x, y (start) and x2, y2 (end)
- For TYPE: provide x, y (to click first) and text (to type)
- For OPEN_APP: provide app (name of the app or package name)
- For DONE/FAIL/PRESS_BACK/PRESS_HOME: only the action and reason is needed

STRATEGY:
1. Analyze the screenshot and UI tree to understand the current screen state
2. Find the element that moves you toward the task goal
3. If element is not visible, SCROLL to find it
4. If stuck, PRESS_BACK and try a different path
5. Be efficient — minimize steps. Don't repeat actions."""

    fun getNextAction(task: String, step: Int, uiTree: String, base64Screenshot: String): AgentAction {
        val jsonPayload = JsonObject().apply {
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", SYSTEM_PROMPT) })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("responseMimeType", "application/json")
                addProperty("temperature", 0.2)
                addProperty("maxOutputTokens", 512)
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
                                addProperty("mimeType", "image/jpeg")
                                addProperty("data", base64Screenshot)
                            })
                        })
                    })
                })
            })
        }

        val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
        val apiKey = BuildConfig.GEMINI_API_KEY
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

        Log.d(TAG, "Calling Gemini API (key=${apiKey.take(8)}... screenshot=${base64Screenshot.length} chars)")

        val request = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(200) ?: "no body"
                    Log.e(TAG, "Gemini API error: HTTP ${response.code} — $errBody")
                    return AgentAction(action = "FAIL", reason = "HTTP ${response.code}: $errBody")
                }
                val respString = response.body?.string() ?: return AgentAction(action = "FAIL", reason = "Empty response")
                Log.d(TAG, "Gemini response: ${respString.take(300)}")

                val root = gson.fromJson(respString, JsonObject::class.java)
                val candidates = root.getAsJsonArray("candidates")
                if (candidates == null || candidates.size() == 0) {
                    return AgentAction(action = "FAIL", reason = "No candidates in response")
                }

                val textResponse = candidates[0].asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                    ?.get(0)?.asJsonObject
                    ?.get("text")?.asString
                    ?: return AgentAction(action = "FAIL", reason = "No text in response")

                // Clean up markdown code fences if present
                var cleanText = textResponse.trim()
                if (cleanText.startsWith("```")) {
                    val lines = cleanText.split("\n")
                    cleanText = lines.drop(1).dropLast(1).joinToString("\n")
                }

                return try {
                    gson.fromJson(cleanText, AgentAction::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse action JSON: $cleanText")
                    AgentAction(action = "FAIL", reason = "JSON parse error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call failed: ${e.message}")
            return AgentAction(action = "FAIL", reason = e.message.toString())
        }
    }
}
