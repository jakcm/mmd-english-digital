package com.mmd.englishdigital

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
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
    private lateinit var btnCall: ImageButton
    private lateinit var btnTest: ImageButton
    private lateinit var btnRotate: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var swAuto: Switch
    private lateinit var avatarView: TextureView

    private var client: SeeduplexClient? = null
    private var capture: AudioCapture? = null
    private var player: AudioPlayer? = null

    // ---- 软件回声消除（无硬件 AEC 的设备，如安卓电视）----
    private var aec: Aec3Processor? = null

    // ---- 数字人视频头像（循环静音播放）----
    private var avatarPlayer: MediaPlayer? = null

    // ---- 自动模式（不影响 SeeduplexClient / AudioIo）----
    private var controller: AutoCallController? = null
    private var localVad: LocalVad? = null
    private var monitorCapture: AudioCapture? = null

    // ---- 通话中打断 / barge-in（用户插话即停播并进入下一轮）----
    private var bargeVad: LocalVad? = null
    @Volatile private var aiResponding = false
    @Volatile private var bargeArmed = false
    @Volatile private var dropAudio = false
    @Volatile private var bargeSilenceSeen = false
    /**
     * [v2.6] 本地双讲检出参数：VOICE_COMMUNICATION 下 HAL 把 AI 回声压低约 30dB，
     * 因此"帧能量门 + VAD + 连续时长"即可稳定区分"用户插话"与"AI 回声"。
     */
    private var bargeRunMs = 0L
    @Volatile private var lastFrameRms = 0.0
    private var bargeRmsThreshold = 300.0   // 20ms 帧 RMS 门限（int16 域）
    private var bargeHoldMs = 200L          // 连续判定时长
    private var bargeDbg = 0L               // 诊断计数（BARGE-PROBE 日志节流）

    private val main = Handler(Looper.getMainLooper())
    private val userBuf = StringBuilder()
    private val aiBuf = StringBuilder()
    private var callActive = false
    private var receivedAudioBytes = 0L
    private var lastStatus: String = ""
    // [离线标定] 三路录音落盘（--ez dumpAudio true）：用于离线分析回声时延与三类信号。
    //   mic_raw.pcm  麦克风原始音频(16k)
    //   mic_clean.pcm AEC 处理后、发往服务端的音频(16k)
    //   far_end.pcm  AI 实际播出的音频(24k)  ← 用它才能精确标定"AI 是否在播"
    private var dumpRaw: java.io.FileOutputStream? = null
    private var dumpClean: java.io.FileOutputStream? = null
    private var dumpRender: java.io.FileOutputStream? = null
    private var dumpT0Mic = false
    private var dumpT0Fe = false
    /** 上行门控：仅在 AI 播报期生效，以 far-end 电平为参考判据挡掉 AI 自身回声（方案3）。 */
    private var uplinkGate: FarEndGate? = null
    /** 软件 AGC：补偿原始音源无 AGC 导致的电平过低。 */
    private val softwareAgc = SimpleAgc()
    /** 神经网络回声消除（DTLN-aec）——本机唯一有效的 AEC（线性 AEC3 实测 ERLE≈0）。 */
    private var dtlnAec: DtlNAec? = null
    /** 已写入的麦克风帧数（每帧 640B=20ms），用于配合锚点做精确时间轴还原。 */
    private var dumpFrames = 0L
    /** --ez noAuto true：启动不自动拨号（用于需要"单次通话、录音无缺口"的标定实验）。 */
    private var noAutoDial = false
    // [半双工验证] 采集源 / 播放用途可切换：电视在 VOICE_COMMUNICATION 组合下会"播放时掐麦克风"
    private var audioSrc = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private var audioUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION
    /**
     * 本地 VAD 打断开关。
     * 仅当**硬件 AEC 真正生效**时启用：
     *  - 手机：硬件 AEC 生效 → 麦克风听不到 AI 自己的声音 → 本地 VAD 可靠，可做低延迟打断；
     *  - 电视：无硬件 AEC（或形同虚设），靠软件 AEC3 兜底，但实测残余回声仍有 0~19dB 波动，
     *    本地 VAD 会把 AI 自己的声音判成"用户插话" → AI 一开口就被自己打断。
     *    此时关闭本地打断，改由**服务端**（input_audio_transcription.started）判定用户开口，避免自打断。
     */
    private var localBargeEnabled = true

    // API Key 仅存本机（明文），不写入源码或 APK
    private val prefs by lazy { getSharedPreferences("mmd_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        localVad = LocalVad { isSpeech -> controller?.onMonitorSpeech(isSpeech) }
        audioSrc = intent?.getIntExtra("src", MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            ?: MediaRecorder.AudioSource.VOICE_COMMUNICATION
        audioUsage = intent?.getIntExtra("usage", AudioAttributes.USAGE_VOICE_COMMUNICATION)
            ?: AudioAttributes.USAGE_VOICE_COMMUNICATION
        // [v2.5] 神经网络 AEC（DTLN-aec）——替代无效的线性 AEC3；--ez nnAec true 启用
        val useNnAec = intent?.getBooleanExtra("nnAec", false) == true
        if (useNnAec) {
            try {
                dtlnAec = DtlNAec(this, intent?.getIntExtra("feDelayMs", 150) ?: 150)
                Log.i(TAG, "已启用 DTLN 神经网络 AEC（echo-cancel）+ far-end 延迟 ${intent?.getIntExtra("feDelayMs", 150) ?: 150}ms")
            } catch (e: Throwable) {
                Log.e(TAG, "DTLN AEC 初始化失败，降级：${e.message}")
                dtlnAec = null
            }
        }
        // [方案3] 原始音源 + 软件 AGC + far-end 参考门控；不再需要时 gateRms=0
        val gateRms = intent?.getIntExtra("gateRms", if (useNnAec) 0 else 1) ?: 1
        if (gateRms > 0) {
            uplinkGate = FarEndGate(
                echoGain = (intent?.getIntExtra("echoGain", 45) ?: 45) / 1000.0,
                ratio = (intent?.getIntExtra("gateRatio", 16) ?: 16) / 10.0,
                preRollMs = 300, holdMs = 500,
                silentFrame = ByteArray(640),
                agc = softwareAgc,
            )
            Log.i(TAG, "上行门控已启用: 回声系数=${(intent?.getIntExtra("echoGain", 45) ?: 45) / 1000.0} 倍数=${(intent?.getIntExtra("gateRatio", 16) ?: 16) / 10.0}")
        } else {
            Log.i(TAG, "上行门控已禁用（AGC 仍生效）")
        }
        softwareAgc.reset()
        Log.i(TAG, "音频配置: source=$audioSrc usage=$audioUsage")
        // [离线标定] 三路录音落盘（仅在带 --ez dumpAudio true 启动时开启，正式版不影响）
        if (intent?.getBooleanExtra("dumpAudio", false) == true) {
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                dumpRaw = java.io.FileOutputStream(java.io.File(dir, "mic_raw.pcm"))
                dumpClean = java.io.FileOutputStream(java.io.File(dir, "mic_clean.pcm"))
                dumpRender = java.io.FileOutputStream(java.io.File(dir, "far_end.pcm"))
                Log.i(TAG, "离线标定录音中 → ${dir.absolutePath}/{mic_raw,mic_clean,far_end}.pcm")
            } catch (e: Exception) { Log.w(TAG, "dump open failed: ${e.message}") }
        }
        bargeVad = LocalVad { isSpeech -> onBargeVad(isSpeech) }
        controller = AutoCallController(object : AutoCallController.Callbacks {
            override fun requestDial() = startCall()
            override fun requestHangup() = hangup()
            override fun requestStartMonitoring() = startMonitoring()
            override fun requestStopMonitoring() = stopMonitoring()
            override fun onStateChanged(label: String) { setStatus(label) }
        })

        applyLayout()

        if (apiKey().isEmpty()) {
            refreshKeyStatus()
            promptForKey()               // 首次启动：先让用户填写 API Key
        } else {
            refreshKeyStatus()
            ensurePermissionThenAutoStart()
        }

        runVadSelfTest()
    }

    // ---------- API Key：本机填写与保存（不走构建注入，APK 内不含 Key）----------

    private fun apiKey(): String = prefs.getString(PREF_KEY, "") ?: ""

    private fun refreshKeyStatus() {
        setStatus(if (apiKey().isEmpty()) "⚠️ 未设置 API Key（点右上角 ⚙ 填写）" else "API Key 已就绪")
    }

    private fun ensurePermissionThenAutoStart() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else {
            noAutoDial = intent?.getBooleanExtra("noAuto", false) == true
            if (noAutoDial) {
                controller?.enabled = false
                setStatus("未自动拨号（noAuto）——请手动点「拨打」")
            } else {
                controller?.start()   // 默认启动即拨打
            }
        }
    }

    /** 设置页：填写 / 修改 / 清空 API Key（本地明文保存）。 */
    private fun promptForKey() {
        val edit = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = "X-Api-Key"
            setText(apiKey())
            setSingleLine(true)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置实时语音 API Key")
            .setMessage("填入火山引擎 Seeduplex 的 API Key，仅保存在本机，不写入 APK。")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit().putString(PREF_KEY, edit.text.toString().trim()).apply()
                refreshKeyStatus()
                appendSystem(if (apiKey().isEmpty()) "API Key 已清空" else "API Key 已保存")
                if (apiKey().isNotEmpty()) ensurePermissionThenAutoStart()
            }
            .setNeutralButton("清空") { _, _ ->
                prefs.edit().remove(PREF_KEY).apply()
                refreshKeyStatus()
                appendSystem("API Key 已清空")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- UI 绑定（支持横竖屏切换重建）----------

    private fun applyLayout() {
        setContentView(R.layout.activity_main)
        hideStatusBar()
        statusTv = findViewById(R.id.status)
        transcriptTv = findViewById(R.id.transcript)
        scroll = findViewById(R.id.scroll)
        btnCall = findViewById(R.id.btnCall)
        btnTest = findViewById(R.id.btnTest)
        btnRotate = findViewById(R.id.btnRotate)
        btnSettings = findViewById(R.id.btnSettings)
        swAuto = findViewById(R.id.swAuto)
        avatarView = findViewById(R.id.avatarView)

        btnCall.setOnClickListener { if (callActive) hangup() else startCall() }
        btnTest.setOnClickListener { feedTestAudio() }
        btnRotate.setOnClickListener { toggleOrientation() }
        btnSettings.setOnClickListener { promptForKey() }

        swAuto.isChecked = controller?.enabled ?: true
        swAuto.setOnCheckedChangeListener { _, checked ->
            controller?.enabled = checked
            if (checked) appendSystem("自动模式：开") else appendSystem("自动模式：关（回到手动）")
        }

        updateCallUi()
        render()
        if (lastStatus.isNotEmpty()) statusTv.text = lastStatus
        startAvatar()
    }

    /** 隐藏系统状态栏（沉浸式），观感更干净。 */
    private fun hideStatusBar() {
        try {
            val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            c.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Exception) {
            Log.w(TAG, "hideStatusBar failed: ${e.message}")
        }
    }

    private fun updateCallUi() {
        if (callActive) {
            btnCall.setImageResource(R.drawable.ic_call_end)
            btnCall.setBackgroundResource(R.drawable.bg_btn_ghost)
            btnCall.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.danger)
            )
        } else {
            btnCall.setImageResource(R.drawable.ic_phone)
            btnCall.setBackgroundResource(R.drawable.bg_btn_primary)
            btnCall.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.bg_top)
            )
        }
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()   // 旋转后按新方向重建布局，通话状态不受影响
    }

    // ---------- 数字人视频头像：循环 + 静音 ----------

    private fun startAvatar() {
        avatarView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                playAvatar(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releaseAvatar(); return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        // 若 surface 已就绪，立即播放
        if (avatarView.isAvailable) {
            avatarView.surfaceTexture?.let { playAvatar(Surface(it)) }
        }
    }

    private fun playAvatar(surface: Surface) {
        releaseAvatar()
        avatarPlayer = try {
            MediaPlayer().apply {
                setDataSource(
                    this@MainActivity,
                    Uri.parse("android.resource://$packageName/${R.raw.avatar}")
                )
                setSurface(surface)
                isLooping = true
                setVolume(0f, 0f)   // 静音：数字人视频不发声音，避免干扰对话
                setOnPreparedListener { start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "avatar play failed: ${e.message}"); null
        }
    }

    private fun releaseAvatar() {
        try { avatarPlayer?.release() } catch (_: Exception) {}
        avatarPlayer = null
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
            if (!noAutoDial) controller?.start()
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
            monitorCapture?.stop(); monitorCapture = null
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

    // ---------- 主流程 ----------

    private fun startCall() {
        val key = apiKey()
        if (key.isEmpty()) {
            toast("请先在设置中填写 API Key")
            promptForKey()
            return
        }
        if (callActive) return
        userBuf.setLength(0); aiBuf.setLength(0); receivedAudioBytes = 0
        aiResponding = false; bargeArmed = false; dropAudio = false; bargeSilenceSeen = false
        bargeVad?.flush()
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

        player = AudioPlayer(SeeduplexClient.OUT_RATE, audioUsage).also {
            val ok = it.start()
            Log.i(TAG, "AudioPlayer started=$ok")
            if (!ok) appendSystem("AudioTrack 不可用（当前环境无音频输出），仅统计收到的音频字节")
        }
        // [音源保真度验证] --ez testTone true：通过播放器发 3 秒宽带白噪声（固定种子，可跨音源对比），
        // 麦克风同时采集 → 用回声频谱判断各音源在"本地播放期"是否保住了高频（人声可懂度）。
        if (intent?.getBooleanExtra("testTone", false) == true) {
            Thread {
                val sr = SeeduplexClient.OUT_RATE
                val n = sr * 3
                val tone = ByteArray(n * 2)
                val rnd = java.util.Random(42L)
                for (i in 0 until n) {
                    val v = rnd.nextInt(12001) - 6000
                    tone[2 * i] = (v and 0xFF).toByte()
                    tone[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                }
                main.post { appendSystem("🔊 [验证] 开始播放 3 秒宽带测试噪声") }
                var off = 0
                while (off < tone.size) {
                    val m = minOf(960, tone.size - off)
                    player?.write(tone.copyOfRange(off, off + m))
                    off += m
                    try { Thread.sleep(20) } catch (_: InterruptedException) {}
                }
                main.post { appendSystem("🔊 [验证] 测试音播放结束") }
            }.start()
        }
        capture = AudioCapture(audioSrc) { frame ->
            // [离线标定] 落盘原始麦克风音频
            dumpRaw?.let { f -> try { f.write(frame) } catch (_: Exception) {} }
            dumpFrames++
            if (!dumpT0Mic) { dumpT0Mic = true; Log.i(TAG, "DUMP-T0 mic=${System.currentTimeMillis()}") }
            // 先做回声消除（优先神经网络 AEC），再同时喂给服务端与本地打断 VAD
            val clean = dtlnAec?.process(frame) ?: aec?.process(frame) ?: frame
            if (clean.isNotEmpty()) {
                // [上行门控] 播报期只放行人声帧，挡掉 AI 自身回声（非播报期全量透传）
                val sent = uplinkGate?.process(clean, aiResponding) ?: listOf(clean)
                for (f in sent) {
                    dumpClean?.let { out -> try { out.write(f) } catch (_: Exception) {} }
                    client?.sendAudio(f)
                }
            }
            // 记录本帧能量（供本地双讲检出使用；400~1800=用户插话，个位数~几十=AI回声）
            run {
                var s = 0.0
                var k = 0
                while (k + 1 < clean.size) {
                    val v = (((clean[k + 1].toInt() shl 8) or (clean[k].toInt() and 0xFF)).toShort()).toInt()
                    s += v.toDouble() * v
                    k += 2
                }
                val n = clean.size / 2
                if (n > 0) lastFrameRms = Math.sqrt(s / n)
            }
            bargeVad?.feed(clean)
        }
        val micOk = capture?.start() ?: false
        // 精确时间轴锚点：本次采集开始时的"已写帧数"与墙钟时间（应对采集重启造成的录音缺口）
        Log.i(TAG, "DUMP-ANCHOR frames=$dumpFrames ts=${System.currentTimeMillis()}")
        Log.i(TAG, "AudioCapture started=$micOk")
        if (!micOk) appendSystem("麦克风不可用（模拟器）→ 请点「测试音频」喂内置语音")

        // 软件 AEC3：依据“硬件 AEC 是否真正生效”决定，而不是仅看 isAvailable()。
        // 坑（本机实测）：部分安卓电视 AcousticEchoCanceler.isAvailable() 返回 true，
        // 但 create()/enabled 实际失败（AudioCapture 里 active=false，效果器是空壳），
        // 此时回声完全没被消除。仅用 isAvailable() 判断会漏判 → 软件 AEC3 不启用 → bug 依旧。
        val hwAec = capture?.hwAecActive == true
        val delayMs = player?.bufferMs ?: 0
        // [v2.7 纠正] 之前的 v2.6.1 误判：把"VOICE_COMMUNICATION + 软件AEC3"组合下的尖峰归咎于 AEC3，
        // 于是关掉了 AEC3 —— 结果录到了回声全漏的信号（1457）。回看 f2 录音的真实数据：
        //   VOICE_COMMUNICATION + 软件AEC3  → AI 单独播报时麦克风仅 2~27（回声被消 ~40dB），用户说话 599~3849
        //   即 25~30dB 分离度，能量门+VAD 足以区分。故恢复 AEC3 启用，重新做纠正实验。
        aec = if (hwAec) null else Aec3Processor.createOrNull(delayMs)
        player?.onRender = { pcm ->
            // [离线标定] 落盘 far-end（AI 实际播出的音频，24kHz）
            dumpRender?.let { f -> try { f.write(pcm) } catch (_: Exception) {} }
            if (!dumpT0Fe) { dumpT0Fe = true; Log.i(TAG, "DUMP-T0 far_end=${System.currentTimeMillis()}") }
            aec?.feedRender(pcm)
            dtlnAec?.feedRender(pcm)   // 神经网络 AEC 的 far-end 参考
            // 更新门控参考电平（带 ~200ms 衰减保持，匹配回声的短时包络）
            uplinkGate?.let { g ->
                var s = 0.0; var i = 0
                while (i + 1 < pcm.size) {
                    val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                    s += v.toDouble() * v; i += 2
                }
                val r = if (pcm.isNotEmpty()) kotlin.math.sqrt(s / (pcm.size / 2)) else 0.0
                val keep = g.farEndLevel * 0.90
                g.farEndLevel = if (r > keep) r else keep
            }
        }
        // 关键修复：本地 VAD 打断只在硬件 AEC 真正生效时启用；否则回声残留会误判为"用户插话"。
        // [v2.6] 新增 --ez localBarge true：配合新判据（能量门+VAD+时长）在无硬件 AEC 的设备上实验。
        localBargeEnabled = hwAec || (intent?.getBooleanExtra("localBarge", false) == true)
        bargeRmsThreshold = (intent?.getIntExtra("bargeRms", 300) ?: 300).toDouble()
        bargeHoldMs = (intent?.getIntExtra("bargeHold", 200) ?: 200).toLong()
        Log.i(TAG, "software AEC3 = ${aec != null} (hwAecActive=$hwAec, delayMs=$delayMs, playerBufferMs=${player?.bufferMs}, localBarge=$localBargeEnabled, bargeRms=${bargeRmsThreshold.toInt()}, bargeHold=${bargeHoldMs}ms)")
        if (aec != null) appendSystem("🎧 软件 AEC3 已启用（硬件回声消除未生效）")

        callActive = true
        main.post { updateCallUi() }
    }

    private fun hangup() {
        player?.onRender = null
        capture?.stop(); capture = null
        player?.stop(); player = null
        aec?.release(); aec = null
        client?.close(); client = null
        aiResponding = false; bargeArmed = false; dropAudio = false; bargeSilenceSeen = false
        bargeVad?.flush()
        callActive = false
        main.post { updateCallUi() }
        setStatus("已挂断")
        controller?.onCallEnded()   // 通知状态机（自动模式下进入监听）
    }

    // ---------- 打断 / barge-in ----------

    /**
     * [v2.6] 本地双讲检出（替代原"仅 VAD"判据）。
     * 判据：AI 正在回复 且 该帧 VAD=语音 且 帧能量 > bargeRmsThreshold 且 连续 ≥ bargeHoldMs。
     * 依据：VOICE_COMMUNICATION 下 AI 回声被 HAL 压低约 30dB（个位数~几十），
     *      用户插话时达 400~1800，两者分离度 >25dB，配合能量门即可稳定区分。
     */
    private fun onBargeVad(isSpeech: Boolean) {
        if (!callActive) return
        // 诊断日志：AI 播报期间每秒记录一次帧能量与 VAD（用于计算误报/检出率）
        if (aiResponding) {
            bargeDbg++
            if (bargeDbg % 50L == 0L) Log.i(TAG, "BARGE-PROBE rms=${lastFrameRms.toInt()} vad=$isSpeech run=${bargeRunMs}ms")
        }
        if (!localBargeEnabled) return
        if (!aiResponding || bargeArmed) { bargeRunMs = 0; return }
        val voiced = isSpeech && lastFrameRms > bargeRmsThreshold
        bargeRunMs = if (voiced) bargeRunMs + 20 else 0
        if (bargeRunMs < bargeHoldMs) return
        // 连续满足 → 判定用户插话，立即打断
        bargeArmed = true
        dropAudio = true
        aiResponding = false
        player?.interrupt()
        aec?.clearRender()
        client?.sendCancel()
        main.post { appendSystem("⏹ 本地双讲检出（能量 ${lastFrameRms.toInt()} ≥ ${bargeRmsThreshold.toInt()}，连续 ${bargeRunMs}ms）→ 打断 AI 播报") }
        Log.i(TAG, "BARGE-HIT rms=${lastFrameRms.toInt()} run=${bargeRunMs}ms")
    }

    /** 服务端已检测到用户开口：立即清空本地播放缓冲（与官方网页一致）。 */
    private fun onServerBarge() {
        if (!callActive || !aiResponding) return
        bargeArmed = true
        dropAudio = true
        aiResponding = false
        player?.interrupt()
        aec?.clearRender()
        main.post { appendSystem("⏹ 服务端检出用户开口 → 停止播报，进入下一轮") }
    }

    /** 无麦克风环境下的测试通道：把内置 PCM 按 20ms 节奏喂给模型。 */
    private fun feedTestAudio() {
        val c = client ?: run { toast("请先「拨打」建立会话"); return }
        if (!callActive) { toast("请先「拨打」"); return }
        if (intent?.getBooleanExtra("keepMic", false) == true) {
            appendSystem("(保留麦克风采集 — 半双工验证)")
        } else {
            capture?.stop(); capture = null
            appendSystem("(已暂停麦克风采集)")
        }
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
        // 关键词打断（服务端增量转写；前 6 字命中"停/不对"即视为用户打断）
        // A2：叠加在既有打断（本地 VAD / 服务端 started）之上，作为"精确打断"补充。
        if (isBargeCommand(delta)) onKeywordBarge()
        // 退出指令：整句、前 7 字内含"退出/关闭"
        if (final && isExitCommand(delta)) {
            main.post { exitByVoice() }
            return
        }
        main.post {
            userBuf.setLength(0)
            if (delta.isNotEmpty()) userBuf.append(delta)
            if (final) userBuf.append("\n")
            render()
        }
    }

    /**
     * 关键词打断判定（服务端转写文本，仅看前 3 个字）：
     * 命中「停」或「不对」即视为用户想插话——
     * 「停」已覆盖 停止/暂停/停下/停一下（暂停、停下、停一下 的前 3 字里都含"停"）。
     * 用增量(delta)即可判定，无需等整句 completed，保证打断及时。
     */
    private fun isBargeCommand(text: String): Boolean {
        val head = text.trim().take(3)
        val hit = head.contains("停") || head.contains("不对")
        if (hit) Log.i(TAG, "关键词命中: head=「$head」 aiResponding=$aiResponding callActive=$callActive")
        return hit
    }

    /** 关键词打断：立即停播 + 取消当前回复，进入下一轮。 */
    private fun onKeywordBarge() {
        if (!callActive || !aiResponding || bargeArmed) return
        bargeArmed = true
        dropAudio = true
        aiResponding = false
        player?.interrupt()
        client?.sendCancel()
        main.post { appendSystem("⏹ 关键词打断（停/不对）→ 停止播报，进入下一轮") }
    }

    /** 语音退出判定：整句文本「前 7 个字」内含有"退出"或"关闭"。 */
    private fun isExitCommand(text: String): Boolean {
        val head = text.trim().take(7)
        return head.contains("退出") || head.contains("关闭")
    }

    /** 执行语音退出：先挂断会话，再结束任务并结束进程（C1 / D1）。 */
    private fun exitByVoice() {
        Log.i(TAG, "语音退出指令命中 → 退出应用")
        try { appendSystem("⏹ 收到退出指令，正在退出…") } catch (_: Exception) {}
        controller?.release()                      // 停自动模式，避免挂断后进入监听/重拨
        try { if (callActive) hangup() } catch (_: Exception) {}
        stopMonitoring()
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onAssistantText(delta: String) {
        main.post {
            aiBuf.append(delta); render()
        }
    }

    override fun onAudioStarted() {
        aiResponding = true
        bargeArmed = false
        dropAudio = false
        bargeSilenceSeen = false
    }

    override fun onAudioDelta(pcm: ByteArray) {
        controller?.onAiAudio()
        if (dropAudio) return
        player?.write(pcm)
        receivedAudioBytes += pcm.size
    }

    override fun onAudioDone() {
        aiResponding = false
        main.post {
            aiBuf.append("\n"); render()
            setStatus("🔊 收到 AI 音频累计 ${receivedAudioBytes} 字节（约 ${receivedAudioBytes / 48}ms @24k/16bit）")
        }
    }

    override fun onUserTurnStarted() {
        onServerBarge()
    }

    override fun onResponseCanceled() {
        main.post { appendSystem("服务端已取消当前回复") }
    }

    override fun onError(msg: String) {
        main.post {
            setStatus("❌ $msg")
            appendSystem(msg)
        }
    }

    // ---------- helpers ----------

    private fun render() {
        if (!::transcriptTv.isInitialized) return
        transcriptTv.text = "👤 用户: $userBuf\n\n🤖 Emma: $aiBuf"
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendSystem(text: String) {
        aiBuf.append("[系统] ").append(text).append("\n")
        render()
    }

    private fun setStatus(s: String) = main.post {
        lastStatus = s
        if (::statusTv.isInitialized) statusTv.text = s
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        try { dumpRaw?.close() } catch (_: Exception) {}
        try { dumpClean?.close() } catch (_: Exception) {}
        try { dumpRender?.close() } catch (_: Exception) {}
        releaseAvatar()
        controller?.release()
        stopMonitoring()
        localVad?.release()
        bargeVad?.release()
        if (callActive) hangup()
        super.onDestroy()
    }

    companion object {
        const val TAG = "MMD-English"
        const val PREF_KEY = "volc_api_key"
    }
}
