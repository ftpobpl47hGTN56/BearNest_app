package com.bearnest.vpn.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bearnest.vpn.R
import com.bearnest.vpn.core.ConfigGenerator
import com.bearnest.vpn.core.ServerPinger
import com.bearnest.vpn.core.SubscriptionParser
import com.bearnest.vpn.core.XrayManager
import com.bearnest.vpn.data.AppDatabase
import com.bearnest.vpn.data.AppSettings
import com.bearnest.vpn.data.ServerEntity
import com.bearnest.vpn.model.ServerConfig
import com.bearnest.vpn.model.SubscriptionInfo
import com.bearnest.vpn.vpn.BearVpnService
import com.bearnest.vpn.vpn.toJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context  = app.applicationContext
    private val db       = AppDatabase.getInstance(context)
    private val dao      = db.serverDao()
    private val settings = AppSettings(context)
    private val xray     = XrayManager(context, onLog = { _, msg -> addLog(msg) })

    companion object { private const val TAG = "MainViewModel" }

    // ── State ──────────────────────────────────────────────────────────────────

    val theme: StateFlow<String> = settings.theme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "catppuccin")

    fun setTheme(t: String) = viewModelScope.launch { settings.setTheme(t) }

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private val _selectedIdx = MutableStateFlow(0)
    val selectedIdx: StateFlow<Int> = _selectedIdx.asStateFlow()

    /** Комбинированный flow — текущий сервер. Удобен для наблюдения из фрагментов. */
    val currentServerFlow: StateFlow<ServerConfig?> =
        combine(_servers, _selectedIdx) { list, idx -> list.getOrNull(idx) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val language: StateFlow<String> = settings.language
        .stateIn(viewModelScope, SharingStarted.Eagerly, "ru")

    fun setLanguage(lang: String) = viewModelScope.launch { settings.setLanguage(lang) }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _vpnMode = MutableStateFlow("tun")
    val vpnMode: StateFlow<String> = _vpnMode.asStateFlow()

    private val _subUrl = MutableStateFlow("")
    val subUrl: StateFlow<String> = _subUrl.asStateFlow()

    private val _subInfo = MutableStateFlow(SubscriptionInfo())
    val subInfo: StateFlow<SubscriptionInfo> = _subInfo.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _pingProgress = MutableStateFlow(-1)
    val pingProgress: StateFlow<Int> = _pingProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _proxyInfo = MutableStateFlow("")
    val proxyInfo: StateFlow<String> = _proxyInfo.asStateFlow()

    // ── [Fixed] Реальная проверка трафика ─────────────────────────────────────
    // null  = проверка ещё не проводилась (только что подключились / отключены)
    // true  = трафик идёт нормально
    // false = туннель поднят, но реальный трафик НЕ проходит (сервер сломан)
    private val _trafficOk = MutableStateFlow<Boolean?>(null)
    val trafficOk: StateFlow<Boolean?> = _trafficOk.asStateFlow()

    // ── Логи ───────────────────────────────────────────────────────────────────

    data class LogEntry(val time: String, val level: Int, val msg: String)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun addLog(line: String) {
        val entry = LogEntry(
            time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date()),
            level = when {
                line.contains("[Error]",   ignoreCase = true) -> 3
                line.contains("[Warning]", ignoreCase = true) -> 2
                line.contains("[Info]",    ignoreCase = true) -> 1
                else -> 0
            },
            msg = line
        )
        _logs.value = (_logs.value + entry).takeLast(200)
    }

    fun clearLogs() { _logs.value = emptyList() }

    val sessionStartMs: StateFlow<Long> = BearVpnService.sessionStartMs.asStateFlow()
    val xrayVersion: String by lazy { xray.version() }

    // ── Init ───────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            dao.observeAll().collect { entities ->
                _servers.value = entities.map { it.toServerConfig() }
            }
        }
        viewModelScope.launch { settings.subUrl.collect      { _subUrl.value = it } }
        viewModelScope.launch { settings.selectedIdx.collect { _selectedIdx.value = it } }
        viewModelScope.launch { settings.vpnMode.collect     { _vpnMode.value = it } }
        viewModelScope.launch { BearVpnService.logFlow.collect { msg -> addLog(msg) } }
        // Источник истины для состояния — connectedFlow сервиса.
        // (Убрана привязка к _vpnMode: подключение всегда идёт через сервис/TUN.)
        viewModelScope.launch {
            BearVpnService.connectedFlow.collect { running ->
                _connected.value = running
            }
        }
        viewModelScope.launch {
            combine(
                settings.subTitle, settings.subUpload, settings.subDownload,
                settings.subTotal, settings.subExpire
            ) { title, upload, download, total, expire ->
                SubscriptionInfo(title = title, upload = upload, download = download,
                    total = total, expireUnix = expire)
            }.collect { info -> if (info.title.isNotEmpty()) _subInfo.value = info }
        }
    }

    // ── Подписка ───────────────────────────────────────────────────────────────

    fun loadSubscription(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            _error.value   = null
            try {
                val proxyPort = if (_connected.value && _vpnMode.value == "proxy")
                    settings.proxyPort.first() else 0
                val (content, info) = SubscriptionParser.download(url, proxyPort)
                val parsed = SubscriptionParser.parse(content)
                if (parsed.isEmpty()) {
                    _error.value = context.getString(R.string.error_no_servers_found)
                    return@launch
                }
                val entities = parsed.mapIndexed { i, cfg ->
                    ServerEntity.fromServerConfig(cfg, sortOrder = i)
                }
                dao.deleteAll()
                dao.insertAll(entities)
                settings.setSubUrl(url)
                settings.setSubInfo(info)
                settings.setSelectedIdx(0)
                _subInfo.value = info
            } catch (e: Exception) {
                _error.value = context.getString(R.string.error_loading_prefix, e.message ?: "")
            } finally {
                _loading.value = false
            }
        }
    }

    // ── Ping ───────────────────────────────────────────────────────────────────

    fun pingAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _servers.value
            if (current.isEmpty()) return@launch
            _pingProgress.value = 0
            val updated = ServerPinger.pingAll(current) { idx, _ ->
                _pingProgress.value = idx + 1
            }
            _pingProgress.value = -1
            val dbItems = dao.getAll()
            updated.forEachIndexed { i, cfg ->
                if (i < dbItems.size) dao.updatePing(dbItems[i].id, cfg.pingMs)
            }
        }
    }

    fun autoSelectBest() {
        viewModelScope.launch {
            val best = ServerPinger.findBest(_servers.value)
            if (best != null) {
                val idx = _servers.value.indexOf(best)
                if (idx >= 0) selectServer(idx)
            }
        }
    }

    // ── Выбор сервера ─────────────────────────────────────────────────────────

    fun selectServer(idx: Int) {
        viewModelScope.launch {
            _selectedIdx.value = idx
            settings.setSelectedIdx(idx)
        }
    }

    /** Быстрый синхронный доступ (для случаев, когда flow избыточен) */
    val currentServer: ServerConfig?
        get() = _servers.value.getOrNull(_selectedIdx.value)

    // ── Подключение / отключение ──────────────────────────────────────────────

    fun connect(vpnPermLauncher: ActivityResultLauncher<Intent>? = null) {
        val server = currentServer ?: run {
            _error.value = context.getString(R.string.error_no_server_selected)
            return
        }
        viewModelScope.launch {
            _error.value = null
            startTunMode(server, vpnPermLauncher)
        }
    }

    private suspend fun startTunMode(
        server: ServerConfig,
        launcher: ActivityResultLauncher<Intent>?
    ) {
        val permIntent = VpnService.prepare(context)
        if (permIntent != null) {
            if (launcher != null) launcher.launch(permIntent)
            else _error.value = context.getString(R.string.error_vpn_permission_required)
            return
        }
        val serviceIntent = Intent(context, BearVpnService::class.java).apply {
            action = BearVpnService.ACTION_START
            putExtra(BearVpnService.EXTRA_SERVER_JSON, server.toJson())
        }
        context.startForegroundService(serviceIntent)
        // Оптимистично показываем "подключено" — сервис почти сразу подтвердит это
        // через connectedFlow. Если старт упадёт, стоп-путь сервиса выставит false.
        _connected.value  = true
        _loading.value    = false
        _trafficOk.value  = null  // сбрасываем — идёт подключение

        // [Fixed] Проверка реального трафика через туннель.
        viewModelScope.launch(Dispatchers.IO) {
            delay(3_000)
            if (!_connected.value) return@launch
            val socksPort = settings.proxyPort.first()
            val ok = ServerPinger.checkRealTraffic(socksPort = socksPort)
            _trafficOk.value = ok
            if (!ok) {
                addLog("[Warning] Tunnel is up but no real traffic detected — server may be down")
            } else {
                addLog("[Info] Traffic check passed — tunnel is working")
            }
        }
    }

    fun disconnect() {
        // ── ФИКС ГЛАВНЫЙ ─────────────────────────────────────────────────────
        // connect() ВСЕГДА поднимает сервис через startTunMode(), какой бы режим
        // ни был сохранён. Значит и останавливать нужно ВСЕГДА сам сервис — без
        // разветвления по _vpnMode. Раньше при _vpnMode == "proxy" мы глушили
        // пустой UI-XrayManager (controller == null → "xray stopped" вхолостую),
        // а живое ядро в сервисе оставалось работать вечно. Это и был баг.
        //
        // 1) Прямой teardown в процессе — не зависит от доставки intent'ов
        //    (на Tecno/HiOS stopService()/ACTION_STOP иногда молча дропаются).
        BearVpnService.stopNow()
        // 2) Страховка — штатная остановка сервиса.
        try {
            context.stopService(Intent(context, BearVpnService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "stopService: ${e.message}")
        }
        // 3) На всякий случай глушим и локальный xray (no-op в TUN, актуально
        //    только если когда-то вернётся отдельный proxy-режим).
        try { xray.stop() } catch (_: Exception) {}
        _proxyInfo.value = ""
        _trafficOk.value = null
        // _connected НЕ трогаем — его выставит connectedFlow сервиса после
        // реальной остановки (единственный источник истины).
    }

    fun setVpnMode(mode: String) {
        viewModelScope.launch {
            if (_connected.value) disconnect()
            _vpnMode.value = mode
            settings.setVpnMode(mode)
        }
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        try { xray.stop() } catch (_: Exception) {}
    }
}
