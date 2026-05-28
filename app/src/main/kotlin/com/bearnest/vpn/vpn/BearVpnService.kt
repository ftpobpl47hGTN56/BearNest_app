package com.bearnest.vpn.vpn

import android.system.Os
import java.io.FileDescriptor
import android.system.OsConstants
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bearnest.vpn.R
import com.bearnest.vpn.core.ConfigGenerator
import com.bearnest.vpn.core.XrayManager
import com.bearnest.vpn.data.AppSettings          // ← ДОБАВИТЬ импорт
import com.bearnest.vpn.model.ServerConfig
import com.bearnest.vpn.ui.MainActivity
import com.v2ray.ang.service.TProxyService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

class BearVpnService : TProxyService() {

    companion object {
        private const val TAG       = "BearVpnService"

        const val NOTIFICATION_ID   = 1001
        const val CHANNEL_ID        = "bearnest_vpn"
        const val ACTION_START      = "com.bearnest.vpn.START"
        const val ACTION_STOP       = "com.bearnest.vpn.STOP"
        const val EXTRA_SERVER_JSON = "server_json"

        val logFlow = MutableSharedFlow<String>(
            extraBufferCapacity = 200,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val connectedFlow  = MutableStateFlow(false)
        val sessionStartMs = MutableStateFlow(0L)
        @Volatile var isRunning         = false
        @Volatile var currentServerName = ""
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var xrayManager: XrayManager?     = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val json = intent.getStringExtra(EXTRA_SERVER_JSON) ?: run {
                    Log.e(TAG, "No server JSON")
                    stopSelf()
                    return START_NOT_STICKY
                }
                scope.launch { startVpn(parseServer(json)) }
            }
            ACTION_STOP -> scope.launch { stopVpn() }
        }
        return START_STICKY
    }

    private suspend fun startVpn(server: ServerConfig) {
        val pfd = buildTunInterface() ?: run {
            Log.e(TAG, "Failed to establish TUN"); stopSelf(); return
        }
        tunPfd = pfd
        val fd = pfd.fd
        Log.d(TAG, "TUN fd=$fd")

        // ── ДОБАВИТЬ: читаем bypass-список из AppSettings ─────────────
        // Оба метода suspend и используют DataStore.first() —
        // безопасно вызывать прямо здесь, мы уже внутри корутины Dispatchers.IO
        val appSettings      = AppSettings(applicationContext)
        val bypassDomains    = appSettings.getBypassDomainsOnce()
        val splitTunnelEnabled = appSettings.isSplitTunnelEnabledOnce()
        // ─────────────────────────────────────────────────────────────

        // 1. xray — SOCKS5
        val mgr = XrayManager(
            applicationContext,
            onLog = { _, msg ->
                scope.launch { logFlow.emit(msg) }
            }
        )
        xrayManager = mgr

        // ── ИЗМЕНИТЬ: передаём bypass-параметры в generateProxy ───────
        val config = ConfigGenerator.generateProxy(
            server             = server,
            bypassDomains      = bypassDomains,
            splitTunnelEnabled = splitTunnelEnabled
        )
        // ─────────────────────────────────────────────────────────────

        val err = mgr.start(config)
        if (err != null) {
            Log.e(TAG, "xray failed: $err")
            pfd.close()
            stopSelf()
            return
        }

        // 2. hevtun — конфиг в файл, путь к файлу + fd
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")
            appendLine("  ipv4: 10.10.0.1")
            appendLine("socks5:")
            appendLine("  port: 10808")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: info")
        }

        val configFile = java.io.File(filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(yaml)
        }
        Log.d(TAG, "hevtun config: ${configFile.absolutePath}\n$yaml")

        Thread({
            Log.i(TAG, "hevtun start fd=$fd")
            TProxyStartService(configFile.absolutePath, fd)
            Log.i(TAG, "hevtun exit")
        }, "hevtun").start()

        isRunning = true
        connectedFlow.value = true
        sessionStartMs.value = System.currentTimeMillis()
        currentServerName = server.name
        withContext(Dispatchers.Main) {
            startForeground(NOTIFICATION_ID, buildNotification(server.name))
        }
        Log.i(TAG, "VPN started: ${server.name}")
    }

    private suspend fun stopVpn() {
        xrayManager?.stop()
        xrayManager = null
        // ── ДОБАВИТЬ: останавливаем нативный hevtun ──────────────
        // Без этого нативный поток висит, и при следующем старте
        // новый hevtun падает мгновенно — трафик перестаёт идти
        try {
            TProxyStopService()
        } catch (e: Exception) {
            Log.w(TAG, "TProxyStopService: ${e.message}")
        }

        tunPfd?.close()
        tunPfd = null
        isRunning = false
        connectedFlow.value = false
        sessionStartMs.value = 0L
        currentServerName = ""
        withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.launch { stopVpn() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        scope.launch { stopVpn() }
        super.onRevoke()
    }

    private fun buildTunInterface(): ParcelFileDescriptor? = try {
        Builder()
            .setSession("BearNest")
            .setMtu(8500)
            .addAddress("10.10.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd6e:a81f:3ae0::1", 128)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addDisallowedApplication(packageName)
            .establish()
    } catch (e: Exception) {
        Log.e(TAG, "TUN establish failed: ${e.message}")
        null
    }

    private fun buildNotification(serverName: String): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BearNest VPN",
                    NotificationManager.IMPORTANCE_LOW)
            )
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, BearVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mainPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐻 BearNest VPN")
            .setContentText("Подключено: $serverName")
            .setSmallIcon(R.drawable.ic_bear_notification)
            .setContentIntent(mainPi)
            .addAction(0, "Отключить", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun parseServer(json: String): ServerConfig {
        val o = JSONObject(json)
        return ServerConfig(
            name        = o.optString("name"),
            protocol    = o.optString("protocol"),
            address     = o.optString("address"),
            port        = o.optInt("port"),
            id          = o.optString("id"),
            security    = o.optString("security"),
            network     = o.optString("network", "tcp"),
            publicKey   = o.optString("publicKey"),
            shortId     = o.optString("shortId"),
            serverName  = o.optString("serverName"),
            path        = o.optString("path", "/"),
            flow        = o.optString("flow"),
            fingerprint = o.optString("fingerprint", "chrome")
        )
    }
}

fun ServerConfig.toJson(): String = JSONObject().apply {
    put("name", name); put("protocol", protocol); put("address", address)
    put("port", port); put("id", id); put("security", security)
    put("network", network); put("publicKey", publicKey); put("shortId", shortId)
    put("serverName", serverName); put("path", path); put("flow", flow)
    put("fingerprint", fingerprint)
}.toString()