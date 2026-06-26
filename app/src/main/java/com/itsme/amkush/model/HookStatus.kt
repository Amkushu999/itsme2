package com.itsme.amkush.model

data class HookStatus(
    val id: String,
    val name: String,
    val category: String,
    val isActive: Boolean
)

object HookStatusRegistry {
    fun getAllHooks(): List<HookStatus> {
        return listOf(
            // Anti-Detection Hooks
            HookStatus("emulator", "Emulator Bypass", "Anti-Detection", true),
            HookStatus("root", "Root Bypass", "Anti-Detection", true),
            HookStatus("xposed", "Xposed Bypass", "Anti-Detection", true),
            HookStatus("cloner", "Cloner Bypass", "Anti-Detection", true),

            // EXIF Spoofing
            HookStatus("exif", "EXIF Spoofing", "Spoofing", true),

            // Device Spoofing
            HookStatus("build", "Build Spoofing", "Spoofing", true),
            HookStatus("deviceid", "Device ID Spoofing", "Spoofing", true),
            HookStatus("serial", "Serial Spoofing", "Spoofing", true),

            // Deny List
            HookStatus("denylist", "Deny List Filtering", "Privacy", true),

            // SELinux
            HookStatus("selinux", "SELinux Bypass", "System", true)
        )
    }
}