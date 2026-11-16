package com.bearnest.vpn.model

import java.text.DecimalFormat
import java.util.Date

/**
 * Информация о подписке из заголовков HTTP-ответа.
 * subscription-userinfo: upload=...; download=...; total=...; expire=...
 * profile-title: ...
 */
data class SubscriptionInfo(
    val title: String = "",
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expireUnix: Long = 0L
) {
    val used: Long get() = upload + download

    val usedDisplay: String get() = formatBytes(used)
    val totalDisplay: String get() = formatBytes(total)

    val expireDisplay: String get() {
        if (expireUnix == 0L) return "—"
        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
        val dateStr = sdf.format(Date(expireUnix * 1000))
        val daysLeft = ((expireUnix - System.currentTimeMillis() / 1000) / 86400).toInt()
        return when {
            daysLeft < 0  -> "$dateStr (истекла)"
            daysLeft == 0 -> "$dateStr (сегодня)"
            else          -> "$dateStr ($daysLeft дн.)"
        }
    }

    val usagePercent: Float get() =
        if (total == 0L) 0f else (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    val isExpired: Boolean get() =
        expireUnix > 0 && System.currentTimeMillis() / 1000 > expireUnix

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val fmt = DecimalFormat("#,##0.#")
        return "${fmt.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }
}
