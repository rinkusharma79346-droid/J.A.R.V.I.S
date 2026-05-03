package com.jarvis.agent

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

interface ApiProvider {
    val name: String
    suspend fun getNextAction(request: AgentRequest): AgentAction
    suspend fun validate(): ValidationResult
}

class GeminiProvider(private val apiKey: String, private val model: String = "gemini-2.0-flash") : ApiProvider {
    override val name = "Gemini"
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val gson = Gson()

    companion object { private const val TAG = "GeminiProvider"
        private const val SYSTEM_PROMPT = """You are V.A.Y.U, an autonomous Android agent — Vision-Assisted Yonder Unit.
You will be provided with the current user task, the step number, the screen UI tree, a screenshot, and your recent action history.
Determine the next SINGLE action to accomplish the task.
OUTPUT FORMAT — respond with ONLY a JSON object:
{ "action": "TAP", "x": 540, "y": 960, "x2": 0, "y2": 0, "text": "", "direction": "", "app": "", "reason": "tapping the YouTube icon" }
VALID ACTIONS: TAP, LONG_PRESS, SWIPE, TYPE, SCROLL, OPEN_APP, PRESS_BACK, PRESS_HOME, PRESS_RECENTS, DONE, FAIL
- TAP: provide x, y
- LONG_PRESS: provide x, y (press and hold 600ms)
- SWIPE/SCROLL: provide x, y (start) and x2, y2 (end)
- TYPE: provide x, y (click first) and text (to type)
- OPEN_APP: provide app (app name or package)
- DONE/FAIL/PRESS_BACK/PRESS_HOME/PRESS_RECENTS: only action + reason needed
STRATEGY:
1. Analyze the screenshot and UI tree to understand the current screen
2. Use the action history to AVOID repeating the same action
3. If the desired element is not visible, SCROLL to find it
4. If stuck for 2+ steps, PRESS_BACK and try a different path
5. Be efficient — minimize steps. Don't repeat failed actions.
6. When the task is clearly complete, respond with DONE."""
    }

    override suspend fun getNextAction(request: AgentRequest): AgentAction = withContext(Dispatchers.IO) {
        try {
            val historyText = if (request.history.isNotEmpty()) "\n\nACTION HISTORY (avoid repeating these):\n${request.history.joinToString("\n") { it.toPromptString() }}" else ""
            val payload = JsonObject().apply {
                add("systemInstruction", JsonObject().apply { add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", SYSTEM_PROMPT) }) }) })
                add("generationConfig", JsonObject().apply { addProperty("responseMimeType", "application/json"); addProperty("temperature", 0.2); addProperty("maxOutputTokens", 512) })
                add("contents", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("parts", JsonArray().apply {
                            add(JsonObject().apply { addProperty("text", "Task: ${request.task}\nStep: ${request.step}$historyText\n\nUI Tree:\n${request.uiTree}") })
                            if (request.base64Screenshot.isNotBlank()) add(JsonObject().apply { add("inlineData", JsonObject().apply { addProperty("mimeType", request.screenshotMime); addProperty("data", request.base64Screenshot) }) })
                        })
                    })
                })
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext AgentAction(action = "FAIL", reason = "HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                val respString = response.body?.string() ?: return@withContext AgentAction(action = "FAIL", reason = "Empty response")
                val root = gson.fromJson(respString, JsonObject::class.java)
                val candidates = root.getAsJsonArray("candidates") ?: return@withContext AgentAction(action = "FAIL", reason = "No candidates")
                var textResponse = candidates[0].asJsonObject?.getAsJsonObject("content")?.getAsJsonArray("parts")?.get(0)?.asJsonObject?.get("text")?.asString ?: return@withContext AgentAction(action = "FAIL", reason = "No text")
                textResponse = textResponse.trim(); if (textResponse.startsWith("```")) { val lines = textResponse.split("\n"); textResponse = lines.drop(1).dropLast(1).joinToString("\n") }
                return@withContext try { gson.fromJson(textResponse, AgentAction::class.java) } catch (e: Exception) { AgentAction(action = "FAIL", reason = "JSON parse: ${e.message}") }
            }
        } catch (e: Exception) { AgentAction(action = "FAIL", reason = "Network: ${e.message}") }
    }

