package com.tirexnet.app.engine

import java.util.Base64

/**
 * Helpers for importing standard .ovpn / .conf files. Pure Kotlin (no Android classes) so the
 * logic is easy to unit-test. Nothing here logs or returns key material in messages.
 */
object OvpnImport {
    /** Directives whose argument is a file. [inlineTag] is the OpenVPN inline block that replaces it. */
    private enum class Ref(val directive: String, val inlineTag: String, val binary: Boolean = false) {
        CA("ca", "ca"),
        CERT("cert", "cert"),
        KEY("key", "key"),
        DH("dh", "dh"),
        EXTRA_CERTS("extra-certs", "extra-certs"),
        TLS_AUTH("tls-auth", "tls-auth"),
        TLS_CRYPT("tls-crypt", "tls-crypt"),
        TLS_CRYPT_V2("tls-crypt-v2", "tls-crypt-v2"),
        PKCS12("pkcs12", "pkcs12", binary = true),
        AUTH_USER_PASS("auth-user-pass", "auth-user-pass")
    }

    private const val MAX_REF_BYTES = 256 * 1024

    private val lineRegex = Regex(
        "^\\s*(ca|cert|key|dh|extra-certs|tls-auth|tls-crypt|tls-crypt-v2|pkcs12|auth-user-pass)\\s+" +
            "(\"[^\"]+\"|'[^']+'|\\S+)(?:\\s+(\\d))?\\s*$",
        RegexOption.IGNORE_CASE
    )

    data class Result(val config: String?, val error: String?, val missing: List<String> = emptyList(), val inlined: List<String> = emptyList())

    private class Hit(val lineIndex: Int, val ref: Ref, val file: String, val direction: String?)

    private fun baseName(path: String): String =
        path.trim().trim('"', '\'').replace('\\', '/').substringAfterLast('/')

    private fun findHits(lines: List<String>): List<Hit> {
        val hits = ArrayList<Hit>()
        for ((i, line) in lines.withIndex()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) continue
            val m = lineRegex.find(line) ?: continue
            val ref = Ref.values().first { it.directive.equals(m.groupValues[1], ignoreCase = true) }
            val file = m.groupValues[2]
            if (file.equals("[inline]", true) || file.equals("inline", true)) continue
            hits.add(Hit(i, ref, file, m.groupValues[3].ifEmpty { null }))
        }
        return hits
    }

    /** File names (base names) referenced by directives instead of inline blocks. Empty = self-contained. */
    fun referencedFiles(config: String): List<String> =
        findHits(config.lines()).map { baseName(it.file) }.distinct()

    /**
     * Replaces every file reference with an inline block, using [files] (file name -> bytes).
     * Never partially applies: either every reference is resolved or a clear error is returned.
     */
    fun inline(config: String, files: Map<String, ByteArray>): Result {
        val lines = config.replace("\r\n", "\n").replace('\r', '\n').lines()
        val hits = findHits(lines)
        if (hits.isEmpty()) return Result(config.trim(), null)
        val byName = files.mapKeys { it.key.lowercase() }
        val missing = hits.map { baseName(it.file) }.filter { byName[it.lowercase()] == null }.distinct()
        if (missing.isNotEmpty()) {
            return Result(null, "Missing referenced files: ${missing.joinToString(", ")}. Select them together with the .ovpn file.", missing)
        }
        val replacements = HashMap<Int, List<String>>()
        val done = ArrayList<String>()
        for (h in hits) {
            val name = baseName(h.file)
            val bytes = byName[name.lowercase()]!!
            if (bytes.size > MAX_REF_BYTES) return Result(null, "Referenced file \"$name\" is too large.")
            val out = ArrayList<String>()
            when {
                h.ref.binary -> {
                    out.add("<${h.ref.inlineTag}>")
                    out.addAll(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes).lines())
                    out.add("</${h.ref.inlineTag}>")
                }
                h.ref == Ref.AUTH_USER_PASS -> {
                    val creds = String(bytes, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (creds.size < 2) return Result(null, "Credentials file \"$name\" must contain a username line and a password line.")
                    out.add("<auth-user-pass>"); out.add(creds[0]); out.add(creds[1]); out.add("</auth-user-pass>")
                }
                else -> {
                    val text = String(bytes, Charsets.UTF_8).replace("\r\n", "\n").trim()
                    if (!text.contains("-----BEGIN")) return Result(null, "Referenced file \"$name\" is not a valid PEM / key file.")
                    if (h.ref == Ref.TLS_AUTH && h.direction != null) out.add("key-direction ${h.direction}")
                    out.add("<${h.ref.inlineTag}>"); out.addAll(text.lines()); out.add("</${h.ref.inlineTag}>")
                }
            }
            replacements[h.lineIndex] = out
            done.add(name)
        }
        val result = ArrayList<String>()
        for ((i, line) in lines.withIndex()) {
            val r = replacements[i]
            if (r != null) result.addAll(r) else result.add(line)
        }
        return Result(result.joinToString("\n").trim(), null, emptyList(), done.distinct())
    }

    /** Name for a freshly imported profile: file name (without extension), else remote host. "" if neither. */
    fun suggestName(fileName: String, config: String): String {
        val stem = fileName.substringBeforeLast('.', fileName).replace('_', ' ').replace('-', ' ').trim()
        if (stem.isNotBlank()) return stem
        return Regex("(?im)^\\s*remote\\s+(\\S+)").find(config)?.groupValues?.get(1).orEmpty()
    }
}

object WireGuardImport {
    private val keyRegex = Regex("^[A-Za-z0-9+/]{43}=$")

    private fun value(raw: String, key: String): String? =
        Regex("(?im)^\\s*" + Regex.escape(key) + "\\s*=\\s*(.*?)\\s*$").find(raw)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** Returns a user-readable problem (never contains key material) or null when the profile is usable. */
    fun validate(raw: String): String? {
        val lower = raw.lowercase()
        if (!lower.contains("[interface]")) return "WireGuard file has no [Interface] section."
        if (!lower.contains("[peer]")) return "WireGuard file has no [Peer] section."
        val priv = value(raw, "PrivateKey") ?: return "WireGuard file has no PrivateKey."
        if (!keyRegex.matches(priv)) return "WireGuard PrivateKey is not a valid 32-byte base64 key."
        if (value(raw, "Address") == null) return "WireGuard file has no Address."
        val pub = value(raw, "PublicKey") ?: return "WireGuard file has no peer PublicKey."
        if (!keyRegex.matches(pub)) return "WireGuard PublicKey is not a valid 32-byte base64 key."
        value(raw, "PresharedKey")?.let { if (!keyRegex.matches(it)) return "WireGuard PresharedKey is not a valid 32-byte base64 key." }
        if (value(raw, "Endpoint") == null) return "WireGuard file has no peer Endpoint."
        if (value(raw, "AllowedIPs") == null) return "WireGuard file has no AllowedIPs."
        value(raw, "MTU")?.let { m -> if (m.toIntOrNull()?.let { it in 576..9000 } != true) return "WireGuard MTU must be a number between 576 and 9000." }
        value(raw, "PersistentKeepalive")?.let { k -> if (k != "off" && k.toIntOrNull()?.let { it in 0..65535 } != true) return "WireGuard PersistentKeepalive is not valid." }
        return null
    }

    fun suggestName(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).replace('_', ' ').trim()
}
