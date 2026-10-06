package com.mmd.englishdigital

import android.util.Log
import cn.enaium.webrtc.aec3.Aec3AudioBuffer
import cn.enaium.webrtc.aec3.Aec3Config
import cn.enaium.webrtc.aec3.Aec3EchoControl
import cn.enaium.webrtc.aec3.Aec3Environment
import cn.enaium.webrtc.aec3.Aec3Factory
import cn.enaium.webrtc.aec3.createAec3AudioBuffer
import cn.enaium.webrtc.aec3.createAec3Config
import cn.enaium.webrtc.aec3.createAec3EchoControl
import cn.enaium.webrtc.aec3.createAec3Environment
import cn.enaium.webrtc.aec3.createAec3FactoryWithConfig
import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ============================ 软件回声消除（WebRTC AEC3） ============================
 *
 * 【为什么需要】
 * 「用户说话自动打断 AI」的前提是：麦克风里听不到 AI 自己的声音。
 * 手机有硬件 AcousticEchoCanceler 能消掉；但安卓电视普遍没有音频效果器
 * （`dumpsys media.audio_flinger` 显示 "XML effect configuration failed to load / 0 Effect Chains"），
 * `AcousticEchoCanceler.isAvailable()` 恒为 false，App 里 AEC 被整段跳过、且没有回退。
 * 于是 AI 的声音被电视喇叭放出来、又被麦克风收回去，被云端 VAD 与本地 bargeVad
 * 误判成「用户在说话」→ AI 一开口就被自己打断。
 *
 * 本类在**音频 I/O 边界**插一层软件 AEC3，主流程（SeeduplexClient 协议、
 * AutoCallController 状态机）零改动：
 *
 *   AI 下行(24k) ─► AudioPlayer.write ─┬─► AudioTrack 播放
 *                                      └─► feedRender() [24k→16k] ─► AEC3 far-end
 *   麦克风(16k)  ─► process() ─► AEC3 near-end ─► 消除后16k ─┬─► client.sendAudio()
 *                                                            └─► bargeVad.feed()
 *
 * 【依赖与两个坑（务必知悉）】
 *  1) 库：cn.enaium.webrtc.aec3:webrtc-aec3-kmp（从 WebRTC 抽出的独立 AEC3）。
 *     其 Maven AAR 写死 minCompileSdk=37，本工程 AGP 8.4.0 / compileSdk 34 无法直接依赖，
 *     故改为内置解包产物：app/libs/aec3-classes.jar + app/src/main/jniLibs/<abi>/ 下的 .so。
 *  2) 该库用 Kotlin 2.2.10 构建，metadata 需 Kotlin >= 2.2；本工程已把 Kotlin 从
 *     1.9.23 升到 2.2.10（build.gradle）。升级后仍兼容旧库（android-vad 2.0.9，1.9.21 构建）。
 *
 * 【实机实测（KUNL-250A / Android 12 / arm64-v8a，无硬件 AEC）】
 *  - 回声比底噪高约 38 dB；AEC3 稳定估计时延 ≈ 20 ms；ERLE ≈ 26 dB。
 *
 * 【关键实现约束（踩过的坑）】
 *  - AEC3 在 16k 下**只接受恰好 10ms = 160 样本**一帧，输入必须按此重分帧；
 *  - far-end（render，正在播放的信号）必须**连续、按实时节拍**喂入。AI 音频是突发到达的
 *    （response.output_audio.delta），若直接在其到达时喂，render 队列会错位，时延估计乱跳、
 *    ERLE 掉到个位数。这里用「render 环形队列 + 独立 10ms 定拍线程」，空闲补零整流成连续流；
 *  - AI 下行是 24kHz，而麦克风/AEC3 都是 16kHz，far-end 需 **24k→16k 重采样**；
 *  - AEC3 缓冲里的样本是 **int16 数值范围的 float**（不是 -1..1 归一化）；
 *  - render 线程与采集线程都会调 native，必须**串行化**（本类用 lock）。
 *
 * 【降级】原生库缺失（如 x86 模拟器未放 x86_64 的 .so）时 createOrNull() 返回 null，
 * 调用方保持原逻辑。
 */
