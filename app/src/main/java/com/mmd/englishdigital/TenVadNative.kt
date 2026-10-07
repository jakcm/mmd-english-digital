package com.mmd.englishdigital


/**
 * TEN VAD 的 JNI 绑定（TEN Framework / Agora，Apache-2.0）。
 *
 * 为何用它替代 WebRTC VAD：实测同一段噪音录音（应用实际上行信号）
 *   WebRTC VAD：低噪档判为"人声" 67%，中噪档 96%  → 噪音必然误触发打断
 *   TEN  VAD：低噪档 5%，中噪档 17%，真人语音 87~99%（概率 0.76~0.93）
 * 体积 532KB（仅依赖 libc/libm/libdl），低端安卓机 RTF 0.057。
 *
 * 注意：本类纯本地，不产生任何网络交互。
 */
object TenVadNative {

    private const val TAG = "MMD-English"
    private val available: Boolean = try {
        System.loadLibrary("ten_vad_jni")
        true
    } catch (t: Throwable) {
        L.w(TAG, "TEN VAD 不可用（${t.message}）→ 本地打断将回退为不可用")
        false
    }

    val isAvailable: Boolean get() = available

    /** 返回非 0 句柄；失败返回 0。hopSize=256(16ms@16k)，threshold 一般 0.4~0.5 */
    external fun nativeCreate(hopSize: Int, threshold: Float): Long

    /** 返回 float[2] = {概率, flag}；失败返回 null */
    external fun nativeProcess(handle: Long, audio: ShortArray): FloatArray?

    external fun nativeDestroy(handle: Long)

    external fun nativeVersion(): String
}
