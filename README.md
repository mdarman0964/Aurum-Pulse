# Aurum Pulse — HedgeFund Mobile

Android port of the PC hedgefund_bot. The app communicates with the Exness Trader API using signed REST requests and WebSocket ticks, requests BUY/SELL/HOLD decisions from DeepSeek, and applies risk guards including daily drawdown limits, spread checks, duplicate/reversal handling, exposure caps, stale-tick checks, and risk-based sizing.

## Build APK
Push to main, open GitHub Actions, and download the `hedgefund-mobile-debug` artifact from the Build APK workflow.

## First run
1. Configure the gateway host, account ID, API key, signing private key, and DeepSeek key in Settings.
2. Use instrument names reported by the Exness API (for example `XAUUSD`, not MT5's `XAUUSDm`).
3. Press Test connection and review the Log tab.
4. Test on DEMO before considering LIVE. Allow background operation in Android battery settings.

## Security
Credentials are encrypted with AES-256-GCM using an Android Keystore key; backup is disabled. Protect Exness signing secrets carefully. Keep key expiry short, scope keys narrowly, and never share the APK or credentials.

## Not ported yet
The auditor self-learning rules and trade reconciliation are not ported; `journal.jsonl` currently records entries only.
