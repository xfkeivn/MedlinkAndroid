package com.bsci.medlink.ui.videocall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bsci.medlink.MainApplication
import com.bsci.medlink.R
import com.bsci.medlink.databinding.ActivityVideoCallBinding
import com.bsci.medlink.utils.AgoraManager
import com.bsci.medlink.utils.CommonUtil
import com.bsci.medlink.utils.PermissonUtils
import com.bsci.medlink.utils.SerialPortManager
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import io.agora.base.NV21Buffer
import io.agora.base.VideoFrame
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import com.jiangdg.ausbc.render.env.RotateType
import java.text.SimpleDateFormat
import java.util.*

class VideoCallActivity : CameraActivity() {
    private lateinit var binding: ActivityVideoCallBinding
    private var isMicrophoneMuted = false
    private var isRemoteControlEnabled = false
    private var callStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private val TAG = "VideoCallActivity"
    
    private var engine: RtcEngine? = null
    private var myUid = 0
    private var joined = false
    private var channelId: String = ""
    private var remoteUid: Int = 0 // 远程用户ID
    
    // 获取 AgoraManager 实例
    private val agoraManager: AgoraManager by lazy {
        (application as MainApplication).agoraManager
    }
    
    // USB 串口管理器
    private var serialPortManager: SerialPortManager? = null
    
    private val previewDataCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(
            data: ByteArray?,
            width: Int,
            height: Int,
            format: IPreviewDataCallBack.DataFormat
        ) {
            if (!joined || data == null) return
            
            if (format == IPreviewDataCallBack.DataFormat.NV21) {
                pushVideoFrameByNV21(data, width, height)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 设置为横屏
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        super.onCreate(savedInstanceState)
        
        channelId = intent.getStringExtra("channel_id") ?: "default_channel"
        isMicrophoneMuted = !intent.getBooleanExtra("microphone_enabled", true)
        isRemoteControlEnabled = intent.getBooleanExtra("remote_control_enabled", false)

        callStartTime = System.currentTimeMillis()
        startTimer()

        setupControls()
        initAgoraEngine()
        initSerialPort()
        checkAndRequestPermissions()
    }

    override fun getRootView(layoutInflater: LayoutInflater): View? {
        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        return binding.root
    }
    
    override fun initData() {
        super.initData()
        // 初始化数据
    }

    override fun getCameraView(): IAspectRatio? {
        // 使用 AspectRatioTextureView 来显示 USB 摄像头预览
        val textureView = AspectRatioTextureView(this)
        // 将 textureView 添加到左侧视频显示区域
        binding.surfaceVideo.parent?.let { parent ->
            if (parent is android.view.ViewGroup) {
                parent.removeView(binding.surfaceVideo)
            }
        }
        // 隐藏占位 TextView
        binding.root.findViewById<View>(R.id.placeholder_text)?.visibility = View.GONE
        return textureView
    }

    override fun getCameraViewContainer(): android.view.ViewGroup? {
        // 返回左侧视频显示区域的 FrameLayout
        return binding.root.findViewById(R.id.video_container)
    }

    override fun getGravity(): Int {
        return Gravity.CENTER
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_SYS_MIC)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(true)  // Enable raw preview data for callback
            .create()
    }

    override fun initView() {
        super.initView()
        // 检查摄像头权限
        checkCameraPermission()
        
        // 初始化远程视频视图
        setupRemoteVideoView()
    }
    
    private fun setupRemoteVideoView() {
        // 远程视频视图会在用户加入时自动设置
        binding.tvRemoteVideoPlaceholder.visibility = View.VISIBLE
    }

