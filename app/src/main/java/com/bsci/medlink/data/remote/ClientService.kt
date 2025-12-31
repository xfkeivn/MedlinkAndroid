package com.bsci.medlink.data.remote

import android.util.Log
import com.bsci.medlink.data.model.Client
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ClientService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val TAG = "ClientService"

    /**
     * 获取客户端列表
     * @param ip IP 地址
     * @param uuid UUID
     * @return 客户端列表，如果失败返回空列表
     */
    suspend fun getClients(ip: String, uuid: String): List<Client> {
        return try {
            val url = "https://$ip/api-meeting/RequestHostClients/ServiceClients/$uuid"
            Log.d(TAG, "Requesting clients from: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                parseClientsResponse(responseBody)
            } else {
                Log.e(TAG, "Failed to get clients: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting clients", e)
            emptyList()
        }
    }

    /**
     * 解析客户端列表响应
     * @param response JSON 响应字符串
     * @return 客户端列表
     */
    private fun parseClientsResponse(response: String): List<Client> {
        val clients = mutableListOf<Client>()

        try {
            val doc = JSONObject(response)
            val log = StringBuilder("Receive host clients infos:")

            if (doc.has("Result") && doc.get("Result") is Boolean) {
                val result = doc.getBoolean("Result")

                if (result) {
                    if (doc.has("Clients") && doc.get("Clients") is JSONArray) {
                        val clientsArray = doc.getJSONArray("Clients")
                        val len = clientsArray.length()

                        for (i in 0 until len) {
                            val clientObj = clientsArray.getJSONObject(i)
                            if (clientObj != null) {
                                log.append("client_$i{")

                                // 解析 display_name
                                var name = ""
                                if (clientObj.has("display_name")) {
                                    val displayNameValue = clientObj.get("display_name")
                                    if (displayNameValue is String) {
                                        name = displayNameValue
                                        log.append("display_name=$name")
                                    } else {
                                        Log.e(TAG, "The value of display_name is not string type.")
                                    }
                                }

                                // 解析 telephone
                                var telephone = ""
                                if (clientObj.has("telephone")) {
                                    val telephoneValue = clientObj.get("telephone")
                                    if (telephoneValue is String) {
                                        telephone = telephoneValue
                                        log.append(" telephone=$telephone")
                                    } else {
                                        Log.e(TAG, "The value of telephone is not string type.")
                                    }
                                }

                                // 解析 id
                                var userId = ""
                                if (clientObj.has("id")) {
                                    val idValue = clientObj.get("id")
                                    when (idValue) {
                                        is Int -> {
                                            userId = idValue.toString()
                                        }
                                        is String -> {
                                            userId = idValue
                                        }
                                        else -> {
                                            Log.e(TAG, "The value of id is not int or string type.")
                                        }
                                    }
                                }
                                log.append(" id=$userId")

                                // 解析 icon_round (base64 编码的图片)
                                var iconPath: String? = null
                                if (clientObj.has("icon_round")) {
                                    val iconValue = clientObj.get("icon_round")
                                    if (iconValue is String) {
                                        // icon_round 是 base64 编码的图片数据
                                        // 可以保存到本地或直接使用 base64 字符串
                                        iconPath = iconValue // 暂时保存 base64 字符串，后续可以保存为文件
                                        log.append(" icon_round=base64_image")
                                    } else {
                                        Log.e(TAG, "The value of icon_round is not string type.")
                                    }
                                }

                                log.append("} ")

                                // 创建 Client 对象
                                val client = Client(
                                    id = userId,
                                    name = name,
                                    telephone = telephone,
                                    avatar = iconPath,
                                    isOnline = false // 默认离线，可以根据实际需求设置
                                )

                                clients.add(client)
                            }
                        }

                        Log.d(TAG, log.toString())
                    }
                } else {
                    // Result 为 false，检查 Error 字段
                    if (doc.has("Error") && doc.get("Error") is String) {
                        val error = doc.getString("Error")
                        Log.e(TAG, error)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing clients response: $response", e)
        }

        return clients
    }
}

