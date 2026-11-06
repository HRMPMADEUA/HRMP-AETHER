package com.tirexnet.app.engine

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opaque TIREXNET share codec.
 *
 * Format: tirexnet://share/v1/<URL-safe Base64 of: version(1) | iv(12) | ciphertext+tag>
 *
 * Payload is GZIP-compressed JSON then AES-256-GCM encrypted.
 * A shared app key is used for convenience sharing. It does NOT provide absolute secrecy
 * against a determined attacker who extracts the key from the APK. Optional password-based
 * encryption can be layered later without breaking this scheme.
 *
 * Sensitive fields (host, domain, IP, port, credentials, raw config) exist only inside
 * the encrypted payload and never appear in the URI in clear text.
 */
object TirexNetShare {

    const val SCHEME = "tirexnet://share/v1/"
    const val SCHEMA_VERSION = 1
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /**
     * Derived from a fixed app-level secret. Not user-visible.
     * Purpose: stop casual reading of shared links; not absolute security.
     */
    private val APP_KEY: ByteArray by lazy {
        // 32-byte key material (SHA-256 of a fixed phrase + salt). Do not log.
        val phrase = "TIREXNET-OPAQUE-SHARE-v1-HRMP-AETHER-2026"
        val salt = "tirexnet.share.opaque.v1".toByteArray(StandardCharsets.UTF_8)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.digest(phrase.toByteArray(StandardCharsets.UTF_8))
    }

    enum class ProfileOrigin {
        PANEL,
        PASTED,
        MANUAL,
        IMPORTED_TIREXNET,
        LOCAL_DUPLICATE,
        UNKNOWN
    }

    data class SharePayload(
        val schemaVersion: Int = SCHEMA_VERSION,
        val name: String,
        val engine: String,
        val raw: String,
        val origin: ProfileOrigin = ProfileOrigin.IMPORTED_TIREXNET,
        val dnsResolvers: List<String> = emptyList(),
        val selectedResolver: String = "",
        val customSettings: Map<String, String> = emptyMap(),
        val country: String = "",
        val createdAt: Long = System.currentTimeMillis()
    )

    data class EncodeResult(val uri: String, val byteSize: Int)
    data class DecodeResult(val payload: SharePayload)
    sealed class ShareError(message: String) : Exception(message) {
        class TooLarge(msg: String) : ShareError(msg)
        class InvalidFormat(msg: String) : ShareError(msg)
        class DecryptFailed(msg: String) : ShareError(msg)
        class Tampered(msg: String) : ShareError(msg)
        class UnsupportedVersion(msg: String) : ShareError(msg)
    }

    /** True when the profile may be shared (not a direct panel subscription). */
    fun canShare(origin: ProfileOrigin, isLocal: Boolean, panelId: String): Boolean {
        if (!isLocal) return false
        if (panelId.isNotBlank()) return false
        return when (origin) {
            ProfileOrigin.PANEL -> false
            ProfileOrigin.UNKNOWN -> false // safe default for legacy
            else -> true
        }
    }

