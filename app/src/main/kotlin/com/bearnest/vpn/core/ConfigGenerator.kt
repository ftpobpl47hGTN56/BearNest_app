package com.bearnest.vpn.core

import com.bearnest.vpn.model.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Генератор конфигурации xray-core.
 *
 * Два режима:
 *   1. [generateProxy] — inbounds: socks:proxyPort + http:httpPort
 *   2. [generateTun]   — inbound: tun с tunFd из VpnService
 *
 * Split Tunneling:
 *   Оба метода принимают [bypassDomains] — Set доменов, которые
 *   Xray направит через outbound "direct" (freedom), минуя прокси.
 *   Вызывающий код (BearVpnService) читает список из AppSettings
 *   через appSettings.getBypassDomainsOnce() перед вызовом generate*.
 */
object ConfigGenerator {

    // ── Proxy mode ─────────────────────────────────────────────────

    fun generateProxy(
        server: ServerConfig,
        proxyPort: Int = 10808,
        httpPort: Int = 10809,
        bypassDomains: Set<String> = emptySet(),
        splitTunnelEnabled: Boolean = false
    ): String {
        val config = JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "debug")
                put("access", "none")
            })
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("listen", "127.0.0.1")
                    put("port", proxyPort)
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                    // Без sniffing Xray видит только IP-адреса от hevtun,
                    // и domain-правила bypass никогда не срабатывают.
                    // sniffing заставляет Xray читать TLS SNI / HTTP Host
                    // и переопределять destination по реальному домену.
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().apply {
                            put("http")
                            put("tls")
                            put("quic")   // на случай HTTP/3
                        })
                        put("routeOnly", false)
                    })
                    put("tag", "socks-in")
                })
                put(JSONObject().apply {
                    put("listen", "127.0.0.1")
                    put("port", httpPort)
                    put("protocol", "http")
                    put("tag", "http-in")
                })
            })
            put("outbounds", buildOutbounds(server))
            put("routing", buildRouting(
                bypassDomains = if (splitTunnelEnabled) bypassDomains else emptySet()
            ))
        }
        return config.toString(2)
    }

    // ── TUN mode ───────────────────────────────────────────────────

    fun generateTun(
        server: ServerConfig,
        tunFd: Int,
        bypassDomains: Set<String> = emptySet(),
        splitTunnelEnabled: Boolean = false
    ): String {
        val config = JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "debug") })
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("listen", "127.0.0.1")
                    put("port", 10808)
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                    put("tag", "socks-in")
                })
            })
            put("outbounds", buildOutbounds(server))
            put("routing", buildRoutingTun(
                bypassDomains = if (splitTunnelEnabled) bypassDomains else emptySet()
            ))
        }
        return config.toString(2)
    }

    // ── Outbounds ──────────────────────────────────────────────────

    private fun buildOutbounds(server: ServerConfig): JSONArray {
        return JSONArray().apply {
            put(buildServerOutbound(server))
            put(JSONObject().apply {
                put("protocol", "freedom")
                put("tag", "direct")
                put("settings", JSONObject().apply {
                    put("domainStrategy", "UseIPv4")
                })
            })
            put(JSONObject().apply {
                put("protocol", "blackhole")
                put("tag", "block")
            })
        }
    }

    private fun buildServerOutbound(s: ServerConfig): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")
            when (s.protocol) {
                "vless"  -> buildVless(this, s)
                "vmess"  -> buildVmess(this, s)
                "trojan" -> buildTrojan(this, s)
                "ss"     -> buildShadowsocks(this, s)
            }
        }
    }

    private fun buildVless(obj: JSONObject, s: ServerConfig) {
        obj.put("protocol", "vless")
        obj.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", s.address)
                    put("port", s.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", s.id)
                            put("encryption", "none")
                            put("flow", s.flow)
                            put("level", 0)
                        })
                    })
                })
            })
        })
        obj.put("streamSettings", buildStream(s))
    }

    private fun buildVmess(obj: JSONObject, s: ServerConfig) {
        obj.put("protocol", "vmess")
        obj.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", s.address)
                    put("port", s.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", s.id)
                            put("alterId", 0)
                            put("security", "auto")
                            put("level", 0)
                        })
                    })
                })
            })
        })
        if (s.network.isNotEmpty()) obj.put("streamSettings", buildStream(s))
    }

    private fun buildTrojan(obj: JSONObject, s: ServerConfig) {
        obj.put("protocol", "trojan")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", s.address)
                    put("port", s.port)
                    put("password", s.id)
                    put("level", 0)
                })
            })
        })
        obj.put("streamSettings", buildStream(s))
    }

    private fun buildShadowsocks(obj: JSONObject, s: ServerConfig) {
        obj.put("protocol", "shadowsocks")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", s.address)
                    put("port", s.port)
                    put("password", s.id)
                    put("method", "chacha20-ietf-poly1305")
                    put("level", 0)
                })
            })
        })
    }

    private fun buildStream(s: ServerConfig): JSONObject {
        return JSONObject().apply {
            put("network", s.network)
            when (s.security) {
                "reality" -> {
                    put("security", "reality")
                    put("realitySettings", JSONObject().apply {
                        put("serverName", s.serverName)
                        put("fingerprint", s.fingerprint.ifEmpty { "chrome" })
                        put("publicKey", s.publicKey)
                        put("shortId", s.shortId)
                        put("show", false)
                        put("spiderX", "/")
                    })
                }
                "tls" -> {
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", s.serverName)
                        put("fingerprint", s.fingerprint.ifEmpty { "chrome" })
                        put("allowInsecure", false)
                    })
                }
                else -> put("security", "none")
            }
            when (s.network) {
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", s.path.ifEmpty { "/" })
                    put("headers", JSONObject().apply {
                        if (s.serverName.isNotEmpty()) put("Host", s.serverName)
                    })
                })
                "xhttp" -> put("xhttpSettings", JSONObject().apply {
                    put("path", s.path.ifEmpty { "/" })
                    put("host", s.serverName)
                    put("mode", "auto")
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", s.path)
                    put("multiMode", false)
                })
                "h2" -> put("httpSettings", JSONObject().apply {
                    put("path", s.path.ifEmpty { "/" })
                    put("host", JSONArray().apply {
                        if (s.serverName.isNotEmpty()) put(s.serverName)
                    })
                })
            }
        }
    }

    // ── Routing ────────────────────────────────────────────────────

    /**
     * Routing для Proxy-режима.
     *
     * Порядок правил важен — Xray применяет ПЕРВОЕ совпавшее:
     *   1. bypass-домены → direct   (если split tunnel включён)
     *   2. bittorrent    → direct
     *   (всё остальное идёт через proxy по умолчанию)
     */
    private fun buildRouting(bypassDomains: Set<String> = emptySet()): JSONObject {
        return JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {

                // ── Правило 1: bypass-домены → direct ─────────────
                // Вставляем ПЕРВЫМ, чтобы оно проверялось раньше остальных.
                if (bypassDomains.isNotEmpty()) {
                    put(buildBypassRule(bypassDomains))
                }

                // ── Правило 2: bittorrent → direct ────────────────
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("protocol", JSONArray().apply { put("bittorrent") })
                })
            })
        }
    }

    /**
     * Routing для TUN-режима.
     *
     *   1. bypass-домены  → direct   (если split tunnel включён)
     *   2. geoip:private  → direct   (локальная сеть)
     *   3. socks-in/tun-in → proxy   (весь остальной трафик)
     */
    private fun buildRoutingTun(bypassDomains: Set<String> = emptySet()): JSONObject {
        return JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {

                // ── Правило 1: bypass-домены → direct ─────────────
                if (bypassDomains.isNotEmpty()) {
                    put(buildBypassRule(bypassDomains))
                }

                // ── Правило 2: приватные IP → direct ──────────────
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply { put("geoip:private") })
                })

                // ── Правило 3: весь остальной трафик → proxy ──────
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("inboundTag", JSONArray().apply {
                        put("tun-in")
                        put("socks-in")
                    })
                })
            })
        }
    }

    /**
     * Строит одно routing-правило для bypass-доменов.
     *
     * Формат Xray для поля "domain":
     *   "domain:tinkoff.ru"   — совпадает с tinkoff.ru и ВСЕМИ субдоменами
     *   "full:api.tinkoff.ru" — только точное совпадение
     *   "keyword:gosuslugi"   — любой домен, содержащий строку
     *
     * Если пользователь ввёл "tinkoff.ru" без префикса —
     * добавляем "domain:" автоматически.
     */
    private fun buildBypassRule(domains: Set<String>): JSONObject {
        val domainArray = JSONArray()
        val ipArray = JSONArray()

        domains.forEach { raw ->
            val entry = raw.trim().lowercase()
            when {
                // Уже с xray-префиксом — оставляем как есть
                entry.startsWith("domain:") ||
                        entry.startsWith("full:")   ||
                        entry.startsWith("keyword:")||
                        entry.startsWith("regexp:") -> domainArray.put(entry)

                // IP-адрес или CIDR (192.168.1.0/24)
                entry.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")) ->
                    ipArray.put(entry)

                // Обычный домен — добавляем "domain:" для охвата субдоменов
                else -> domainArray.put("domain:$entry")
            }
        }

        return JSONObject().apply {
            put("type", "field")
            put("outboundTag", "direct")
            if (domainArray.length() > 0) put("domain", domainArray)
            if (ipArray.length() > 0)     put("ip", ipArray)
        }
    }
}