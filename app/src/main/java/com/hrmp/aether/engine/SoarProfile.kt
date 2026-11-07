package com.tirexnet.app.engine

import java.net.URLDecoder
import org.json.JSONObject

/**
 * Parser/validator for Soar / WhiteDNS profile links. It validates and exposes the data; it does NOT run anything.
 * Connecting is done by the SlipNet engine (which owns the Soar tunnel), see MainActivity.doImport.
 *
 * Supported forms (checked against a real "whitedns.profile" sample):
 *  1. soar://<Base64 or Base64URL of JSON>   {"schema":"whitedns.profile","version":1,"profile":{...}}
 *  2. soar://<domain>?pk=<public key>&resolvers=a,b&country=XX#<name>
 */
object SoarProfile {
    data class Data(
        val name: String,
        val domain: String,
        val publicKey: String,
        val resolvers: List<String>,
        val country: String,
        /** Every key of "soarSettings" (and unknown top-level profile keys) is kept as text. */
        val settings: Map<String, String>
    )

    sealed interface Result {
        data class Ok(val data: Data) : Result
        data class Invalid(val reason: String) : Result
    }

    private val hostRegex = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")
    private val keyRegex = Regex("^[A-Za-z0-9+/]{43}=$")
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isSoar(raw: String) = raw.trim().startsWith("soar://", ignoreCase = true)

    private fun decodeBase64(text: String): ByteArray? = runCatching {
        val t = text.trim().replace('-', '+').replace('_', '/')
        java.util.Base64.getDecoder().decode(t + "=".repeat((4 - t.length % 4) % 4))
    }.getOrNull()

    private fun validResolver(value: String): Boolean {
        val host = value.substringBefore(':')
        val port = value.substringAfter(':', "")
        val okPort = port.isEmpty() || port.toIntOrNull()?.let { it in 1..65535 } == true
        val okHost = (ipv4.matches(host) && host.split('.').all { it.toInt() in 0..255 }) || value.startsWith("[") || hostRegex.matches(host)
        return okPort && okHost
    }

    fun parse(raw: String): Result {
        val t = raw.trim()
        if (!isSoar(t)) return Result.Invalid("Not a soar:// link.")
        val body = t.substring("soar://".length)
        if (body.isBlank()) return Result.Invalid("The Soar link is empty.")
        val payload = body.substringBefore('#').substringBefore('?')
        val json = decodeBase64(payload)?.let { String(it, Charsets.UTF_8).trim() }?.takeIf { it.startsWith("{") }
        return if (json != null) parseJson(json) else parseUrl(body)
    }

    private fun parseJson(json: String): Result {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return Result.Invalid("The Soar profile data is damaged.")
        val schema = root.optString("schema")
        if (schema.isNotEmpty() && !schema.contains("profile", true)) return Result.Invalid("Unsupported Soar profile schema.")
        val version = root.optInt("version", 1)
        if (version != 1) return Result.Invalid("Unsupported Soar profile version $version.")
        val p = root.optJSONObject("profile") ?: return Result.Invalid("The Soar profile has no \"profile\" section.")
        val server = p.optJSONObject("server") ?: return Result.Invalid("The Soar profile has no server section.")
        val domain = server.optString("domain").trim()
        val key = server.optString("public_key").trim()
        val resolvers = buildList {
            p.optJSONArray("resolvers")?.let { a -> for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add) }
        }
        val settings = LinkedHashMap<String, String>()
        p.optJSONObject("soarSettings")?.let { s -> s.keys().forEach { k -> settings[k] = s.opt(k).toString() } }
        return build(p.optString("name").trim(), domain, key, resolvers, settings.remove("country").orEmpty().ifBlank { p.optString("country") }, settings)
    }

    private fun parseUrl(body: String): Result {
        val fragment = body.substringAfter('#', "")
        val noFrag = body.substringBefore('#')
        val domain = noFrag.substringBefore('?').trim('/')
        val query = noFrag.substringAfter('?', "")
        val params = query.split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=').lowercase() to runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("")
        }
        val resolvers = params["resolvers"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val name = runCatching { URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment).trim()
        return build(name, domain, params["pk"].orEmpty(), resolvers, params["country"].orEmpty(), emptyMap())
    }

    private fun build(name: String, domain: String, key: String, resolvers: List<String>, country: String, settings: Map<String, String>): Result {
        if (domain.isEmpty()) return Result.Invalid("The Soar profile has no server domain.")
        if (!hostRegex.matches(domain)) return Result.Invalid("The Soar server domain is not valid.")
        if (key.isEmpty()) return Result.Invalid("The Soar profile has no public key.")
        if (!keyRegex.matches(key)) return Result.Invalid("The Soar public key is not a valid 32-byte base64 key.")
        if (resolvers.isEmpty()) return Result.Invalid("The Soar profile has no DNS resolver.")
        resolvers.firstOrNull { !validResolver(it) }?.let { return Result.Invalid("Invalid Soar resolver: $it") }
        if (country.isNotEmpty() && !Regex("^[A-Za-z]{2}$").matches(country)) return Result.Invalid("The Soar country code is not valid.")
        return Result.Ok(Data(name.ifBlank { domain }, domain, key, resolvers, country.uppercase(), settings))
    }
}
