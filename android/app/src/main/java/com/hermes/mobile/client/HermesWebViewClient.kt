package com.hermes.mobile.client

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log

/**
 * HermesWebViewClient - 控制 WebView 页面加载行为
 *
 * 职责：
 * 1. 页面加载开始/完成/失败的回调处理
 * 2. 控制页面内链接的打开方式（在当前 WebView 内打开，不跳转浏览器）
 * 3. 加载过程中显示/隐藏加载指示器
 */
class HermesWebViewClient : WebViewClient() {

    companion object {
        private const val TAG = "HermesWebViewClient"
    }

    /**
     * 页面开始加载时回调
     * 可用于显示加载进度指示器
     */
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        Log.d(TAG, "Page started loading: $url")
    }

    /**
     * 页面加载完成时回调
     * 可用于隐藏加载指示器，或在此时注入 JS 配置
     */
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.d(TAG, "Page finished loading: $url")
    }

    /**
     * 控制页面内链接的打开方式
     * 返回 true：在当前 WebView 内打开
     * 返回 false：启动系统浏览器打开
     *
     * 这里始终返回 false（使用默认行为），让 WebView 自行处理
     */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return false
    }

    /**
     * 页面加载出错时回调
     * 可根据错误类型做不同的处理（如显示错误页面）
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        Log.e(TAG, "Page error: ${error?.description} (code: ${error?.errorCode})")
    }
}