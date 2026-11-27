package com.shaazifi.app.core.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

interface PinStore {
    fun loadAll(): Map<String, String>
    fun save(key: String, fingerprint: String)
    fun remove(key: String)
}

class DataStorePinStore(private val dataStore: DataStore<Preferences>) : PinStore {
    private val prefix = "tls_pin_"

    override fun loadAll(): Map<String, String> = runBlocking {
        val prefs = dataStore.data.first()
        buildMap {
            prefs.asMap().forEach { (k, v) ->
                if (k.name.startsWith(prefix) && v is String && v.isNotBlank()) {
                    put(k.name.removePrefix(prefix), v)
                }
            }
        }
    }

    override fun save(key: String, fingerprint: String) {
        runBlocking {
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("$prefix$key")] = fingerprint
            }
        }
    }

    override fun remove(key: String) {
        runBlocking {
            dataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey("$prefix$key"))
            }
        }
    }
}

/**
 * Exception thrown when a server presents a certificate that does not match the
 * previously pinned fingerprint.
 *
 * This is a HARD STOP: the connection must be aborted immediately and never
 * accepted silently, alerting the user to potential MITM or configuration reset.
 */
class CertificateChangedException(
    val host: String,
    val expectedFingerprint: String,
    val actualFingerprint: String,
    message: String = "Certificate fingerprint mismatch for '$host'! Expected: $expectedFingerprint, Got: $actualFingerprint. Hard stop to prevent impersonation or MITM."
) : SSLPeerUnverifiedException(message)

/**
 * Exception thrown when a server presents a certificate that has not been approved/pinned by the user yet.
 * The connection is refused during TLS handshake.
 * The UI catches this exception to extract [presentedFingerprint] and prompt the user for approval.
 */
class UntrustedCertificateException(
    val host: String,
    val presentedFingerprint: String,
    val certificate: X509Certificate,
    message: String = "No pinned certificate for '$host'. Server presented fingerprint: $presentedFingerprint. User approval required before connecting."
) : SSLPeerUnverifiedException(message)

/**
 * TrustStore manages Trust-On-First-Use (TOFU) certificate pinning for self-signed
 * router certificates.
 */
class TrustStore(private val pinStore: PinStore? = null) {

    // Maps "host:port" or "host" -> uppercase SHA-256 fingerprint ("XX:XX:XX:...")
    private val pins = ConcurrentHashMap<String, String>().apply {
        pinStore?.loadAll()?.forEach { (k, v) -> put(k, v) }
    }
    private val lastObservedFingerprints = ConcurrentHashMap<String, String>()

    fun pin(hostOrKey: String, fingerprint: String) {
        val fp = fingerprint.trim().uppercase()
        if (fp == "HTTP_NO_PIN" || fp.isEmpty()) return
        val key = normalizeKey(hostOrKey)
        pins[key] = fp
        pinStore?.save(key, fp)
    }

    fun getPinned(hostOrKey: String): String? {
        val key = normalizeKey(hostOrKey)
        return pins[key] ?: pins[hostOnly(key)]
    }

    fun getObserved(hostOrKey: String): String? {
        val key = normalizeKey(hostOrKey)
        return lastObservedFingerprints[key] ?: lastObservedFingerprints[hostOnly(key)]
    }

    fun clearPin(hostOrKey: String) {
        val key = normalizeKey(hostOrKey)
        pins.remove(key)
        pinStore?.remove(key)
    }

    private fun normalizeKey(key: String): String {
        return key.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore("/")
    }

    private fun hostOnly(normalized: String): String {
        if (normalized.startsWith("[")) {
            return normalized.substringAfter("[").substringBefore("]")
        }
        return if (normalized.count { it == ':' } == 1) {
            normalized.substringBefore(":")
        } else {
            normalized
        }
    }

    val trustManager: X509TrustManager = object : X509ExtendedTrustManager() {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // No client cert validation needed
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            // No client cert validation needed
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            // No client cert validation needed
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            validateChain(chain, hostHint = null)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            val host = extractHostFromSocket(socket)
            validateChain(chain, hostHint = host)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            val host = if (engine != null) "${engine.peerHost}:${engine.peerPort}" else null
            validateChain(chain, hostHint = host)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun extractHostFromSocket(socket: Socket?): String? {
            if (socket == null) return null
            val peerHost = if (socket is SSLSocket) {
                try {
                    socket.handshakeSession?.peerHost
                } catch (_: Exception) {
                    null
                }
            } else null

            val host = peerHost ?: socket.inetAddress?.hostAddress
            val port = socket.port
            if (host == null) return null
            val formattedHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
            return if (port > 0) "$formattedHost:$port" else formattedHost
        }

        private fun validateChain(chain: Array<out X509Certificate>?, hostHint: String?) {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("Server presented an empty certificate chain")
            }

            val serverCert = chain[0]
            val actualFp = fingerprint(serverCert)
            if (hostHint != null) {
                lastObservedFingerprints[normalizeKey(hostHint)] = actualFp
            }

            // 1. Check for an exact host:port or host pin (never fall back to another host's pin)
            if (hostHint != null) {
                val normalized = normalizeKey(hostHint)
                val host = hostOnly(normalized)
                val expectedFp = pins[normalized] ?: pins[host] ?: pins["[$host]"]
                if (expectedFp != null) {
                    if (!actualFp.equals(expectedFp, ignoreCase = true)) {
                        throw CertificateChangedException(hostHint, expectedFp, actualFp)
                    }
                    return
                }
            }

            // 2. No pin exists for this host: refuse until user explicitly approves (TOFU)
            throw UntrustedCertificateException(hostHint ?: "unknown", actualFp, serverCert)
        }
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        sslContext.socketFactory
    }

    /**
     * HostnameVerifier that works in concert with TrustStore's certificate pinning.
     *
     * Self-signed router certificates generated by OpenWrt (uhttpd) typically have
     * CN=OpenWrt and do not include the router's IP address (e.g. 192.168.1.1)
     * in Subject Alternative Names. For pinned routers, trust is authenticated by the exact
     * SHA-256 certificate fingerprint. If a pin matches the presented certificate, this
     * verifier accepts the connection. If unpinned or mismatched, it rejects (never trust-all).
     */
    val hostnameVerifier: HostnameVerifier by lazy {
        HostnameVerifier { hostname, session ->
            try {
                val peerCerts = session.peerCertificates
                if (peerCerts.isNullOrEmpty()) return@HostnameVerifier false
                val serverCert = peerCerts[0] as? X509Certificate ?: return@HostnameVerifier false
                val actualFp = fingerprint(serverCert)

                // 1. Check if pinned for this specific host or host:port
                val normalized = normalizeKey(hostname)
                val host = hostOnly(normalized)
                val portSuffix = if (session.peerPort > 0) ":${session.peerPort}" else ""
                val hostWithPort = if (host.contains(":")) "[$host]$portSuffix" else "$host$portSuffix"
                val expectedFp = pins[hostWithPort] ?: pins[normalized] ?: pins[host]

                if (expectedFp != null) {
                    return@HostnameVerifier actualFp.equals(expectedFp, ignoreCase = true)
                }

                // 2. Never trust-all or cross-host match: unpinned certificates are refused
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        /**
         * Computes the SHA-256 fingerprint of an X.509 certificate in uppercase
         * colon-delimited format (e.g. "XX:XX:XX:...").
         */
        fun fingerprint(cert: X509Certificate): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(cert.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }
    }
}
