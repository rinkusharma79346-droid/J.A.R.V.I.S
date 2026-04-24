package com.jarvis.agent

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * JARVIS MCP Relay Client v3.0 — HTTP Long-Polling
 *
 * Key improvements for speed:
 *   - Auto-capture: Every action response includes screenshot + UI tree
 *   - Sequence commands: Execute multiple actions with one round-trip
 *   - Chrome URL macro: Open URL in Chrome with zero round-trips during execution
 *   - Enhanced type_text: Uses clipboard paste for Chrome URL bar compatibility
 *   - PiP overlay: Shows when MCP commands are being processed
 *
 * Relay endpoints:
 *   POST /api/register   → register device
 *   GET  /api/poll       → long-poll for commands (25s timeout)
 *   POST /api/status     → push status updates
 *   POST /api/response   → push command responses
 *   GET  /api/warmup     → keep server alive
 */
object RelayClient {

    private const val TAG = "RelayClient"
    private const val PREF_RELAY_URL = "relay_url"
    private const val PREF_RELAY_ENABLED = "relay_enabled"
    private const val DEFAULT_RELAY_URL = "https://j-a-r-v-i-s-ktlh.onrender.com"
    private const val RECONNECT_BASE_MS = 3000L
    private const val RECONNECT_MAX_MS = 60000L
    private const val STATUS_PUSH_INTERVAL_MS = 3000L
    private const val WARMUP_INTERVAL_MS = 12 * 60 * 1000L

    val isConnected = MutableStateFlow(false)
    val relayStatus = MutableStateFlow("Disconnected")

    // Track MCP activity for PiP overlay
    val mcpActive = MutableStateFlow(false)
    val mcpLastAction = MutableStateFlow("")

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private var prefs: SharedPreferences? = null
    private var serviceRef: JarvisService? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private var statusPushJob: Job? = null
    private var warmupJob: Job? = null
    private var consecutiveErrors = 0
    private var registered = false
    private var mcpAutoResetJob: Job? = null

    fun init(prefs: SharedPreferences, service: JarvisService) {
        this.prefs = prefs
        this.serviceRef = service
    }

    fun getRelayUrl(): String = prefs?.getString(PREF_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL

    fun setRelayUrl(url: String) {
        prefs?.edit()?.putString(PREF_RELAY_URL, url)?.apply()
    }

    fun isEnabled(): Boolean = prefs?.getBoolean(PREF_RELAY_ENABLED, false) ?: false

    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(PREF_RELAY_ENABLED, enabled)?.apply()
        if (enabled) connect() else disconnect()
    }

