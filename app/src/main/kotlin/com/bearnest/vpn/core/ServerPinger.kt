package com.bearnest.vpn.core

import com.bearnest.vpn.model.ServerConfig
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * TCP-пинг серверов — порт ServerPinger.cs.
 * Параллельно пингует все серверы, возвращает обновлённый список.
 */
object ServerPinger {

    private const val TIMEOUT_MS = 3000
    private const val PARALLELISM = 8

    /**
     * Пингует один сервер.
     * @return задержка в мс, или -2 если недоступен.
     */
    suspend fun ping(server: ServerConfig): Long = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(server.address, server.port),
                    TIMEOUT_MS
                )
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -2L
        }
    }

    /**
     * Пингует все серверы параллельно.
     * @param onProgress  Вызывается после каждого пинга с индексом и результатом.
     */
    suspend fun pingAll(
        servers: List<ServerConfig>,
        onProgress: (index: Int, pingMs: Long) -> Unit = { _, _ -> }
    ): List<ServerConfig> = withContext(Dispatchers.IO) {
        val dispatcher = Dispatchers.IO.limitedParallelism(PARALLELISM)
        servers.mapIndexed { index, server ->
            async(dispatcher) {
                val ms = ping(server)
                onProgress(index, ms)
                server.copy(pingMs = ms)
            }
        }.awaitAll()
    }

    /**
     * Возвращает сервер с наименьшим пингом из списка (исключая недоступные).
     */
    fun findBest(servers: List<ServerConfig>): ServerConfig? =
        servers.filter { it.pingMs >= 0 }.minByOrNull { it.pingMs }

    // ── [Fixed] Реальная проверка трафика через туннель ───────────────────────
    //
    // TCP ping показывает только что порт открыт — сервер может быть жив,
    // но Xray на нём упал или провайдер режет протокол.
    // Этот метод делает реальный HTTP запрос ЧЕРЕЗ туннель (SOCKS5 на 127.0.0.1:socksPort)
    // и смотрит — идёт трафик или нет.
    //
    // Используется ПОСЛЕ подключения для проверки реального состояния туннеля.

    /**
     * Проверяет реальный трафик через активный туннель.
     * Делает HEAD-запрос через SOCKS5 на 127.0.0.1:[socksPort].
     *
     * @param socksPort  Локальный SOCKS5 порт (обычно 10808 у Xray).
     * @param timeoutMs  Таймаут запроса в мс.
     * @return  true  — трафик идёт (сервер реально работает).
     *          false — туннель поднят, но трафик не проходит (сервер сломан).
     */
    suspend fun checkRealTraffic(
        socksPort: Int,
        timeoutMs: Int = 5000
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val proxy = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", socksPort)
            )
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()

            // Cloudflare generate_204 — лёгкий нейтральный endpoint,
            // всегда возвращает 204 No Content если трафик идёт.
            val request = Request.Builder()
                .url("https://cp.cloudflare.com/generate_204")
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                response.code == 204
            }
        } catch (_: Exception) {
            false
        }
    }
}
