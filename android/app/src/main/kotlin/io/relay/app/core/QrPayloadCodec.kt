package io.relay.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

sealed interface DecodeResult {
    data class Ok(val payload: QrPayload) : DecodeResult

    /** [reason] values are the stable strings used in /shared/test-vectors.json. */
    data class Invalid(val reason: String) : DecodeResult
}

/**
 * Encodes/decodes the QR pairing payload: compact JSON -> UTF-8 -> base64url
 * (no padding). Validation reasons mirror /shared/test-vectors.json exactly.
 */
object QrPayloadCodec {
    const val SUPPORTED_VERSION = 1
    const val QR_PREFIX = "relay://p/"

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    private val ipv4Regex = Regex(
        "^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$"
    )

    fun encode(payload: QrPayload): String {
        val bytes = json.encodeToString(QrPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** The exact string rendered into the QR image. */
    fun encodeForQr(payload: QrPayload): String = QR_PREFIX + encode(payload)

    fun decode(encoded: String): DecodeResult {
        val bare = encoded.removePrefix(QR_PREFIX)
        val text = try {
            String(Base64.getUrlDecoder().decode(bare), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return DecodeResult.Invalid("decode-error")
        }
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: return DecodeResult.Invalid("decode-error")
        } catch (_: Exception) {
            return DecodeResult.Invalid("decode-error")
        }
        return validate(obj)
    }

    /** Structural validation of an already-parsed JSON object. */
    fun validate(obj: JsonObject): DecodeResult {
        val v = obj["v"]?.jsonPrimitive?.intOrNull
            ?: return DecodeResult.Invalid("missing-required-field")
        if (v != SUPPORTED_VERSION) return DecodeResult.Invalid("unknown-version")

        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull
            ?: return DecodeResult.Invalid("missing-required-field")
        val host = obj["host"]?.jsonPrimitive?.contentOrNull
            ?: return DecodeResult.Invalid("missing-required-field")
        val port = obj["port"]?.jsonPrimitive?.intOrNull
            ?: return DecodeResult.Invalid("missing-required-field")

        if (mode != QrPayload.MODE_SOCKS5 && mode != QrPayload.MODE_WIREGUARD) {
            return DecodeResult.Invalid("invalid-mode")
        }
        if (port !in 1..65535) return DecodeResult.Invalid("invalid-port")
        if (!ipv4Regex.matches(host)) return DecodeResult.Invalid("invalid-host")

        val hasWg = obj.containsKey("wg")
        if (mode == QrPayload.MODE_WIREGUARD && !hasWg) {
            return DecodeResult.Invalid("missing-wg-block")
        }
        if (mode == QrPayload.MODE_SOCKS5 && hasWg) {
            return DecodeResult.Invalid("unexpected-wg-block")
        }

        val payload = try {
            json.decodeFromJsonElement(QrPayload.serializer(), obj)
        } catch (_: Exception) {
            return DecodeResult.Invalid("decode-error")
        }
        return DecodeResult.Ok(payload)
    }
}
