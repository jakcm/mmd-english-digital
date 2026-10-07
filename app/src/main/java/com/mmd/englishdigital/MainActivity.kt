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
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator

class MainActivity : AppCompatActivity(), SeeduplexClient.Listener,
    com.bytedance.speech.speechengine.SpeechEngine.SpeechListener {

    private lateinit var statusTv: TextView
    private lateinit var bubbleBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var btnCall: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnSettings: ImageButton
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
    /** 3018 播放音频帧计数（只统计，不打印内容） */
    /** ★ AI 本轮播报：已收到音频字节数 + 首帧时刻（用于推算播放终点） */
    @Volatile private var aiAudioBytesTotal = 0L
    @Volatile private var aiAudioFirstAt = 0L
    /** 播放终点安全余量（SDK 内部缓冲/抖动） */
    private val AI_PLAY_MARGIN_MS = 1500L
    /** ★ 播放终点（在 3011 时一次性算定的定值；0 = 未定） */
    @Volatile private var aiPlaybackEndsAt = 0L
    /** ★ 服务端声明的本轮音频总时长（ms），来自 3009 的 sentence_duration.sentence_end_time */
    @Volatile private var aiAudioDurationMs = 0L
    /** ★ 本轮 AI 播报起点（首个 3008 时刻），作为播放起点锚 */
    @Volatile private var aiTurnStartedAt = 0L
    /** "推算播放已结束"只打一次日志 */
    @Volatile private var playbackEndLogged = false
    @Volatile private var playerAudioFrames = 0L
    @Volatile private var playerAudioBytes = 0L
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
    /** 当前句的中间识别结果（未定稿） */
    private var userCur = ""
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

    // ---- 前后台切换：退到后台即释放麦克风，回前台按配置恢复 ----
    /** 是否处于前台（onStart 之后、onStop 之前） */
    @Volatile private var isForeground = true
    /** 是否经历过一次退后台（首次 onStart 不重复拨号，交由 onCreate 处理） */
    private var wentBackground = false

    // API Key 仅存本机（明文），不写入源码或 APK
    private val prefs by lazy { getSharedPreferences("mmd_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 统一日志开关（默认关；--ez log true 开启；debug 包默认开）
        val logFlag = if (intent?.hasExtra("log") == true)
            intent.getBooleanExtra("log", false) else null
        val logging = L.init(logFlag)

        localVad = LocalVad { prob -> controller?.onMonitorSpeech(prob > 0.5f) }
        audioSrc = intent?.getIntExtra("src", MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            ?: MediaRecorder.AudioSource.VOICE_COMMUNICATION
        audioUsage = intent?.getIntExtra("usage", AudioAttributes.USAGE_VOICE_COMMUNICATION)
            ?: AudioAttributes.USAGE_VOICE_COMMUNICATION
        // [v2.5] 神经网络 AEC（DTLN-aec）——替代无效的线性 AEC3；--ez nnAec true 启用
        val useNnAec = intent?.getBooleanExtra("nnAec", false) == true
        if (useNnAec) {
            try {
                dtlnAec = DtlNAec(this, intent?.getIntExtra("feDelayMs", 150) ?: 150)
                L.i(TAG, "已启用 DTLN 神经网络 AEC（echo-cancel）+ far-end 延迟 ${intent?.getIntExtra("feDelayMs", 150) ?: 150}ms")
            } catch (e: Throwable) {
                L.e(TAG, "DTLN AEC 初始化失败，降级：${e.message}")
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
            L.i(TAG, "上行门控已启用: 回声系数=${(intent?.getIntExtra("echoGain", 45) ?: 45) / 1000.0} 倍数=${(intent?.getIntExtra("gateRatio", 16) ?: 16) / 10.0}")
        } else {
            L.i(TAG, "上行门控已禁用（AGC 仍生效）")
        }
        softwareAgc.reset()
        L.i(TAG, "音频配置: source=$audioSrc usage=$audioUsage")
        // [离线标定] 三路录音落盘（仅在带 --ez dumpAudio true 启动时开启，正式版不影响）
        if (logging && intent?.getBooleanExtra("dumpAudio", false) == true) {
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                dumpRaw = java.io.FileOutputStream(java.io.File(dir, "mic_raw.pcm"))
                dumpClean = java.io.FileOutputStream(java.io.File(dir, "mic_clean.pcm"))
                dumpRender = java.io.FileOutputStream(java.io.File(dir, "far_end.pcm"))
                L.i(TAG, "离线标定录音中 → ${dir.absolutePath}/{mic_raw,mic_clean,far_end}.pcm")
            } catch (e: Exception) { L.w(TAG, "dump open failed: ${e.message}") }
        }
        bargeVad = LocalVad { prob -> onBargeVad(prob) }
        controller = AutoCallController(object : AutoCallController.Callbacks {
            override fun requestDial() = startCall()
            override fun requestHangup() = hangup()
            override fun requestStartMonitoring() = startMonitoring()
            override fun requestStopMonitoring() = stopMonitoring()
            override fun onStateChanged(label: String) { setStatus(label) }
            // ★ 播报中不算闲置：服务端在生成 或 按音频字节推算播放尚未结束
            override fun isAiSpeaking() = aiResponding || aiPlaybackOngoing()

            /**
             * ★ 按「已收到音频字节数 ÷ 实时速率」推算播放终点。
             * 服务端 3011(TTS_ENDED) 只表示数据下发完毕；长回复时数据一次传完，
             * 播放器仍要播几十上百秒，必须按字节数推算才不会误判"播报已结束"。
             */
            fun aiPlaybackOngoing(): Boolean {
                val endAt = aiPlaybackEndsAt
                if (endAt == 0L) return false
                val ongoing = System.currentTimeMillis() < endAt
                if (!ongoing && !playbackEndLogged) {
                    playbackEndLogged = true
                    L.i(TAG, "★ 推算播放已结束 → 闲置计时从此刻起算")
                }
                return ongoing
            }
        })

        applyLayout()
        // ★ 启动即应用配置的「静默挂断秒数」（0 = 关闭自动模式；替代原自动模式开关）
        runCatching {
            val secCfg = prefs.getInt(KEY_SILENCE_SEC, AutoCallController.DEFAULT_IDLE_SILENCE_SEC)
            if (secCfg <= 0) {
                controller?.idleSilenceMs = 0L
                controller?.enabled = false
                L.i(TAG, "启动配置：静默挂断秒数=0 → 自动模式关闭")
            } else {
                controller?.idleSilenceMs = secCfg * 1000L
                L.i(TAG, "启动配置：静默挂断秒数=$secCfg → 自动模式开，阈值 ${secCfg * 1000}ms")
            }
        }

        // ★ 只检查官方 SDK 的三件套凭据（不再用旧链路的 apiKey()，否则会误弹设置框）
        val (cid, ckey, ctoken) = creds3()
        val hasCreds = cid.isNotBlank() && ckey.isNotBlank() && ctoken.isNotBlank()
        if (!hasCreds) {
            refreshKeyStatus()
            promptForKey()               // 首次启动：先让用户填写凭据
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
        val (curA, curK, curT) = creds3()
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        fun field(hint: String, value: String, secret: Boolean = false) =
            android.widget.EditText(this).apply {
                inputType = if (secret) {
                    android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    android.text.InputType.TYPE_CLASS_TEXT
                }
                this.hint = hint
                setSingleLine(true)
                // ★ 紧凑：缩小上下内边距与最小高度（手机屏竖向空间有限）
                val dpx = (3 * resources.displayMetrics.density).toInt()
                setPadding(0, dpx, 0, dpx)
                minimumHeight = (30 * resources.displayMetrics.density).toInt()
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_secondary))
                setText(value)
                // ★ 必须在 setText/setSingleLine 之后设置，否则会被 inputType 重置
                if (secret) {
                    transformationMethod =
                        android.text.method.PasswordTransformationMethod()
                }
                layoutParams = lp
            }
        val curAK = prefs.getString(PREF_KEY, "").orEmpty()
        val e1 = field("App ID", curA, secret = true)
        val e2 = field("App Key", curK, secret = true)
        val e3 = field("Access Token", curT, secret = true)
        // ★ 新版鉴权（双工协议必需）：API Key
        val e4 = field("API Key（双工协议必需）", curAK, secret = true)
        // 名称与输入框【同一行】：左标题固定比例，右输入框占余下宽度，避免换行
        fun row(name: String, f: android.widget.EditText) =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = name
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    isSingleLine = true
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 0.36f
                    )
                })
                addView(f, android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 0.64f
                ))
            }

        // ★ 静默挂断秒数（替代原「自动模式」开关；0 = 关闭自动模式）
        val curSec = prefs.getInt(KEY_SILENCE_SEC, AutoCallController.DEFAULT_IDLE_SILENCE_SEC)
        val e5 = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            this.hint = "10"
            setSingleLine(true)
            val dpx2 = (3 * resources.displayMetrics.density).toInt()
            setPadding(0, dpx2, 0, dpx2)
            minimumHeight = (30 * resources.displayMetrics.density).toInt()
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            setText(curSec.toString())
        }

        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 6, pad, 0)
            addView(row("App ID", e1))
            addView(row("App Key", e2))
            addView(row("Access Token", e3))
            addView(row("API Key（双工）", e4))
            addView(row("静默挂断秒数", e5))
        }
        // 字段变多，用 ScrollView 包裹避免小屏被裁切
        val boxScroll = android.widget.ScrollView(this).apply { addView(box) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setMessage("凭据仅存本机，不写入 APK（显示为星号）。")
            .setView(boxScroll)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString(KEY_APPID, e1.text.toString().trim())
                    .putString(KEY_APPKEY, e2.text.toString().trim())
                    .putString(KEY_TOKEN, e3.text.toString().trim())
                    .putString(PREF_KEY, e4.text.toString().trim())
                    .apply()
                // ★ 应用静默挂断秒数（0 = 关闭自动模式）
                val secIn = e5.text.toString().trim().toIntOrNull()
                    ?: AutoCallController.DEFAULT_IDLE_SILENCE_SEC
                prefs.edit().putInt(KEY_SILENCE_SEC, secIn).apply()
                applySilenceSec(secIn)
                refreshKeyStatus()
                appendSystem("设置已保存")
            }
            .setNeutralButton("清空") { _, _ ->
                prefs.edit().remove(KEY_APPID).remove(KEY_APPKEY).remove(KEY_TOKEN).remove(PREF_KEY).apply()
                refreshKeyStatus()
                appendSystem("凭据已清空")
            }
            .setNegativeButton("取消", null)
            .show()
            .also { d ->
                // ★ 加宽弹框（88% 屏宽），避免按钮被挤到可视区外
                d.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.88).toInt(),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
    }

    // ---------- UI 绑定（支持横竖屏切换重建）----------

    private fun applyLayout() {
        setContentView(R.layout.activity_main)
        hideStatusBar()
        statusTv = findViewById(R.id.status)
        bubbleBox = findViewById(R.id.bubbleBox)
        scroll = findViewById(R.id.scroll)
        btnCall = findViewById(R.id.btnCall)
        btnClose = findViewById(R.id.btnClose)
        btnSettings = findViewById(R.id.btnSettings)
        avatarView = findViewById(R.id.avatarView)

        btnCall.setOnClickListener { if (callActive) hangup() else startCall() }
        btnClose.setOnClickListener { exitApp() }
        btnSettings.setOnClickListener { promptForKey() }
        // ★ 启动自检：双工协议需要 API Key，缺失时明确提示
        if (prefs.getString(PREF_KEY, "").orEmpty().isBlank()) {
            main.postDelayed({
                if (prefs.getString(PREF_KEY, "").orEmpty().isBlank()) {
                    setStatus("⚠️ 未配置 API Key —— 点右上角 ⚙ 填写后才能听到你说话")
                    appendSystem("⚠️ 未检测到 API Key。双工协议使用新版鉴权（API Key + App Key），缺少 API Key 时不会识别你的语音。请点右上角 ⚙ → 在「API Key（双工）」一行填入，保存。")
                }
            }, 1200)
        }

        updateCallUi()
        render()
        if (lastStatus.isNotEmpty()) statusTv.text = displayStatus()
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
            L.w(TAG, "hideStatusBar failed: ${e.message}")
        }
    }

    private fun updateCallUi() {
        if (callActive) {
            // 通话中：红色挂断键
            btnCall.setImageResource(R.drawable.ic_call_end)
            btnCall.setBackgroundResource(R.drawable.bg_btn_ghost)
            btnCall.imageTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.danger))
        } else {
            // 待机 / 监听中 / 可拨号：绿色通话键
            btnCall.setImageResource(R.drawable.ic_phone)
            btnCall.setBackgroundResource(R.drawable.bg_btn_primary)
            btnCall.imageTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.bg_top))
            btnCall.alpha = 1f
        }
        // ★ 通话状态变化时同步刷新状态文案（通话中 / 待唤醒）
        if (::statusTv.isInitialized) statusTv.text = displayStatus()
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
            L.w(TAG, "avatar play failed: ${e.message}"); null
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
            val v = LocalVad { p -> total++; if (p > 0.5f) speech++ }
            v.feed(pcm)
            v.release()
            L.i(
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
        L.i(TAG, "monitor capture started=$ok")
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
            L.i(TAG, "DEBUG: simulate user speech → feed VAD")
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
        if (callActive) return
        // ★★★ v3.0：统一走官方 SpeechEngine SDK（内置多级 AEC，支持播报中打断）★★★
        startOfficialDialog()
    }

    // ---------- 官方 SpeechEngine SDK（v3.0 新引擎；UI/头像沿用原有）----------

    private var dialogEngine: com.bytedance.speech.speechengine.SpeechEngine? = null
    private var dialogStarted = false
    /**
     * ★ 一轮 AI 回复的三段内容（均已剥离括号），按决策 A1/B1/C1 归属：
     *   brace  {...}  → 用户气泡第二行（白字，用户内容的英文翻译）
     *   bracket[...]  → AI 气泡第一行（黑字，英文对答）
     *   corner 【...】 → AI 气泡第二行（灰字，中文翻译）
     */
    private class AiTurn {
        val brace = StringBuilder()
        val bracket = StringBuilder()
        val corner = StringBuilder()
        var seg = 0        // 0=段外 1=brace 2=bracket 3=corner
        /** ★ 该轮回复对应的「用户气泡序号」；-1 表示前面没有用户发言（如开场白） */
        var userIdx = -1
    }
    private val aiTurnList = ArrayList<AiTurn>()
    /** 系统提示（灰字气泡，渲染在末尾） */
    private val systemMsgs = ArrayList<String>()

    /** 当前已完成的用户发言条数（userBuf 中的非空行） */
    private fun countUserTurns(): Int =
        userBuf.toString().split("\n").count { it.isNotBlank() }

    /** 把流式增量喂进三段解析器（只拆段，不改动任何流式时序） */
    private fun feedAiText(delta: String) {
        if (aiTurnList.isEmpty()) {
            val t0 = AiTurn()
            t0.userIdx = countUserTurns() - 1
            aiTurnList.add(t0)
        }
        for (ch in delta) {
            // 新的 { 且上一轮已有内容 → 视为新一轮回复
            if (ch == '{' && (aiTurnList.last().bracket.isNotEmpty() ||
                        aiTurnList.last().corner.isNotEmpty())) {
                val nt = AiTurn()
                // ★ 显式关联到「此刻已完成的最后一条用户发言」。
                //   开场白没有前置用户发言 → userIdx=-1，不会再挤占后续索引导致整体错位。
                nt.userIdx = countUserTurns() - 1
                aiTurnList.add(nt)
            }
            val t = aiTurnList.last()
            when (ch) {
                '{' -> t.seg = 1
                '}' -> if (t.seg == 1) t.seg = 0
                '[' -> t.seg = 2
                ']' -> if (t.seg == 2) t.seg = 0
                '【' -> t.seg = 3
                '】' -> if (t.seg == 3) t.seg = 0
                else -> when (t.seg) {
                    1 -> t.brace.append(ch)
                    2 -> t.bracket.append(ch)
                    3 -> t.corner.append(ch)
                }
            }
        }
    }

    /** 最近一次 ASR 转写文本（用于 ASR_ENDED 时兜底 flush final） */
    private var lastAsrText = ""
    /** 上一次 ASR 分发是否为已定稿（避免 ASR_ENDED 重复 flush） */
    private var lastAsrWasFinal = false

    /**
     * ★ 应用「静默挂断秒数」：>0 → 开自动模式并设置阈值；=0 → 关闭自动模式。
     * 该值替代了原来的「自动模式」开关（单一事实来源）。
     */
    private fun applySilenceSec(sec: Int) {
        val c = controller ?: return
        if (sec <= 0) {
            c.enabled = false
            appendSystem("⏹ 自动模式：关（静默挂断秒数 = 0）")
            L.i(TAG, "静默挂断秒数=0 → 关闭自动模式")
        } else {
            c.idleSilenceMs = sec * 1000L
            c.enabled = true
            appendSystem("▶️ 自动模式：开（双方静默 ${sec}s 自动挂断；待机零费用）")
            L.i(TAG, "静默挂断秒数=$sec → 自动模式开，阈值 ${sec * 1000}ms")
        }
    }

    private fun creds3(): Triple<String, String, String> {
        val appid = prefs.getString(KEY_APPID, null)?.takeIf { it.isNotBlank() }
            ?: "2446422829"
        val appkey = prefs.getString(KEY_APPKEY, null)?.takeIf { it.isNotBlank() }
            ?: ""
        val token = prefs.getString(KEY_TOKEN, "") ?: ""
        return Triple(appid, appkey, token)
    }

    // ---------- 内置提示词：外部文件覆盖（adb 可改，改后下次启动生效）----------
    //
    // 路径：<应用外部私有目录>/sys_prompt.txt（即 /sdcard/Android/data/<包名>/files/sys_prompt.txt）
    // 规则：
    //   * 文件不存在 → 首次启动用内置默认 SYSTEM_PROMPT 写入该文件
    //   * 文件存在且非空 → 使用文件内容作为提示词
    //   * 文件为空 → 回退内置默认
    // adb 修改：push 覆盖该文件即可；删除该文件则下次启动恢复默认。不提供界面配置入口。
    private val promptFile by lazy {
        java.io.File(getExternalFilesDir(null) ?: filesDir, "sys_prompt.txt")
    }

    private fun effectivePrompt(): String = try {
        if (!promptFile.exists()) {
            promptFile.writeText(SYSTEM_PROMPT, Charsets.UTF_8)
            L.i(TAG, "首次创建提示词文件 → ${promptFile.absolutePath}")
        }
        val t = promptFile.readText(Charsets.UTF_8).trim()
        if (t.isNotEmpty()) t else SYSTEM_PROMPT
    } catch (e: Exception) {
        L.w(TAG, "读取提示词文件失败，回退内置默认：${e.message}")
        SYSTEM_PROMPT
    }

    /** 本次启动实际生效的提示词（惰性求值一次；外部文件优先） */
    private val activePrompt: String by lazy {
        effectivePrompt().also {
            L.i(TAG, "生效提示词长度=${it.length} 来源=${if (it == SYSTEM_PROMPT) "内置默认" else "外部文件"} 文件=${promptFile.absolutePath}")
        }
    }

    private fun startOfficialDialog() {
        val (pa, pk, pt) = creds3()
        // 调试用：允许 Intent 覆盖凭据（仅用于 adb 对照测试，正常使用走设置页）
        val appid = intent?.getStringExtra("appid") ?: pa
        val appkey = intent?.getStringExtra("appkey") ?: pk
        val token = intent?.getStringExtra("accessToken") ?: pt
        // 诊断：MD5 摘要（只输出哈希，不泄露凭据原值）
        fun md5(s: String): String = java.security.MessageDigest.getInstance("MD5")
            .digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        L.i(TAG, "凭据读取: appid=$appid appkey长度=${appkey.length} token长度=${token.length} 来源=${
            if (intent?.getStringExtra("appkey") != null) "intent" else "prefs"
        }")
        L.i(TAG, "凭据MD5: appid=${md5(appid)} appkey=${md5(appkey)} token=${md5(token)}")
        if (token.isBlank()) {
            setStatus("⚠️ 请先在设置中填写 Access Token")
            promptForKey()
            return
        }
        // ★ 彻底重置对话状态（B2：每次拨号=全新对话，界面清空 + 云端本就是新会话天然不记忆）
        //   关键：必须连同 aiTurnList / systemMsgs 一起清。旧实现只清 userBuf 却留着
        //   aiTurnList → 旧 AI 气泡残留、新用户气泡被顶到顶部、旧用户气泡消失（历史 bug）。
        userBuf.setLength(0); aiBuf.setLength(0); userCur = ""
        lastAsrText = ""; lastAsrWasFinal = false; receivedAudioBytes = 0
        aiTurnList.clear(); systemMsgs.clear()
        bargeHeadBuf.setLength(0)
        aiResponding = false; bargeArmed = false; dropAudio = false; bargeSilenceSeen = false
        bargeVad?.flush()
        render()
        setStatus("初始化引擎…")
        Thread {
            try {
                com.bytedance.speech.speechengine.SpeechEngineGenerator
                    .PrepareEnvironment(applicationContext, application)
                val e = com.bytedance.speech.speechengine.SpeechEngineGenerator.getInstance()
                e.createEngine()
                dialogEngine = e
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING, SpeechEngineDefines.DIALOG_ENGINE)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_UID_STRING, "mmd-tv-001")
                // 旧版鉴权三件套（勿与新版 api_key 混用，混用会 initEngine=-1）
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING, appid)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_KEY_STRING, appkey)
                // ★ Token 必须带固定前缀 "Bearer;"（官方文档要求）；用户填的是原始值，此处补齐
                val tokenBearer = if (token.startsWith("Bearer;")) token else "Bearer;$token"
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING, tokenBearer)
                L.i(TAG, "token 加前缀后长度=${tokenBearer.length}（原=${token.length}）")
                // 协议：0=默认 1=SEED 2=SEED_DUPLEX（官方提示词仅双工协议支持）
                val protoType = intent?.getIntExtra("protocol", 0) ?: 0
                e.setOptionInt(SpeechEngineDefines.PARAMS_KEY_PROTOCOL_TYPE_INT, protoType)
                // 鉴权：new(api_key+appkey，官方双工同款) | old(appid/appkey/token)
                val authMode = intent?.getStringExtra("authMode") ?: "new"
                // 凭据来源：Intent（调试）> 设置页保存值（正常使用，唯一持久来源）
                val apiKeyVal = intent?.getStringExtra("apiKey")
                    ?.takeIf { it.isNotBlank() }
                    ?: prefs.getString("volc_api_key", "").orEmpty()
                if (authMode == "new") {
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_API_KEY_STRING, apiKeyVal)
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_KEY_STRING, appkey)
                    L.i(TAG, "新版鉴权: apiKey长度=${apiKeyVal.length} appkey长度=${appkey.length}")
                } else {
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING, appid)
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_KEY_STRING, appkey)
                    val tb = if (token.startsWith("Bearer;")) token else "Bearer;$token"
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING, tb)
                    L.i(TAG, "旧版鉴权: appid=$appid token长度=${tb.length}")
                }
                val resourceId = intent?.getStringExtra("resourceId") ?: "volc.speech.dialog"
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_RESOURCE_ID_STRING, resourceId)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_ADDRESS_STRING, "wss://openspeech.bytedance.com")
                val dialogUri = intent?.getStringExtra("uri") ?: "/api/v3/realtime/dialogue"
                // ★ 开启播放器音频回调：SDK 会把【播放音频】通过 3018 送进来。
                //   注意本协议不下发 3019/3020 事件，我们只用 3018 的【字节数】
                //   推算播放终点（绝不构造 String / 打日志，否则会拖死应用）。
                e.setOptionBoolean(
                    SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_PLAYER_AUDIO_CALLBACK_BOOL, true
                )
                // ★ SDK 自身日志（C1）：随统一开关；默认只留 ERROR，开启时 TRACE + 落盘
                e.setOptionString(
                    SpeechEngineDefines.PARAMS_KEY_LOG_LEVEL_STRING,
                    if (L.enabled) SpeechEngineDefines.LOG_LEVEL_TRACE
                    else SpeechEngineDefines.LOG_LEVEL_ERROR
                )
                if (L.enabled) {
                    val dbgDir = (getExternalFilesDir(null) ?: filesDir).absolutePath
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_DEBUG_PATH_STRING, dbgDir)
                }
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_URI_STRING, dialogUri)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_RECORDER_TYPE_STRING, SpeechEngineDefines.RECORDER_TYPE_RECORDER)
                L.i(TAG, "配置: protocol=$protoType auth=$authMode rid=$resourceId uri=$dialogUri")
                // ★ 新版鉴权必须有 API Key，否则上行链路不生效（表现为"说话没反应"）
                if (authMode == "new" && apiKeyVal.isBlank()) {
                    L.e(TAG, "缺少 API Key！新版鉴权（双工协议）必需，请到设置页填写")
                    main.post {
                        setStatus("❌ 缺少 API Key —— 请点右上角 ⚙ 填写「API Key（双工）」后保存")
                        appendSystem("⚠️ 缺少 API Key：双工协议的新版鉴权必需。请打开 ⚙ 设置，在「API Key（双工）」一行填入你的 API Key，点保存。")
                        updateCallUi()
                    }
                    return@Thread
                }
                if (authMode == "new") L.i(TAG, "API Key 已配置（长度 ${apiKeyVal.length}）")
                // 内置 AEC（模型从 assets 释放）
                val dir = applicationContext.getExternalFilesDir(null) ?: filesDir
                val model = java.io.File(dir, "aec.model")
                if (!model.exists()) {
                    try {
                        assets.open("testdata/aec/aec.model").use { i ->
                            model.outputStream().use { o -> i.copyTo(o) }
                        }
                    } catch (t: Throwable) { L.w(TAG, "释放 AEC 模型失败: ${t.message}") }
                }
                e.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ENABLE_AEC_BOOL, true)
                if (model.exists()) {
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_AEC_MODEL_PATH_STRING, model.absolutePath)
                }
                e.setContext(applicationContext)
                e.setListener(this)
                val ret = e.initEngine()
                L.i(TAG, "SDK initEngine = $ret")
                if (ret != 0) {
                    main.post { setStatus("❌ 引擎初始化失败 ret=$ret") }
                    return@Thread
                }
                // ★ v4.8：还原 SYNC_STOP_ENGINE，但必须传 session.close 的 JSON（传空串会导致 -700）
                //          官方 demo: sendDirective(SYNC_STOP_ENGINE, buildSessionClose())
                e.sendDirective(
                    SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE,
                    """{"type":"session.close","event_id":"event_close"}"""
                )
                // ★ 参考 temp-speech-digital：默认协议 dialog 载荷 + system_role（内置提示词）
                val modelVer2 = intent?.getStringExtra("model") ?: "1.2.1.1"
                val startJson = org.json.JSONObject()
                    .put("dialog", org.json.JSONObject()
                        .put("extra", org.json.JSONObject()
                            .put("input_mod", "keep_alive")
                            .put("model", modelVer2))
                        .put("bot_name", "豆包")
                        .put("system_role", activePrompt))
                    .toString()
                L.i(TAG, "START_ENGINE payload(dialog+system_role) model=$modelVer2 instrLen=${activePrompt.length}")
                val r2 = e.sendDirective(SpeechEngineDefines.DIRECTIVE_START_ENGINE, startJson)
                L.i(TAG, "SDK START_ENGINE = $r2")
                dialogStarted = r2 == 0
                // ★ 注意：callActive 不在此处置位！START_ENGINE 只是"指令被接受"，
                //   服务端会话尚未建立（EVT[3003] 通常晚 0.4~0.8s）。
                //   过早置位会导致按钮提前变红且上行音频被丢。改到 onSessionCreated() 里置位。
                main.post {
                    setStatus(if (dialogStarted) "🔄 正在接通…" else "❌ 启动失败 ret=$r2")
                    render()
                }
            } catch (t: Throwable) {
                L.e(TAG, "SDK start failed", t)
                main.post { setStatus("❌ 异常: ${t.message}") }
            }
        }.start()
    }

    /** 官方 SDK 回调 → 复用原有的 UI/字幕/状态更新逻辑 */
    override fun onSpeechMessage(type: Int, data: ByteArray?, len: Int) {
        // ★★ 3018 = 播放音频本体（二进制）。必须在构造 String 之前拦截：
        //    1) 每帧几百 KB，String 转换会拖死应用（实测直接导致退出）
        //    2) 二进制转 String 会破坏字节计数，必须用回调给出的真实长度 len
        if (type == SpeechEngineDefines.MESSAGE_TYPE_DIALOG_PLAYER_AUDIO) {
            if (aiAudioFirstAt == 0L) aiAudioFirstAt = System.currentTimeMillis()
            aiAudioBytesTotal += len.toLong()
            playerAudioFrames++
            playerAudioBytes += len.toLong()
            return
        }
        val raw = data?.let { String(it, 0, minOf(len, it.size)) } ?: ""
        when (type) {
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_SESSION_STARTED -> {
                L.i(TAG, "会话建立: $raw")
                val sid = runCatching {
                    org.json.JSONObject(raw).optString("dialog_id")
                }.getOrNull()
                onSessionCreated(sid)
            }
            // ★ 3029 = 下行事件（双工协议专用）：session.created / session.updated 都在这里
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_DOWNLINK_EVENT -> {
                L.i(TAG, "EVT[3029] 下行事件: $raw")
                try {
                    val o = org.json.JSONObject(raw)
                    when (o.optString("type")) {
                        "session.created" -> {
                            val sid = o.optJSONObject("session")?.optString("id")
                            L.i(TAG, "★ 双工会话建立 session.id=$sid")
                            onSessionCreated(sid)
                        }
                        "session.updated" -> L.i(TAG, "★ session.update 已确认（提示词已生效）")
                        "session.closed" -> L.i(TAG, "会话已关闭")
                        else -> {}
                    }
                } catch (t: Throwable) { L.w(TAG, "解析 3029 失败: ${t.message}") }
            }
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_ASR_RESPONSE -> {
                controller?.onSpeechSignal()   // ★ 3013 增量即讲话迹象
                runCatching {
                    val o = org.json.JSONObject(raw)
                    val r0 = o.optJSONArray("results")?.optJSONObject(0)
                    val txt = r0?.optString("text").orEmpty()
                    val extra = o.optJSONObject("extra")
                    val isInterim = r0?.optBoolean("is_interim", true) ?: true
                    val endpoint = extra?.optBoolean("endpoint", false) ?: false
                    // ★ E2：只有「非中间结果 且 服务端判停」才算整句 completed
                    val isFinal = !isInterim && endpoint
                    if (txt.isNotEmpty()) {
                        val score = extra?.optDouble("interrupt_score", 0.0) ?: 0.0
                        L.i(TAG, "ASR: $txt (isInterim=$isInterim endpoint=$endpoint final=$isFinal " +
                                "score=$score aiSpeaking=$aiResponding)")
                        lastAsrText = txt
                        lastAsrWasFinal = isFinal
                        onUserText(txt, isFinal)
                    }
                }
            }
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_ASR_ENDED -> {
                // ★ 3014 只代表「用户这一轮 ASR 结束」，与 AI 音频无关！
                //   绝不能调用 onAudioDone()（那会把 aiResponding 误清为 false，
                //   导致 AI 长回复期间被判"AI未播报"而提前挂断）
                controller?.onSpeechSignal()
                aiRespondingNote("3014 ASR_ENDED")
                // 兜底：若 endpoint 未置位导致整句漏判，ASR 结束时补一次 final
                if (lastAsrText.isNotEmpty() && !lastAsrWasFinal) {
                    L.i(TAG, "ASR ended → 兜底 flush final: $lastAsrText")
                    onUserText(lastAsrText, true)
                }
                lastAsrText = ""
                lastAsrWasFinal = false
            }
            // ★ 3008 AI 开始说 → 讲话迹象 + 重置兜底计时
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_SENTENCE_START -> {
                controller?.onSpeechSignal()
                controller?.onActivityStart()
                onAudioStarted()
            }
            // ★ 3010 AI 播报音频 → 讲话迹象（播报期间持续刷新，闲置计时自然不会走）
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_RESPONSE -> {
                controller?.onSpeechSignal()
            }
            // ★ 3011 TTS_ENDED = 「服务端产出结束」→ 清 aiResponding（这个语义是对的）。
            //   注意：此时播放器可能还在播（长回复数据先一次传完），
            //   这部分由 aiPlaybackOngoing()（字节推算）继续兜住 isAiSpeaking()。
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_ENDED -> {
                aiResponding = false
                controller?.onSpeechSignal()
                // ★ 用【服务端声明的音频时长】算播放终点，而非数 3018 字节。
                //   实测：数据是"快于实时"灌下来的（10.2s 传完 52.8s 音频），
                //   且 3018 字节数严重少算（约 1/5），两者都会导致终点偏早 → AI 被掐断。
                //   播放始终是实时速率，故：终点 = 播放起点 + 声明时长 + 余量。
                val anchor = if (aiAudioFirstAt > 0) aiAudioFirstAt else aiTurnStartedAt
                val durMs = aiAudioDurationMs
                aiPlaybackEndsAt =
                    if (durMs > 0 && anchor > 0) anchor + durMs + AI_PLAY_MARGIN_MS else 0L
                L.i(
                    TAG,
                    "EVT[3011] 服务端产出结束 → aiResponding=false；服务端声明时长=${durMs}ms " +
                            "播放起点偏移=${System.currentTimeMillis() - anchor}ms → " +
                            "播放终点=+${(aiPlaybackEndsAt - System.currentTimeMillis()).coerceAtLeast(0)}ms"
                )
            }
            // ★ 3009 TTS_SENTENCE_END：服务端声明该句音频时长（秒）→ 换算 ms。
            //   这是【权威】的播放时长来源；实测按 3018 数字节会严重少算（约 1/5）。
            SpeechEngineDefines.MESSAGE_TYPE_EVENT_TTS_SENTENCE_END -> {
                runCatching {
                    val sd = org.json.JSONObject(raw).optJSONObject("sentence_duration")
                    val endSec = sd?.optDouble("sentence_end_time", 0.0) ?: 0.0
                    if (endSec > 0) {
                        val ms = (endSec * 1000).toLong()
                        if (ms > aiAudioDurationMs) aiAudioDurationMs = ms
                        L.i(TAG, "EVT[3009] 服务端声明音频时长=${ms}ms（累计=${aiAudioDurationMs}ms）")
                    }
                }
            }
            // ★ 3019 播放器真正开始播放
            SpeechEngineDefines.MESSAGE_TYPE_PLAYER_START_PLAY_AUDIO -> {
                aiResponding = true
                controller?.onSpeechSignal()
                L.i(TAG, "EVT[3019] 播放器开始播放 → aiResponding=true")
            }
            // ★★ 3020 播放器真正播放完成 ← 这才是「AI 播报结束」的权威信号
            SpeechEngineDefines.MESSAGE_TYPE_PLAYER_FINISH_PLAY_AUDIO -> {
                aiResponding = false
                controller?.onSpeechSignal()
                L.i(
                    TAG,
                    "EVT[3020] 播放器播放完成 → aiResponding=false（闲置计时从此刻起算）" +
                            " 本轮播放音频帧=${playerAudioFrames} 字节=${playerAudioBytes}"
                )
                playerAudioFrames = 0L
                playerAudioBytes = 0L
            }
            // ★ 3012 用户开口（服务端为这一轮开启 ASR 任务）→ 讲话迹象 + 重置兜底计时
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_ASR_INFO -> {
                L.i(TAG, "EVT[3012] 用户开口: $raw")
                controller?.onSpeechSignal()
                controller?.onActivityStart()
            }
            // ★ 3015 AI 文本回复：模型生成期间也算"有人在讲话"，
            //   否则长回复在 TTS 开始前的生成空窗会被判静默而挂断
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_CHAT_RESPONSE -> {
                controller?.onSpeechSignal()
                runCatching {
                    val c = org.json.JSONObject(raw).optString("content")
                    if (c.isNotEmpty()) onAssistantText(c)
                }
            }
            // ★ 3016 AI 整轮回复结束（文本侧）→ 讲话迹象
            3016 -> controller?.onSpeechSignal()
            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_ERROR -> onError(raw)
            1001 -> main.post { setStatus("✅ 引擎已启动") }
            1002 -> L.i(TAG, "引擎停止")
            // ★ 3018 = MESSAGE_TYPE_DIALOG_PLAYER_AUDIO，是【二进制播放音频】。
            //   绝不能整段转字符串打日志：每 20ms 一帧、每帧几百 KB，
            //   会把 logcat 瞬间撑爆并拖死应用（实测直接导致应用退出）。
            // 3018 已在 onSpeechMessage 开头拦截处理（不构造 String）
            // ★ 兜底：任何未知事件都截断，杜绝同类事故
            else -> {
                val brief = if (raw.length > 200) raw.take(200) + "...(len=${raw.length})" else raw
                L.d(TAG, "EVT[$type] $brief")
            }
        }
    }

    override fun onSpeechLogid(logid: String?) { L.d(TAG, "logid: $logid") }

    /** 低频讲话迹象事件的诊断日志（音频帧不打印，避免刷屏） */
    private fun aiRespondingNote(src: String) {
        L.i(TAG, "信号[$src] aiResponding=$aiResponding callActive=$callActive")
    }

    private fun stopOfficialDialog() {
        try {
            dialogEngine?.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "")
            dialogEngine?.sendDirective(SpeechEngineDefines.DIRECTIVE_STOP_ENGINE, "")
        } catch (_: Throwable) {
        }
        dialogEngine = null
        dialogStarted = false
        callActive = false
    }

    private fun startCallLegacy() {
        val key = apiKey()
        if (key.isEmpty()) {
            toast("请先在设置中填写 API Key")
            promptForKey()
            return
        }
        if (callActive) return
        userBuf.setLength(0); aiBuf.setLength(0); userCur = ""; lastAsrText = ""; lastAsrWasFinal = false; receivedAudioBytes = 0
        aiTurnList.clear(); systemMsgs.clear()
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
            L.i(TAG, "AudioPlayer started=$ok")
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
            if (!dumpT0Mic) { dumpT0Mic = true; L.i(TAG, "DUMP-T0 mic=${System.currentTimeMillis()}") }
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
        L.i(TAG, "DUMP-ANCHOR frames=$dumpFrames ts=${System.currentTimeMillis()}")
        L.i(TAG, "AudioCapture started=$micOk")
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
            if (!dumpT0Fe) { dumpT0Fe = true; L.i(TAG, "DUMP-T0 far_end=${System.currentTimeMillis()}") }
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
        bargeRmsThreshold = (intent?.getIntExtra("bargeRms", 250) ?: 250).toDouble()
        bargeHoldMs = (intent?.getIntExtra("bargeHold", 200) ?: 200).toLong()
        bargeProbThr = ((intent?.getIntExtra("bargeProbPct", 40) ?: 40) / 100f)
        L.i(TAG, "software AEC3 = ${aec != null} (hwAecActive=$hwAec, delayMs=$delayMs, playerBufferMs=${player?.bufferMs}, localBarge=$localBargeEnabled, bargeProb=${bargeProbThr}, bargeHold=${bargeHoldMs}ms, tenVad=${TenVadNative.isAvailable})")
        if (aec != null) appendSystem("🎧 软件 AEC3 已启用（硬件回声消除未生效）")

        callActive = true
        main.post { updateCallUi() }
    }

    private fun hangup() {
        stopOfficialDialog()        // ★ v3.0：停止官方 SDK 会话
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
     * [v2.8.2] 本地双讲检出 —— TEN VAD 概率 + 能量门「双信号 AND」，**滑窗占比**判定。
     *
     * 演进：
     *  - v2.6 能量门+VAD+严格连续 → 噪音下误触发
     *  - v2.8 仅 TEN VAD 概率 → AEC3 输出的全零帧是分布外输入，prob 虚高(0.5~0.6) → 误触发
     *  - v2.8.1 加回能量门(AND) → 误报消除，但「严格连续 500ms」太苛刻：
     *           实测真人插话帧为 rms 272~1570 / prob 0.71~0.83，中间任何一帧掉档
     *           就把连续计数清零 → 永远攒不满 → 不触发
     *  - v2.8.2 改为滑窗占比：最近 bargeHoldMs 窗口内，满足(prob & rms)的帧占比 ≥60% 即触发
     *
     * 依据（真实录音实测）：
     *   WebRTC VAD：低噪 67% / 中噪 96% 判为"人声" → 噪音必然误触发
     *   TEN  VAD：低噪 5% / 中噪 17% / 真人语音 87~99%（概率 0.71~0.93）
     *   能量门：回声 2~27 / 静音 0~62 / 真人插话 272~1570
     */
    @Volatile private var bargeProbThr = 0.40f
    /** 滑窗（帧）与判定占比：容忍说话中的短暂掉档 */
    private val bargeWin = ArrayDeque<Boolean>()
    private val BARGE_WIN_RATIO = 0.60f

    private fun onBargeVad(prob: Float) {
        if (!callActive) return
        // [v2.9] 无论本地打断是否开启，都刷新 VAD 闸门状态（供服务端事件做防噪确认）
        refreshVadGate(prob)
        // 诊断日志：AI 播报期间定期记录概率与能量（用于离线核算误报/检出率）
        if (aiResponding) {
            bargeDbg++
            if (bargeDbg % 60L == 0L) L.i(TAG, "BARGE-PROBE prob=%.2f rms=%d run=%dms".format(prob, lastFrameRms.toInt(), bargeRunMs))
        }
        if (!localBargeEnabled) return
        if (!aiResponding || bargeArmed) { bargeWin.clear(); bargeRunMs = 0; return }
        val voiced = prob > bargeProbThr && lastFrameRms > bargeRmsThreshold
        // 滑窗（窗口长度 = bargeHoldMs，帧长 16ms）
        val winFrames = (bargeHoldMs / 16L).toInt().coerceAtLeast(1)
        bargeWin.addLast(voiced)
        while (bargeWin.size > winFrames) bargeWin.removeFirst()
        val hitRatio = bargeWin.count { it }.toFloat() / bargeWin.size
        bargeRunMs = (hitRatio * winFrames * 16).toLong()
        if (bargeWin.size < winFrames || hitRatio < BARGE_WIN_RATIO) return
        // 窗口内足够比例满足 → 判定用户插话，立即打断
        bargeArmed = true
        dropAudio = true
        aiResponding = false
        player?.interrupt()
        aec?.clearRender()
        client?.sendCancel()
        val pct = (hitRatio * 100).toInt()
        main.post { appendSystem("⏹ 本地双讲检出（TEN VAD %.2f ≥ %.2f，能量 %d ≥ %d，${winFrames * 16}ms 内占比 %d%% ≥ 60%%）→ 打断 AI 播报".format(prob, bargeProbThr, lastFrameRms.toInt(), bargeRmsThreshold.toInt(), pct)) }
        L.i(TAG, "BARGE-HIT prob=%.2f rms=%d ratio=%d%% win=%dms".format(prob, lastFrameRms.toInt(), pct, winFrames * 16))
    }

    /** 服务端已检测到用户开口：立即停止播报并取消当前回复。 */
    private fun onServerBarge() {
        if (!callActive || !aiResponding) return
        // [v2.9] 防噪闸门：本地 TEN VAD 未确认人声时（噪音/幻听），不打断
        if (!recentVadVoiced) {
            L.i(TAG, "SERVER-BARGE 被本地 VAD 闸门拦截（服务端报开口但本地判非人声）")
            return
        }
        bargeArmed = true
        dropAudio = true
        aiResponding = false
        player?.interrupt()
        aec?.clearRender()
        client?.sendCancel()   // [v2.8.4] 之前漏了这句：只停本地播放，服务端仍在生成 → AI 会把整轮说完
        main.post { appendSystem("⏹ 服务端检出用户开口 + 本地VAD确认 → 停止播报，进入下一轮") }
        L.i(TAG, "SERVER-BARGE 服务端检出用户开口 → 停止播报")
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
        L.w(TAG, "loadAsset($name) failed: ${e.message}"); null
    }

    // ---------- SeeduplexClient.Listener ----------

    override fun onStatus(msg: String) { main.post { setStatus(msg) } }

    override fun onSessionCreated(sessionId: String?) {
        // ★ 会话真正建立 → 此刻才置 callActive 并刷新按钮（红）
        callActive = true
        controller?.onCallConnected()
        // ★ 下发系统提示词：官方 demo 用 session.update 事件（EVT[3003] 后发）
        try {
            val ev = org.json.JSONObject()
            ev.put("type", "session.update")
            ev.put("event_id", "event_" + java.util.UUID.randomUUID().toString())
            val session = org.json.JSONObject()
            session.put("id", sessionId ?: "")
            // ★ 官方 demo 的 session.update 里带 model 与 output_modalities，缺了可能导致配置不生效
            session.put("model", "1.2.1.1")
            session.put("instructions", activePrompt)
            session.put("output_modalities", org.json.JSONArray().put("text").put("audio"))
            ev.put("session", session)
            val r = dialogEngine?.sendDirective(
                SpeechEngineDefines.DIRECTIVE_SEND_UPLINK_EVENT, ev.toString()
            )
            L.i(TAG, "session.update(instructions) ret=$r len=${activePrompt.length}")
        } catch (t: Throwable) {
            L.e(TAG, "session.update failed", t)
        }
        // ★ 开场白：会话建立后主动让模型说话（参考 temp-speech-digital 的 sayHello）
        try {
            val helloJson = org.json.JSONObject().put("content", HELLO_TEXT).toString()
            val hr = dialogEngine?.sendDirective(
                SpeechEngineDefines.DIRECTIVE_EVENT_SAY_HELLO, helloJson
            )
            L.i(TAG, "开场白 SAY_HELLO ret=$hr content=「$HELLO_TEXT」")
        } catch (t: Throwable) {
            L.w(TAG, "开场白下发失败: ${t.message}")
        }
        main.post {
            setStatus("🎙️ 对话中，直接说话即可")
            updateCallUi()
            // 不再把 session id 显示成气泡
        }
    }

    /**
     * [v2.9] 双讲判据：aiResponding 期间服务端产生转写 = 用户在说话 → 直接打断。
     *
     * 依据：上行已反复验证为"干净"（AI 回声不会进入转写，只有用户的话），
     * 故 aiResponding 期间任何转写内容都来自真人 → 即双讲，无需判关键词。
     * 防噪闸门：本地 TEN VAD 概率（实测噪音下仅 5~17% 判语音，真人 87~99%），
     * 避免服务端 ASR 对噪音"幻听"出文字时误触发。
     */
    override fun onUserText(delta: String, final: Boolean) {
        controller?.onSpeechSignal()
        if (delta.isNotEmpty()) {
            bargeHeadBuf.append(delta)
            // 双讲判定：AI 正在回复 + 服务端产出转写 + 本地 VAD 确认是人声
            if (aiResponding && !bargeArmed && recentVadVoiced) {
                main.post { appendSystem("⏹ 双讲检出（AI 播报中收到上行转写「${delta.take(12)}」+ 本地VAD确认）→ 打断") }
                L.i(TAG, "DOUBLETALK-BARGE delta=「${delta.take(12)}」")
                onKeywordBarge()
            }
        }
        if (final) bargeHeadBuf.setLength(0)
        // 退出指令：整句、前 7 字内含"退出/关闭"
        if (final && isExitCommand(delta)) {
            main.post { exitByVoice() }
            return
        }
        main.post {
            // ★ 用户文本累积：整句完成后才追加到 userBuf（避免中间结果重复）
            //   中间结果暂存 userCur，render 时作为"当前句"展示
            if (final) {
                if (delta.isNotEmpty()) userBuf.append(delta)
                userBuf.append("\n")
                userCur = ""
            } else {
                userCur = delta
            }
            render()
        }
    }

    /** 本地 TEN VAD 近期是否确认"有人在说话"（用于给服务端事件做防噪闸门） */
    @Volatile private var recentVadVoiced = false
    @Volatile private var lastVadVoicedAt = 0L

    /** 刷新本地 VAD 闸门状态（在 onBargeVad 中调用） */
    private fun refreshVadGate(prob: Float) {
        if (prob > 0.40f) {
            recentVadVoiced = true
            lastVadVoicedAt = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - lastVadVoicedAt > 1500L) {
            // 1.5 秒内没有再次确认人声 → 闸门关闭（防噪）
            recentVadVoiced = false
        }
    }

    /** 本轮累积的上行转写文本（用于关键词判定，避免 delta 分块漏检） */
    private val bargeHeadBuf = StringBuilder()

    /**
     * 关键词打断判定（本轮累积转写文本，仅看前 4 个字）：
     * 命中「停」或「不对」或「等等」或「别说」即视为用户想插话——
     * 「停」已覆盖 停止/暂停/停下/停一下（这些词的前 3 字里都含"停"）。
     * 用增量(delta)累积即可判定，无需等整句 completed，保证打断及时。
     */
    private fun isBargeCommand(text: String): Boolean {
        val head = text.trim().take(4)
        val hit = head.contains("停") || head.contains("不对") ||
                  head.contains("等等") || head.contains("别说")
        if (hit) L.i(TAG, "关键词命中: head=「$head」 aiResponding=$aiResponding callActive=$callActive")
        return hit
    }

    /**
     * 关键词打断：立即停播 + 取消当前回复，进入下一轮。
     * [v2.8.4] 修复：不再要求 aiResponding=true。
     *   原因：ASR 流式转写比用户实际说话晚 ~1s，届时 AI 那轮回复可能已播完
     *   （aiResponding 已被 onAudioDone 置 false）→ 关键词被自己的门槛挡掉。
     *   关键词代表用户明确意图，只要通话中命中即执行打断（bargeArmed 保证幂等）。
     */
    private fun onKeywordBarge() {
        if (!callActive || bargeArmed) return
        val wasResponding = aiResponding
        bargeArmed = true
        dropAudio = true
        aiResponding = false
        player?.interrupt()
        aec?.clearRender()
        client?.sendCancel()   // 同时取消服务端侧回复，避免"停了又继续"
        main.post { appendSystem("⏹ 关键词打断（停/不对）→ 停止播报，进入下一轮（当时AI${if (wasResponding) "正在" else "已结束"}播报）") }
        L.i(TAG, "KEYWORD-BARGE 关键词打断 wasResponding=$wasResponding")
    }

    /** 语音退出判定：整句文本「前 7 个字」内含有"退出"或"关闭"。 */
    private fun isExitCommand(text: String): Boolean {
        val head = text.trim().take(7)
        // 退出关键词：退出 / 关闭 / 关机 / 小艺（说“小艺小艺”亦命中，便于与 TV 原生助手共存时退出本应用）
        return head.contains("退出") || head.contains("关闭") ||
                head.contains("关机") || head.contains("小艺")
    }

    /** 执行语音退出：先挂断会话，再结束任务并结束进程（C1 / D1）。 */
    /** 关闭按钮：与语音退出同链路（挂断 → finishAndRemoveTask → killProcess） */
    private fun exitApp() {
        L.i(TAG, "关闭按钮 → 退出应用")
        try { appendSystem("⏹ 正在关闭…") } catch (_: Exception) {}
        controller?.release()
        try { if (callActive) hangup() } catch (_: Exception) {}
        stopMonitoring()
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun exitByVoice() {
        L.i(TAG, "语音退出指令命中 → 退出应用")
        try { appendSystem("⏹ 收到退出指令，正在退出…") } catch (_: Exception) {}
        controller?.release()                      // 停自动模式，避免挂断后进入监听/重拨
        try { if (callActive) hangup() } catch (_: Exception) {}
        stopMonitoring()
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onAssistantText(delta: String) {
        main.post {
            aiBuf.append(delta)     // 保留原始累积（[系统] 消息等仍用它）
            feedAiText(delta)       // ★ 三段式拆分（流式时序不变）
            render()
        }
    }

    override fun onAudioStarted() {
        // ★ 新一轮 AI 播报开始 → 复位音频字节统计
        if (!aiResponding) {
            aiAudioBytesTotal = 0L
            aiAudioFirstAt = 0L
            aiPlaybackEndsAt = 0L
            aiAudioDurationMs = 0L
            aiTurnStartedAt = System.currentTimeMillis()
            playbackEndLogged = false
        }
        aiResponding = true
        bargeArmed = false
        dropAudio = false
        bargeSilenceSeen = false
    }

    override fun onAudioDelta(pcm: ByteArray) {
        controller?.onSpeechSignal()
        // ★ 累加播放音频字节（用于推算播放终点）
        if (aiAudioFirstAt == 0L) aiAudioFirstAt = System.currentTimeMillis()
        aiAudioBytesTotal += pcm.size
        if (dropAudio) return
        player?.write(pcm)
        receivedAudioBytes += pcm.size
    }

    override fun onAudioDone() {
        // ★ 不再在此清 aiResponding！播放结束由 3020(PLAYER_FINISH_PLAY_AUDIO) 负责，
        //   否则长回复在"数据传完但仍在播放"期间会被误判为 AI 已停 → 提前挂断。
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
        if (!::bubbleBox.isInitialized) return
        bubbleBox.removeAllViews()
        // 用户：已完成句 + 当前中间结果句（原话）
        val userTurns = (userBuf.toString().split("\n") + userCur)
            .filter { it.isNotBlank() }
        // ★ 顺序与配对改为「显式关联」：
        //   1) 先渲染无前置用户发言的 AI 轮次（开场白等）
        //   2) 再按用户发言顺序：用户气泡（含其翻译）→ 紧随其关联的 AI 回复
        aiTurnList.filter { it.userIdx < 0 }.forEach { at ->
            val en0 = at.bracket.toString().trim()
            val zh0 = at.corner.toString().trim()
            if (en0.isNotEmpty() || zh0.isNotEmpty()) addBubbleAi(en0, zh0)
        }
        userTurns.forEachIndexed { i, u ->
            val linked = aiTurnList.filter { it.userIdx == i }
            val trans = linked.map { it.brace.toString().trim() }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            addBubbleUser(u, trans)
            linked.forEach { at ->
                val en = at.bracket.toString().trim()
                val zh = at.corner.toString().trim()
                if (en.isNotEmpty() || zh.isNotEmpty()) addBubbleAi(en, zh)
            }
        }
        // 兜底：关联序号越界的 AI 轮次（如用户发言尚未落盘）仍要显示
        aiTurnList.filter { it.userIdx >= userTurns.size }.forEach { at ->
            val en2 = at.bracket.toString().trim()
            val zh2 = at.corner.toString().trim()
            if (en2.isNotEmpty() || zh2.isNotEmpty()) addBubbleAi(en2, zh2)
        }
        // 系统提示（灰字，居中偏左）
        systemMsgs.forEach { msg ->
            val sb = android.text.SpannableString(msg)
            sb.setSpan(
                android.text.style.ForegroundColorSpan(getColor(R.color.text_secondary)),
                0, msg.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            addBubbleSpanned(sb, false)
        }
        // ★ 自动滚动到最新内容
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * 追加一个对话气泡（微信风格）：
     * 用户 = 右对齐 / 透明背景 / 白色字体
     * AI   = 左对齐 / 翠绿色背景 / 黑色字体
     * 均限最大宽度（78%），超长自动换行
     */
    private fun addBubbleSpanned(text: CharSequence, isUser: Boolean) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setLineSpacing(4f, 1.15f)
        // ★ 超长自动换行：不限制单行 + 按词/字符断行
        tv.isSingleLine = false
        tv.maxLines = Int.MAX_VALUE
        tv.breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        tv.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        val pad = (12 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (4 * resources.displayMetrics.density).toInt()
        lp.setMargins(margin, margin, margin, margin)
        lp.gravity = if (isUser) Gravity.END else Gravity.START
        // ★ 最大宽度限制（微信风格）：用 maxWidth 而非固定 width，让宽度自适应、长文本换行不溢出
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
        if (isUser) {
            // ★ 颜色由 Spannable 逐段控制（原话灰字 / 翻译白字）
            tv.setBackgroundResource(R.drawable.bg_bubble_user)  // 透明背景
            tv.textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_END
        } else {
            // ★ 颜色由 Spannable 逐段控制（英文黑字 / 中文灰字）
            tv.setBackgroundResource(R.drawable.bg_bubble_ai)    // 翠绿色背景
            tv.textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
        }
        tv.layoutParams = lp
        bubbleBox.addView(tv)
    }

    /** 用户气泡：第一行=原话（灰字），第二行=花括号翻译（白字）；翻译为空则只显示原话 */
    private fun addBubbleUser(text: String, trans: String) {
        if (trans.isBlank()) {
            val sb = android.text.SpannableString(text)
            sb.setSpan(
                android.text.style.ForegroundColorSpan(getColor(R.color.text_secondary)),
                0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            addBubbleSpanned(sb, true)
            return
        }
        val body = text + "\n" + trans
        val sb = android.text.SpannableString(body)
        sb.setSpan(
            android.text.style.ForegroundColorSpan(getColor(R.color.text_secondary)),
            0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            android.text.style.ForegroundColorSpan(getColor(R.color.text_primary)),
            text.length + 1, body.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        addBubbleSpanned(sb, true)
    }

    /** AI 气泡：第一行=方括号英文（黑字），第二行=方头括号中文（灰字） */
    private fun addBubbleAi(en: String, zh: String) {
        val black = 0xFF000000.toInt()
        val gray = getColor(R.color.text_secondary)
        val sb: android.text.SpannableString
        when {
            en.isNotBlank() && zh.isNotBlank() -> {
                val body = en + "\n" + zh
                sb = android.text.SpannableString(body)
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(black),
                    0, en.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(gray),
                    en.length + 1, body.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            en.isNotBlank() -> {
                sb = android.text.SpannableString(en)
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(black),
                    0, en.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            else -> {
                sb = android.text.SpannableString(zh)
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(gray),
                    0, zh.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        addBubbleSpanned(sb, false)
    }

    private fun appendSystem(text: String) {
        systemMsgs.add(text)
        render()
    }

    private fun setStatus(s: String) = main.post {
        lastStatus = s
        if (::statusTv.isInitialized) statusTv.text = displayStatus()
    }

    /**
     * ★ 状态区只显示两种文案（A2）：
     *   通话中 / 待唤醒：“豆包豆包”
     * 关键警告（⚠️ / ❌ 开头）原样透出，避免遮挡必要的失败提示。
     */
    private fun displayStatus(): String {
        val s = lastStatus
        if (s.startsWith("⚠️") || s.startsWith("❌")) return s
        val st = controller?.state
        val inCall = callActive ||
                st == AutoCallController.State.DIALING ||
                st == AutoCallController.State.IN_CALL
        return if (inCall) getString(R.string.status_in_call)
        else getString(R.string.status_waiting)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /**
     * ★ 退到后台（onStop）→ 立即释放麦克风。
     *
     * 背景：本应用以 VOICE_COMMUNICATION 源占用麦克风（挂断后还会进入本地监听）。
     * 实测（华为 KUNL-250A 安卓电视）：只要本应用持有该麦克风采集，系统热词引擎
     * （AUDIO_SOURCE_HOTWORD / hwwakeup，负责“小艺小艺”唤醒）的采集线程会被拆掉，
     * 导致 TV 原生语音助手无法响应。故应用不在前台时主动让出麦克风。
     *
     * 动作：停用自动模式（停本地监听 + 停 ticker + 挂断进行中的会话）→ 兜底停监听。
     * 代价：后台不再自动重拨（已与用户确认 B1）。
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return   // 配置变更（横竖屏等）不视为退后台
        isForeground = false
        wentBackground = true
        L.i(TAG, "onStop → 释放麦克风（停监听/挂断），让出给系统语音助手")
        runCatching { controller?.enabled = false }   // 内部：停监听 + 停 ticker + 挂断通话
        runCatching { stopMonitoring() }
        runCatching { if (callActive) hangup() }
        runCatching { bargeVad?.flush() }
    }

    /** ★ 回到前台（onStart）→ 按“静默挂断秒数”配置恢复：>0 自动重拨，=0 仅待机。 */
    override fun onStart() {
        super.onStart()
        if (!wentBackground) return           // 首次启动的 onStart 交由 onCreate 处理，避免重复拨号
        isForeground = true
        L.i(TAG, "onStart → 回到前台，按配置恢复自动模式")
        runCatching {
            val secCfg = prefs.getInt(KEY_SILENCE_SEC, AutoCallController.DEFAULT_IDLE_SILENCE_SEC)
            controller?.idleSilenceMs = if (secCfg > 0) secCfg * 1000L else 0L
            controller?.enabled = secCfg > 0
        }
    }

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
        // v3.0 官方 SDK 凭据（旧版鉴权三件套；仅本机保存，不写入 APK）
        const val KEY_APPID = "volc_app_id"
        const val KEY_APPKEY = "volc_app_key"
        const val KEY_TOKEN = "volc_app_token"
        /** 静默挂断秒数（0 = 关闭自动模式） */
        const val KEY_SILENCE_SEC = "auto_silence_sec"

        /**
         * 内置系统提示词（通过 session.update 事件下发）。
         * 约束：中文/中英混合先译成英文 {英文}；英文语法错误先纠正 {英文}；
         *      再用简短简单英文作答 [English]；最后附中文翻译 【中文】。
         */
        val HELLO_TEXT = "Hi! I'm Doubao."
    val SYSTEM_PROMPT = "你叫豆包，用户的英文名叫 Jerry，不要暴露你是英语练习助手的角色，可以称自己是用户的好朋友以避免太过目的性，你熟悉中文和英语，善于在欢快对话中引导用户学习英语。为了让用户从零逐渐学习英语，请严格遵守如下三段式回复：第一段，用户输入内容时若是中文或中英混合则你先翻译成英文（使用花括号{}括起来），若是语法有问题的英文你先纠正成正确的英文并且不要修改原句意思（使用花括号括起来），其他情况则第一段仅保留花括号{}这两个字符（不要出现其他任何内容）；第二段，然后使用简短并且简单的英文对答回复（使用方括号[]括起来）；第三段，再将自己的回复翻译成中文（使用方头括号【】括起来），以让用户明白意思。比如 用户输入中文“你是谁？”，你先翻译成英文“{Who are you?}”，再英文对答回复“[Haha, I'm Doubao.]”，再将回复翻译成中文“【哈哈，我是豆包。】”。你的性格那叫一个热情似火，比夏天的迈阿密还烫！你超爱夸人！而且你经常管用户叫大佬或Boss！内容必须阳光积极，你严禁涉及任何负面信息；你的字典里常有“Awesome！”、“Let’s go！”、“You rock！”、“That’s cool”、“Yeah man”。最高优先级格式硬约束再次强调：若用户输入本身已是正确英文：第一段必须严格只输出 {} 这两个字符，不得重复或改写用户的话；第二段必须整体用[]包住；第三段必须整体用【】包住。"
    }
}
