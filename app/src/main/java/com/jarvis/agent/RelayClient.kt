package com.jarvis.agent

import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that connects the JARVIS Android app to the MCP relay server.
 * This enables MCP clients (Claude Desktop, Cursor, etc.) to control the agent remotely.
 *
 * Protocol:
 *   Device → Relay:  { type: "register_device", deviceId, model, androidVersion }
 *   Device → Relay:  { type: "status_update", status, step, action, deviceId }
 *   Relay → Device:  { type: "execute", task, requestId }
 *   Relay → Device:  { type: "status", requestId }
 *   Relay → Device:  { type: "kill", requestId }
 *   Relay → Device:  { type: "screenshot", requestId }
 *   Relay → Device:  { type: "list_apps", requestId }
 *   Device → Relay:  { type: "<response>", requestId, ... }
 */
object RelayClient {

    private const val TAG = "RelayClient"
    private const val PREF_RELAY_URL = "relay_url"
    private const val PREF_RELAY_ENABLED = "relay_enabled"
    private const val DEFAULT_RELAY_URL = "https://j-a-r-v-i-s-ktlh.onrender.com"
    private const val WS_PATH = "/ws"           // WebSocket endpoint on relay
    private const val RECONNECT_BASE_MS = 3000L  // Initial reconnect delay
    private const val RECONNECT_MAX_MS = 60000L  // Max reconnect delay
    private const val HEARTBEAT_INTERVAL_MS = 25000L // Ping interval

