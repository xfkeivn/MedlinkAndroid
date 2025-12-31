package com.bsci.medlink.ui.meeting

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import com.bsci.medlink.utils.DeviceUuidFactory
import com.bsci.medlink.utils.SerialPortManager
import com.bsci.medlink.utils.HIDCommandBuilder
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

class MeetingPrepareActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMeetingPrepareBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var clientAdapter: ClientListAdapter
    private val clientService = ClientService()
    private val registrationService = HostRegistrationService()
    private lateinit var uuidFactory: DeviceUuidFactory
    private var selectedClient: Client? = null
    private var isUsbCameraOk = false
    private var isMicrophoneEnabled = false
    private var isRemoteControlEnabled = false
    private var serialPortManager: SerialPortManager? = null
    private var isSerialPortAvailable = false
    
    // Agora RTC Engine（仅用于监听频道用户变化）
    private var agoraEngine: RtcEngine? = null
    private var isAgoraJoined = false
    private val onlineUserIds = mutableSetOf<String>() // 存储在线用户的ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeetingPrepareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)
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

    private fun initializeUI() {
        setupDeviceInfo()
        setupClientList()
        initSerialPort()
        setupDeviceStatus()
        setupExitButton()
    }
    
    /**
     * 设置退出按钮
     */
    private fun setupExitButton() {
        binding.btnExit.setOnClickListener {
            showExitAppDialog()
        }
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

        // 先从缓存中获取客户端列表（在 SplashActivity 中已预加载）
        loadClientsFromCache()
        
        // 同时从服务器刷新客户端列表（后台更新）
        loadClientsFromServer()

        // 检查 USB 摄像头状态
        checkUsbCameraStatus()
    }
    
    private fun loadClientsFromCache() {
        // 从 Application 缓存中获取客户端列表
        val cachedClients = (application as? MainApplication)?.cachedClients
        if (cachedClients != null && cachedClients.isNotEmpty()) {
            // 更新在线状态并排序（在线在前）
            val sortedClients = updateClientsOnlineStatus(cachedClients)
            // 使用缓存的数据更新适配器
            clientAdapter = ClientListAdapter(sortedClients) { client ->
                selectedClient = client
                updateCallButtonState()
            }
            binding.rvClients.adapter = clientAdapter
        }
    }

    private fun loadClientsFromServer() {
        // TODO: 从 PreferenceManager 或配置中获取 IP 和 UUID
        // 这里需要根据实际情况获取 IP 地址和 UUID
         val ip = getServerIp() // 需要实现此方法获取 IP
        val uuid = getUuid() // 需要实现此方法获取 UUID

        if (ip.isEmpty() || uuid.isEmpty()) {
            Toast.makeText(this, "服务器配置不完整", Toast.LENGTH_SHORT).show()
            return
        }

        // 显示加载状态（可以添加 ProgressBar 到布局中）
        // binding.progressBar?.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val clients = clientService.getClients(ip, uuid)
                withContext(Dispatchers.Main) {
                    // binding.progressBar?.visibility = View.GONE
                    if (clients.isNotEmpty()) {
                        // 更新缓存
                        (application as? MainApplication)?.cachedClients = clients
                        
                        // 更新适配器数据（使用在线状态更新后的客户端列表）
                        val clientsWithStatus = updateClientsOnlineStatus(clients)
                        clientAdapter = ClientListAdapter(clientsWithStatus) { client ->
                            selectedClient = client
                            updateCallButtonState()
                        }
                        binding.rvClients.adapter = clientAdapter
                        
                        // 加入 Agora 频道以监听用户变化
                        joinAgoraChannelForPresence()
                    } else {
                        // 如果服务器返回空列表，但缓存中有数据，保持显示缓存数据
                        if ((application as? MainApplication)?.cachedClients.isNullOrEmpty()) {
                            Toast.makeText(this@MeetingPrepareActivity, "未获取到客户端列表", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // binding.progressBar?.visibility = View.GONE
                    // 如果获取失败，但缓存中有数据，继续使用缓存数据
                    if ((application as? MainApplication)?.cachedClients.isNullOrEmpty()) {
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

        clientAdapter = ClientListAdapter(clients) { client ->
            selectedClient = client
            updateCallButtonState()
        }
        binding.rvClients.adapter = clientAdapter
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

    private fun checkUsbCameraStatus() {
        // TODO: 实际检查 USB 摄像头状态
        isUsbCameraOk = true // 模拟状态
        updateUsbCameraIcon()
    }

    private fun setupDeviceStatus() {
        // USB 摄像头状态
        binding.ivUsbCamera.setOnClickListener {
            // USB 摄像头状态不可点击切换，只显示状态
            Toast.makeText(this, if (isUsbCameraOk) "USB摄像头正常" else "USB摄像头不可用", Toast.LENGTH_SHORT).show()
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
        if (isUsbCameraOk) {
            binding.ivUsbCamera.setImageResource(R.drawable.ic_camera_on)
            binding.ivUsbCamera.alpha = 1.0f
        } else {
            binding.ivUsbCamera.setImageResource(R.drawable.ic_camera_off)
            binding.ivUsbCamera.alpha = 0.5f
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
            // 串口设备不可用，显示灰色禁用鼠标图标
            binding.ivRemoteControl.setImageResource(R.drawable.ic_mouse_disabled)
            binding.ivRemoteControl.alpha = 0.3f
        } else if (isRemoteControlEnabled) {
            // 串口设备可用且已启用，显示正常鼠标图标
            binding.ivRemoteControl.setImageResource(R.drawable.ic_mouse)
            binding.ivRemoteControl.alpha = 1.0f
        } else {
            // 串口设备可用但未启用，显示禁用鼠标图标（带斜线）
            binding.ivRemoteControl.setImageResource(R.drawable.ic_mouse_disabled)
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
        try {
            val config = RtcEngineConfig()
            config.mContext = applicationContext
            config.mAppId = getString(R.string.agora_app_id)
            config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            config.mEventHandler = agoraPresenceEventHandler
            config.mAudioScenario = Constants.AudioScenario.getValue(Constants.AudioScenario.DEFAULT)
            
            (application as? MainApplication)?.globalSettings?.areaCode?.let {
                config.mAreaCode = it
            }
            
            agoraEngine = RtcEngine.create(config)
            
            agoraEngine?.setParameters(
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
                (application as? MainApplication)?.globalSettings?.privateCloudConfig
            if (localAccessPointConfiguration != null) {
                agoraEngine?.setLocalAccessPoint(localAccessPointConfiguration)
            }
            
            Log.d(TAG, "Agora Engine initialized for presence monitoring")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Agora engine for presence", e)
        }
    }
    
    /**
     * 启动视频通话（直接启动 VideoCallActivity，由 VideoCallActivity 负责加入频道）
     */
    private fun startVideoCall() {
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
            Log.d(TAG, "Joined channel for presence: $channel, uid: $uid")
            isAgoraJoined = true
            // 加入频道成功后，刷新客户端列表状态
            // 注意：已经在频道中的用户会通过 onUserJoined 回调通知
            // 但为了确保状态正确，延迟一小段时间后刷新
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                refreshClientsListStatus()
            }, 500)
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
        super.onPause()
    }
    
    override fun onResume() {
        super.onResume()
        // 当 Activity 恢复时，如果不在监听频道中，重新加入监听频道
        if (!isAgoraJoined && agoraEngine != null) {
            val channelId = preferenceManager.getChannelId()
            if (channelId.isNotEmpty()) {
                joinAgoraChannelForPresence()
            }
        } else if (isAgoraJoined) {
            // 如果已经在频道中，刷新客户端列表状态（确保状态正确）
            refreshClientsListStatus()
        }
    }
    
    /**
     * 刷新客户端列表状态（重新从缓存获取并更新在线状态）
     */
    private fun refreshClientsListStatus() {
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
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 离开 Agora 频道
        if (isAgoraJoined) {
            agoraEngine?.leaveChannel()
            isAgoraJoined = false
        }
        agoraEngine?.stopPreview()
        agoraEngine = null
        
        // 释放串口资源
        serialPortManager?.release()
        serialPortManager = null
    }
}

