package com.bearnest.vpn.vpn.tile

import android.app.Dialog
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.util.Log
import android.view.ContextThemeWrapper
import com.bearnest.vpn.R
import com.bearnest.vpn.vpn.BearVpnService
import com.bearnest.vpn.vpn.VpnController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Плитка «Сервер»: тап — диалог со списком серверов прямо поверх шторки.
 * Выбор → сервер сохраняется и VPN сразу переключается на него
 * (если VPN был выключен — подключается).
 */
class BearServerTileService : BaseBearTileService() {

    companion object {
        /** Кэш имени выбранного сервера, чтобы плитка не мигала пустой подписью. */
        @Volatile private var cachedName: String = ""
    }

    private var dialog: Dialog? = null

    override fun onStartListening() {
        super.onStartListening()
        // Имя выбранного сервера лежит в БД — читаем асинхронно и перерисовываем.
        listenScope?.launch {
            val name = withContext(Dispatchers.IO) {
                VpnController.selectedServer(applicationContext)?.toString().orEmpty()
            }
            cachedName = name
            render()
        }
    }

    override fun onStopListening() {
        dialog?.dismiss()
        dialog = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        runUnlocked { showPicker() }
    }

    private fun showPicker() {
        listenScope?.launch {
            val (servers, selected) = withContext(Dispatchers.IO) {
                VpnController.loadServers(applicationContext) to
                    VpnController.selectedIdx(applicationContext)
            }
            if (servers.isEmpty()) {
                // Подписка ещё не загружена — открываем приложение.
                openApp()
                return@launch
            }

            // Тема + язык приложения для диалога.
            val themed = ContextThemeWrapper(this@BearServerTileService, VpnController.themeResId(this@BearServerTileService))
                .apply { applyOverrideConfiguration(VpnController.localeOverride(this@BearServerTileService)) }

            val items = servers.map { s ->
                if (s.pingMs >= 0) "${s}  ·  ${s.pingMs} ms" else s.toString()
            }.toTypedArray()

            val d = MaterialAlertDialogBuilder(themed)
                .setTitle(R.string.tile_pick_server)
                .setSingleChoiceItems(items, selected.coerceIn(0, servers.lastIndex)) { dlg, which ->
                    dlg.dismiss()
                    onServerPicked(which, servers[which].toString())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            dialog = d
            try {
                showDialog(d)
            } catch (e: Exception) {
                Log.w("BearServerTile", "showDialog: ${e.message}")
                openApp()
            }
        }
    }

    private fun onServerPicked(idx: Int, name: String) {
        cachedName = name
        render()
        VpnController.scope.launch {
            val ok = VpnController.selectAndSwitch(applicationContext, idx, connectIfStopped = true)
            withContext(Dispatchers.Main) {
                if (!ok) openApp(requestVpnPermission = true)
                render()
            }
        }
    }

    override fun render() {
        val tile = qsTile ?: return
        val ctx = VpnController.localized(this)
        val on = BearVpnService.isRunning
        val name = (if (on) BearVpnService.currentServerName else "").ifBlank { cachedName }

        tile.icon  = Icon.createWithResource(this, R.drawable.ic_tile_servers)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.label    = ctx.getString(R.string.tile_server_label)
            tile.subtitle = name.ifBlank { ctx.getString(R.string.server_not_selected) }
        } else {
            // До Android 10 подписи нет — показываем имя сервера прямо в label.
            tile.label = name.ifBlank { ctx.getString(R.string.tile_server_label) }
        }
        tile.contentDescription = "${ctx.getString(R.string.tile_server_label)}: $name"
        tile.updateTile()
    }
}
