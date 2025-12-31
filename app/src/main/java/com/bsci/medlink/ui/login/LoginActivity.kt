package com.bsci.medlink.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bsci.medlink.R
import com.bsci.medlink.data.local.PreferenceManager
import com.bsci.medlink.data.remote.AuthService
import com.bsci.medlink.databinding.ActivityLoginBinding
import com.bsci.medlink.ui.registration.HostRegistrationActivity
import com.bsci.medlink.utils.DeviceUuidFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var preferenceManager: PreferenceManager
    private val authService = AuthService()
    private lateinit var uuidFactory: DeviceUuidFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 PreferenceManager（必须在 onCreate 中，此时 Context 已准备好）
        preferenceManager = PreferenceManager(this)
        uuidFactory = DeviceUuidFactory(this)

        // 检查是否有保存的登录信息
        val savedUsername = preferenceManager.getUsername()
        val savedPassword = preferenceManager.getPassword()
        val rememberMe = preferenceManager.getRememberMe()

        if (rememberMe && savedUsername.isNotEmpty()) {
            binding.etUsername.setText(savedUsername)
            binding.etPassword.setText(savedPassword)
            binding.cbRememberMe.isChecked = true
        }

        binding.btnLogin.setOnClickListener {
            login()
        }
    }

    private fun login() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (username.isEmpty()) {
            Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.isEmpty()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false

        // 后台认证登录
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = authService.login(username, password)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true

                    if (result.success && result.userInfo != null) {
                        // 登录验证成功，保存登录验证状态
                        preferenceManager.setLoginVerified(true)
                        
                        // 保存登录信息
                        if (binding.cbRememberMe.isChecked) {
                            preferenceManager.saveLoginInfo(username, password, true)
                        } else {
                            preferenceManager.clearLoginInfo()
                        }

                        // 保存用户信息
                        preferenceManager.saveUserInfo(result.userInfo!!)
                        
                        // 保存服务器 IP（从登录响应中获取，或使用默认值）
                        // TODO: 从登录响应中获取服务器 IP
                        val serverIp = getServerIpFromConfig()
                        if (serverIp.isNotEmpty()) {
                            preferenceManager.saveServerIp(serverIp)
                        }

                        // 登录验证成功后，直接跳转到注册页面
                        val deviceUuid = uuidFactory.getDeviceUuid()
                        val intent = Intent(this@LoginActivity, HostRegistrationActivity::class.java).apply {
                            putExtra("server_ip", serverIp)
                            putExtra("device_uuid", deviceUuid)
                        }
                        startActivity(intent)
                        finish()
                    } else {
                        // 登录验证失败，无法注册HOST
                        Toast.makeText(this@LoginActivity, result.message ?: "登录失败，无法注册HOST", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, "网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getServerIpFromConfig(): String {
        // TODO: 从配置或登录响应中获取服务器 IP
        // 可以从 UserInfo 或其他配置中获取
        // 暂时返回空字符串，需要根据实际情况实现
        return getString(R.string.server_ip)
    }

}

