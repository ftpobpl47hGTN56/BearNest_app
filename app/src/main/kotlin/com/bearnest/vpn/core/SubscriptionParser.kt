package com.bearnest.vpn.core

import android.util.Base64
import com.bearnest.vpn.model.ServerConfig
import com.bearnest.vpn.model.SubscriptionInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Парсер подписок.
 * Полный порт SubscriptionParser.cs с поддержкой:
 *  - Clash/Mihomo YAML
 *  - Base64 URI list
 *  - Прямой список vless:// vmess:// trojan:// ss://
 *  - Happ JSON xray-array  ← [Fixed]
 */
object SubscriptionParser {

    // ── Скачивание ────────────────────────────────────────────────────────────

    /**
     * Скачать содержимое подписки.
     * @param proxyPort  Если > 0 — использовать локальный SOCKS5 (127.0.0.1:proxyPort).
     *                   Нужно когда подписка заблокирована и надо скачать через уже работающий VPN.
     */
    fun download(url: String, proxyPort: Int = 0): Pair<String, SubscriptionInfo> {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)

        if (proxyPort > 0) {
            clientBuilder.proxy(
                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
            )
        }

        val client = clientBuilder.build()

        // [Fixed] User-Agent для Happ API через обычный .header() — addUnsafeHeader не существует в OkHttp
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Happ/2.16.2/Windows/2605221224603")
            .header("Accept", "application/json")
            .header("X-Device-Os", "Windows")
            .header("X-Ver-Os", "10_10.0.19045")
            .header("X-Hwid", "4c7f17cb-05b4-4ec0-b254-275442c3c71d")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val info = parseSubscriptionHeaders(response)
            return Pair(body, info)
        }
    }

    private fun parseSubscriptionHeaders(response: okhttp3.Response): SubscriptionInfo {
        var title = ""
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L

        response.header("profile-title")?.let { raw ->
            title = if (raw.startsWith("base64:")) {
                try { decodeBase64(raw.removePrefix("base64:")) } catch (e: Exception) { raw }
            } else {
                URLDecoder.decode(raw, "UTF-8")
            }
        }

        response.header("subscription-userinfo")?.split(";")?.forEach { part ->
            val kv = part.trim().split("=")
            if (kv.size != 2) return@forEach
            val v = kv[1].trim().toLongOrNull() ?: return@forEach
            when (kv[0].trim()) {
                "upload"   -> upload   = v
                "download" -> download = v
                "total"    -> total    = v
                "expire"   -> expire   = v
            }
        }

        return SubscriptionInfo(title, upload, download, total, expire)
    }

    // ── Парсинг ───────────────────────────────────────────────────────────────

    fun parse(content: String): List<ServerConfig> {
        val text = content.replace("\r\n", "\n").replace("\r", "\n").trim()

        // [Fixed] Новый блок: Happ JSON xray-array — ПЕРЕД блоком when{}
        // Если контент начинается с '[' — пробуем как JSON-массив Happ
        if (text.trimStart().startsWith("[")) {
            try {
                val fromHapp = parseHappXrayArray(text)
                if (fromHapp.isNotEmpty()) return fromHapp
            } catch (_: Exception) {}
        }

        // Остальное без изменений
        val result = mutableListOf<ServerConfig>()
        when {
            text.contains("proxies:") -> result.addAll(parseClashYaml(text))
            else -> {
                val decoded = if (!text.contains("://")) {
                    try { decodeBase64(text) } catch (e: Exception) { text }
                } else text

                decoded.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { line ->
                        try {
                            val cfg = when {
                                line.startsWith("vless://")  -> parseVless(line)
                                line.startsWith("vmess://")  -> parseVmess(line)
                                line.startsWith("trojan://") -> parseTrojan(line)
                                line.startsWith("ss://")     -> parseShadowsocks(line)
                                else -> null
                            }
                            if (cfg != null) result.add(cfg)
                        } catch (_: Exception) {}
                    }
            }
        }
        return result
    }

    // ── Парсер Happ xray-array JSON ──────────────────────────────────────────
    // [Fixed] Новый метод. Использует ТОЛЬКО поля которые есть в ServerConfig
    private fun parseHappXrayArray(json: String): List<ServerConfig> {
        val result = mutableListOf<ServerConfig>()
        val arr = org.json.JSONArray(json)

        for (i in 0 until arr.length()) {
            try {
                val obj      = arr.getJSONObject(i)
                val remarks  = obj.optString("remarks", "")
                val outbounds = obj.optJSONArray("outbounds") ?: continue

                // Берём первый outbound с тегом начинающимся на "proxy"
                var proxyOut: org.json.JSONObject? = null
                for (j in 0 until outbounds.length()) {
                    val o = outbounds.optJSONObject(j) ?: continue
                    if (o.optString("tag").startsWith("proxy")) {
                        proxyOut = o; break
                    }
                }
                proxyOut ?: continue

                val protocol = proxyOut.optString("protocol")
                if (protocol !in listOf("vless", "vmess", "trojan")) continue

                val settings = proxyOut.optJSONObject("settings") ?: continue
                val stream   = proxyOut.optJSONObject("streamSettings")

                val vnext   = settings.optJSONArray("vnext")?.optJSONObject(0) ?: continue
                val address = vnext.optString("address")
                val port    = vnext.optInt("port", 0)
                if (address.isEmpty() || port == 0) continue

                val users = vnext.optJSONArray("users")?.optJSONObject(0)
                val id    = users?.optString("id") ?: ""
                val flow  = users?.optString("flow") ?: ""

                val network  = stream?.optString("network") ?: "tcp"
                val security = stream?.optString("security") ?: "none"

                // [Fixed] Только поля которые реально есть в ServerConfig
                // (serverName, publicKey, shortId, fingerprint, path)
                var serverName  = ""
                var publicKey   = ""
                var shortId     = ""
                var fingerprint = "chrome"
                var path        = "/"

                when (security) {
                    "reality" -> {
                        val rs = stream?.optJSONObject("realitySettings")
                        serverName  = rs?.optString("serverName")  ?: ""
                        publicKey   = rs?.optString("publicKey")   ?: ""
                        shortId     = rs?.optString("shortId")     ?: ""
                        fingerprint = rs?.optString("fingerprint") ?: "chrome"
                    }
                    "tls" -> {
                        val ts = stream?.optJSONObject("tlsSettings")
                        serverName  = ts?.optString("serverName")  ?: ""
                        fingerprint = ts?.optString("fingerprint") ?: "chrome"
                    }
                }

                when (network) {
                    "xhttp" -> path = stream?.optJSONObject("xhttpSettings")?.optString("path") ?: "/"
                    "ws"    -> path = stream?.optJSONObject("wsSettings")?.optString("path") ?: "/"
                }

                // Создаём ServerConfig только из существующих полей
                result.add(ServerConfig(
                    protocol    = protocol,
                    name        = remarks,
                    address     = address,
                    port        = port,
                    id          = id,
                    flow        = flow,
                    network     = network,
                    security    = security,
                    serverName  = serverName,
                    publicKey   = publicKey,
                    shortId     = shortId,
                    fingerprint = fingerprint,
                    path        = path
                ))

            } catch (_: Exception) {}
        }
        return result
    }

    // ── Clash YAML ────────────────────────────────────────────────────────────

    private fun parseClashYaml(yaml: String): List<ServerConfig> {
        val result = mutableListOf<ServerConfig>()

        var proxiesIdx = yaml.indexOf("\nproxies:")
        if (proxiesIdx < 0) proxiesIdx = yaml.indexOf("proxies:")
        if (proxiesIdx < 0) return result

        val lineEnd = yaml.indexOf('\n', proxiesIdx + 1)
        val start = lineEnd + 1
        val remaining = yaml.substring(start)

        val proxyLines = mutableListOf<String>()
        for (line in remaining.split("\n")) {
            if (line.isNotEmpty() && line[0] != ' ' && line[0] != '-' && line.contains(':'))
                break
            proxyLines.add(line)
        }

        val blocks = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        for (line in proxyLines) {
            if (line.trimStart().startsWith("- ") && current.isNotEmpty()) {
                blocks.add(current)
                current = StringBuilder()
            }
            current.appendLine(line)
        }
        if (current.isNotEmpty()) blocks.add(current)

        for (block in blocks) {
            try {
                val cfg = parseClashProxy(block.toString())
                if (cfg != null) result.add(cfg)
            } catch (_: Exception) {}
        }
        return result
    }

    private fun parseClashProxy(block: String): ServerConfig? {
        val lines = block.split("\n")

        fun get(key: String): String {
            for (line in lines) {
                val trimmed = line.trim().trimStart('-').trim()
                if (trimmed.startsWith("$key:", ignoreCase = true)) {
                    return trimmed.substring(key.length + 1).trim().trim('"', '\'')
                }
            }
            return ""
        }

        fun getNested(parentKey: String, childKey: String): String {
            var inParent = false
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("$parentKey:", ignoreCase = true)) {
                    inParent = true; continue
                }
                if (inParent) {
                    if (line.isNotEmpty() && line[0] != ' ') break
                    if (trimmed.startsWith("$childKey:", ignoreCase = true))
                        return trimmed.substring(childKey.length + 1).trim().trim('"', '\'')
                }
            }
            return ""
        }

        val type = get("type").lowercase()
        if (type.isEmpty()) return null
        if (type !in listOf("vless", "vmess", "trojan", "ss", "shadowsocks")) return null

        val port = get("port").toIntOrNull() ?: return null

        val cfg = ServerConfig(
            protocol    = if (type == "shadowsocks") "ss" else type,
            name        = get("name"),
            address     = get("server"),
            port        = port,
            id          = if (type == "trojan" || type == "ss" || type == "shadowsocks")
                get("password") else get("uuid"),
            network     = get("network").ifEmpty { "tcp" },
            serverName  = get("servername"),
            flow        = get("flow"),
            fingerprint = get("client-fingerprint").ifEmpty { "chrome" },
            path        = get("ws-path"),
            publicKey   = getNested("reality-opts", "public-key"),
            shortId     = getNested("reality-opts", "short-id"),
            security    = when {
                block.contains("reality-opts:") -> "reality"
                get("tls") == "true"            -> "tls"
                type == "trojan"                -> "tls"
                else                            -> "none"
            }
        )

        return cfg.copy(
            name = cfg.name.ifEmpty { "$type://${cfg.address}:${cfg.port}" }
        )
    }

    // ── URI парсеры ───────────────────────────────────────────────────────────

    private fun parseVless(uri: String): ServerConfig {
        val u = java.net.URI(uri)
        val params = parseQuery(u.rawQuery ?: "")
        return ServerConfig(
            protocol    = "vless",
            id          = u.userInfo ?: "",
            address     = u.host,
            port        = u.port,
            security    = params["security"] ?: "none",
            network     = params["type"] ?: "tcp",
            serverName  = params["sni"] ?: u.host,
            publicKey   = params["pbk"] ?: "",
            shortId     = params["sid"] ?: "",
            flow        = params["flow"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            path        = params["path"] ?: "/",
            name        = URLDecoder.decode(u.fragment?.trimStart('#') ?: "", "UTF-8")
        )
    }

    private fun parseVmess(uri: String): ServerConfig {
        val json = decodeBase64(uri.removePrefix("vmess://"))
        fun get(key: String): String {
            val idx = json.indexOf("\"$key\"", ignoreCase = true)
            if (idx < 0) return ""
            var i = json.indexOf(':', idx) + 1
            while (i < json.length && (json[i] == ' ' || json[i] == '"')) i++
            val end = json.indexOfFirst(i) { it == '"' || it == ',' || it == '}' }
            return if (end > i) json.substring(i, end) else ""
        }
        return ServerConfig(
            protocol    = "vmess",
            id          = get("id"),
            address     = get("add"),
            port        = get("port").toIntOrNull() ?: 443,
            network     = get("net"),
            security    = if (get("tls") == "tls") "tls" else "none",
            serverName  = get("sni"),
            path        = get("path"),
            name        = get("ps")
        )
    }

    private fun parseTrojan(uri: String): ServerConfig {
        val u = java.net.URI(uri)
        val params = parseQuery(u.rawQuery ?: "")
        return ServerConfig(
            protocol    = "trojan",
            id          = u.userInfo ?: "",
            address     = u.host,
            port        = u.port,
            security    = "tls",
            network     = params["type"] ?: "tcp",
            serverName  = params["sni"] ?: u.host,
            name        = URLDecoder.decode(u.fragment?.trimStart('#') ?: "", "UTF-8")
        )
    }

    private fun parseShadowsocks(uri: String): ServerConfig {
        val u = java.net.URI(uri)
        return ServerConfig(
            protocol = "ss",
            address  = u.host,
            port     = u.port,
            name     = URLDecoder.decode(u.fragment?.trimStart('#') ?: "", "UTF-8")
        )
    }

    // ── Вспомогательные ──────────────────────────────────────────────────────

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").associate { part ->
            val idx = part.indexOf('=')
            if (idx < 0) part to ""
            else part.substring(0, idx) to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }
    }

    private fun decodeBase64(s: String): String {
        var b64 = s.replace('-', '+').replace('_', '/')
        when (b64.length % 4) {
            2 -> b64 += "=="
            3 -> b64 += "="
        }
        return String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun String.indexOfFirst(start: Int, predicate: (Char) -> Boolean): Int {
        for (i in start until length) if (predicate(this[i])) return i
        return length
    }
}