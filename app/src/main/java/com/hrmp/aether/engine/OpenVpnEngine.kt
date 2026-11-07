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
    /** True while a start request is being processed; a second request is ignored (no duplicate starts). */
    @Volatile var isStarting: Boolean = false
        private set
    /** Shown when no core is bundled. Contains "failed" so the UI reports a failure instead of waiting forever. */
    const val NO_CORE_MESSAGE = "OpenVPN failed to start: this build does not include an OpenVPN core."

    /** Config directives that make a core run external programs. They are refused, never passed on. */
    private val dangerous = Regex("(?im)^\\s*(up|down|route-up|route-pre-down|ipchange|tls-verify|auth-user-pass-verify|client-connect|client-disconnect|learn-address|plugin|script-security|management)\\b")

    /** Returns a user-readable problem (without any config content) or null when the profile looks usable. */
    fun validate(raw: String): String? {
        if (!Regex("(?im)^\\s*remote\\s+\\S+").containsMatchIn(raw)) return "OpenVPN profile has no remote server."
        if (dangerous.containsMatchIn(raw)) return "OpenVPN profile uses script/plugin directives, which are not allowed."
        val hasCa = raw.contains("<ca>", ignoreCase = true) || raw.contains("<pkcs12>", ignoreCase = true) ||
            Regex("(?im)^\\s*ca\\s+\\S+").containsMatchIn(raw)
        if (!hasCa) return "OpenVPN profile has no CA certificate (inline <ca> block expected)."
        if (OvpnImport.referencedFiles(raw).isNotEmpty())
            return "OpenVPN profile points to separate files (${OvpnImport.referencedFiles(raw).take(4).joinToString(", ")}). Import the .ovpn together with those files."
        return null
    }

    fun start(context: Context, raw: String, callback: (String, Boolean) -> Unit) {
        if (isStarting) { callback("OpenVPN is already starting.", false); return }
        validate(raw)?.let { callback("OpenVPN profile invalid: $it", false); return }
        val c = core
        if (c == null) {
            callback(NO_CORE_MESSAGE, false)
            return
        }
        isStarting = true
        try {
            c.start(context.applicationContext, raw, null, null) { message, active ->
                isActive = active
                isStarting = false
                callback(ConfigSanitizer.redact(message), active)
            }
        } catch (e: Throwable) {
            // A core that throws must not take the app down; the real reason is kept for diagnostics.
            isActive = false
            isStarting = false
            lastError = e.javaClass.simpleName + ": " + ConfigSanitizer.redact(e.message.orEmpty()).take(160)
            callback("OpenVPN failed to start. Check the selected profile and try again.", false)
        }
    }

    /** Short, redacted reason of the last start failure (for diagnostics only). */
    @Volatile var lastError: String = ""
        private set

    fun stop(callback: (String, Boolean) -> Unit) {
        TirexNotifier.stopUpdates()
        isStarting = false
        val c = core
        if (c == null) { isActive = false; callback("Disconnected.", false); return }
        try {
            c.stop { message, active -> isActive = active; callback(ConfigSanitizer.redact(message), active) }
        } catch (e: Throwable) {
            callback("Disconnect could not be completed. Please try again.", isActive)
        }
    }
}
