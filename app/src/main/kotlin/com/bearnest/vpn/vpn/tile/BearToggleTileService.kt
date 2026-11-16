package com.bearnest.vpn.vpn.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import com.bearnest.vpn.R
import com.bearnest.vpn.vpn.BearVpnService
import com.bearnest.vpn.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Плитка «BearNest» в шторке: тап — включить / выключить VPN.
 * Подпись (Android 10+) — имя текущего сервера.
 */
class BearToggleTileService : BaseBearTileService() {

    /** Между тапом «включить» и подтверждением от сервиса показываем «Подключение…». */
    @Volatile private var connecting = false

    override fun onClick() {
        super.onClick()
        runUnlocked { toggle() }
    }

    private fun toggle() {
        if (BearVpnService.isActive()) {
            connecting = false
            VpnController.stop(applicationContext)
            render()
            return
        }

        connecting = true
        render()
        VpnController.scope.launch {
            val ok = VpnController.connectSelected(applicationContext)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    connecting = false
                    render()
                    // Нет разрешения VPN / нет серверов — открываем приложение.
                    openApp(requestVpnPermission = true)
                }
            }
        }
    }

    override fun render() {
        val tile = qsTile ?: return
        val ctx = VpnController.localized(this)
        val on = BearVpnService.isRunning
        if (on) connecting = false

        tile.icon  = Icon.createWithResource(this, R.drawable.ic_stat_bearnest)
        tile.label = ctx.getString(R.string.tile_vpn_label)
        tile.state = when {
            on         -> Tile.STATE_ACTIVE
            connecting -> Tile.STATE_ACTIVE
            else       -> Tile.STATE_INACTIVE
        }
        val sub = when {
            on         -> BearVpnService.currentServerName.ifBlank { ctx.getString(R.string.tile_state_on) }
            connecting -> ctx.getString(R.string.connecting)
            else       -> ctx.getString(R.string.tile_state_off)
        }
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        tile.contentDescription = "${tile.label}: $sub"
        tile.updateTile()
    }
}
