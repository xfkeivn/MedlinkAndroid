package com.bsci.medlink.ui.meeting

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import com.bsci.medlink.R
import android.app.AlertDialog
import com.bsci.medlink.MainApplication
import com.bsci.medlink.data.local.PreferenceManager
import com.bsci.medlink.data.model.Client
import com.bsci.medlink.data.remote.ClientService
import com.bsci.medlink.data.remote.HostRegistrationService
import com.bsci.medlink.databinding.ActivityMeetingPrepareBinding
import com.bsci.medlink.ui.adapter.ClientListAdapter
import com.bsci.medlink.ui.videocall.VideoCallActivity
import com.bsci.medlink.utils.AgoraManager
import com.bsci.medlink.utils.DeviceUuidFactory
import com.bsci.medlink.utils.SerialPortManager
import com.bsci.medlink.utils.HIDCommandBuilder
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.widget.IAspectRatio
import android.hardware.usb.UsbDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
class MeetingPrepareActivity : CameraActivity() {
    private lateinit var binding: ActivityMeetingPrepareBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var clientAdapter: ClientListAdapter
    private val clientService = ClientService()
    private val registrationService = HostRegistrationService()
    private lateinit var uuidFactory: DeviceUuidFactory
    private var selectedClient: Client? = null
    private var isUsbCameraOk = false  // USB摄像头是否已连接
    private var isUsbCameraEnabled = true  // USB摄像头是否启用（默认启用）
    private var isMicrophoneEnabled = true  // 默认开启
    private var isRemoteControlEnabled = false
    private var serialPortManager: SerialPortManager? = null
    private var isSerialPortAvailable = false
    
    // Agora RTC Engine（仅用于监听频道用户变化）
    private var agoraEngine: RtcEngine? = null
    private var isAgoraJoined = false
    private val onlineUserIds = mutableSetOf<String>() // 存储在线用户的ID
    
    override fun getRootView(layoutInflater: LayoutInflater): View? {
        binding = ActivityMeetingPrepareBinding.inflate(layoutInflater)
        return binding.root
    }
    
    override fun initData() {
        super.initData()

        // preferenceManager 已在 initView 中初始化，这里只需要确保已初始化
        if (!::preferenceManager.isInitialized) {
            preferenceManager = PreferenceManager(this)
        }
        uuidFactory = DeviceUuidFactory(this)

        // 初始化 Agora Engine 用于监听频道用户变化
        initAgoraEngineForPresence()
        
        // 检查设备是否可用（后台开关控制）
        checkDeviceEnabled()
    }

    private fun checkDeviceEnabled() {
        val serverIp = preferenceManager.getServerIp()
        val deviceUuid = uuidFactory.getDeviceUuid()

        if (serverIp.isEmpty() || deviceUuid.isEmpty()) {
            // 系统错误：缺少必要的配置信息
            val errorMsg = when {
                serverIp.isEmpty() && deviceUuid.isEmpty() -> "系统错误：缺少服务器IP和设备UUID"
                serverIp.isEmpty() -> "系统错误：缺少服务器IP"
                else -> "系统错误：缺少设备UUID"
            }
            Log.e("MeetingPrepareActivity", errorMsg)
            showSystemErrorDialog(errorMsg)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = registrationService.checkDeviceEnabled(serverIp, deviceUuid)
                withContext(Dispatchers.Main) {
                    if (response.success && response.isEnabled) {
                        // 设备可用，初始化界面
                        initializeUI()
                    } else {
                        // 设备被禁用，显示提示并退出
                        showDeviceDisabledDialog(response.message ?: "设备已被禁用")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // 检查失败，允许继续使用（避免网络问题导致无法使用）
                    initializeUI()
                }
            }
        }
    }

    override fun initView() {
        super.initView()
        // 在 initView 中初始化 preferenceManager，因为 initView 在 initData 之前被调用
        if (!::preferenceManager.isInitialized) {
            preferenceManager = PreferenceManager(this)
        }
        initializeUI()
    }
    
    private fun initializeUI() {
        setupDeviceInfo()
        setupClientList()
        initSerialPort()
        setupDeviceStatus()
        setupExitButton()
        setupBackPressHandler()
        
        // 检查当前已连接的USB摄像头
        checkUsbCameraStatus()
    }
    
