package com.deriv.parimpar

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DerivWSClient(
    private val url: String,
    private val name: String,
    private val onMessage: (String) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket keep-alive
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isConnected = false
    private var stopFlag = false
    private val reqIdCounter = AtomicInteger(0)

    // Flow para notificar reconexões
    private val _connectionState = MutableSharedFlow<Boolean>()
    val connectionState = _connectionState.asSharedFlow()

    fun connect() {
        stopFlag = false
        if (isConnected) return

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                scope.launch { _connectionState.emit(true) }
                android.util.Log.i("DerivWS", "[$name] CONECTADO")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopFlag) return
                android.util.Log.d("DerivWS", "[$name] RECV: ${text.take(150)}...")
                try {
                    onMessage(text)
                } catch (e: Exception) {
                    android.util.Log.e("DerivWS", "[$name] Erro no callback: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                isConnected = false
                println("[$name] Fechando: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                println("[$name] Falha de conexão: ${t.message}")
                scope.launch {
                    _connectionState.emit(false)
                    reconnect()
                }
            }
        })
    }

    private suspend fun reconnect() {
        if (stopFlag) return
        delay(2000) // Backoff simples inicial
        println("[$name] Tentando reconectar...")
        connect()
    }

    fun send(json: String) {
        if (!isConnected || webSocket == null) {
            android.util.Log.w("DerivWS", "[$name] SEND DESCARTADO (não conectado): ${json.take(80)}...")
            return
        }
        android.util.Log.d("DerivWS", "[$name] SEND: ${json.take(100)}...")
        webSocket?.send(json)
    }

    fun stop() {
        stopFlag = true
        webSocket?.close(1000, "Parando bot")
        webSocket = null
        isConnected = false
        scope.cancel()
    }

    // Utilitário para gerar req_id único
    fun nextReqId(): Int = reqIdCounter.incrementAndGet()
}
