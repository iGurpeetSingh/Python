package com.orbit.agent

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class GuriCloudSocket {
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var baseUrl: String = ""
    private var pairingCode: String = ""
    private var statusCallback: ((String) -> Unit)? = null
    private var commandCallback: ((String, String) -> Unit)? = null
    private var retryAttempt = 0
    private var closedByUser = false
    private var heartbeatTask: Runnable? = null

    @Synchronized
    fun connect(
        serverUrl: String,
        code: String,
        onStatus: (String) -> Unit,
        onCommand: (String, String) -> Unit
    ) {
        baseUrl = serverUrl.trim().trimEnd('/')
        pairingCode = code.trim()
        statusCallback = onStatus
        commandCallback = onCommand
        closedByUser = false
        openSocket()
    }

    private fun websocketAddress(): String {
        val uri = URI(baseUrl)
        require(uri.host != null) { "Guri Cloud URL गलत है।" }
        val scheme = when (uri.scheme?.lowercase()) {
            "https" -> "wss"
            "http" -> "ws"
            "wss" -> "wss"
            "ws" -> "ws"
            else -> throw IllegalArgumentException("URL https:// से शुरू होना चाहिए।")
        }
        return scheme + "://" + uri.rawAuthority + "/ws/device/android"
    }

    @Synchronized
    private fun openSocket() {
        if (closedByUser || baseUrl.isBlank() || pairingCode.isBlank()) return
        try {
            socket?.cancel()
            val request = Request.Builder()
                .url(websocketAddress())
                .header("Authorization", "Bearer " + pairingCode)
                .build()

            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    retryAttempt = 0
                    statusCallback?.invoke("Guri Cloud से Android connected")
                    startHeartbeat(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val packet = JSONObject(text)
                        when (packet.optString("type")) {
                            "connected" -> statusCallback?.invoke("Guri Cloud से Android connected")
                            "command" -> {
                                val id = packet.optString("id")
                                val command = packet.optString("command")
                                commandCallback?.invoke(id, command)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    stopHeartbeat()
                    scheduleReconnect("Cloud connection बंद हुई: " + reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    stopHeartbeat()
                    scheduleReconnect("Cloud connection नहीं बनी: " + (t.localizedMessage ?: "network error"))
                }
            })
        } catch (error: Exception) {
            statusCallback?.invoke("Cloud URL error: " + (error.localizedMessage ?: "invalid URL"))
            scheduleReconnect("Cloud URL जाँचें")
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        stopHeartbeat()
        val task = object : Runnable {
            override fun run() {
                if (closedByUser) return
                val sent = webSocket.send(JSONObject().put("type", "heartbeat").toString())
                if (!sent) {
                    scheduleReconnect("Cloud heartbeat failed")
                    return
                }
                handler.postDelayed(this, 25000)
            }
        }
        heartbeatTask = task
        handler.postDelayed(task, 25000)
    }

    private fun stopHeartbeat() {
        heartbeatTask?.let { handler.removeCallbacks(it) }
        heartbeatTask = null
    }

    private fun scheduleReconnect(reason: String) {
        if (closedByUser) return
        statusCallback?.invoke(reason + " — reconnect कर रही हूँ…")
        val delay = (2000L shl retryAttempt.coerceAtMost(4)).coerceAtMost(30000L)
        retryAttempt += 1
        handler.postDelayed({ openSocket() }, delay)
    }

    fun sendResult(id: String, result: JSONObject) {
        val packet = JSONObject()
            .put("type", "result")
            .put("id", id)
            .put("result", result)
        val accepted = socket?.send(packet.toString()) ?: false
        if (!accepted) statusCallback?.invoke("Cloud पर result भेज नहीं पाया।")
    }

    @Synchronized
    fun close() {
        closedByUser = true
        stopHeartbeat()
        socket?.close(1000, "Guri app closed")
        socket = null
        handler.removeCallbacksAndMessages(null)
        client.dispatcher.executorService.shutdown()
    }
}
