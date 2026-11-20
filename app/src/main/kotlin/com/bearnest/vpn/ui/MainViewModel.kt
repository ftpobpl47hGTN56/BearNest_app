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
import com.bearnest.vpn.data.SubscriptionEntity
import com.bearnest.vpn.model.ServerConfig
import com.bearnest.vpn.model.SubscriptionInfo
import com.bearnest.vpn.vpn.BearVpnService
import com.bearnest.vpn.vpn.VpnController
import com.bearnest.vpn.vpn.toJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context  = app.applicationContext
    private val db       = AppDatabase.getInstance(context)
    private val dao      = db.serverDao()
    private val subDao   = db.subscriptionDao()
    private val settings = AppSettings(context)
    private val xray     = XrayManager(context, onLog = { _, msg -> addLog(msg) })

    companion object {
        private const val TAG = "MainViewModel"

        // Режимы сортировки серверов (порт sortField из Tauri main.js)
        const val SORT_DEFAULT = "default"
        const val SORT_NAME    = "name"
        const val SORT_PING    = "ping"

        private const val SUB_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }

    // ── State ──────────────────────────────────────────────────────────────────

    val theme: StateFlow<String> = settings.theme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "catppuccin")

    fun setTheme(t: String) = viewModelScope.launch { settings.setTheme(t) }

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    /** Подписки (порт subs[] из Tauri main.js). */
    private val _subscriptions = MutableStateFlow<List<SubscriptionEntity>>(emptyList())
    val subscriptions: StateFlow<List<SubscriptionEntity>> = _subscriptions.asStateFlow()

    private val _selectedIdx = MutableStateFlow(0)
    val selectedIdx: StateFlow<Int> = _selectedIdx.asStateFlow()

    /** Ключ выбранного сервера "subId::name" (порт last_server из Tauri). */
    private val _selectedKey = MutableStateFlow("")
    val selectedKey: StateFlow<String> = _selectedKey.asStateFlow()

    // ── Сортировка списка серверов ───────────────────────────────────────────

    private val _sortMode = MutableStateFlow(SORT_DEFAULT)
    val sortMode: StateFlow<String> = _sortMode.asStateFlow()

    /** 1 = по возрастанию, -1 = по убыванию. */
    private val _sortDir = MutableStateFlow(1)
    val sortDir: StateFlow<Int> = _sortDir.asStateFlow()

    fun setSortMode(mode: String) {
        _sortMode.value = mode
        viewModelScope.launch { settings.setSortMode(mode) }
    }

    fun setSortDir(dir: Int) {
        val fixed = if (dir >= 0) 1 else -1
        _sortDir.value = fixed
        viewModelScope.launch { settings.setSortDir(fixed) }
    }

    /**
     * Клик по кнопке сортировки: новый режим → dir=1;
     * повторный клик по тому же режиму → flip направления (как в Tauri).
     */
    fun cycleSort(mode: String) {
        if (_sortMode.value == mode) setSortDir(-_sortDir.value)
        else { setSortMode(mode); setSortDir(1) }
    }

    /**
     * Сортировка внутри группы подписки (порт sortedServers() из Tauri):
     * 1. Избранные всегда наверху своей группы.
     * 2. ping: необмеренные/мёртвые (pingMs < 0) всегда в конце,
     *    независимо от направления сортировки.
     */
    fun sortedServers(list: List<ServerConfig>): List<ServerConfig> {
        val dir = _sortDir.value
        val sorter: (List<ServerConfig>) -> List<ServerConfig> = { l ->
            when (_sortMode.value) {
                SORT_NAME -> l.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    .let { if (dir > 0) it else it.reversed() }
                SORT_PING -> {
                    val (alive, dead) = l.partition { it.pingMs >= 0 }
                    (if (dir > 0) alive.sortedBy { it.pingMs }
                     else alive.sortedByDescending { it.pingMs }) + dead
                }
                else -> l   // default — исходный порядок из подписки (sortOrder)
            }
        }
        val (fav, rest) = list.partition { it.isFavorite }
        return sorter(fav) + sorter(rest)
    }

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
        // Серверы + восстановление выбора/сортировки + миграция legacy-подписки.
        // Чтение настроек и миграция идут ПЕРЕД collect, чтобы reconcileSelection
        // сразу видел сохранённый ключ и привязанную подписку.
        viewModelScope.launch {
            _selectedKey.value = settings.selectedKey.first()
            _sortMode.value    = settings.sortMode.first()
            _sortDir.value     = settings.sortDir.first()
            try { migrateLegacySingleSub() } catch (e: Exception) {
                Log.w(TAG, "legacy migration: ${e.message}")
            }
            dao.observeAll().collect { entities ->
                val list = entities.map { it.toServerConfig() }
                _servers.value = list
                reconcileSelection(list)
            }
        }
        // Подписки
        viewModelScope.launch {
            subDao.observeAll().collect { _subscriptions.value = it }
        }
        // subInfo = инфо подписки выбранного сервера (fallback — первая подписка)
        viewModelScope.launch {
            combine(_subscriptions, _selectedKey) { subs, key ->
                val subId = key.substringBefore("::", "")
                subs.firstOrNull { it.id == subId } ?: subs.firstOrNull()
            }.collect { sub ->
                if (sub != null) {
                    _subInfo.value = SubscriptionInfo(
                        title      = sub.infoTitle.ifEmpty { sub.name },
                        upload     = sub.upload,
                        download   = sub.download,
                        total      = sub.total,
                        expireUnix = sub.expireUnix
                    )
                }
            }
        }
        viewModelScope.launch { settings.subUrl.collect      { _subUrl.value = it } }
        viewModelScope.launch { settings.vpnMode.collect     { _vpnMode.value = it } }
        viewModelScope.launch { BearVpnService.logFlow.collect { msg -> addLog(msg) } }
        // Источник истины для состояния — connectedFlow сервиса.
        viewModelScope.launch {
            BearVpnService.connectedFlow.collect { running ->
                _connected.value = running
            }
        }
    }

    /** Поддерживает selectedIdx/selectedKey согласованными при изменениях списка. */
    private suspend fun reconcileSelection(list: List<ServerConfig>) {
        if (list.isEmpty()) { _selectedIdx.value = 0; return }
        val key = _selectedKey.value
        if (key.isNotEmpty()) {
            var idx = list.indexOfFirst { it.selectionKey == key }
            if (idx < 0 && "::" in key) {
                // fallback: только имя (как restoreSelection в Tauri)
                val name = key.substringAfter("::")
                idx = list.indexOfFirst { it.name == name }
            }
            if (idx >= 0) {
                _selectedIdx.value = idx
                settings.setSelectedIdx(idx)
            } else {
                // Выбранный сервер исчез (например, удалили подписку) — берём первый
                val first = list.first()
                _selectedIdx.value = 0
                _selectedKey.value = first.selectionKey
                settings.setSelectedKey(first.selectionKey)
                settings.setSelectedIdx(0)
            }
        } else {
            // Legacy: мигрируем selectedIdx → selectedKey
            val idx = settings.selectedIdx.first().coerceIn(0, list.size - 1)
            _selectedIdx.value = idx
            val k = list[idx].selectionKey
            _selectedKey.value = k
            settings.setSelectedKey(k)
        }
    }

    // ── Подписки: миграция legacy (single-sub → первая подписка) ─────────────

    private val legacyMigrated = AtomicBoolean(false)

    /**
     * Порт loadSubs() из Tauri: если таблица подписок пуста, но есть старые
     * sub_url + серверы (после миграции БД v1→v2) — создаём первую подписку
     * и перепривязываем к ней "осиротевшие" серверы.
     */
    private suspend fun migrateLegacySingleSub() {
        if (!legacyMigrated.compareAndSet(false, true)) return
        val subs = subDao.getAll()
        if (subs.isNotEmpty()) {
            if (dao.countUnassigned() > 0) {
                dao.reassignServers("", subs.first().id)
                dao.reassignServers("legacy", subs.first().id)
            }
            return
        }
        val url = settings.subUrl.first()
        if (url.isEmpty()) return   // свежая установка — нечего мигрировать
        val title = settings.subTitle.first()
        val sub = SubscriptionEntity(
            id           = newSubId(),
            name         = title.ifEmpty { hostFromUrl(url).ifEmpty { url } },
            url          = url,
            infoTitle    = title,
            upload       = settings.subUpload.first(),
            download     = settings.subDownload.first(),
            total        = settings.subTotal.first(),
            expireUnix   = settings.subExpire.first(),
            sortOrder    = 0,
            lastUpdateMs = System.currentTimeMillis()
        )
        subDao.insert(sub)
        dao.reassignServers("", sub.id)
        dao.reassignServers("legacy", sub.id)
        addLog("[Info] Migrated legacy subscription → ${sub.name}")
    }

    // ── Подписки: загрузка / обновление (порт doLoad/refreshSub из Tauri) ────

    private data class FetchedSub(val servers: List<ServerConfig>, val info: SubscriptionInfo)

    private suspend fun fetchSubscription(url: String): FetchedSub {
        val proxyPort = if (_connected.value && _vpnMode.value == "proxy")
            settings.proxyPort.first() else 0
        val (content, info) = SubscriptionParser.download(url, proxyPort)
        val parsed = SubscriptionParser.parse(content)
        if (parsed.isEmpty())
            throw IllegalStateException(context.getString(R.string.error_no_servers_found))
        return FetchedSub(parsed, info)
    }

    /**
     * Главная точка входа (имя сохранено для совместимости со старым UI).
     * Как doLoad() в Tauri: если URL уже есть среди подписок — обновляем её,
     * иначе создаём новую. При ошибке загрузки НОВОЙ подписки она не создаётся
     * (rollback, как в Tauri).
     */
    fun loadSubscription(url: String) = addOrRefreshSubscription(url)

    fun addOrRefreshSubscription(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            _error.value   = null
            try {
                val existing = subDao.getAll().firstOrNull { it.url == trimmed }
                if (existing != null) {
                    refreshSubscriptionInternal(existing)
                    addLog("[Info] Subscription refreshed: ${existing.name}")
                } else {
                    val fetched = fetchSubscription(trimmed)
                    val sub = SubscriptionEntity(
                        id           = newSubId(),
                        name         = fetched.info.title.ifEmpty {
                            hostFromUrl(trimmed).ifEmpty { trimmed }
                        },
                        url          = trimmed,
                        infoTitle    = fetched.info.title,
                        upload       = fetched.info.upload,
                        download     = fetched.info.download,
                        total        = fetched.info.total,
                        expireUnix   = fetched.info.expireUnix,
                        sortOrder    = (subDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1,
                        lastUpdateMs = System.currentTimeMillis()
                    )
                    subDao.insert(sub)
                    dao.insertAll(fetched.servers.mapIndexed { i, cfg ->
                        ServerEntity.fromServerConfig(cfg, sortOrder = i, subscriptionId = sub.id)
                    })
                    syncLegacySubUrl()
                    addLog("[Info] Subscription added: ${sub.name} (${fetched.servers.size} servers)")
                }
            } catch (e: Exception) {
                _error.value = context.getString(R.string.error_loading_prefix, e.message ?: "")
                addLog("[Error] Subscription load failed: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    /** Обновить одну подписку (кнопка refresh в заголовке группы). */
    fun refreshSubscription(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            _error.value   = null
            try {
                val sub = subDao.getById(id)
                if (sub != null) {
                    refreshSubscriptionInternal(sub)
                    addLog("[Info] Subscription refreshed: ${sub.name}")
                }
            } catch (e: Exception) {
                _error.value = context.getString(R.string.error_loading_prefix, e.message ?: "")
                addLog("[Error] Subscription refresh failed: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    /** Обновить все подписки разом. Ошибка показывается, если не обновилась НИ ОДНА. */
    fun refreshAllSubscriptions() {
        viewModelScope.launch(Dispatchers.IO) {
            val subs = subDao.getAll()
            if (subs.isEmpty()) return@launch
            _loading.value = true
            _error.value   = null
            var ok = 0
            var fail = 0
            var lastError: String? = null
            subs.forEach { sub ->
                try {
                    refreshSubscriptionInternal(sub)
                    ok++
                } catch (e: Exception) {
                    fail++
                    lastError = e.message
                    addLog("[Warning] Refresh failed for ${sub.name}: ${e.message}")
                }
            }
            if (ok == 0 && fail > 0) {
                _error.value = context.getString(R.string.error_loading_prefix, lastError ?: "")
            }
            addLog("[Info] Refresh all: $ok ok, $fail failed")
            _loading.value = false
        }
    }

    /**
     * Порт refreshSub() из Tauri: перекачивает серверы подписки.
     * Избранное и последний пинг сохраняются по имени сервера.
     */
    private suspend fun refreshSubscriptionInternal(sub: SubscriptionEntity) {
        val fetched = fetchSubscription(sub.url)
        val old     = dao.getBySubscription(sub.id)
        val favByName  = old.filter { it.isFavorite }.map { it.name }.toSet()
        val pingByName = old.associate { it.name to it.pingMs }
        dao.deleteBySubscription(sub.id)
        dao.insertAll(fetched.servers.mapIndexed { i, cfg ->
            ServerEntity.fromServerConfig(
                cfg,
                sortOrder      = i,
                subscriptionId = sub.id,
                isFavorite     = cfg.name in favByName
            ).copy(pingMs = pingByName[cfg.name] ?: -1L)
        })
        subDao.insert(sub.copy(
            name         = fetched.info.title.ifEmpty { sub.name },
            infoTitle    = fetched.info.title,
            upload       = fetched.info.upload,
            download     = fetched.info.download,
            total        = fetched.info.total,
            expireUnix   = fetched.info.expireUnix,
            lastUpdateMs = System.currentTimeMillis()
        ))
    }

    /**
     * Порт cloneSub() из Tauri: копия подписки со всеми серверами.
     * Имя: "Name — копия", при повторе "Name — копия 2" и т.д.
     * Пинги сбрасываются (-1), копия встаёт сразу после оригинала.
     */
    fun cloneSubscription(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val orig = subDao.getById(id) ?: return@launch
            val suffix = copySuffix()
            val existingNames = subDao.getAll().map { it.name }.toSet()
            var copyName = "${orig.name} — $suffix"
            var n = 2
            while (copyName in existingNames) { copyName = "${orig.name} — $suffix $n"; n++ }
            val clone = SubscriptionEntity(
                id           = newSubId(),
                name         = copyName,
                url          = orig.url,
                infoTitle    = orig.infoTitle,
                upload       = orig.upload,
                download     = orig.download,
                total        = orig.total,
                expireUnix   = orig.expireUnix,
                sortOrder    = orig.sortOrder + 1,   // сразу после оригинала
                lastUpdateMs = System.currentTimeMillis()
            )
            subDao.insert(clone)
            val servers = dao.getBySubscription(orig.id)
            dao.insertAll(servers.map { it.copy(id = 0, subscriptionId = clone.id, pingMs = -1L) })
            addLog("[Info] Subscription cloned: $copyName")
        }
    }

    /**
     * Порт deleteSub() из Tauri: удаляет подписку и её серверы.
     * Если выбранный сервер был в ней — выбор сбрасывается
     * (reconcileSelection переведёт на первый доступный).
     */
    fun deleteSubscription(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteBySubscription(id)
            subDao.deleteById(id)
            if (_selectedKey.value.startsWith("$id::")) {
                _selectedKey.value = ""
                settings.setSelectedKey("")
            }
            syncLegacySubUrl()
            addLog("[Info] Subscription deleted")
        }
    }

    /** Свернуть/развернуть группу подписки в списке серверов. */
    fun toggleCollapsed(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sub = subDao.getById(id) ?: return@launch
            subDao.update(sub.copy(collapsed = !sub.collapsed))
        }
    }

    /** Переименовать подписку вручную. */
    fun renameSubscription(id: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val sub = subDao.getById(id) ?: return@launch
            subDao.update(sub.copy(name = name))
        }
    }

    /** Избранное: сервер поднимается наверх своей группы. */
    fun toggleFavorite(server: ServerConfig) {
        if (server.dbId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.setFavorite(server.dbId, !server.isFavorite)
        }
    }

    // ── Подписки: helpers ─────────────────────────────────────────────────────

    /** Порт newSubId() из Tauri main.js: "sub_<ts36>_<rand5>". */
    private fun newSubId(): String {
        val ts   = System.currentTimeMillis().toString(36)
        val rand = (1..5).map { SUB_ID_ALPHABET.random() }.joinToString("")
        return "sub_${ts}_$rand"
    }

    /** Порт hostFromUrl() из Tauri main.js. */
    private fun hostFromUrl(url: String): String = try {
        URI(url).host ?: ""
    } catch (_: Exception) { "" }

    /** "копия"/"copy" — суффикс имени клона (порт I18N.t("SubCopy")). */
    private fun copySuffix(): String =
        if (language.value.startsWith("en")) "copy" else "копия"

    /** Совместимость со старым UI: sub_url всегда указывает на первую подписку. */
    private suspend fun syncLegacySubUrl() {
        settings.setSubUrl(subDao.getAll().firstOrNull()?.url ?: "")
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
            updated.forEach { cfg ->
                if (cfg.dbId > 0) dao.updatePing(cfg.dbId, cfg.pingMs)
            }
        }
    }

    fun autoSelectBest() {
        viewModelScope.launch {
            ServerPinger.findBest(_servers.value)?.let { selectServerByKey(it.selectionKey) }
        }
    }

    // ── Выбор сервера ─────────────────────────────────────────────────────────

    /**
     * Выбор по ключу "subId::name" (порт last_server из Tauri main.js).
     * selectedIdx сохраняется для обратной совместимости и VpnController.
     */
    fun selectServerByKey(key: String) {
        viewModelScope.launch {
            _selectedKey.value = key
            settings.setSelectedKey(key)
            val idx = _servers.value.indexOfFirst { it.selectionKey == key }
            if (idx >= 0) applySelectedIdx(idx)
        }
    }

    fun selectServer(idx: Int) {
        val cfg = _servers.value.getOrNull(idx) ?: return
        selectServerByKey(cfg.selectionKey)
    }

    private suspend fun applySelectedIdx(idx: Int) {
        val changed = idx != _selectedIdx.value
        _selectedIdx.value = idx
        if (changed && BearVpnService.isActive()) {
            // [QuickTiles] VPN включён — сразу переключаемся на новый сервер
            // (так же, как из плитки в шторке). selectAndSwitch сам сохранит индекс.
            _trafficOk.value = null
            VpnController.selectAndSwitch(context, idx, connectIfStopped = false)
        } else {
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
