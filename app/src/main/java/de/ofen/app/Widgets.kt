package de.ofen.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Widgets für den Startbildschirm:
 *  - StatusWidget (4×2): Zustand, Raum/Ziel, Rauchgas, Pellets, Warnungen
 *  - ControlWidget (4×1): Ofen an/aus (mit Rückfrage), Frischluftgebläse, Zieltemperatur −/+
 *  - WoodWidget (2×2): Rauchgas im Holzbetrieb mit Warnschwelle und „Holz nachlegen?“
 * Die Daten kommen aus der Hintergrund-Überwachung (OfenService ruft push() nach jeder Abfrage auf).
 */
object Widgets {
    private const val PREF = "widgets"
    private const val FAN_MIN = 15
    private val RUN_PELLET = setOf("2", "4", "5", "6", "32")
    private val RUN_ANY = RUN_PELLET + setOf("9", "11")

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    fun any(ctx: Context): Boolean = try {
        ids(ctx, StatusWidget::class.java).isNotEmpty() || ids(ctx, ControlWidget::class.java).isNotEmpty() ||
            ids(ctx, WoodWidget::class.java).isNotEmpty()
    } catch (e: Exception) { false }

    // ------------------------------------------------------------------ Daten
    /** Neuen Status speichern und alle Widgets neu zeichnen */
    fun push(ctx: Context, s: JSONObject?, fanOn: Boolean? = null, error: String? = null) {
        if (!any(ctx)) return
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        if (s != null) p.putString("status", s.toString()).putLong("at", System.currentTimeMillis())
        if (fanOn != null) p.putBoolean("fan", fanOn)
        p.putString("error", error ?: "")
        p.apply()
        redraw(ctx)
    }

    /** Status holen (direkt oder über den Raspi) und Widgets aktualisieren – nicht im Hauptthread aufrufen */
    fun fetch(ctx: Context) {
        Stove.init(AndroidPlatform.of(ctx))
        if (!Stove.appRunning()) { push(ctx, null, error = "Funktionen aus"); return }
        val r = try { Link.handle("status", null) } catch (e: Exception) { JSONObject().put("error", e.message ?: "Fehler") }
        val d = r.optJSONObject("data")
        push(ctx, d, fanState(), if (d == null) (r.optString("error").ifBlank { "Keine Verbindung" }) else null)
    }

    /** Läuft das Frischluftgebläse gerade? (null = unbekannt) */
    fun fanState(): Boolean? = try {
        val m = Link.handle("manual", null).optJSONObject("data")
        if (m == null || m.isNull("fresh_air_bit")) null
        else m.optJSONObject("running")?.has(m.optInt("fresh_air_bit").toString()) ?: false
    } catch (e: Exception) { null }

    private fun cached(ctx: Context): Triple<JSONObject?, Long, String> {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val s = try { p.getString("status", null)?.let { JSONObject(it) } } catch (e: Exception) { null }
        return Triple(s, p.getLong("at", 0L), p.getString("error", "") ?: "")
    }

    private fun fanCached(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("fan", false)

    // ------------------------------------------------------------------ Hilfen
    private fun num(s: JSONObject, k: String): Double? = s.optString(k).toDoubleOrNull()
    private fun t0(v: Double?) = if (v == null) "–" else String.format(Locale.GERMANY, "%.0f", v)
    private fun t1(v: Double?) = if (v == null) "–" else String.format(Locale.GERMANY, "%.1f", v).removeSuffix(",0")
    private fun time(at: Long) = if (at <= 0) "" else SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(at))

    private enum class Mode { PELLET, WOOD, OFF, ERR, READY, IDLE, STALE }

    private fun mode(s: JSONObject?, stale: Boolean): Mode {
        if (s == null || stale) return Mode.STALE
        val st = s.optString("state")
        val txt = s.optString("state_text").lowercase()
        return when {
            s.optBoolean("halted") -> Mode.OFF
            txt.contains("defekt") || txt.contains("fehlgeschlagen") || txt.contains("störung") -> Mode.ERR
            s.optJSONObject("warmhold")?.optBoolean("active") == true -> Mode.READY
            s.optBoolean("wood_mode") -> Mode.WOOD
            st in RUN_PELLET -> Mode.PELLET
            else -> Mode.IDLE
        }
    }

