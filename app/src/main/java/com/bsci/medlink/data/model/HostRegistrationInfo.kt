package com.bsci.medlink.data.model

/**
 * HOST 注册信息
 */
data class HostRegistrationInfo(
    val hospital: String,      // 医院名称
    val department: String,    // 科室
    val equipment: String? = null, // 设备类型
    val description: String? = null // 描述信息
)

