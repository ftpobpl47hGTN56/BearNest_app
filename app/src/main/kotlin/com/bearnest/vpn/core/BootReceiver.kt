package com.bearnest.vpn.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bearnest.vpn.data.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val settings = AppSettings(context)
        val autostart = runBlocking { settings.autostart.first() }

        if (autostart) {
            Log.i("BootReceiver", "Autostart on boot — TODO: запустить сервис")
            // Для proxy-режима можно запустить ForegroundService напрямую.
            // Для TUN-режима нельзя — VpnService.prepare() требует Activity.
        }
    }
}
