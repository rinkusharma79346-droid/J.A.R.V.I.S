package com.jarvis.agent

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
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
 * HTTP Long-Polling relay client for JARVIS MCP.
 *
 * Bulletproof on Render free tier — no WebSockets, no connection drops.
 * Uses simple HTTP requests:
 *   - POST /api/register   → register device
 *   - GET  /api/poll       → long-poll for commands (25s timeout)
 *   - POST /api/status     → push status updates
 *   - POST /api/response   → push command responses
 *   - GET  /api/warmup     → keep server alive (prevent cold starts)
 *
 * Direct Action Commands (Z AI is the brain — no local Gemini needed):
 *   - tap         → tap at (x, y)
 *   - swipe       → swipe from (x,y) to (x2,y2)
 *   - long_press  → long press at (x, y)
 *   - type_text   → type text at (x, y)
 *   - press_back  → press back button
 *   - press_home  → press home button
 *   - press_recents → press recents button
 *   - open_app    → open app by name or package
 *   - screenshot  → capture screenshot
 *   - ui_tree     → get current UI tree
 *   - execute     → delegate to local agent (needs Gemini API)
 *   - status      → get device status
 *   - kill        → kill running task
 *   - list_apps   → list installed apps
 *   - ping        → pong
 */
object RelayClient {

    private const val TAG = "RelayClient"
    private const val PREF_RELAY_URL = "relay_url"
    private const val PREF_RELAY_ENABLED = "relay_enabled"
    private const val DEFAULT_RELAY_URL = "https://j-a-r-v-i-s-ktlh.onrender.com"
    private const val RECONNECT_BASE_MS = 3000L
    private const val RECONNECT_MAX_MS = 60000L
    private const val STATUS_PUSH_INTERVAL_MS = 3000L
    private const val WARMUP_INTERVAL_MS = 12 * 60 * 1000L // 12 min (Render sleeps after 15min)

    val isConnected = MutableStateFlow(false)
    val relayStatus = MutableStateFlow("Disconnected")

    // Longer timeouts for Render cold starts
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)  // 60s for cold starts
        .readTimeout(35, TimeUnit.SECONDS)      // Must be > server's 25s poll timeout
        .writeTimeout(30, TimeUnit.SECONDS)     // Generous for cold starts
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

    // ─── Config ───
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

        // Start the poll loop
        pollJob = scope.launch {
            // Register first (with cold-start retry)
            if (!registerDeviceWithRetry()) {
                isConnected.value = false
                relayStatus.value = "Registration failed — retrying..."
                scheduleReconnect()
                return@launch
            }

            registered = true
            isConnected.value = true
            relayStatus.value = "Connected (HTTP polling)"

            // Start status push loop
            startStatusPush()

            // Start warmup pings (prevent Render from sleeping)
            startWarmupPings()

            // Start poll loop
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
        isConnected.value = false
        relayStatus.value = "Disconnected"
        registered = false
        consecutiveErrors = 0
    }

    // ─── Registration (with cold-start retry) ───
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

    // ─── Warmup Pings (prevent Render cold starts) ───
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

                // Process each command
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

        Log.d(TAG, "Command received: $type (requestId: $requestId)")

        when (type) {
            // ─── Direct Action Commands (Z AI controlled — no Gemini needed) ───
            "tap" -> handleTap(cmd, requestId)
            "swipe" -> handleSwipe(cmd, requestId)
            "long_press" -> handleLongPress(cmd, requestId)
            "type_text" -> handleTypeText(cmd, requestId)
            "press_back" -> handlePressBack(requestId)
            "press_home" -> handlePressHome(requestId)
            "press_recents" -> handlePressRecents(requestId)
            "open_app" -> handleOpenApp(cmd, requestId)
            "open_url" -> handleOpenUrl(cmd, requestId)
            "screenshot" -> handleScreenshot(requestId)
            "ui_tree" -> handleUiTree(requestId)
            "screenshot_and_ui" -> handleScreenshotAndUi(requestId)

            // ─── Legacy commands (uses local Gemini agent) ───
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
    //  DIRECT ACTION HANDLERS — Z AI controls, phone executes
    // ══════════════════════════════════════════════════════════════

    private fun handleTap(cmd: JsonObject, requestId: String) {
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
            delay(300) // Wait for tap to register
            sendResponse(requestId, mapOf(
                "type" to "tap_done",
                "x" to x, "y" to y,
                "success" to true,
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handleSwipe(cmd: JsonObject, requestId: String) {
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
            delay(duration + 200)
            sendResponse(requestId, mapOf(
                "type" to "swipe_done",
                "x" to x, "y" to y, "x2" to x2, "y2" to y2,
                "success" to true,
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handleLongPress(cmd: JsonObject, requestId: String) {
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
            delay(800)
            sendResponse(requestId, mapOf(
                "type" to "long_press_done",
                "x" to x, "y" to y,
                "success" to true,
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handleTypeText(cmd: JsonObject, requestId: String) {
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
            delay(500)
            sendResponse(requestId, mapOf(
                "type" to "type_done",
                "x" to x, "y" to y, "text" to text,
                "success" to true,
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handlePressBack(requestId: String) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("PRESS_BACK", 0, 0, 0, 0, "")
            }
            delay(300)
            sendResponse(requestId, mapOf(
                "type" to "press_back_done",
                "success" to true,
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handlePressHome(requestId: String) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("PRESS_HOME", 0, 0, 0, 0, "")
            }
            delay(300)
            sendResponse(requestId, mapOf(
                "type" to "press_home_done",
                "success" to true,
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handlePressRecents(requestId: String) {
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("PRESS_RECENTS", 0, 0, 0, 0, "")
            }
            delay(300)
            sendResponse(requestId, mapOf(
                "type" to "press_recents_done",
                "success" to true,
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handleOpenApp(cmd: JsonObject, requestId: String) {
        val app = cmd.get("app")?.asString ?: ""
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                service.executeDirectAction("OPEN_APP", 0, 0, 0, 0, app)
            }
            delay(2000) // Wait for app to open
            sendResponse(requestId, mapOf(
                "type" to "open_app_done",
                "app" to app,
                "success" to true,
                "deviceId" to getDeviceId()
            ))
        }
    }

    private fun handleOpenUrl(cmd: JsonObject, requestId: String) {
        val url = cmd.get("url")?.asString ?: ""
        val service = serviceRef ?: run {
            sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
            return
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
            }
            delay(3000) // Wait for browser to load
            sendResponse(requestId, mapOf(
                "type" to "open_url_done",
                "url" to url,
                "success" to true,
                "deviceId" to getDeviceId()
            ))
        }
    }

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

    // ══════════════════════════════════════════════════════════════
    //  LEGACY COMMAND HANDLERS (uses local Gemini agent)
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

    // ─── Send Response (HTTP POST) ───
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

    // ─── Push Status Update (public, called from JarvisService) ───
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
