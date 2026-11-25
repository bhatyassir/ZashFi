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

    fun lookup(mac: String): String? {
        val hex = mac.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
        if (Makers.isRandomized(mac)) return null
        return byPrefix[hex.take(9)] ?: byPrefix[hex.take(7)] ?: byPrefix[hex.take(6)]
    }
}

object Makers {
    fun parse(text: String): MakerTable = MakerTable(
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()
    )

    /**
     * True for "locally administered" addresses (bit 0x02 of the first byte), which phones and
     * laptops use as private random addresses: they have no maker.
     */
    fun isRandomized(mac: String): Boolean {
        val first = mac.filter { it.isLetterOrDigit() }.take(2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }

    fun label(table: MakerTable?, mac: String): String? =
        if (isRandomized(mac)) "Private address" else table?.lookup(mac)

    @Volatile private var cached: MakerTable? = null

    /** Loads the bundled list once (about 1.5 MB of text), off the main thread. */
    suspend fun load(context: Context): MakerTable = cached ?: withContext(Dispatchers.IO) {
        cached ?: parse(context.assets.open("oui/makers.tsv").bufferedReader().use { it.readText() }).also { cached = it }
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
