package com.mmd.englishdigital

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import com.bytedance.speech.speechengine.SpeechResourceManagerGenerator

/**
 * POC：验证火山引擎官方「端到端实时语音 DIALOG」SDK 能否实现播报中打断。
 *
 * 背景：我们自研链路（自写 WebSocket + WebRTC AEC3 + 自研判据）实测无法实现
 * 播报期打断，根因是回声消不干净 + 服务端在播报期不流式输出用户转写。
 * 官方 SDK 内含 线性AEC3 + 神经网络AEC(DeepVQE) + Kalman + 麦克风阵列选择
 * + ByteNN 优化推理引擎，官方文档称"硬件 AEC 强制开启，不会自打断"。
 *
 * 本 Activity 只做一件事：跑官方 SDK，把服务端事件按时间轴打出来，观察
 * `conversation.item.input_audio_transcription.started`（官方 SDK 的 OnInterrupt
 * 对应事件）是否在 `output_audio` 播报期间到达。若是 → 方案成立。
 *
 * 凭据通过启动参数传入（不内置到 APK）：
 *   adb shell am start -n com.mmd.englishdigital/.PocDialogActivity \
 *     --es appid "xxx" --es appkey "xxx" [--es aecModelPath "/path/x.bin"]
 */
class PocDialogActivity : Activity(), SpeechEngine.SpeechListener {

    companion object {
        private const val TAG = "MMD-POC"
        private const val RESOURCE_ID = "volc.speech.dialog"
        // 官方 iOS SDK 文档示例："wss://openspeech.bytedance.com" + "/api/v3/realtime/dialogue"
        // 注意：SDK 用的 URI 与 WebSocket 直连（/api/v3/duplex/realtime/dialogue）不同！
        private const val DEFAULT_ADDRESS = "wss://openspeech.bytedance.com"
        private const val DEFAULT_URI = "/api/v3/realtime/dialogue"
    }

    private var engine: SpeechEngine? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView

    // 统计：播报期收到「用户首字」事件的次数 = 官方打断机制是否生效的核心指标
    private var aiPlaying = false
    private var playingSince = 0L
    private var interruptDuringPlayback = 0
    private var turns = 0
    private val sb = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appid = intent?.getStringExtra("appid") ?: run {
            Log.e(TAG, "缺少 appid"); finish(); return
        }
        val appkey = intent?.getStringExtra("appkey") ?: run {
            Log.e(TAG, "缺少 appkey"); finish(); return
        }
        // 鉴权模式（可空）：late_bind / pre_bind —— 空则走 SDK 默认（旧版 access_token）
        val authType = intent?.getStringExtra("authType")
        val authSecret = intent?.getStringExtra("authSecret") ?: appkey
        val authCredential = intent?.getStringExtra("authCredential")
        val aecModelPath = intent?.getStringExtra("aecModelPath")
        val addr = intent?.getStringExtra("addr") ?: DEFAULT_ADDRESS
        val uri = intent?.getStringExtra("uri") ?: DEFAULT_URI
        // Token 是与 AppKey 不同的凭据（官方文档【必需配置】）
        val appToken = intent?.getStringExtra("appToken") ?: ""

        // 简易 UI：状态 + 滚动日志
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        val status = TextView(this).apply { textSize = 20f; text = "POC 初始化中…" }
        val btn = Button(this).apply {
            text = "开始会话"
            setOnClickListener { startSession() }
        }
        logView = TextView(this).apply { textSize = 12f }
        root.addView(status); root.addView(btn)
        root.addView(ScrollView(this).apply { addView(logView) })
        setContentView(root)

