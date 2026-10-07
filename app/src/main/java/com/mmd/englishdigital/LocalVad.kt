package com.mmd.englishdigital


/**
 * 本地语音活动检测（TEN VAD）+ 定长重打包。
 *
 * 与旧实现（WebRTC VAD GMM）的区别：WebRTC VAD 在噪音下会把噪音大量判为"人声"
 * （实测低噪 67%、中噪 96%），是"环境噪音一增大就被打断"的直接原因；
 * TEN VAD 实测同一信号低噪 5%、中噪 17%，同时真人语音 87~99%。
 *
 * 帧约束：TEN VAD 要求 hop_size 恰好 = 256 样本（16ms @16k）= 512 字节。
 * 输入是任意长度 PCM 字节流，内部攒够 512 字节才切一帧判定。
 *
 * 注意：本类纯本地，不产生任何网络交互。
 */
class LocalVad(private val listener: (probability: Float) -> Unit) {

    companion object {
        const val HOP_SAMPLES = 256              // 16ms @16k，TEN VAD 要求
        const val FRAME_BYTES = HOP_SAMPLES * 2  // 512 字节
        const val TAG = "MMD-English"
    }

    private var handle: Long = 0L
    private val acc = java.io.ByteArrayOutputStream(2048)
    private val samples = ShortArray(HOP_SAMPLES)

    init {
        if (TenVadNative.isAvailable) {
            // 阈值 0.40：介于"噪音 0.16~0.26"与"语音 0.41~0.93"之间
            handle = TenVadNative.nativeCreate(HOP_SAMPLES, 0.40f)
            if (handle != 0L) {
                L.i(TAG, "TEN VAD 就绪 version=${TenVadNative.nativeVersion()} hop=$HOP_SAMPLES thr=0.40")
            } else {
                L.e(TAG, "TEN VAD nativeCreate 失败")
            }
        }
    }

    /** 喂入任意长度 PCM；每攒满 512 字节（256 样本）判定一次，回调语音概率。 */
    @Synchronized
    fun feed(data: ByteArray, len: Int = data.size) {
        if (handle == 0L || len <= 0) return
        acc.write(data, 0, len)
        while (acc.size() >= FRAME_BYTES) {
            val buf = acc.toByteArray()
            for (i in 0 until HOP_SAMPLES) {
                samples[i] = ((buf[i * 2].toInt() and 0xFF) or (buf[i * 2 + 1].toInt() shl 8)).toShort()
            }
            val rest = buf.copyOfRange(FRAME_BYTES, buf.size)
            acc.reset()
            if (rest.isNotEmpty()) acc.write(rest, 0, rest.size)
            val p = try {
                TenVadNative.nativeProcess(handle, samples)?.getOrNull(0) ?: 0f
            } catch (e: Exception) {
                L.w(TAG, "TEN VAD process 失败: ${e.message}"); 0f
            }
            listener(p)
        }
    }

    /** 停止时丢弃不足一帧的余量，避免污染下一轮。 */
    @Synchronized
    fun flush() {
        acc.reset()
    }

    @Synchronized
    fun release() {
        if (handle != 0L) {
            try { TenVadNative.nativeDestroy(handle) } catch (_: Exception) {}
            handle = 0L
        }
        acc.reset()
    }
}
