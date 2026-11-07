package com.tirexnet.app

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sequential compatibility decoder for SlipNet share URIs.
 *
 * The official importer is always attempted first by TirexSlipNetRuntime.
 * This fallback handles the public app-compatible AES key and damaged
 * Base64 transport encodings. It never guesses passwords or bypasses locks.
 */
internal object SlipNetDecodeFallback {

    private const val PUBLIC_APP_KEY_HEX =
        "214F052025B2F949605A5429EC3D5FA80C2022C168AD946E68852D447214DBD3"

    private val prefixes = listOf(
        "slipnet-bundle-enc://",
        "slipnet-enc://",
        "slipnet://"
    )

    private val knownKeys by lazy {
        listOf(hexToBytes(PUBLIC_APP_KEY_HEX))
    }

    /**
     * Normalizes one or multiple SlipNet URI lines.
     * Returns null when no safe normalization was possible or necessary.
     */
    fun normalizeInput(input: String): String? {
        if (input.isBlank() || input.length > 6_000_000) return null

        val lines = input
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')

        val output = ArrayList<String>()
        var changed = false
        var index = 0

        while (index < lines.size) {
            val originalLine = lines[index]
            val line = originalLine.trim()
                .removeSurrounding("<", ">")
                .removeSurrounding("\"", "\"")
                .removeSurrounding("'", "'")

            val prefix = prefixes.firstOrNull {
                line.startsWith(it, ignoreCase = true)
            }

            if (prefix == null) {
                output.add(originalLine)
                index++
                continue
            }

            val payload = StringBuilder(line.substring(prefix.length))
            var next = index + 1

            // Recover Base64 payloads wrapped by messengers or QR scanners.
            while (next < lines.size) {
                val continuation = lines[next].trim()

                if (continuation.isEmpty()) break
                if (prefixes.any { continuation.startsWith(it, true) }) break
                if (!continuation.matches(Regex("^[A-Za-z0-9_+/=\\-]+$"))) break

                payload.append(continuation)
                next++
            }

            val assembled = prefix + payload.toString()
            val normalized = normalizeOne(assembled)

            if (normalized != null) {
                output.add(normalized)
                if (normalized != assembled || next > index + 1 ||
                    line != assembled
                ) {
                    changed = true
                }
                index = next
            } else {
                output.add(originalLine)
                index++
            }
        }

        val result = output.joinToString("\n")
        return if (changed && result.isNotBlank()) result else null
    }

    private fun normalizeOne(raw: String): String? {
        val value = raw.trim()
            .removeSurrounding("<", ">")
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")

        val prefix = prefixes.firstOrNull {
            value.startsWith(it, ignoreCase = true)
        } ?: return null

        val rawPayload = value.substring(prefix.length).substringBefore('#')
        val token = rawPayload
            .replace("%2B", "+", ignoreCase = true)
            .replace("%2F", "/", ignoreCase = true)
            .replace("%3D", "=", ignoreCase = true)
            .replace(' ', '+')
            .replace("\r", "")
            .replace("\n", "")
            .replace("\t", "")

        val bytes = decodeBase64(token) ?: return null
        val canonical = Base64.encodeToString(bytes, Base64.NO_WRAP)

        return when (prefix) {
            "slipnet://" -> {
                val plain = decodeUtf8(bytes) ?: return null
                if (!looksLikeSlipNetProfile(plain)) return null
                "slipnet://$canonical"
            }

            "slipnet-bundle-enc://" -> {
                // Bundle decryption remains password-based and is delegated
                // to the official SlipNet importer.
                if (bytes.size < 45 || bytes[0] != 0x01.toByte()) return null
                "slipnet-bundle-enc://$canonical"
            }

            "slipnet-enc://" -> {
                val plaintext = decryptKnownSingleProfile(bytes)
                if (plaintext != null) {
                    val encoded = Base64.encodeToString(
                        plaintext.toByteArray(StandardCharsets.UTF_8),
                        Base64.NO_WRAP
                    )
                    "slipnet://$encoded"
                } else {
                    // Preserve the encrypted URI so the official native
                    // importer can still attempt its configured key.
                    "slipnet-enc://$canonical"
                }
            }

            else -> null
        }
    }

    private fun decodeBase64(value: String): ByteArray? {
        val compact = value.trim()
        if (compact.isEmpty()) return null

        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)

        val modes = listOf(
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.DEFAULT,
            Base64.URL_SAFE or Base64.DEFAULT
        )

        for (mode in modes) {
            val decoded = runCatching {
                Base64.decode(padded, mode)
            }.getOrNull()

            if (decoded != null && decoded.isNotEmpty()) return decoded
        }

        return null
    }

    private fun decryptKnownSingleProfile(data: ByteArray): String? {
        // Official single-profile envelope:
        // version(1) + nonce(12) + AES-GCM ciphertext/tag.
        if (data.size < 29 || data[0] != 0x01.toByte()) return null

        val nonce = data.copyOfRange(1, 13)
        val ciphertext = data.copyOfRange(13, data.size)

        for (key in knownKeys) {
            val plaintext = runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce)
                )
                cipher.doFinal(ciphertext)
            }.getOrNull() ?: continue

            val decoded = decodeUtf8(plaintext) ?: continue
            if (looksLikeSlipNetProfile(decoded)) return decoded
        }

        return null
    }

    private fun looksLikeSlipNetProfile(value: String): Boolean {
        if (value.isBlank() || value.contains('\u0000')) return false

        val fields = value.split('|')
        val version = fields.firstOrNull()?.toIntOrNull() ?: return false

        return version in 1..999 &&
            fields.size >= 11 &&
            fields.getOrNull(1).orEmpty().isNotBlank() &&
            fields.getOrNull(2).orEmpty().isNotBlank()
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
