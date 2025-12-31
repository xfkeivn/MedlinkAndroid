package com.bsci.medlink.data.remote

import android.util.Log
import com.bsci.medlink.data.model.HostInfo
import com.bsci.medlink.data.model.HostInitialInfoResponse
import com.bsci.medlink.data.model.HostRegistrationInfo
import com.bsci.medlink.data.model.HostRegistrationResponse
import com.bsci.medlink.data.model.MeetingAccountResponse
import org.json.JSONArray
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HostRegistrationService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val TAG = "HostRegistrationService"

    /**
     * 检查 HOST 是否已注册
     * @param ip 服务器 IP
     * @param uuid 设备 UUID
     * @return HostRegistrationResponse
     */
    suspend fun checkHostRegistration(ip: String, uuid: String): HostRegistrationResponse {
        return try {
            val url = "https://$ip/api-meeting/RequestMeetingAccount/UUID/$uuid"
            Log.d(TAG, "Checking host registration: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                parseCheckResponse(responseBody)
            } else {
                Log.e(TAG, "Failed to check registration: ${response.code}")
                HostRegistrationResponse(
                    success = false,
                    isRegistered = false,
                    message = "服务器错误: ${response.code}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking host registration", e)
            HostRegistrationResponse(
                success = false,
                isRegistered = false,
                message = "网络错误: ${e.message}"
            )
        }
    }

    /**
     * 注册 HOST
     * @param ip 服务器 IP
     * @param uuid 设备 UUID
     * @param registrationInfo 注册信息
     * @return HostRegistrationResponse
     */
    suspend fun registerHost(
        ip: String,
        uuid: String,
        registrationInfo: HostRegistrationInfo
    ): HostRegistrationResponse {
        return try {
            // 构建查询参数
            val urlBuilder = StringBuilder()
            urlBuilder.append("https://$ip/api-meeting/RequestMeetingAccount/UUID/$uuid")
            urlBuilder.append("?hospital=${java.net.URLEncoder.encode(registrationInfo.hospital, "UTF-8")}")
            urlBuilder.append("&department=${java.net.URLEncoder.encode(registrationInfo.department, "UTF-8")}")
            urlBuilder.append("&equipment=${java.net.URLEncoder.encode(registrationInfo.equipment, "UTF-8")}")
            
            
            val url = urlBuilder.toString()
            Log.d(TAG, "Registering host: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                parseRegisterResponse(responseBody, uuid)
            } else {
                Log.e(TAG, "Failed to register host: ${response.code}")
                HostRegistrationResponse(
                    success = false,
                    isRegistered = false,
                    message = "服务器错误: ${response.code}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering host", e)
            HostRegistrationResponse(
                success = false,
                isRegistered = false,
                message = "网络错误: ${e.message}"
            )
        }
    }

    /**
     * 解析检查注册响应
     */
    private fun parseCheckResponse(response: String): HostRegistrationResponse {
        return try {
            val doc = JSONObject(response)
            
            if (doc.has("Result") && doc.get("Result") is Boolean) {
                val result = doc.getBoolean("Result")
                
                if (result) {
                    // 已注册
                    val hostInfo = if (doc.has("HostInfo") && doc.get("HostInfo") is JSONObject) {
                        val hostObj = doc.getJSONObject("HostInfo")
                        val hospital = extractNameFromField(hostObj, "hospital")
                        val department = extractNameFromField(hostObj, "department")
                        val equipment = extractEquipmentName(hostObj)
                        
                        // 获取channel_id（会议号）
                        val channelId = extractChannelId(hostObj)
                        
                        HostInfo(
                            uuid = hostObj.optString("uuid", ""),
                            hospital = hospital,
                            department = department,
                            location = hostObj.optString("location", null),
                            equipment = equipment,
                            channelId = channelId
                        )
                    } else {
                        null
                    }
                    
                    HostRegistrationResponse(
                        success = true,
                        isRegistered = true,
                        hostInfo = hostInfo
                    )
                } else {
                    // 未注册
                    val error = if (doc.has("Error") && doc.get("Error") is String) {
                        doc.getString("Error")
                    } else {
                        "HOST 未注册"
                    }
                    
                    HostRegistrationResponse(
                        success = true,
                        isRegistered = false,
                        message = error
                    )
                }
            } else {
                HostRegistrationResponse(
                    success = false,
                    isRegistered = false,
                    message = "响应格式错误"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing check response", e)
            HostRegistrationResponse(
                success = false,
                isRegistered = false,
                message = "解析响应失败: ${e.message}"
            )
        }
    }

    /**
     * 解析注册响应
     */
    private fun parseRegisterResponse(response: String, uuid: String): HostRegistrationResponse {
        return try {
            val doc = JSONObject(response)
            
            if (doc.has("Result") && doc.get("Result") is Boolean) {
                val result = doc.getBoolean("Result")
                
                if (result) {
                    val hostInfo = if (doc.has("HostInfo") && doc.get("HostInfo") is JSONObject) {
                        val hostObj = doc.getJSONObject("HostInfo")
                        val hospital = extractNameFromField(hostObj, "hospital")
                        val department = extractNameFromField(hostObj, "department")
                        val equipment = extractEquipmentName(hostObj)
                        
                        // 获取channel_id（会议号）
                        val channelId = extractChannelId(hostObj)
                        
                        HostInfo(
                            uuid = uuid,
                            hospital = hospital,
                            department = department,
                            location = hostObj.optString("location", null),
                            equipment = equipment,
                            channelId = channelId
                        )
                    } else {
                        HostInfo(uuid = uuid)
                    }
                    
                    HostRegistrationResponse(
                        success = true,
                        isRegistered = true,
                        hostInfo = hostInfo
                    )
                } else {
                    val error = if (doc.has("Error") && doc.get("Error") is String) {
                        doc.getString("Error")
                    } else {
                        "注册失败"
                    }
                    
                    HostRegistrationResponse(
                        success = false,
                        isRegistered = false,
                        message = error
                    )
                }
            } else {
                HostRegistrationResponse(
                    success = false,
                    isRegistered = false,
                    message = "响应格式错误"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing register response", e)
            HostRegistrationResponse(
                success = false,
                isRegistered = false,
                message = "解析响应失败: ${e.message}"
            )
        }
    }

    /**
     * 获取参会账号信息（包括医院、科室等信息）
     * @param ip 服务器 IP
     * @param uuid 设备 UUID
     * @return MeetingAccountResponse
     */
    suspend fun getMeetingAccount(ip: String, uuid: String): MeetingAccountResponse {
        return try {
            val url = "https://$ip/api-meeting/RequestMeetingAccount/UUID/$uuid"
            Log.d(TAG, "Requesting meeting account: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                parseMeetingAccountResponse(responseBody, uuid)
            } else {
                Log.e(TAG, "Failed to request meeting account: ${response.code}")
                MeetingAccountResponse(
                    success = false,
                    isEnabled = false,
                    message = "服务器错误: ${response.code}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting meeting account", e)
            MeetingAccountResponse(
                success = false,
                isEnabled = false,
                message = "网络错误: ${e.message}"
            )
        }
    }
    
    /**
     * 检查设备是否可用（通过 RequestMeetingAccount API）
     * Result 为 True 表示设备可用，False 表示设备不可用
     * @param ip 服务器 IP
     * @param uuid 设备 UUID
     * @return DeviceStatusResponse
     */
    suspend fun checkDeviceEnabled(ip: String, uuid: String): com.bsci.medlink.data.model.DeviceStatusResponse {
        val accountResponse = getMeetingAccount(ip, uuid)
        return com.bsci.medlink.data.model.DeviceStatusResponse(
            success = accountResponse.success,
            isEnabled = accountResponse.isEnabled,
            message = accountResponse.message
        )
    }

    /**
     * 解析 RequestMeetingAccount 响应（包含医院等信息）
     * Result 为 True 表示请求成功，False 表示请求失败
     * is_enable 字段表示设备是否可用（True 可用，False 不可用）
     */
    private fun parseMeetingAccountResponse(response: String, uuid: String): MeetingAccountResponse {
        return try {
            val doc = JSONObject(response)
            
            if (doc.has("Result") && doc.get("Result") is Boolean) {
                val result = doc.getBoolean("Result")
                
                if (result) {
                    // 请求成功，从 Host 对象中获取信息
                    var isEnabled = false
                    var errorMessage: String? = null
                    var hostInfo: HostInfo? = null
                    
                    if (doc.has("Host") && doc.get("Host") is JSONObject) {
                        val hostObj = doc.getJSONObject("Host")
                        
                        // 获取 is_enable 字段
                        if (hostObj.has("is_enable") && hostObj.get("is_enable") is Boolean) {
                            isEnabled = hostObj.getBoolean("is_enable")
                        } else if (hostObj.has("isEnable") && hostObj.get("isEnable") is Boolean) {
                            // 兼容驼峰命名
                            isEnabled = hostObj.getBoolean("isEnable")
                        }
                        
                        // 获取医院、科室等信息（支持对象和字符串格式）
                        val hospital = extractNameFromField(hostObj, "hospital")
                        val department = extractNameFromField(hostObj, "department")
                        val equipment = extractEquipmentName(hostObj)
                        
                        // 获取channel_id（会议号）
                        val channelId = extractChannelId(hostObj)
                        
                        hostInfo = HostInfo(
                            uuid = uuid,
                            hospital = hospital,
                            department = department,
                            location = hostObj.optString("location", null),
                            equipment = equipment,
                            createTime = hostObj.optString("create_time", null),
                            channelId = channelId
                        )
                        
                        // 如果设备不可用，尝试获取错误信息
                        if (!isEnabled) {
                            errorMessage = hostObj.optString("Error", null)
                                ?: doc.optString("Error", "设备已被禁用")
                        }
                    } else {
                        // 如果没有 Host 对象，默认不可用
                        isEnabled = false
                        errorMessage = "响应格式错误：缺少 Host 对象"
                    }
                    
                    MeetingAccountResponse(
                        success = true,
                        isEnabled = isEnabled,
                        message = errorMessage,
                        hostInfo = hostInfo
                    )
                } else {
                    // 请求失败
                    val error = if (doc.has("Error") && doc.get("Error") is String) {
                        doc.getString("Error")
                    } else {
                        "请求失败"
                    }
                    
                    MeetingAccountResponse(
                        success = false,
                        isEnabled = false,
                        message = error
                    )
                }
            } else {
                MeetingAccountResponse(
                    success = false,
                    isEnabled = false,
                    message = "响应格式错误：缺少 Result 字段"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing RequestMeetingAccount response", e)
            MeetingAccountResponse(
                success = false,
                isEnabled = false,
                message = "解析响应失败: ${e.message}"
            )
        }
    }
    
    /**
     * 从字段中提取名称（支持对象和字符串格式）
     * 如果是对象，优先返回中文名称，如果没有则返回英文名称
     * 如果是字符串，直接返回
     */
    private fun extractNameFromField(obj: JSONObject, fieldName: String): String? {
        if (!obj.has(fieldName)) {
            return null
        }
        
        val fieldValue = obj.get(fieldName)
        
        return when {
            fieldValue is String -> {
                // 如果是字符串，直接返回
                if (fieldValue.isNotEmpty()) fieldValue else null
            }
            fieldValue is JSONObject -> {
                // 如果是对象，提取中文或英文名称
                val chineseName = fieldValue.optString("chinese_name", "")
                val englishName = fieldValue.optString("english_name", "")
                
                when {
                    chineseName.isNotEmpty() -> chineseName
                    englishName.isNotEmpty() -> englishName
                    else -> null
                }
            }
            else -> null
        }
    }
    
    /**
     * 从设备字段中提取名称（支持对象和字符串格式）
     */
    private fun extractEquipmentName(obj: JSONObject): String? {
        if (!obj.has("equipment")) {
            return null
        }
        
        val equipmentValue = obj.get("equipment")
        
        return when {
            equipmentValue is String -> {
                // 如果是字符串，直接返回
                if (equipmentValue.isNotEmpty()) equipmentValue else null
            }
            equipmentValue is JSONObject -> {
                // 如果是对象，提取name字段
                val name = equipmentValue.optString("name", "")
                if (name.isNotEmpty()) name else null
            }
            else -> null
        }
    }
    
    /**
     * 从channel字段中提取channel_id（会议号）
     * 支持两种格式：
     * 1. "channel": {"id": 45, "channel_id": "1044"} (对象格式)
     * 2. "channel_id": "1044" (直接字段格式)
     */
    private fun extractChannelId(obj: JSONObject): String? {
        // 优先检查 channel 对象
        if (obj.has("channel")) {
            val channelValue = obj.get("channel")
            return when {
                channelValue is JSONObject -> {
                    // 如果是对象，提取 channel_id 字段
                    val channelId = channelValue.optString("channel_id", "")
                    if (channelId.isNotEmpty()) channelId else null
                }
                channelValue is String -> {
                    // 如果是字符串，直接返回
                    if (channelValue.isNotEmpty()) channelValue else null
                }
                else -> null
            }
        }
        
        // 如果没有 channel 对象，检查是否有直接的 channel_id 字段
        if (obj.has("channel_id")) {
            val channelId = obj.optString("channel_id", "")
            return if (channelId.isNotEmpty()) channelId else null
        }
        
        return null
    }
    
    /**
     * 获取 HOST 初始信息（医院、科室、设备列表）
     * @param ip 服务器 IP
     * @return HostInitialInfoResponse
     */
    suspend fun getHostInitialInfo(ip: String): HostInitialInfoResponse {
        return try {
            val url = "https://$ip/api-meeting/getHostInitialInfo"
            Log.d(TAG, "Getting host initial info: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                parseHostInitialInfoResponse(responseBody)
            } else {
                Log.e(TAG, "Failed to get host initial info: ${response.code}")
                HostInitialInfoResponse(
                    success = false,
                    message = "服务器错误: ${response.code}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting host initial info", e)
            HostInitialInfoResponse(
                success = false,
                message = "网络错误: ${e.message}"
            )
        }
    }
    
    /**
     * 解析初始信息响应
     */
    private fun parseHostInitialInfoResponse(response: String): HostInitialInfoResponse {
        return try {
            val doc = JSONObject(response)
            
            if (doc.has("Result") && doc.get("Result") is Boolean) {
                val result = doc.getBoolean("Result")
                
                if (result && doc.has("Info") && doc.get("Info") is JSONObject) {
                    val infoObj = doc.getJSONObject("Info")
                    val hospitals = mutableListOf<com.bsci.medlink.data.model.Hospital>()
                    val departments = mutableListOf<com.bsci.medlink.data.model.Department>()
                    val equipments = mutableListOf<com.bsci.medlink.data.model.Equipment>()
                    
                    // 解析医院列表
                    if (infoObj.has("Hospitals") && infoObj.get("Hospitals") is JSONArray) {
                        val hospitalsArray = infoObj.getJSONArray("Hospitals")
                        for (i in 0 until hospitalsArray.length()) {
                            val hospitalObj = hospitalsArray.getJSONObject(i)
                            hospitals.add(
                                com.bsci.medlink.data.model.Hospital(
                                    id = hospitalObj.getInt("id"),
                                    english_name = hospitalObj.optString("english_name", ""),
                                    chinese_name = hospitalObj.optString("chinese_name", "")
                                )
                            )
                        }
                    }
                    
                    // 解析科室列表
                    if (infoObj.has("Departments") && infoObj.get("Departments") is JSONArray) {
                        val departmentsArray = infoObj.getJSONArray("Departments")
                        for (i in 0 until departmentsArray.length()) {
                            val departmentObj = departmentsArray.getJSONObject(i)
                            departments.add(
                                com.bsci.medlink.data.model.Department(
                                    id = departmentObj.getInt("id"),
                                    english_name = departmentObj.optString("english_name", ""),
                                    chinese_name = departmentObj.optString("chinese_name", "")
                                )
                            )
                        }
                    }
                    
                    // 解析设备列表
                    if (infoObj.has("Equipments") && infoObj.get("Equipments") is JSONArray) {
                        val equipmentsArray = infoObj.getJSONArray("Equipments")
                        for (i in 0 until equipmentsArray.length()) {
                            val equipmentObj = equipmentsArray.getJSONObject(i)
                            equipments.add(
                                com.bsci.medlink.data.model.Equipment(
                                    id = equipmentObj.getInt("id"),
                                    name = equipmentObj.optString("name", "")
                                )
                            )
                        }
                    }
                    
                    HostInitialInfoResponse(
                        success = true,
                        hospitals = hospitals,
                        departments = departments,
                        equipments = equipments
                    )
                } else {
                    val error = if (doc.has("Error") && doc.get("Error") is String) {
                        doc.getString("Error")
                    } else {
                        "获取初始信息失败"
                    }
                    HostInitialInfoResponse(
                        success = false,
                        message = error
                    )
                }
            } else {
                HostInitialInfoResponse(
                    success = false,
                    message = "响应格式错误：缺少 Result 字段"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing host initial info response", e)
            HostInitialInfoResponse(
                success = false,
                message = "解析响应失败: ${e.message}"
            )
        }
    }
}

