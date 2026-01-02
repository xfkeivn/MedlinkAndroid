package com.bsci.medlink.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bsci.medlink.R
import com.bsci.medlink.data.local.PreferenceManager
import com.jiangdg.uac.UACAudio.AudioStatus
import io.agora.rtm.*


/**
 * Agora RTM2 客户端管理器
 * 用于管理 RTM 客户端和通道，接收消息并传递给 RTMMessageHandler
 * 参考文档：https://doc.shengwang.cn/api-ref/rtm2/android/toc-configuration/configuration
 */
object RTMManager {
    private const val TAG = "RTMManager"
    private var rtmClient: RtmClient? = null
    private var rtmMessageHandler: RTMMessageHandler? = null
    private var isLoggedIn = false
    private var isJoinedChannel = false
    private var currentChannelId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    
    /**
     * 初始化 RTM 客户端
     * @param context Context 实例
     * @param userId 用户 ID（使用设备 ID）
     * @return 是否初始化成功
     */
    fun initialize(context: Context, userId: String): Boolean {
        if (rtmClient != null) {
            Log.w(TAG, "RTM client already initialized")
            return true
        }
        
        return try {
            val appContext = context.applicationContext
            val appId = appContext.getString(R.string.agora_app_id)
            
            // 创建 RTM 配置
            val rtmConfig = RtmConfig.Builder(appId, userId)
                .eventListener(object : RtmEventListener {
                    override fun onConnectionStateChanged(channelName:String, state:RtmConstants.RtmConnectionState , reason:RtmConstants.RtmConnectionChangeReason ) {

                        when (state) {
                            RtmConstants.RtmConnectionState.CONNECTED -> {
                                Log.d(TAG, "RTM client connected")
                            }
                            RtmConstants.RtmConnectionState.DISCONNECTED -> {
                                Log.d(TAG, "RTM client disconnected, reason: ${reason}")
                                isLoggedIn = false
                            }
                            RtmConstants.RtmConnectionState.CONNECTING -> {
                                Log.d(TAG, "RTM client connecting...")
                            }
                            RtmConstants.RtmConnectionState.RECONNECTING -> {
                                Log.d(TAG, "RTM client reconnecting...")
                            }
                            RtmConstants.RtmConnectionState.FAILED -> {
                                Log.e(TAG, "RTM client connection failed, reason: ${reason}")
                                isLoggedIn = false
                            }
                            else -> {
                                Log.d(TAG, "RTM link state: ${state}")
                            }
                        }
                    }
                    
                    override fun onMessageEvent(event:MessageEvent ) {
                        // 点对点消息（如果需要）
                        Log.d(TAG, "Received peer message from ${event.publisherId}: ${event.message.data}")
                        (event.message.data as? String)?.let { messageData ->
                            rtmMessageHandler?.handleMessage(messageData)
                        }
                    }

                })
                .build()
            
            // 创建 RTM 客户端
            rtmClient = RtmClient.create(rtmConfig)
            
            // 初始化消息处理器
            rtmMessageHandler = RTMMessageHandler(context)
            rtmMessageHandler?.initialize()
            
            Log.d(TAG, "RTM client initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RTM client", e)
            false
        }
    }
    