    val isConnected = MutableStateFlow(false)
    val relayStatus = MutableStateFlow("Disconnected")

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempt = 0
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private var prefs: SharedPreferences? = null
    private var serviceRef: JarvisService? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    // ─── Connection ───
    fun connect() {
        val url = getRelayUrl()
        if (url.isBlank()) {
            relayStatus.value = "No relay URL configured"
            Log.w(TAG, "No relay URL configured")
            return
        }

        disconnect()

        // Build WebSocket URL: https://... → wss://.../ws
        val wsUrl = buildWsUrl(url)
        Log.i(TAG, "Connecting to relay: $wsUrl")
        relayStatus.value = "Connecting..."

        val request = Request.Builder()
            .url(wsUrl)
            .header("X-Device-Id", getDeviceId())
            .header("X-Device-Model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .header("X-Android-Version", Build.VERSION.RELEASE)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to relay — code: ${response.code}")
                reconnectAttempt = 0
                isConnected.value = true
                relayStatus.value = "Connected"

                // Register as a device
                val registerMsg = JsonObject().apply {
                    addProperty("type", "register_device")
                    addProperty("deviceId", getDeviceId())
                    addProperty("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    addProperty("androidVersion", Build.VERSION.RELEASE)
                    addProperty("sdkVersion", Build.VERSION.SDK_INT)
                }
                webSocket.send(gson.toJson(registerMsg))

                // Start heartbeat
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                handleDisconnect()
            }
        })
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
        isConnected.value = false
        relayStatus.value = "Disconnected"
        reconnectAttempt = 0
    }

    private fun buildWsUrl(baseUrl: String): String {
        var wsUrl = baseUrl.trimEnd('/')
        // Convert HTTP(S) to WS(S)
        wsUrl = when {
            wsUrl.startsWith("https://") -> wsUrl.replace("https://", "wss://")
            wsUrl.startsWith("http://") -> wsUrl.replace("http://", "ws://")
            wsUrl.startsWith("wss://") -> wsUrl
            wsUrl.startsWith("ws://") -> wsUrl
            else -> "wss://$wsUrl"
        }
        // Append WebSocket path if not already present
        if (!wsUrl.endsWith("/ws") && !wsUrl.endsWith("/socket") && !wsUrl.contains("/ws?")) {
            wsUrl = "$wsUrl$WS_PATH"
        }
        return wsUrl
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isConnected.value) {
                    val ping = JsonObject().apply {
                        addProperty("type", "ping")
                        addProperty("deviceId", getDeviceId())
                        addProperty("timestamp", System.currentTimeMillis())
                    }
                    webSocket?.send(gson.toJson(ping))
                }
            }
        }
    }

    private fun handleDisconnect() {
        isConnected.value = false
        heartbeatJob?.cancel()
        heartbeatJob = null

        if (isEnabled()) {
            reconnectAttempt++
            val delayMs = (RECONNECT_BASE_MS * (1L shl (reconnectAttempt.coerceAtMost(5) - 1)))
                .coerceIn(RECONNECT_BASE_MS, RECONNECT_MAX_MS)
            relayStatus.value = "Reconnecting in ${delayMs / 1000}s (attempt $reconnectAttempt)..."
            Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")

            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                if (isEnabled()) connect()
            }
        } else {
            relayStatus.value = "Disconnected"
        }
    }

    // ─── Message Handler ───
    private fun handleMessage(text: String) {
        val msg = try {
            gson.fromJson(text, JsonObject::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Non-JSON message: ${text.take(100)}")
            return
        }
        val type = msg.get("type")?.asString ?: return
        val requestId = msg.get("requestId")?.asString ?: ""

        Log.d(TAG, "Received: $type (requestId: $requestId)")

        when (type) {
            "execute" -> handleExecute(msg, requestId)
            "status" -> handleStatus(requestId)
            "kill" -> handleKill(requestId)
            "screenshot" -> handleScreenshot(requestId)
            "list_apps" -> handleListApps(requestId)
            "ping" -> sendResponse("", mapOf("type" to "pong", "deviceId" to getDeviceId()))
            "pong" -> { /* heartbeat ack */ }
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    }

    // ─── Command Handlers ───

    private fun handleExecute(msg: JsonObject, requestId: String) {
        val task = msg.get("task")?.asString ?: return
        Log.i(TAG, "Remote execute: $task")

        // Start the task on the agent
        JarvisService.startTask(task)

        // Send initial acknowledgment
        sendResponse(requestId, mapOf(
            "type" to "execute_ack",
            "status" to "started",
            "task" to task,
            "deviceId" to getDeviceId()
        ))

        // Monitor task and send periodic updates
        scope.launch {
            var lastStatus = ""
            var lastAction = ""
            while (JarvisService.isRunning.value) {
                delay(1500)
                val curStatus = JarvisService.status.value
                val curAction = JarvisService.currentAction.value
                // Only send if something changed
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

            // Task finished — send completion
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
                try {
                    service.captureScreenPublic()
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot capture failed: ${e.message}")
                    null
                }
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
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null } // Only launchable apps
            .map { appInfo ->
                mapOf(
                    "name" to pm.getApplicationLabel(appInfo).toString(),
                    "packageName" to appInfo.packageName
                )
            }.sortedBy { it["name"].toString().lowercase() }

        sendResponse(requestId, mapOf(
            "type" to "app_list",
            "apps" to apps,
            "count" to apps.size,
            "deviceId" to getDeviceId()
        ))
    }

    // ─── Send Response ───
    private fun sendResponse(requestId: String, data: Map<String, Any?>) {
        val ws = webSocket
        if (ws == null || !isConnected.value) {
            Log.w(TAG, "Cannot send response — not connected")
            return
        }

        val json = gson.toJson(data + ("requestId" to requestId))
        val sent = ws.send(json)
        if (!sent) {
            Log.w(TAG, "Failed to send response — send queue full")
        }
    }

    // ─── Push Status Update (called by JarvisService during ReAct loop) ───
    fun pushStatusUpdate(status: String, step: Int, action: String) {
        if (!isConnected.value) return
        val msg = mapOf(
            "type" to "status_update",
            "status" to status,
            "step" to step,
            "action" to action,
            "deviceId" to getDeviceId(),
            "timestamp" to System.currentTimeMillis()
        )
        val sent = webSocket?.send(gson.toJson(msg))
        if (sent == false) Log.w(TAG, "Status push failed — not connected")
    }

    // ─── Device ID (persistent, unique per install) ───
    private fun getDeviceId(): String {
        val key = "device_id"
        var id = prefs?.getString(key, "") ?: ""
        if (id.isBlank()) {
            id = "jarvis-${Build.MODEL?.take(6)?.lowercase()?.replace(" ", "")}-${System.currentTimeMillis() % 10000}"
            prefs?.edit()?.putString(key, id)?.apply()
        }
        return id
    }

    // ─── Get connection stats for UI ───
    fun getConnectionInfo(): String {
        if (!isConnected.value) return relayStatus.value
        return "Connected (${getRelayUrl().removePrefix("https://").removePrefix("http://")})"
    }
}
