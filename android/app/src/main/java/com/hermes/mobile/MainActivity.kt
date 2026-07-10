package com.hermes.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.hermes.mobile.client.HermesChromeClient
import com.hermes.mobile.config.ServerConfig
import java.io.InputStream

/**
 * MainActivity - Hermes Studio Mobile 主界面
 *
 * 设计原则：混合加载 — 本地前端资源 + 远程 API 请求
 *
 * WebView 加载服务器 HTTP 地址（同源，API 请求不受 CORS 限制），
 * 通过 shouldInterceptRequest 拦截前端静态资源请求（HTML/CSS/JS）
 * 从 APK 本地 assets/hermes/ 返回，实现前端秒开。
 * API 请求放行走网络，同源发往服务器。
 *
 * 这种方案的优势：
 * 1. 前端资源本地加载（秒开），无需走网络下载几 MB 的前端代码
 * 2. API 请求同源（http://服务器），不受 CORS 跨域限制
 * 3. WebSocket 连接同源，不受跨域限制
 * 4. 用户体验和原生 APP 一样快
 *
 * 加载流程：
 * 首次启动 → 检查配置 → 未配置 → 跳转 ConfigActivity
 *                     → 已配置 → 加载 http://服务器/
 *                              → shouldInterceptRequest 拦截前端资源 → 本地返回
 *                              → API 请求放行 → 走网络同源
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesWeb"
        // 前端资源本地存储路径
        private const val ASSETS_FRONTEND_PATH = "hermes"
        // 需要从本地加载的前端资源文件扩展名（白名单）
        private val STATIC_FILE_EXTENSIONS = setOf(
            "html", "js", "css", "json", "svg", "png", "jpg", "jpeg",
            "gif", "ico", "woff", "woff2", "ttf", "eot", "otf", "webp"
        )
        // API 请求路径前缀（黑名单，放行走网络）
        private val API_PATH_PREFIXES = setOf(
            "/api/", "/v1/", "/health", "/upload", "/webhook", "/socket.io/"
        )
    }

    private lateinit var webView: WebView
    private lateinit var serverConfig: ServerConfig
    private var serverUrl: String = ""
    private var serverOrigin: String = ""  // http://host:port

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
        // 提取 Origin（http://host:port），用于判断是否拦截
        serverOrigin = extractOrigin(serverUrl)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        configureWebView()
        loadFrontend()
    }

    /**
     * 配置 WebView 的各项设置
     *
     * 直接从 HTTP 服务器加载前端，所有请求同源。
     * shouldInterceptRequest 负责将静态资源请求拦截到本地 assets。
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
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            defaultTextEncodingName = "UTF-8"
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            setNeedInitialFocus(true)
        }

        // 核心：混合加载 WebViewClient
        // 拦截前端静态资源请求从本地 assets 返回，API 请求放行走网络
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val uri = request.url

                // 只拦截同源请求（http://服务器地址/...）
                if (!url.startsWith(serverOrigin)) return null

                val path = uri.path ?: return null

                // API 请求放行走网络
                if (API_PATH_PREFIXES.any { path.startsWith(it) }) return null

                // 静态资源请求：从本地 assets 返回
                return tryServeLocalAsset(path)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false // 返回 false 让 WebView 自行处理
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectMobileFlag()
            }
        }
        webView.webChromeClient = HermesChromeClient()

        // 启用 WebView 远程调试（仅在 debug 构建有效）
        WebView.setWebContentsDebuggingEnabled(true)
    }

    /**
     * 加载服务器前端
     *
     * 访问服务器 HTTP 地址，WebView 会发起请求获取 index.html。
     * shouldInterceptRequest 会拦截这个请求，从本地 assets 返回。
     * 后续的 JS/CSS 资源请求同样被拦截到本地 assets。
     * API 请求放行走网络，同源发往服务器。
     */
    private fun loadFrontend() {
        webView.loadUrl(serverUrl)
    }

    /**
     * 尝试从本地 assets 返回静态资源
     *
     * 路径映射规则：
     * - / → hermes/index.html
     * - /assets/xxx.js → hermes/assets/xxx.js
     * - /favicon.ico → hermes/favicon.ico
     *
     * 如果本地 assets 中没有该文件，返回 null 让 WebView 走网络。
     *
     * @param path 请求路径（如 /assets/index.js）
     * @return 本地资源响应，或 null 表示走网络
     */
    private fun tryServeLocalAsset(path: String): WebResourceResponse? {
        // 根路径返回 index.html
        val assetPath = when {
            path == "/" || path.isEmpty() -> "$ASSETS_FRONTEND_PATH/index.html"
            path.startsWith("/") -> "$ASSETS_FRONTEND_PATH${path}"
            else -> "$ASSETS_FRONTEND_PATH/$path"
        }

        // 检查文件扩展名是否在白名单中（只拦截静态资源）
        val extension = assetPath.substringAfterLast('.', "").lowercase()
        if (extension !in STATIC_FILE_EXTENSIONS) return null

        // 尝试打开本地 assets 文件
        val inputStream: InputStream = try {
            assets.open(assetPath)
        } catch (e: Exception) {
            // 本地文件不存在，走网络
            return null
        }

        // 根据扩展名确定 MIME 类型
        val mimeType = getMimeType(extension)
        val encoding = if (extension == "html" || extension == "js" || extension == "css") "UTF-8" else null

        return WebResourceResponse(mimeType, encoding, inputStream)
    }

    /**
     * 根据文件扩展名获取 MIME 类型
     */
    private fun getMimeType(extension: String): String = when (extension) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "eot" -> "application/vnd.ms-fontobject"
        "otf" -> "font/otf"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    /**
     * 从 URL 中提取 Origin（http://host:port）
     */
    private fun extractOrigin(url: String): String {
        val normalized = url.trimEnd('/')
        // 找到第三个 / 之后的内容（http://host:port/...）
        val schemeEnd = normalized.indexOf("://")
        if (schemeEnd < 0) return normalized
        val pathStart = normalized.indexOf('/', schemeEnd + 3)
        return if (pathStart >= 0) normalized.substring(0, pathStart) else normalized
    }

    /**
     * 注入移动端环境标记到前端 localStorage
     *
     * 前端某些 UI 组件根据 isMobile 判断移动端环境，
     * 注入标记确保兼容性。
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