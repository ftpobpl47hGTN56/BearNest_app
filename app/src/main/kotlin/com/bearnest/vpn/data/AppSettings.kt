package com.bearnest.vpn.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bearnest_settings")

/**
 * Настройки приложения через DataStore.
 */
class AppSettings(private val context: Context) {

    companion object {
        val KEY_SUB_TITLE     = stringPreferencesKey("sub_title")
        val KEY_SUB_UPLOAD    = longPreferencesKey("sub_upload")
        val KEY_SUB_DOWNLOAD  = longPreferencesKey("sub_download")
        val KEY_SUB_TOTAL     = longPreferencesKey("sub_total")
        val KEY_SUB_EXPIRE    = longPreferencesKey("sub_expire")
        val KEY_SUB_URL       = stringPreferencesKey("sub_url")
        val KEY_SELECTED_IDX  = intPreferencesKey("selected_server_idx")
        val KEY_PROXY_PORT    = intPreferencesKey("proxy_port")
        val KEY_HTTP_PORT     = intPreferencesKey("http_port")
        val KEY_VPN_MODE      = stringPreferencesKey("vpn_mode")
        val KEY_LANGUAGE      = stringPreferencesKey("language")
        val KEY_AUTOSTART     = booleanPreferencesKey("autostart")
        val KEY_WATCHDOG      = booleanPreferencesKey("watchdog")

        // ── Split Tunneling ───────────────────────────────────────────
        val KEY_BYPASS_DOMAINS_JSON  = stringPreferencesKey("bypass_domains_json")
        val KEY_SPLIT_TUNNEL_ENABLED = booleanPreferencesKey("split_tunnel_enabled")

        const val DEFAULT_PROXY_PORT = 10808
        const val DEFAULT_HTTP_PORT  = 10809
        const val DEFAULT_VPN_MODE   = "proxy"
    }