    override suspend fun validate(): ValidationResult = withContext(Dispatchers.IO) {
        try { client.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey").get().build()).execute().use { if (it.isSuccessful) ValidationResult(true, "Gemini connected", "gemini") else ValidationResult(false, "Gemini error: ${it.body?.string()?.take(100)}", "gemini") } }
        catch (e: Exception) { ValidationResult(false, "Connection failed: ${e.message}", "gemini") }
    }
}

class OpenAiProvider(private val apiKey: String, private val model: String = "gpt-4o", private val baseUrl: String = "https://api.openai.com/v1") : ApiProvider {
    override val name = "OpenAI"
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val gson = Gson()

    companion object { private const val TAG = "OpenAiProvider"
        private const val SYSTEM_PROMPT = """You are V.A.Y.U, an autonomous Android agent — Vision-Assisted Yonder Unit.
Return ONLY a JSON object for the next action:
{ "action": "TAP", "x": 540, "y": 960, "x2": 0, "y2": 0, "text": "", "direction": "", "app": "", "reason": "tapping search bar" }
ACTIONS: TAP, LONG_PRESS, SWIPE, TYPE, SCROLL, OPEN_APP, PRESS_BACK, PRESS_HOME, PRESS_RECENTS, DONE, FAIL
Use history to avoid repeats. If stuck, go BACK and try differently."""
    }

