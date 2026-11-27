package com.shaazifi.app.feature.reconnect

import com.shaazifi.app.ui.theme.PillTone

/** Words and countdown maths for the reconnect screen. Unit-tested. */
object ReconnectDisplay {

    data class Copy(val title: String, val body: String, val pill: String, val tone: PillTone)

    /** Share of the safety window still left, for the countdown ring (1 = full, 0 = empty). */
    fun ringFraction(remainingSeconds: Int, totalSeconds: Int): Float {
        if (totalSeconds <= 0) return 0f
        return (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    }

    fun copy(state: ReconnectState, routerName: String): Copy = when (state) {
        is ReconnectState.Probing -> Copy(
            title = "Reconnecting to $routerName",
            body = "Waiting for the router to answer. If a settings change cut the connection, " +
                "the router puts the old settings back by itself after about two minutes.",
            pill = "Waiting",
            tone = PillTone.Off
        )
        is ReconnectState.ReconnectedKept -> Copy(
            title = "Back in touch",
            body = "The router answered at its new address with the same security certificate.",
            pill = "Connected",
            tone = PillTone.Ok
        )
        is ReconnectState.ReconnectedSameAddress -> Copy(
            title = "Back in touch",
            body = "$routerName answered with the same security certificate.",
            pill = "Connected",
            tone = PillTone.Ok
        )
        is ReconnectState.ReconnectedRolledBack -> Copy(
            title = "Old settings restored",
            body = "The router did not answer at the new address, so it went back to its previous settings. Nothing was lost.",
            pill = "Rolled back",
            tone = PillTone.Warn
        )
        is ReconnectState.CertificateChanged -> Copy(
            title = "Stopped: certificate changed",
            body = "The router showed a different security certificate from the one you approved. " +
                "The app stopped so your password is not sent to a device it cannot trust.",
            pill = "Stopped",
            tone = PillTone.Bad
        )
        ReconnectState.TimeoutUnreachable -> Copy(
            title = "Router not reachable",
            body = "The router did not answer within ${ReconnectProber.DEFAULT_DURATION_SECONDS} seconds. " +
                "Check that this phone is on the router's Wi-Fi or VPN, then try again.",
            pill = "No answer",
            tone = PillTone.Bad
        )
    }
}
