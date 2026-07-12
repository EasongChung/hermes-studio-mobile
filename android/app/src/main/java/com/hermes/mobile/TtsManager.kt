package com.hermes.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TtsManager - Android TTS 朗读管理类
 *
 * 负责 TextToSpeech 引擎的初始化和朗读控制。
 * 跟随 Activity 生命周期：create() / stop() / destroy()
 *
 * 主要功能：
 * - 初始化 TTS 引擎（支持中文优先，回退到系统默认语言）
 * - speak(text) 朗读文本（引擎未就绪时自动延迟重试）
 * - stop() 停止朗读
 * - isSpeaking() 查询朗读状态
 * - destroy() 释放资源（Activity onDestroy 时调用）
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "HermesTTS"
        private const val RETRY_DELAY_MS = 500L // 引擎未就绪时的重试延迟（毫秒）
    }

    private var tts: TextToSpeech? = null
    var isInitialized = false
        private set
    private var onDoneCallback: (() -> Unit)? = null

    /**
     * 初始化 TTS 引擎
     * @param callback 初始化完成后的回调（可选）
     */
    fun init(callback: (() -> Unit)? = null) {
        if (isInitialized) {
            callback?.invoke()
            return
        }
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
     *
     * 如果引擎尚未就绪，会自动重新初始化并在延迟后重试朗读。
     * 支持 UtteranceProgressListener 回调，通过 onDone 通知朗读完成状态。
     *
     * @param text 要朗读的文本内容
     * @param onDone 朗读完成后的回调（可选）
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized, retrying after ${RETRY_DELAY_MS}ms")
            init {
                Handler(Looper.getMainLooper()).postDelayed({
                    speakDirect(text, onDone)
                }, RETRY_DELAY_MS)
            }
            return
        }
        speakDirect(text, onDone)
    }

    /**
     * 直接执行朗读（不检查初始化状态）
     * @param text 要朗读的文本
     * @param onDone 朗读完成回调
     */
    private fun speakDirect(text: String, onDone: (() -> Unit)? = null) {
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
     *
     * 在 Activity.onDestroy() 中调用，顺序：
     * 1. 停止当前朗读
     * 2. 关闭 TTS 引擎
     * 3. 清空引用
     */
    fun destroy() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS destroyed")
    }
}