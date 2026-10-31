package com.tirexnet.app.engine

import android.content.Context

/**
 * What an OpenVPN core library has to provide. The core (ics-openvpn / openvpn3 or similar) is a native
 * library and is NOT bundled in this project yet, so [OpenVpnEngine.core] is null until one is plugged in:
 *
 *   OpenVpnEngine.core = MyOpenVpnCore()   // e.g. in Application.onCreate or before the first connect
 *
 * Until then connecting reports a clear failure instead of pretending to work.
 */
interface OpenVpnCore {
    /** Start the tunnel. Report progress/result through [onState] (message, connected). Never log [config]. */
    fun start(context: Context, config: String, username: String?, password: String?, onState: (String, Boolean) -> Unit)
    fun stop(onState: (String, Boolean) -> Unit)
}

object OpenVpnEngine {
    @Volatile var core: OpenVpnCore? = null
    @Volatile var isActive: Boolean = false
        private set

    /** Config directives that make a core run external programs. They are refused, never passed on. */
    private val dangerous = Regex("(?im)^\\s*(up|down|route-up|route-pre-down|ipchange|tls-verify|auth-user-pass-verify|client-connect|client-disconnect|learn-address|plugin|script-security|management)\\b")

    /** Returns a user-readable problem (without any config content) or null when the profile looks usable. */
    fun validate(raw: String): String? {
        if (!Regex("(?im)^\\s*remote\\s+\\S+").containsMatchIn(raw)) return "OpenVPN profile has no remote server."
        if (dangerous.containsMatchIn(raw)) return "OpenVPN profile uses script/plugin directives, which are not allowed."
        val hasCa = raw.contains("<ca>", ignoreCase = true) || Regex("(?im)^\\s*ca\\s+\\S+").containsMatchIn(raw)
        if (!hasCa) return "OpenVPN profile has no CA certificate (inline <ca> block expected)."
        if (Regex("(?im)^\\s*(ca|cert|key|tls-auth|tls-crypt|pkcs12)\\s+[^<\\s]").containsMatchIn(raw))
            return "OpenVPN profile points to separate files. Use a single .ovpn with inline <ca>/<cert>/<key> blocks."
        return null
    }

    fun start(context: Context, raw: String, callback: (String, Boolean) -> Unit) {
        validate(raw)?.let { callback(it, false); return }
        val c = core
        if (c == null) {
            callback("OpenVPN core is not included in this build yet.", false)
            return
        }
        try {
            c.start(context.applicationContext, raw, null, null) { message, active ->
                isActive = active
                callback(ConfigSanitizer.redact(message), active)
            }
        } catch (e: Throwable) {
            isActive = false
            callback("OpenVPN failed: " + ConfigSanitizer.redact(e.message ?: e.javaClass.simpleName), false)
        }
    }

    fun stop(callback: (String, Boolean) -> Unit) {
        TirexNotifier.stopUpdates()
        val c = core
        if (c == null) { isActive = false; callback("Disconnected.", false); return }
        try {
            c.stop { message, active -> isActive = active; callback(ConfigSanitizer.redact(message), active) }
        } catch (e: Throwable) {
            callback("Disconnect failed: " + ConfigSanitizer.redact(e.message ?: e.javaClass.simpleName), isActive)
        }
    }
}
