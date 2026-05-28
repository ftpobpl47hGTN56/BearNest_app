package com.v2ray.ang.service

import android.net.VpnService
import android.util.Log

open class TProxyService : VpnService() {

    companion object {
        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                Log.i("TProxyService", "libhev-socks5-tunnel loaded OK")
            } catch (e: Throwable) {
                Log.e("TProxyService", "load failed: ${e.message}")
            }
        }
    }

    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray

    override fun protect(socket: Int): Boolean = super.protect(socket)
    fun sendSelf() {}
}