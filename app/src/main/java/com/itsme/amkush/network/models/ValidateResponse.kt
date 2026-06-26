package com.itsme.amkush.network.models

import com.google.gson.annotations.SerializedName

data class ValidateResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String,

    @SerializedName("token")
    val token: String? = null,

    @SerializedName("is_trial")
    val isTrial: Boolean = false,

    @SerializedName("is_paid")
    val isPaid: Boolean = false,

    @SerializedName("expires_at")
    val expiresAt: String? = null,

    @SerializedName("remaining_devices")
    val remainingDevices: Int = 0,

    @SerializedName("max_devices")
    val maxDevices: Int = 1
)

data class TokenVerifyResponse(
    @SerializedName("valid")
    val valid: Boolean,

    @SerializedName("message")
    val message: String,

    @SerializedName("is_trial")
    val isTrial: Boolean = false,

    @SerializedName("is_paid")
    val isPaid: Boolean = false,

    @SerializedName("expires_at")
    val expiresAt: String? = null
)

data class TrialCheckResponse(
    @SerializedName("available")
    val available: Boolean,

    @SerializedName("message")
    val message: String,

    @SerializedName("expires_at")
    val expiresAt: String? = null
)

data class ErrorResponse(
    @SerializedName("error")
    val error: String,

    @SerializedName("message")
    val message: String
)