    private fun bg(m: Mode) = when (m) {
        Mode.PELLET -> R.drawable.wbg_pellet
        Mode.WOOD -> R.drawable.wbg_wood
        Mode.ERR -> R.drawable.wbg_err
        Mode.OFF, Mode.STALE -> R.drawable.wbg_off
        else -> R.drawable.wbg_dark
    }

    private fun stateText(s: JSONObject?, err: String): String = when {
        s == null -> if (err.isNotBlank()) err else "Noch keine Daten"
        s?.optBoolean("halted") == true -> "Ausgeschaltet"
        s?.optJSONObject("early")?.optBoolean("stopped") == true && s.optBoolean("wood_mode") -> "Holzbetrieb (Pellets aus)"
        else -> s?.optString("state_text")?.ifBlank { "–" } ?: "–"
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(ctx: Context, a: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, req, Intent(ctx, WidgetAction::class.java).setAction(a),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun confirm(ctx: Context, on: Boolean): PendingIntent =
        PendingIntent.getActivity(ctx, if (on) 11 else 12,
            Intent(ctx, WidgetConfirm::class.java).putExtra("on", on)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // ------------------------------------------------------------------ Zeichnen
    fun redraw(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val (s, at, err) = cached(ctx)
        // älter als 10 min = veraltet (Überwachung läuft nicht)
        val stale = at <= 0 || System.currentTimeMillis() - at > 10 * 60_000L
        try { val v = status(ctx, s, at, err, stale); for (id in ids(ctx, StatusWidget::class.java)) mgr.updateAppWidget(id, v) } catch (e: Exception) { }
        try { val v = control(ctx, s, stale); for (id in ids(ctx, ControlWidget::class.java)) mgr.updateAppWidget(id, v) } catch (e: Exception) { }
        try { val v = wood(ctx, s, at, err, stale); for (id in ids(ctx, WoodWidget::class.java)) mgr.updateAppWidget(id, v) } catch (e: Exception) { }
    }

    private fun stamp(at: Long, err: String, stale: Boolean): String = when {
        err.isNotBlank() && at > 0 -> "$err · Stand ${time(at)}"
        err.isNotBlank() -> err
        stale && at > 0 -> "veraltet · ${time(at)}"
        else -> time(at)
    }

    private fun status(ctx: Context, s: JSONObject?, at: Long, err: String, stale: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_status)
        val m = mode(s, stale || err.isNotBlank())
        v.setInt(R.id.w_root, "setBackgroundResource", bg(m))
        v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
        v.setTextViewText(R.id.w_state, stateText(s, err))
        v.setTextViewText(R.id.w_time, stamp(at, err, stale))
        if (s == null) {
            v.setTextViewText(R.id.w_room, "–"); v.setTextViewText(R.id.w_target, "Ziel –"); v.setTextViewText(R.id.w_smoke, "Rauchgas –")
            v.setViewVisibility(R.id.w_hop_box, View.GONE); v.setViewVisibility(R.id.w_warn, View.GONE)
            return v
        }
        val th = s.optJSONObject("thermo")
        val hmTarget = th != null && th.optBoolean("enabled") && th.has("target")
        v.setTextViewText(R.id.w_room, t1(num(s, "room_temp")) + " °C")
        v.setTextViewText(R.id.w_target, "Ziel " + (if (hmTarget) t1(th!!.optDouble("target")) else t0(num(s, "target_temp"))) + " °C")
        v.setTextViewText(R.id.w_smoke, "Rauchgas " + t0(num(s, "smoke_temp")) + " °C")
        // Pellets
        val h = s.optJSONObject("hopper")
        val kg = h?.optString("kg")?.toDoubleOrNull()
        if (h != null && kg != null && h.optBoolean("known")) {
            val cap = h.optDouble("cap", 13.0).coerceAtLeast(1.0)
            v.setViewVisibility(R.id.w_hop_box, View.VISIBLE)
            v.setProgressBar(R.id.w_hop, 100, (kg / cap * 100).toInt().coerceIn(0, 100), false)
            v.setTextViewText(R.id.w_hop_t, "Pellets ${t0(kg)} / ${t0(cap)} kg")
        } else v.setViewVisibility(R.id.w_hop_box, View.GONE)
        // Hinweis
        val warn = when {
            s.optBoolean("wood_warn") -> "Holz nachlegen? Rauchgas ${t0(num(s, "smoke_temp"))} °C"
            h != null && kg != null && h.optBoolean("known") && kg <= h.optDouble("warn", 3.0) -> "Pellets knapp: ${t0(kg)} kg"
            s.optJSONObject("warmhold")?.optBoolean("active") == true -> s.optJSONObject("warmhold")!!.optString("msg")
            s.optJSONObject("early")?.optString("msg")?.isNotBlank() == true -> s.optJSONObject("early")!!.optString("msg")
            else -> ""
        }
        v.setViewVisibility(R.id.w_warn, if (warn.isBlank()) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.w_warn, warn)
        return v
    }

    private fun control(ctx: Context, s: JSONObject?, stale: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_control)
        v.setOnClickPendingIntent(R.id.w_on, confirm(ctx, true))
        v.setOnClickPendingIntent(R.id.w_off, confirm(ctx, false))
        v.setOnClickPendingIntent(R.id.w_fan, action(ctx, WidgetAction.FAN, 21))
        v.setOnClickPendingIntent(R.id.w_minus, action(ctx, WidgetAction.DOWN, 22))
        v.setOnClickPendingIntent(R.id.w_plus, action(ctx, WidgetAction.UP, 23))
        v.setOnClickPendingIntent(R.id.w_tgt, openApp(ctx))
        val st = s?.optString("state") ?: ""
        val hal = s?.optBoolean("halted") == true
        val wh = s?.optJSONObject("warmhold")?.optBoolean("active") == true
        val running = st in RUN_ANY || s?.optBoolean("wood_mode") == true
        val onAct = !stale && s != null && !hal && (running || st == "0" || wh)
        val offAct = !stale && s != null && (hal || (!wh && !running && st in setOf("14", "23")))
        v.setInt(R.id.w_on, "setBackgroundResource", if (onAct) R.drawable.wbtn_on else R.drawable.wbtn)
        v.setInt(R.id.w_off, "setBackgroundResource", if (offAct) R.drawable.wbtn_off else R.drawable.wbtn)
        val fan = fanCached(ctx)
        v.setInt(R.id.w_fan, "setBackgroundResource", if (fan) R.drawable.wbtn_fan else R.drawable.wbtn)
        v.setTextViewText(R.id.w_fan, if (fan) "Gebläse\naus" else "Gebläse\n$FAN_MIN min")
        val th = s?.optJSONObject("thermo")
        val tgt = if (th != null && th.optBoolean("enabled") && th.has("target")) t1(th.optDouble("target"))
            else t0(s?.let { num(it, "target_temp") })
        v.setTextViewText(R.id.w_tgt, "$tgt°")
        return v
    }

    private fun wood(ctx: Context, s: JSONObject?, at: Long, err: String, stale: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_wood)
        v.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
        val warnOn = s?.optBoolean("wood_warn") == true && !stale
        val m = mode(s, stale || err.isNotBlank())
        v.setInt(R.id.w_root, "setBackgroundResource", if (warnOn) R.drawable.wbg_err else bg(m))
        val smoke = s?.let { num(it, "smoke_temp") }
        val min = s?.optString("wood_min")?.toDoubleOrNull()
        v.setTextViewText(R.id.w_smoke, t0(smoke) + " °C")
        v.setProgressBar(R.id.w_bar, 400, (smoke ?: 0.0).toInt().coerceIn(0, 400), false)
        v.setTextViewText(R.id.w_min, if (min != null) "Warnung unter ${t0(min)} °C" else "Rauchgas")
        v.setTextViewText(R.id.w_hint, when {
            warnOn -> "Holz nachlegen?"
            s == null || err.isNotBlank() -> stamp(at, err, stale)
            stale -> "veraltet · ${time(at)}"
            s?.optBoolean("wood_mode") == true -> "Holzbetrieb"
            else -> stateText(s, err)
        })
        return v
    }
}

