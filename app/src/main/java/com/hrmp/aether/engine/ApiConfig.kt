package com.tirexnet.app.engine

import java.io.IOException

/**
 * Backend addresses, tried in this order.
 *
 * SERVER_BASE: the direct TIREXNET VPS endpoint. HTTPS only.
 * WORKER_BASE: disabled; all app API traffic now goes to the VPS.
 *
 * Never put server logins (SSH/panel passwords) in the app: anyone can unpack an APK.
 */
object ApiConfig {
    const val IRAN_BASE = "https://netherrap.extraservices.ir"
    const val WORKER_BASE = ""

    /** After a network failure the Iranian server is skipped for this long, so every request does not wait for its timeout. */
    const val IRAN_RETRY_AFTER_MS = 5 * 60 * 1000L
    const val FAST_FAIL_TIMEOUT_MS = 4000
    const val NORMAL_TIMEOUT_MS = 15000
}

/** A reachable server answered with an HTTP error. The message format ("HTTP 401: ...") is relied on by callers. */
class ApiHttpException(val code: Int, body: String) : IllegalStateException("HTTP $code: $body")

/** No server could be reached, or a token belongs to another server. Never means "session expired". */
class ApiUnavailableException(message: String) : IOException(message)