    private fun setupControls() {
        // 麦克风静音切换
        binding.btnMicrophone.setOnClickListener {
            isMicrophoneMuted = !isMicrophoneMuted
            engine?.muteLocalAudioStream(isMicrophoneMuted)
            updateMicrophoneButton()
            Toast.makeText(this, if (isMicrophoneMuted) "麦克风已静音" else "麦克风已开启", Toast.LENGTH_SHORT).show()
        }

        // 结束会议
        binding.btnEndCall.setOnClickListener {
            showExitCallDialog()
        }

        // 远程控制使能切换
        binding.btnRemoteControl.setOnClickListener {
            isRemoteControlEnabled = !isRemoteControlEnabled
            if (isRemoteControlEnabled) {
                // 启用远程控制，尝试连接串口设备
                connectSerialPort()
            } else {
                // 禁用远程控制，断开串口连接
                disconnectSerialPort()
            }
            updateRemoteControlButton()
            Toast.makeText(this, if (isRemoteControlEnabled) "远程控制已启用" else "远程控制已禁用", Toast.LENGTH_SHORT).show()
        }

        updateMicrophoneButton()
        updateRemoteControlButton()
    }

    private fun initAgoraEngine() {
        // 使用 AgoraManager 获取或创建 RtcEngine 实例
        engine = agoraManager.getOrCreateEngine(iRtcEngineEventHandler)
        if (engine == null) {
            showAlert("初始化 Agora 引擎失败")
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = PermissonUtils.getCommonPermission()
        if (PermissonUtils.checkPermissions(this, permissions)) {
            joinChannel()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            joinChannel()
        } else {
            showAlert("需要摄像头和麦克风权限才能进行视频通话")
        }
    }
    
    private fun joinChannel() {
        engine?.setDefaultAudioRoutetoSpeakerphone(true)
        engine?.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
        engine?.enableVideo()
        
        val globalSettings = (application as? MainApplication)?.globalSettings
        engine?.setVideoEncoderConfiguration(
            VideoEncoderConfiguration(
                globalSettings?.videoEncodingDimensionObject,
                VideoEncoderConfiguration.FRAME_RATE.valueOf(
                    globalSettings?.videoEncodingFrameRate ?: "FRAME_RATE_FPS_15"
                ),
                VideoEncoderConfiguration.STANDARD_BITRATE,
                VideoEncoderConfiguration.ORIENTATION_MODE.valueOf(
                    globalSettings?.videoEncodingOrientation ?: "ORIENTATION_MODE_ADAPTIVE"
                )
            )
        )
        
        // Configure external video source
        engine?.setExternalVideoSource(
            true,
            false,  // useTexture = false, we use NV21 data
            Constants.ExternalVideoSourceType.VIDEO_FRAME
        )
        
        // 设置麦克风状态
        engine?.muteLocalAudioStream(isMicrophoneMuted)
        
        val option = ChannelMediaOptions()
        option.autoSubscribeAudio = true
        option.autoSubscribeVideo = true
        option.publishCustomVideoTrack = true
        option.publishMicrophoneTrack = !isMicrophoneMuted
        option.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
        
        val res = engine?.joinChannel(null, channelId, 0, option) ?: -1
        if (res != 0) {
            handler.post {
                showAlert(RtcEngine.getErrorDescription(Math.abs(res)))
            }
        }
    }

    private fun checkCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "CAMERA permission already granted")
                }
                else -> {
                    Log.d(TAG, "Requesting CAMERA permission")
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "CAMERA permission granted")
        } else {
            Log.e(TAG, "CAMERA permission denied")
            showToast("需要摄像头权限才能显示 USB 摄像头")
        }
    }



    /**
     * 显示退出会议确认对话框
     */
    private fun showExitCallDialog() {
        AlertDialog.Builder(this)
            .setTitle("退出会议")
            .setMessage("确定要退出会议吗？")
            .setPositiveButton("确定") { _, _ ->
                exitCall()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 退出会议（离开频道并返回）
     */
    private fun exitCall() {
        // 移除预览数据回调
        removePreviewDataCallBack(previewDataCallback)
        joined = false
        
        // 离开频道
        engine?.leaveChannel()
        engine?.stopPreview()
        
        // 返回 MeetingPrepareActivity
        finish()
    }
    
    override fun onBackPressed() {
        // 拦截返回键，提示用户是否退出会议
        showExitCallDialog()
    }

    private fun leaveChannel() {
        if (!joined) return
        
        joined = false
        // Remove preview data callback
        removePreviewDataCallBack(previewDataCallback)
        
        engine?.leaveChannel()
        engine?.stopPreview()
    }

    private fun pushVideoFrameByNV21(nv21: ByteArray, width: Int, height: Int) {
        try {
            val frameBuffer = NV21Buffer(nv21, width, height, null)
            
            val currentMonotonicTimeInMs = engine?.currentMonotonicTimeInMs ?: System.currentTimeMillis()
            val videoFrame = VideoFrame(frameBuffer, 0, currentMonotonicTimeInMs * 1000000)
            
            val success = engine?.pushExternalVideoFrame(videoFrame) ?: false
            if (!success) {
                Log.w(TAG, "pushExternalVideoFrame error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing video frame", e)
        }
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.d(TAG, "Camera opened: ${self.getUsbDevice().deviceName}")
                handler.post {
                    // 隐藏占位文本
                    binding.root.findViewById<View>(R.id.placeholder_text)?.visibility = View.GONE
                }
                // Add preview data callback when camera is opened
                if (joined) {
                    addPreviewDataCallBack(previewDataCallback)
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.d(TAG, "Camera closed: ${self.getUsbDevice().deviceName}")
                // Remove preview data callback when camera is closed
                removePreviewDataCallBack(previewDataCallback)
            }
            ICameraStateCallBack.State.ERROR -> {
                val errorMsg = msg ?: "Unknown error"
                Log.e(TAG, "Camera error: ${self.getUsbDevice().deviceName}, $errorMsg")
                handler.post {
                    showToast("摄像头错误: $errorMsg")
                }
            }
        }
    }

    private fun updateMicrophoneButton() {
        if (isMicrophoneMuted) {
            binding.btnMicrophone.setImageResource(R.drawable.ic_mic_off)
            binding.btnMicrophone.alpha = 0.7f
        } else {
            binding.btnMicrophone.setImageResource(R.drawable.ic_mic_on)
            binding.btnMicrophone.alpha = 1.0f
        }
    }

    private fun updateRemoteControlButton() {
        if (isRemoteControlEnabled) {
            binding.btnRemoteControl.setImageResource(R.drawable.ic_remote_control_on)
            binding.btnRemoteControl.alpha = 1.0f
        } else {
            binding.btnRemoteControl.setImageResource(R.drawable.ic_remote_control_off)
            binding.btnRemoteControl.alpha = 0.7f
        }
    }

    private fun startTimer() {
        handler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - callStartTime
                val seconds = (elapsed / 1000).toInt()
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60
                binding.tvCallTime.text = String.format("%02d:%02d:%02d", hours, minutes, secs)
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showAlert(message: String) {
        AlertDialog.Builder(this)
            .setTitle("错误")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private val iRtcEngineEventHandler = object : IRtcEngineEventHandler() {
        override fun onError(err: Int) {
            Log.w(TAG, String.format("onError code %d message %s", err, RtcEngine.getErrorDescription(err)))
        }

        override fun onLeaveChannel(stats: RtcStats) {
            super.onLeaveChannel(stats)
            Log.i(TAG, String.format("local user %d leaveChannel!", myUid))
            handler.post {
                showToast("离开频道")
                // 清理远程视频视图
                clearRemoteVideoView()
            }
        }

        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.i(TAG, String.format("onJoinChannelSuccess channel %s uid %d", channel, uid))
            myUid = uid
            // joined 状态已在 configureVideoCall 中设置
            handler.post {
                showToast("加入频道成功")
            }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.i(TAG, String.format("onUserJoined uid %d", uid))
            remoteUid = uid
            handler.post {
                setupRemoteVideo(uid)
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.i(TAG, String.format("onUserOffline uid %d reason %d", uid, reason))
            if (uid == remoteUid) {
                handler.post {
                    clearRemoteVideoView()
                    remoteUid = 0
                }
            }
        }

        override fun onFirstRemoteVideoDecoded(uid: Int, width: Int, height: Int, elapsed: Int) {
            Log.i(TAG, String.format("onFirstRemoteVideoDecoded uid %d width %d height %d", uid, width, height))
            handler.post {
                binding.tvRemoteVideoPlaceholder.visibility = View.GONE
            }
        }
    }
    
    private fun setupRemoteVideo(uid: Int) {
        try {
            // 设置远程视频视图
            val remoteVideoView = binding.remoteVideoView
            val videoCanvas = VideoCanvas(remoteVideoView)
            videoCanvas.uid = uid
            videoCanvas.renderMode = Constants.RENDER_MODE_FIT
            engine?.setupRemoteVideo(videoCanvas)
            
            Log.d(TAG, "Setup remote video for uid: $uid")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up remote video", e)
        }
    }
    
    private fun clearRemoteVideoView() {
        try {
            if (remoteUid != 0) {
                val videoCanvas = VideoCanvas(null)
                videoCanvas.uid = remoteUid
                engine?.setupRemoteVideo(videoCanvas)
            }
            binding.tvRemoteVideoPlaceholder.visibility = View.VISIBLE
            remoteUid = 0
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing remote video", e)
        }
    }

    /**
     * 初始化串口管理器
     */
    private fun initSerialPort() {
        serialPortManager = SerialPortManager(this).apply {
            setOnConnectionStateListener { connected ->
                handler.post {
                    if (connected) {
                        Log.d(TAG, "串口设备连接成功")
                        showToast("串口设备已连接")
                    } else {
                        Log.d(TAG, "串口设备断开连接")
                        if (isRemoteControlEnabled) {
                            showToast("串口设备断开，远程控制已禁用")
                            isRemoteControlEnabled = false
                            updateRemoteControlButton()
                        }
                    }
                }
            }
            
            setOnDataReceivedListener { data ->
                // 处理接收到的串口数据
                val message = String(data, Charsets.UTF_8)
                Log.d(TAG, "收到串口数据: $message")
                handler.post {
                    // 可以在这里处理接收到的控制反馈
                }
            }
        }
    }
    
    /**
     * 连接串口设备
     */
    private fun connectSerialPort() {
        serialPortManager?.let { manager ->
            if (manager.isPortOpen()) {
                Log.d(TAG, "串口已连接")
                return
            }
            
            // 尝试自动连接第一个可用的串口设备
            val connected = manager.autoConnect()
            if (!connected) {
                // 如果没有自动连接成功，可能是需要用户授权
                Log.d(TAG, "等待用户授权 USB 权限")
                showToast("请授权 USB 串口设备权限")
            }
        } ?: run {
            Log.e(TAG, "串口管理器未初始化")
            showToast("串口管理器初始化失败")
        }
    }
    
    /**
     * 断开串口设备
     */
    private fun disconnectSerialPort() {
        serialPortManager?.closeSerialPort()
    }
    
    /**
     * 通过串口发送控制命令
     * @param command 控制命令字符串
     * @return 是否发送成功
     */
    fun sendControlCommand(command: String): Boolean {
        return if (isRemoteControlEnabled && serialPortManager?.isPortOpen() == true) {
            val success = serialPortManager?.sendString(command) ?: false
            if (success) {
                Log.d(TAG, "发送控制命令成功: $command")
            } else {
                Log.e(TAG, "发送控制命令失败: $command")
            }
            success
        } else {
            Log.w(TAG, "远程控制未启用或串口未连接，无法发送命令: $command")
            false
        }
    }
    
    /**
     * 通过串口发送控制命令（字节数组）
     * @param data 控制命令字节数组
     * @return 是否发送成功
     */
    fun sendControlCommand(data: ByteArray): Boolean {
        return if (isRemoteControlEnabled && serialPortManager?.isPortOpen() == true) {
            val success = serialPortManager?.sendData(data) ?: false
            if (success) {
                Log.d(TAG, "发送控制命令成功: ${data.size} bytes")
            } else {
                Log.e(TAG, "发送控制命令失败: ${data.size} bytes")
            }
            success
        } else {
            Log.w(TAG, "远程控制未启用或串口未连接，无法发送命令")
            false
        }
    }

    override fun onDestroy() {
        leaveChannel()
        clearRemoteVideoView()
        disconnectSerialPort()
        serialPortManager?.release()
        serialPortManager = null
        engine?.leaveChannel()
        engine?.stopPreview()
        // 注意：不在这里释放 engine，因为可能被 MeetingPrepareActivity 使用
        // 只在应用退出时由 MainApplication 统一释放
        engine = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