/** Gemeinsame Basis: beim Hinzufügen / regelmäßig zeichnen und frische Daten holen */
abstract class OfenWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Widgets.redraw(ctx)
        val pr = goAsync()
        Thread {
            try { Widgets.fetch(ctx) } catch (e: Exception) { }
            pr.finish()
        }.start()
    }
}

class StatusWidget : OfenWidget()
class ControlWidget : OfenWidget()
class WoodWidget : OfenWidget()

/** Knöpfe ohne Rückfrage: Gebläse, Zieltemperatur −/+ */
class WidgetAction : BroadcastReceiver() {
    companion object {
        const val FAN = "de.ofen.app.widget.FAN"
        const val UP = "de.ofen.app.widget.UP"
        const val DOWN = "de.ofen.app.widget.DOWN"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action ?: return
        val pr = goAsync()
        Thread {
            val msg = try { run(ctx, a) } catch (e: Exception) { e.message ?: "Fehler" }
            try { Widgets.fetch(ctx) } catch (e: Exception) { }
            Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
            pr.finish()
        }.start()
    }

    private fun res(r: JSONObject, okMsg: String): String =
        if (r.optBoolean("ok")) okMsg else r.optString("error").ifBlank { "Fehler" }

    private fun run(ctx: Context, a: String): String {
        Stove.init(AndroidPlatform.of(ctx))
        if (!Stove.appRunning()) return "Funktionen sind aus – App öffnen"
        when (a) {
            FAN -> {
                val m = Link.handle("manual", null).optJSONObject("data")
                    ?: return "Ofen nicht erreichbar"
                if (m.isNull("fresh_air_bit")) return "Kein Frischluftgebläse eingerichtet (Einstellungen → Ausgänge)"
                val bit = m.optInt("fresh_air_bit")
                val on = m.optJSONObject("running")?.has(bit.toString()) == true
                val body = JSONObject().put("output", bit + 1).put("on", !on)
                if (!on) body.put("minutes", 15)
                return res(Link.handle("manual/switch", body.toString()), if (on) "Frischluftgebläse aus" else "Frischluftgebläse an für 15 min")
            }
            UP, DOWN -> {
                val d = if (a == UP) 1 else -1
                val s = Link.handle("status", null).optJSONObject("data") ?: return "Ofen nicht erreichbar"
                val th = s.optJSONObject("thermo")
                if (th != null && th.optBoolean("enabled") && th.has("target")) {
                    // Homematic-Raumsteuerung: deren Ziel in 0,5-°C-Schritten
                    val t = (th.optDouble("target") + d * 0.5).coerceIn(15.0, 25.0)
                    return res(Link.handle("config", JSONObject().put("hm_ctrl_target", t).toString()),
                        "Zieltemperatur ${String.format(Locale.GERMANY, "%.1f", t)} °C")
                }
                val cur = s.optString("target_temp").toDoubleOrNull()?.toInt() ?: return "Zieltemperatur unbekannt"
                val t = (cur + d).coerceIn(5, 35)
                return res(Link.handle("target", JSONObject().put("temp", t).toString()), "Zieltemperatur $t °C")
            }
        }
        return ""
    }
}

