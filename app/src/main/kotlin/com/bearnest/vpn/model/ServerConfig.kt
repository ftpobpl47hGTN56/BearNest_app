package com.bearnest.vpn.model

/**
 * Конфигурация одного сервера.
 * Полный порт ServerConfig.cs из Windows-версии BearNest.
 */
data class ServerConfig(
    val name: String = "",
    val protocol: String = "",   // vless, vmess, trojan, ss
    val address: String = "",
    val port: Int = 0,
    val id: String = "",         // UUID для vless/vmess, password для trojan/ss
    val security: String = "",   // none | tls | reality
    val network: String = "tcp", // tcp | ws | xhttp | grpc
    val publicKey: String = "",  // Reality
    val shortId: String = "",    // Reality
    val serverName: String = "", // SNI
    val path: String = "/",      // WS / xhttp path
    val flow: String = "",       // xtls-rprx-vision и т.д.
    val fingerprint: String = "chrome",
    var pingMs: Long = -1L       // -1 = не проверен, -2 = недоступен
) {
    /**
     * Отображение пинга для UI — аналог PingDisplay из C#.
     */
    val pingDisplay: String
        get() = when {
            pingMs == -1L -> "—"
            pingMs == -2L -> "✕"
            pingMs < 80   -> "● ${pingMs}ms"   // зелёный
            pingMs < 150  -> "◐ ${pingMs}ms"   // жёлтый
            else          -> "○ ${pingMs}ms"   // красный
        }

    val pingColor: PingColor
        get() = when {
            pingMs == -1L -> PingColor.NEUTRAL
            pingMs == -2L -> PingColor.ERROR
            pingMs < 80   -> PingColor.GOOD
            pingMs < 150  -> PingColor.OK
            else          -> PingColor.BAD
        }

    override fun toString() =
        if (name.isBlank()) "$protocol://$address:$port" else name
}

enum class PingColor { NEUTRAL, ERROR, GOOD, OK, BAD }
