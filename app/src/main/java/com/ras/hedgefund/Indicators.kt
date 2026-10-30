package com.ras.hedgefund

import kotlin.math.abs
import kotlin.math.max

/** Port of data_engine.py: EMA(200), Wilder RSI(14), ATR(14), relative volume, trend structure. */
object Indicators {
    fun compute(c: List<Candle>): Stats? {
        if (c.size < 5) return null
        val close = c.map { it.close }
        val ema = ema(close, 200)
        val rsi = rsi(close, 14)
        val atr = atr(c, 14)
        val vols = c.map { it.volume }
        val relVol = if (vols.size >= 20) {
            val ma = vols.takeLast(20).average()
            if (ma > 0) vols.last() / ma else null
        } else null
        val recent = close.takeLast(10)
        var trend = "sideways"
        if (recent.size >= 2 && recent.first() != 0.0) {
            val pct = (recent.last() - recent.first()) / recent.first() * 100
            if (pct > 0.15) trend = "uptrend" else if (pct < -0.15) trend = "downtrend"
        }
        return Stats(ema, rsi, atr, relVol, close.last(), trend)
    }

    private fun ema(v: List<Double>, span: Int): Double {
        val a = 2.0 / (span + 1)
        var e = v[0]
        for (i in 1 until v.size) e = a * v[i] + (1 - a) * e
        return e
    }

    private fun rsi(v: List<Double>, p: Int): Double? {
        if (v.size <= p) return null
        val a = 1.0 / p
        var ag = max(v[1] - v[0], 0.0)
        var al = max(v[0] - v[1], 0.0)
        for (i in 2 until v.size) {
            val d = v[i] - v[i - 1]
            ag = a * max(d, 0.0) + (1 - a) * ag
            al = a * max(-d, 0.0) + (1 - a) * al
        }
        val rs = ag / (if (al == 0.0) 1e-10 else al)
        return 100 - 100 / (1 + rs)
    }

    private fun atr(c: List<Candle>, p: Int): Double? {
        if (c.size <= p) return null
        val a = 1.0 / p
        var e = c[0].high - c[0].low
        for (i in 1 until c.size) {
            val pc = c[i - 1].close
            val tr = max(c[i].high - c[i].low, max(abs(c[i].high - pc), abs(c[i].low - pc)))
            e = a * tr + (1 - a) * e
        }
        return e
    }
}
