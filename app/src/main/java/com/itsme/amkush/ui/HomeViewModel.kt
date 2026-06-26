package com.itsme.amkush.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsme.amkush.model.AppInfo
import com.itsme.amkush.network.ApiClient
import com.itsme.amkush.network.Response
import com.itsme.amkush.network.models.TokenRequest
import com.itsme.amkush.utils.DeviceUtils
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val context: Context) : ViewModel() {

    private val _tokenStatus = MutableLiveData<Response<Boolean>>(Response.Loading)
    val tokenStatus: LiveData<Response<Boolean>> = _tokenStatus

    private val _targetApp = MutableLiveData<AppInfo?>()
    val targetApp: LiveData<AppInfo?> = _targetApp

    init {
        loadSavedTarget()
        checkTokenStatus()
    }

    private fun loadSavedTarget() {
        val packageName = SharedPrefs.getTargetPackage()
        val appName = SharedPrefs.getTargetAppName()
        if (!packageName.isNullOrEmpty() && !appName.isNullOrEmpty()) {
            _targetApp.value = AppInfo(packageName, appName)
        }
    }

    fun setTargetApp(app: AppInfo) {
        _targetApp.value = app
        SharedPrefs.setTargetPackage(app.packageName)
        SharedPrefs.setTargetAppName(app.appName)
    }

    private fun checkTokenStatus() {
        val token = SharedPrefs.getActivationToken()
        if (token.isNullOrEmpty()) {
            _tokenStatus.value = Response.Error("No token found")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiService = ApiClient.getApiService()
                val deviceId = DeviceUtils.getDeviceId(context)
                val request = TokenRequest(token, deviceId)
                val response = apiService.verifyToken(request).execute()

                // Use postValue (thread-safe) instead of value= (main-thread only).
                // The coroutine runs on Dispatchers.IO so .value= would throw
                // IllegalStateException ("Cannot invoke setValue on a background thread").
                if (response.isSuccessful && response.body()?.valid == true) {
                    _tokenStatus.postValue(Response.Success(true))
                } else {
                    SharedPrefs.clearActivation()
                    _tokenStatus.postValue(Response.Error("Invalid token"))
                }
            } catch (e: Exception) {
                Logger.e("Error checking token", e)
                _tokenStatus.postValue(Response.Error("Network error"))
            }
        }
    }

    fun clearTarget() {
        _targetApp.value = null
        SharedPrefs.clearTarget()
    }
}