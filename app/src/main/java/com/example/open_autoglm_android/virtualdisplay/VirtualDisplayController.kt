package com.example.open_autoglm_android.virtualdisplay

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ai.assistance.showerclient.ShowerController
import com.ai.assistance.showerclient.ShowerServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VirtualDisplayController(private val application: Application) {

    suspend fun ensureReady(
        width: Int,
        height: Int,
        dpi: Int,
        bitrateKbps: Int = 8000,
    ): Boolean {
        val context = application.applicationContext
        val serverOk = ShowerServerManager.ensureServerStarted(context)
        if (!serverOk) return false
        return ShowerController.ensureDisplay(
            context = context,
            width = width,
            height = height,
            dpi = dpi,
            bitrateKbps = bitrateKbps,
        )
    }

    fun getDisplayId(): Int? = ShowerController.getDisplayId()

    fun getVideoSize(): Pair<Int, Int>? = ShowerController.getVideoSize()

    suspend fun takeScreenshotBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = ShowerController.requestScreenshot() ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}

