package com.ras.hedgefund

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

data class UiState(
    val running: Boolean = false,
    val live: Boolean = false,
    val balance: Double = 0.0,
    val equity: Double = 0.0,
    val positions: List<Pos> = emptyList(),
    val ddPct: Double = 0.0,
    val halted: Boolean = false,
    val lastDecision: String = "-",
    val log: List<String> = emptyList()
)

object Engine {
    val state = MutableStateFlow(UiState())
    lateinit var prefs: Prefs
        private set
    private lateinit var appCtx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var job: Job? = null
    private var ticks: TickStream? = null
    private var client: ExnClient? = null
    private val spreadHistory = HashMap<String, ArrayDeque<Double>>()
    private val conditions = HashMap<String, Conditions>()
    private var accountCurrency = ""

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appCtx = context.applicationContext
        prefs = Prefs(appCtx)
        state.update { it.copy(live = prefs.live) }
    }

    fun setLive(value: Boolean) {
        if (job?.isActive == true) return
        prefs.live = value
        state.update { it.copy(live = value, balance = 0.0, equity = 0.0, positions = emptyList()) }
    }

    fun log(message: String) {
        val stamp = LocalTime.now().withNano(0).toString()
        state.update { it.copy(log = (it.log + "$stamp $message").takeLast(200)) }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        state.update { it.copy(running = true, live = prefs.live) }
        log("Starting ${if (prefs.live) "LIVE" else "DEMO"} account")
        job = scope.launch {
            try {
                runLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Bot stopped: ${e.message}")
            } finally {
                ticks?.stop()
                ticks = null
                state.update { it.copy(running = false) }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        ticks?.stop()
        ticks = null
        state.update { it.copy(running = false) }
        log("Bot stopped")
    }

    fun closePosition(id: String) {
        scope.launch {
            try {
                val cl = client ?: prefs.credentials()?.let { ExnClient(it, http) }
                    ?: throw IllegalStateException("Credentials are incomplete")
                val result = awaitOperation(cl, cl.closePosition(id))
                log("Close $id: ${result.status} ${result.error.orEmpty()}")
                refresh(cl)
            } catch (e: Exception) {
                log("Close failed: ${e.message}")
            }
        }
    }

    fun testConnection() {
        scope.launch {
            try {
                val creds = prefs.credentials() ?: throw IllegalStateException("Credentials are incomplete")
                val cl = ExnClient(creds, http)
                log("Access point: ${cl.host()}")
                val details = cl.accountDetails()
                val settings = details.optJSONObject("settings")
                accountCurrency = settings?.optString("currency").orEmpty()
                log("Account ${details.optString("account_id")} currency=$accountCurrency")
                val available = cl.instruments()
                log("Available instruments: ${available.size}")
                Cfg.from(prefs).instruments.forEach { symbol ->
                    log("$symbol: ${if (available.any { it.equals(symbol, true) }) "available" else "not found"}")
                }
                val snapshot = cl.snapshot()
                log("Connection OK. Equity=${snapshot.equity}, open positions=${snapshot.positions.size}")
            } catch (e: Exception) {
                log("Connection test failed: ${e.message}")
            }
        }
    }

    private suspend fun runLoop() {
        val creds = prefs.credentials() ?: throw IllegalStateException("Set account credentials in Settings")
        val cl = ExnClient(creds, http)
        client = cl
        log("Resolving Exness access point...")
        cl.host()
        accountCurrency = try {
            cl.accountDetails().optJSONObject("settings")?.optString("currency").orEmpty()
        } catch (_: Exception) { "" }
        log("Account currency: ${accountCurrency.ifBlank { "unknown" }}")
        val cfg = Cfg.from(prefs)
        ticks = TickStream(cl, http, cfg.instruments, ::log).also { it.start() }
        delay(3000)
        while (true) {
            coroutineContext.ensureActive()
            val current = Cfg.from(prefs)
            try {
                val snapshot = refresh(cl)
                if (!drawdownExceeded(snapshot.equity, current)) {
                    for (symbol in current.instruments) {
                        coroutineContext.ensureActive()
                        try {
                            evaluateSymbol(cl, current, symbol)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("$symbol error: ${e.message}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Scan error: ${e.message}")
            }
            delay(current.intervalSec * 1000L)
        }
    }

    private fun refresh(cl: ExnClient): Snapshot {
        val snapshot = cl.snapshot()
        state.update {
            it.copy(balance = snapshot.balance, equity = snapshot.equity, positions = snapshot.positions)
        }
        return snapshot
    }

    private fun drawdownExceeded(equity: Double, cfg: Cfg): Boolean {
        if (cfg.maxDd <= 0.0) {
            state.update { it.copy(ddPct = 0.0, halted = false) }
            return false
        }
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        if (prefs.pStr("dd_day") != today) {
            prefs.pPut("dd_day", today)
            prefs.pPut("dd_start", equity.toString())
            prefs.pPut("dd_halt", "0")
            state.update { it.copy(ddPct = 0.0, halted = false) }
            log("Daily equity baseline set: $equity")
            return false
        }
        val baseline = prefs.pStr("dd_start").toDoubleOrNull() ?: equity
        if (baseline <= 0.0) return false
        val dd = max(0.0, (baseline - equity) / baseline * 100.0)
        var halted = prefs.pStr("dd_halt") == "1"
        if (dd >= cfg.maxDd && !halted) {
            halted = true
            prefs.pPut("dd_halt", "1")
            log("DAILY LOSS LIMIT reached: ${"%.2f".format(dd)}%. New orders disabled until next UTC day.")
        }
        state.update { it.copy(ddPct = dd, halted = halted) }
        return halted
    }

    private fun spreadAllowed(symbol: String, spread: Double, cfg: Cfg): Boolean {
        val history = spreadHistory.getOrPut(symbol) { ArrayDeque() }
        var allowed = true
        if (history.size >= cfg.spreadMin) {
            val average = history.average()
            if (average > 0.0 && spread > average * cfg.spreadMult) {
                log("$symbol spread filter: current spread is unusually high")
                allowed = false
            }
        }
        history.addLast(spread)
        if (history.size > cfg.spreadHist) history.removeFirst()
        return allowed
    }

    private suspend fun evaluateSymbol(cl: ExnClient, cfg: Cfg, symbol: String) {
        val tick = ticks?.latest(symbol)
        if (tick == null) {
            log("$symbol: waiting for live tick")
            return
        }
        val ageSeconds = (System.currentTimeMillis() - tick.tsMs) / 1000
        if (ageSeconds > cfg.tickAge) {
            log("$symbol: stale tick ($ageSeconds seconds), skipping")
            return
        }
        val spec = conditions.getOrPut(symbol) { cl.conditions(symbol) }
        if (spec.tradeMode == "trading_disabled") {
            log("$symbol: trading is disabled")
            return
        }
        if (!spreadAllowed(symbol, tick.ask - tick.bid, cfg)) return

        val h1 = Indicators.compute(cl.candles(symbol, "H1", 250))
        val d1 = Indicators.compute(cl.candles(symbol, "D1", 250))
        if (h1 == null || d1 == null) {
            log("$symbol: insufficient candle data")
            return
        }
        val decision = AiBrain.decide(
            http, prefs.globalGet("deepseek"), symbol, tick.bid, tick.ask,
            state.value.equity, h1, d1, cfg.conf
        )
        state.update {
            it.copy(lastDecision = "$symbol ${decision.signal} (${decision.confidence}) ${decision.logic}")
        }
        log("AI $symbol: ${decision.signal}, confidence ${decision.confidence}; ${decision.logic.take(140)}")
        if (decision.signal !in listOf("BUY", "SELL")) return
        val stop = decision.sl ?: return
        val target = decision.tp ?: return

        val snapshot = refresh(cl)
        val sameSymbol = snapshot.positions.filter { it.instrument.equals(symbol, true) }
        val side = decision.signal.lowercase(Locale.US)
        if (sameSymbol.any { it.side.equals(side, true) }) {
            log("$symbol: same-direction position already exists")
            return
        }
        val opposite = sameSymbol.firstOrNull()
        if (opposite != null) {
            log("$symbol: closing opposite position before reversal")
            val closeResult = awaitOperation(cl, cl.closePosition(opposite.id))
            if (closeResult.status != "confirmed") {
                log("$symbol: close not confirmed; entry cancelled")
                return
            }
        }
        if (cfg.maxPos > 0 && snapshot.positions.size >= cfg.maxPos && opposite == null) {
            log("Maximum open-position limit reached")
            return
        }

        val entry = if (decision.signal == "BUY") tick.ask else tick.bid
        val volume = calculateVolume(snapshot.equity, cfg.risk, entry, stop, spec)
        if (volume == null) {
            log("$symbol: unable to calculate safe volume for account currency $accountCurrency")
            return
        }
        val volumeText = formatVolume(volume, spec.volStepStr)
        val slText = String.format(Locale.US, "%.${spec.digits}f", stop)
        val tpText = String.format(Locale.US, "%.${spec.digits}f", target)
        log("$symbol: submitting ${decision.signal} $volumeText with SL=$slText TP=$tpText")
        val result = awaitOperation(cl, cl.openPosition(symbol, decision.signal, volumeText, slText, tpText))
        log("$symbol order: ${result.status} ${result.error.orEmpty()}")
        if (result.status == "confirmed") {
            Journal.append(appCtx, JSONObject()
                .put("time", System.currentTimeMillis())
                .put("instrument", symbol)
                .put("side", decision.signal)
                .put("volume", volumeText)
                .put("entry", entry)
                .put("stop_loss", slText)
                .put("take_profit", tpText)
                .put("confidence", decision.confidence)
                .put("logic", decision.logic)
                .put("live", prefs.live))
            refresh(cl)
        }
    }

    private suspend fun awaitOperation(cl: ExnClient, id: String): OpResult {
        if (id.isBlank()) return OpResult("unknown", "API did not return operation_id")
        repeat(20) {
            delay(1000)
            val result = try {
                cl.operation(id)
            } catch (e: ApiException) {
                OpResult("pending", e.message)
            }
            if (result.status != "pending") return result
        }
        return OpResult("pending", "Timed out waiting for operation confirmation")
    }

    private fun calculateVolume(
        equity: Double, riskPercent: Double, entry: Double, stop: Double, spec: Conditions
    ): Double? {
        val distance = abs(entry - stop)
        if (distance <= 0.0 || spec.contractSize <= 0.0 || spec.volStep <= 0.0) return null
        val currency = accountCurrency.uppercase(Locale.US)
        val conversion = when {
            currency.isBlank() -> return null
            spec.quote.uppercase(Locale.US) == currency -> 1.0
            spec.base.uppercase(Locale.US) == currency -> 1.0 / entry
            currency == "USC" && spec.quote.equals("USD", true) -> 100.0
            currency == "USC" && spec.base.equals("USD", true) -> 100.0 / entry
            else -> return null
        }
        val lossPerLot = distance * spec.contractSize * conversion
        if (lossPerLot <= 0.0) return null
        val raw = (equity * riskPercent / 100.0) / lossPerLot
        val stepped = floor(raw / spec.volStep) * spec.volStep
        if (stepped < spec.volMin) return null
        return stepped.coerceAtMost(spec.volMax)
    }

    private fun formatVolume(value: Double, step: String): String {
        val decimals = BigDecimal(step).stripTrailingZeros().scale().coerceAtLeast(0)
        return BigDecimal(value).setScale(decimals, RoundingMode.DOWN).toPlainString()
    }
}
