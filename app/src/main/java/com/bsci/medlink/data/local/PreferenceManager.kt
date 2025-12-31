package com.bsci.medlink.data.local

import android.content.Context
import android.content.SharedPreferences
import com.bsci.medlink.data.model.UserInfo

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "medlink_prefs"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_REGION = "region"
        private const val KEY_TOKEN = "token"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_HOST_REGISTERED = "host_registered"
        private const val KEY_LOGIN_VERIFIED = "login_verified" // 登录验证状态
        private const val KEY_HOSPITAL = "hospital"
        private const val KEY_DEPARTMENT = "department"
        private const val KEY_LOCATION = "location"
        private const val KEY_EQUIPMENT = "equipment"
        private const val KEY_REGISTER_DATE = "register_date"
    }

    fun saveLoginInfo(username: String, password: String, rememberMe: Boolean) {
        prefs.edit().apply {
            putString(KEY_USERNAME, username)
            if (rememberMe) {
                putString(KEY_PASSWORD, password)
            } else {
                remove(KEY_PASSWORD)
            }
            putBoolean(KEY_REMEMBER_ME, rememberMe)
            apply()
        }
    }

    fun getUsername(): String {
        return prefs.getString(KEY_USERNAME, "") ?: ""
    }

    fun getPassword(): String {
        return prefs.getString(KEY_PASSWORD, "") ?: ""
    }

    fun getRememberMe(): Boolean {
        return prefs.getBoolean(KEY_REMEMBER_ME, false)
    }

    fun clearLoginInfo() {
        prefs.edit().apply {
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_REMEMBER_ME)
            apply()
        }
    }

    fun saveUserInfo(userInfo: UserInfo) {
        prefs.edit().apply {
            putString(KEY_USER_ID, userInfo.userId)
            putString(KEY_DEVICE_ID, userInfo.deviceId)
            putString(KEY_REGION, userInfo.region)
            putString(KEY_TOKEN, userInfo.token)
            apply()
        }
    }

    fun getUserInfo(): UserInfo {
        return UserInfo(
            userId = prefs.getString(KEY_USER_ID, "") ?: "",
            deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: "",
            region = prefs.getString(KEY_REGION, "") ?: "",
            token = prefs.getString(KEY_TOKEN, "") ?: ""
        )
    }

    fun getToken(): String {
        return prefs.getString(KEY_TOKEN, "") ?: ""
    }

    fun getDeviceId(): String {
        return prefs.getString(KEY_DEVICE_ID, "") ?: ""
    }

    fun getRegion(): String {
        return prefs.getString(KEY_REGION, "") ?: ""
    }

    fun saveServerIp(ip: String) {
        prefs.edit().putString(KEY_SERVER_IP, ip).apply()
    }

    fun getServerIp(): String {
        return prefs.getString(KEY_SERVER_IP, "") ?: ""
    }

    fun setHostRegistered(registered: Boolean) {
        prefs.edit().putBoolean(KEY_HOST_REGISTERED, registered).apply()
    }

    fun isHostRegistered(): Boolean {
        return prefs.getBoolean(KEY_HOST_REGISTERED, false)
    }

    fun setLoginVerified(verified: Boolean) {
        prefs.edit().putBoolean(KEY_LOGIN_VERIFIED, verified).apply()
    }

    fun isLoginVerified(): Boolean {
        return prefs.getBoolean(KEY_LOGIN_VERIFIED, false)
    }
    
    fun saveHostInfo(hospital: String?, department: String?, location: String?, equipment: String?) {
        prefs.edit().apply {
            hospital?.let { putString(KEY_HOSPITAL, it) } ?: remove(KEY_HOSPITAL)
            department?.let { putString(KEY_DEPARTMENT, it) } ?: remove(KEY_DEPARTMENT)
            location?.let { putString(KEY_LOCATION, it) } ?: remove(KEY_LOCATION)
            equipment?.let { putString(KEY_EQUIPMENT, it) } ?: remove(KEY_EQUIPMENT)
            apply()
        }
    }
    
    fun getHospital(): String {
        return prefs.getString(KEY_HOSPITAL, "") ?: ""
    }
    
    fun getDepartment(): String {
        return prefs.getString(KEY_DEPARTMENT, "") ?: ""
    }
    
    fun getLocation(): String {
        return prefs.getString(KEY_LOCATION, "") ?: ""
    }
    
    fun getEquipment(): String {
        return prefs.getString(KEY_EQUIPMENT, "") ?: ""
    }
    
    fun saveRegisterDate(date: String?) {
        prefs.edit().apply {
            date?.let { putString(KEY_REGISTER_DATE, it) } ?: remove(KEY_REGISTER_DATE)
            apply()
        }
    }
    
    fun getRegisterDate(): String {
        return prefs.getString(KEY_REGISTER_DATE, "") ?: ""
    }
}

