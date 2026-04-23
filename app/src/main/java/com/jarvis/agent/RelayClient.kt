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
 * WebSocket client that connects the JARVIS Android app to the relay server.
 * This enables MCP clients (Claude Desktop, Cursor, etc.) to control the agent remotely.
 */
object RelayClient {

    private const val TAG = "RelayClient"
    private const val PREF_RELAY_URL = "relay_url"
    private const val PREF_RELAY_ENABLED = "relay_enabled"

    val isConnected = MutableStateFlow(false)
    val relayStatus = MutableStateFlow("Disconnected")

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private var prefs: SharedPreferences? = null
    private var serviceRef: JarvisService? = null

    // ─── Config ───
    fun init(prefs: SharedPreferences, service: JarvisService) {
        this.prefs = prefs
        this.serviceRef = service
    }

    fun getRelayUrl(): String = prefs?.getString(PREF_RELAY_URL, "") ?: ""

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

        val wsUrl = if (url.startsWith("https://")) url.replace("https://", "wss://")
            else if (url.startsWith("http://")) url.replace("http://", "ws://")
            else if (!url.startsWith("ws")) "wss://$url"
            else url

        Log.i(TAG, "Connecting to relay: $wsUrl")
        relayStatus.value = "Connecting..."

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to relay")
                isConnected.value = true
                relayStatus.value = "Connected"

                // Register as a device
                val registerMsg = JsonObject().apply {
                    addProperty("type", "register_device")
                    addProperty("deviceId", getDeviceId())
                    addProperty("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    addProperty("androidVersion", Build.VERSION.RELEASE)
                }
                webSocket.send(gson.toJson(registerMsg))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
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
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
        isConnected.value = false
        relayStatus.value = "Disconnected"
        reconnectJob?.cancel()
    }

    private fun handleDisconnect() {
        isConnected.value = false
        if (isEnabled()) {
            relayStatus.value = "Reconnecting in 10s..."
            reconnectJob = CoroutineScope(Dispatchers.IO).launch {
                delay(10000)
                if (isEnabled()) connect()
            }
        } else {
            relayStatus.value = "Disconnected"
        }
    }

    // ─── Message Handler ───
    private fun handleMessage(text: String) {
        val msg = gson.fromJson(text, JsonObject::class.java)
        val type = msg.get("type")?.asString ?: return
        val requestId = msg.get("requestId")?.asString ?: ""

        Log.d(TAG, "Received: $type (requestId: $requestId)")

        when (type) {
            "execute" -> handleExecute(msg, requestId)
            "status" -> handleStatus(requestId)
            "kill" -> handleKill(requestId)
            "screenshot" -> handleScreenshot(requestId)
            "list_apps" -> handleListApps(requestId)
        }
    }

    // ─── Command Handlers ───

    private fun handleExecute(msg: JsonObject, requestId: String) {
        val task = msg.get("task")?.asString ?: return
        Log.i(TAG, "Remote execute: $task")

        // Start the task on the agent
        JarvisService.startTask(task)

        // Send initial response
        sendResponse(requestId, mapOf(
            "type" to "execute_ack",
            "status" to "started",
            "task" to task
        ))

        // Monitor and send completion after a delay
        CoroutineScope(Dispatchers.IO).launch {
            while (JarvisService.isRunning.value) {
                delay(1000)
                // Send periodic status updates
                sendResponse(requestId, mapOf(
                    "type" to "status_update",
                    "status" to JarvisService.status.value,
                    "step" to JarvisService.currentStep.value,
                    "action" to JarvisService.currentAction.value
                ))
            }

            // Task finished
            sendResponse(requestId, mapOf(
                "type" to "execute_complete",
                "status" to JarvisService.status.value,
                "step" to JarvisService.currentStep.value,
                "action" to JarvisService.currentAction.value,
                "reason" to JarvisService.status.value
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
            "model" to config.model
        ))
    }

    private fun handleKill(requestId: String) {
        JarvisService.stopTask()
        sendResponse(requestId, mapOf(
            "type" to "kill_response",
            "message" to "Task killed"
        ))
    }

    private fun handleScreenshot(requestId: String) {
        // Screenshot capture needs to happen on the service
        CoroutineScope(Dispatchers.IO).launch {
            val service = serviceRef ?: run {
                sendResponse(requestId, mapOf("type" to "error", "message" to "Service not available"))
                return@launch
            }

            // Use the service's captureScreen via reflection or direct call
            // We'll capture on the main thread
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
                    "base64" to b64
                ))
            } else {
                sendResponse(requestId, mapOf(
                    "type" to "error",
                    "message" to "Screenshot capture failed. Is accessibility service active?"
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
        val apps = pm.getInstalledApplications(0).map { appInfo ->
            mapOf(
                "name" to (pm.getApplicationLabel(appInfo).toString()),
                "packageName" to appInfo.packageName
            )
        }.sortedBy { it["name"].toString().lowercase() }

        sendResponse(requestId, mapOf(
            "type" to "app_list",
            "apps" to apps
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
        ws.send(json)
    }

    // ─── Push Status Update (called by JarvisService) ───
    fun pushStatusUpdate(status: String, step: Int, action: String) {
        if (!isConnected.value) return
        val msg = mapOf(
            "type" to "status_update",
            "status" to status,
            "step" to step,
            "action" to action,
            "deviceId" to getDeviceId()
        )
        webSocket?.send(gson.toJson(msg))
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
}
