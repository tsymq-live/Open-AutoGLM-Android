package com.ai.assistance.showerclient.ui

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ai.assistance.showerclient.ShowerController
import com.ai.assistance.showerclient.ShowerVideoRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SurfaceView used inside a virtual display overlay to render the Shower video stream.
 * Now supports touch passthrough to the virtual display.
 */
open class ShowerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ShowerSurfaceView"
    }

    private var attachJob: Job? = null
    private val touchScope = CoroutineScope(Dispatchers.Main.immediate)

    init {
        holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
        attachJob?.cancel()
        attachJob = CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "surfaceCreated: polling for video size...")
            // 持续轮询直到获取到尺寸或 Job 被取消
            while (isActive) {
                val size = ShowerController.getVideoSize()
                if (size != null) {
                    val (w, h) = size
                    Log.d(TAG, "Attaching renderer with size: ${w}x${h}")
                    ShowerVideoRenderer.attach(holder.surface, w, h)
                    ShowerController.setBinaryHandler { data ->
                        ShowerVideoRenderer.onFrame(data)
                    }
                    break // 获取成功，退出轮询
                }
                delay(500) // 每 0.5 秒检查一次
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        attachJob?.cancel()
        attachJob = null
        ShowerController.setBinaryHandler(null)
        ShowerVideoRenderer.detach()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 获取虚拟屏的实际分辨率
        val videoSize = ShowerController.getVideoSize() ?: return super.onTouchEvent(event)
        val (dW, dH) = videoSize
        
        // 获取当前 View 的尺寸
        val vW = width
        val vH = height
        if (vW <= 0 || vH <= 0) return super.onTouchEvent(event)

        // 坐标映射：View 坐标 -> 虚拟屏坐标
        val targetX = (event.x / vW * dW).toInt()
        val targetY = (event.y / vH * dH).toInt()

        // 异步发送触摸事件到虚拟屏
        touchScope.launch {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ShowerController.touchDown(targetX, targetY)
                }
                MotionEvent.ACTION_MOVE -> {
                    ShowerController.touchMove(targetX, targetY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ShowerController.touchUp(targetX, targetY)
                }
            }
        }
        
        return true // 消费掉事件
    }
}
