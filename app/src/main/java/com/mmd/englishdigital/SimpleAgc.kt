package com.mmd.englishdigital

import kotlin.math.sqrt

/**
 * 简易软件 AGC —— 补偿"原始音源无 AGC"导致的电平过低问题。
 *
 * 实测（原始 MIC）：播报前用户人声 RMS 仅 150~350（-46dBFS 量级），服务端虽能识别但余量很小；
 * 而 VOICE_COMMUNICATION 靠 HAL 的 AGC 能到 1000~6800。故自建 AGC 把电平拉到可用区间。
 *
 * 注意：AGC **必须放在门控之后**（只处理"已判定为人声"的帧），
 * 否则 AI 播放期的回声会把增益压下去、并在播放结束后泵动，反而伤害人声。
 */
class SimpleAgc(
    private val targetRms: Double = 2200.0,
    private val minGain: Double = 1.0,
    private val maxGain: Double = 30.0,
) {
    @Volatile private var gain = 4.0

    /** 处理一帧 16k/int16 PCM，返回放大后的新帧（不修改入参）。 */
    fun process(frame: ByteArray): ByteArray {
        val n = frame.size / 2
        if (n == 0) return frame
        val v = IntArray(n)
        var s = 0.0
        for (i in 0 until n) {
            val x = ((frame[2 * i + 1].toInt() shl 8) or (frame[2 * i].toInt() and 0xFF)).toShort().toInt()
            v[i] = x
            s += x.toDouble() * x
        }
        val rms = sqrt(s / n)
        if (rms > 15.0) {          // 有明显信号才调整增益（避免纯静音把增益推到顶）
            val desired = (targetRms / rms).coerceIn(minGain, maxGain)
            // 快攻（降增益）慢放（升增益），避免抽吸感
            gain += (desired - gain) * (if (desired < gain) 0.35 else 0.04)
        }
        val out = ByteArray(frame.size)
        for (i in 0 until n) {
            var y = (v[i] * gain).toInt()
            if (y > 32767) y = 32767 else if (y < -32768) y = -32768
            out[2 * i] = (y and 0xFF).toByte()
            out[2 * i + 1] = ((y shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun currentGain(): Double = gain

    fun reset() { gain = 4.0 }
}