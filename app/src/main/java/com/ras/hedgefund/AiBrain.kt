package com.ras.hedgefund

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Multi-provider AI adapter. API credentials are read from encrypted Prefs storage. */
object AiBrain {
    private const val SYSTEM_PROMPT = """You are a conservative quantitative trading decision engine.
Trade only when Daily and H1 evidence is aligned. Prefer HOLD when signals conflict,
volume is weak, or volatility is abnormal.
Return ONLY valid JSON:
{"signal":"BUY|SELL|HOLD","confidence_score":0,"stop_loss":0.0,"take_profit":0.0,"logic":"brief indicator-based explanation"}"""

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
Return your decision as raw JSON only.""".trim()

    private fun extractJson(raw: String): JSONObject {
        var s = raw.trim()
        if (s.startsWith("```")) s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        require(start >= 0 && end > start) { "AI response did not contain JSON" }
        return JSONObject(s.substring(start, end + 1))
    }

    private data class Reply(val text: String, val input: Int, val output: Int, val total: Int)

    private fun callProvider(
        http: OkHttpClient, provider: String, key: String, model: String, baseUrl: String, prompt: String
    ): Reply {
        val p = provider.lowercase(Locale.US)
        val url = baseUrl.trim().trimEnd('/')
        require(url.startsWith("https://") || (p == "ollama" && url.startsWith("http://"))) { "API URL must use HTTPS (HTTP allowed only for local Ollama)" }
        val payload: JSONObject
        val request: Request.Builder
        when (p) {
            "gemini" -> {
                payload = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json"))
                val endpoint = if (url.contains(":generateContent")) url else "$url/v1beta/models/$model:generateContent"
                val full = endpoint + (if (endpoint.contains("?")) "&" else "?") + "key=" + URLEncoder.encode(key, "UTF-8")
                request = Request.Builder().url(full).header("Content-Type", "application/json")
            }
            "claude" -> {
                payload = JSONObject().put("model", model).put("max_tokens", 700).put("temperature", 0.1)
                    .put("system", SYSTEM_PROMPT)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                val endpoint = if (url.endsWith("/messages")) url else "$url/v1/messages"
                request = Request.Builder().url(endpoint).header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
            }
            "ollama" -> {
                payload = JSONObject().put("model", model).put("stream", false)
                    .put("format", "json")
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                val endpoint = if (url.endsWith("/api/chat")) url else "$url/api/chat"
                request = Request.Builder().url(endpoint)
            }
            else -> {
                payload = JSONObject().put("model", model).put("temperature", 0.1)
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                val endpoint = if (url.endsWith("/chat/completions")) url else "$url/chat/completions"
                request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            }
        }
        val req = request.post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val body = http.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("$p HTTP ${response.code}: ${text.take(180)}")
            JSONObject(text)
        }
        return when (p) {
            "gemini" -> {
                val content = body.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val usage = body.optJSONObject("usageMetadata") ?: JSONObject()
                Reply(content, usage.optInt("promptTokenCount"), usage.optInt("candidatesTokenCount"), usage.optInt("totalTokenCount"))
            }
            "claude" -> {
                val content = body.getJSONArray("content").getJSONObject(0).getString("text")
                val usage = body.optJSONObject("usage") ?: JSONObject()
                val input = usage.optInt("input_tokens"); val output = usage.optInt("output_tokens")
                Reply(content, input, output, input + output)
            }
            "ollama" -> {
                val content = body.getJSONObject("message").getString("content")
                val input = body.optInt("prompt_eval_count"); val output = body.optInt("eval_count")
                Reply(content, input, output, input + output)
            }
            else -> {
                val content = body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                val usage = body.optJSONObject("usage") ?: JSONObject()
                val input = usage.optInt("prompt_tokens"); val output = usage.optInt("completion_tokens")
                Reply(content, input, output, usage.optInt("total_tokens", input + output))
            }
        }
    }

    fun decide(
        http: OkHttpClient, prefs: Prefs, sym: String, bid: Double, ask: Double,
        equity: Double, h1: Stats?, d1: Stats?, threshold: Int,
        onUsage: (String, Int, Int, Int) -> Unit
    ): Decision {
        val hold = { why: String -> Decision("HOLD", 0, null, null, why) }
        val primary = prefs.str("ai_provider", "gemini").lowercase(Locale.US)
        val fallback = prefs.str("ai_fallback", "none").lowercase(Locale.US)
        val prompt = userPrompt(sym, bid, ask, equity, h1, d1)
        val providers = listOf(primary, fallback).filter { it != "none" && it.isNotBlank() }.distinct()
        if (providers.isEmpty()) return hold("No AI provider selected")
        var lastError = "AI request failed"
        for (provider in providers) {
            val keyName = "ai_key_$provider"
            val key = prefs.globalGet(keyName).ifBlank {
                if (provider == "deepseek") prefs.globalGet("deepseek") else ""
            }
            if (provider != "ollama" && key.isBlank()) {
                lastError = "$provider API key not configured"
                continue
            }
            val defaultUrl = when (provider) {
                "gemini" -> "https://generativelanguage.googleapis.com"
                "claude" -> "https://api.anthropic.com"
                "deepseek" -> "https://api.deepseek.com"
                "ollama" -> "http://127.0.0.1:11434"
                "openai" -> "https://api.openai.com/v1"
                "groq" -> "https://api.groq.com/openai/v1"
                "openrouter" -> "https://openrouter.ai/api/v1"
                else -> "https://api.openai.com/v1"
            }
            val modelDefault = when (provider) {
                "gemini" -> "gemini-3.8-flash"
                "claude" -> "claude-3-5-haiku-latest"
                "deepseek" -> "deepseek-chat"
                "ollama" -> "qwen2.5:3b"
                "openai" -> "gpt-4o-mini"
                "groq" -> "llama-3.3-70b-versatile"
                "openrouter" -> "google/gemini-3.8-flash"
                else -> "gpt-4o-mini"
            }
            val url = prefs.str("ai_url_$provider", defaultUrl)
            val savedModel = prefs.str("ai_model_$provider", modelDefault)
            val model = if (provider == "gemini" && savedModel == "gemini-2.5-flash") {
                prefs.put("ai_model_gemini", "gemini-3.8-flash")
                "gemini-3.8-flash"
            } else savedModel
            try {
                val reply = callProvider(http, provider, key, model, url, prompt)
                onUsage(provider, reply.input, reply.output, reply.total)
                val d = extractJson(reply.text)
                var signal = d.optString("signal", "HOLD").uppercase(Locale.US)
                if (signal !in listOf("BUY", "SELL", "HOLD")) signal = "HOLD"
                val conf = d.optInt("confidence_score", 0).coerceIn(0, 100)
                var sl = d.opt("stop_loss")?.toString()?.toDoubleOrNull()
                var tp = d.opt("take_profit")?.toString()?.toDoubleOrNull()
                val price = if (signal == "BUY") ask else bid
                if (signal != "HOLD") {
                    val valid = if (signal == "BUY") (sl ?: price) < price && price < (tp ?: price)
                        else (tp ?: price) < price && price < (sl ?: price)
                    if (!valid) {
                        val atr = h1?.atr ?: return hold("AI returned invalid stops and H1 ATR is unavailable")
                        if (atr <= 0.0) return hold("AI returned invalid stops")
                        if (signal == "BUY") { sl = price - 1.5 * atr; tp = price + 3.0 * atr }
                        else { sl = price + 1.5 * atr; tp = price - 3.0 * atr }
                    }
                }
                if (conf < threshold) signal = "HOLD"
                return Decision(signal, conf, sl, tp, "[$provider] " + d.optString("logic", ""))
            } catch (e: Exception) {
                lastError = "$provider error: ${e.message}"
            }
        }
        return hold(lastError)
    }
}
