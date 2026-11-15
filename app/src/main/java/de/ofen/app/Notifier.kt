package de.ofen.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_SERVICE = "service"
    const val CH_WARN = "warn"
    const val ID_SERVICE = 1
    const val ID_WOOD = 2
    const val ID_EARLY = 3
    const val ID_STATE = 4
    const val ID_HOPPER = 5
    const val ID_CONTROL = 6
    const val CH_STATE = "state"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Überwachung", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Dauerhafte Anzeige, solange die Ofen-Überwachung läuft"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATE, "Zustand des Ofens", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Meldung bei jeder Zustandsänderung (z. B. Zündung, Normalbetrieb, gestoppt)"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, "Warnungen", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Holz nachlegen, Pellets gestoppt/gestartet"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600)
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun serviceNotification(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_flame)
            .setContentTitle("Ofen wird überwacht")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Beenden", PendingIntent.getService(ctx, 1,
                Intent(ctx, OfenService::class.java).setAction(OfenService.ACTION_QUIT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun allowed(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun updateService(ctx: Context, text: String) {
        if (!allowed(ctx)) return
        try { NotificationManagerCompat.from(ctx).notify(ID_SERVICE, serviceNotification(ctx, text)) } catch (e: SecurityException) { }
    }

    fun alert(ctx: Context, id: Int, title: String, text: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_WARN)
            .setSmallIcon(R.drawable.ic_stat_flame)
            .setContentTitle("Ofen: $title")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: SecurityException) { }
    }

    fun stateChange(ctx: Context, title: String, text: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_STATE)
            .setSmallIcon(R.drawable.ic_stat_flame)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(ID_STATE, n) } catch (e: SecurityException) { }
    }

    fun clear(ctx: Context, id: Int) {
        NotificationManagerCompat.from(ctx).cancel(id)
    }

    fun enabled(ctx: Context) = allowed(ctx) && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
}