        Thread {
            try {
                // 1) 环境准备（网络等依赖，生命周期内一次）
                val ok = SpeechEngineGenerator.PrepareEnvironment(applicationContext, application)
                log("PrepareEnvironment = $ok")

                // 2) 创建引擎
                val e = SpeechEngineGenerator.getInstance()
                engine = e
                e.createEngine()

                // 3) 参数：引擎名 + 鉴权 + 资源 + 内置 AEC
                // ★ 必需：Engine Name —— 漏设会导致 SDK 不知初始化哪个引擎，
                //   报出误导性的 -202(ERR_ADDRESS_INVALID)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING, SpeechEngineDefines.DIALOG_ENGINE)
                // ★ 必需：User ID（辅助定位线上问题，可固定字符串）
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_UID_STRING, "mmd-tv-001")
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING, appid)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_KEY_STRING, appkey)
                // ★ 必需：Token（与 AppKey 是两个不同凭据）
                if (!appToken.isNullOrEmpty()) {
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING, appToken)
                }
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_RESOURCE_ID_STRING, RESOURCE_ID)
                // 鉴权模式：late_bind 走"新版鉴权"（用 API Key），空则走旧版 access_token
                if (!authType.isNullOrEmpty()) {
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_AUTHENTICATE_TYPE_STRING, authType)
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_AUTHENTICATE_SECRET_STRING, authSecret)
                    if (!authCredential.isNullOrEmpty()) {
                        e.setOptionString(SpeechEngineDefines.PARAMS_KEY_AUTHENTICATE_CREDENTIAL, authCredential)
                    }
                    log("鉴权模式: type=$authType secret长度=${authSecret.length} credential=${!authCredential.isNullOrEmpty()}")
                }
                // 3.5) 尝试用 SDK 自带的资源管理器下载 AEC 模型（官方设计路径）
                var fetchedModel: String? = aecModelPath
                try {
                    val rm = SpeechResourceManagerGenerator.getInstance()
                    rm.setAppId(appid)
                    rm.setEngineName(SpeechEngineDefines.DIALOG_ENGINE)
                    val ok = rm.initResourceManager(applicationContext, RESOURCE_ID)
                    log("ResourceManager init = $ok")
                    for (name in listOf("aec_model", "aec", "volc.speech.dialog.aec")) {
                        try {
                            val has = rm.checkResourceDownload(name)
                            log("  资源 $name 已下载=$has")
                            if (has) {
                                val p = rm.getResourcePath(name)
                                log("  资源路径 $name = $p")
                                if (!p.isNullOrEmpty() && File(p).exists()) { fetchedModel = p; break }
                            }
                        } catch (t: Throwable) { log("  检查 $name 异常: ${t.message}") }
                    }
                } catch (t: Throwable) {
                    log("资源管理器异常: ${t.javaClass.simpleName}: ${t.message}")
                }

                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_ADDRESS_STRING, addr)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_URI_STRING, uri)
                log("地址: $addr$uri")
                // 内置 AEC（关键）：既要开录音又要开播放时必须开启；
                // 但【开启 AEC 时 aec_model_path 必填】，否则 initEngine 返回 -1
                val aecOn = intent?.getBooleanExtra("aec", false) ?: false
                if (aecOn) {
                    e.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ENABLE_AEC_BOOL, true)
                    if (!fetchedModel.isNullOrEmpty()) {
                        e.setOptionString(SpeechEngineDefines.PARAMS_KEY_AEC_MODEL_PATH_STRING, fetchedModel)
                        log("AEC 模型路径: $fetchedModel")
                    } else {
                        log("⚠️ AEC 已开启但未提供模型路径（官方要求开启时必填）")
                    }
                } else {
                    e.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ENABLE_AEC_BOOL, false)
                    log("AEC 已关闭（-202 阶段验证用）")
                }
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_LOG_LEVEL_STRING, SpeechEngineDefines.LOG_LEVEL_DEBUG)

                e.setContext(applicationContext)
                e.setListener(this)

                // 4) 初始化
                val ret = e.initEngine()
                log("initEngine = $ret (0 为成功)")
                ui.post { status.text = if (ret == 0) "引擎就绪 ✅" else "初始化失败 ret=$ret ❌" }
                // 自动启动会话（POC 免点击：adb 无法可靠点击自绘按钮）
                if (ret == 0 && intent?.getBooleanExtra("autoStart", false) == true) {
                    Thread.sleep(1500)
                    log("自动启动会话…")
                    startSession()
                }
            } catch (t: Throwable) {
                log("异常: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "init failed", t)
            }
        }.start()
    }

    private fun startSession() {
        Thread {
            val e = engine ?: return@Thread
            try {
                e.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "")
                val ret = e.sendDirective(
                    SpeechEngineDefines.DIRECTIVE_START_ENGINE,
                    "{\"dialog\":{\"bot_name\":\"豆包\"}}"
                )
                log("START_ENGINE = $ret")
                turns++
            } catch (t: Throwable) {
                log("启动异常: ${t.message}")
            }
        }.start()
    }

    /** SDK 回调：交付与服务端一致的 JSON 协议事件 */
    override fun onSpeechMessage(type: Int, data: ByteArray?, len: Int) {
        if (data == null || len <= 0) return
        val text = String(data, 0, len, Charsets.UTF_8)
        val now = System.currentTimeMillis()

        // 识别关键事件并做时间轴统计
        when {
            text.contains("output_audio.started") -> {
                aiPlaying = true; playingSince = now
            }
            text.contains("output_audio.done") -> {
                aiPlaying = false
                log("◀ AI 播报结束 (时长 ${now - playingSince}ms)  msgType=$type")
            }
            text.contains("input_audio_transcription.started") -> {
                if (aiPlaying) {
                    interruptDuringPlayback++
                    log("★ 播报期收到「用户首字」! 距播报开始 ${now - playingSince}ms  (第 $interruptDuringPlayback 次)")
                } else {
                    log("· 非播报期收到「用户首字」")
                }
            }
            text.contains("input_audio_transcription.completed") ->
                log("· 用户说话结束")
        }
        Log.i(TAG, "EVT[$type] $text")
    }

    private fun log(s: String) {
        Log.i(TAG, s)
        ui.post {
            sb.insert(0, s + "\n")
            logView.text = sb.toString()
        }
    }

    override fun onDestroy() {
        try { engine?.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "") } catch (_: Throwable) {}
        try { engine?.destroyEngine() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
