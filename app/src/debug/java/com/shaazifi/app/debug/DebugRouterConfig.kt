package com.shaazifi.app.debug

import com.shaazifi.app.core.net.TrustStore

/**
 * Release-variant stub.
 * Production release builds strictly contain no test router coordinates or fingerprints.
 */
object DebugRouterConfig {
    const val IS_DEBUG: Boolean = false
    const val ROUTER_HOST: String = ""
    const val ROUTER_HTTPS_URL: String = ""
    const val ROUTER_HTTP_URL: String = ""
    const val ROUTER_CERT_FINGERPRINT: String = ""

    fun seedDebugPins(trustStore: TrustStore) {
        // No-op in release builds
    }
}

