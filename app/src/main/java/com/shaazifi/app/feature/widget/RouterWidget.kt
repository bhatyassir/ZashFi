package com.shaazifi.app.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shaazifi.app.MainActivity
import com.shaazifi.app.R
import com.shaazifi.app.ShaaziFiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Date

/**
 * What the home-screen widget shows (M10e). Only a router name, three status values and a time:
 * no address, MAC, token or password (RouterWidgetTest checks the field names).
 */
@Serializable
data class WidgetSnapshot(
    val routerId: String,
    val routerName: String,
    val internetUp: Boolean? = null,
    val devicesOnline: Int? = null,
    val adguardOn: Boolean? = null,
    val updatedAt: Long
) {
    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(s: String?): WidgetSnapshot? = s?.let { runCatching { json.decodeFromString<WidgetSnapshot>(it) }.getOrNull() }

        /** Applies new values; another router starts from empty values. */
        fun merge(
            old: WidgetSnapshot?, routerId: String, routerName: String, now: Long,
            internetUp: Boolean? = null, devicesOnline: Int? = null, adguardOn: Boolean? = null
        ): WidgetSnapshot {
            val base = old?.takeIf { it.routerId == routerId }
            return WidgetSnapshot(
                routerId = routerId,
                routerName = routerName,
                internetUp = internetUp ?: base?.internetUp,
                devicesOnline = devicesOnline ?: base?.devicesOnline,
                adguardOn = adguardOn ?: base?.adguardOn,
                updatedAt = now
            )
        }
    }
}

object WidgetText {
    fun lines(s: WidgetSnapshot?, emptyMessage: String, time: (Long) -> String): List<String> {
        if (s == null) return listOf(emptyMessage)
        return buildList {
            add("Internet: " + when (s.internetUp) { true -> "online"; false -> "down"; null -> "unknown" })
            s.devicesOnline?.let { add(if (it == 1) "1 device online" else "$it devices online") }
            s.adguardOn?.let { add("AdGuard: " + if (it) "on" else "off") }
            add("Updated ${time(s.updatedAt)}")
        }
    }
}

/** Keeps the last snapshot in app storage (never backed up: data_extraction_rules.xml). */
class WidgetStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("widget_snapshot")

    suspend fun read(): WidgetSnapshot? = WidgetSnapshot.decode(dataStore.data.first()[key])

    suspend fun update(
        context: Context, routerId: String, routerName: String,
        internetUp: Boolean? = null, devicesOnline: Int? = null, adguardOn: Boolean? = null
    ) {
        var changed = false
        dataStore.edit { p ->
            val old = WidgetSnapshot.decode(p[key])
            val now = System.currentTimeMillis()
            val merged = WidgetSnapshot.merge(old, routerId, routerName, now, internetUp, devicesOnline, adguardOn)
            // Home refreshes every few seconds: write only on a change, or at most once a minute.
            if (old == null || merged.copy(updatedAt = old.updatedAt) != old || now - old.updatedAt >= 60_000) {
                p[key] = merged.encode()
                changed = true
            }
        }
        if (changed) RouterWidget.refreshAll(context)
    }

    /** Forgetting a router empties the widget if it showed that router. */
    suspend fun clearIf(context: Context, routerId: String) {
        dataStore.edit { p -> if (WidgetSnapshot.decode(p[key])?.routerId == routerId) p.remove(key) }
        RouterWidget.refreshAll(context)
    }
}

/**
 * Home-screen widget (RemoteViews, no extra library). It never signs in or contacts the router:
 * the app pushes what it last saw (Home screen, AdGuard screen, alert checks). Tap opens the app.
 */
class RouterWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = (context.applicationContext as ShaaziFiApp).container.widgetStore
                val views = render(context, runCatching { store.read() }.getOrNull())
                appWidgetIds.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun render(context: Context, s: WidgetSnapshot?): RemoteViews {
            val fmt = DateFormat.getTimeFormat(context)
            val lines = WidgetText.lines(s, context.getString(R.string.widget_empty)) { fmt.format(Date(it)) }
            return RemoteViews(context.packageName, R.layout.widget_router).apply {
                setTextViewText(R.id.widget_title, s?.routerName ?: context.getString(R.string.app_name))
                setTextViewText(R.id.widget_body, lines.joinToString("\n"))
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 0,
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }

        /** Re-draws every placed widget from the stored snapshot. */
        suspend fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, RouterWidget::class.java))
            if (ids.isEmpty()) return
            val store = (context.applicationContext as ShaaziFiApp).container.widgetStore
            val views = render(context, runCatching { store.read() }.getOrNull())
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
