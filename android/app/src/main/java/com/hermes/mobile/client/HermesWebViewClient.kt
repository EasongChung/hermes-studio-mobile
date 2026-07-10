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
 * 2. 页面加载完成后，调用注入回调将 Server URL 注入前端 localStorage
 * 3. 控制页面内链接的打开方式
 *
 * @param onPageLoaded 页面加载完成后的回调，参数为要注入的 Server URL
 */
class HermesWebViewClient(
    private val onPageLoaded: ((serverUrl: String) -> Unit)? = null
) : WebViewClient() {

    companion object {
        private const val TAG = "HermesWebViewClient"
    }

    /**
     * 保存传入的 Server URL，在 onPageFinished 时使用
     */
    var serverUrl: String = ""
        set(value) {
            field = value
            Log.d(TAG, "Server URL set: $value")
        }

    /**
     * 页面开始加载时回调
     */
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        Log.d(TAG, "Page started loading: $url")
    }

    /**
     * 页面加载完成时回调
     *
     * 当本地前端加载完成后，立即注入 Server URL 到前端 localStorage。
     * 注入时机必须在 onPageFinished 之后（此时 JS 上下文已就绪）。
     * 注入后前端 api/client.ts 中的 getBaseUrl() 会从 localStorage 读取地址。
     */
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.d(TAG, "Page finished loading: $url")

        // 页面加载完成后，触发注入回调
        if (serverUrl.isNotEmpty()) {
            onPageLoaded?.invoke(serverUrl)
            Log.d(TAG, "Server URL injected via callback")
        }
    }

    /**
     * 控制页面内链接的打开方式
     * 返回 false 让 WebView 自行处理
     */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return false
    }

    /**
     * 页面加载出错时回调
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