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
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class BearVpnService : TProxyService() {

    companion object {
        private const val TAG       = "BearVpnService"

        const val NOTIFICATION_ID   = 1001
        const val CHANNEL_ID        = "bearnest_vpn"
        const val ACTION_START      = "com.bearnest.vpn.START"
        const val ACTION_STOP       = "com.bearnest.vpn.STOP"
        const val ACTION_NEXT       = "com.bearnest.vpn.NEXT"   // [QuickTiles] кнопка «Следующий» в уведомлении
        const val ACTION_DISCONNECT = "com.bearnest.vpn.DISCONNECT" // [NotifIdle] «Отключить», уведомление остаётся
        const val ACTION_CONNECT    = "com.bearnest.vpn.CONNECT"    // [NotifIdle] «Подключить» из уведомления
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

        /**
         * [QuickTiles] Горячая смена сервера в живом сервисе
         * (плитка в шторке / кнопка в уведомлении / выбор в приложении).
         * @return false — сервис не запущен, нужно стартовать его обычным путём.
         */
        fun switchTo(server: ServerConfig): Boolean {
            val s = instance ?: return false
            s.switchServer(server)
            return true
        }

        /** [QuickTiles] VPN работает ИЛИ прямо сейчас подключается / переключается. */
        fun isActive(): Boolean = isRunning || (instance?.busy == true)
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var xrayManager: XrayManager?     = null
    private var hevThread: Thread?            = null   // ← захватываем нативный поток, чтобы контролировать его выход
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // [QuickTiles] Сериализует старт / смену сервера: два быстрых тапа не поднимут два ядра.
    private val coreLock = Mutex()

    // [QuickTiles] true, пока идёт подключение или переключение
    // (isRunning в этот момент бывает false — старое ядро снято, новое ещё не поднято).
    @Volatile internal var busy = false

    // [NotifIdle] true — VPN отключён кнопкой «Отключить», но сервис и уведомление
    // оставлены живыми (режим «Отключено» с кнопками [Подключить] [Закрыть]).
    @Volatile private var idle = false

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
                startForeground(NOTIFICATION_ID, buildNotification(str(R.string.connecting)))

                val json = intent.getStringExtra(EXTRA_SERVER_JSON) ?: run {
                    Log.e(TAG, "No server JSON")
                    scope.launch { stopVpn() }
                    return START_NOT_STICKY
                }
                // [QuickTiles] Старт идёт через coreLock. Если ядро уже работает
                // (повторный START, например с плитки) — сначала аккуратно снимаем старое.
                switchServer(parseServer(json))
            }
            // Кнопка «Закрыть» — полная остановка: ядро + уведомление + сервис.
            ACTION_STOP -> scope.launch { stopVpn() }

            // [NotifIdle] Кнопка «Отключить» — VPN выключается, но сервис и
            // уведомление остаются: «Отключено» + [Подключить] [Закрыть].
            ACTION_DISCONNECT -> {
                if (instanceIsFreshWithoutCore()) {
                    // Кнопка со «старого» уведомления в новом процессе — поднимать нечего.
                    stopSelf()
                    return START_NOT_STICKY
                }
                goIdle()
            }

            // [NotifIdle] Кнопка «Подключить» в уведомлении «Отключено».
            ACTION_CONNECT -> {
                if (instanceIsFreshWithoutCore()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                connectFromNotification()
            }

            // [QuickTiles] «Следующий сервер» из уведомления.
            ACTION_NEXT -> {
                when {
                    isActive() -> VpnController.scope.launch { VpnController.switchToNext(applicationContext) }
                    idle       -> Unit   // в режиме «Отключено» кнопки нет — старый клик игнорируем
                    else -> {
                        // Нажали кнопку на «зависшем» уведомлении, а VPN уже не работает.
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * [NotifIdle] true — сервис только что создан системой ради клика по кнопке
     * уведомления, но он не foreground и ядра нет (процесс был убит, а уведомление
     * осталось). В таком случае ничего не поднимаем.
     */
    private fun instanceIsFreshWithoutCore(): Boolean =
        !idle && !isActive() && tunPfd == null && xrayManager == null

    /** [NotifIdle] Отключить VPN, оставив уведомление с кнопками [Подключить] [Закрыть]. */
    private fun goIdle() {
        idle = true
        scope.launch {
            coreLock.withLock { tearDownCore() }
            notifyText(str(R.string.notif_disconnected), connected = false)
        }
    }

    /** [NotifIdle] Подключиться к выбранному серверу прямо из уведомления. */
    private fun connectFromNotification() {
        if (VpnController.needsPermission(this)) {
            // Разрешение VPN отозвано — из уведомления системный диалог не показать.
            notifyText(str(R.string.error_vpn_permission_required), connected = false)
            return
        }
        busy = true
        notifyText(str(R.string.connecting))
        VpnController.scope.launch {
            val server = VpnController.selectedServer(applicationContext)
            if (server == null) {
                busy = false
                notifyText(str(R.string.server_not_selected), connected = false)
            } else {
                switchServer(server)
            }
        }
    }

    /**
     * [QuickTiles] Запуск / горячая смена сервера.
     * Сервис остаётся foreground, уведомление не пропадает — пересоздаются только ядро и TUN.
     */
    internal fun switchServer(server: ServerConfig) {
        busy = true
        idle = false   // [NotifIdle] выходим из режима «Отключено»
        scope.launch {
            coreLock.withLock {
                try {
                    val hadCore = isRunning || tunPfd != null || xrayManager != null
                    if (hadCore) {
                        Log.i(TAG, "switch: $currentServerName → ${server.name}")
                        notifyText(str(R.string.notif_switching, server.name))
                        tearDownCore()
                    }
                    startVpn(server)
                    // [Alerts] Всплывающее оповещение об автосмене сервера —
                    // только при переключении на живом соединении, не при первом старте.
                    if (hadCore && isRunning) {
                        AppNotifier.notifyServerSwitched(applicationContext, server.name)
                    }
                } finally {
                    busy = false
                    VpnController.refreshTiles(applicationContext)
                }
            }
        }
    }

    private suspend fun startVpn(server: ServerConfig) {
        val pfd = buildTunInterface() ?: run {
            Log.e(TAG, "Failed to establish TUN")
            logFlow.tryEmit("[Error] Failed to establish TUN interface")
            AppNotifier.notifyConnectFailed(applicationContext, server.name)
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
            logFlow.tryEmit("[Error] xray failed to start: $err")
            AppNotifier.notifyConnectFailed(applicationContext, server.name)
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
        currentServerName = server.name          // [QuickTiles] ДО connectedFlow — плитки читают имя в момент эмита
        sessionStartMs.value = System.currentTimeMillis()
        connectedFlow.value = true

        // Обновляем текст нотификации на «Подключено» (foreground уже активен).
        notifyText(str(R.string.notif_connected, server.name))
        VpnController.refreshTiles(applicationContext)
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

        // [QuickTiles] Плитки в шторке должны узнать, что VPN выключился.
        VpnController.refreshTiles(applicationContext)
    }

    private suspend fun stopVpn() {
        tearDownCore()
        withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        busy = false
        tearDownCore()      // синхронно и ДО отмены scope — гарантированно исполняется
        // ── ФИКС: при остановке через stopService() teardown приходит сюда,
        //    минуя stopVpn(). Снимаем FGS-уведомление явно, иначе оно зависнет.
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { Log.w(TAG, "stopForeground: ${e.message}") }
        scope.cancel()      // отменяем scope только после того, как ядро остановлено
        super.onDestroy()
    }

    override fun onRevoke() {
        // [Alerts] Туннель отозван системой или другим VPN-приложением — предупреждаем.
        AppNotifier.notifyConnectionLost(applicationContext)
        logFlow.tryEmit("[Error] VPN tunnel revoked by system or another VPN app")
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

    /** [QuickTiles] Обновить текст уведомления (foreground уже активен). */
    private fun notifyText(text: String, connected: Boolean = true) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text, connected))
        } catch (e: Exception) {
            Log.w(TAG, "notify: ${e.message}")
        }
    }

    /** [QuickTiles] Строки на языке приложения (как в MainActivity), а не системы. */
    private fun str(resId: Int, vararg args: Any): String =
        VpnController.localized(this).getString(resId, *args)

    /** PendingIntent на действие сервиса. У каждого действия свой requestCode, чтобы они не перезатирали друг друга. */
    private fun servicePi(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(this, requestCode,
            Intent(this, BearVpnService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * @param connected true  — VPN работает/подключается: [Следующий] [Отключить]
     *                  false — режим «Отключено»:          [Подключить] [Закрыть]
     */
    private fun buildNotification(text: String, connected: Boolean = true): Notification {
        val mainPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // [NotifButtons] Своё содержимое: кнопки видны сразу, без разворачивания.
        // Один и тот же вид для свёрнутого и развёрнутого состояния.
        // [NotifIdle] Левая (синяя) и правая (красная) кнопки меняют смысл по состоянию.
        val content = RemoteViews(packageName, R.layout.notification_vpn).apply {
            setTextViewText(R.id.notif_text, text)
            if (connected) {
                setTextViewText(R.id.notif_btn_next, str(R.string.notif_next_server))
                setOnClickPendingIntent(R.id.notif_btn_next, servicePi(ACTION_NEXT, 1))
                setTextViewText(R.id.notif_btn_stop, str(R.string.disconnect))
                setOnClickPendingIntent(R.id.notif_btn_stop, servicePi(ACTION_DISCONNECT, 2))
            } else {
                setTextViewText(R.id.notif_btn_next, str(R.string.connect))
                setOnClickPendingIntent(R.id.notif_btn_next, servicePi(ACTION_CONNECT, 3))
                setTextViewText(R.id.notif_btn_stop, str(R.string.notif_close))
                setOnClickPendingIntent(R.id.notif_btn_stop, servicePi(ACTION_STOP, 0))
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // Заголовок/текст остаются для TalkBack, часов и прошивок без custom view.
            .setContentTitle("🐻 BearNest VPN")
            .setContentText(text)
            // [QuickTiles] Новый монохромный медведь (глаза + нос вырезами).
            .setSmallIcon(R.drawable.ic_stat_bearnest)
            .setColor(ContextCompat.getColor(this, R.color.bear_blue))
            .setContentIntent(mainPi)
            // [NotifButtons] Шапку (иконка, имя приложения) рисует система,
            // под ней — наш layout с кнопками. addAction убраны, иначе
            // в развёрнутом виде кнопки задублировались бы.
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(content)
            .setCustomBigContentView(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
