package com.itsme.amkush.model

import android.net.Uri
import java.io.File

/**
 * Configuration for video/stream source
 */
data class StreamConfig(
    val sourceType: SourceType = SourceType.NONE,
    val url: String? = null,
    val localFile: File? = null,
    val localUri: Uri? = null
) {
    enum class SourceType {
        NONE,
        RTSP,
        RTMP,
        HLS,
        HTTP,
        HTTPS,
        UDP,
        RTP,
        SRT,
        MMS,
        FTP,
        LOCAL_VIDEO,
        LOCAL_IMAGE
    }

    fun getDisplayName(): String {
        return when (sourceType) {
            SourceType.NONE -> "No stream configured"
            SourceType.RTSP -> "RTSP: $url"
            SourceType.RTMP -> "RTMP: $url"
            SourceType.HLS -> "HLS: $url"
            SourceType.HTTP -> "HTTP: $url"
            SourceType.HTTPS -> "HTTPS: $url"
            SourceType.UDP -> "UDP: $url"
            SourceType.RTP -> "RTP: $url"
            SourceType.SRT -> "SRT: $url"
            SourceType.MMS -> "MMS: $url"
            SourceType.FTP -> "FTP: $url"
            SourceType.LOCAL_VIDEO -> "Local Video: ${localFile?.name ?: localUri?.lastPathSegment}"
            SourceType.LOCAL_IMAGE -> "Local Image: ${localFile?.name ?: localUri?.lastPathSegment}"
        }
    }

    fun isConfigured(): Boolean {
        return when (sourceType) {
            SourceType.NONE -> false
            SourceType.LOCAL_VIDEO, SourceType.LOCAL_IMAGE -> localFile != null || localUri != null
            else -> !url.isNullOrEmpty()
        }
    }

    companion object {
        fun fromUrl(url: String): StreamConfig {
            val type = when {
                url.startsWith("rtsp://") -> SourceType.RTSP
                url.startsWith("rtmp://") -> SourceType.RTMP
                url.startsWith("http://") -> SourceType.HTTP
                url.startsWith("https://") -> SourceType.HTTPS
                url.startsWith("udp://") -> SourceType.UDP
                url.startsWith("rtp://") -> SourceType.RTP
                url.startsWith("srt://") -> SourceType.SRT
                url.startsWith("mms://") -> SourceType.MMS
                url.startsWith("ftp://") -> SourceType.FTP
                url.endsWith(".m3u8") -> SourceType.HLS
                else -> SourceType.HTTP
            }
            return StreamConfig(sourceType = type, url = url)
        }
    }
}