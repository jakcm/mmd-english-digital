package com.mmd.englishdigital

import android.util.Log
import kotlin.math.sqrt

/**
 * 上行门控（Uplink Gate）—— 只在「AI 正在播报」期间生效。
 *
 * 背景（实测数据）：
 *  - 电视无硬件 AEC（0 个音频效果器）；Android 的 VOICE_COMMUNICATION 语音处理链在本地播放时
 *    会把麦克风信号"闷掉"（3k+ 高频占比从播报前 25.3% 掉到播报中 7.8%）→ 人声不可懂 → 服务端认不出。
 *  - 改用原始音源（MIC/MEDIA）后人声恢复可懂（"停一下"被识别为「暂停」+ 关键词命中），
 *    但 AI 自己的回声也随之进入上行（实测 -22dB，RMS 150~300），服务端会把 AI 的话当成用户输入转写。
 *
 * 解法：播报期只放行"人声帧"——
 *  · 帧能量 < 阈值（回声/房间本底）→ 上行发静音帧（保活），不把 AI 的声音喂给服务端
 *  · 帧能量 ≥ 阈值（用户在说话）→ 上行发真实音频，插话照常被服务端识别
 *  · preRoll：触发瞬间把之前若干帧一并补发，避免切掉字头
 *  · hold：从"响"回到"不响"后继续放行一段时间，避免句中停顿时被切断
 *
 * 判据依据：原始音源下 回声(150~300) 与 人声(1400~6800) 相差 10~20dB —— 可靠的能量判据。
 * （此前基于 VOICE_COMMUNICATION 的"频谱门控"失败，是因为那时回声与人声被压成同一副闷声，
 *   频谱不可分；现在前提已变。）
 *
 * 非播报期：全量透传（不损失任何正常对话质量）。
 */
class UplinkGate(
    private val thresholdRms: Double,
    preRollMs: Int = 300,
    holdMs: Int = 500,
    private val silentFrame: ByteArray,
) {
    private val frameMs = 20
    private val preRollMax = (preRollMs / frameMs).coerceAtLeast(0)
    private val holdFrames = (holdMs / frameMs).coerceAtLeast(0)

    private val preRoll = ArrayDeque<ByteArray>()
    private var holdLeft = 0
    private var lastLog = 0L

    @Volatile var passCount = 0L
        private set
    @Volatile var muteCount = 0L
        private set

    /**
     * @param frame  上行音频帧（16k/int16，期望 640B=20ms）
     * @param active 是否处于「AI 播报期」（仅此时启用门控）
     * @return 需要发送的帧序列（透传 1 帧 / 放行 1..N 帧 / 静音保活 1 帧）
     */
    fun process(frame: ByteArray, active: Boolean): List<ByteArray> {
        if (!active) {
            // 非播报期：全量透传，并清空预滚（避免把很久以前的帧补发出去）
            if (preRoll.isNotEmpty()) preRoll.clear()
            holdLeft = 0
            passCount++
            return listOf(frame)
        }
        // 帧长异常时不做处理，直接透传（避免破坏协议）
        if (frame.size != silentFrame.size) return listOf(frame)

        val level = rms(frame)
        if (level >= thresholdRms) {
            val out = ArrayList<ByteArray>(preRoll.size + 1)
            while (preRoll.isNotEmpty()) out.add(preRoll.removeFirst())
            out.add(frame)
            holdLeft = holdFrames
            passCount++
            val now = System.currentTimeMillis()
            if (now - lastLog > 1000) {
                lastLog = now
                Log.i(TAG, "上行门控: 放行 rms=${level.toInt()} 补发预滚=${out.size - 1}帧")
            }
            return out
        }
        if (holdLeft > 0) {
            holdLeft--
            passCount++
            return listOf(frame)
        }
        // 静音保活：把帧缓存进预滚，上行送静音
        preRoll.addLast(frame.copyOf())
        while (preRoll.size > preRollMax) preRoll.removeFirst()
        muteCount++
        return listOf(silentFrame)
    }

    fun reset() {
        preRoll.clear(); holdLeft = 0
    }

    fun stats(): String = "放行=${passCount}帧 静音=${muteCount}帧 阈值=${thresholdRms.toInt()}"

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