    /**
     * 登录 RTM
     * @param token RTM Token（可选，如果使用 App ID 认证可以为空字符串）
     * @param callback 登录结果回调
     */
    fun login(token: String = "", callback: ((Boolean, String?) -> Unit)? = null) {
        if (rtmClient == null) {
            Log.e(TAG, "RTM client not initialized")
            callback?.invoke(false, "RTM client not initialized")
            return
        }
        
        if (isLoggedIn) {
            Log.w(TAG, "Already logged in")
            callback?.invoke(true, null)
            return
        }
        
        try {
            rtmClient?.login(token, object : ResultCallback<Void> {
                override fun onSuccess(responseInfo: Void?) {
                    Log.d(TAG, "RTM login successful")
                    isLoggedIn = true
                    handler.post {
                        callback?.invoke(true, null)
                    }
                }
                
                override fun onFailure(errorInfo: ErrorInfo) {
                    Log.e(TAG, "RTM login failed: ${errorInfo.errorCode} - ${errorInfo.errorReason}")
                    isLoggedIn = false
                    handler.post {
                        callback?.invoke(false, errorInfo.errorReason)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error during RTM login", e)
            handler.post {
                callback?.invoke(false, e.message)
            }
        }
    }

    /**
     * 加入 RTM 通道（使用与 RTC 相同的 channelId）
     * @param channelId 通道 ID（与 RTC 通道相同）
     * @param callback 加入结果回调
     */
    fun joinChannel(channelId: String, callback: ((Boolean, String?) -> Unit)? = null) {
        val options = SubscribeOptions()
        options.setWithMessage(true)
        options.setWithPresence(true)
        options.setWithMetadata(false)
        options.setWithLock(false)
        options.setBeQuiet(false)
        rtmClient!!.subscribe(channelId, options, object:ResultCallback<Void>{
            override fun onSuccess(responseInfo: Void) {
                Log.d(TAG, "subscribe channel success")
            }

            override fun onFailure(errorInfo: ErrorInfo) {
                Log.d(TAG, errorInfo.toString())
            }
        })


    }
    
    /**
     * 离开 RTM 通道
     */

    
    /**
     * 登出 RTM
     */
    fun leaveChannel() {
        rtmClient!!.unsubscribe("channel_name", object : ResultCallback<Void>{
            public override fun onSuccess(responseInfo: Void) {
                Log.d(TAG, "unsubscribe channel success")
            }

            public override fun onFailure(errorInfo: ErrorInfo) {
                Log.d(TAG, errorInfo.toString())
            }
        })
    }
    
    /**
     * 检查是否已登录
     */
    fun isLoggedIn(): Boolean = isLoggedIn
    
    /**
     * 检查是否已加入通道
     */
    fun isJoinedChannel(): Boolean = isJoinedChannel
    
    /**
     * 获取当前通道 ID
     */
    fun getCurrentChannelId(): String? = currentChannelId
    
    /**
     * 释放 RTM 资源
     */
    fun logout()
    {
        rtmClient?.logout(object:ResultCallback<Void>{
            public override fun onSuccess(responseInfo: Void) {
                Log.d(TAG, "unsubscribe channel success")
            }

            public override fun onFailure(errorInfo: ErrorInfo) {
                Log.d(TAG, errorInfo.toString())
            }
        })
    }
    fun release() {
        logout()
        leaveChannel()
        rtmMessageHandler?.release()
        rtmMessageHandler = null
        try {
            rtmClient = null
            Log.d(TAG, "RTM client released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing RTM client", e)
        }
    }
    
    /**
     * 自动初始化和加入通道（使用 PreferenceManager 中的 channelId 和 deviceId）
     * @param context Context 实例
     * @param callback 完成回调（成功或失败）
     */
    fun autoInitializeAndJoin(context: Context, callback: ((Boolean, String?) -> Unit)? = null) {
        val preferenceManager = PreferenceManager(context)
        val channelId = preferenceManager.getChannelId()
        val deviceId = preferenceManager.getDeviceId()
        
        if (channelId.isEmpty()) {
            Log.e(TAG, "Channel ID is empty, cannot join RTM channel")
            callback?.invoke(false, "Channel ID is empty")
            return
        }
        
        if (deviceId.isEmpty()) {
            Log.e(TAG, "Device ID is empty, cannot login RTM")
            callback?.invoke(false, "Device ID is empty")
            return
        }
        
        // 初始化（使用设备 ID 作为用户 ID）
        if (!initialize(context, deviceId)) {
            callback?.invoke(false, "Failed to initialize RTM client")
            return
        }
        
        // 登录（使用空字符串作为 token，如果未开启 Token 鉴权）
        login("") { loginSuccess, loginError ->
            if (!loginSuccess) {
                callback?.invoke(false, loginError ?: "Login failed")
                return@login
            }
            
            // 加入通道
            joinChannel(channelId) { joinSuccess, joinError ->
                callback?.invoke(joinSuccess, joinError)
            }
        }
    }
}
