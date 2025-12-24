package com.ai.assistance.showerclient

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Optimized H.264 decoder with robust SPS/PPS handling to prevent black screen.
 */
object ShowerVideoRenderer {

    private const val TAG = "ShowerVideoRenderer"

    private val lock = Any()

    @Volatile
    private var decoder: MediaCodec? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var csd0: ByteArray? = null

    @Volatile
    private var csd1: ByteArray? = null

    private val pendingFrames = mutableListOf<ByteArray>()

    @Volatile
    private var width: Int = 0

    @Volatile
    private var height: Int = 0

    @Volatile
    private var warnedNoSurface: Boolean = false

    fun attach(surface: Surface, videoWidth: Int, videoHeight: Int) {
        synchronized(lock) {
            this.surface = surface
            this.width = videoWidth
            this.height = videoHeight
            warnedNoSurface = false
            releaseDecoderLocked()
            // 重新挂载时清空之前的配置，强制重新获取 SPS/PPS 以防参数变化
            csd0 = null
            csd1 = null
            pendingFrames.clear()
        }
    }

    fun detach() {
        synchronized(lock) {
            releaseDecoderLocked()
            surface = null
            pendingFrames.clear()
            warnedNoSurface = false
        }
    }

    private fun releaseDecoderLocked() {
        val dec = decoder
        decoder = null
        if (dec != null) {
            try {
                dec.stop()
            } catch (_: Exception) {}
            try {
                dec.release()
            } catch (_: Exception) {}
        }
    }

    fun onFrame(data: ByteArray) {
        synchronized(lock) {
            if (surface == null || width <= 0 || height <= 0) return

            val packet = maybeAvccToAnnexb(data)
            val nalType = findNalUnitType(packet)

            // 如果收到新的配置帧，且与之前不同，则重置解码器
            if (nalType == 7) {
                if (csd0 == null || !csd0!!.contentEquals(packet)) {
                    Log.d(TAG, "New SPS detected, resetting decoder")
                    csd0 = packet
                    releaseDecoderLocked()
                }
            } else if (nalType == 8) {
                if (csd1 == null || !csd1!!.contentEquals(packet)) {
                    csd1 = packet
                }
            }

            if (decoder == null) {
                if (nalType != 7 && nalType != 8) {
                    pendingFrames.add(packet)
                }
                if (csd0 != null && csd1 != null) {
                    initDecoderLocked()
                    val frames = pendingFrames.toList()
                    pendingFrames.clear()
                    frames.forEach { queueFrameToDecoder(it) }
                }
                return
            }

            queueFrameToDecoder(packet)
        }
    }

    private fun findNalUnitType(packet: ByteArray): Int {
        var offset = -1
        for (i in 0 until packet.size - 3) {
            if (packet[i] == 0.toByte() && packet[i + 1] == 0.toByte()) {
                if (packet[i + 2] == 1.toByte()) {
                    offset = i + 3
                    break
                } else if (i + 3 < packet.size && packet[i + 2] == 0.toByte() && packet[i + 3] == 1.toByte()) {
                    offset = i + 4
                    break
                }
            }
        }
        return if (offset != -1 && offset < packet.size) (packet[offset].toInt() and 0x1F) else -1
    }

    private fun queueFrameToDecoder(packet: ByteArray) {
        synchronized(lock) {
            val dec = decoder ?: return
            try {
                val inIndex = dec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    dec.getInputBuffer(inIndex)?.apply {
                        clear()
                        put(packet)
                        dec.queueInputBuffer(inIndex, 0, packet.size, System.nanoTime() / 1000, 0)
                    }
                }

                val bufferInfo = BufferInfo()
                var outIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
                while (outIndex >= 0) {
                    dec.releaseOutputBuffer(outIndex, true)
                    outIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decoder error", e)
                releaseDecoderLocked()
            }
        }
    }

    private fun maybeAvccToAnnexb(packet: ByteArray): ByteArray {
        if (packet.size >= 4) {
            val b0 = packet[0].toInt() and 0xFF
            val b1 = packet[1].toInt() and 0xFF
            val b2 = packet[2].toInt() and 0xFF
            val b3 = packet[3].toInt() and 0xFF
            if (b0 == 0 && b1 == 0 && ((b2 == 0 && b3 == 1) || b2 == 1)) return packet
        }
        val out = ByteArrayOutputStream()
        var i = 0
        val n = packet.size
        while (i + 4 <= n) {
            val nalLen = ((packet[i].toInt() and 0xFF) shl 24) or
                    ((packet[i + 1].toInt() and 0xFF) shl 16) or
                    ((packet[i + 2].toInt() and 0xFF) shl 8) or
                    (packet[i + 3].toInt() and 0xFF)
            i += 4
            if (nalLen <= 0 || i + nalLen > n) return packet
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(packet, i, nalLen)
            i += nalLen
        }
        return out.toByteArray()
    }

    suspend fun captureCurrentFramePng(): ByteArray? {
        val s: Surface; val w: Int; val h: Int
        synchronized(lock) {
            val localSurface = surface ?: return null
            s = localSurface; w = width; h = height
        }
        return withContext(Dispatchers.Main) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            suspendCancellableCoroutine { cont ->
                PixelCopy.request(s, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        cont.resume(baos.toByteArray())
                    } else cont.resume(null)
                    bitmap.recycle()
                }, Handler(Looper.getMainLooper()))
            }
        }
    }

    private fun initDecoderLocked() {
        val s = surface ?: return
        val localCsd0 = csd0 ?: return
        val localCsd1 = csd1 ?: return
        try {
            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(maybeAvccToAnnexb(localCsd0)))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(maybeAvccToAnnexb(localCsd1)))
            val dec = MediaCodec.createDecoderByType("video/avc")
            dec.configure(format, s, null, 0)
            dec.start()
            decoder = dec
        } catch (e: Exception) {
            Log.e(TAG, "initDecoder failed", e)
        }
    }
}
