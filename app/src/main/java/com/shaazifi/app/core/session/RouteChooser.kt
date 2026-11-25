package com.shaazifi.app.core.session

import com.shaazifi.app.core.net.AddressNotAllowedException
import com.shaazifi.app.core.net.CertificateChangedException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Decides whether sign-in may try the router's next address (home first, then VPN).
 * Only "could not reach it" moves on. A certificate problem, a refused address or a wrong
 * password stops at once, so the password is never sent to a second address after those.
 */
object RouteChooser {
    fun tryNextAddress(e: Throwable): Boolean {
        val chain = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(10).toList()
        if (chain.any { it is CertificateChangedException || it is SSLException || it is AddressNotAllowedException || it is SecurityException }) return false
        return chain.any {
            it is ConnectException || it is SocketTimeoutException || it is NoRouteToHostException ||
                it is UnknownHostException || it is PortUnreachableException
        }
    }
}
