package com.mmd.englishdigital

import android.util.Log
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/**
 * 本地语音活动检测（WebRTC VAD GMM）+ 定长重打包。
 *
 * 关键约束：VadWebRTC 在 16kHz 下只接受恰好 320 样本 = 640 字节的帧。
 * 输入是任意长度的 PCM 字节流（AudioRecord 返回值不保证定长），
 * 内部用累加缓冲攒够 640 字节才切一帧去判定，不足的留存到下一轮。
 *
 * 注意：本类纯本地，不产生任何网络/服务端交互。
 */
class LocalVad(private val listener: (isSpeech: Boolean) -> Unit) {

    companion object {
        const val FRAME_BYTES = 640          // 320 samples @16k = 20ms
        const val TAG = "MMD-English"
    }

    private val vad = VadWebRTC(
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_320,
        mode = Mode.AGGRESSIVE,
        silenceDurationMs = 300,
        speechDurationMs = 300
    )

    private val acc = java.io.ByteArrayOutputStream(2048)
    private val frame = ByteArray(FRAME_BYTES)

    /** 喂入任意长度 PCM；每攒满 640 字节判定一次。 */
    @Synchronized
    fun feed(data: ByteArray, len: Int = data.size) {
        if (len <= 0) return
        acc.write(data, 0, len)
        while (acc.size() >= FRAME_BYTES) {
            val buf = acc.toByteArray()
            System.arraycopy(buf, 0, frame, 0, FRAME_BYTES)
            // 保留余量
            val rest = buf.copyOfRange(FRAME_BYTES, buf.size)
            acc.reset()
            if (rest.isNotEmpty()) acc.write(rest, 0, rest.size)
            // 严格帧长断言（排障用）
            if (frame.size != FRAME_BYTES) Log.e(TAG, "VAD bad frame size=${frame.size}")
            val speech = try {
                vad.isSpeech(frame)
            } catch (e: Exception) {
                Log.w(TAG, "VAD isSpeech failed: ${e.message}"); false
            }
            listener(speech)
        }
    }

    /** 停止时丢弃不足一帧的余量，避免污染下一轮。 */
    @Synchronized
    fun flush() {
        acc.reset()
    }

    @Synchronized
    fun release() {
        try { vad.close() } catch (_: Exception) {}
        acc.reset()
    }
}