/** Ofen an / aus vom Widget – immer mit Rückfrage */
class WidgetConfirm : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val on = intent.getBooleanExtra("on", true)
        val ctx = applicationContext
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (on) "Ofen einschalten?" else "Ofen ausschalten?")
            .setMessage(if (on) "Der Ofen startet, und die Automatiken der App sind wieder aktiv. Nur einschalten, wenn Brennraum und Umgebung in Ordnung sind."
                else "Der Ofen stoppt, und alle Automatiken der App pausieren (Raumsteuerung, Pellet-Neustart, Heizkörper-Steuerung), bis du wieder einschaltest.")
            .setPositiveButton(if (on) "Einschalten" else "Ausschalten") { _, _ ->
                Thread {
                    val msg = try {
                        Stove.init(AndroidPlatform.of(ctx))
                        if (!Stove.appRunning()) "Funktionen sind aus – App öffnen"
                        else {
                            val r = Link.handle(if (on) "start" else "stop", "{}")
                            if (r.optBoolean("ok")) (if (on) "Ofen eingeschaltet" else "Ofen ausgeschaltet – Automatiken pausiert")
                            else r.optString("error").ifBlank { "Fehler" }
                        }
                    } catch (e: Exception) { e.message ?: "Fehler" }
                    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                    try { Thread.sleep(1500); Widgets.fetch(ctx) } catch (e: Exception) { }
                }.start()
                finish()
            }
            .setNegativeButton("Abbrechen") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
