package com.itsme.amkush.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SharedPrefs {
    private const val PREFS_NAME = "facegate_prefs"

    private const val KEY_ACTIVATION_TOKEN = "activation_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_TARGET_APP_NAME = "target_app_name"
    private const val KEY_STREAM_URL = "stream_url"
    private const val KEY_STREAM_TYPE = "stream_type"
    private const val KEY_IS_PAID = "is_paid"
    private const val KEY_IS_TRIAL = "is_trial"
    private const val KEY_TRIAL_EXPIRY = "trial_expiry"
    private const val KEY_DENY_LIST = "deny_list"
    private const val KEY_SPOOF_MODEL = "spoof_model"
    private const val KEY_SPOOF_BRAND = "spoof_brand"
    private const val KEY_SPOOF_MANUFACTURER = "spoof_manufacturer"
    private const val KEY_SPOOF_ANDROID = "spoof_android"
    private const val KEY_SPOOF_BUILD_ID = "spoof_build_id"
    private const val KEY_SPOOF_SECURITY_PATCH = "spoof_security_patch"
    private const val KEY_SPOOF_ACTIVE = "spoof_active"
    private const val KEY_SPOOF_DEVICE_ID = "spoof_device_id"
    private const val KEY_SPOOF_SERIAL = "spoof_serial"
    private const val KEY_LAST_USED_URL = "last_used_url"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isInitialized(): Boolean = prefs != null

    private val p: SharedPreferences?
        get() {
            if (prefs == null) Logger.e("SharedPrefs.init() not called before use")
            return prefs
        }

    // ==================== ACTIVATION ====================

    fun getActivationToken(): String? = p?.getString(KEY_ACTIVATION_TOKEN, null)

    fun setActivationToken(token: String?) {
        p?.edit { putString(KEY_ACTIVATION_TOKEN, token) }
    }

    fun getDeviceId(): String? = p?.getString(KEY_DEVICE_ID, null)

    fun setDeviceId(deviceId: String) {
        p?.edit { putString(KEY_DEVICE_ID, deviceId) }
    }

    fun isPaid(): Boolean = p?.getBoolean(KEY_IS_PAID, false) ?: false

    fun setPaid(isPaid: Boolean) {
        p?.edit { putBoolean(KEY_IS_PAID, isPaid) }
    }

    fun isTrial(): Boolean = p?.getBoolean(KEY_IS_TRIAL, false) ?: false

    fun setTrial(isTrial: Boolean) {
        p?.edit { putBoolean(KEY_IS_TRIAL, isTrial) }
    }

    fun getTrialExpiry(): Long = p?.getLong(KEY_TRIAL_EXPIRY, 0) ?: 0L

    fun setTrialExpiry(expiry: Long) {
        p?.edit { putLong(KEY_TRIAL_EXPIRY, expiry) }
    }

    fun clearActivation() {
        p?.edit {
            remove(KEY_ACTIVATION_TOKEN)
            remove(KEY_IS_PAID)
            remove(KEY_IS_TRIAL)
            remove(KEY_TRIAL_EXPIRY)
        }
    }

    // ==================== TARGET APP ====================

    fun getTargetPackage(): String? = p?.getString(KEY_TARGET_PACKAGE, null)

    fun setTargetPackage(packageName: String?) {
        p?.edit { putString(KEY_TARGET_PACKAGE, packageName) }
    }

    fun getTargetAppName(): String? = p?.getString(KEY_TARGET_APP_NAME, null)

    fun setTargetAppName(appName: String?) {
        p?.edit { putString(KEY_TARGET_APP_NAME, appName) }
    }

    fun clearTarget() {
        p?.edit {
            remove(KEY_TARGET_PACKAGE)
            remove(KEY_TARGET_APP_NAME)
        }
    }

    // ==================== STREAM ====================

    fun getStreamUrl(): String? = p?.getString(KEY_STREAM_URL, null)

    fun setStreamUrl(url: String?) {
        p?.edit { putString(KEY_STREAM_URL, url) }
    }

    fun getStreamType(): String? = p?.getString(KEY_STREAM_TYPE, null)

    fun setStreamType(type: String?) {
        p?.edit { putString(KEY_STREAM_TYPE, type) }
    }

    fun getLastUsedUrl(): String? = p?.getString(KEY_LAST_USED_URL, null)

    fun setLastUsedUrl(url: String?) {
        p?.edit { putString(KEY_LAST_USED_URL, url) }
    }

    // ==================== DENY LIST ====================

    fun getDenyList(): Set<String> = p?.getStringSet(KEY_DENY_LIST, emptySet()) ?: emptySet()

    fun setDenyList(denyList: Set<String>) {
        p?.edit { putStringSet(KEY_DENY_LIST, denyList) }
    }

    fun addToDenyList(packageName: String) {
        val current = getDenyList().toMutableSet()
        current.add(packageName)
        setDenyList(current)
    }

    fun removeFromDenyList(packageName: String) {
        val current = getDenyList().toMutableSet()
        current.remove(packageName)
        setDenyList(current)
    }

    fun isDenied(packageName: String): Boolean = getDenyList().contains(packageName)

    // ==================== SPOOFING ====================

    fun getSpoofModel(): String? = p?.getString(KEY_SPOOF_MODEL, null)

    fun setSpoofModel(model: String?) {
        p?.edit { putString(KEY_SPOOF_MODEL, model) }
    }

    fun getSpoofBrand(): String? = p?.getString(KEY_SPOOF_BRAND, null)

    fun setSpoofBrand(brand: String?) {
        p?.edit { putString(KEY_SPOOF_BRAND, brand) }
    }

    fun getSpoofManufacturer(): String? = p?.getString(KEY_SPOOF_MANUFACTURER, null)

    fun setSpoofManufacturer(manufacturer: String?) {
        p?.edit { putString(KEY_SPOOF_MANUFACTURER, manufacturer) }
    }

    fun getSpoofAndroid(): String? = p?.getString(KEY_SPOOF_ANDROID, null)

    fun setSpoofAndroid(androidVersion: String?) {
        p?.edit { putString(KEY_SPOOF_ANDROID, androidVersion) }
    }

    fun getSpoofBuildId(): String? = p?.getString(KEY_SPOOF_BUILD_ID, null)

    fun setSpoofBuildId(buildId: String?) {
        p?.edit { putString(KEY_SPOOF_BUILD_ID, buildId) }
    }

    fun getSpoofSecurityPatch(): String? = p?.getString(KEY_SPOOF_SECURITY_PATCH, null)

    fun setSpoofSecurityPatch(patch: String?) {
        p?.edit { putString(KEY_SPOOF_SECURITY_PATCH, patch) }
    }

    fun isSpoofActive(): Boolean = p?.getBoolean(KEY_SPOOF_ACTIVE, false) ?: false

    fun setSpoofActive(active: Boolean) {
        p?.edit { putBoolean(KEY_SPOOF_ACTIVE, active) }
    }

    fun getSpoofDeviceId(): String? = p?.getString(KEY_SPOOF_DEVICE_ID, null)

    fun setSpoofDeviceId(deviceId: String?) {
        p?.edit { putString(KEY_SPOOF_DEVICE_ID, deviceId) }
    }

    fun getSpoofSerial(): String? = p?.getString(KEY_SPOOF_SERIAL, null)

    fun setSpoofSerial(serial: String?) {
        p?.edit { putString(KEY_SPOOF_SERIAL, serial) }
    }

    // ==================== CLEAR ALL ====================

    fun clearAll() {
        p?.edit { clear() }
    }
}
