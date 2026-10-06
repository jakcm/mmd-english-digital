package com.mmd.englishdigital

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 豆包实时语音模型 3.0（Seeduplex）全双工 WebSocket 客户端。
 * 纯 JSON 文本帧协议，鉴权走 X-Api-Key（已实测验证）。
 */
class SeeduplexClient(
    private val apiKey: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(msg: String)
        fun onSessionCreated(sessionId: String?)
        fun onUserText(delta: String, final: Boolean)
        fun onAssistantText(delta: String)
        fun onAudioDelta(pcm: ByteArray)
        fun onAudioDone()
        fun onError(msg: String)

        /** 服务端开始下发某一轮 AI 音频（新回复开始）。 */
        fun onAudioStarted()
        /** 服务端检测到用户开口（全双工 barge-in 信号；也用于每轮用户说话开始）。 */
        fun onUserTurnStarted()
        /** 服务端确认已取消当前回复（response.cancel 的 ack）。 */
        fun onResponseCanceled()
    }

    companion object {
        const val URL = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"
        const val MODEL = "1.2.6.1"
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        const val FRAME_BYTES = 640 // 20ms @ 16k mono 16bit
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var opened = false

    fun connect(instructions: String, voice: String) {
        val req = Request.Builder()
            .url(URL)
            .addHeader("X-Api-Key", apiKey)
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                listener.onStatus("WebSocket 已连接 (HTTP ${response.code})，建立会话…")
                webSocket.send(buildSessionCreate(instructions, voice))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened = false
                listener.onError("连接失败: ${t.message} | HTTP ${response?.code}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
    }

    private fun buildSessionCreate(instructions: String, voice: String): String {
        val session = JSONObject()
            .put("model", MODEL)
            .put("instructions", instructions)
            .put("tools", org.json.JSONArray())
            .put(
                "audio", JSONObject()
                    .put(
                        "input", JSONObject().put(
                            "format", JSONObject().put("type", "pcm").put("rate", IN_RATE)
                        )
                    )
                    .put(
                        "output", JSONObject()
                            // 关键：pcm=32bit float，pcm_s16le=16bit int（可直接喂 AudioTrack）
                            .put("format", JSONObject().put("type", "pcm_s16le").put("rate", OUT_RATE))
                            .put("speed", 0)
                            .put("loudness", 0)
                            .put("voice", voice)
                    )
            )
        // 官方 demo 同款 extension 字段（对齐参数；enable_proactive_speak 可能影响服务端判停/主动说话行为）
        val extension = JSONObject()
            .put("extra", JSONObject().put("enable_proactive_speak", false))
            .put("dialog", JSONObject().put("extra", JSONObject()))
        return JSONObject()
            .put("type", "session.create")
            .put("event_id", UUID.randomUUID().toString())
            .put("session", session)
            .put("extension", extension)
            .toString()
    }

    /** 发送 16k/mono/16bit PCM 音频帧（20ms/640B 为佳）。 */
    fun sendAudio(pcm: ByteArray) {
        val w = ws ?: return
        if (!opened) return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        w.send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("event_id", UUID.randomUUID().toString())
                .put("audio", b64)
                .toString()
        )
    }

    /** 强制模型判停（用户说完）。 */
    fun sendCommit() {
        ws?.send(
            JSONObject()
                .put("type", "input_audio_buffer.commit")
                .put("event_id", UUID.randomUUID().toString())
                .toString()
        )
    }

    /**
     * 客户端主动打断：取消进行中的回复（服务端会停止生成并回 response.canceled），
     * 便于用户插话后直接进入下一轮识别。
     */
    fun sendCancel() {
        val w = ws ?: return
        if (!opened) return
        w.send(
            JSONObject()
                .put("type", "response.cancel")
                .put("event_id", UUID.randomUUID().toString())
                .toString()
        )
    }

    fun close() {
        try {
            ws?.send(
                JSONObject()
                    .put("type", "session.close")
                    .put("event_id", UUID.randomUUID().toString())
                    .toString()
            )
        } catch (_: Exception) {
        }
        ws?.close(1000, "bye")
        ws = null
        opened = false
    }

    private fun handle(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        val t = obj.optString("type")
        Log.i("MMD-English", "RECV: $t | ${text.take(200)}")
        when (t) {
            "session.created" ->
                listener.onSessionCreated(obj.optJSONObject("session")?.optString("id"))

            "conversation.item.input_audio_transcription.delta" ->
                listener.onUserText(obj.optString("delta").ifEmpty { obj.optString("text") }, false)

            "conversation.item.input_audio_transcription.started" ->
                listener.onUserTurnStarted()

            "conversation.item.input_audio_transcription.completed" ->
                listener.onUserText(
                    obj.optString("text").ifEmpty { obj.optString("transcript") }, true
                )

            "response.output_text.delta" ->
                listener.onAssistantText(obj.optString("delta").ifEmpty { obj.optString("text") })

            "response.output_audio.started" -> listener.onAudioStarted()

            "response.output_audio.delta" -> {
                val b64 = obj.optString("delta").ifEmpty { obj.optString("audio") }
                if (b64.isNotEmpty()) {
                    try {
                        listener.onAudioDelta(Base64.decode(b64, Base64.DEFAULT))
                    } catch (_: Exception) {
                    }
                }
            }

            "response.output_audio.done" -> listener.onAudioDone()

            "response.canceled" -> listener.onResponseCanceled()

            "error" -> {
                val err = obj.optJSONObject("error")
                listener.onError("服务端错误: " + (err?.toString() ?: obj.toString()))
            }
        }
    }
}
