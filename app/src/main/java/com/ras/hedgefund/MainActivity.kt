package com.ras.hedgefund

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Dashboard", "Settings", "Log").forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) { 0 -> Dashboard(); 1 -> SettingsScreen(); else -> LogScreen() }
        }
    }
}

private fun sendService(ctx: Context, action: String) {
    val i = Intent(ctx, TradingService::class.java).setAction(action)
    if (action == "START") ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
}

@Composable
fun Dashboard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s by Engine.state.collectAsState()
    var confirmLive by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (s.live) "LIVE ACCOUNT" else "DEMO ACCOUNT",
            color = if (s.live) Color(0xFFFF5252) else Color(0xFF66BB6A),
            style = MaterialTheme.typography.titleLarge
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Use live account")
            Switch(checked = s.live, enabled = !s.running, onCheckedChange = { want ->
                if (want) confirmLive = true else Engine.setLive(false)
            })
        }
        if (confirmLive) AlertDialog(
            onDismissRequest = { confirmLive = false },
            title = { Text("Switch to LIVE?") },
            text = { Text("The bot will trade real money on the live account. Test on demo first.") },
            confirmButton = { TextButton(onClick = { Engine.setLive(true); confirmLive = false }) { Text("Yes, live") } },
            dismissButton = { TextButton(onClick = { confirmLive = false }) { Text("Cancel") } }
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { sendService(ctx, if (s.running) "STOP" else "START") }
        ) { Text(if (s.running) "Stop bot" else "Start bot") }

        Text("Equity: ${"%.2f".format(s.equity)}   Balance: ${"%.2f".format(s.balance)}")
        Text("Daily drawdown: ${"%.2f".format(s.ddPct)}%" + if (s.halted) "  - KILL SWITCH ACTIVE" else "")
        Text("Last decision: ${s.lastDecision}", fontSize = 13.sp)

        Text("Open positions (${s.positions.size})", style = MaterialTheme.typography.titleMedium)
        s.positions.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.instrument} ${p.side.uppercase()} ${p.volume} @ ${p.openPrice}", Modifier.weight(1f), fontSize = 13.sp)
                OutlinedButton(onClick = { Engine.closePosition(p.id) }) { Text("Close") }
            }
        }
        OutlinedButton(onClick = {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                )
            }
        }) { Text("Allow background running (battery)") }
    }
}

