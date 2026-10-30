package com.ras.hedgefund

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

fun parseIso(s: String?): Long = try {
    OffsetDateTime.parse(s).toInstant().toEpochMilli()
} catch (_: Exception) { System.currentTimeMillis() }

class ExnClient(private val c: Creds, private val http: OkHttpClient) {
    private var base: String? = null
    private val json = "application/json".toMediaType()
    val accountId: String get() = c.accountId

    fun host(): String {
        base?.let { return it }
        val body = call("GET", c.gateway, "/v1/trading/access-point", mapOf("account_id" to c.accountId))
        val ap = JSONObject(body).getString("access_point").trim().trimEnd('/')
        base = if (ap.startsWith("http")) ap else "https://$ap"
        return base!!
    }

    private fun call(method: String, baseUrl: String, path: String, query: Map<String,String> = emptyMap(), body: String? = null, idem: String = ""): String {
        val ub = baseUrl.toHttpUrl().newBuilder().encodedPath(path)
        query.forEach { (k,v) -> ub.addQueryParameter(k,v) }
        val url = ub.build()
        val signedPath = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val rb = Request.Builder().url(url)
        ExnAuth.headers(c.apiKey,c.seed,method,signedPath,idem,bytes).forEach { (k,v) -> rb.header(k,v) }
        when(method) {
            "GET" -> rb.get()
            "DELETE" -> rb.delete()
            "POST" -> rb.post((body ?: "").toRequestBody(json))
            "PATCH" -> rb.patch((body ?: "").toRequestBody(json))
            else -> error("Unsupported HTTP method")
        }
        return http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code,"HTTP ${r.code}: ${text.take(300)}")
            text
        }
    }

    private fun acc() = "/${c.accountId}"
    private fun num(o: JSONObject,k: String) = o.opt(k)?.toString()?.toDoubleOrNull() ?: 0.0

    fun accountDetails() = JSONObject(call("GET",host(),"/v1/configuration/accounts${acc()}/account"))
    fun instruments(): List<String> {
        val a = JSONObject(call("GET",host(),"/v1/configuration/accounts${acc()}/instruments")).optJSONArray("instruments") ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }
    fun conditions(inst: String): Conditions {
        val o = JSONObject(call("GET",host(),"/v1/configuration/accounts${acc()}/instruments/$inst/conditions"))
        val step = o.opt("volume_step")?.toString() ?: "0.01"
        return Conditions(inst,o.optString("base_currency"),o.optString("quote_currency"),o.optInt("point_digits",2),
            num(o,"contract_size"),num(o,"volume_min"),num(o,"volume_max"),step.toDoubleOrNull() ?: 0.01,step,o.optString("trade_mode"))
    }
    fun snapshot(): Snapshot {
        val o = JSONObject(call("GET",host(),"/v1/trading/accounts${acc()}/snapshot"))
        val st = o.getJSONObject("account_state")
        val a = o.optJSONArray("positions")
        val ps = mutableListOf<Pos>()
        if (a != null) for (i in 0 until a.length()) {
            val p = a.getJSONObject(i)
            if (p.optString("state") == "open") ps.add(Pos(p.getString("position_id"),p.optString("instrument"),p.optString("direction"),p.opt("volume").toString(),p.opt("open_price").toString()))
        }
        return Snapshot(num(st,"balance"),num(st,"equity"),ps)
    }
    fun candles(inst: String, tf: String, n: Int): List<Candle> {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        fun parse(s: String): List<Candle> {
            val a = JSONObject(s).optJSONArray("candles") ?: return emptyList()
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Candle(parseIso(o.optString("time")),num(o,"open"),num(o,"high"),num(o,"low"),num(o,"close"),num(o,"volume"))
            }
        }
        val path = "/v1/market-data/accounts${acc()}/candles"
        val q = mapOf("instrument" to inst,"timeframe" to tf,"price_type" to "bid")
        val first = try { parse(call("GET",host(),path,q + mapOf("from" to now.toString(),"count" to (-n).toString()))) } catch (_: ApiException) { emptyList() }
        if (first.size >= minOf(n,50)) return first.takeLast(n)
        val sec = TF_SECONDS[tf] ?: 3600L
        return parse(call("GET",host(),path,q + mapOf("from" to now.minusSeconds((sec*n*1.5).toLong()).toString(),"to" to now.plusSeconds(sec).toString())).takeLast(n)
    }
    private fun newIdem() = "hf-" + UUID.randomUUID()
    fun openPosition(inst: String,side: String,volume: String,sl: String,tp: String): String {
        val b = JSONObject().put("instrument",inst).put("side",side.lowercase()).put("volume",volume)
            .put("stop_loss_price",sl).put("take_profit_price",tp).put("comment","HF Mobile").toString()
        return JSONObject(call("POST",host(),"/v1/trading/accounts${acc()}/positions",body=b,idem=newIdem())).optString("operation_id")
    }
    fun closePosition(id: String): String = JSONObject(call("DELETE",host(),"/v1/trading/accounts${acc()}/positions/$id",idem=newIdem())).optString("operation_id")
    fun operation(id: String): OpResult {
        val o = JSONObject(call("GET",host(),"/v1/trading/accounts${acc()}/operations/$id"))
        return OpResult(o.optString("status"),o.optString("error_message").ifBlank { null })
    }
    fun wsRequest(path: String): Request {
        val rb = Request.Builder().url(host()+path)
        ExnAuth.headers(c.apiKey,c.seed,"GET",path,"",ByteArray(0)).forEach { (k,v) -> rb.header(k,v) }
        return rb.build()
    }
    companion object { val TF_SECONDS = mapOf("M1" to 60L,"M5" to 300L,"M15" to 900L,"M30" to 1800L,"H1" to 3600L,"H4" to 14400L,"D1" to 86400L) }
}
