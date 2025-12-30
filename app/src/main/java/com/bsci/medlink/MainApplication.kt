package com.bsci.medlink

import android.app.Application
import com.bsci.medlink.common.model.GlobalSettings
import com.bsci.medlink.data.model.Client

class MainApplication: Application() {
    var globalSettings: GlobalSettings = GlobalSettings()
    
    // 缓存客户端列表，在 SplashActivity 中预加载
    var cachedClients: List<Client>? = null

    override fun onCreate() {
        super.onCreate()
    }
}