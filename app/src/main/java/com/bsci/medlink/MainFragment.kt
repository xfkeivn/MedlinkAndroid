package com.bsci.medlink

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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
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
import io.agora.rtc2.video.VideoEncoderConfiguration
import com.jiangdg.ausbc.render.env.RotateType
import kotlin.collections.all
import kotlin.let
import kotlin.text.format
import com.bsci.medlink.utils.CommonUtil
import com.bsci.medlink.utils.PermissonUtils
/**
 * USB Camera Fragment - Display USB camera video and push to Agora SDK
 */

class MainFragment : CameraFragment(), ICameraStateCallBack {

    private val TAG = "USBCamera"
    private var tvCameraTip: TextView? = null
    private var btnJoin: Button? = null
    private var etChannel: EditText? = null
    private var engine: RtcEngine? = null
    private var myUid = 0
    private var joined = false
    private val handler = Handler(Looper.getMainLooper())

    // Permission launcher for CAMERA permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "CAMERA permission granted")
        } else {
            Log.e(TAG, "CAMERA permission denied")
            showToast("Camera permission is required to display USB camera")
        }
    }

    override fun getRootView(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): View? {
        val rootView = inflater.inflate(
            R.layout.main_fragment,
            container,
            false
        )
        tvCameraTip = rootView?.findViewById(R.id.tv_camera_tip)
        btnJoin = rootView?.findViewById(R.id.btn_join)
        etChannel = rootView?.findViewById(R.id.et_channel)
        btnJoin?.setOnClickListener {
            if (!joined) {
                CommonUtil.hideInputBoard(activity, etChannel)
                val channelId = etChannel?.text?.toString() ?: ""
                checkAndRequestPermissions(channelId)
            } else {
                leaveChannel()
            }
        }
        return rootView
    }

    override fun getCameraView(): IAspectRatio? {
        // Return AspectRatioTextureView to display camera preview
        return AspectRatioTextureView(requireContext())
    }

    override fun getCameraViewContainer(): ViewGroup? {
        // Return the container for camera view
        return getRootView()?.findViewById(R.id.camera_view_container)
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
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(true)  // Enable raw preview data for callback
            .create()
    }

    override fun initView() {
        super.initView()
        // Check and request CAMERA permission if needed
        checkCameraPermission()
    }

    override fun onActivityCreated(@Nullable savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        initAgoraEngine()
    }

    private fun initAgoraEngine() {
        val context = context ?: return
        try {
            val config = RtcEngineConfig()
            config.mContext = context.applicationContext
            config.mAppId = getString(R.string.agora_app_id)
            config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            config.mEventHandler = iRtcEngineEventHandler
            config.mAudioScenario = Constants.AudioScenario.getValue(Constants.AudioScenario.DEFAULT)
            (activity?.application as? MainApplication)?.globalSettings?.areaCode?.let {
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
                (activity?.application as? MainApplication)?.globalSettings?.privateCloudConfig
            if (localAccessPointConfiguration != null) {
                engine?.setLocalAccessPoint(localAccessPointConfiguration)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            activity?.onBackPressed()
        }
    }

    override fun onDestroy() {
        leaveChannel()
        engine?.leaveChannel()
        engine?.stopPreview()
        engine = null
        handler.post {
            RtcEngine.destroy()
        }
        super.onDestroy()
    }

    private fun joinChannel(channelId: String) {
        val context = context ?: return
        
        engine?.setDefaultAudioRoutetoSpeakerphone(true)
        engine?.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
        engine?.enableVideo()
        
        val globalSettings = (activity?.application as? MainApplication)?.globalSettings
        engine?.setVideoEncoderConfiguration(
            VideoEncoderConfiguration(
                globalSettings?.videoEncodingDimensionObject,
                VideoEncoderConfiguration.FRAME_RATE.valueOf(
                    globalSettings?.videoEncodingFrameRate ?: "FRAME_RATE_FPS_15"
                ),
                VideoEncoderConfiguration.STANDARD_BITRATE,
                VideoEncoderConfiguration.ORIENTATION_MODE.valueOf(
                    globalSettings?.videoEncodingOrientation ?: "ORIENTATION_MODE_FIXED_PORTRAIT"
                )
            )
        )
        
        // Configure external video source
        engine?.setExternalVideoSource(
            true,
            false,  // useTexture = false, we use NV21 data
            Constants.ExternalVideoSourceType.VIDEO_FRAME
        )
        

            val option = ChannelMediaOptions()
            option.autoSubscribeAudio = true
            option.autoSubscribeVideo = true
            option.publishCustomVideoTrack = true
            val res = engine?.joinChannel(null, channelId, 0, option) ?: -1
            if (res != 0) {
                handler.post {
                    showAlert(RtcEngine.getErrorDescription(Math.abs(res)))
                }

            }
            handler.post {
                btnJoin?.isEnabled = true
                btnJoin?.text = getString(R.string.leave)
            }
        }


    private fun leaveChannel() {
        if (!joined) return
        
        joined = false
        handler.post {
            btnJoin?.text = getString(R.string.join)
        }
        
        // Remove preview data callback
        removePreviewDataCallBack(previewDataCallback)
        
        engine?.leaveChannel()
        engine?.stopPreview()
    }

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

    private fun checkCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            pendingChannelId?.let { joinChannel(it) }
        }
        pendingChannelId = null
    }

    private var pendingChannelId: String? = "12345" // 测试阶段默认频道ID

    private fun checkAndRequestPermissions(channelId: String) {
        val permissions = PermissonUtils.getCommonPermission()
        if (PermissonUtils.checkPermissions(requireContext(), permissions)) {
            joinChannel(channelId)
        } else {
            pendingChannelId = channelId
            permissionLauncher.launch(permissions)
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
                activity?.runOnUiThread {
                    tvCameraTip?.visibility = View.GONE
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
                activity?.runOnUiThread {
                    showToast("Camera error: $errorMsg")
                }
            }
        }
    }

    private fun showToast(message: String) {
        context?.let {
            Toast.makeText(it, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAlert(message: String) {
        activity?.let {
            AlertDialog.Builder(it)
                .setTitle("Error")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private val iRtcEngineEventHandler = object : IRtcEngineEventHandler() {
        override fun onError(err: Int) {
            Log.w(TAG, String.format("onError code %d message %s", err, RtcEngine.getErrorDescription(err)))
        }

        override fun onLeaveChannel(stats: RtcStats) {
            super.onLeaveChannel(stats)
            Log.i(TAG, String.format("local user %d leaveChannel!", myUid))
            handler.post {
                showToast("Leave channel")
            }
        }

        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.i(TAG, String.format("onJoinChannelSuccess channel %s uid %d", channel, uid))
            myUid = uid
            joined = true
            handler.post {
                btnJoin?.isEnabled = true
                btnJoin?.text = getString(R.string.leave)
                showToast("Join channel success")
                // Add preview data callback after joining channel successfully
                // If camera is already opened, the callback will be added
                // Otherwise, it will be added in onCameraState when camera opens
                addPreviewDataCallBack(previewDataCallback)
            }
        }
    }
}