package com.bsci.medlink

import android.app.Application
import com.bsci.medlink.common.model.GlobalSettings

class MainApplication: Application() {
    var globalSettings: GlobalSettings = GlobalSettings()

    override fun onCreate() {
        super.onCreate()
    }
}