    /**
     * 获取摄像头视图（返回 null，因为不需要预览，只需要检测设备）
     */
    override fun getCameraView(): IAspectRatio? {
        return null  // 返回 null 使用 offscreen render 模式，只检测设备不显示预览
    }
    
    /**
     * 获取摄像头视图容器（返回 null，因为不需要预览）
     */
    override fun getCameraViewContainer(): ViewGroup? {
        return null
    }
    
    /**
     * 设置设置按钮
     */

    
    /**
     * 设置退出按钮
     */
    private fun setupExitButton() {
        binding.btnExit.setOnClickListener {
            showExitAppDialog()
        }
    }
    
    /**
     * 设置返回键处理
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 按返回键时显示退出确认对话框
                showExitAppDialog()
            }
        })
    }
    
    /**
     * 显示退出程序确认对话框
     */
    private fun showExitAppDialog() {
        AlertDialog.Builder(this)
            .setTitle("退出程序")
            .setMessage("确定要退出程序吗？")
            .setPositiveButton("确定") { _, _ ->
                exitApp()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 退出程序
     */
    private fun exitApp() {
        // 离开频道
        if (isAgoraJoined) {
            agoraEngine?.leaveChannel()
            isAgoraJoined = false
        }
        
        // 释放资源
        agoraEngine?.stopPreview()
        agoraEngine = null
        serialPortManager?.release()
        serialPortManager = null
        
        // 退出应用
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun showDeviceDisabledDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("设备已禁用")
            .setMessage(message)
            .setPositiveButton("确定") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showSystemErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("系统错误")
            .setMessage(message)
            .setPositiveButton("确定") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupDeviceInfo() {
        val channelId = preferenceManager.getChannelId()
        val hospital = preferenceManager.getHospital()
        val department = preferenceManager.getDepartment()
        val equipment = preferenceManager.getEquipment()

        // 在客户端列表上方显示详细信息
        setupDeviceInfoPanel(channelId, hospital, department, equipment)
    }
    
    private fun setupDeviceInfoPanel(channelId: String, hospital: String, department: String, equipment: String) {
        // 显示会议号
        binding.tvDeviceId.text = if (channelId.isNotEmpty()) channelId else "未设置"
        
        // 显示医院
        binding.tvHospitalValue.text = if (hospital.isNotEmpty()) hospital else "未设置"
        
        // 显示科室
        binding.tvDepartmentValue.text = if (department.isNotEmpty()) department else "未设置"
        
        // 显示设备
        binding.tvEquipmentValue.text = if (equipment.isNotEmpty()) equipment else "未设置"
    }

    private fun setupClientList() {
        // 初始化适配器（先显示空列表）
        clientAdapter = ClientListAdapter(emptyList()) { client ->
            selectedClient = client
            updateCallButtonState()
        }

        binding.rvClients.layoutManager = LinearLayoutManager(this)
        binding.rvClients.adapter = clientAdapter

        // 加载客户端列表（先显示缓存，后台刷新）
        loadClients()
    }
    
    /**
     * 加载客户端列表
     * 直接使用 SplashActivity 中已缓存的客户端列表
     * 如果缓存为空，才尝试从服务器加载（作为兜底）
     */
    private fun loadClients() {
        val cachedClients = (application as? MainApplication)?.cachedClients
        if (!cachedClients.isNullOrEmpty()) {
            // 使用缓存数据
            updateClientAdapter(cachedClients)
            
            // 加入 Agora 频道以监听用户在线状态变化
            joinAgoraChannelForPresence()
        } else {
            // 缓存为空时，才尝试从服务器加载（兜底逻辑）
            Log.w(TAG, "缓存为空，尝试从服务器加载客户端列表")
            refreshClientsFromServer()
        }
    }
    
    /**
     * 更新客户端适配器
     */
    private fun updateClientAdapter(clients: List<Client>) {
        val clientsWithStatus = updateClientsOnlineStatus(clients)
        clientAdapter = ClientListAdapter(clientsWithStatus) { client ->
            selectedClient = client
            updateCallButtonState()
        }
        binding.rvClients.adapter = clientAdapter
    }
    
    /**
     * 从服务器刷新客户端列表
     */
    private fun refreshClientsFromServer() {
        val ip = getServerIp()
        val uuid = getUuid()

        if (ip.isEmpty() || uuid.isEmpty()) {
            // 如果配置不完整，且缓存为空，使用模拟数据
            val cachedClients = (application as? MainApplication)?.cachedClients
            if (cachedClients.isNullOrEmpty()) {
                loadMockClients()
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val clients = clientService.getClients(ip, uuid)
                withContext(Dispatchers.Main) {
                    if (clients.isNotEmpty()) {
                        // 更新缓存
                        (application as? MainApplication)?.cachedClients = clients
                        
                        // 更新适配器
                        updateClientAdapter(clients)
                        
                        // 加入 Agora 频道以监听用户变化（如果还未加入）
                        if (!isAgoraJoined) {
                            joinAgoraChannelForPresence()
                        }
                    } else {
                        // 如果服务器返回空列表，但缓存中有数据，保持显示缓存数据
                        val cachedClients = (application as? MainApplication)?.cachedClients
                        if (cachedClients.isNullOrEmpty()) {
                            Toast.makeText(this@MeetingPrepareActivity, "未获取到客户端列表", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取客户端列表失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    // 如果获取失败，但缓存中有数据，继续使用缓存数据
                    val cachedClients = (application as? MainApplication)?.cachedClients
                    if (cachedClients.isNullOrEmpty()) {
                        Toast.makeText(this@MeetingPrepareActivity, "获取客户端列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        // 如果获取失败且缓存为空，使用模拟数据
                        loadMockClients()
                    }
                }
            }
        }
    }

    private fun loadMockClients() {
        // 模拟客户端数据（作为备用）
        val clients = listOf(
            Client("1", "客户端A", null, null, true),
            Client("2", "客户端B", null, null, true),
            Client("3", "客户端C", null, null, false),
            Client("4", "客户端D", null, null, true),
            Client("5", "客户端E", null, null, false)
        )
        
        // 更新缓存
        (application as? MainApplication)?.cachedClients = clients
        
        // 更新适配器
        updateClientAdapter(clients)
    }

    private fun getServerIp(): String {
        // 从 PreferenceManager 获取服务器 IP
        return preferenceManager.getServerIp()
    }

    private fun getUuid(): String {
        // 使用 DeviceUuidFactory 获取设备 UUID
        val uuidFactory = com.bsci.medlink.utils.DeviceUuidFactory(this)
        return uuidFactory.getDeviceUuid()
    }

    /**
     * 检查USB摄像头状态
     */
    private fun checkUsbCameraStatus() {
        val deviceList = getDeviceList()
        isUsbCameraOk = !deviceList.isNullOrEmpty()
        if (isUsbCameraOk && isUsbCameraEnabled) {
            // 如果有摄像头且已启用，保持启用状态
        } else if (!isUsbCameraOk) {
            // 如果没有摄像头，禁用状态
            isUsbCameraEnabled = false
        }
        updateUsbCameraIcon()
    }
    
    /**
     * ICameraStateCallBack 实现 - 摄像头状态变化回调
     */
    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.d(TAG, "USB摄像头已打开: $msg")
                runOnUiThread {
                    isUsbCameraOk = true
                    updateUsbCameraIcon()
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.d(TAG, "USB摄像头已关闭: $msg")
                runOnUiThread {
                    isUsbCameraOk = false
                    isUsbCameraEnabled = false
                    updateUsbCameraIcon()
                }
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "USB摄像头错误: $msg")
                runOnUiThread {
                    isUsbCameraOk = false
                    updateUsbCameraIcon()
                }
            }
        }
    }
    

    private fun setupDeviceStatus() {
        // USB 摄像头状态（可点击切换启用/禁用）
        binding.ivUsbCamera.setOnClickListener {
            if (!isUsbCameraOk) {
                // 如果没有USB摄像头，提示用户
                Toast.makeText(this, "未检测到USB摄像头", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 切换启用/禁用状态
            isUsbCameraEnabled = !isUsbCameraEnabled
            updateUsbCameraIcon()
            Toast.makeText(this, if (isUsbCameraEnabled) "USB摄像头已启用" else "USB摄像头已禁用", Toast.LENGTH_SHORT).show()
        }

        // 麦克风使能切换
        binding.ivMicrophone.setOnClickListener {
            isMicrophoneEnabled = !isMicrophoneEnabled
            updateMicrophoneIcon()
        }

        // 远程控制使能切换
        binding.ivRemoteControl.setOnClickListener {
            if (!isSerialPortAvailable) {
                // 如果串口设备不可用，尝试检测
                checkSerialPortAvailability()
                Toast.makeText(this, "正在检测串口设备...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isRemoteControlEnabled = !isRemoteControlEnabled
            updateRemoteControlIcon()
            
            if (isRemoteControlEnabled) {
                Toast.makeText(this, "远程控制已启用", Toast.LENGTH_SHORT).show()
                // 执行从左到右的鼠标移动测试
                performMouseMoveTest()
            } else {
                Toast.makeText(this, "远程控制已禁用", Toast.LENGTH_SHORT).show()
            }
        }

        // 通话按钮
        binding.btnCall.setOnClickListener {
            if (selectedClient != null && selectedClient!!.isOnline) {
                startVideoCall()
            } else {
                Toast.makeText(this, "请选择一个在线的客户端", Toast.LENGTH_SHORT).show()
            }
        }

        updateDeviceStatusIcons()
    }

    private fun updateUsbCameraIcon() {
        if (!isUsbCameraOk) {
            // 没有USB摄像头，显示灰色禁用图标
            binding.ivUsbCamera.setImageResource(R.drawable.ic_camera_off)
            binding.ivUsbCamera.alpha = 0.5f
        } else if (isUsbCameraEnabled) {
            // 有USB摄像头且已启用，显示正常图标
            binding.ivUsbCamera.setImageResource(R.drawable.ic_camera_on)
            binding.ivUsbCamera.alpha = 1.0f
        } else {
            // 有USB摄像头但已禁用，显示带对角线的灰色图标
            binding.ivUsbCamera.setImageResource(R.drawable.ic_camera_disabled)
            binding.ivUsbCamera.alpha = 1.0f
        }
    }

    private fun updateMicrophoneIcon() {
        if (isMicrophoneEnabled) {
            binding.ivMicrophone.setImageResource(R.drawable.ic_mic_on)
            binding.ivMicrophone.alpha = 1.0f
        } else {
            binding.ivMicrophone.setImageResource(R.drawable.ic_mic_off)
            binding.ivMicrophone.alpha = 0.5f
        }
    }

    private fun updateRemoteControlIcon() {
        if (!isSerialPortAvailable) {
            // 串口设备不可用，显示灰色禁用图标
            binding.ivRemoteControl.setImageResource(R.drawable.ic_remote_control_off)
            binding.ivRemoteControl.alpha = 0.5f
        } else if (isRemoteControlEnabled) {
            // 串口设备可用且已启用，显示开启图标
            binding.ivRemoteControl.setImageResource(R.drawable.ic_remote_control_on)
            binding.ivRemoteControl.alpha = 1.0f
        } else {
            // 串口设备可用但未启用，显示关闭图标
            binding.ivRemoteControl.setImageResource(R.drawable.ic_remote_control_off)
            binding.ivRemoteControl.alpha = 1.0f
        }
    }

    private fun updateDeviceStatusIcons() {
        updateUsbCameraIcon()
        updateMicrophoneIcon()
        updateRemoteControlIcon()
    }

    private fun updateCallButtonState() {
        val canCall = selectedClient != null && selectedClient!!.isOnline
        binding.btnCall.isEnabled = canCall
        binding.btnCall.alpha = if (canCall) 1.0f else 0.5f
    }
    
    /**
     * 初始化串口管理器
     */
    private fun initSerialPort() {
        serialPortManager = SerialPortManager(this).apply {
            setOnConnectionStateListener { connected ->
                isSerialPortAvailable = connected
                runOnUiThread {
                    updateRemoteControlIcon()
                    if (connected) {
                        Toast.makeText(this@MeetingPrepareActivity, "串口设备已连接", Toast.LENGTH_SHORT).show()
                    } else {
                        if (isRemoteControlEnabled) {
                            isRemoteControlEnabled = false
                            updateRemoteControlIcon()
                        }
                    }
                }
            }
        }
        // 检查串口设备可用性
        checkSerialPortAvailability()
    }
    
    /**
     * 检查串口设备可用性
     */
    private fun checkSerialPortAvailability() {
        serialPortManager?.let { manager ->
            val drivers = manager.discoverSerialPorts()
            isSerialPortAvailable = drivers.isNotEmpty()
            updateRemoteControlIcon()
            
            if (isSerialPortAvailable && !manager.isPortOpen()) {
                // 如果发现设备但未连接，可以提示用户
                Toast.makeText(this, "发现 ${drivers.size} 个串口设备", Toast.LENGTH_SHORT).show()
            } else if (!isSerialPortAvailable) {
                Toast.makeText(this, "未检测到串口设备，请连接 USB 串口设备", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 执行鼠标从左到右移动测试
     * 使用协程实现平滑移动
     */
    private fun performMouseMoveTest() {
        if (!isSerialPortAvailable || serialPortManager == null) {
            Log.w("MeetingPrepareActivity", "Serial port not available for mouse move test")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 目标窗口尺寸（可以根据实际设备调整）
                val targetWidth = 1920
                val targetHeight = 1080
                
                // 移动参数
                val startX = 0
                val endX = targetWidth
                val y = targetHeight / 2  // 屏幕中间
                val steps = 50  // 移动步数
                val stepDelay = 20L  // 每步延迟（毫秒）
                
                Log.d("MeetingPrepareActivity", "Starting mouse move test: from ($startX, $y) to ($endX, $y) in $steps steps")
                
                for (i in 0..steps) {
                    // 计算当前位置
                    val currentX = startX + ((endX - startX) * i / steps)
                    
                    // 生成绝对鼠标移动指令
                    val command = HIDCommandBuilder.buildMouseAbsoluteCommand(
                        x = currentX,
                        y = y,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        buttonStatus = HIDCommandBuilder.MouseButton.NONE
                    )
                    
                    // 发送指令
                    val success = serialPortManager?.sendData(command) ?: false
                    if (success) {
                        Log.d("MeetingPrepareActivity", "Mouse moved to ($currentX, $y)")
                    } else {
                        Log.w("MeetingPrepareActivity", "Failed to send mouse move command to ($currentX, $y)")
                    }
                    
                    // 延迟，实现平滑移动
                    delay(stepDelay)
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MeetingPrepareActivity, "鼠标移动测试完成", Toast.LENGTH_SHORT).show()
                }
                
                Log.d("MeetingPrepareActivity", "Mouse move test completed")
            } catch (e: Exception) {
                Log.e("MeetingPrepareActivity", "Error during mouse move test", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MeetingPrepareActivity, "鼠标移动测试失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 初始化 Agora Engine（仅用于监听频道用户变化，不发布音视频）
     */
    private fun initAgoraEngineForPresence() {
        // 使用 AgoraManager 获取或创建 RtcEngine 实例
        agoraEngine = AgoraManager.getOrCreateEngine(this)
        AgoraManager.addEventHandler(agoraPresenceEventHandler)
        if (agoraEngine != null) {
            Log.d(TAG, "Agora Engine initialized for presence monitoring")
        } else {
            Log.e(TAG, "Failed to initialize Agora engine for presence")
        }
    }
    
    /**
     * 启动视频通话（直接启动 VideoCallActivity，由 VideoCallActivity 负责加入频道）
     */
    private fun startVideoCall() {
        Log.d(TAG,"startVideoCall")
        val client = selectedClient ?: return
        val channelId = preferenceManager.getChannelId() // 使用设备的频道ID
        
        if (channelId.isEmpty()) {
            Toast.makeText(this, "频道ID未设置，无法开始通话", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 直接启动 VideoCallActivity，由 VideoCallActivity 负责加入频道
        val intent = Intent(this, VideoCallActivity::class.java).apply {
            putExtra("client_id", client.id)
            putExtra("client_name", client.name)
            putExtra("channel_id", channelId) // 使用设备的频道ID
            putExtra("microphone_enabled", isMicrophoneEnabled)
            putExtra("remote_control_enabled", isRemoteControlEnabled)
        }

        if (isAgoraJoined)
        {
            Log.d(TAG,"leaveChannel")
            agoraEngine?.leaveChannel()
            isAgoraJoined = false
        }
        startActivity(intent)
    }
    
    /**
     * 加入 Agora 频道作为观察者（用于监听用户变化）
     */
    private fun joinAgoraChannelForPresence() {
        if (isAgoraJoined || agoraEngine == null) {
            return
        }
        
        val channelId = preferenceManager.getChannelId()
        if (channelId.isEmpty()) {
            Log.w(TAG, "Channel ID is empty, cannot join channel for presence")
            return
        }
        
        try {
            // 作为观察者加入频道（不发布音视频）
            val option = ChannelMediaOptions().apply {
                autoSubscribeAudio = false
                autoSubscribeVideo = false
                publishCustomVideoTrack = false
                publishMicrophoneTrack = false
                clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            }
            
            val result = agoraEngine?.joinChannel(null, channelId, 0, option) ?: -1
            if (result == 0) {
                Log.d(TAG, "Joined channel $channelId for presence monitoring")
            } else {
                Log.e(TAG, "Failed to join channel for presence: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error joining channel for presence", e)
        }
    }
    
    
    
    /**
     * 更新客户端在线状态（基于 Agora 频道内的用户）
     * 返回的列表已排序：在线客户端在前，离线客户端在后
     */
    private fun updateClientsOnlineStatus(clients: List<Client>): List<Client> {
        return clients.map { client ->
            val isOnline = onlineUserIds.contains(client.id) || 
                          onlineUserIds.contains(client.id.toIntOrNull()?.toString())
            client.copy(isOnline = isOnline)
        }.sortedByDescending { it.isOnline } // 在线状态为 true 的排在前面
    }
    
    /**
     * Agora 事件处理器（用于监听频道用户变化）
     */
    private val agoraPresenceEventHandler = object : IRtcEngineEventHandler() {
        override fun onError(err: Int) {
            Log.w(TAG, "Agora presence engine error: $err")
        }
        
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.d(TAG, "Joined channel for presence: $channel, uid: $uid,elapsed:$elapsed")
            isAgoraJoined = true
        }
        
        override fun onLeaveChannel(stats: io.agora.rtc2.IRtcEngineEventHandler.RtcStats) {
            Log.d(TAG, "Left channel for presence")
            isAgoraJoined = false
        }
        
        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.d(TAG, "User joined channel: uid=$uid")
            onlineUserIds.add(uid.toString())
            // 更新客户端列表状态
            updateClientsListStatus()
        }
        
        override fun onUserOffline(uid: Int, reason: Int) {
            Log.d(TAG, "User offline: uid=$uid, reason=$reason")
            onlineUserIds.remove(uid.toString())
            // 更新客户端列表状态
            updateClientsListStatus()
        }
    }
    
    /**
     * 更新客户端列表的在线状态
     */
    private fun updateClientsListStatus() {
        val cachedClients = (application as? MainApplication)?.cachedClients
        if (cachedClients != null) {
            val updatedClients = updateClientsOnlineStatus(cachedClients)
            // 更新缓存
            (application as? MainApplication)?.cachedClients = updatedClients
            
            // 更新适配器
            runOnUiThread {
                if (::clientAdapter.isInitialized) {
                    clientAdapter = ClientListAdapter(updatedClients) { client ->
                        selectedClient = client
                        updateCallButtonState()
                    }
                    binding.rvClients.adapter = clientAdapter
                }
            }
        }
    }
    
    companion object {
        private const val TAG = "MeetingPrepareActivity"
    }

    override fun onPause() {
        Log.d(TAG,"on Paused")
        super.onPause()
        if (isAgoraJoined)
        {
            agoraEngine?.leaveChannel()
            isAgoraJoined = false
        }

    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG,"on Resumed")
        // 当 Activity 恢复时，重新加入监听频道（确保状态是最新的）
        // 因为 VideoCallActivity 退出时会 leaveChannel，所以需要重新加入
        val channelId = preferenceManager.getChannelId()
        if (channelId.isNotEmpty() && agoraEngine != null) {
            // 如果已经在频道中，先离开再重新加入（确保状态刷新）
            if (isAgoraJoined) {

            } else {
                // 如果不在频道中，直接加入
                joinAgoraChannelForPresence()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG,"on Destroyed")
        // 离开 Agora 频道
        if (isAgoraJoined) {
            agoraEngine?.leaveChannel()
            isAgoraJoined = false
        }
        agoraEngine?.stopPreview()
        // 注意：不在这里释放 engine，因为可能被 VideoCallActivity 使用
        // 只在应用退出时由 MainApplication 统一释放
        agoraEngine = null
        
        // 释放串口资源
        serialPortManager?.release()
        serialPortManager = null
        
        // CameraActivity 的 clear() 方法会自动处理摄像头注销
    }
}

