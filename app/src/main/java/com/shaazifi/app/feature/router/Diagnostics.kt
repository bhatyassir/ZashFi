package com.shaazifi.app.feature.router

/**
 * M10b network diagnostics run on the router: ping, traceroute and DNS lookup of one typed host.
 * Commands and arguments copy LuCI's diagnostics page (luci-mod-network diagnostics.js and its
 * ACL acl.d/luci-mod-network.json, master, read 2026-11-17). Only the host varies, and only a
 * plain host name or IPv4 address is accepted, so nothing else can reach the router's shell.
 */
object Diagnostics {

    enum class Tool(val label: String) { PING("Ping"), TRACEROUTE("Trace route"), LOOKUP("DNS lookup") }

    private val HOSTNAME = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

    /** A host name (letters, digits, dots, hyphens; not starting or ending with - or .) or an IPv4 address. */
    fun isSafeHost(host: String): Boolean = host.length in 1..253 && HOSTNAME.matches(host)

    private val PING_ARGS = listOf("-4", "-c", "5", "-W", "1")
    private val TRACE_ARGS = listOf("-4", "-q", "1", "-w", "1", "-n", "-m", "20")

    /** The command lines to try, in order (OpenWrt has the tools in /bin or /usr/bin). Null for an unsafe host. */
    fun commandsFor(tool: Tool, host: String): List<Pair<String, List<String>>>? {
        if (!isSafeHost(host)) return null
        return when (tool) {
            Tool.PING -> listOf("/bin/ping", "/usr/bin/ping").map { it to PING_ARGS + host }
            Tool.TRACEROUTE -> listOf("/bin/traceroute", "/usr/bin/traceroute").map { it to TRACE_ARGS + host }
            Tool.LOOKUP -> listOf("/usr/bin/nslookup" to listOf(host))
        }
    }

    /** True when [command] with [params] is exactly one of the lines [commandsFor] can produce. */
    fun isAllowed(command: String, params: List<String>): Boolean {
        val host = params.lastOrNull() ?: return false
        return Tool.entries.any { t -> commandsFor(t, host)?.contains(command to params) == true }
    }

    /** Sample output for demo mode (no router contacted). */
    fun demoOutput(tool: Tool, host: String): String = when (tool) {
        Tool.PING -> "PING $host (203.0.113.10): 56 data bytes\n64 bytes from 203.0.113.10: seq=0 ttl=57 time=12.4 ms\n" +
            "64 bytes from 203.0.113.10: seq=1 ttl=57 time=11.9 ms\n\n--- $host ping statistics ---\n5 packets transmitted, 5 packets received, 0% packet loss"
        Tool.TRACEROUTE -> "traceroute to $host (203.0.113.10), 20 hops max\n 1  192.168.1.1  0.6 ms\n 2  10.0.0.1  4.1 ms\n 3  203.0.113.10  12.0 ms"
        Tool.LOOKUP -> "Server:\t\t127.0.0.1\nAddress:\t127.0.0.1:53\n\nName:\t$host\nAddress: 203.0.113.10"
    }
}
