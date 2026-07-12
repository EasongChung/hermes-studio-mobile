package com.hermes.mobile

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * TtsManager - Android TTS 朗读管理类
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "HermesTTS"
    }

    private var tts: TextToSpeech? = null
    var isInitialized = false
        private set

    fun init() {
        if (isInitialized) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                isInitialized = true
                Log.d(TAG, "TTS initialized")
            }
        }
    }

    fun speak(text: String) {
        if (!isInitialized || tts == null) {
            init()
            // 延迟重试（等待初始化完成）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                speakDirect(text)
            }, 500)
            return
        }
        speakDirect(text)
    }

    private fun speakDirect(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        Log.d(TAG, "TTS: ${text.take(50)}...")
    }

    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking ?: false

    fun destroy() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}