    override suspend fun getNextAction(request: AgentRequest): AgentAction = withContext(Dispatchers.IO) {
        try {
            val historyText = if (request.history.isNotEmpty()) "\n\nACTION HISTORY:\n${request.history.joinToString("\n") { it.toPromptString() }}" else ""
            val userContent = "Task: ${request.task}\nStep: ${request.step}$historyText\n\nUI Tree:\n${request.uiTree}"
            val payload = JsonObject().apply {
                addProperty("model", model); addProperty("temperature", 0.2); addProperty("max_tokens", 512)
                add("messages", JsonArray().apply {
                    add(JsonObject().apply { addProperty("role", "system"); addProperty("content", SYSTEM_PROMPT) })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        if (request.base64Screenshot.isNotBlank()) add("content", JsonArray().apply {
                            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", userContent) })
                            add(JsonObject().apply { addProperty("type", "image_url"); add("image_url", JsonObject().apply { addProperty("url", "data:${request.screenshotMime};base64,${request.base64Screenshot}"); addProperty("detail", "low") }) })
                        }) else addProperty("content", userContent)
                    })
                })
            }
            client.newCall(Request.Builder().url("$baseUrl/chat/completions").header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json").post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext AgentAction(action = "FAIL", reason = "HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                val root = gson.fromJson(response.body?.string() ?: "", JsonObject::class.java)
                var textResponse = root.getAsJsonArray("choices")?.get(0)?.asJsonObject?.getAsJsonObject("message")?.get("content")?.asString ?: return@withContext AgentAction(action = "FAIL", reason = "No content")
                textResponse = textResponse.trim(); if (textResponse.startsWith("```")) { val lines = textResponse.split("\n"); textResponse = lines.drop(1).dropLast(1).joinToString("\n") }
                return@withContext try { gson.fromJson(textResponse, AgentAction::class.java) } catch (e: Exception) { AgentAction(action = "FAIL", reason = "JSON parse: ${e.message}") }
            }
        } catch (e: Exception) { AgentAction(action = "FAIL", reason = "Network: ${e.message}") }
    }

    override suspend fun validate(): ValidationResult = withContext(Dispatchers.IO) {
        try { client.newCall(Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").get().build()).execute().use { if (it.isSuccessful) ValidationResult(true, "OpenAI connected", "openai") else ValidationResult(false, "OpenAI error: ${it.code}", "openai") } }
        catch (e: Exception) { ValidationResult(false, "Connection failed: ${e.message}", "openai") }
    }
}

class NvidiaProvider(private val apiKey: String, private val model: String = "nvidia/llama-3.1-nemotron-70b-instruct") : ApiProvider {
    override val name = "NVIDIA"
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val gson = Gson()

    companion object { private const val TAG = "NvidiaProvider"; private const val BASE_URL = "https://integrate.api.nvidia.com/v1"
        private const val SYSTEM_PROMPT = """You are V.A.Y.U, an autonomous Android agent — Vision-Assisted Yonder Unit. No screenshot — rely on UI tree.
Return ONLY JSON: { "action": "TAP", "x": 540, "y": 960, "x2": 0, "y2": 0, "text": "", "direction": "", "app": "", "reason": "tapping button" }
ACTIONS: TAP, LONG_PRESS, SWIPE, TYPE, SCROLL, OPEN_APP, PRESS_BACK, PRESS_HOME, PRESS_RECENTS, DONE, FAIL. Use UI tree coordinates. Use history to avoid repeats."""
    }

    override suspend fun getNextAction(request: AgentRequest): AgentAction = withContext(Dispatchers.IO) {
        try {
            val historyText = if (request.history.isNotEmpty()) "\n\nACTION HISTORY:\n${request.history.joinToString("\n") { it.toPromptString() }}" else ""
            val userContent = "Task: ${request.task}\nStep: ${request.step}$historyText\n\nUI Tree:\n${request.uiTree}"
            val payload = JsonObject().apply {
                addProperty("model", model); addProperty("temperature", 0.2); addProperty("max_tokens", 512)
                add("messages", JsonArray().apply {
                    add(JsonObject().apply { addProperty("role", "system"); addProperty("content", SYSTEM_PROMPT) })
                    add(JsonObject().apply { addProperty("role", "user"); addProperty("content", userContent) })
                })
            }
            client.newCall(Request.Builder().url("$BASE_URL/chat/completions").header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json").post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext AgentAction(action = "FAIL", reason = "HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                val root = gson.fromJson(response.body?.string() ?: "", JsonObject::class.java)
                var textResponse = root.getAsJsonArray("choices")?.get(0)?.asJsonObject?.getAsJsonObject("message")?.get("content")?.asString ?: return@withContext AgentAction(action = "FAIL", reason = "No content")
                textResponse = textResponse.trim(); if (textResponse.startsWith("```")) { val lines = textResponse.split("\n"); textResponse = lines.drop(1).dropLast(1).joinToString("\n") }
                return@withContext try { gson.fromJson(textResponse, AgentAction::class.java) } catch (e: Exception) { AgentAction(action = "FAIL", reason = "JSON parse: ${e.message}") }
            }
        } catch (e: Exception) { AgentAction(action = "FAIL", reason = "Network: ${e.message}") }
    }

    override suspend fun validate(): ValidationResult = withContext(Dispatchers.IO) {
        try { client.newCall(Request.Builder().url("$BASE_URL/models").header("Authorization", "Bearer $apiKey").get().build()).execute().use { if (it.isSuccessful) ValidationResult(true, "NVIDIA connected", "nvidia") else ValidationResult(false, "NVIDIA error: ${it.code}", "nvidia") } }
        catch (e: Exception) { ValidationResult(false, "Connection failed: ${e.message}", "nvidia") }
    }
}

object ProviderFactory {
    fun create(config: ProviderConfig): ApiProvider = when (config.provider) {
        "gemini" -> GeminiProvider(config.apiKey, config.model.ifBlank { "gemini-2.0-flash" })
        "openai" -> OpenAiProvider(config.apiKey, config.model.ifBlank { "gpt-4o" }, "https://api.openai.com/v1")
        "nvidia" -> NvidiaProvider(config.apiKey, config.model.ifBlank { "nvidia/llama-3.1-nemotron-70b-instruct" })
        "custom" -> OpenAiProvider(config.apiKey, config.model.ifBlank { "default" }, config.baseUrl.ifBlank { "https://api.openai.com/v1" })
        else -> GeminiProvider(config.apiKey, "gemini-2.0-flash")
    }
}
