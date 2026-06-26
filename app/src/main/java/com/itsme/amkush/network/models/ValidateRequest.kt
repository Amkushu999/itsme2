package com.itsme.amkush.network.models

import com.google.gson.annotations.SerializedName

data class ValidateRequest(
    @SerializedName("key")
    val key: String,

    @SerializedName("device_id")
    val deviceId: String,

    @SerializedName("wifi_ip")
    val wifiIp: String? = null,

    @SerializedName("app_version")
    val appVersion: String? = null
)

data class TokenRequest(
    @SerializedName("token")
    val token: String,

    @SerializedName("device_id")
    val deviceId: String
)