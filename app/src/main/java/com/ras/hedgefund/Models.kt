package com.ras.hedgefund

class Creds(val accountId: String, val apiKey: String, val seed: ByteArray, val gateway: String)

data class Tick(val instrument: String, val bid: Double, val ask: Double, val tsMs: Long)
data class Candle(val timeMs: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
data class Pos(val id: String, val instrument: String, val side: String, val volume: String, val openPrice: String)
data class Snapshot(val balance: Double, val equity: Double, val positions: List<Pos>)
data class Conditions(
    val instrument: String, val base: String, val quote: String, val digits: Int,
    val contractSize: Double, val volMin: Double, val volMax: Double, val volStep: Double,
    val volStepStr: String, val tradeMode: String
)
data class Stats(
    val ema200: Double?, val rsi: Double?, val atr: Double?, val relVol: Double?,
    val close: Double?, val trend: String
)
data class Decision(val signal: String, val confidence: Int, val sl: Double?, val tp: Double?, val logic: String)
data class OpResult(val status: String, val error: String?)

class ApiException(val code: Int, message: String) : Exception(message)
