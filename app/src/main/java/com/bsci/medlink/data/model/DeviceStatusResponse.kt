package com.bsci.medlink.data.model

/**
 * 设备状态响应
 */
data class DeviceStatusResponse(
    val success: Boolean,
    val isEnabled: Boolean,  // 设备是否可用（Result 为 True 表示可用）
    val message: String? = null
)

