package com.mmd.englishdigital

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 神经网络回声消除（DTLN-aec，微软 AEC-Challenge 第 3 名，MIT 许可）。
 *
 * 【为什么换掉 AEC3】本机（KUNL-250A 电视）实测：线性 AEC3 的 ERLE ≈ 0，
 * 且 mic 与 far-end 的互相关只有 0.02~0.05（±1s 全延迟搜索、各音量下均如此）
 * → 回声路径**非线性**，线性自适应滤波器无法建模。而离线用同一批真实录音
 * 测试 DTLN-aec：纯回声段抑制 **29.8dB**、等强度双讲抑制 **23.9dB 且人声保留 0.943**
 * → 神经网络能建模该非线性路径。最小规格（128，7MB）即可，适合电视算力。
 *
 * 【模型接口】（dtln_aec_128_1/2.tflite，均为 float32）
 *  模型1 输入: input_3 [1,1,257] 麦克风幅度谱
 *              input_4 [1,1,257] far-end(lpb) 幅度谱
 *              input_5 [1,2,128,2] LSTM 状态
 *       输出: Identity [1,1,257] 幅度掩码;  Identity_1 状态
 *  模型2 输入: input_6 [1,1,512] 掩码后时域块; input_7 [1,1,512] far-end 时域块; input_8 状态
 *       输出: Identity [1,1,512] 输出块; Identity_1 状态
 *  外部帧参数: block=512, shift=128（16kHz）
 */
class DtlNAec(context: Context, private val farEndDelayMs: Int = 150) {

    companion object {
        private const val TAG = "DtlNAec"
        private const val RATE = 16000
        private const val AI_RATE = 24000
        const val BLOCK = 512
        const val SHIFT = 128
        private const val BINS = 257
        private const val RING_SEC = 3
        private const val RING = RATE * RING_SEC
    }

    private val i1: Interpreter
    private val i2: Interpreter

    // ---- 张量索引（按名查找，避免依赖顺序）----
    private val m1InMic: Int; private val m1InLpb: Int; private val m1InState: Int
    private val m1OutMask: Int; private val m1OutState: Int
    private val m2InEst: Int; private val m2InLpb: Int; private val m2InState: Int
    private val m2OutBlock: Int; private val m2OutState: Int

    // ---- 复用缓冲区（热路径零分配）----
    private val micMag = Array(1) { Array(1) { FloatArray(BINS) } }
    private val lpbMag = Array(1) { Array(1) { FloatArray(BINS) } }
    private var state1: Array<Array<Array<FloatArray>>> = newState()
    private var state2: Array<Array<Array<FloatArray>>> = newState()
    private val estBlock = Array(1) { Array(1) { FloatArray(BLOCK) } }
    private val lpbBlock = Array(1) { Array(1) { FloatArray(BLOCK) } }

    private fun newState() = Array(1) { Array(2) { Array(128) { FloatArray(2) } } }

    // ---- FFT 工作区 ----
    private val re = FloatArray(BLOCK)
    private val im = FloatArray(BLOCK)
    private val binRe = FloatArray(BINS)
    private val binIm = FloatArray(BINS)

    // ---- 滑窗缓冲 ----
    private val micWin = FloatArray(BLOCK)          // 麦克风滑动窗（最新 512 样本）
    private val outOla = FloatArray(BLOCK)          // overlap-add 输出缓冲

    // ---- far-end（lpb）：环形缓冲 + 24k→16k 重采样（跨块保持相位）----
    private val lpbRing = FloatArray(RING)
    private var lpbWrite = 0L                        // 累计写入样本数
    private var rsPhase = 0.0                        // 重采样相位
    private var rsPrev = 0f
    private var rsInit = false
    private var pendingLpb = 0                       // 上一个 chunk 留下的待输出样本
    private val delaySamples = farEndDelayMs.coerceAtLeast(0) * RATE / 1000

    // ---- 麦克风输入累计 ----
    private val micQ = ArrayList<Float>(2048)
    private var micTotal = 0L                        // 累计消费的麦克风样本
    private val outQ = ArrayList<Float>(2048)

    // ---- ERLE 观测 ----
    private var obsSumRaw = 0.0; private var obsSumOut = 0.0; private var obsN = 0
    private var lastLog = 0L

