package com.mmd.englishdigital

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 正式版：豆包端到端实时语音对话（官方 SpeechEngine SDK）
 *
 * 凭据来自设置页（SharedPreferences，本地明文），APK 内不内置密钥。
 * 官方 SDK 内置多级 AEC（线性 AEC3 + 神经 AEC DeepVQE + Kalman），
 * 解决了自研 WebRTC AEC3 在电视上回声消不干净的问题。
 *
 * 核心能力：AI 播报期间用户说话可被实时识别（EVT[3013] 带 interrupt_score），
 * 从而支持"播报中打断"。
 */
class DialogCallActivity : Activity(), SpeechEngine.SpeechListener {

    companion object {
        private const val TAG = "MMD-Dialog"
        private const val RESOURCE_ID = "volc.speech.dialog"
        private const val ADDRESS = "wss://openspeech.bytedance.com"
        private const val URI = "/api/v3/realtime/dialogue"
        private const val AEC_MODEL = "aec.model"
        // 对话服务需要的固定 AppID（非密钥）
        const val DEFAULT_APPID = "2446422829"
        // 旧版鉴权用的 App Key（设置页可覆盖）
        const val DEFAULT_APPKEY = ""
        private const val PREF = "mmd_prefs"
        private const val KEY_APPID = "volc_app_id"
        private const val KEY_APPKEY = "volc_app_key"
        private const val KEY_TOKEN = "volc_app_token"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREF, MODE_PRIVATE) }

    private var engine: SpeechEngine? = null
    private var started = false
    private var aiSpeaking = false

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 统一日志开关（与其他入口保持一致）
        L.init(if (intent?.hasExtra("log") == true) intent.getBooleanExtra("log", false) else null)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == 1 && (r.isEmpty() || r[0] != PackageManager.PERMISSION_GRANTED)) {
            status.text = "❌ 缺少录音权限"
        }
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "MMD English · 实时语音对话"
            textSize = 22f
        }
        status = TextView(this).apply {
            text = "准备中…"
            textSize = 16f
            setPadding(0, 16, 0, 16)
        }
        transcript = TextView(this).apply {
            textSize = 15f
            setPadding(8, 8, 8, 8)
        }
        scroll = ScrollView(this).apply {
            addView(transcript)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val btn = Button(this).apply {
            text = "开始对话"
            setOnClickListener { if (started) stopSession() else startSession() }
        }
        val btnCfg = Button(this).apply {
            text = "⚙ 凭据设置"
            setOnClickListener { promptCreds() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(scroll)
        root.addView(btn)
        root.addView(btnCfg)
        setContentView(root)
    }

    /** 凭据设置：App ID / App Key / Access Token（本地明文保存，APK 不含） */
    private fun promptCreds() {
        val (a, k, t) = creds()
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        val e1 = android.widget.EditText(this).apply { hint = "App ID"; setText(a); layoutParams = lp }
        val e2 = android.widget.EditText(this).apply { hint = "App Key"; setText(k); layoutParams = lp }
        val e3 = android.widget.EditText(this).apply { hint = "Access Token"; setText(t); layoutParams = lp }
        box.addView(e1); box.addView(e2); box.addView(e3)
        android.app.AlertDialog.Builder(this)
            .setTitle("凭据设置")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString(KEY_APPID, e1.text.toString().trim())
                    .putString(KEY_APPKEY, e2.text.toString().trim())
                    .putString(KEY_TOKEN, e3.text.toString().trim())
                    .apply()
                status.text = "✅ 凭据已保存，点「开始对话」"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun say(prefix: String, text: String) {
        ui.post {
            transcript.append("[${sdf.format(Date())}] $prefix: $text\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ---------------- 引擎 ----------------

    private fun creds(): Triple<String, String, String> {
        val appid = prefs.getString(KEY_APPID, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_APPID
        val appkey = prefs.getString(KEY_APPKEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_APPKEY
        val token = prefs.getString(KEY_TOKEN, "") ?: ""
        return Triple(appid, appkey, token)
    }

    private fun startSession() {
        val (appid, appkey, token) = creds()
        if (token.isBlank()) {
            status.text = "⚠️ 请先在设置页填写 Access Token"
            say("系统", "未配置 Access Token，无法启动")
            return
        }
        status.text = "正在初始化引擎…"
        Thread {
            try {
                SpeechEngineGenerator.PrepareEnvironment(applicationContext, application)
                val e = SpeechEngineGenerator.getInstance()
                e.createEngine()
                engine = e

                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING,
                    SpeechEngineDefines.DIALOG_ENGINE)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_UID_STRING, "mmd-tv-001")

                // ---- 旧版鉴权：app_id + app_key + app_token（三件套，勿与新版 api_key 混用）----
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING, appid)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_KEY_STRING, appkey)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING, token)

                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_RESOURCE_ID_STRING, RESOURCE_ID)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_ADDRESS_STRING, ADDRESS)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_URI_STRING, URI)
                e.setOptionString(SpeechEngineDefines.PARAMS_KEY_RECORDER_TYPE_STRING,
                    SpeechEngineDefines.RECORDER_TYPE_RECORDER)

                // ---- 内置 AEC（关键：既录音又播放必须开启；开启时必须给模型路径）----
                val dir = applicationContext.getExternalFilesDir(null) ?: filesDir
                val model = File(dir, AEC_MODEL)
                if (!model.exists()) {
                    // 首次运行从 assets 释放
                    try {
                        assets.open("testdata/aec/$AEC_MODEL").use { i ->
                            model.outputStream().use { o -> i.copyTo(o) }
                        }
                    } catch (t: Throwable) {
                        L.w(TAG, "释放 AEC 模型失败: ${t.message}")
                    }
                }
                e.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ENABLE_AEC_BOOL, true)
                if (model.exists()) {
                    e.setOptionString(SpeechEngineDefines.PARAMS_KEY_AEC_MODEL_PATH_STRING,
                        model.absolutePath)
                }

                e.setContext(applicationContext)
                e.setListener(this)
                val ret = e.initEngine()
                L.i(TAG, "initEngine = $ret")
                if (ret != 0) {
                    ui.post { status.text = "❌ 初始化失败 ret=$ret" }
                    say("系统", "初始化失败 ret=$ret")
                    return@Thread
                }
                ui.post { status.text = "✅ 引擎就绪" }
                startConn()
            } catch (t: Throwable) {
                L.e(TAG, "start failed", t)
                ui.post { status.text = "❌ 异常: ${t.message}" }
            }
        }.start()
    }

    private fun startConn() {
        val e = engine ?: return
        e.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "")
        // extra.input_mod=keep_alive 是全双工开关（缺了服务端报 45000001）
        val json = """{"dialog":{"extra":{"input_mod":"keep_alive","model":"1.2.1.1"},"bot_name":"豆包"}}"""
        val ret = e.sendDirective(SpeechEngineDefines.DIRECTIVE_START_ENGINE, json)
        L.i(TAG, "START_ENGINE = $ret")
        started = ret == 0
        ui.post { status.text = if (started) "🎙️ 对话中，直接说话即可" else "❌ 启动失败 ret=$ret" }
    }

    private fun stopSession() {
        try {
            engine?.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "")
            engine?.sendDirective(SpeechEngineDefines.DIRECTIVE_STOP_ENGINE, "")
        } catch (_: Throwable) {
        }
        started = false
        status.text = "已停止"
        say("系统", "会话已停止")
    }

    override fun onDestroy() {
        try {
            stopSession()
            engine = null
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    // ---------------- 回调 ----------------

    override fun onSpeechMessage(type: Int, data: ByteArray?, len: Int) {
        val raw = data?.let { String(it, 0, minOf(len, it.size)) } ?: ""
        when (type) {
            1001 -> { L.i(TAG, "引擎启动"); ui.post { status.text = "✅ 引擎已启动" } }
            1002 -> L.i(TAG, "引擎停止")
            1003 -> {
                L.e(TAG, "引擎错误: $raw")
                say("系统", "引擎错误: ${raw.take(160)}")
            }
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_SESSION_STARTED -> {
                L.i(TAG, "对话建立: $raw")
                ui.post { status.text = "🎙️ 对话中（会话已建立）" }
                say("系统", "会话已建立，请说话")
            }
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_ASR_RESPONSE -> {
                // 用户语音实时识别（播报期也会到达 → 支持打断）
                runCatching {
                    val o = JSONObject(raw)
                    val txt = o.optJSONArray("results")?.optJSONObject(0)?.optString("text").orEmpty()
                    val score = o.optJSONObject("extra")?.optDouble("interrupt_score", 0.0) ?: 0.0
                    if (txt.isNotEmpty()) {
                        L.i(TAG, "ASR: $txt (interrupt_score=$score, aiSpeaking=$aiSpeaking)")
                        say("你", txt)
                    }
                }
            }
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_ASR_ENDED -> {}
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_SENTENCE_START -> {
                aiSpeaking = true
                ui.post { status.text = "🔊 AI 播报中（此刻说话可打断）" }
            }
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_SENTENCE_END -> {}
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_ENDED -> {
                aiSpeaking = false
                ui.post { status.text = "🎙️ 对话中，直接说话即可" }
            }
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_TTS_RESPONSE -> {}
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_CHAT_RESPONSE -> {
                runCatching {
                    val c = JSONObject(raw).optString("content")
                    if (c.isNotEmpty()) say("AI", c)
                }
            }
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_CHAT_ENDED -> {}
            else -> L.d(TAG, "EVT[$type] $raw")
        }
    }

    override fun onSpeechLogid(logid: String?) {
        L.d(TAG, "logid: $logid")
    }
}
