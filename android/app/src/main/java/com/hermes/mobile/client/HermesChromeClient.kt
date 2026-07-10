package com.hermes.mobile.client

import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.util.Log

/**
 * HermesChromeClient - 处理 WebView 中与浏览器 Chrome 相关的功能
 *
 * 职责：
 * 1. 处理 JS 弹窗（alert/confirm/prompt）
 * 2. 页面加载进度回调
 * 3. 页面标题更新回调
 */
class HermesChromeClient : WebChromeClient() {

    companion object {
        private const val TAG = "HermesChromeClient"
    }

    /**
     * 页面加载进度变化时回调
     * @param newProgress 0-100 的进度值
     */
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        // 可用于更新进度条
        if (newProgress == 100) {
            Log.d(TAG, "Page load complete (100%)")
        }
    }

    /**
     * 处理 JS 的 alert() 弹窗
     * 使用 Android 原生的对话框显示
     */
    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        Log.d(TAG, "JS Alert: $message")
        // 使用默认处理方式
        return super.onJsAlert(view, url, message, result)
    }

    /**
     * 处理 JS 的 confirm() 弹窗
     */
    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        Log.d(TAG, "JS Confirm: $message")
        return super.onJsConfirm(view, url, message, result)
    }

    /**
     * 页面标题更新时回调
     * 可用于更新 ActionBar 标题
     */
    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        Log.d(TAG, "Page title: $title")
    }
}