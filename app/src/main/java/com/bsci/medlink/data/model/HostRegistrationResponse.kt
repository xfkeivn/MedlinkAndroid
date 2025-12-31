package com.bsci.medlink.data.model

/**
 * HOST 注册响应
 */
data class HostRegistrationResponse(
    val success: Boolean,
    val isRegistered: Boolean,  // 是否已注册
    val message: String? = null,
    val hostInfo: HostInfo? = null
)

data class HostInfo(
    val uuid: String,
    val hospital: String? = null,
    val department: String? = null,
    val location: String? = null,
    val equipment: String? = null,
    val createTime: String? = null // 注册日期，格式：2025-12-30T09:57:14.705702
)

