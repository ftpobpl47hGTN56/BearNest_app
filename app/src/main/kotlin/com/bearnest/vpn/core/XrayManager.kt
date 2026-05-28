package com.bearnest.vpn.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

class XrayManager(
    private val context: Context,
    private val protectSocket: ((Int) -> Boolean)? = null,
    private val onLog: ((level: Int, msg: String) -> Unit)? = null
) {
    companion object {
        private const val TAG        = "XrayManager"
        private const val ASSETS_DIR = "xray"
    }

    private val xrayDir: File
        get() = File(context.filesDir, "xray").also { it.mkdirs() }

    @Volatile private var controller: libv2ray.CoreController? = null
    @Volatile private var running = false
    private var watchdogJob: Job? = null

    private val callbackHandler = object : libv2ray.CoreCallbackHandler {
        override fun startup(): Long {
            onLog?.invoke(1, "xray startup")
            return 0L
        }

        override fun shutdown(): Long {
            onLog?.invoke(1, "xray shutdown")
            return 0L
        }

        override fun onEmitStatus(level: Long, msg: String): Long {
            val lvl = level.toInt()
            val priority = when (lvl) {
                0 -> Log.DEBUG; 1 -> Log.INFO; 2 -> Log.WARN; else -> Log.ERROR
            }
            Log.println(priority, TAG, "xray: $msg")
            onLog?.invoke(lvl, msg)
            return 0L
        }
    }

    fun version(): String = try {
        libv2ray.Libv2ray.checkVersionX()
    } catch (e: Exception) {
        "unknown"
    }

    suspend fun start(configJson: String): String? =
        startWithTun(configJson, tunFd = 0)

    suspend fun startTun(configJson: String, tunFd: Int): String? =
        startWithTun(configJson, tunFd)

    private suspend fun startWithTun(configJson: String, tunFd: Int): String? =
        withContext(Dispatchers.IO) {
            stop()
            copyGeoFilesIfNeeded()
            libv2ray.Libv2ray.initCoreEnv(xrayDir.absolutePath, "")
            Log.d(TAG, "=== CONFIG ===\n$configJson")  // ← добавь эту строку
            Log.d(TAG, "=== tunFd=$tunFd ===")
            return@withContext try {
                val ctrl = libv2ray.Libv2ray.newCoreController(callbackHandler)
                ctrl.startLoop(configJson, tunFd)
                controller = ctrl
                running = true
                Log.i(TAG, "xray started (tunFd=$tunFd)")
                null
            } catch (e: Exception) {
                val msg = "Ошибка запуска xray: ${e.message}"
                Log.e(TAG, msg)
                msg
            }
        }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        try {
            controller?.stopLoop()
        } catch (e: Exception) {
            Log.w(TAG, "stopLoop: ${e.message}")
        }
        controller = null
        running = false
        Log.i(TAG, "xray stopped")
    }

    val isRunning: Boolean get() = running

    suspend fun measureDelay(url: String = "https://www.gstatic.com/generate_204"): Long =
        withContext(Dispatchers.IO) {
            try { controller?.measureDelay(url) ?: -1L }
            catch (e: Exception) { -1L }
        }

    fun startWatchdog(
        getServers: () -> List<com.bearnest.vpn.model.ServerConfig>,
        getCurrent: () -> com.bearnest.vpn.model.ServerConfig?,
        onSwitch: suspend (com.bearnest.vpn.model.ServerConfig) -> Unit
    ) {
        watchdogJob?.cancel()
        watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(30_000)
                if (!isRunning) continue
                val current = getCurrent() ?: continue
                val pingMs = ServerPinger.ping(current)
                if (pingMs == -2L) {
                    val best = ServerPinger.findBest(ServerPinger.pingAll(getServers()))
                    if (best != null && best.address != current.address) {
                        onSwitch(best)
                    }
                }
            }
        }
    }

    fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun copyGeoFilesIfNeeded() {
        val assetMgr = context.assets
        val available = try { assetMgr.list(ASSETS_DIR) ?: emptyArray() }
        catch (e: Exception) { emptyArray<String>() }
        for (name in listOf("geoip.dat", "geosite.dat")) {
            if (name !in available) continue
            val dest = File(xrayDir, name)
            if (dest.exists() && dest.length() > 100_000) continue
            try {
                assetMgr.open("$ASSETS_DIR/$name").use { i ->
                    dest.outputStream().use { o -> i.copyTo(o) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось скопировать $name: ${e.message}")
            }
        }
    }
}