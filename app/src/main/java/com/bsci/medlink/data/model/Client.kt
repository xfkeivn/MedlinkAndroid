package com.bsci.medlink.data.model

data class Client(
    val id: String,
    val name: String,
    val telephone: String? = null,
    val avatar: String? = null, // base64 编码的图片或本地路径
    val isOnline: Boolean = false
)

