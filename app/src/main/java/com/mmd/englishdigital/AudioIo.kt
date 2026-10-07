package com.mmd.englishdigital

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler

/** 麦克风采集：16kHz / 单声道 / 16bit，20ms 一帧回调。内置 AEC（可用时）。
 *
 * [半双工坑] 部分设备（尤其电视）在 AudioSource=VOICE_COMMUNICATION 且播放
 * USAGE_VOICE_COMMUNICATION 时，HAL 会在播放期间**直接掐掉麦克风**（数字静音），
 * 导致 AI 说话时用户的声音根本采不到。故把 source 做成可配置，便于验证换成
 * MIC / VOICE_RECOGNITION 是否能绕开该行为。
 */
class AudioCapture(
    private val source: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    private val onFrame: (ByteArray) -> Unit
) {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null

    /** 硬件 AEC 是否真正启用。为 false 的设备（多数安卓电视）需在外部叠加软件 AEC3。 */
    @Volatile var hwAecActive = false; private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SeeduplexClient.IN_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val r = try {
            AudioRecord(
                source,
                SeeduplexClient.IN_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SeeduplexClient.FRAME_BYTES * 10)
            )
        } catch (e: Exception) {
            L.w("AudioCapture", "AudioRecord init failed: ${e.message}")
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); return false
        }
        record = r
        if (AcousticEchoCanceler.isAvailable()) {
            aec = try {
                AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
            } catch (_: Exception) {
                null
            }
        }
        hwAecActive = aec?.enabled == true
        L.i(
            "AudioCapture",
            "HW AEC available=${AcousticEchoCanceler.isAvailable()} active=$hwAecActive"
        )
        running = true
        r.startRecording()
        thread = Thread {
            val buf = ByteArray(SeeduplexClient.FRAME_BYTES)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) onFrame(buf.copyOf(n))
            }
        }.also { it.start() }
        return true
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { aec?.release() } catch (_: Exception) {}
        record = null; aec = null; thread = null
    }
}

/** 播放：24kHz / 单声道 / 16bit 流式。无声环境（模拟器）下 start() 返回 false。 */
class AudioPlayer(
    private val sampleRate: Int = SeeduplexClient.OUT_RATE,
    private val usage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION
) {
    private var track: AudioTrack? = null
    var bytesWritten: Long = 0; private set

    /** 播放缓冲时长(ms)= AudioTrack 缓冲字节数对应的时间；供 AEC3 对齐 far-end 参考。 */
    var bufferMs: Int = 0; private set

    /**
     * 播放参考（far-end）回调：把即将写入 AudioTrack 的 PCM 原样抛出，
     * 供软件 AEC3 作为回声消除的参考信号。必须在 write() 之外单独使用，
     * 不要在其中做耗时操作（运行在网络回调线程）。
     */
    var onRender: ((ByteArray) -> Unit)? = null

    fun start(): Boolean {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufBytes = maxOf(minBuf, 8192)
        bufferMs = bufBytes * 1000 / (sampleRate * 2)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            L.w("AudioPlayer", "AudioTrack init failed: ${e.message}")
            return false
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release(); return false
        }
        track = t
        bytesWritten = 0L      // ★ 复位（playbackHeadPosition 从 0 开始）
        t.play()
        return true
    }

    fun write(pcm: ByteArray) {
        try {
            // 先把“将要播放”的信号交给 AEC3 作为 far-end 参考（时延由 AEC3 自适应）
            onRender?.invoke(pcm)
            track?.write(pcm, 0, pcm.size)
            bytesWritten += pcm.size
        } catch (_: Exception) {
        }
    }

    /**
     * 立即停止当前播报并丢弃尚未播放的缓冲（用户打断 / barge-in 用）。
     * AudioTrack.flush() 要求处于 pause/stop 状态，故按 pause → flush → play 复位。
     */
    fun interrupt() {
        try {
            track?.pause()
            track?.flush()
            track?.play()
            bytesWritten = 0L   // ★ flush 后播放头归零，同步复位写入计数
        } catch (_: Exception) {
        }
    }

    /**
     * ★ 尚未播放的字节数 = 已写入 - 播放头已播。
     * 用于判定「AI 是否真的还在播报」——服务端的 TTS_ENDED 只表示
     * 「音频数据下发完毕」，长回复时数据先一次传完，播放器里可能还有上百秒缓冲。
     */
    fun pendingBytes(): Long {
        val t = track ?: return 0L
        return try {
            val played = t.playbackHeadPosition.toLong() * 2L   // 帧数 × 2 字节
            (bytesWritten - played).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    /** ★ 是否仍在播报（缓冲里还有 >200ms 音频） */
    fun isPlaying(): Boolean = pendingBytes() > (sampleRate * 2L * 200L / 1000L)

    fun stop() {
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
