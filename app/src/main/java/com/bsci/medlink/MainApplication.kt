package com.bsci.medlink

import android.app.Application
import com.bsci.medlink.common.model.GlobalSettings
import com.bsci.medlink.data.model.Client
import com.bsci.medlink.utils.AgoraManager

class MainApplication: Application() {
    var globalSettings: GlobalSettings = GlobalSettings()
    
    // 缓存客户端列表，在 SplashActivity 中预加载
    var cachedClients: List<Client>? = null
    
    // Agora 管理器
    val agoraManager: AgoraManager by lazy {
        AgoraManager.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onTerminate() {
        super.onTerminate()
        // 应用退出时销毁 Agora Engine
        agoraManager.destroyEngine()
    }
}