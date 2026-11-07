package com.tirexnet.app.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Per-profile settings, kept separately from the raw share link so subscription data stays immutable. */
class ProfileSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tirexnet_profile_settings_v1", Context.MODE_PRIVATE)

    data class Settings(
        val profileId: String,
        val schemaVersion: Int = SCHEMA_VERSION,
        val dnsResolvers: List<String> = emptyList(),
        val selectedResolver: String = "",
        val customSettings: Map<String, String> = emptyMap(),
        val origin: TirexNetShare.ProfileOrigin = TirexNetShare.ProfileOrigin.UNKNOWN
    )

    @Synchronized
    fun get(raw: String): Settings {
        val id = idFor(raw)
        val json = runCatching { JSONObject(prefs.getString("profile:$id", "{}").orEmpty()) }.getOrDefault(JSONObject())
        val resolvers = json.optJSONArray("dnsResolvers")?.toStringList().orEmpty()
        val custom = linkedMapOf<String, String>()
        json.optJSONObject("customSettings")?.let { obj ->
            obj.keys().forEach { key -> custom[key] = obj.optString(key, "") }
        }
        val origin = runCatching {
            TirexNetShare.ProfileOrigin.valueOf(json.optString("origin", "UNKNOWN"))
        }.getOrDefault(TirexNetShare.ProfileOrigin.UNKNOWN)
        return Settings(
            id,
            json.optInt("schemaVersion", 1).coerceAtLeast(1),
            resolvers,
            json.optString("selectedResolver", ""),
            custom,
            origin
        )
    }

    @Synchronized
    fun save(
        raw: String,
        dnsResolvers: List<String>,
        selectedResolver: String = "",
        customSettings: Map<String, String> = emptyMap(),
        origin: TirexNetShare.ProfileOrigin? = null
    ) {
        val old = get(raw)
        val resolvedOrigin = origin ?: old.origin
        val json = JSONObject().put("schemaVersion", SCHEMA_VERSION)
            .put("dnsResolvers", JSONArray(dnsResolvers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
            .put("selectedResolver", selectedResolver.trim())
            .put("customSettings", JSONObject(customSettings.ifEmpty { old.customSettings }))
            .put("origin", resolvedOrigin.name)
        prefs.edit().putString("profile:${old.profileId}", json.toString()).apply()
    }

    @Synchronized
    fun setOrigin(raw: String, origin: TirexNetShare.ProfileOrigin) {
        val s = get(raw)
        save(raw, s.dnsResolvers, s.selectedResolver, s.customSettings, origin)
    }

    @Synchronized
    fun setDnsResolvers(raw: String, resolvers: List<String>, selected: String = "") {
        val s = get(raw)
        val clean = resolvers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val sel = selected.ifBlank { clean.firstOrNull().orEmpty() }
        save(raw, clean, sel, s.customSettings, s.origin)
    }

    /** Preserve settings when a user edits the raw profile and its content hash changes. */
    @Synchronized
    fun migrate(oldRaw: String, newRaw: String) {
        val oldId = idFor(oldRaw)
        val newHash = hash(newRaw)
        val existingId = prefs.getString("hash:$newHash", null)
        if (existingId == null) prefs.edit().putString("hash:$newHash", oldId).apply()
        else if (existingId != oldId) {
            val oldJson = prefs.getString("profile:$oldId", null)
            if (oldJson != null && prefs.getString("profile:$existingId", null) == null) {
                prefs.edit().putString("profile:$existingId", oldJson).apply()
            }
        }
    }

    @Synchronized
    fun duplicate(sourceRaw: String, newRaw: String, newOrigin: TirexNetShare.ProfileOrigin = TirexNetShare.ProfileOrigin.LOCAL_DUPLICATE) {
        val source = get(sourceRaw)
        val newId = idFor(newRaw)
        val copy = JSONObject().put("schemaVersion", SCHEMA_VERSION)
            .put("dnsResolvers", JSONArray(source.dnsResolvers))
            .put("selectedResolver", source.selectedResolver)
            .put("customSettings", JSONObject(source.customSettings))
            .put("origin", newOrigin.name)
        prefs.edit().putString("profile:$newId", copy.toString()).apply()
    }

    private fun idFor(raw: String): String {
        val key = "hash:${hash(raw)}"
        val existing = prefs.getString(key, null)
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(key, id).apply()
        return id
    }

    private fun hash(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }

    companion object { const val SCHEMA_VERSION = 3 }
}
