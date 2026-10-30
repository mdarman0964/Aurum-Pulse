package com.ras.hedgefund

import android.content.Context

/** Plain settings in SharedPreferences; every credential goes through SecureStore (Keystore AES-GCM). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure = SecureStore(ctx.applicationContext)

    var live: Boolean
        get() = sp.getBoolean("live", false)
        set(v) { sp.edit().putBoolean("live", v).apply() }

    fun str(k: String, d: String): String = sp.getString(k, d) ?: d
    fun put(k: String, v: String) { sp.edit().putString(k, v).apply() }
    fun dbl(k: String, d: Double): Double = str(k, d.toString()).toDoubleOrNull() ?: d
    fun int(k: String, d: Int): Int = str(k, d.toString()).toIntOrNull() ?: d

    private fun pk(k: String) = (if (live) "live_" else "demo_") + k
    fun profileGet(k: String): String = secure.get(pk(k))
    fun profilePut(k: String, v: String) = secure.put(pk(k), v)
    fun globalGet(k: String): String = secure.get(k)
    fun globalPut(k: String, v: String) = secure.put(k, v)

    /** Per-profile (demo/live) non-secret state keyed by profile, e.g. daily drawdown baseline. */
    fun pStr(k: String, d: String = "") = str(pk(k), d)
    fun pPut(k: String, v: String) = put(pk(k), v)

    fun credentials(): Creds? {
        val id = profileGet("account").trim()
        val key = profileGet("apikey").trim()
        val pem = profileGet("privkey")
        val gw = profileGet("gateway").trim().trimEnd('/')
        if (id.isBlank() || key.isBlank() || pem.isBlank() || gw.isBlank()) return null
        return Creds(id, key, ExnAuth.parseSeed(pem), gw)
    }
}

data class Cfg(
    val instruments: List<String>, val risk: Double, val intervalSec: Int, val conf: Int,
    val maxDd: Double, val spreadMult: Double, val spreadHist: Int, val spreadMin: Int,
    val maxPos: Int, val tickAge: Int
) {
    companion object {
        fun from(p: Prefs) = Cfg(
            instruments = p.str("instruments", "XAUUSD").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            risk = p.dbl("risk", 1.0),
            intervalSec = p.int("interval", 30).coerceAtLeast(10),
            conf = p.int("conf", 65),
            maxDd = p.dbl("maxdd", 30.0),
            spreadMult = p.dbl("spreadmult", 3.0),
            spreadHist = 20,
            spreadMin = 5,
            maxPos = p.int("maxpos", 0),
            tickAge = p.int("tickage", 120)
        )
    }
}
