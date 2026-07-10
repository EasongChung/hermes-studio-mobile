package com.hermes.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.hermes.mobile.client.HermesChromeClient
import com.hermes.mobile.client.HermesWebViewClient
import com.hermes.mobile.config.ServerConfig

/**
 * MainActivity - Hermes Studio Mobile 主界面
 *
 * 设计原则：本地加载前端，远程调用 API
 *
 * 前端资源（HTML/CSS/JS）打包在 APK assets/hermes/ 中，
 * 从 file:// 协议本地加载，瞬间完成。
 *
 * API 请求通过 WebView 的跨源访问权限发往用户配置的服务器地址，
 * 在 onPageFinished 时通过 evaluateJavascript 将服务器地址
 * 注入到 localStorage，前端 api/client.ts 中的 getBaseUrl()
 * 读取 localStorage 获得服务器地址，发起 API 请求。
 *
 * 加载流程：
 * 首次启动 → 检查配置 → 未配置 → 跳转 ConfigActivity
 *                     → 已配置 → 加载本地前端（file://）
 *                              → onPageFinished 注入 server_url
 *                              → 前端发起 API 请求到服务器
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesWeb"
        private const val LOCAL_FRONTEND_URL = "file:///android_asset/hermes/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var serverConfig: ServerConfig
    private var serverUrl: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serverConfig = ServerConfig(this)

        // 检查是否已配置服务器地址
        if (!serverConfig.isConfigured()) {
            // 未配置：跳转到配置界面
            startActivity(Intent(this, ConfigActivity::class.java))
            finish()
            return
        }

        // 已配置：读取服务器 URL
        serverUrl = serverConfig.getServerUrl() ?: ""

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        configureWebView()
        loadFrontend()
    }

    /**
     * 配置 WebView 的各项设置
     *
     * 关键配置说明：
     * - allowUniversalAccessFromFileURLs：核心！允许 file:// 页面
     *   向任意 HTTP 服务器发起跨源请求（API 通信必需）
     * - allowFileAccessFromFileURLs：允许 file:// 页面访问其他文件
     * - domStorageEnabled：localStorage 用于存储服务器地址
     * - mixedContentMode：允许 HTTPS 页面加载 HTTP 内容
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            // file:// 协议相关权限
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            // ★ 允许 file:// 页面跨源访问 HTTP API（核心修复）
            allowUniversalAccessFromFileURLs = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            defaultTextEncodingName = "UTF-8"
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            setNeedInitialFocus(true)
        }

        // 设置 WebViewClient，传入注入回调
        // serverUrl 必须在加载前赋值，onPageFinished 时触发注入
        val webViewClient = HermesWebViewClient { injectedUrl ->
            injectServerUrl(injectedUrl)
        }
        webViewClient.serverUrl = serverUrl
        webView.webViewClient = webViewClient
        webView.webChromeClient = HermesChromeClient()

        // 启用 WebView 远程调试（仅在 debug 构建有效）
        WebView.setWebContentsDebuggingEnabled(true)
    }

    /**
     * 加载本地前端资源
     *
     * 从 assets/hermes/ 目录加载构建好的前端 dist 文件。
     * 前端使用 Hash 路由，天然支持 file:// 协议。
     * 本地加载速度远快于远程加载，这是本 APP 的核心优势。
     */
    private fun loadFrontend() {
        webView.loadUrl(LOCAL_FRONTEND_URL)
    }

    /**
     * 注入 Server URL 到前端 localStorage
     *
     * 在 onPageFinished 回调中执行 JavaScript，将 Server URL
     * 写入 localStorage。前端通过 api/client.ts 中的 getBaseUrl()
     * 读取 localStorage.getItem('hermes_server_url') 获取地址。
     *
     * 注入内容：
     * - hermes_server_url: HTTP 基础地址（API 请求用）
     * - hermes_ws_url: WebSocket 地址（socket.io 连接用，HTTP URL 派生）
     * - hermes_is_mobile: 标记移动端环境
     */
    private fun injectServerUrl(url: String) {
        val normalizedUrl = url.trimEnd('/')
        val wsUrl = normalizedUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")

        val js = """
            (function() {
                try {
                    localStorage.setItem('hermes_server_url', '$normalizedUrl');
                    localStorage.setItem('hermes_ws_url', '$wsUrl');
                    localStorage.setItem('hermes_is_mobile', 'true');
                    console.log('[HermesMobile] Server URL injected:', '$normalizedUrl');
                } catch(e) {
                    console.error('[HermesMobile] Injection failed:', e);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /**
     * 处理系统返回键
     *
     * 如果 WebView 可以后退（有历史记录），则执行页面后退；
     * 否则执行默认行为（退出 APP）。
     */
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}