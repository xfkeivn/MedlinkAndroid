package com.bsci.medlink.utils

import android.content.Context
import android.util.Log
import com.bsci.medlink.MainApplication
import com.bsci.medlink.R
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.Constants

/**
 * Agora RTC Engine 管理器
 * 统一管理 RtcEngine 实例，避免在多个 Activity 中重复初始化
 * 
 * 使用 Kotlin object 实现单例模式，更简洁且线程安全
 */
object AgoraManager {
    private const val TAG = "AgoraManager"
    private var engine: RtcEngine? = null
    
    
    /**
     * 初始化或获取 RtcEngine 实例
     * @param context Context 实例，建议传入 Activity 或 Application 的 context
     * @return RtcEngine 实例，如果初始化失败返回 null
     */
    fun getOrCreateEngine(context: Context): RtcEngine? {
        if (engine == null) {
            val appContext = context.applicationContext
            
            try {
                val config = RtcEngineConfig()
                config.mContext = appContext
                config.mAppId = appContext.getString(R.string.agora_app_id)
                config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                config.mAudioScenario = Constants.AudioScenario.getValue(Constants.AudioScenario.DEFAULT)
                
                (appContext as? MainApplication)?.globalSettings?.areaCode?.let {
                    config.mAreaCode = it
                }
                
                engine = RtcEngine.create(config)
                
                engine?.setParameters(
                    "{" +
                        "\"rtc.report_app_scenario\":" +
                        "{" +
                        "\"appScenario\":" + 100 + "," +
                        "\"serviceType\":" + 11 + "," +
                        "\"appVersion\":\"" + RtcEngine.getSdkVersion() + "\"" +
                        "}" +
                        "}"
                )
                
                val localAccessPointConfiguration =
                    (appContext as? MainApplication)?.globalSettings?.privateCloudConfig
                if (localAccessPointConfiguration != null) {
                    engine?.setLocalAccessPoint(localAccessPointConfiguration)
                }


                Log.d(TAG, "RtcEngine created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create RtcEngine", e)
                return null
            }
        }
        
        return engine
    }
    
    /**
     * 更新事件处理器
     * 注意：Agora SDK 不支持动态更换 EventHandler，需要重新创建实例
     * 这里只是记录当前使用的处理器
     */
    fun addEventHandler(eventHandler: IRtcEngineEventHandler) {
        //multiRectEventHandler?.addListener(eventHandler)
        engine?.addHandler(eventHandler)
        Log.w(TAG, "EventHandler updated, but RtcEngine doesn't support dynamic handler change")
    }

    fun removeEventHandler(eventHandler: IRtcEngineEventHandler) {
        //multiRectEventHandler?.removeListener(eventHandler)
        engine?.removeHandler(eventHandler)
        Log.w(TAG, "EventHandler updated, but RtcEngine doesn't support dynamic handler change")
    }
    
    /**
     * 获取当前的 RtcEngine 实例（不创建新实例）
     */
    fun getEngine(): RtcEngine? = engine
    
    /**
     * 释放 RtcEngine 实例
     * 注意：只有在确定不再使用时才调用，否则会影响其他正在使用的 Activity
     */
    fun releaseEngine() {
        engine?.leaveChannel()
        engine?.stopPreview()
        engine = null
        Log.d(TAG, "RtcEngine released")
    }
    
    /**
     * 销毁 RtcEngine（应用退出时调用）
     */
    fun destroyEngine() {
        releaseEngine()
        try {
            RtcEngine.destroy()
            Log.d(TAG, "RtcEngine destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying RtcEngine", e)
        }
    }
    
    /**
     * 应用音频设备设置
     * @param context Context 实例
     */

}