class Aec3Processor private constructor(
    private val config: Aec3Config,
    private val env: Aec3Environment,
    private val factory: Aec3Factory,
    private val ec: Aec3EchoControl,
    private val renderBuf: Aec3AudioBuffer,
    private val captureBuf: Aec3AudioBuffer,
    /** 喂给 AEC3 的 far-end 相对“写入 AudioTrack 时刻”延后的样本数，用于对齐真正播放时刻。 */
    private val renderDelaySamples: Int,
) {

    companion object {
        private const val TAG = "Aec3"
        private const val RATE = 16000            // 麦克风 / AEC3 采样率
        private const val AI_RATE = 24000         // AI 下行采样率
        private const val FRAME = 160             // 10ms @16k（AEC3 硬性帧长）
        private const val FRAME_BYTES = FRAME * 2
        private const val RING_SECONDS = 1        // render 环缓冲时长上限
        private const val RING = RATE * RING_SECONDS
        // render→capture 时延先验(ms)：工程里 AudioTrack 缓冲约 170ms + 声程；
        // AEC3 会在此基础上自适应，给一个接近真值的先验能加快收敛。
        private const val DELAY_HINT_MS = 100

        /**
         * 创建 AEC3 处理器；原生库不可用时返回 null（调用方降级）。
         * @param renderDelayMs 播放缓冲时长(ms)。far-end 会按该时长延后喂入，
         *   以对齐“真正从扬声器播出”的时刻——**这是本方案效果好坏的关键**：
         *   不延后时（参考早于播放约一个缓冲时长），实测 ERLE 只有个位数~十几 dB；
         *   按缓冲时长延后后，ERLE 稳定到 ~34 dB。
         */
        fun createOrNull(renderDelayMs: Int): Aec3Processor? = try {
            val config = createAec3Config().apply {
                setDelayDefaultDelay(DELAY_HINT_MS)
                setFilterInitialStateSeconds(0.5f)
                setFilterConservativeInitialPhase(false)
            }
            val env = createAec3Environment()
            val factory = createAec3FactoryWithConfig(config)
            val ec = createAec3EchoControl(factory, env, RATE, 1, 1)
            val rb = createAec3AudioBuffer(RATE, 1)
            val cb = createAec3AudioBuffer(RATE, 1)
            val delaySamples = (renderDelayMs.coerceAtLeast(0) * RATE / 1000)
            Aec3Processor(config, env, factory, ec, rb, cb, delaySamples).also { it.start() }
        } catch (e: Throwable) {
            Log.w(TAG, "AEC3 不可用，保持原始音频：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private val lock = Object()
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var renderThread: Thread? = null

    // ---- render（far-end）环形队列，16k 单声道样本 ----
    private val ring = ShortArray(RING)
    private var ringHead = 0
    private var ringTail = 0
    private var ringCount = 0

    // ---- 24k→16k 重采样状态（跨 chunk 连续）----
    private var rsPrev = 0
    private var rsInit = false
    private var rsPhase = 0.0
    private val rsStep = RATE.toDouble() / AI_RATE.toDouble()   // 16000/24000 = 2/3

    // ---- 采集端字节重分帧 ----
    private val capIn = ByteArrayOutputStream(FRAME_BYTES * 4)

    // ---- ERLE 观测（仅日志，便于电视上核实）----
    private var obsRaw = 0.0
    private var obsClean = 0.0
    private var obsRef = 0.0
    private var obsCnt = 0
    @Volatile private var fedSamples = 0L

    /** 启动 render 定拍线程：每 10ms 喂一帧 far-end（队列空则补零，保证连续）。 */
    private fun start() {
        running.set(true)
        renderThread = Thread {
            val rf = FloatArray(FRAME)
            val inF = FloatArray(FRAME)
            // far-end 延迟线：输出的是 renderDelaySamples 个样本之前的内容，
            // 从而与“真正从扬声器播出”的时刻对齐（详见 createOrNull 注释）。
            val dl = ShortArray(maxOf(1, renderDelaySamples))
            var dlPos = 0
            val nanos = 10_000_000L
            var next = System.nanoTime()
            while (running.get()) {
                synchronized(lock) {
                    var i = 0
                    while (i < FRAME) { inF[i] = ringPop().toFloat(); i++ }
                    if (renderDelaySamples > 0) {
                        for (k in 0 until FRAME) {
                            rf[k] = dl[dlPos].toFloat()
                            dl[dlPos] = inF[k].toInt().coerceIn(-32768, 32767).toShort()
                            dlPos = (dlPos + 1) % renderDelaySamples
                        }
                    } else {
                        for (k in 0 until FRAME) rf[k] = inF[k]
                    }
                    try {
                        var rr = 0.0
                        for (k in 0 until FRAME) rr += rf[k].toDouble() * rf[k].toDouble()
                        obsRef += rr
                        renderBuf.writeChannel(0, rf)
                        ec.analyzeRender(renderBuf)
                    } catch (e: Throwable) {
                        Log.w(TAG, "analyzeRender 失败：${e.message}")
                    }
                }
                next += nanos
                val sleep = next - System.nanoTime()
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
                    } catch (_: InterruptedException) { break }
                } else {
                    next = System.nanoTime()   // 落后过多则重置节拍，避免堆积
                }
            }
        }.also { it.name = "aec3-render"; it.start() }
    }

    /**
     * 喂入正在播放的 AI 音频（24k/mono/16bit），内部重采样到 16k 并入 render 队列。
     * 由 AudioPlayer 在把该数据写入 AudioTrack 时调用（网络线程）。
     */
    fun feedRender(pcm24k: ByteArray) {
        synchronized(lock) {
            fedSamples += (pcm24k.size / 2).toLong()
            var i = 0
            while (i + 1 < pcm24k.size) {
                val cur = ((pcm24k[i + 1].toInt() shl 8) or (pcm24k[i].toInt() and 0xFF)).toShort().toInt()
                i += 2
                if (!rsInit) { rsPrev = cur; rsInit = true; continue }
                while (rsPhase < 1.0) {
                    val v = rsPrev + (cur - rsPrev) * rsPhase
                    ringPush(v.toInt().coerceIn(-32768, 32767).toShort())
                    rsPhase += rsStep
                }
                rsPhase -= 1.0
                rsPrev = cur
            }
        }
    }

    /**
     * 麦克风 16k/mono/16bit 帧 → 软件回声消除后的同采样率 PCM。
     * 由 AudioCapture 在每帧回调时调用（采集线程）。返回长度可能小于输入（因为要
     * 攒够 10ms 整帧才处理），调用方对空结果跳过发送即可。
     */
    fun process(frame: ByteArray): ByteArray {
        synchronized(lock) {
            capIn.write(frame, 0, frame.size)
            val data = capIn.toByteArray()
            val out = ByteArrayOutputStream(data.size)
            val cIn = FloatArray(FRAME)
            var consumed = 0
            while (data.size - consumed >= FRAME_BYTES) {
                for (k in 0 until FRAME) {
                    val lo = data[consumed + k * 2].toInt() and 0xFF
                    val hi = data[consumed + k * 2 + 1].toInt()
                    cIn[k] = ((hi shl 8) or lo).toShort().toFloat()
                }
                consumed += FRAME_BYTES
                try {
                    captureBuf.writeChannel(0, cIn)
                    ec.analyzeCapture(captureBuf)
                    ec.processCapture(captureBuf, false)
                    val clean = captureBuf.readChannel(0)
                    var rp = 0.0
                    var cp = 0.0
                    for (k in 0 until FRAME) {
                        val c = clean[k].toInt().coerceIn(-32768, 32767)
                        out.write(c and 0xFF)
                        out.write((c shr 8) and 0xFF)
                        val r = cIn[k].toDouble(); rp += r * r
                        val q = c.toDouble(); cp += q * q
                    }
                    obsRaw += rp; obsClean += cp; obsCnt += FRAME
                } catch (e: Throwable) {
                    // 单帧异常时原样透传，避免中断整条音频链
                    Log.w(TAG, "processCapture 失败，本帧透传：${e.message}")
                    for (k in 0 until FRAME) {
                        val c = cIn[k].toInt().coerceIn(-32768, 32767)
                        out.write(c and 0xFF); out.write((c shr 8) and 0xFF)
                    }
                }
            }
            val rest = data.copyOfRange(consumed, data.size)
            capIn.reset()
            if (rest.isNotEmpty()) capIn.write(rest, 0, rest.size)

            if (obsCnt >= RATE * 2) {   // 约每 2s 打一条 ERLE，便于电视上核实效果
                val erle = 10.0 * log10(obsRaw / maxOf(obsClean, 1e-9))
                Log.i(TAG, "ERLE≈%.1f dB（原始RMS=%.1f 消除后RMS=%.1f 参考RMS=%.1f fed=%d delay=%d）".format(
                    erle, sqrt(obsRaw / obsCnt), sqrt(obsClean / obsCnt),
                    sqrt(obsRef / obsCnt), fedSamples, renderDelaySamples))
                obsRaw = 0.0; obsClean = 0.0; obsRef = 0.0; obsCnt = 0
            }
            return out.toByteArray()
        }
    }

    /** 播报被打断（barge-in）时调用：丢弃尚未播放参考，避免 AEC3 追已 flush 的信号。 */
    fun clearRender() {
        synchronized(lock) {
            ringHead = 0; ringTail = 0; ringCount = 0
            rsInit = false; rsPhase = 0.0
        }
    }

    fun release() {
        running.set(false)
        try { renderThread?.join(500) } catch (_: Exception) {}
        renderThread = null
        synchronized(lock) {
            runCatching { renderBuf.close() }
            runCatching { captureBuf.close() }
            runCatching { ec.close() }
            runCatching { factory.close() }
            runCatching { env.close() }
            runCatching { config.close() }
            capIn.reset()
            ringHead = 0; ringTail = 0; ringCount = 0
        }
    }

    // ---- render 环形队列（全部在 lock 内访问）----

    private fun ringPush(s: Short) {
        if (ringCount >= RING) {         // 溢出丢最旧，避免累积导致时延漂移
            ringHead = (ringHead + 1) % RING
            ringCount--
        }
        ring[ringTail] = s
        ringTail = (ringTail + 1) % RING
        ringCount++
    }

    private fun ringPop(): Int {
        if (ringCount == 0) return 0
        val s = ring[ringHead].toInt()
        ringHead = (ringHead + 1) % RING
        ringCount--
        return s
    }
}
