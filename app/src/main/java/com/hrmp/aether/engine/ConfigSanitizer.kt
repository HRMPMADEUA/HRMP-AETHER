package com.tirexnet.app.engine

/**
 * Pre-parse clean-up for AmneziaWG configs and secret-safe error text.
 *
 * Background for the reported failure
 *   "Interface.I1: INVALID_SIGNATURE_FORMAT (value: 0)":
 * I1..I5 are AmneziaWG 1.5+ "signature packet" fields. They must be a chain of tags such as
 * `<b 0x1234><r 16><t><c>`. Some panels/exporters write `I1 = 0` (or an empty value) to mean
 * "disabled". The engine's validator rejects that literal 0. Dropping such lines keeps the
 * feature disabled, which is what the exporter meant.
 *
 * NOTE: this was derived from the error text and the AmneziaWG syntax, not from a reproduced
 * run (no real config was available and the engine library source was not inspectable offline).
 */
object ConfigSanitizer {

    data class Result(val text: String, val notes: List<String>)

    private val signatureKey = Regex("(?i)^I[1-5]$")
    private val signatureValue = Regex("(?i)^(<(b\\s+0x[0-9a-f]+|c|t|r\\s+\\d+|rc\\s+\\d+|rd\\s+\\d+)>)+$")

    fun sanitizeAmnezia(raw: String): Result {
        val notes = ArrayList<String>()
        var inInterface = false
        val out = ArrayList<String>()
        for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val t = line.trim()
            if (t.startsWith("[")) inInterface = t.equals("[Interface]", ignoreCase = true)
            if (inInterface && t.contains('=') && !t.startsWith("#") && !t.startsWith(";")) {
                val key = t.substringBefore('=').trim()
                val value = t.substringAfter('=').trim()
                if (signatureKey.matches(key) && !signatureValue.matches(value)) {
                    notes.add("$key ignored: value is not a valid signature packet (empty/0 means disabled).")
                    continue
                }
            }
            out.add(line)
        }
        return Result(out.joinToString("\n"), notes)
    }

    /** Remove anything that looks like a key, token or share link from text meant for the UI/logs. */
    fun redact(message: String?): String {
        var s = message ?: return ""
        // Certificates, keys and inline OpenVPN blocks never reach the UI or logs.
        s = s.replace(Regex("(?s)-----BEGIN [A-Z ]+-----.*?(-----END [A-Z ]+-----|$)"), "<key hidden>")
        s = s.replace(Regex("(?is)<(ca|cert|key|tls-auth|tls-crypt|tls-crypt-v2|secret)>.*?(</\\1>|$)"), "<block hidden>")
        s = s.replace(Regex("(?i)(vless|vmess|trojan|ss|ssr)://\\S+"), "<link hidden>")
        s = s.replace(Regex("(?im)(private\\s*key|preshared\\s*key|password|token)\\s*[=:]\\s*\\S+"), "$1=<hidden>")
        s = s.replace(Regex("[A-Za-z0-9+/_-]{40,}={0,2}"), "<hidden>")
        s = s.replace(Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), "<id hidden>")
        // Endpoint host/port must not reach the UI either.
        s = s.replace(Regex("(?im)endpoint\\s*[=:]\\s*\\S+"), "endpoint=<hidden>")
        s = s.replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b"), "<addr hidden>")
        return s.take(220)
    }
}
