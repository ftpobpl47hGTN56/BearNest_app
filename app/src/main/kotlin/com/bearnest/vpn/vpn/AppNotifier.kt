package com.bearnest.vpn.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bearnest.vpn.R
import com.bearnest.vpn.ui.MainActivity

/**
 * [Alerts] Всплывающие оповещения о состоянии соединения — отдельный канал
 * с IMPORTANCE_DEFAULT (heads-up), не путать с foreground-уведомлением сервиса:
 *  • сервер перестал пропускать трафик (watchdog в MainViewModel);
 *  • автоматическая смена сервера (плитка / кнопка «Следующий» / выбор в приложении);
 *  • неудачное подключение или отзыв туннеля системой.
 */
object AppNotifier {

    private const val TAG = "AppNotifier"

    const val CHANNEL_ALERTS = "bearnest_alerts"

    private const val ID_SERVER_DOWN = 2001
    private const val ID_SWITCHED    = 2002
    private const val ID_FAILED      = 2003

    /** Сервер «отвалился»: туннель есть, но трафик не идёт. */
    fun notifyServerDown(ctx: Context, serverName: String) {
        val loc  = VpnController.localized(ctx)
        val name = serverName.ifEmpty { loc.getString(R.string.notif_server_unknown) }
        post(ctx, ID_SERVER_DOWN,
            loc.getString(R.string.notif_server_down_title),
            loc.getString(R.string.notif_server_down_text, name))
    }

    /** Автосмена сервера на живом соединении. */
    fun notifyServerSwitched(ctx: Context, serverName: String) {
        val loc = VpnController.localized(ctx)
        post(ctx, ID_SWITCHED,
            loc.getString(R.string.notif_switched_title),
            loc.getString(R.string.notif_switched_text, serverName))
    }

    /** Не удалось поднять туннель / ядро. */
    fun notifyConnectFailed(ctx: Context, serverName: String) {
        val loc = VpnController.localized(ctx)
        post(ctx, ID_FAILED,
            loc.getString(R.string.notif_conn_failed_title),
            loc.getString(R.string.notif_conn_failed_text, serverName))
    }

    /** Система отозвала VPN (другое VPN-приложение, отзыв разрешения и т.п.). */
    fun notifyConnectionLost(ctx: Context) {
        val loc = VpnController.localized(ctx)
        post(ctx, ID_FAILED,
            loc.getString(R.string.notif_conn_lost_title),
            loc.getString(R.string.notif_conn_lost_text))
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun post(ctx: Context, id: Int, title: String, text: String) {
        // Без разрешения POST_NOTIFICATIONS (Android 13+) молча пропускаем —
        // пользователь мог запретить уведомления.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return

        ensureChannel(ctx)
        val tapPi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_bearnest)
            .setColor(ContextCompat.getColor(ctx, R.color.bear_blue))
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify: ${e.message}")
        }
    }

    private fun ensureChannel(ctx: Context) {
        val loc = VpnController.localized(ctx)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    loc.getString(R.string.notif_channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
    }
}
