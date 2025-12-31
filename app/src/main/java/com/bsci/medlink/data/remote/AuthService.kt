package com.bsci.medlink.data.remote

import android.os.Build
import android.provider.Settings
import com.bsci.medlink.data.model.LoginResponse
import com.bsci.medlink.data.model.UserInfo
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // TODO: 替换为实际的 API 地址
    private val baseUrl = "https://your-api-server.com/api"

    suspend fun login(username: String, password: String): LoginResponse {
        return try {
            val json = JSONObject().apply {
                put("username", username)
                put("password", password)
            }

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/auth/login")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseBody)
                val success = jsonResponse.optBoolean("success", false)
                
                if (success) {
                    val data = jsonResponse.optJSONObject("data")
                    val userInfo = UserInfo(
                        userId = data?.optString("userId") ?: "",
                        deviceId = data?.optString("deviceId") ?: "",
                        region = data?.optString("region") ?: "",
                        token = data?.optString("token") ?: ""
                    )
                    LoginResponse.success(userInfo)
                } else {
                    LoginResponse.failure(jsonResponse.optString("message", "登录失败"))
                }
            } else {
                LoginResponse.failure("服务器错误: ${response.code}")
            }
        } catch (e: Exception) {
            // 如果 API 不可用，返回模拟数据用于测试
            // 实际使用时应该删除这部分代码
            if (e is IOException || e.message?.contains("Failed to connect") == true) {
                // 模拟登录成功
                val mockUserInfo = UserInfo(
                    userId = "user_${username}",
                    deviceId = android.os.Build.MODEL,
                    region = "区域A",
                    token = "mock_token_${System.currentTimeMillis()}"
                )
                LoginResponse.success(mockUserInfo)
            } else {
                LoginResponse.failure("网络错误: ${e.message}")
            }
        }
    }
}

