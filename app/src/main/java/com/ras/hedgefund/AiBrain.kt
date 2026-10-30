package com.ras.hedgefund

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Port of ai_brain.py: DeepSeek strict-JSON decision with stop validation and confidence gate. */
object AiBrain {
    private const val SYSTEM_PROMPT = """You are the Head Quantitative Trader of a strict, risk-managed
autonomous hedge fund. You trade only when technical evidence is strong across
both the Daily and H1 timeframes. You are conservative: when signals conflict,
volume is weak, or volatility is abnormal, you prefer HOLD.

You must respond with RAW JSON ONLY, no markdown formatting, no commentary,
no code fences, exactly in this shape:
{"signal": "BUY" | "SELL" | "HOLD", "confidence_score": <integer 0-100>,
"stop_loss": <float>, "take_profit": <float>,
"logic": "<concise explanation referencing the indicators>"}"""

    private fun f(v: Double?) = if (v == null) "None" else String.format(Locale.US, "%.5f", v)

    private fun block(name: String, s: Stats?) = """
$name:
- EMA(200): ${f(s?.ema200)}
- RSI(14): ${f(s?.rsi)}
- ATR(14): ${f(s?.atr)}
- Relative Volume: ${f(s?.relVol)}
- Trend Structure: ${s?.trend}"""

    private fun userPrompt(sym: String, bid: Double, ask: Double, equity: Double, h1: Stats?, d1: Stats?) = """
SYMBOL: $sym
BID: $bid
ASK: $ask
SPREAD: ${f(ask - bid)}
ACCOUNT EQUITY: $equity
${block("DAILY TIMEFRAME", d1)}
${block("H1 TIMEFRAME", h1)}

No active learned risk rules for this symbol.

Return your decision now as raw JSON only.""".trim()

    private fun fixStops(signal: String, price: Double, atr: Double?, sl: Double, tp: Double): Pair<Double, Double> {
        val valid = if (signal == "BUY") sl < price && price < tp else tp < price && price < sl
        if (valid || atr == null || atr <= 0) return sl to tp
        return if (signal == "BUY") (price - 1.5 * atr) to (price + 3.0 * atr)
        else (price + 1.5 * atr) to (price - 3.0 * atr)
    }

    fun decide(
        http: OkHttpClient, apiKey: String, sym: String, bid: Double, ask: Double,
        equity: Double, h1: Stats?, d1: Stats?, threshold: Int
    ): Decision {
        val hold = { why: String -> Decision("HOLD", 0, null, null, why) }
        if (apiKey.isBlank()) return hold("DeepSeek API key not configured")
        try {
            val payload = JSONObject()
                .put("model", "deepseek-chat").put("temperature", 0.1)
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(JSONObject().put("role", "user").put("content", userPrompt(sym, bid, ask, equity, h1, d1)))
                )
            val req = Request.Builder().url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            val text = http.newCall(req).execute().use { r ->
                val b = r.body?.string().orEmpty()
                if (!r.isSuccessful) return hold("DeepSeek HTTP ${r.code}: ${b.take(120)}")
                b
            }
            var content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            if (content.startsWith("```")) {
                content = content.trim('`').trim()
                if (content.lowercase().startsWith("json")) content = content.substring(4).trim()
            }
            val d = JSONObject(content)
            var signal = d.optString("signal", "HOLD").uppercase()
            if (signal !in listOf("BUY", "SELL", "HOLD")) signal = "HOLD"
            val conf = d.optInt("confidence_score", 0).coerceIn(0, 100)
            var sl = d.opt("stop_loss")?.toString()?.toDoubleOrNull()
            var tp = d.opt("take_profit")?.toString()?.toDoubleOrNull()
            val price = if (signal == "BUY") ask else bid
            if (signal != "HOLD") {
                val fixed = fixStops(signal, price, h1?.atr, sl ?: price, tp ?: price)
                sl = fixed.first; tp = fixed.second
            }
            if (conf < threshold) signal = "HOLD"
            return Decision(signal, conf, sl, tp, d.optString("logic", ""))
        } catch (e: Exception) {
            return hold("DeepSeek error: ${e.message}")
        }
    }
}
