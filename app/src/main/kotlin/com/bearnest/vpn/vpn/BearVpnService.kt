package com.bearnest.vpn.vpn

import android.system.Os
import java.io.FileDescriptor
import android.system.OsConstants
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bearnest.vpn.R
import com.bearnest.vpn.core.ConfigGenerator
import com.bearnest.vpn.core.XrayManager
import com.bearnest.vpn.data.AppSettings
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

        // ── ФИКС: прямая ссылка на живой сервис ───────────────────────────────
        // Нужна, чтобы UI мог остановить ядро НАПРЯМУЮ, в том же процессе, не
        // полагаясь на доставку stopService()/ACTION_STOP (её на Tecno/HiOS
        // система иногда молча дропает).
        @Volatile private var instance: BearVpnService? = null

        /** Синхронно и надёжно останавливает VPN из любого места процесса. */
        fun stopNow() {
            val s = instance ?: return
            try { s.tearDownCore() } catch (e: Exception) { Log.w(TAG, "stopNow tearDown: ${e.message}") }
            try { s.stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (e: Exception) { Log.w(TAG, "stopNow fg: ${e.message}") }
            try { s.stopSelf() } catch (e: Exception) { Log.w(TAG, "stopNow stopSelf: ${e.message}") }
        }
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var xrayManager: XrayManager?     = null
    private var hevThread: Thread?            = null   // ← захватываем нативный поток, чтобы контролировать его выход
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ── ФИКС Дефекта 2: перезапуск с null-интентом (после убийства процесса
        //    системой при START_STICKY) не несёт ни конфига, ни action.
        //    Поднимать сломанный полусервис нельзя — гасимся сразу.
        if (intent == null) {
            Log.w(TAG, "onStartCommand: null-intent restart — конфига нет, останавливаемся")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                // ── ФИКС Дефекта 4: переводим сервис в foreground СРАЗУ, до тяжёлой
                //    работы (xray/hevtun), иначе на Android 12+ возможны краши FGS.
                ensureChannel()
                startForeground(NOTIFICATION_ID, buildNotification("Подключение…"))

                val json = intent.getStringExtra(EXTRA_SERVER_JSON) ?: run {
                    Log.e(TAG, "No server JSON")
                    scope.launch { stopVpn() }
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
            Log.e(TAG, "Failed to establish TUN")
            stopVpn()
            return
        }
        tunPfd = pfd
        val fd = pfd.fd
        Log.d(TAG, "TUN fd=$fd")

        // Читаем bypass-список из AppSettings.
        // Оба метода suspend и используют DataStore.first() —
        // безопасно вызывать прямо здесь, мы уже внутри корутины Dispatchers.IO.
        val appSettings        = AppSettings(applicationContext)
        val bypassDomains      = appSettings.getBypassDomainsOnce()
        val splitTunnelEnabled = appSettings.isSplitTunnelEnabledOnce()

        // 1. xray — SOCKS5 (in-process, libv2ray)
        val mgr = XrayManager(
            applicationContext,
            onLog = { _, msg ->
                scope.launch { logFlow.emit(msg) }
            }
        )
        xrayManager = mgr

        val config = ConfigGenerator.generateProxy(
            server             = server,
            bypassDomains      = bypassDomains,
            splitTunnelEnabled = splitTunnelEnabled
        )

        val err = mgr.start(config)
        if (err != null) {
            Log.e(TAG, "xray failed: $err")
            stopVpn()
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

        val configFile = File(filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(yaml)
        }
        Log.d(TAG, "hevtun config: ${configFile.absolutePath}\n$yaml")

        // ── ФИКС Дефекта 3: захватываем поток hevtun в поле, чтобы позже
        //    дождаться его выхода и зафиксировать утечку, если он не умер.
        hevThread = Thread({
            Log.i(TAG, "hevtun start fd=$fd")
            TProxyStartService(configFile.absolutePath, fd)
            Log.i(TAG, "hevtun exit")   // ← маркер корректного выхода нативного цикла
        }, "hevtun").also { it.start() }

        isRunning = true
        connectedFlow.value = true
        sessionStartMs.value = System.currentTimeMillis()
        currentServerName = server.name

        // Обновляем текст нотификации на «Подключено» (foreground уже активен).
        withContext(Dispatchers.Main) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification("Подключено: ${server.name}"))
        }
        Log.i(TAG, "VPN started: ${server.name}")
    }

    /**
     * ФИКС Дефекта 1 (главный): синхронный, идемпотентный teardown ядра.
     * internal — чтобы companion.stopNow() мог вызвать его напрямую.
     */
    internal fun tearDownCore() {
        try { xrayManager?.stop() } catch (e: Exception) { Log.w(TAG, "xray stop: ${e.message}") }
        xrayManager = null

        // Останавливаем нативный hevtun.
        try { TProxyStopService() } catch (e: Exception) { Log.w(TAG, "TProxyStopService: ${e.message}") }

        // Закрываем tun fd — дополнительно будит нативный poll-цикл,
        // если TProxyStopService сам его не разбудил (частый случай при отвале сети).
        try { tunPfd?.close() } catch (e: Exception) { Log.w(TAG, "tun close: ${e.message}") }
        tunPfd = null

        // Ждём выхода нативного потока и ЛОГИРУЕМ утечку, если он завис.
        hevThread?.let { t ->
            try { t.join(1500) } catch (_: InterruptedException) {}
            if (t.isAlive) {
                Log.e(TAG, "hevtun thread STILL ALIVE after stop — нативный цикл утёк (Дефект 3)")
            } else {
                Log.i(TAG, "hevtun thread joined cleanly")
            }
        }
        hevThread = null

        isRunning = false
        connectedFlow.value = false
        sessionStartMs.value = 0L
        currentServerName = ""
    }

    private suspend fun stopVpn() {
        tearDownCore()
        withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        tearDownCore()      // синхронно и ДО отмены scope — гарантированно исполняется
        // ── ФИКС: при остановке через stopService() teardown приходит сюда,
        //    минуя stopVpn(). Снимаем FGS-уведомление явно, иначе оно зависнет.
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { Log.w(TAG, "stopForeground: ${e.message}") }
        scope.cancel()      // отменяем scope только после того, как ядро остановлено
        super.onDestroy()
    }

    override fun onRevoke() {
        tearDownCore()      // отзыв туннеля другим VPN / пользователем — тоже чистый останов
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "BearNest VPN",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, BearVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mainPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐻 BearNest VPN")
            .setContentText(text)
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