    // ─── Connection Management ───
    fun connect() {
        val url = getRelayUrl()
        if (url.isBlank()) {
            relayStatus.value = "No relay URL configured"
            return
        }

        disconnect()
        registered = false
        consecutiveErrors = 0
        isConnected.value = true
        relayStatus.value = "Connecting..."

        pollJob = scope.launch {
            if (!registerDeviceWithRetry()) {
                isConnected.value = false
                relayStatus.value = "Registration failed — retrying..."
                scheduleReconnect()
                return@launch
            }

            registered = true
            isConnected.value = true
            relayStatus.value = "Connected (HTTP polling)"

            startStatusPush()
            startWarmupPings()
            pollLoop()
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        statusPushJob?.cancel()
        statusPushJob = null
        warmupJob?.cancel()
        warmupJob = null
        mcpAutoResetJob?.cancel()
        mcpAutoResetJob = null
        isConnected.value = false
        relayStatus.value = "Disconnected"
        registered = false
        consecutiveErrors = 0
        mcpActive.value = false
    }

    // ─── Auto-reset MCP active state after 10s of no commands ───
    private fun scheduleMcpAutoReset() {
        mcpAutoResetJob?.cancel()
        mcpAutoResetJob = scope.launch {
            delay(10_000L)
            mcpActive.value = false
        }
    }

    // ─── Registration ───
    private suspend fun registerDeviceWithRetry(): Boolean {
        val maxRetries = 3
        for (attempt in 1..maxRetries) {
            if (registerDevice()) return true
            if (attempt < maxRetries) {
                Log.w(TAG, "Registration attempt $attempt/$maxRetries failed, retrying in ${attempt * 5}s...")
                relayStatus.value = "Waking up server (attempt $attempt/$maxRetries)..."
                delay(attempt * 5000L)
            }
        }
        return false
    }

    private suspend fun registerDevice(): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = mapOf(
                "type" to "register_device",
                "deviceId" to getDeviceId(),
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "androidVersion" to Build.VERSION.RELEASE,
                "sdkVersion" to Build.VERSION.SDK_INT
            )
            val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${getRelayUrl()}/api/register")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Registered with relay server")
                    true
                } else {
                    Log.e(TAG, "Registration failed: ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration error: ${e.message}")
            false
        }
    }

    // ─── Warmup Pings ───
    private fun startWarmupPings() {
        warmupJob?.cancel()
        warmupJob = scope.launch {
            while (currentCoroutineContext().isActive && isEnabled()) {
                delay(WARMUP_INTERVAL_MS)
                try {
                    val request = Request.Builder()
                        .url("${getRelayUrl()}/api/warmup")
                        .get()
                        .build()
                    client.newCall(request).execute().close()
                    Log.d(TAG, "Warmup ping sent")
                } catch (e: Exception) {
                    Log.w(TAG, "Warmup ping failed: ${e.message}")
                }
            }
        }
    }

    // ─── Poll Loop ───
    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive && isEnabled()) {
            try {
                val commands = pollForCommands()
                consecutiveErrors = 0

                for (cmd in commands) {
                    try {
                        handleCommand(cmd)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling command: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                consecutiveErrors++
                val delayMs = (RECONNECT_BASE_MS * (1L shl (consecutiveErrors.coerceAtMost(5) - 1)))
                    .coerceIn(RECONNECT_BASE_MS, RECONNECT_MAX_MS)
                Log.w(TAG, "Poll failed ($consecutiveErrors errors), retrying in ${delayMs}ms: ${e.message}")
                relayStatus.value = "Reconnecting in ${delayMs / 1000}s..."

                if (consecutiveErrors >= 10) {
                    Log.w(TAG, "Too many errors, re-registering...")
                    registered = registerDeviceWithRetry()
                    if (registered) {
                        relayStatus.value = "Connected (HTTP polling)"
                    }
                    consecutiveErrors = 0
                }

                delay(delayMs)
            }
        }

        if (isEnabled()) {
            isConnected.value = false
            relayStatus.value = "Poll loop ended — reconnecting..."
            scheduleReconnect()
        }
    }

    private suspend fun pollForCommands(): List<JsonObject> = withContext(Dispatchers.IO) {
        val url = "${getRelayUrl()}/api/poll?deviceId=${getDeviceId()}"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Poll HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            val json = gson.fromJson(body, JsonObject::class.java)
            val commands = json.getAsJsonArray("commands") ?: return@withContext emptyList()

            commands.mapNotNull { element ->
                try { element.asJsonObject } catch (e: Exception) { null }
            }
        }
    }

    // ─── Status Push Loop ───
    private fun startStatusPush() {
        statusPushJob?.cancel()
        statusPushJob = scope.launch {
            while (currentCoroutineContext().isActive && isEnabled()) {
                if (JarvisService.isRunning.value || JarvisService.status.value != "Idle") {
                    pushStatusUpdate(
                        JarvisService.status.value,
                        JarvisService.currentStep.value,
                        JarvisService.currentAction.value,
                        JarvisService.isRunning.value
                    )
                }
                delay(STATUS_PUSH_INTERVAL_MS)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  COMMAND HANDLER — Z AI is the brain, phone just executes
    // ══════════════════════════════════════════════════════════════

    private fun handleCommand(cmd: JsonObject) {
        val type = cmd.get("type")?.asString ?: return
        val requestId = cmd.get("requestId")?.asString ?: ""
        val capture = cmd.get("capture")?.asBoolean ?: true  // Auto-capture by default

        Log.d(TAG, "Command received: $type (requestId: $requestId, capture: $capture)")

        // Show PiP overlay when MCP commands arrive
        mcpActive.value = true
        mcpLastAction.value = type

        // CRITICAL FIX: Start HUD overlay for MCP commands
        // Previously, HUD only started from EXECUTE button in MainActivity.
        // Now it also shows when MCP commands arrive via relay.
        serviceRef?.let { service ->
            HUDService.showForMcp(service)
        }

        // Auto-reset mcpActive after 10s of no new commands
        scheduleMcpAutoReset()

        when (type) {
            // ─── Direct Action Commands ───
            "tap" -> handleTap(cmd, requestId, capture)
            "swipe" -> handleSwipe(cmd, requestId, capture)
            "long_press" -> handleLongPress(cmd, requestId, capture)
            "type_text" -> handleTypeText(cmd, requestId, capture)
            "press_back" -> handlePressBack(requestId, capture)
            "press_home" -> handlePressHome(requestId, capture)
            "press_recents" -> handlePressRecents(requestId, capture)
            "open_app" -> handleOpenApp(cmd, requestId, capture)
            "open_url" -> handleOpenUrl(cmd, requestId, capture)
            "screenshot" -> handleScreenshot(requestId)
            "ui_tree" -> handleUiTree(requestId)
            "screenshot_and_ui" -> handleScreenshotAndUi(requestId)

            // ─── Sequence & Macro Commands (SPEED BOOST) ───
            "sequence" -> handleSequence(cmd, requestId)
            "open_chrome_url" -> handleOpenChromeUrl(cmd, requestId)

            // ─── Legacy commands ───
            "execute" -> handleExecute(cmd, requestId)
            "status" -> handleStatus(requestId)
            "kill" -> handleKill(requestId)
            "list_apps" -> handleListApps(requestId)
            "ping" -> sendResponse(requestId, mapOf("type" to "pong", "deviceId" to getDeviceId()))

            else -> {
                Log.w(TAG, "Unknown command type: $type")
                sendResponse(requestId, mapOf("type" to "error", "message" to "Unknown command: $type", "deviceId" to getDeviceId()))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  DIRECT ACTION HANDLERS — with auto-capture
    // ══════════════════════════════════════════════════════════════

    private fun handleTap(cmd: JsonObject, requestId: String, capture: Boolean) {
        val x = cmd.get("x")?.asInt ?: 0
        val y = cmd.get("y")?.asInt ?: 0
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("TAP", x, y, 0, 0, "")
            }

            if (capture) {
                val (b64, uiTree) = service.autoCapture()
                sendResponse(requestId, mapOf(
                    "type" to "tap_done",
                    "x" to x, "y" to y,
                    "success" to true,
                    "deviceId" to getDeviceId(),
                    "base64" to (b64 ?: ""),
                    "mimeType" to "image/jpeg",
                    "uiTree" to (uiTree ?: "")
                ))
            } else {
                delay(300)
                sendResponse(requestId, mapOf(
                    "type" to "tap_done",
                    "x" to x, "y" to y,
                    "success" to true,
                    "deviceId" to getDeviceId()
                ))
            }
            mcpLastAction.value = "tap($x,$y)"
        }
    }

    private fun handleSwipe(cmd: JsonObject, requestId: String, capture: Boolean) {
        val x = cmd.get("x")?.asInt ?: 0
        val y = cmd.get("y")?.asInt ?: 0
        val x2 = cmd.get("x2")?.asInt ?: 0
        val y2 = cmd.get("y2")?.asInt ?: 0
        val duration = cmd.get("duration")?.asLong ?: 400L
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("SWIPE", x, y, x2, y2, "", duration)
            }

            if (capture) {
                val (b64, uiTree) = service.autoCapture()
                sendResponse(requestId, mapOf(
                    "type" to "swipe_done",
                    "x" to x, "y" to y, "x2" to x2, "y2" to y2,
                    "success" to true,
                    "deviceId" to getDeviceId(),
                    "base64" to (b64 ?: ""),
                    "mimeType" to "image/jpeg",
                    "uiTree" to (uiTree ?: "")
                ))
            } else {
                delay(duration + 200)
                sendResponse(requestId, mapOf(
                    "type" to "swipe_done",
                    "x" to x, "y" to y, "x2" to x2, "y2" to y2,
                    "success" to true,
                    "deviceId" to getDeviceId()
                ))
            }
            mcpLastAction.value = "swipe"
        }
    }

    private fun handleLongPress(cmd: JsonObject, requestId: String, capture: Boolean) {
        val x = cmd.get("x")?.asInt ?: 0
        val y = cmd.get("y")?.asInt ?: 0
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("LONG_PRESS", x, y, 0, 0, "")
            }

            if (capture) {
                val (b64, uiTree) = service.autoCapture()
                sendResponse(requestId, mapOf(
                    "type" to "long_press_done",
                    "x" to x, "y" to y,
                    "success" to true,
                    "deviceId" to getDeviceId(),
                    "base64" to (b64 ?: ""),
                    "mimeType" to "image/jpeg",
                    "uiTree" to (uiTree ?: "")
                ))
            } else {
                delay(800)
                sendResponse(requestId, mapOf(
                    "type" to "long_press_done",
                    "x" to x, "y" to y,
                    "success" to true,
                    "deviceId" to getDeviceId()
                ))
            }
            mcpLastAction.value = "long_press($x,$y)"
        }
    }

    private fun handleTypeText(cmd: JsonObject, requestId: String, capture: Boolean) {
        val x = cmd.get("x")?.asInt ?: 0
        val y = cmd.get("y")?.asInt ?: 0
        val text = cmd.get("text")?.asString ?: ""
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("TYPE", x, y, 0, 0, text)
            }

            if (capture) {
                val (b64, uiTree) = service.autoCapture()
                sendResponse(requestId, mapOf(
                    "type" to "type_done",
                    "x" to x, "y" to y, "text" to text,
                    "success" to true,
                    "deviceId" to getDeviceId(),
                    "base64" to (b64 ?: ""),
                    "mimeType" to "image/jpeg",
                    "uiTree" to (uiTree ?: "")
                ))
            } else {
                delay(500)
                sendResponse(requestId, mapOf(
                    "type" to "type_done",
                    "x" to x, "y" to y, "text" to text,
                    "success" to true,
                    "deviceId" to getDeviceId()
                ))
            }
            mcpLastAction.value = "type: ${text.take(20)}"
        }
    }

    private fun handlePressBack(requestId: String, capture: Boolean) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("PRESS_BACK", 0, 0, 0, 0, "")
            }

            if (capture) {
                val (b64, uiTree) = service.autoCapture()
                sendResponse(requestId, mapOf(
                    "type" to "press_back_done",
                    "success" to true,
                    "deviceId" to getDeviceId(),
                    "base64" to (b64 ?: ""),
                    "mimeType" to "image/jpeg",
                    "uiTree" to (uiTree ?: "")
                ))
            } else {
                delay(300)
                sendResponse(requestId, mapOf(
                    "type" to "press_back_done",
                    "success" to true,
                    "deviceId" to getDeviceId()
                ))
            }
            mcpLastAction.value = "press_back"
        }
    }

    private fun handlePressHome(requestId: String, capture: Boolean) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("PRESS_HOME", 0, 0, 0, 0, "")
            }

            if (capture) {
                val (b64, uiTree) = service.autoCapture()
                sendResponse(requestId, mapOf(
                    "type" to "press_home_done",
                    "success" to true,
                    "deviceId" to getDeviceId(),
                    "base64" to (b64 ?: ""),
                    "mimeType" to "image/jpeg",
                    "uiTree" to (uiTree ?: "")
                ))
            } else {
                delay(300)
                sendResponse(requestId, mapOf(
                    "type" to "press_home_done",
                    "success" to true,
                    "deviceId" to getDeviceId()
                ))
            }
            mcpLastAction.value = "press_home"
        }
    }

    private fun handlePressRecents(requestId: String, capture: Boolean) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("PRESS_RECENTS", 0, 0, 0, 0, "")
            }

            if (capture) {
                val (b64, uiTree) = service.autoCapture()
                sendResponse(requestId, mapOf(
                    "type" to "press_recents_done",
                    "success" to true,
                    "deviceId" to getDeviceId(),
                    "base64" to (b64 ?: ""),
                    "mimeType" to "image/jpeg",
                    "uiTree" to (uiTree ?: "")
                ))
            } else {
                delay(300)
                sendResponse(requestId, mapOf(
                    "type" to "press_recents_done",
                    "success" to true,
                    "deviceId" to getDeviceId()
                ))
            }
            mcpLastAction.value = "press_recents"
        }
    }

    private fun handleOpenApp(cmd: JsonObject, requestId: String, capture: Boolean) {
        val app = cmd.get("app")?.asString ?: ""
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("OPEN_APP", 0, 0, 0, 0, app)
            }

            if (capture) {
                // Wait longer for app to open
                delay(2000)
                val (b64, uiTree) = service.autoCapture()
                sendResponse(requestId, mapOf(
                    "type" to "open_app_done",
                    "app" to app,
                    "success" to true,
                    "deviceId" to getDeviceId(),
                    "base64" to (b64 ?: ""),
                    "mimeType" to "image/jpeg",
                    "uiTree" to (uiTree ?: "")
                ))
            } else {
                delay(2000)
                sendResponse(requestId, mapOf(
                    "type" to "open_app_done",
                    "app" to app,
                    "success" to true,
                    "deviceId" to getDeviceId()
                ))
            }
            mcpLastAction.value = "open_app: $app"
        }
    }

    private fun handleOpenUrl(cmd: JsonObject, requestId: String, capture: Boolean) {
        val url = cmd.get("url")?.asString ?: ""
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                // Fix: Explicitly target Chrome to avoid opening wrong app
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.setPackage("com.android.chrome")
                    service.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: try without Chrome package
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        service.startActivity(intent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to open URL: ${e2.message}")
                    }
                }
            }

            delay(3000) // Wait for page to load

            if (capture) {
                val (b64, uiTree) = service.autoCapture()
                sendResponse(requestId, mapOf(
                    "type" to "open_url_done",
                    "url" to url,
                    "success" to true,
                    "deviceId" to getDeviceId(),
                    "base64" to (b64 ?: ""),
                    "mimeType" to "image/jpeg",
                    "uiTree" to (uiTree ?: "")
                ))
            } else {
                sendResponse(requestId, mapOf(
                    "type" to "open_url_done",
                    "url" to url,
                    "success" to true,
                    "deviceId" to getDeviceId()
                ))
            }
            mcpLastAction.value = "open_url: ${url.take(30)}"
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SEQUENCE COMMAND — Execute batch of actions, auto-capture at end
    // ══════════════════════════════════════════════════════════════

    private fun handleSequence(cmd: JsonObject, requestId: String) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        val actionsArray = cmd.getAsJsonArray("actions") ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Missing actions array"))
            return
        }

        val actions = mutableListOf<SequenceAction>()
        for (element in actionsArray) {
            try {
                val obj = element.asJsonObject
                actions.add(SequenceAction(
                    action = obj.get("action")?.asString ?: "TAP",
                    x = obj.get("x")?.asInt ?: 0,
                    y = obj.get("y")?.asInt ?: 0,
                    x2 = obj.get("x2")?.asInt ?: 0,
                    y2 = obj.get("y2")?.asInt ?: 0,
                    text = obj.get("text")?.asString ?: "",
                    app = obj.get("app")?.asString ?: "",
                    delay = obj.get("delay")?.asLong ?: 0L,
                    duration = obj.get("duration")?.asLong ?: 0L
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse sequence action: ${e.message}")
            }
        }

        if (actions.isEmpty()) {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Empty actions array"))
            return
        }

        mcpLastAction.value = "sequence: ${actions.size} actions"

        scope.launch {
            val (b64, uiTree) = service.executeSequence(actions)
            val actionSummary = actions.map { it.action }.joinToString(" → ")

            sendResponse(requestId, mapOf(
                "type" to "sequence_done",
                "actionCount" to actions.size,
                "actions" to actionSummary,
                "success" to true,
                "deviceId" to getDeviceId(),
                "base64" to (b64 ?: ""),
                "mimeType" to "image/jpeg",
                "uiTree" to (uiTree ?: "")
            ))
            mcpLastAction.value = "sequence_done"
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  CHROME URL MACRO — Open URL in Chrome, type it, load page
    //  Executes entirely on phone with zero round-trips
    // ══════════════════════════════════════════════════════════════

    private fun handleOpenChromeUrl(cmd: JsonObject, requestId: String) {
        val url = cmd.get("url")?.asString ?: ""
        if (url.isBlank()) {
            sendResponse(requestId, mapOf("type" to "error", "message" to "URL is required"))
            return
        }
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        mcpLastAction.value = "open_chrome_url: ${url.take(30)}"

        scope.launch {
            val (b64, uiTree) = service.openChromeUrl(url)
            sendResponse(requestId, mapOf(
                "type" to "chrome_url_done",
                "url" to url,
                "success" to true,
                "deviceId" to getDeviceId(),
                "base64" to (b64 ?: ""),
                "mimeType" to "image/jpeg",
                "uiTree" to (uiTree ?: "")
            ))
            mcpLastAction.value = "chrome_url_done"
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LOOK / SCREENSHOT / UI TREE
    // ══════════════════════════════════════════════════════════════

    private fun handleUiTree(requestId: String) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            val uiTree = withContext(Dispatchers.Main) {
                service.getUiTreePublic()
            }
            sendResponse(requestId, mapOf(
                "type" to "ui_tree",
                "uiTree" to (uiTree ?: ""),
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handleScreenshotAndUi(requestId: String) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            val b64 = withContext(Dispatchers.Main) {
                try { service.captureScreenPublic() }
                catch (e: Exception) { Log.e(TAG, "Screenshot failed: ${e.message}"); null }
            }
            val uiTree = withContext(Dispatchers.Main) {
                service.getUiTreePublic()
            }
            sendResponse(requestId, mapOf(
                "type" to "screenshot_and_ui",
                "base64" to (b64 ?: ""),
                "mimeType" to "image/jpeg",
                "uiTree" to (uiTree ?: ""),
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handleScreenshot(requestId: String) {
        scope.launch {
            val service = serviceRef ?: run {
                sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
                return@launch
            }

            val b64 = withContext(Dispatchers.Main) {
                try { service.captureScreenPublic() }
                catch (e: Exception) { Log.e(TAG, "Screenshot failed: ${e.message}"); null }
            }

            if (b64 != null) {
                sendResponse(requestId, mapOf(
                    "type" to "screenshot_response",
                    "base64" to b64,
                    "mimeType" to "image/jpeg",
                    "deviceId" to getDeviceId()
                ))
            } else {
                sendResponse(requestId, mapOf(
                    "type" to "error",
                    "message" to "Screenshot capture failed. Is accessibility service active?",
                    "deviceId" to getDeviceId()
                ))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LEGACY COMMAND HANDLERS
    // ══════════════════════════════════════════════════════════════

    private fun handleExecute(msg: JsonObject, requestId: String) {
        val task = msg.get("task")?.asString ?: return
        Log.i(TAG, "Remote execute: $task")

        JarvisService.startTask(task)

        sendResponse(requestId, mapOf(
            "type" to "execute_ack",
            "status" to "started",
            "task" to task,
            "deviceId" to getDeviceId()
        ))

        scope.launch {
            var lastStatus = ""
            var lastAction = ""
            while (JarvisService.isRunning.value) {
                delay(2000)
                val curStatus = JarvisService.status.value
                val curAction = JarvisService.currentAction.value
                if (curStatus != lastStatus || curAction != lastAction) {
                    sendResponse(requestId, mapOf(
                        "type" to "status_update",
                        "status" to curStatus,
                        "step" to JarvisService.currentStep.value,
                        "action" to curAction,
                        "deviceId" to getDeviceId()
                    ))
                    lastStatus = curStatus
                    lastAction = curAction
                }
            }

            sendResponse(requestId, mapOf(
                "type" to "execute_complete",
                "status" to JarvisService.status.value,
                "step" to JarvisService.currentStep.value,
                "action" to JarvisService.currentAction.value,
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handleStatus(requestId: String) {
        val config = serviceRef?.let { SettingsManager.getConfig(it) } ?: ProviderConfig()

        sendResponse(requestId, mapOf(
            "type" to "status_response",
            "isRunning" to JarvisService.isRunning.value,
            "status" to JarvisService.status.value,
            "step" to JarvisService.currentStep.value,
            "action" to JarvisService.currentAction.value,
            "provider" to config.provider,
            "model" to config.model,
            "deviceId" to getDeviceId()
        ))
    }

    private fun handleKill(requestId: String) {
        JarvisService.stopTask()
        sendResponse(requestId, mapOf(
            "type" to "kill_response",
            "message" to "Task killed",
            "deviceId" to getDeviceId()
        ))
    }

    private fun handleListApps(requestId: String) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        val pm = service.packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { mapOf("name" to pm.getApplicationLabel(it).toString(), "packageName" to it.packageName) }
            .sortedBy { it["name"].toString().lowercase() }

        sendResponse(requestId, mapOf(
            "type" to "app_list",
            "apps" to apps,
            "count" to apps.size,
            "deviceId" to getDeviceId()
        ))
    }

    // ─── Send Response ───
    private fun sendResponse(requestId: String, data: Map<String, Any?>) {
        scope.launch {
            try {
                val payload = data + ("requestId" to requestId)
                val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${getRelayUrl()}/api/response")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Response POST failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Response POST error: ${e.message}")
            }
        }
    }

    // ─── Push Status Update ───
    fun pushStatusUpdate(status: String, step: Int, action: String) {
        if (!isConnected.value) return
        scope.launch {
            try {
                val payload = mapOf(
                    "deviceId" to getDeviceId(),
                    "status" to status,
                    "step" to step,
                    "action" to action,
                    "isRunning" to JarvisService.isRunning.value,
                    "timestamp" to System.currentTimeMillis()
                )
                val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${getRelayUrl()}/api/status")
                    .post(body)
                    .build()

                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Status push error: ${e.message}")
            }
        }
    }

    private suspend fun pushStatusUpdate(status: String, step: Int, action: String, isRunning: Boolean) {
        try {
            val payload = mapOf(
                "deviceId" to getDeviceId(),
                "status" to status,
                "step" to step,
                "action" to action,
                "isRunning" to isRunning,
                "timestamp" to System.currentTimeMillis()
            )
            val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${getRelayUrl()}/api/status")
                .post(body)
                .build()

            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "Status push error: ${e.message}")
        }
    }

    // ─── Reconnect ───
    private fun scheduleReconnect() {
        scope.launch {
            delay(RECONNECT_BASE_MS)
            if (isEnabled()) connect()
        }
    }

    // ─── Test Connection ───
    suspend fun testConnection(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$url/api/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val version = json.get("version")?.asString ?: "?"
                    val devCount = json.get("devices")?.asInt ?: 0
                    val protocol = json.get("protocol")?.asString ?: "unknown"
                    Pair(true, "Relay v$version ($protocol, $devCount devices)")
                } else {
                    Pair(false, "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Connection failed: ${e.message}")
        }
    }

    // ─── Device ID ───
    private fun getDeviceId(): String {
        val key = "device_id"
        var id = prefs?.getString(key, "") ?: ""
        if (id.isBlank()) {
            id = "jarvis-${Build.MODEL?.take(6)?.lowercase()?.replace(" ", "")}-${System.currentTimeMillis() % 10000}"
            prefs?.edit()?.putString(key, id)?.apply()
        }
        return id
    }

    // ─── Connection Info ───
    fun getConnectionInfo(): String {
        if (!isConnected.value) return relayStatus.value
        return "Connected (${getRelayUrl().removePrefix("https://").removePrefix("http://")})"
    }
}
