package com.bambu.floatwidget

import android.app.Application
import com.bambu.floatwidget.data.PrefsManager

class BambuApp : Application() {
    lateinit var prefs: PrefsManager
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
    }
}
