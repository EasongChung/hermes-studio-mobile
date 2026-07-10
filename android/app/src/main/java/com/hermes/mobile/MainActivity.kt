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
 * 职责：
 * 1. 检查服务器地址是否已配置，未配置则跳转 ConfigActivity
 * 2. 创建并配置 WebView 实例
 * 3. 加载本地前端资源（assets/hermes/index.html）
 * 4. 页面加载完成后注入 Server URL 到前端 localStorage
 *
 * 加载流程：
 * 首次启动 → 检查配置 → 未配置 → 跳转 ConfigActivity
 *                     → 已配置 → 加载本地前端 → onPageFinished 注入 URL
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
     * 这些配置是前端正常运行的前提条件：
     * - javaScriptEnabled：前端 Vue 框架基于 JS 运行
     * - domStorageEnabled：localStorage 用于持久化配置
     * - allowFileAccess：file:// 协议加载本地资源
     * - mixedContentMode：允许 HTTP 页面加载 HTTPS 资源（反之亦然）
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
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
        webView.webViewClient = HermesWebViewClient { injectedUrl ->
            // 页面加载完成后，注入 Server URL 到前端 localStorage
            injectServerUrl(injectedUrl)
        }
        webView.webChromeClient = HermesChromeClient()

        // 启用 WebView 远程调试（仅在 debug 构建有效）
        WebView.setWebContentsDebuggingEnabled(true)
    }

    /**
     * 加载本地前端资源
     *
     * 从 assets/hermes/ 目录加载构建好的前端 dist 文件。
     * 前端使用 Hash 路由，天然支持 file:// 协议。
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
     *
     * @param url 要注入的服务器 HTTP 地址
     */
    private fun injectServerUrl(url: String) {
        val normalizedUrl = url.trimEnd('/')
        // 从 HTTP URL 推导 WebSocket URL（http→ws，https→wss）
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
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}