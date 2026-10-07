package com.mmd.englishdigital

import android.os.Handler
import android.os.Looper

/**
 * 自动拨号 / 自动挂断状态机（纯本地逻辑，不涉及任何服务端对接）。
 *
 * 计时设计（已与用户确认：A1/B2/C1/D2/E1）：
 *
 * 【闲置计时】—— 语义：「AI 和人都没讲话」才累加
 *   重置事件集合 = {3008 AI开始说, 3011 AI说完, 3012 用户开口, 3013 增量, 3014 用户说完}
 *   任何一方有讲话迹象 → onSpeechSignal() 把闲置计时归零
 *   额外保护：AI 正在播报时（isAiSpeaking()）不判挂断
 *   满足「!isAiSpeaking() && 距上次讲话迹象 ≥ idleSilenceMs」→ 挂断
 *   idleSilenceMs 由设置页「静默挂断秒数」配置（默认 10 秒；0 = 调用方关闭自动模式）
 *
 * 【兜底计时】—— 语义：长时间双方都没有「开始讲话」
 *   重置事件 = {3008, 3012}（AI 开始说 / 用户开口）
 *   距上次「开始讲话」达 FALLBACK_HANGUP_MS（600 秒，固定不可配）→ 挂断
 *
 * 【AI 播报判定】—— E1：仅用宿主维护的 aiResponding（3008 开始 / 3011 结束）
 *
 * ⚠️ 线程约定：所有回调入口一律切回主线程再执行，
 * 因为 requestDial/requestHangup 会直接操作 UI 控件。
 */
class AutoCallController(private val cb: Callbacks) {

    interface Callbacks {
        fun requestDial()
        fun requestHangup()
        fun requestStartMonitoring()
        fun requestStopMonitoring()
        fun onStateChanged(label: String)
        /** AI 是否正在播报（服务端在生成 或 播放器按字节推算仍在播） */
        fun isAiSpeaking(): Boolean
    }

    enum class State { DIALING, IN_CALL, MONITORING }

