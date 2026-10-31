package com.tirexnet.app.engine

import java.io.IOException

/**
 * Cloudflare-only backend configuration.
 * All account, subscription, and profile API requests are sent to this Worker.
 * No Iran VPS/Tunnel URL is configured in the Android application.
 */
object ApiConfig {
    const val WORKER_BASE = "https://hrmp.hrmpdownloader.kdns.fr"
    const val CONNECT_TIMEOUT_MS = 15000
    const val READ_TIMEOUT_MS = 20000
}

/** A reachable Worker answered with an HTTP error. */
class ApiHttpException(val code: Int, body: String) : IllegalStateException("HTTP $code: $body")

/** The Cloudflare Worker could not be reached. */
class ApiUnavailableException(message: String) : IOException(message)
