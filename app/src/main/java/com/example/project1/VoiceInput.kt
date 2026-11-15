package com.example.project1

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

/**
 * 음성으로 이름 넣기 — Pleos Gleo AI SpeechToText SDK (2.1.3.2)
 *
 * 흐름: SpeechToText(context, Mode) → addListener → initialize()
 *       → onReady 에서 registerApp(Client ID, Secret) → request() 로 듣기 시작
 *       → onUpdated(글자, 최종인가?) → 최종이면 이름 칸에 넣기
 * 마이크는 차의 음성 서비스가 직접 잡는다 (우리 앱은 결과 글자만 받음).
 * 키가 없거나 연결에 실패하면 available=false → 화면에서 마이크 버튼이 안 보이고 나머지는 그대로 동작.
 */
class VoiceInput(context: Context) {

    enum class State { PREPARING, READY, LISTENING, UNAVAILABLE }

    var state by mutableStateOf(State.PREPARING)
        private set
    /** 듣는 중에 보여줄 중간 글자 */
    var partial by mutableStateOf("")
        private set
    /** 마지막 오류 안내 (없으면 null) */
    var message by mutableStateOf<String?>(null)
        private set

    val available get() = state == State.READY || state == State.LISTENING

    private val main = Handler(Looper.getMainLooper())
    private var onFinal: ((String) -> Unit)? = null
    private val clientId = BuildConfig.PLEOS_CLIENT_ID
    private val clientSecret = BuildConfig.PLEOS_CLIENT_SECRET
    private val stt: SpeechToText?

    private val listener = object : ResultListener {
        // 듣기 준비 완료 신호 (등록은 initialize 직후 따로 함)
        // request() 뒤에 오는 "이제 말하세요" 신호
        override fun onReady() = ui { Log.d(TAG, "onReady"); if (onFinal != null) { state = State.LISTENING; partial = "" } }

        override fun onStartedRecognition() = ui { Log.d(TAG, "onStartedRecognition"); state = State.LISTENING }

        override fun onUpdated(text: String, isFinal: Boolean) = ui {
            Log.d(TAG, "onUpdated final=$isFinal text=$text")
            partial = text
            if (isFinal) {
                val name = cleanName(text)
                if (name.isNotEmpty()) onFinal?.invoke(name) else message = "잘 못 들었어요. 다시 말해 주세요"
                onFinal = null
                if (state == State.LISTENING) state = State.READY
            }
        }

        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) {}

        override fun onEndedRecognition() = ui {
            Log.d(TAG, "onEndedRecognition")
            if (state == State.LISTENING && onFinal == null) state = State.READY
        }

        override fun onError() = ui {
            Log.w(TAG, "onError (state=$state)")
            if (state == State.PREPARING) message = "음성 서비스 오류 (준비 중)"
            message = "잘 못 들었어요. 다시 말해 주세요"
            onFinal = null
            if (state == State.LISTENING || state == State.PREPARING) state = if (state == State.PREPARING) State.UNAVAILABLE else State.READY
        }
    }

    init {
        stt = runCatching {
            SpeechToText(context.applicationContext, Mode.SERVER).also {
                it.addListener(listener)
                it.initialize()
            }
        }.onFailure {
            Log.w(TAG, "SpeechToText 시작 실패 → 음성 입력 끔", it)
            state = State.UNAVAILABLE
            message = "SDK 시작 실패: ${it.javaClass.simpleName} ${it.message ?: ""}".take(80)
        }.getOrNull()
        register()
        // 10초 안에 준비가 안 되면 음성 입력 끄기 (차에 음성 서비스가 없는 경우 등)
        main.postDelayed({ if (state == State.PREPARING) { state = State.UNAVAILABLE; message = "음성 서비스 응답 없음 (등록 결과 안 옴)" } }, 15_000)
    }

    /** Pleos Playground My Project의 Client ID/Secret으로 앱 등록 → 성공하면 READY */
    private fun register() {
        val s = stt ?: return
        if (clientId.isBlank()) {
            state = State.UNAVAILABLE; message = "음성 키가 없어요 (local.properties)"; return
        }
        runCatching {
            s.registerApp(clientId, clientSecret, object : OnServerConnectionListener {
                override fun onConnected() = ui {
                    runCatching { s.setLocale(Locale.KOREAN) }
                    state = State.READY; message = null
                    Log.d(TAG, "registerApp 성공")
                }
                override fun onFailed(reason: String) = ui {
                    Log.w(TAG, "registerApp 실패: $reason")
                    state = State.UNAVAILABLE; message = "앱 등록 실패: $reason".take(80)
                }
            })
        }.onFailure { Log.w(TAG, "registerApp 오류", it); state = State.UNAVAILABLE; message = "앱 등록 오류: ${it.message ?: ""}".take(80) }
    }

    /** 듣기 시작. 최종 글자(이름)가 나오면 onName 호출 */
    fun listen(onName: (String) -> Unit) {
        if (!available) return
        message = null
        partial = ""
        onFinal = onName
        Log.d(TAG, "request()")
        runCatching { stt?.request() }.onFailure { Log.w(TAG, "request 오류", it); message = "음성 인식을 시작하지 못했어요" }
    }

    fun cancel() {
        onFinal = null
        runCatching { stt?.stop() }
        if (state == State.LISTENING) state = State.READY
    }

    fun release() {
        runCatching { stt?.removeListener(listener); stt?.release() }
    }

    private fun ui(block: () -> Unit) { main.post(block) }

    companion object {
        private const val TAG = "NbbangVoice"

        /** "민준이요." / "김민준 입니다" 같은 말에서 이름만 남기기 */
        fun cleanName(raw: String): String {
            var s = raw.replace(Regex("[\\p{Punct}·…\"'“”‘’]"), " ").trim()
            listOf("입니다", "이에요", "예요", "이요").forEach { suf ->
                if (s.length > suf.length + 1 && s.endsWith(suf)) s = s.removeSuffix(suf).trim()
            }
            return s.replace(Regex("\\s+"), " ").take(12)
        }
    }
}

/** 화면 어디서나 음성 입력을 꺼내 쓰기 위한 통로 (없으면 null → 마이크 버튼 숨김) */
val LocalVoice = compositionLocalOf<VoiceInput?> { null }