    companion object {
        /** 默认闲置挂断阈值（秒）——设置页可改 */
        const val DEFAULT_IDLE_SILENCE_SEC = 10
        /** 兜底挂断：距上次「开始讲话」的上限（固定 600 秒，不进配置页 → D2） */
        const val FALLBACK_HANGUP_MS = 600_000L
        const val REDIAL_SPEECH_MS = 500L
        const val TICK_MS = 500L
        const val FRAME_MS = 20L
        const val TAG = "MMD-Auto"
    }

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            onMain { if (value) start() else stopAll() }
        }

    /** 闲置挂断阈值（毫秒）。<=0 表示关闭自动（由宿主置 enabled=false）。 */
    @Volatile
    var idleSilenceMs: Long = DEFAULT_IDLE_SILENCE_SEC * 1000L
        set(value) {
            field = value
            L.i(TAG, "闲置挂断阈值 = ${value}ms")
        }

    @Volatile
    var state: State = State.DIALING
        private set

    private val main = Handler(Looper.getMainLooper())

    /** 最近一次「任何一方有讲话迹象」的时间戳（闲置计时基准） */
    @Volatile private var lastSpeechSignal = 0L
    /** 最近一次「开始讲话」的时间戳（兜底计时基准） */
    @Volatile private var lastActivityStart = 0L
    private var speechRunMs = 0L
    @Volatile private var running = false

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            onTick()
            main.postDelayed(this, TICK_MS)
        }
    }

    fun start() = onMain {
        if (!enabled || running) return@onMain
        running = true
        goDialing()
        main.postDelayed(ticker, TICK_MS)
    }

    fun stopAll() = onMain {
        if (!running) {
            cb.onStateChanged("自动模式已关闭")
            return@onMain
        }
        running = false
        main.removeCallbacks(ticker)
        cb.requestStopMonitoring()
        if (state == State.IN_CALL || state == State.DIALING) cb.requestHangup()
        cb.onStateChanged("自动模式已关闭")
    }

    private fun goDialing() {
        state = State.DIALING
        cb.requestStopMonitoring()   // 先停本地监听，避免与通话采集抢麦
        markSpeechSignal()
        markActivityStart()
        L.i(TAG, "状态 → DIALING（即将连服务端，开始计费）")
        cb.onStateChanged("自动：拨打中…")
        cb.requestDial()
    }

    private fun goMonitoring() {
        state = State.MONITORING
        speechRunMs = 0
        L.i(TAG, "状态 → MONITORING（★已断开服务端，仅本地VAD，零费用）")
        cb.onStateChanged("自动：监听中（说话即重拨）")
        cb.requestStartMonitoring()
    }

    // ---------- 宿主事件 ----------

    fun onCallConnected() = onMain {
        if (!running) return@onMain
        state = State.IN_CALL
        markSpeechSignal()
        markActivityStart()
        L.i(
            TAG,
            "状态 → IN_CALL（已连服务端，开始计费）闲置阈值=${idleSilenceMs}ms 兜底=${FALLBACK_HANGUP_MS}ms"
        )
        cb.onStateChanged("自动：通话中（双方都静默 ${idleSilenceMs / 1000}s 自动挂断）")
    }

    /**
     * ★ 任何一方的「讲话迹象」事件 → 闲置计时归零。
     * 调用点：3008（AI开始说）/ 3011（AI说完）/ 3012（用户开口）/
     *        3013（ASR 增量）/ 3014（用户说完）/ 3010+音频帧（AI播报中）
     */
    fun onSpeechSignal() = onMain { markSpeechSignal() }

    /**
     * ★「开始讲话」事件 → 兜底计时归零。
     * 调用点：3008（AI开始说）/ 3012（用户开口）
     */
    fun onActivityStart() = onMain { markActivityStart() }

    private fun onTick() {
        if (state != State.IN_CALL) return
        val t = System.currentTimeMillis()

        // 闲置：距上次「任何讲话迹象」的时长
        val idle = t - lastSpeechSignal
        // 兜底：距上次「开始讲话」的时长
        val sinceStart = t - lastActivityStart

        // ---- 兜底挂断（固定 600s，不受闲置阈值影响）----
        if (sinceStart >= FALLBACK_HANGUP_MS) {
            L.i(TAG, "兜底：距上次开口 ${sinceStart}ms ≥ ${FALLBACK_HANGUP_MS}ms → 强制挂断")
            cb.onStateChanged("自动：长时间无互动，挂断")
            state = State.DIALING
            cb.requestHangup()
            return
        }

        // ---- 闲置挂断：阈值 <=0 视为关闭（宿主已置 enabled=false，这里再兜一层）----
        if (idleSilenceMs <= 0L) return
        // ★ AI 正在播报（服务端在生成 或 播放器还在播）→ 持续刷新闲置计时并跳过判定。
        //   这样"播报结束的那一刻"闲置计时才从 0 开始，长回复也不会被提前挂断。
        if (cb.isAiSpeaking()) {
            markSpeechSignal()
            return
        }
        if (idle >= idleSilenceMs) {
            L.i(TAG, "闲置${idle}ms ≥ ${idleSilenceMs}ms 且 AI未播报 → 自动挂断")
            cb.onStateChanged("自动：双方静默超时，挂断")
            state = State.DIALING
            cb.requestHangup()
        }
    }

    fun onCallEnded() = onMain {
        if (!running) return@onMain
        if (state == State.MONITORING) return@onMain
        goMonitoring()
    }

    /** 本地监听的 VAD 判决（每 20ms 一帧，来自音频采集线程）→ 切主线程。 */
    fun onMonitorSpeech(isSpeech: Boolean) = onMain {
        if (!running || state != State.MONITORING) return@onMain
        if (isSpeech) {
            speechRunMs += FRAME_MS
            if (speechRunMs >= REDIAL_SPEECH_MS) {
                L.i(TAG, "本地VAD检测到持续说话${speechRunMs}ms → 自动重拨")
                speechRunMs = 0
                goDialing()
            }
        } else {
            speechRunMs = 0
        }
    }

    private fun markSpeechSignal() { lastSpeechSignal = System.currentTimeMillis() }
    private fun markActivityStart() { lastActivityStart = System.currentTimeMillis() }

    fun release() {
        running = false
        main.removeCallbacks(ticker)
    }
}