    init {
        i1 = Interpreter(loadModel(context, "dtln_aec_128_1.tflite"), Interpreter.Options().setNumThreads(2))
        i2 = Interpreter(loadModel(context, "dtln_aec_128_2.tflite"), Interpreter.Options().setNumThreads(2))
        fun inIdx(interp: Interpreter, name: String): Int {
            for (k in 0 until interp.inputTensorCount) if (interp.getInputTensor(k).name() == name) return k
            return -1
        }
        fun outIdx(interp: Interpreter, name: String): Int {
            for (k in 0 until interp.outputTensorCount) if (interp.getOutputTensor(k).name() == name) return k
            return -1
        }
        m1InMic = inIdx(i1, "input_3"); m1InLpb = inIdx(i1, "input_4"); m1InState = inIdx(i1, "input_5")
        m1OutMask = outIdx(i1, "Identity"); m1OutState = outIdx(i1, "Identity_1")
        m2InEst = inIdx(i2, "input_6"); m2InLpb = inIdx(i2, "input_7"); m2InState = inIdx(i2, "input_8")
        m2OutBlock = outIdx(i2, "Identity"); m2OutState = outIdx(i2, "Identity_1")
        Log.i(TAG, "DTLN-aec 就绪: 模型1(mic=$m1InMic lpb=$m1InLpb state=$m1InState) 模型2(est=$m2InEst lpb=$m2InLpb state=$m2InState) 延迟=${farEndDelayMs}ms")
    }

