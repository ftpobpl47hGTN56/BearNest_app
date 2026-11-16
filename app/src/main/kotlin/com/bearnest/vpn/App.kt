package com.bearnest.vpn

import android.app.Application
import android.util.Log

class App : Application() {

    override fun onCreate() {

        super.onCreate()
        Log.i("BearNest", "App started, pid=${android.os.Process.myPid()}")
    }
}
