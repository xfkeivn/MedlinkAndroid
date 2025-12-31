package com.bsci.medlink.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bsci.medlink.R
import com.bsci.medlink.MainApplication
import com.bsci.medlink.data.local.PreferenceManager
import com.bsci.medlink.data.remote.ClientService
import com.bsci.medlink.data.remote.HostRegistrationService
import com.bsci.medlink.ui.login.LoginActivity
import com.bsci.medlink.ui.meeting.MeetingPrepareActivity
import com.bsci.medlink.utils.DeviceUuidFactory
import com.bsci.medlink.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplashActivity : AppCompatActivity() {
    private val splashDelay: Long = 1000 // 1秒延迟
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var uuidFactory: DeviceUuidFactory
    private val registrationService = HostRegistrationService()
    private val clientService = ClientService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        preferenceManager = PreferenceManager(this)
        uuidFactory = DeviceUuidFactory(this)

        // 延迟后检查网络和注册状态
        Handler(Looper.getMainLooper()).postDelayed({
            checkNetworkAndRegistration()
        }, splashDelay)
    }
    
    private fun checkNetworkAndRegistration() {
        // 首先检查网络是否可用
        if (!NetworkUtils.isNetworkAvailable(this)) {
            showNetworkErrorDialog()
            return
        }
        
        // 网络可用，检查注册状态
        checkRegistrationStatus()
    }
    
    private fun showNetworkErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle("网络连接异常")
            .setMessage("无法连接到网络，请检查网络设置后重试")
            .setPositiveButton("确认") { _, _ ->
                // 退出应用
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun checkRegistrationStatus() {
        // 检查设备是否已经注册过（通过读取SharedPreference）
        if (!preferenceManager.isHostRegistered()) {
            // 未注册，跳转到登录页面
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            // 已注册，直接跳转到会议准备界面
            val serverIp = preferenceManager.getServerIp()
            val deviceUuid = uuidFactory.getDeviceUuid()
            
            if (serverIp.isEmpty()) {
                // 如果没有服务器IP，使用默认值
                val defaultIp = getString(R.string.server_ip)
                preferenceManager.saveServerIp(defaultIp)
                requestMeetingAccount(defaultIp, deviceUuid)
            } else {
                requestMeetingAccount(serverIp, deviceUuid)
            }
        }
    }
    
    private fun requestMeetingAccount(serverIp: String, deviceUuid: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 并行请求 MeetingAccount 和客户端列表
                val accountDeferred = async {
                    registrationService.getMeetingAccount(serverIp, deviceUuid)
                }
                val clientsDeferred = async {
                    clientService.getClients(serverIp, deviceUuid)
                }
                
                // 等待两个请求完成
                val accountResponse = accountDeferred.await()
                val clients = clientsDeferred.await()
                
                withContext(Dispatchers.Main) {
                    // 保存 MeetingAccount 信息
                    if (accountResponse.success && accountResponse.hostInfo != null) {
                        preferenceManager.saveHostInfo(
                            accountResponse.hostInfo.hospital,
                            accountResponse.hostInfo.department,
                            accountResponse.hostInfo.location,
                            accountResponse.hostInfo.equipment
                        )
                        // 保存注册日期
                        accountResponse.hostInfo.createTime?.let {
                            preferenceManager.saveRegisterDate(it)
                        }
                    }
                    
                    // 缓存客户端列表到 Application
                    (application as? MainApplication)?.cachedClients = clients
                    
                    // 跳转到会议准备界面
                    val intent = Intent(this@SplashActivity, MeetingPrepareActivity::class.java)
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // 即使请求失败，也跳转到会议准备界面（使用已保存的信息）
                    val intent = Intent(this@SplashActivity, MeetingPrepareActivity::class.java)
                    startActivity(intent)
                    finish()
                }
            }
        }
    }
}

