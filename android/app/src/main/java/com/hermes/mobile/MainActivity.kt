package com.hermes.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.hermes.mobile.client.HermesChromeClient
import com.hermes.mobile.config.ServerConfig

/**
 * MainActivity - Hermes Studio Mobile 主界面
 *
 * 职责：
 * 1. 检查服务器地址是否已配置，未配置则跳转 ConfigActivity
 * 2. 创建并配置 WebView 实例
 * 3. 直接从服务器加载前端（同源请求，无需跨域注入）
 * 4. 页面加载完成后注入移动端标记
 *
 * 加载流程：
 * 首次启动 → 检查配置 → 未配置 → 跳转 ConfigActivity
 *                     → 已配置 → 加载 http://服务器/ → 页面加载完成
 *
 * 为什么不从 assets/hermes/ 本地加载：
 * Hermes 网关本身就是全栈服务，前端、API、WebSocket 共用一个地址。
 * 从 file:// 加载前端会导致跨源 HTTP 请求被 WebView 安全策略拦截，
 * 即使启用 allowUniversalAccessFromFileURLs 在某些 Android 版本仍不可靠。
 * 直接从 HTTP 加载，所有请求同源，没有任何跨域问题。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesWeb"
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
     * 直接从 HTTP 服务器加载前端，不需要 file:// 相关权限和跨源设置。
     * 请求同源，API 请求自然可以正常发送。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            setNeedInitialFocus(true)
        }

        // 设置 WebViewClient，页面加载完成后注入移动端标记
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面加载完成后注入移动端环境标记
                injectMobileFlag()
                // 注入页面 URL，确保前端 api/client.ts 中的
                // getBaseUrl() 能正确读取到服务器地址
                injectServerUrl()
            }

            // 拦截所有链接在当前 WebView 内打开
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false // 返回 false 让 WebView 自行处理
            }
        }
        webView.webChromeClient = HermesChromeClient()

        // 启用 WebView 远程调试（仅在 debug 构建有效）
        WebView.setWebContentsDebuggingEnabled(true)
    }

    /**
     * 从服务器加载前端
     *
     * 直接访问服务器 HTTP 地址，Hermes 网关会返回前端页面。
     * 所有 API 请求、WebSocket 连接自动同源，无需额外配置。
     */
    private fun loadFrontend() {
        webView.loadUrl(serverUrl)
    }

    /**
     * 注入移动端环境标记到前端 localStorage
     *
     * 前端某些 UI 组件根据 this.$hermesIsMobile 或 navigator.userAgent
     * 判断移动端环境，额外注入标记确保兼容性。
     */
    private fun injectMobileFlag() {
        val js = """
            (function() {
                try {
                    localStorage.setItem('hermes_is_mobile', 'true');
                    console.log('[HermesMobile] Mobile flag injected');
                } catch(e) {
                    console.error('[HermesMobile] Injection failed:', e);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /**
     * 注入 Server URL 和 WebSocket URL 到前端 localStorage
     *
     * 虽然大部分请求同源不需要注入，但前端 api/client.ts 的 getBaseUrl()
     * 默认读取 localStorage 中的 hermes_server_url。
     * 同步注入确保任意场景下都能正确获取。
     */
    private fun injectServerUrl() {
        val normalizedUrl = serverUrl.trimEnd('/')
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