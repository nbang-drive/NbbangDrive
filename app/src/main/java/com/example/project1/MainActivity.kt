package com.example.project1

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider

class MainActivity : ComponentActivity() {
    // 진짜 차량 신호 (Pleos Vehicle SDK). 연결에 실패하면 null → 모의 차량으로 동작
    private var pleos: PleosCar? = null
    // 음성으로 이름 넣기 (Gleo AI SpeechToText). 실패하면 null → 마이크 버튼 없이 동작
    private var voice: VoiceInput? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pleos = runCatching { PleosCar(this).also { it.start() } }
            .onFailure { Log.w("Nbbang", "Pleos Vehicle SDK 연결 실패 → 모의 차량 사용", it) }
            .getOrNull()

        voice = runCatching { VoiceInput(this) }
            .onFailure { Log.w("Nbbang", "음성 입력 준비 실패 → 끔", it) }
            .getOrNull()

        setContent {
            NbbangTheme {
                CompositionLocalProvider(LocalVoice provides voice) {
                    NbbangApp(pleos)
                }
            }
        }
    }

    override fun onDestroy() {
        pleos?.stop()
        voice?.release()
        super.onDestroy()
    }
}
