package com.ras.hedgefund

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest

/** Exness API request signing (sign_version 1): Ed25519 over the decoded EXN-DATA JSON bytes. */
object ExnAuth {
    private fun b64u(b: ByteArray) =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Accepts PKCS#8 PEM, 64-char hex seed, or base64 of a 32/48/64-byte key. Returns the 32-byte seed. */
    fun parseSeed(input: String): ByteArray {
        val t = input.trim()
        if (t.contains("BEGIN")) {
            val b64 = t.lines().filter { !it.startsWith("-----") }.joinToString("").trim()
            val der = Base64.decode(b64, Base64.DEFAULT)
            require(der.size >= 32) { "Private key too short" }
            return der.copyOfRange(der.size - 32, der.size)
        }
        if (Regex("^[0-9a-fA-F]{64}$").matches(t)) {
            return ByteArray(32) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        val raw = try { Base64.decode(t, Base64.DEFAULT) } catch (e: Exception) { Base64.decode(t, Base64.URL_SAFE) }
        return when (raw.size) {
            32 -> raw
            48 -> raw.copyOfRange(16, 48)
            64 -> raw.copyOfRange(0, 32)
            else -> throw IllegalArgumentException("Unrecognized private key format (${raw.size} bytes)")
        }
    }

    fun headers(
        apiKey: String, seed: ByteArray, method: String, pathWithQuery: String,
        idempotencyKey: String, body: ByteArray
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        val bodyHash = b64u(MessageDigest.getInstance("SHA-256").digest(body))
        val payload = "{\"api_key\":\"${esc(apiKey)}\",\"idempotency_key\":\"${esc(idempotencyKey)}\"," +
            "\"timestamp\":$ts,\"sign_version\":1,\"method\":\"$method\"," +
            "\"path\":\"${esc(pathWithQuery)}\",\"body_hash\":\"$bodyHash\"}"
        val data = payload.toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(data, 0, data.size)
        val sig = signer.generateSignature()
        return mapOf(
            "EXN-API-KEY" to apiKey,
            "EXN-IDEMPOTENCY-KEY" to idempotencyKey,
            "EXN-TIMESTAMP" to ts.toString(),
            "EXN-SIGN-VERSION" to "1",
            "EXN-DATA" to b64u(data),
            "EXN-SIGN" to b64u(sig)
        )
    }
}