@Composable
fun SettingsScreen() {
    val s by Engine.state.collectAsState()
    val p = Engine.prefs
    key(s.live) {
        var gw by remember { mutableStateOf(p.profileGet("gateway").ifBlank { "https://api.exness.com" }) }
        var acc by remember { mutableStateOf(p.profileGet("account")) }
        var apiKey by remember { mutableStateOf(p.profileGet("apikey")) }
        var priv by remember { mutableStateOf("") }
        var provider by remember { mutableStateOf(p.str("ai_provider", "gemini")) }
        var model by remember { mutableStateOf(p.str("ai_model_${p.str("ai_provider", "gemini")}", "gemini-3.8-flash").let { if (it == "gemini-2.5-flash") "gemini-3.8-flash" else it }) }
        var aiUrl by remember { mutableStateOf(p.str("ai_url_${p.str("ai_provider", "gemini")}", "https://generativelanguage.googleapis.com")) }
        var aiKey by remember { mutableStateOf(p.globalGet("ai_key_${p.str("ai_provider", "gemini")}")) }
        var fallback by remember { mutableStateOf(p.str("ai_fallback", "none")) }
        var fallbackKey by remember { mutableStateOf(p.globalGet("ai_key_${p.str("ai_fallback", "none")}")) }
        var inst by remember { mutableStateOf(p.str("instruments", "XAUUSD")) }
        var risk by remember { mutableStateOf(p.str("risk", "1.0")) }
        var interval by remember { mutableStateOf(p.str("interval", "30")) }
        var conf by remember { mutableStateOf(p.str("conf", "65")) }
        var maxdd by remember { mutableStateOf(p.str("maxdd", "30")) }
        var spread by remember { mutableStateOf(p.str("spreadmult", "3")) }
        var maxpos by remember { mutableStateOf(p.str("maxpos", "0")) }
        var tickage by remember { mutableStateOf(p.str("tickage", "120")) }
        val hasPriv = p.profileGet("privkey").isNotBlank()
        val providerOptions = "gemini,deepseek,openai,claude,groq,openrouter,ollama"
        val inputTokens = p.int("ai_usage_input", 0)
        val outputTokens = p.int("ai_usage_output", 0)
        val totalTokens = p.int("ai_usage_total", 0)

        Column(
            Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("AI Provider & API", style = MaterialTheme.typography.titleLarge)
            Text("Supported: $providerOptions", fontSize = 11.sp)
            Field("Primary provider (gemini / deepseek / openai / claude / groq / openrouter / ollama)", provider) {
                provider = it.trim().lowercase()
                model = p.str("ai_model_${provider}", when(provider) {
                    "gemini" -> "gemini-3.8-flash"; "deepseek" -> "deepseek-chat"; "claude" -> "claude-3-5-haiku-latest"
                    "ollama" -> "qwen2.5:3b"; "groq" -> "llama-3.3-70b-versatile"; "openrouter" -> "google/gemini-3.8-flash"
                    else -> "gpt-4o-mini"
                })
                aiUrl = p.str("ai_url_${provider}", when(provider) {
                    "gemini" -> "https://generativelanguage.googleapis.com"; "deepseek" -> "https://api.deepseek.com"
                    "claude" -> "https://api.anthropic.com"; "ollama" -> "http://127.0.0.1:11434"
                    "groq" -> "https://api.groq.com/openai/v1"; "openrouter" -> "https://openrouter.ai/api/v1"
                    else -> "https://api.openai.com/v1"
                })
                aiKey = p.globalGet("ai_key_${provider}")
            }
            Field("Model name", model) { model = it }
            Field("API URL / Base URL (editable)", aiUrl) { aiUrl = it }
            Field("Selected provider API key", aiKey, secret = true) { aiKey = it }
            Field("Fallback provider (none or provider name)", fallback) {
                fallback = it.trim().lowercase()
                fallbackKey = p.globalGet("ai_key_${fallback}")
            }
            Field("Fallback provider API key", fallbackKey, secret = true) { fallbackKey = it }
            Text("Token usage (reported by provider)", style = MaterialTheme.typography.titleMedium)
            Text("Input: $inputTokens   Output: $outputTokens   Total: $totalTokens", fontSize = 13.sp)
            Text("Latest provider: ${p.str("ai_usage_provider", "-")}", fontSize = 12.sp)

            Text("Exness account", style = MaterialTheme.typography.titleMedium)
            Text("Editing: ${if (s.live) "LIVE" else "DEMO"} credentials", style = MaterialTheme.typography.bodyMedium)
            Field("Gateway host (Key Management > DNS widget)", gw) { gw = it }
            Field("Account ID", acc) { acc = it }
            Field("API key (exnsk_...)", apiKey) { apiKey = it }
            Field(if (hasPriv) "Private key (saved - leave blank to keep)" else "Private key (PEM / hex / base64)", priv, secret = true) { priv = it }

            Text("Strategy & risk", style = MaterialTheme.typography.titleMedium)
            Field("Instruments (comma separated)", inst) { inst = it }
            Field("Risk % per trade", risk) { risk = it }
            Field("Scan interval (seconds, min 10)", interval) { interval = it }
            Field("Confidence threshold", conf) { conf = it }
            Field("Max daily drawdown % (0 = off)", maxdd) { maxdd = it }
            Field("Spread guard multiplier", spread) { spread = it }
            Field("Max open positions (0 = no limit)", maxpos) { maxpos = it }
            Field("Max tick age (seconds)", tickage) { tickage = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val primary = provider.trim().lowercase()
                    val backup = fallback.trim().lowercase()
                    p.put("ai_provider", primary)
                    p.put("ai_model_${primary}", model.trim())
                    p.put("ai_url_${primary}", aiUrl.trim())
                    if (aiKey.isNotBlank()) p.globalPut("ai_key_${primary}", aiKey.trim())
                    p.put("ai_fallback", if (backup.isBlank()) "none" else backup)
                    if (backup != "none" && fallbackKey.isNotBlank()) p.globalPut("ai_key_${backup}", fallbackKey.trim())
                    p.profilePut("gateway", gw.trim()); p.profilePut("account", acc.trim()); p.profilePut("apikey", apiKey.trim())
                    if (priv.isNotBlank()) {
                        try { ExnAuth.parseSeed(priv); p.profilePut("privkey", priv); priv = "" }
                        catch (e: Exception) { Engine.log("Private key rejected: ${e.message}") }
                    }
                    p.put("instruments", inst); p.put("risk", risk); p.put("interval", interval); p.put("conf", conf)
                    p.put("maxdd", maxdd); p.put("spreadmult", spread); p.put("maxpos", maxpos); p.put("tickage", tickage)
                    Engine.log("AI provider settings saved: $primary; fallback=$backup")
                }) { Text("Save") }
                OutlinedButton(onClick = { Engine.testConnection() }) { Text("Test Exness") }
            }
            Text("Provider API key is stored encrypted on this device. Restart the bot to apply settings.", fontSize = 12.sp)
            Text("Exness connection test results appear in the Log tab.", fontSize = 12.sp)
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 12.sp) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
    )
}

@Composable
fun LogScreen() {
    val s by Engine.state.collectAsState()
    LazyColumn(Modifier.padding(12.dp).fillMaxSize()) {
        items(s.log.reversed()) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}
