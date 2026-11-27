package com.shaazifi.app.debug

import androidx.compose.runtime.Composable
import com.shaazifi.app.AppContainer
import com.shaazifi.app.core.net.TrustStore

/**
 * Release-variant stub.
 * Production release builds strictly contain no test router coordinates, fingerprints, or self-test UI.
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

    @Composable
    fun SelfTestOverlay(container: AppContainer, onBack: () -> Unit) {
        // No-op in release builds
    }
}
