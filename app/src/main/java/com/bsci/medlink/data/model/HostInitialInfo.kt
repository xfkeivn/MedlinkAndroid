package com.bsci.medlink.data.model

/**
 * 医院信息
 */
data class Hospital(
    val id: Int,
    val english_name: String,
    val chinese_name: String
)

/**
 * 科室信息
 */
data class Department(
    val id: Int,
    val english_name: String,
    val chinese_name: String
)

/**
 * 设备信息
 */
data class Equipment(
    val id: Int,
    val name: String
)

/**
 * 初始信息响应
 */
data class HostInitialInfoResponse(
    val success: Boolean,
    val hospitals: List<Hospital> = emptyList(),
    val departments: List<Department> = emptyList(),
    val equipments: List<Equipment> = emptyList(),
    val message: String? = null
)

