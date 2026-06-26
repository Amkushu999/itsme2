package com.itsme.amkush.model

import android.graphics.drawable.Drawable

/**
 * Data class representing an installed application
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val isSystemApp: Boolean = false,
    val isSelected: Boolean = false
) {
    val initials: String
        get() = appName.split(" ")
            .mapNotNull { it.firstOrNull()?.toString() }
            .joinToString("")
            .take(2)
            .uppercase()
            .ifEmpty { appName.take(2).uppercase() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AppInfo
        return packageName == other.packageName
    }

    override fun hashCode(): Int {
        return packageName.hashCode()
    }
}