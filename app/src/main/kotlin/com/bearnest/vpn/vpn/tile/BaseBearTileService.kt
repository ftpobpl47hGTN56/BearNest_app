package com.bearnest.vpn.vpn.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log
import com.bearnest.vpn.ui.MainActivity
import com.bearnest.vpn.vpn.BearVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Общая база для плиток BearNest:
 *  - scope, живущий пока плитка «слушает» (шторка открыта);
 *  - подписка на connectedFlow сервиса → автообновление плитки;
 *  - открытие приложения с закрытием шторки (разные API на Android 14+).
 */
abstract class BaseBearTileService : TileService() {

    protected var listenScope: CoroutineScope? = null

    /** Перерисовать плитку. Вызывается на главном потоке. */
    protected abstract fun render()

    override fun onStartListening() {
        super.onStartListening()
        listenScope?.cancel()
        listenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { BearVpnService.connectedFlow.collect { render() } }
        }
        render()
    }

    override fun onStopListening() {
        listenScope?.cancel()
        listenScope = null
        super.onStopListening()
    }

    /** Выполнить действие; на заблокированном экране — сначала попросить разблокировку. */
    protected fun runUnlocked(action: () -> Unit) {
        if (isLocked) unlockAndRun { action() } else action()
    }

    /**
     * Открыть приложение и свернуть шторку.
     * @param requestVpnPermission попросить MainActivity сразу показать
     *        системный диалог разрешения VPN и подключиться.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun openApp(requestVpnPermission: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (requestVpnPermission) putExtra(MainActivity.EXTRA_REQUEST_VPN, true)
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w("BearTile", "openApp: ${e.message}")
        }
    }
}
