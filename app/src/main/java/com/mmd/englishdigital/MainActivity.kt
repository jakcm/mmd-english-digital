package com.mmd.englishdigital

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity(), SeeduplexClient.Listener {

    private lateinit var statusTv: TextView
    private lateinit var transcriptTv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnCall: Button
    private lateinit var btnTest: Button
    private lateinit var swAuto: Switch

    private var client: SeeduplexClient? = null
    private var capture: AudioCapture? = null
    private var player: AudioPlayer? = null

    // ---- 自动模式（新增，均不影响 SeeduplexClient / AudioIo）----
    private var controller: AutoCallController? = null
    private var localVad: LocalVad? = null
    private var monitorCapture: AudioCapture? = null

    private val main = Handler(Looper.getMainLooper())
    private val userBuf = StringBuilder()
    private val aiBuf = StringBuilder()
    private var callActive = false
    private var receivedAudioBytes = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusTv = findViewById(R.id.status)
        transcriptTv = findViewById(R.id.transcript)
        scroll = findViewById(R.id.scroll)
        btnCall = findViewById(R.id.btnCall)
        btnTest = findViewById(R.id.btnTest)
        swAuto = findViewById(R.id.swAuto)

        setStatus(
            if (BuildConfig.VOLC_API_KEY.isEmpty()) "⚠️ 未注入 API Key"
            else "API Key 已就绪"
        )

        // 本地 VAD（仅自动监听用）
        localVad = LocalVad { isSpeech -> controller?.onMonitorSpeech(isSpeech) }

        controller = AutoCallController(object : AutoCallController.Callbacks {
            override fun requestDial() = startCall()
            override fun requestHangup() = hangup()
            override fun requestStartMonitoring() = startMonitoring()
            override fun requestStopMonitoring() = stopMonitoring()
            override fun onStateChanged(label: String) { setStatus(label) }
        })

        btnCall.setOnClickListener { if (callActive) hangup() else startCall() }
        btnTest.setOnClickListener { feedTestAudio() }
        swAuto.isChecked = true
        swAuto.setOnCheckedChangeListener { _, checked ->
            controller?.enabled = checked
            if (checked) appendSystem("自动模式：开")
            else appendSystem("自动模式：关（回到手动）")
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 1
            )
        } else {
            controller?.start()   // 默认启动即拨打
        }

        runVadSelfTest()
    }

    /** 一次性诊断：用内置英文语音喂本地 VAD，验证原生库判语音能力（不影响主流程）。 */
    private fun runVadSelfTest() {
        Thread {
            val pcm = loadAsset("test_input.pcm") ?: return@Thread
            var total = 0; var speech = 0
            val v = LocalVad { s -> total++; if (s) speech++ }
            v.feed(pcm)
            v.release()
            Log.i(
                TAG,
                "VAD self-test: frames=$total speechFrames=$speech ratio=" +
                        "${if (total > 0) speech * 100 / total else 0}%"
            )
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            controller?.start()
        } else if (requestCode == 1) {
            setStatus("未授予麦克风权限，自动模式不可用")
        }
    }

    // ---------- 本地监听（自动模式，零费用，不连服务端）----------

    private fun startMonitoring() {
        if (monitorCapture != null) return
        localVad?.flush()
        monitorCapture = AudioCapture { frame -> localVad?.feed(frame) }
        val ok = monitorCapture?.start() ?: false
        Log.i(TAG, "monitor capture started=$ok")
        if (!ok) {
            monitorCapture = null
            appendSystem("监听：麦克风不可用")
        }
        if (BuildConfig.DEBUG) simulateSpeechOnce()
    }

    /**
     * 仅 DEBUG 构建：模拟"用户说话"，用于在无麦克风环境验证「说话→重拨」链路。
     * release 构建不含此逻辑。
     */
    private fun simulateSpeechOnce() {
        Thread {
            try { Thread.sleep(2500) } catch (_: InterruptedException) { return@Thread }
            if (controller?.state != AutoCallController.State.MONITORING) return@Thread
            Log.i(TAG, "DEBUG: simulate user speech → feed VAD")
            monitorCapture?.stop(); monitorCapture = null   // 停掉静音采集，避免与模拟语音交错
            val pcm = loadAsset("test_input.pcm") ?: return@Thread
            var off = 0
            while (off < pcm.size) {
                val n = minOf(LocalVad.FRAME_BYTES, pcm.size - off)
                localVad?.feed(pcm.copyOfRange(off, off + n))
                off += n
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }.start()
    }

    private fun stopMonitoring() {
        monitorCapture?.stop(); monitorCapture = null
        localVad?.flush()
    }

    // ---------- 既有主流程（未改动核心逻辑）----------

    private fun startCall() {
        val key = BuildConfig.VOLC_API_KEY
        if (key.isEmpty()) {
            toast("缺少 API Key（构建时未注入）"); return
        }
        if (callActive) return
        userBuf.setLength(0); aiBuf.setLength(0); receivedAudioBytes = 0
        render()
        setStatus("连接中…")

        client = SeeduplexClient(key, this).also {
            it.connect(
                instructions = "You are Emma, a friendly English conversation partner for " +
                        "Chinese learners. Always reply in natural, simple English, keep " +
                        "answers to one or two short sentences, and gently invite the user to continue.",
                voice = "zh_female_vv_jupiter_bigtts"
            )
        }
        player = AudioPlayer(SeeduplexClient.OUT_RATE).also {
            val ok = it.start()
            Log.i(TAG, "AudioPlayer started=$ok")
            if (!ok) appendSystem("AudioTrack 不可用（当前环境无音频输出），仅统计收到的音频字节")
        }
        capture = AudioCapture { frame -> client?.sendAudio(frame) }
        val micOk = capture?.start() ?: false
        Log.i(TAG, "AudioCapture started=$micOk")
        if (!micOk) appendSystem("麦克风不可用（模拟器）→ 请点「测试音频」喂内置语音")

        callActive = true
        btnCall.text = getString(R.string.btn_hangup)
    }

    private fun hangup() {
        capture?.stop(); capture = null
        player?.stop(); player = null
        client?.close(); client = null
        callActive = false
        btnCall.text = getString(R.string.btn_call)
        setStatus("已挂断")
        controller?.onCallEnded()   // 通知状态机（自动模式下进入监听）
    }

    /** 无麦克风环境下的测试通道：把内置 PCM 按 20ms 节奏喂给模型。 */
    private fun feedTestAudio() {
        val c = client ?: run { toast("请先「拨打」建立会话"); return }
        if (!callActive) { toast("请先「拨打」"); return }
        capture?.stop(); capture = null
        appendSystem("(已暂停麦克风采集)")
        Thread {
            val pcm = loadAsset("test_input.pcm")
            if (pcm == null) {
                main.post { appendSystem("未找到 assets/test_input.pcm") }
                return@Thread
            }
            main.post { appendSystem("开始发送测试音频 ${pcm.size} 字节…") }
            var off = 0
            while (off < pcm.size) {
                val n = minOf(SeeduplexClient.FRAME_BYTES, pcm.size - off)
                c.sendAudio(pcm.copyOfRange(off, off + n))
                off += n
                try { Thread.sleep(20) } catch (_: InterruptedException) {}
            }
            c.sendCommit()
            main.post { appendSystem("测试音频发送完毕，等待模型回复…") }
        }.start()
    }

    private fun loadAsset(name: String): ByteArray? = try {
        assets.open(name).use { it.readBytes() }
    } catch (e: Exception) {
        Log.w(TAG, "loadAsset($name) failed: ${e.message}"); null
    }

    // ---------- SeeduplexClient.Listener ----------

    override fun onStatus(msg: String) { main.post { setStatus(msg) } }

    override fun onSessionCreated(sessionId: String?) {
        controller?.onCallConnected()
        main.post {
            setStatus("✅ 会话已建立")
            appendSystem("session.created id=$sessionId")
        }
    }

    override fun onUserText(delta: String, final: Boolean) {
        controller?.onUserActivity()
        main.post {
            userBuf.setLength(0)
            if (delta.isNotEmpty()) userBuf.append(delta)
            if (final) userBuf.append("\n")
            render()
        }
    }

    override fun onAssistantText(delta: String) {
        main.post {
            aiBuf.append(delta); render()
        }
    }

    override fun onAudioDelta(pcm: ByteArray) {
        controller?.onAiAudio()
        player?.write(pcm)
        receivedAudioBytes += pcm.size
    }

    override fun onAudioDone() {
        main.post {
            aiBuf.append("\n"); render()
            setStatus("🔊 收到 AI 音频累计 ${receivedAudioBytes} 字节（约 ${receivedAudioBytes / 48}ms @24k/16bit）")
        }
    }

    override fun onError(msg: String) {
        main.post {
            setStatus("❌ $msg")
            appendSystem(msg)
        }
    }

    // ---------- helpers ----------

    private fun render() {
        transcriptTv.text = "👤 用户: $userBuf\n\n🤖 Emma: $aiBuf"
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendSystem(text: String) {
        aiBuf.append("[系统] ").append(text).append("\n")
        render()
    }

    private fun setStatus(s: String) = main.post { statusTv.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        controller?.release()
        stopMonitoring()
        localVad?.release()
        if (callActive) hangup()
        super.onDestroy()
    }

    companion object { const val TAG = "MMD-English" }
}