    private fun loadModel(ctx: Context, name: String): MappedByteBuffer {
        ctx.assets.openFd(name).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /** 喂入正在播放的 AI 音频（24kHz PCM），内部完成 24k→16k 重采样并写入 lpb 环。 */
    fun feedRender(pcm24k: ByteArray) {
        val n = pcm24k.size / 2
        if (n == 0) return
        val step = RATE.toDouble() / AI_RATE.toDouble()   // 2/3
        var x = 0
        while (x < n) {
            val cur = ((pcm24k[2 * x + 1].toInt() shl 8) or (pcm24k[2 * x].toInt() and 0xFF)).toShort().toInt() / 32768.0f
            if (!rsInit) { rsPrev = cur; rsInit = true; rsPhase = 0.0 }
            while (rsPhase < 1.0) {
                val v = rsPrev + (cur - rsPrev) * rsPhase.toFloat()
                lpbRing[(lpbWrite % RING).toInt()] = v
                lpbWrite++
                rsPhase += step
            }
            rsPhase -= 1.0
            rsPrev = cur
            x++
        }
    }

    /**
     * 处理一帧麦克风音频（16k/int16，任意长度），返回**等长**的回声消除后 PCM。
     * 内部按 128 样本步进、512 样本窗处理；输出经 overlap-add 后按需取用。
     */
    fun process(mic: ByteArray): ByteArray {
        val n = mic.size / 2
        if (n == 0) return mic
        for (k in 0 until n) {
            micQ.add(((mic[2 * k + 1].toInt() shl 8) or (mic[2 * k].toInt() and 0xFF)).toShort().toInt() / 32768.0f)
        }
        // 每凑够 SHIFT 个样本跑一次
        while (micQ.size >= SHIFT) {
            // 滑动窗右移 SHIFT
            System.arraycopy(micWin, SHIFT, micWin, 0, BLOCK - SHIFT)
            for (k in 0 until SHIFT) micWin[BLOCK - SHIFT + k] = micQ[k]
            micQ.subList(0, SHIFT).clear()
            micTotal += SHIFT
            runBlock()
        }
        // 取等长输出
        val out = ByteArray(n * 2)
        for (k in 0 until n) {
            val v = if (k < outQ.size) outQ[k] else 0f
            var s = (v * 32768.0f).toInt()
            if (s > 32767) s = 32767 else if (s < -32768) s = -32768
            out[2 * k] = (s and 0xFF).toByte()
            out[2 * k + 1] = ((s shr 8) and 0xFF).toByte()
        }
        if (outQ.size > n) outQ.subList(0, n).clear() else outQ.clear()

        // ERLE 观测（仅统计 far-end 有声的时段）
        val feNow = lpbNow()
        if (feNow > 0.01f) {
            var s1 = 0.0; var s2 = 0.0
            for (k in 0 until n) {
                val a = ((mic[2 * k + 1].toInt() shl 8) or (mic[2 * k].toInt() and 0xFF)).toShort().toInt() / 32768.0
                val b = ((out[2 * k + 1].toInt() shl 8) or (out[2 * k].toInt() and 0xFF)).toShort().toInt() / 32768.0
                s1 += a * a; s2 += b * b
            }
            obsSumRaw += s1 / n; obsSumOut += s2 / n; obsN++
        }
        val now = System.currentTimeMillis()
        if (obsN > 50 && now - lastLog > 3000) {
            lastLog = now
            val r = sqrt(obsSumRaw / obsN) * 32768; val o = sqrt(obsSumOut / obsN) * 32768
            Log.i(TAG, "DTLN-ERLE≈%.1f dB（原始RMS=%.0f 消除后RMS=%.0f 参考RMS=%.0f）".format(
                20 * ln((r / maxOf(o, 1e-6))).let { it / ln(10.0) }, r, o, feNow * 32768))
            obsSumRaw = 0.0; obsSumOut = 0.0; obsN = 0
        }
        return out
    }

    private fun lpbNow(): Float {
        // 取最近 20ms 的 far-end 平均幅度
        var s = 0.0; val m = minOf(320, lpbWrite.toInt())
        if (m <= 0) return 0f
        for (i in 0 until m) {
            val idx = ((lpbWrite - m + i) % RING).toInt()
            s += kotlin.math.abs(lpbRing[idx]).toDouble()
        }
        return (s / m).toFloat()
    }

    /** 单块：模型1（幅度掩码）→ 模型2（时域滤波），overlap-add 输出 SHIFT 个样本。 */
    private fun runBlock() {
        // 1) 麦克风窗 → 幅度谱
        for (k in 0 until BLOCK) { re[k] = micWin[k]; im[k] = 0f }
        fft(false)
        for (b in 0 until BINS) { binRe[b] = re[b]; binIm[b] = im[b]; micMag[0][0][b] = sqrt(re[b] * re[b] + im[b] * im[b]) }

        // 2) far-end 窗（按 delaySamples 延后）→ 幅度谱 + 时域块
        val end = micTotal - delaySamples
        for (k in 0 until BLOCK) {
            val abs = end - BLOCK + k
            lpbBlock[0][0][k] = if (abs in 0 until lpbWrite) lpbRing[(abs % RING).toInt()] else 0f
        }
        for (k in 0 until BLOCK) { re[k] = lpbBlock[0][0][k]; im[k] = 0f }
        fft(false)
        for (b in 0 until BINS) lpbMag[0][0][b] = sqrt(re[b] * re[b] + im[b] * im[b])

        // 3) 模型1：掩码
        val in1 = arrayOfNulls<Any>(3)
        in1[m1InMic] = micMag; in1[m1InLpb] = lpbMag; in1[m1InState] = state1
        val out1 = arrayOfNulls<Any>(2)
        out1[m1OutMask] = Array(1) { Array(1) { FloatArray(BINS) } }
        out1[m1OutState] = newState()
        i1.runForMultipleInputsOutputs(in1, mapOf(0 to out1[0], 1 to out1[1]))
        @Suppress("UNCHECKED_CAST")
        val mask = (out1[m1OutMask] as Array<Array<FloatArray>>)[0][0]
        @Suppress("UNCHECKED_CAST")
        state1 = out1[m1OutState] as Array<Array<Array<FloatArray>>>

        // 4) 掩码后时域块（必须把掩码结果写回 binRe/binIm —— irfftToEst 从这里取频域数据）
        for (b in 0 until BINS) {
            val m = mask[b]
            binRe[b] *= m
            binIm[b] *= m
        }
        irfftToEst()

        // 5) 模型2：时域滤波
        val in2 = arrayOfNulls<Any>(3)
        in2[m2InEst] = estBlock; in2[m2InLpb] = lpbBlock; in2[m2InState] = state2
        val out2 = arrayOfNulls<Any>(2)
        out2[m2OutBlock] = Array(1) { Array(1) { FloatArray(BLOCK) } }
        out2[m2OutState] = newState()
        i2.runForMultipleInputsOutputs(in2, mapOf(0 to out2[0], 1 to out2[1]))
        @Suppress("UNCHECKED_CAST")
        val blk = (out2[m2OutBlock] as Array<Array<FloatArray>>)[0][0]
        @Suppress("UNCHECKED_CAST")
        state2 = out2[m2OutState] as Array<Array<Array<FloatArray>>>

        // 6) overlap-add：输出缓冲右移 SHIFT，累加新块，取头部 SHIFT
        System.arraycopy(outOla, SHIFT, outOla, 0, BLOCK - SHIFT)
        for (k in 0 until SHIFT) outOla[BLOCK - SHIFT + k] = 0f
        for (k in 0 until BLOCK) outOla[k] += blk[k]
        for (k in 0 until SHIFT) outQ.add(outOla[k])
    }

    /** 由掩码后的 257 个复频点重建时域块（512）：补共轭对称 + 逆 FFT（1/N 归一化）。 */
    private fun irfftToEst() {
        for (k in 0 until BLOCK) { re[k] = 0f; im[k] = 0f }
        for (b in 0 until BINS) { re[b] = binRe[b]; im[b] = binIm[b] }
        for (b in 1 until BINS - 1) { re[BLOCK - b] = binRe[b]; im[BLOCK - b] = -binIm[b] }
        fft(true)
        for (k in 0 until BLOCK) estBlock[0][0][k] = re[k]
    }

    /** 原地 radix-2 FFT（inverse=true 时含 1/N 归一化，与 numpy rfft/irfft 一致）。 */
    private fun fft(inverse: Boolean) {
        val n = BLOCK
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * Math.PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { for (k in 0 until n) { re[k] /= n; im[k] /= n } }
    }

    fun reset() {
        state1 = newState(); state2 = newState()
        micWin.fill(0f); outOla.fill(0f); lpbRing.fill(0f)
        micQ.clear(); outQ.clear(); micTotal = 0; lpbWrite = 0
        rsInit = false; rsPhase = 0.0
        obsSumRaw = 0.0; obsSumOut = 0.0; obsN = 0
    }
}