    fun encode(
        name: String,
        engine: ConfigEngine,
        raw: String,
        origin: ProfileOrigin,
        dnsResolvers: List<String> = emptyList(),
        selectedResolver: String = "",
        customSettings: Map<String, String> = emptyMap(),
        country: String = ""
    ): EncodeResult {
        require(raw.isNotBlank()) { "empty raw" }
        val json = JSONObject()
            .put("v", SCHEMA_VERSION)
            .put("name", name.take(120))
            .put("engine", engine.name)
            .put("raw", raw)
            .put("origin", origin.name)
            .put("dns", JSONArray(dnsResolvers.filter { it.isNotBlank() }.distinct()))
            .put("selDns", selectedResolver)
            .put("custom", JSONObject(customSettings))
            .put("country", country.take(40))
            .put("ts", System.currentTimeMillis())
        val plain = gzip(json.toString().toByteArray(StandardCharsets.UTF_8))
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(APP_KEY, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        // version byte | iv | ciphertext+tag
        val packed = ByteBuffer.allocate(1 + iv.size + ct.size)
            .put(1.toByte())
            .put(iv)
            .put(ct)
            .array()
        val b64 = Base64.encodeToString(packed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val uri = SCHEME + b64
        // Soft limit for QR; callers may still reject larger values for display.
        if (uri.length > 2800) {
            throw ShareError.TooLarge("Share URI is too large for reliable QR (${uri.length} chars). Try a shorter config.")
        }
        return EncodeResult(uri, packed.size)
    }

    fun decode(uriOrToken: String): DecodeResult {
        val value = uriOrToken.trim()
            .removeSurrounding("<", ">")
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
        val token = when {
            value.startsWith(SCHEME, ignoreCase = true) -> value.substring(SCHEME.length)
            value.startsWith("tirexnet://share/", ignoreCase = true) -> {
                // Future versions: tirexnet://share/v2/...
                val after = value.substringAfter("tirexnet://share/")
                val ver = after.substringBefore('/')
                if (ver != "v1") throw ShareError.UnsupportedVersion("Unsupported share version: $ver")
                after.substringAfter('/')
            }
            else -> throw ShareError.InvalidFormat("Not a TIREXNET share URI")
        }
        if (token.isBlank() || token.length > 200_000) {
            throw ShareError.InvalidFormat("Invalid or oversized payload")
        }
        val packed = try {
            Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Throwable) {
            try {
                Base64.decode(token, Base64.DEFAULT)
            } catch (e: Throwable) {
                throw ShareError.InvalidFormat("Base64 decode failed")
            }
        }
        if (packed.size < 1 + IV_BYTES + 16) throw ShareError.InvalidFormat("Payload too short")
        val version = packed[0].toInt() and 0xff
        if (version != 1) throw ShareError.UnsupportedVersion("Binary version $version not supported")
        val iv = packed.copyOfRange(1, 1 + IV_BYTES)
        val ct = packed.copyOfRange(1 + IV_BYTES, packed.size)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(APP_KEY, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            gunzip(cipher.doFinal(ct))
        } catch (e: Throwable) {
            throw ShareError.DecryptFailed("Decrypt/integrity check failed (tampered or wrong key)")
        }
        val json = try {
            JSONObject(String(plain, StandardCharsets.UTF_8))
        } catch (e: Throwable) {
            throw ShareError.Tampered("Invalid JSON after decrypt")
        }
        val schema = json.optInt("v", 0)
        if (schema < 1 || schema > SCHEMA_VERSION) {
            throw ShareError.UnsupportedVersion("Payload schema $schema not supported")
        }
        val raw = json.optString("raw", "")
        if (raw.isBlank()) throw ShareError.Tampered("Missing raw config")
        val origin = runCatching {
            ProfileOrigin.valueOf(json.optString("origin", "IMPORTED_TIREXNET"))
        }.getOrDefault(ProfileOrigin.IMPORTED_TIREXNET)
        val dns = json.optJSONArray("dns")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }.orEmpty()
        val custom = linkedMapOf<String, String>()
        json.optJSONObject("custom")?.let { obj ->
            obj.keys().forEach { k -> custom[k] = obj.optString(k, "") }
        }
        return DecodeResult(
            SharePayload(
                schemaVersion = schema,
                name = json.optString("name", "Imported profile").ifBlank { "Imported profile" },
                engine = json.optString("engine", "UNKNOWN"),
                raw = raw,
                origin = origin,
                dnsResolvers = dns,
                selectedResolver = json.optString("selDns", ""),
                customSettings = custom,
                country = json.optString("country", ""),
                createdAt = json.optLong("ts", System.currentTimeMillis())
            )
        )
    }

    fun isTirexNetShare(value: String): Boolean {
        val v = value.trim().lowercase()
        return v.startsWith("tirexnet://share/")
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }
}
