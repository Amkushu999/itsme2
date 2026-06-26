package com.itsme.amkush.hooks

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object IntentCaptureHooks {

    private const val REQUEST_IMAGE_CAPTURE = 1
    private const val REQUEST_VIDEO_CAPTURE = 2
    private const val RESULT_OK = -1

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!AppState.isHookingActive) {
            return
        }

        try {
            hookActivityOnActivityResult(lpparam)
            hookActivityResultLauncher(lpparam)
            Logger.d("Intent capture hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Intent capture hooks failed", e)
        }
    }

    private fun hookActivityOnActivityResult(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val activityClass = XposedHelpers.findClass(
                "android.app.Activity",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                activityClass,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val requestCode = param.args[0] as? Int ?: return
                        val resultCode = param.args[1] as? Int ?: return
                        val data = param.args[2] as? Intent ?: return

                        if (resultCode == RESULT_OK) {
                            when (requestCode) {
                                REQUEST_IMAGE_CAPTURE -> replaceImageResult(data)
                                REQUEST_VIDEO_CAPTURE -> replaceVideoResult(data)
                                else -> {
                                    if (isImageCaptureResult(data)) {
                                        replaceImageResult(data)
                                    } else if (isVideoCaptureResult(data)) {
                                        replaceVideoResult(data)
                                    }
                                }
                            }
                        }
                    }
                }
            )

            Logger.d("Activity.onActivityResult hook installed")

        } catch (e: Throwable) {
            Logger.e("Activity.onActivityResult hook failed", e)
        }
    }

    private fun hookActivityResultLauncher(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val launcherClass = XposedHelpers.findClass(
                "androidx.activity.result.ActivityResultLauncher",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                launcherClass,
                "launch",
                Any::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logger.d("ActivityResultLauncher.launch called")
                    }
                }
            )

            try {
                val callbackClass = XposedHelpers.findClass(
                    "androidx.activity.result.ActivityResultCallback",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    callbackClass,
                    "onActivityResult",
                    XposedHelpers.findClass("androidx.activity.result.ActivityResult", lpparam.classLoader),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val result = param.args[0]
                            if (result != null) {
                                try {
                                    val resultCode = result.javaClass.getMethod("getResultCode").invoke(result) as? Int
                                    val data = result.javaClass.getMethod("getData").invoke(result) as? Intent

                                    if (resultCode == RESULT_OK && data != null) {
                                        if (isImageCaptureResult(data)) {
                                            replaceImageResult(data)
                                            Logger.d("Replaced image in ActivityResultCallback")
                                        } else if (isVideoCaptureResult(data)) {
                                            replaceVideoResult(data)
                                            Logger.d("Replaced video in ActivityResultCallback")
                                        }
                                    }
                                } catch (e: Throwable) {
                                    Logger.e("Failed to process ActivityResultCallback", e)
                                }
                            }
                        }
                    }
                )
                Logger.d("ActivityResultCallback hook installed")
            } catch (e: Throwable) {
                Logger.d("ActivityResultCallback hook not available")
            }

        } catch (e: Throwable) {
            Logger.d("ActivityResultLauncher hook not available")
        }
    }

    private fun replaceImageResult(data: Intent) {
        try {
            // Check if user has uploaded media
            val mediaUri = SharedPrefs.getLastUsedUrl()
            if (mediaUri.isNullOrEmpty()) {
                Logger.d("No media uploaded, skipping image replacement")
                return
            }

            val context = AppState.context
            if (context == null) {
                Logger.e("Context not available for image replacement")
                return
            }

            // Try to get the uploaded media as a Bitmap
            val uri = Uri.parse(mediaUri)
            val mimeType = context.contentResolver.getType(uri)

            if (mimeType?.startsWith("image/") == true) {
                // User uploaded an image - use it directly
                replaceImageWithUploadedImage(data, context, uri)
            } else if (mimeType?.startsWith("video/") == true) {
                // User uploaded a video - extract first frame
                replaceImageWithVideoFrame(data, context, uri)
            } else {
                Logger.d("Uploaded media is not image or video: $mimeType")
            }

        } catch (e: Throwable) {
            Logger.e("Failed to replace image result", e)
        }
    }

    private fun replaceImageWithUploadedImage(data: Intent, context: Context, uri: Uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                Logger.e("Failed to decode uploaded image")
                return
            }

            // Case 1: Thumbnail mode (data has "data" extra)
            if (data.hasExtra("data")) {
                // Create thumbnail-sized bitmap
                val thumbnail = Bitmap.createScaledBitmap(bitmap, 800, 600, true)
                data.putExtra("data", thumbnail)
                Logger.d("Replaced image thumbnail with uploaded image")
                return
            }

            // Case 2: Full image mode via EXTRA_OUTPUT
            if (data.hasExtra(MediaStore.EXTRA_OUTPUT)) {
                val outputUri = data.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
                if (outputUri != null) {
                    val outputStream = context.contentResolver.openOutputStream(outputUri)
                        ?: run { Logger.e("openOutputStream returned null for $outputUri"); return }
                    outputStream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    Logger.d("Replaced full image with uploaded image at: $outputUri")
                }
                return
            }

            // Case 3: Data URI mode
            val imageUri = data.data
            if (imageUri != null) {
                val outputStream = context.contentResolver.openOutputStream(imageUri)
                    ?: run { Logger.e("openOutputStream returned null for $imageUri"); return }
                outputStream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                Logger.d("Replaced image data URI with uploaded image at: $imageUri")
            }

        } catch (e: Exception) {
            Logger.e("Failed to replace image with uploaded image", e)
        }
    }

    private fun replaceImageWithVideoFrame(data: Intent, context: Context, uri: Uri) {
        try {
            // Extract first frame from video
            val bitmap = extractFirstFrameFromVideo(context, uri)
            if (bitmap == null) {
                Logger.e("Failed to extract first frame from video")
                return
            }

            // Case 1: Thumbnail mode
            if (data.hasExtra("data")) {
                val thumbnail = Bitmap.createScaledBitmap(bitmap, 800, 600, true)
                data.putExtra("data", thumbnail)
                Logger.d("Replaced image thumbnail with video frame")
                return
            }

            // Case 2: Full image mode via EXTRA_OUTPUT
            if (data.hasExtra(MediaStore.EXTRA_OUTPUT)) {
                val outputUri = data.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
                if (outputUri != null) {
                    val outputStream = context.contentResolver.openOutputStream(outputUri)
                        ?: run { Logger.e("openOutputStream returned null for $outputUri"); return }
                    outputStream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    Logger.d("Replaced full image with video frame at: $outputUri")
                }
                return
            }

            // Case 3: Data URI mode
            val imageUri = data.data
            if (imageUri != null) {
                val outputStream = context.contentResolver.openOutputStream(imageUri)
                    ?: run { Logger.e("openOutputStream returned null for $imageUri"); return }
                outputStream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                Logger.d("Replaced image data URI with video frame at: $imageUri")
            }

        } catch (e: Exception) {
            Logger.e("Failed to replace image with video frame", e)
        }
    }

    private fun extractFirstFrameFromVideo(context: Context, uri: Uri): Bitmap? {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            // Extract first frame at 1 second; fall back to frame 0 if unavailable
            mmr.getFrameAtTime(1000L * 1000L) ?: mmr.getFrameAtTime(0)
        } catch (e: Exception) {
            Logger.e("Failed to extract video frame", e)
            null
        } finally {
            // Always release native resources regardless of success or failure
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    private fun replaceVideoResult(data: Intent) {
        try {
            // Check if user has uploaded media
            val mediaUri = SharedPrefs.getLastUsedUrl()
            if (mediaUri.isNullOrEmpty()) {
                Logger.d("No media uploaded, skipping video replacement")
                return
            }

            val context = AppState.context
            if (context == null) {
                Logger.e("Context not available for video replacement")
                return
            }

            // For video capture, we need a video file to replace with
            // Check if user uploaded a video
            val uri = Uri.parse(mediaUri)
            val mimeType = context.contentResolver.getType(uri)

            if (mimeType?.startsWith("video/") != true) {
                Logger.d("Uploaded media is not a video, skipping video replacement")
                return
            }

            // Case 1: EXTRA_OUTPUT mode
            if (data.hasExtra(MediaStore.EXTRA_OUTPUT)) {
                val outputUri = data.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
                if (outputUri != null) {
                    copyVideoToUri(context, uri, outputUri)
                    Logger.d("Replaced video at: $outputUri")
                }
                return
            }

            // Case 2: Data URI mode
            val videoUri = data.data
            if (videoUri != null) {
                copyVideoToUri(context, uri, videoUri)
                Logger.d("Replaced video data URI at: $videoUri")
            }

        } catch (e: Throwable) {
            Logger.e("Failed to replace video result", e)
        }
    }

    private fun copyVideoToUri(context: Context, sourceUri: Uri, targetUri: Uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
            val outputStream = context.contentResolver.openOutputStream(targetUri)

            if (inputStream == null || outputStream == null) {
                Logger.e("Failed to open streams for video copy")
                inputStream?.close()
                outputStream?.close()
                return
            }

            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            inputStream.close()
            outputStream.close()
            Logger.d("Video copied successfully")

        } catch (e: Exception) {
            Logger.e("Failed to copy video", e)
        }
    }

    private fun isImageCaptureResult(data: Intent): Boolean {
        // Thumbnail mode ("data" extra) or full-resolution mode (EXTRA_OUTPUT) both indicate
        // an image capture. Previously only the thumbnail case was matched, causing full-res
        // image captures to be misrouted through isVideoCaptureResult.
        return data.hasExtra("data") || data.hasExtra(MediaStore.EXTRA_OUTPUT)
    }

    private fun isVideoCaptureResult(data: Intent): Boolean {
        // Only match when there's a data URI but none of the image-capture extras are present.
        // replaceVideoResult() additionally verifies the MIME type is video/* before acting.
        return data.data != null && !data.hasExtra("data") && !data.hasExtra(MediaStore.EXTRA_OUTPUT)
    }
}