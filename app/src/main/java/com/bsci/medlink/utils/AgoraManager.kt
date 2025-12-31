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
 */
class AgoraManager private constructor(context: Context) {
    private var engine: RtcEngine? = null
    private var currentEventHandler: IRtcEngineEventHandler? = null
    private val context: Context = context.applicationContext
    
    companion object {
        private const val TAG = "AgoraManager"
        @Volatile
        private var INSTANCE: AgoraManager? = null
        
        /**
         * 获取 AgoraManager 单例实例
         */
        fun getInstance(context: Context): AgoraManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgoraManager(context).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 初始化或获取 RtcEngine 实例
     * @param eventHandler 事件处理器，如果为 null 则使用默认处理器
     * @return RtcEngine 实例，如果初始化失败返回 null
     */
    fun getOrCreateEngine(eventHandler: IRtcEngineEventHandler? = null): RtcEngine? {
        if (engine == null) {
            try {
                val config = RtcEngineConfig()
                config.mContext = context
                config.mAppId = context.getString(R.string.agora_app_id)
                config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                config.mEventHandler = eventHandler ?: defaultEventHandler
                config.mAudioScenario = Constants.AudioScenario.getValue(Constants.AudioScenario.DEFAULT)
                
                (context.applicationContext as? MainApplication)?.globalSettings?.areaCode?.let {
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
                    (context.applicationContext as? MainApplication)?.globalSettings?.privateCloudConfig
                if (localAccessPointConfiguration != null) {
                    engine?.setLocalAccessPoint(localAccessPointConfiguration)
                }
                
                currentEventHandler = eventHandler
                Log.d(TAG, "RtcEngine created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create RtcEngine", e)
                return null
            }
        } else {
            // 如果已存在实例，更新事件处理器
            if (eventHandler != null && eventHandler != currentEventHandler) {
                updateEventHandler(eventHandler)
            }
        }
        
        return engine
    }
    
    /**
     * 更新事件处理器
     * 注意：Agora SDK 不支持动态更换 EventHandler，需要重新创建实例
     * 这里只是记录当前使用的处理器
     */
    private fun updateEventHandler(eventHandler: IRtcEngineEventHandler) {
        currentEventHandler = eventHandler
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
        currentEventHandler = null
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
     * 默认事件处理器（用于初始化时）
     */
    private val defaultEventHandler = object : IRtcEngineEventHandler() {
        override fun onError(err: Int) {
            Log.w(TAG, "Default handler: onError code $err")
        }
    }
}

