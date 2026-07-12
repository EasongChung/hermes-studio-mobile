package com.hermes.mobile

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TtsManager - Android TTS 朗读管理类
 *
 * 负责 TextToSpeech 引擎的初始化和朗读控制。
 * 跟随 Activity 生命周期：create() / stop() / destroy()
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "HermesTTS"
    }

    private var tts: TextToSpeech? = null
    var isInitialized = false
        private set
    private var onDoneCallback: (() -> Unit)? = null

    /**
     * 初始化 TTS 引擎
     */
    fun init(callback: (() -> Unit)? = null) {
        if (isInitialized) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS: Chinese language not fully supported, falling back to default")
                    tts?.setLanguage(Locale.getDefault())
                }
                isInitialized = true
                Log.d(TAG, "TTS initialized successfully")
                callback?.invoke()
            } else {
                Log.e(TAG, "TTS initialization failed, status=$status")
            }
        }
    }

    /**
     * 朗读文本
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized, init now")
            init {
                speak(text, onDone)
            }
            return
        }

        onDoneCallback = onDone
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onDoneCallback?.invoke()
            }
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS speak error: utteranceId=$utteranceId")
            }
        })

        val utteranceId = "hermes_tts_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.d(TAG, "TTS speaking: ${text.take(50)}...")
    }

    /**
     * 停止朗读
     */
    fun stop() {
        tts?.stop()
        Log.d(TAG, "TTS stopped")
    }

    /**
     * 是否正在朗读
     */
    fun isSpeaking(): Boolean = tts?.isSpeaking ?: false

    /**
     * 释放资源
     */
    fun destroy() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS destroyed")
    }
}