package com.bearnest.vpn.core

import com.bearnest.vpn.model.ServerConfig
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

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
}