    val subUrl: Flow<String>    = context.dataStore.data.map { it[KEY_SUB_URL] ?: "" }
    val subTitle: Flow<String>  = context.dataStore.data.map { it[KEY_SUB_TITLE] ?: "" }
    val subUpload: Flow<Long>   = context.dataStore.data.map { it[KEY_SUB_UPLOAD] ?: 0L }
    val subDownload: Flow<Long> = context.dataStore.data.map { it[KEY_SUB_DOWNLOAD] ?: 0L }
    val subTotal: Flow<Long>    = context.dataStore.data.map { it[KEY_SUB_TOTAL] ?: 0L }
    val subExpire: Flow<Long>   = context.dataStore.data.map { it[KEY_SUB_EXPIRE] ?: 0L }
    val selectedIdx: Flow<Int>  = context.dataStore.data.map { it[KEY_SELECTED_IDX] ?: 0 }
    val proxyPort: Flow<Int>    = context.dataStore.data.map { it[KEY_PROXY_PORT] ?: DEFAULT_PROXY_PORT }
    val httpPort: Flow<Int>     = context.dataStore.data.map { it[KEY_HTTP_PORT] ?: DEFAULT_HTTP_PORT }
    val vpnMode: Flow<String>   = context.dataStore.data.map { it[KEY_VPN_MODE] ?: DEFAULT_VPN_MODE }
    val language: Flow<String>  = context.dataStore.data.map { it[KEY_LANGUAGE] ?: "ru" }
    val autostart: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTOSTART] ?: false }
    val watchdog: Flow<Boolean> = context.dataStore.data.map { it[KEY_WATCHDOG] ?: true }

    val KEY_THEME = stringPreferencesKey("theme")
    val theme: Flow<String> = context.dataStore.data.map { it[KEY_THEME] ?: "midnight" }

    // ── Split Tunneling: Flows ────────────────────────────────────────
    /** JSON-строка вида ["tinkoff.ru","gosuslugi.ru",...] */
    val bypassDomainsJson: Flow<String> =
        context.dataStore.data.map { it[KEY_BYPASS_DOMAINS_JSON] ?: "[]" }

    /** true = bypass-список активен (default: включён) */
    val splitTunnelEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_SPLIT_TUNNEL_ENABLED] ?: true }

    // ── Suspend setters (существующие) ───────────────────────────────

    suspend fun setTheme(theme: String) =
        context.dataStore.edit { it[KEY_THEME] = theme }

    suspend fun setSubUrl(url: String) =
        context.dataStore.edit { it[KEY_SUB_URL] = url }

    suspend fun setSubTitle(title: String) =
        context.dataStore.edit { it[KEY_SUB_TITLE] = title }

    suspend fun setSubInfo(info: com.bearnest.vpn.model.SubscriptionInfo) =
        context.dataStore.edit {
            it[KEY_SUB_TITLE]    = info.title
            it[KEY_SUB_UPLOAD]   = info.upload
            it[KEY_SUB_DOWNLOAD] = info.download
            it[KEY_SUB_TOTAL]    = info.total
            it[KEY_SUB_EXPIRE]   = info.expireUnix
        }

    suspend fun setSelectedIdx(idx: Int) =
        context.dataStore.edit { it[KEY_SELECTED_IDX] = idx }

    suspend fun setProxyPort(port: Int) =
        context.dataStore.edit { it[KEY_PROXY_PORT] = port }

    suspend fun setHttpPort(port: Int) =
        context.dataStore.edit { it[KEY_HTTP_PORT] = port }

    suspend fun setVpnMode(mode: String) =
        context.dataStore.edit { it[KEY_VPN_MODE] = mode }

    suspend fun setLanguage(lang: String) =
        context.dataStore.edit { it[KEY_LANGUAGE] = lang }

    suspend fun setAutostart(enabled: Boolean) =
        context.dataStore.edit { it[KEY_AUTOSTART] = enabled }

    suspend fun setWatchdog(enabled: Boolean) =
        context.dataStore.edit { it[KEY_WATCHDOG] = enabled }

    // ── Split Tunneling: suspend setters ─────────────────────────────

    /**
     * Включить / выключить bypass-список целиком.
     * Если выключен — ConfigGenerator игнорирует список доменов.
     */
    suspend fun setSplitTunnelEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_SPLIT_TUNNEL_ENABLED] = enabled }

    /** Полностью заменить список bypass-доменов. */
    suspend fun setBypassDomains(domains: Set<String>) {
        val json = JSONArray(domains.toList()).toString()
        context.dataStore.edit { it[KEY_BYPASS_DOMAINS_JSON] = json }
    }

    /**
     * Добавить один домен.
     * @return true — домен добавлен, false — уже был в списке
     */
    suspend fun addBypassDomain(domain: String): Boolean {
        var added = false
        context.dataStore.edit { prefs ->
            val current = parseDomainsJson(prefs[KEY_BYPASS_DOMAINS_JSON] ?: "[]").toMutableSet()
            if (domain !in current) {
                current.add(domain)
                prefs[KEY_BYPASS_DOMAINS_JSON] = JSONArray(current.toList()).toString()
                added = true
            }
        }
        return added
    }

    /** Удалить один домен. */
    suspend fun removeBypassDomain(domain: String) {
        context.dataStore.edit { prefs ->
            val current = parseDomainsJson(prefs[KEY_BYPASS_DOMAINS_JSON] ?: "[]").toMutableSet()
            current.remove(domain)
            prefs[KEY_BYPASS_DOMAINS_JSON] = JSONArray(current.toList()).toString()
        }
    }

    /**
     * Добавить пресет из популярных RU-сервисов.
     * @return количество новых доменов (которых ещё не было в списке)
     */
    suspend fun addBypassPresets(): Int {
        val presets = setOf(
            // Банки
            "tinkoff.ru", "tinkoff.com",
            "sberbank.ru", "sber.ru", "sbbol.ru",
            "alfabank.ru", "vtb.ru", "gazprombank.ru",
            "raiffeisen.ru", "rshb.ru", "pochtabank.ru",
            "mtsbank.ru", "sovcombank.ru", "rosbank.ru",
            // Госсервисы
            "gosuslugi.ru", "esia.gosuslugi.ru",
            "nalog.ru", "lkfl.nalog.ru",
            "mos.ru", "cbr.ru",
            "sfr.gov.ru", "fss.ru", "fns.gov.ru",
            "mvd.ru", "gibdd.ru", "rosreestr.gov.ru",
            // Почта и соцсети
            "vk.com", "vk.ru", "ok.ru",
            "mail.ru", "bk.ru", "inbox.ru",
            // Яндекс
            "yandex.ru", "yandex.net", "ya.ru",
            // СМИ
            "rbc.ru", "ria.ru", "tass.ru", "rambler.ru",
            // Маркетплейсы
            "wildberries.ru", "wb.ru", "ozon.ru",
            "avito.ru", "hh.ru",
            // Телеком
            "mts.ru", "beeline.ru", "megafon.ru", "tele2.ru"
        )
        var added = 0
        context.dataStore.edit { prefs ->
            val current = parseDomainsJson(prefs[KEY_BYPASS_DOMAINS_JSON] ?: "[]").toMutableSet()
            val before = current.size
            current.addAll(presets)
            prefs[KEY_BYPASS_DOMAINS_JSON] = JSONArray(current.toList()).toString()
            added = current.size - before
        }
        return added
    }

    /**
     * Вспомогательная: парсит JSON-строку в Set<String>.
     * Используется внутри dataStore.edit{} — не suspend, синхронная.
     */
    fun parseDomainsJson(json: String): Set<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Прочитать текущий список bypass-доменов разово (не как Flow).
     * Используется в ConfigGenerator перед запуском VPN.
     */
    suspend fun getBypassDomainsOnce(): Set<String> =
        parseDomainsJson(bypassDomainsJson.first())

    /** Прочитать флаг split tunnel разово. */
    suspend fun isSplitTunnelEnabledOnce(): Boolean =
        splitTunnelEnabled.first()
}