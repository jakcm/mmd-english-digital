package com.mmd.englishdigital

import android.util.Log
import kotlin.math.sqrt

/**
 * 以 far-end 为参考的上行门控（仅在 AI 播报期生效）—— 方案 3 的核心。
 *
 * 为什么不用固定阈值：
 *   原始音源下回声与人声电平接近（回声 ~180，人声 250~350），固定阈值无法分离。
 *   但回声强度与"当前正在播放的 far-end 电平"成固定比例（实测衰减约 -27dB，系数≈0.045），
 *   所以判据应随 far-end 动：**门限 = 回声衰减系数 × 当前 far-end 电平 × 倍数**。
 *   超过该门限 → 判为"用户在说话"（放行）；否则判为"AI 自己的回声/静音"（上行送静音）。
 *
 * 配合：
 *   · preRoll：触发瞬间补发此前若干帧，避免切掉字头
 *   · hold：从"响"回到"不响"后继续放行一段时间，避免句中停顿被切断
 *   · 门控**之后**接 [SimpleAgc]（只放大被判为人声的帧），避免回声驱动 AGC
 *
 * 非播报期：不做门控，AGC 后全量透传（不损失正常对话）。
 */
class FarEndGate(
    private val echoGain: Double = 0.045,   // 回声衰减系数（-27dB）；偏保守可调
    private val ratio: Double = 1.6,        // 门限倍数：mic > echoGain*fe*ratio 判为人声
    preRollMs: Int = 300,
    holdMs: Int = 500,
    private val silentFrame: ByteArray,
    private val agc: SimpleAgc,
) {
    private val frameMs = 20
    private val preRollMax = (preRollMs / frameMs).coerceAtLeast(0)
    private val holdFrames = (holdMs / frameMs).coerceAtLeast(0)

    /** 当前 far-end（正在播放的 AI 音频）电平，int16 RMS，带衰减保持。由播放回调更新。 */
    @Volatile var farEndLevel: Double = 0.0

    private val preRoll = ArrayDeque<ByteArray>()
    private var holdLeft = 0
    private var lastLog = 0L

    @Volatile var passCount = 0L
        private set
    @Volatile var muteCount = 0L
        private set

    fun process(frame: ByteArray, active: Boolean): List<ByteArray> {
        if (!active) {
            if (preRoll.isNotEmpty()) preRoll.clear()
            holdLeft = 0
            passCount++
            return listOf(agc.process(frame))
        }
        if (frame.size != silentFrame.size) return listOf(agc.process(frame))

        val level = rms(frame)
        val fe = farEndLevel
        val threshold = (echoGain * fe * ratio).coerceAtLeast(80.0)   // 最低门限，避免 far-end 静音时乱开
        if (level >= threshold) {
            val out = ArrayList<ByteArray>(preRoll.size + 1)
            while (preRoll.isNotEmpty()) out.add(agc.process(preRoll.removeFirst()))
            out.add(agc.process(frame))
            holdLeft = holdFrames
            passCount++
            val now = System.currentTimeMillis()
            if (now - lastLog > 1000) {
                lastLog = now
                Log.i(TAG, "门控: 放行 mic=${level.toInt()} 门限=${threshold.toInt()} (fe=${fe.toInt()}) 补发=${out.size - 1}帧 增益=${"%.1f".format(agc.currentGain())}")
            }
            return out
        }
        if (holdLeft > 0) {
            holdLeft--
            passCount++
            return listOf(agc.process(frame))
        }
        preRoll.addLast(frame.copyOf())
        while (preRoll.size > preRollMax) preRoll.removeFirst()
        muteCount++
        return listOf(silentFrame)
    }

    fun reset() { preRoll.clear(); holdLeft = 0 }

    fun stats(): String = "放行=${passCount} 静音=${muteCount} 增益=${"%.1f".format(agc.currentGain())}"

    private fun rms(f: ByteArray): Double {
        if (f.size < 2) return 0.0
        var s = 0.0
        var i = 0
        while (i + 1 < f.size) {
            val v = ((f[i + 1].toInt() shl 8) or (f[i].toInt() and 0xFF)).toShort().toInt()
            s += v.toDouble() * v
            i += 2
        }
        return sqrt(s / (f.size / 2))
    }

    companion object {
        private const val TAG = "MMD-English"
    }
}