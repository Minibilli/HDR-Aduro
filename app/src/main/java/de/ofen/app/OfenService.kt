package de.ofen.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Läuft im Hintergrund: fragt den Ofen regelmäßig ab (Warnungen, Automatik)
 * und schaltet Ausgänge im Handbetrieb nach Ablauf der Zeit ab.
 */
class OfenService : Service() {
    @Volatile private var running = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Stove.init(AndroidPlatform.of(this))
        Notifier.channels(this)
        val n = Notifier.serviceNotification(this, "Verbinde …")
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_SERVICE, n, type)
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ofen:monitor").apply { setReferenceCounted(false) }
        running = true
        Thread(::monitorLoop, "ofen-monitor").start()
        Thread(::manualLoop, "ofen-manual").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SKIP_SHUTOFF) {
            // „Diesmal nicht abschalten“ in der Vorwarnung (gekoppelt: über den Raspi, wenn der steuert)
            Thread {
                try {
                    val r = Link.handle("shutoff/skip", org.json.JSONObject().put("on", true).toString())
                    val i = r.optJSONObject("data")
                    Notifier.clear(this, Notifier.ID_SHUTOFF)
                    if (i != null) Notifier.stateChange(this, "Abschaltung ausgelassen", "Der Ofen geht ${i.optString("next_text")} nicht aus.")
                    else Notifier.stateChange(this, "Auslassen fehlgeschlagen", r.optString("error"))
                } catch (e: Exception) { }
            }.start()
            return START_STICKY
        }
        if (intent?.action == ACTION_QUIT) {
            // „Beenden“ in der Benachrichtigung
            Thread {
                try { Stove.appQuit() } catch (e: Exception) { }
                try { Widgets.push(this, null, error = "Funktionen aus") } catch (e: Exception) { }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }.start()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (Stove.man.active || Stove.man.deadline.isNotEmpty())
            Thread { try { Stove.manualAllOff() } catch (e: Exception) { } }.start()
        wake?.release()
        super.onDestroy()
    }

    private fun monitorLoop() {
        while (running) {
            // Mit Raspi gekoppelt: Herzschlag (wenn dieses Handy steuert) bzw. Abgleich + Meldungen vom Raspi
            val viaRaspi = try { Link.tick() } catch (e: Exception) { null }
            if (viaRaspi != null) {
                // Raspi überwacht den Ofen – das Handy fragt ihn nicht zusätzlich selbst ab
                Notifier.updateService(this, "Raspi · $viaRaspi")
                if (Widgets.any(this)) try { Widgets.fetch(this) } catch (e: Exception) { }
                sleep(Control.HB_MS)
                continue
            }
            if (Stove.configured()) {
                try {
                    wake?.acquire(20_000)
                    val s = Stove.readStatus()
                    val txt = Stove.summary(s)
                    try { Widgets.push(this, s, Widgets.fanState()) } catch (e: Exception) { }
                    Notifier.updateService(this, when {
                        !Link.linked() -> txt
                        Control.active() -> "Steuerung hier · $txt"
                        Control.isHolder() -> "Raspi nicht erreichbar – Automatik pausiert · $txt"
                        else -> "Raspi nicht erreichbar – nur Anzeige · $txt"
                    })
                } catch (e: Exception) {
                    Notifier.updateService(this, "Keine Verbindung zum Ofen")
                    try { Widgets.push(this, null, error = "Keine Verbindung") } catch (e: Exception) { }
                    Stove.valveOfflineCheck()
                } finally {
                    wake?.let { if (it.isHeld) it.release() }
                }
            } else {
                Notifier.updateService(this, "Noch nicht eingerichtet")
            }
            val pause = if (Stove.busy() || Stove.lastStatus?.optBoolean("wood_mode") == true) 10_000L
                else if (Stove.cfg().optBoolean("hm_ctrl")) 20_000L else 30_000L
            sleep(pause)
        }
    }

    private fun manualLoop() {
        while (running) {
            Stove.manualTick()
            sleep(2_000)
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) { }

    companion object {
        const val ACTION_QUIT = "de.ofen.app.QUIT"
        const val ACTION_SKIP_SHUTOFF = "de.ofen.app.SKIP_SHUTOFF"

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, OfenService::class.java)) } catch (e: Exception) { }
        }

        fun start(ctx: Context) {
            if (!Stove.appRunning()) return   // erst nach „Funktionen starten“
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, OfenService::class.java))
            } catch (e: Exception) { }
        }
    }
}
