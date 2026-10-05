package com.mmd.englishdigital

import android.os.Handler
import android.os.Looper

/**
 * 自动拨号 / 自动挂断状态机（纯本地逻辑，不涉及任何服务端对接）。
 *
 * 策略（已与用户确认）：
 *  - A1/F1：默认启动即拨打；可开关，默认开。
 *  - A1/B1：通话中「用户持续静默 ≥10s」且「AI 未在播报」→ 自动挂断。
 *  - C1/E3：挂断后本地监听（零费用），检测到持续说话 ≥0.5s → 自动重拨，不限制次数。
 *
 * ⚠️ 线程约定：本类的所有回调入口（含来自音频采集线程的 onMonitorSpeech）
 * 一律切回主线程再执行，因为 requestDial/requestHangup 会直接操作 UI 控件。
 * 若在音频线程直接触发拨号，会抛 CalledFromWrongThreadException 崩溃。
 */
class AutoCallController(private val cb: Callbacks) {

    interface Callbacks {
        fun requestDial()
        fun requestHangup()
        fun requestStartMonitoring()
        fun requestStopMonitoring()
        fun onStateChanged(label: String)
    }

    enum class State { DIALING, IN_CALL, MONITORING }

    companion object {
        const val SILENCE_HANGUP_MS = 10_000L
        const val AI_IDLE_MS = 2_000L
        const val REDIAL_SPEECH_MS = 500L
        const val TICK_MS = 500L
        const val FRAME_MS = 20L
    }

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            onMain { if (value) start() else stopAll() }
        }

    @Volatile
    var state: State = State.DIALING
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastUserActivity = 0L
    @Volatile private var lastAiAudio = 0L
    private var speechRunMs = 0L
    @Volatile private var running = false

    /** 统一在主线程执行；已在主线程则直接跑。 */
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
        markUserActivity()
        markAiAudio()
        cb.onStateChanged("自动：拨打中…")
        cb.requestDial()
    }

    private fun goMonitoring() {
        state = State.MONITORING
        speechRunMs = 0
        cb.onStateChanged("自动：监听中（说话即重拨）")
        cb.requestStartMonitoring()
    }

    // ---------- 宿主事件（可能来自任意线程，统一回主线程）----------

    fun onCallConnected() = onMain {
        if (!running) return@onMain
        state = State.IN_CALL
        markUserActivity()
        markAiAudio()
        cb.onStateChanged("自动：通话中（静默 10s 自动挂断）")
    }

    fun onUserActivity() { lastUserActivity = System.currentTimeMillis() }

    fun onAiAudio() { lastAiAudio = System.currentTimeMillis() }

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
                speechRunMs = 0
                goDialing()
            }
        } else {
            speechRunMs = 0
        }
    }

    private fun onTick() {
        if (state != State.IN_CALL) return
        val t = System.currentTimeMillis()
        if (t - lastUserActivity > SILENCE_HANGUP_MS && t - lastAiAudio > AI_IDLE_MS) {
            cb.onStateChanged("自动：静默超时，挂断")
            state = State.DIALING
            cb.requestHangup()
        }
    }

    private fun markUserActivity() { lastUserActivity = System.currentTimeMillis() }
    private fun markAiAudio() { lastAiAudio = System.currentTimeMillis() }

    fun release() {
        running = false
        main.removeCallbacks(ticker)
    }
}
