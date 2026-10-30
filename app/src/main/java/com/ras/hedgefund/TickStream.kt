package com.ras.hedgefund

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** Live bid/ask over /ws/ticks with signed handshake and automatic reconnect + resubscribe. */
class TickStream(
    private val cl: ExnClient,
    private val http: OkHttpClient,
    private val instruments: List<String>,
    private val log: (String) -> Unit
) {
    private val latest = ConcurrentHashMap<String, Tick>()
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var attempt = 0
    @Volatile private var oddMsgs = 0

    fun latest(inst: String): Tick? = latest[inst.uppercase()]

    fun start() { stopped = false; connect() }

    fun stop() { stopped = true; ws?.close(1000, "bye"); ws = null }

    private fun connect() {
        if (stopped) return
        try {
            val req = cl.wsRequest("/v1/server-events/accounts/${cl.accountId}/ws/ticks")
            ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    val sub = JSONObject()
                        .put("id", "ticks-1")
                        .put("subscribe", JSONObject().put("event", "ticks").put("instruments", JSONArray(instruments)))
                    webSocket.send(sub.toString())
                    log("Ticks connected: ${instruments.joinToString()}")
                }

                override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retry("closed $code $reason") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    retry("${t.message} ${response?.code ?: ""}")
                }
            })
        } catch (e: Exception) {
            retry(e.message ?: "connect error")
        }
    }

    private fun handle(text: String) {
        try {
            val o = JSONObject(text)
            if (o.has("bid") && o.has("ask")) {
                val inst = o.optString("instrument")
                val bid = o.opt("bid").toString().toDouble()
                val ask = o.opt("ask").toString().toDouble()
                latest[inst.uppercase()] = Tick(inst, bid, ask, parseIso(o.optString("timestamp")))
            } else if (oddMsgs < 4) {
                oddMsgs++
                log("ws msg: ${text.take(200)}")
            }
        } catch (e: Exception) {
            if (oddMsgs < 4) { oddMsgs++; log("ws parse: ${text.take(120)}") }
        }
    }

    private fun retry(why: String) {
        if (stopped) return
        attempt++
        val wait = min(60, 1 shl min(attempt, 6))
        log("Ticks reconnect in ${wait}s ($why)")
        scope.launch { delay(wait * 1000L); connect() }
    }
}
