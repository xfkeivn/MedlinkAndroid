package com.bsci.medlink.data.model

/**
 * 参会账号响应（RequestMeetingAccount）
 */
data class MeetingAccountResponse(
    val success: Boolean,
    val isEnabled: Boolean,
    val message: String? = null,
    val hostInfo: HostInfo? = null
)

