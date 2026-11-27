package com.shaazifi.app.feature.devices

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Device maker by hardware-address prefix (phase 5, owner's choice). Data: Wireshark's `manuf`
 * list, built from the IEEE registration authority's listings, converted by tools/make_makers.py
 * into assets/oui/makers.tsv (see CREDITS.md). Prefixes of 24, 28 and 36 bits; the longest wins.
 */
class MakerTable(private val byPrefix: Map<String, String>) {
    val size get() = byPrefix.size

    fun lookup(mac: String): String? = lookupInternal(mac, checkRandomized = true)

    internal fun lookupInternal(mac: String, checkRandomized: Boolean): String? {
        val hex = Makers.extractMacHex(mac) ?: return null
        if (checkRandomized && (Makers.hexNibble(hex[1]) and 0x02) != 0) return null
        val full = String(hex)
        return byPrefix[full.substring(0, 9)] ?: byPrefix[full.substring(0, 7)] ?: byPrefix[full.substring(0, 6)]
    }
}

object Makers {
    internal fun hexNibble(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'A'..'F' -> ch - 'A' + 10
        in 'a'..'f' -> ch - 'a' + 10
        else -> -1
    }

    internal fun extractMacHex(mac: String): CharArray? {
        val out = CharArray(12)
        var len = 0
        for (i in 0 until mac.length) {
            val ch = mac[i]
            if (!ch.isLetterOrDigit()) continue
            val n = hexNibble(ch)
            if (n < 0 || len >= 12) return null
            out[len++] = if (ch in 'a'..'f') (ch - 32) else ch
        }
        return if (len == 12) out else null
    }

    fun parse(lines: Sequence<String>): MakerTable {
        val map = HashMap<String, String>(48_000)
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
        }
        return MakerTable(map)
    }

    fun parse(text: String): MakerTable = parse(text.lineSequence())

    /**
     * True for "locally administered" addresses (bit 0x02 of the first byte), which phones and
     * laptops use as private random addresses: they have no maker.
     */
    fun isRandomized(mac: String): Boolean {
        var sawFirst = false
        for (i in 0 until mac.length) {
            val ch = mac[i]
            if (!ch.isLetterOrDigit()) continue
            val n = hexNibble(ch)
            if (n < 0) return false
            if (!sawFirst) {
                sawFirst = true
            } else {
                return (n and 0x02) != 0
            }
        }
        return false
    }

    fun label(table: MakerTable?, mac: String): String? =
        if (isRandomized(mac)) "Private address" else table?.lookupInternal(mac, checkRandomized = false)

    @Volatile private var cached: MakerTable? = null

    /** Streams the bundled list line-by-line once, off the main thread. */
    suspend fun load(context: Context): MakerTable = cached ?: withContext(Dispatchers.IO) {
        cached ?: context.assets.open("oui/makers.tsv").bufferedReader().useLines { lines ->
            parse(lines)
        }.also { cached = it }
    }
}

/** The maker table for screens; null until loaded (rows simply show no maker meanwhile). */
@Composable
fun rememberMakers(): MakerTable? {
    val context = LocalContext.current
    var table by remember { mutableStateOf<MakerTable?>(null) }
    LaunchedEffect(Unit) { table = runCatching { Makers.load(context.applicationContext) }.getOrNull() }
    return table
}
