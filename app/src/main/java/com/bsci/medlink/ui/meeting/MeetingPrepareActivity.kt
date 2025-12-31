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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeetingPrepareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)
        uuidFactory = DeviceUuidFactory(this)

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
        val deviceUuid = uuidFactory.getDeviceUuid()
        val hospital = preferenceManager.getHospital()
        val department = preferenceManager.getDepartment()
        val equipment = preferenceManager.getEquipment()
        val registerDate = preferenceManager.getRegisterDate()

        // 在客户端列表上方显示详细信息
        setupDeviceInfoPanel(deviceUuid, hospital, department, equipment, registerDate)
    }
    
    private fun setupDeviceInfoPanel(deviceUuid: String, hospital: String, department: String, equipment: String, registerDate: String) {
        // 显示设备ID
        binding.tvDeviceId.text = if (deviceUuid.isNotEmpty()) deviceUuid else "未设置"
        
        // 显示医院
        binding.tvHospitalValue.text = if (hospital.isNotEmpty()) hospital else "未设置"
        
        // 显示科室
        binding.tvDepartmentValue.text = if (department.isNotEmpty()) department else "未设置"
        
        // 显示设备
        binding.tvEquipmentValue.text = if (equipment.isNotEmpty()) equipment else "未设置"
        
        // 显示注册日期
        val formattedDate = if (registerDate.isNotEmpty()) {
            formatRegisterDate(registerDate)
        } else {
            "未设置"
        }
        binding.tvRegisterDateValue.text = formattedDate
    }
    
    private fun formatRegisterDate(dateString: String): String {
        return try {
            // 解析 ISO 8601 格式：2025-12-30T09:57:14.705702
            // 转换为：2025-12-30 09:57:14
            if (dateString.contains("T")) {
                val datePart = dateString.substringBefore("T")
                val timePart = dateString.substringAfter("T").substringBefore(".")
                "$datePart $timePart"
            } else {
                dateString
            }
        } catch (e: Exception) {
            dateString
        }
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
            // 使用缓存的数据更新适配器
            clientAdapter = ClientListAdapter(cachedClients) { client ->
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
                        
                        // 更新适配器数据
                        clientAdapter = ClientListAdapter(clients) { client ->
                            selectedClient = client
                            updateCallButtonState()
                        }
                        binding.rvClients.adapter = clientAdapter
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
            Toast.makeText(this, if (isRemoteControlEnabled) "远程控制已启用" else "远程控制已禁用", Toast.LENGTH_SHORT).show()
        }

        // 通话按钮
        binding.btnCall.setOnClickListener {
            if (selectedClient != null && selectedClient!!.isOnline) {
                val intent = Intent(this, VideoCallActivity::class.java).apply {
                    putExtra("client_id", selectedClient!!.id)
                    putExtra("client_name", selectedClient!!.name)
                    putExtra("channel_id", "channel_${selectedClient!!.id}") // 使用客户端ID作为频道ID
                    putExtra("microphone_enabled", isMicrophoneEnabled)
                    putExtra("remote_control_enabled", isRemoteControlEnabled)
                }
                startActivity(intent)
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
            // 串口设备不可用，显示灰色
            binding.ivRemoteControl.setImageResource(R.drawable.ic_remote_control_off)
            binding.ivRemoteControl.alpha = 0.3f
        } else if (isRemoteControlEnabled) {
            // 串口设备可用且已启用
            binding.ivRemoteControl.setImageResource(R.drawable.ic_remote_control_on)
            binding.ivRemoteControl.alpha = 1.0f
        } else {
            // 串口设备可用但未启用
            binding.ivRemoteControl.setImageResource(R.drawable.ic_remote_control_off)
            binding.ivRemoteControl.alpha = 0.7f
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
    
    override fun onDestroy() {
        super.onDestroy()
        serialPortManager?.release()
        serialPortManager = null
    }
}

