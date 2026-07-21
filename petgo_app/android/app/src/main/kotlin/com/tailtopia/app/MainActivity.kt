package com.tailtopia.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    private val hapticsChannel = "petgo/haptics"
    private val mediaChannel = "petgo/media"
    private val galleryRequestCode = 4201
    private var galleryResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, hapticsChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    // 直接驱动 Vibrator：不受系统「触感反馈」开关影响（HapticFeedback 受其管）。
                    "vibrate" -> {
                        val ms = (call.argument<Int>("ms") ?: 40).toLong()
                        vibrate(ms)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, mediaChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "pickGalleryImage" -> pickGalleryImage(result)
                    else -> result.notImplemented()
                }
            }
    }

    private fun pickGalleryImage(result: MethodChannel.Result) {
        if (galleryResult != null) {
            result.error("busy", "Gallery picker is already open", null)
            return
        }
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        if (intent.resolveActivity(packageManager) == null) {
            result.success(null)
            return
        }
        galleryResult = result
        startActivityForResult(intent, galleryRequestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != galleryRequestCode) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val result = galleryResult ?: return
        galleryResult = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            result.success(null)
            return
        }
        try {
            result.success(copyToCache(uri))
        } catch (e: Exception) {
            result.error("copy_failed", e.message, null)
        }
    }

    private fun copyToCache(uri: Uri): String {
        val dir = File(cacheDir, "picked_images").also { it.mkdirs() }
        val file = File(dir, "gallery_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected image" }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.absolutePath
    }

    private fun vibrate(ms: Long) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }
}
