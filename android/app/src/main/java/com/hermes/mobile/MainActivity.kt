package com.hermes.mobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.hermes.mobile.client.HermesWebViewClient
import com.hermes.mobile.client.HermesChromeClient

/**
 * MainActivity - Hermes Studio Mobile 主界面
 *
 * 职责：
 * 1. 创建并配置 WebView 实例
 * 2. 加载本地前端资源（assets/hermes/index.html）
 * 3. 提供 Server URL 注入接口给上层调用
 *
 * 加载流程：
 * 首次启动 → 检查配置 → 未配置 → 跳转 ConfigActivity
 *                     → 已配置 → 加载本地前端 → 注入 Server URL
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesWeb"
        // 本地前端资源路径（assets/hermes/ 目录下的入口文件）
        private const val LOCAL_FRONTEND_URL = "file:///android_asset/hermes/index.html"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            // JavaScript 支持（Vue 应用必需）
            javaScriptEnabled = true
            // DOM Storage 支持（localStorage/sessionStorage）
            domStorageEnabled = true
            // 数据库支持（Web SQL）
            databaseEnabled = true
            // 缓存模式：优先使用缓存，无缓存则从网络加载
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            // 文件访问权限（加载本地 assets 资源必需）
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowContentAccess = true
            // 视口自适应
            useWideViewPort = true
            loadWithOverviewMode = true
            // 混合内容：允许 HTTP 页面加载 HTTPS 资源
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 编码
            defaultTextEncodingName = "UTF-8"
            // 不显示缩放按钮
            builtInZoomControls = true
            displayZoomControls = false
            // 支持多窗口（如新标签页）
            setSupportMultipleWindows(false)
            // 聚焦时自动请求焦点
            setNeedInitialFocus(true)
        }

        // 设置 WebViewClient（控制页面加载行为）
        webView.webViewClient = HermesWebViewClient()
        // 设置 WebChromeClient（处理 JS 弹窗、进度等）
        webView.webChromeClient = HermesChromeClient()

        // 启用 WebView 远程调试（仅在 debug 构建有效）
        WebView.setWebContentsDebuggingEnabled(true)
    }

    /**
     * 加载本地前端资源
     *
     * 从 assets/hermes/ 目录加载构建好的前端 dist 文件。
     * 前端使用 Hash 路由，天然支持 file:// 协议。
     * 加载完成后，onPageFinished 回调中注入 Server URL 配置。
     */
    private fun loadFrontend() {
        webView.loadUrl(LOCAL_FRONTEND_URL)
    }

    /**
     * 注入 Server URL 到前端
     *
     * 在 onPageFinished 回调中执行 JavaScript，将 Server URL
     * 写入 localStorage。前端通过 api/client.ts 中的 getBaseUrl()
     * 读取 localStorage.getItem('hermes_server_url') 获取地址。
     *
     * @param serverUrl HTTP 服务器地址（如 http://192.168.1.100:8648）
     */
    fun injectServerUrl(serverUrl: String) {
        // 清理末尾的斜杠
        val normalizedUrl = serverUrl.trimEnd('/')

        // 从 HTTP URL 推导 WebSocket URL（http→ws，https→wss）
        val wsUrl = normalizedUrl.replace("http://", "ws://").replace("https://", "wss://")

        // 注入 JS 到 WebView
        // 使用 localStorage 存储，前端自动读取
        val js = """
            (function() {
                // 设置 Server URL（HTTP API 基地址）
                localStorage.setItem('hermes_server_url', '$normalizedUrl');
                // 设置 WebSocket URL（供 socket.io 连接使用）
                localStorage.setItem('hermes_ws_url', '$wsUrl');
                // 标记为 Mobile APP 环境
                localStorage.setItem('hermes_is_mobile', 'true');
                console.log('[HermesMobile] Server URL injected:', '$normalizedUrl');
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

    /**
     * 生命周期：页面暂停
     * 暂停 WebView 的 JS 执行和定时器，节省资源
     */
    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    /**
     * 生命周期：页面恢复
     * 恢复 WebView 的 JS 执行
     */
    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    /**
     * 生命周期：销毁
     * 清理 WebView 资源防止内存泄漏
     */
    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}