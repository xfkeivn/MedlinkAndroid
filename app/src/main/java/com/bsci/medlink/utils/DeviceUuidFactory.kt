package com.bsci.medlink.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.util.*

/**
 * 设备 UUID 工厂类
 * 生成并保存设备唯一标识符，确保每次获取相同
 */
class DeviceUuidFactory(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREF_NAME = "device_uuid_prefs"
        private const val KEY_DEVICE_UUID = "device_uuid"
    }

    /**
     * 获取设备 UUID
     * 如果不存在则生成并保存，确保每次获取相同
     */
    fun getDeviceUuid(): String {
        var uuid = prefs.getString(KEY_DEVICE_UUID, null)
        
        if (uuid == null || uuid.isEmpty()) {
            uuid = generateDeviceUuid()
            prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
        }


        return uuid
    }

    /**
     * 生成设备唯一 UUID
     * 基于 Android ID 和设备信息生成
     */
    private fun generateDeviceUuid(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        
        // 如果 Android ID 不可用，使用其他设备信息
        val deviceInfo = if (androidId != null && androidId != "9774d56d682e549c") {
            // 9774d56d682e549c 是模拟器的默认 Android ID
            androidId
        } else {
            // 使用设备序列号和其他信息组合
            val serial = try {
                Build.getSerial()
            } catch (e: Exception) {
                "unknown"
            }
            "${Build.MANUFACTURER}_${Build.MODEL}_${serial}_${System.currentTimeMillis()}"
        }
        
        // 使用 UUID 算法生成唯一标识符
        val uuid = UUID.nameUUIDFromBytes(deviceInfo.toByteArray())
        return uuid.toString()
    }
}

