package com.itsme.amkush.network

import com.itsme.amkush.network.models.*
import retrofit2.Call
import retrofit2.http.*

interface ApiService {

    @POST("api/validate_key")
    fun validateKey(
        @Body request: ValidateRequest
    ): Call<ValidateResponse>

    @POST("api/verify_token")
    fun verifyToken(
        @Body request: TokenRequest
    ): Call<TokenVerifyResponse>

    @POST("api/check_trial")
    fun checkTrial(
        @Body request: Map<String, String>
    ): Call<TrialCheckResponse>

    @POST("api/activate_trial")
    fun activateTrial(
        @Body request: ValidateRequest
    ): Call<ValidateResponse>

    @GET("api/key_status/{key}")
    fun getKeyStatus(
        @Path("key") key: String
    ): Call<KeyStatusResponse>
}

data class KeyStatusResponse(
    val key: String,
    val max_devices: Int,
    val used_count: Int,
    val remaining: Int,
    val status: String,
    val devices: List<String>? = null
)