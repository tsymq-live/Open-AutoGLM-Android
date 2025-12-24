package com.example.open_autoglm_android.virtualdisplay

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.ai.assistance.showerclient.ShowerController
import com.ai.assistance.showerclient.ShowerServerManager
import com.ai.assistance.showerclient.ShowerVideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class VirtualDisplayController(private val application: Application) {

    companion object {
        private const val TAG = "VirtualDisplayController"
        private const val DEFAULT_LAUNCH_PACKAGE = "com.ai.assistance.operit.desktop"
    }

    suspend fun ensureReady(
        width: Int,
        height: Int,
        dpi: Int,
        bitrateKbps: Int = 8000,
    ): Boolean {
        val context = application.applicationContext
        val serverOk = ShowerServerManager.ensureServerStarted(context)
        if (!serverOk) return false
        
        val displayOk = ShowerController.ensureDisplay(
            context = context,
            width = width,
            height = height,
            dpi = dpi,
            bitrateKbps = bitrateKbps,
        )
        
        if (displayOk) {
            // 自动打开指定的桌面应用以激活虚拟屏
            Log.d(TAG, "Virtual display ready, launching $DEFAULT_LAUNCH_PACKAGE...")
            ShowerController.launchApp(DEFAULT_LAUNCH_PACKAGE)
            // 给系统一点时间来创建图层和开始编码帧
            delay(1500)
        }
        
        return displayOk
    }

    fun getDisplayId(): Int? = ShowerController.getDisplayId()

    fun getVideoSize(): Pair<Int, Int>? = ShowerController.getVideoSize()

    suspend fun takeScreenshotBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        // 尝试多次截图
        repeat(3) { attempt ->
            try {
                // 方案 1: 优先使用 Jar 端 (ShowerController) 截取虚拟屏 (Shell 权限 screencap)
                val bytes = ShowerController.requestScreenshot()
                if (bytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null && !isEntirelyBlack(bitmap)) {
                        Log.d(TAG, "Successfully captured screenshot via Jar method")
                        return@withContext bitmap
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Jar-side screenshot attempt $attempt failed", e)
            }

            try {
                // 方案 2: 备选方案，如果 Jar 端失败，尝试从本地渲染器截取当前帧
                val localBytes = ShowerVideoRenderer.captureCurrentFramePng()
                if (localBytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(localBytes, 0, localBytes.size)
                    if (bitmap != null && !isEntirelyBlack(bitmap)) {
                        Log.d(TAG, "Captured screenshot via local fallback method")
                        return@withContext bitmap
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local fallback screenshot attempt $attempt failed", e)
            }
            
            delay(500) // 失败重试间隔
        }
        null
    }

    /**
     * 检查图片是否全黑
     */
    private fun isEntirelyBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = intArrayOf(
            bitmap.getPixel(w / 4, h / 4),
            bitmap.getPixel(w / 2, h / 2),
            bitmap.getPixel(3 * w / 4, 3 * h / 4)
        )
        return pixels.all { it == android.graphics.Color.BLACK || it == 0 }
    }
}
