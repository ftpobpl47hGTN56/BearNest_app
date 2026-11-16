package com.bearnest.vpn.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import com.bearnest.vpn.data.AppDatabase
import com.bearnest.vpn.data.AppSettings
import com.bearnest.vpn.model.ServerConfig
import com.bearnest.vpn.ui.MainActivity
import com.bearnest.vpn.vpn.tile.BearServerTileService
import com.bearnest.vpn.vpn.tile.BearToggleTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Единая точка управления VPN «снаружи» UI:
 * плитки в шторке, кнопки уведомления, выбор сервера в приложении.
 *
 * Сюда вынесена логика, которая раньше жила только в MainViewModel,
 * чтобы её можно было вызывать без Activity (из TileService / уведомления).
 */
object VpnController {

    private const val TAG = "VpnController"

    /**
     * Процессный scope. Живёт дольше, чем TileService (система отвязывает плитку
     * сразу после закрытия шторки), поэтому долгие операции запускаем тут.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Данные ────────────────────────────────────────────────────────────────

    suspend fun loadServers(ctx: Context): List<ServerConfig> = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(ctx).serverDao().getAll().map { it.toServerConfig() }
    }

    suspend fun selectedIdx(ctx: Context): Int =
        AppSettings(ctx.applicationContext).selectedIdx.first()

    /** Текущий выбранный сервер (или первый, если индекс устарел). */
    suspend fun selectedServer(ctx: Context): ServerConfig? {
        val servers = loadServers(ctx)
        return servers.getOrNull(selectedIdx(ctx)) ?: servers.firstOrNull()
    }

    /** true — разрешение VPN ещё не выдано, нужен системный диалог из Activity. */
    fun needsPermission(ctx: Context): Boolean = VpnService.prepare(ctx) != null

    // ── Управление ────────────────────────────────────────────────────────────

    /** Старт сервиса с указанным сервером (разрешение должно быть уже выдано). */
    fun start(ctx: Context, server: ServerConfig) {
        val intent = Intent(ctx, BearVpnService::class.java).apply {
            action = BearVpnService.ACTION_START
            putExtra(BearVpnService.EXTRA_SERVER_JSON, server.toJson())
        }
        ContextCompat.startForegroundService(ctx.applicationContext, intent)
    }

    /**
     * Подключиться к выбранному серверу.
     * @return false — нужна Activity (нет разрешения VPN или нет серверов).
     */
    suspend fun connectSelected(ctx: Context): Boolean {
        if (needsPermission(ctx)) return false
        val server = selectedServer(ctx) ?: return false
        return try {
            start(ctx, server)
            true
        } catch (e: Exception) {
            // На части прошивок старт FGS из фона может быть запрещён —
            // тогда откроем приложение, пусть подключится оттуда.
            Log.w(TAG, "start from background failed: ${e.message}")
            false
        }
    }

    /** Отключение — тем же надёжным путём, что и в MainViewModel.disconnect(). */
    fun stop(ctx: Context) {
        BearVpnService.stopNow()
        try {
            ctx.applicationContext.stopService(Intent(ctx, BearVpnService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "stopService: ${e.message}")
        }
    }

    /**
     * Выбрать сервер по индексу и, если VPN работает, сразу переключиться на него.
     *
     * @param connectIfStopped если VPN выключен — подключиться к выбранному серверу.
     * @return false — для подключения нужна Activity (нет разрешения / ошибка старта).
     */
    suspend fun selectAndSwitch(ctx: Context, idx: Int, connectIfStopped: Boolean): Boolean {
        val servers = loadServers(ctx)
        val server = servers.getOrNull(idx) ?: return false
        AppSettings(ctx.applicationContext).setSelectedIdx(idx)

        return when {
            BearVpnService.isActive() -> {
                // Горячая смена внутри живого сервиса (без stop/start сервиса).
                if (!BearVpnService.switchTo(server)) start(ctx, server)
                true
            }
            connectIfStopped -> {
                if (needsPermission(ctx)) false
                else try { start(ctx, server); true } catch (e: Exception) {
                    Log.w(TAG, "start failed: ${e.message}"); false
                }
            }
            else -> true
        }
    }

    /** Переключиться на следующий сервер по кругу (кнопка в уведомлении). */
    suspend fun switchToNext(ctx: Context) {
        val servers = loadServers(ctx)
        if (servers.isEmpty()) return
        val next = (selectedIdx(ctx) + 1).mod(servers.size)
        selectAndSwitch(ctx, next, connectIfStopped = false)
    }

    // ── Плитки ────────────────────────────────────────────────────────────────

    /** Просим систему обновить обе плитки (если пользователь их добавил). */
    fun refreshTiles(ctx: Context) {
        val app = ctx.applicationContext
        for (cls in listOf(BearToggleTileService::class.java, BearServerTileService::class.java)) {
            try {
                TileService.requestListeningState(app, ComponentName(app, cls))
            } catch (e: Exception) {
                Log.w(TAG, "requestListeningState ${cls.simpleName}: ${e.message}")
            }
        }
    }

    // ── Локаль и тема приложения (для плиток и уведомления) ────────────────────

    /**
     * Контекст с языком, выбранным в приложении (как в MainActivity.attachBaseContext),
     * чтобы строки в шторке совпадали с языком приложения, а не системы.
     */
    fun localized(ctx: Context): Context =
        ctx.createConfigurationContext(
            Configuration(ctx.resources.configuration).apply { setLocale(appLocale(ctx)) }
        )

    /** Конфигурация-оверрайд только с языком — для ContextThemeWrapper диалога. */
    fun localeOverride(ctx: Context): Configuration =
        Configuration().apply { setLocale(appLocale(ctx)) }

    private fun appLocale(ctx: Context): Locale {
        val prefs = ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return Locale(prefs.getString(MainActivity.KEY_LANG, "ru") ?: "ru")
    }

    /** Текущая тема приложения — для диалога выбора сервера. */
    fun themeResId(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return when (prefs.getString(MainActivity.KEY_THEME, "catppuccin")) {
            "midnight" -> com.bearnest.vpn.R.style.Theme_BearNest_Midnight
            "forest"   -> com.bearnest.vpn.R.style.Theme_BearNest_Forest
            "obsidian" -> com.bearnest.vpn.R.style.Theme_BearNest_Obsidian
            "snow"     -> com.bearnest.vpn.R.style.Theme_BearNest_Snow
            else       -> com.bearnest.vpn.R.style.Theme_BearNest_Catppuccin
        }
    }
}
