package com.bsci.medlink.data.model

data class LoginResponse(
    val success: Boolean,
    val message: String? = null,
    val userInfo: UserInfo? = null
) {
    companion object {
        fun success(userInfo: UserInfo): LoginResponse {
            return LoginResponse(true, null, userInfo)
        }

        fun failure(message: String): LoginResponse {
            return LoginResponse(false, message, null)
        }
    }
}

