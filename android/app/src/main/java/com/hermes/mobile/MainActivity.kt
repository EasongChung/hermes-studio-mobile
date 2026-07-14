package com.hermes.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hermes.mobile.client.HermesChromeClient
import com.hermes.mobile.config.ServerManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * MainActivity - Hermes Studio Mobile 主界面
 *
 * 混合加载 + 自动登录 token 注入 + 朗读/语音支持 + 三击退出
 *
 * 功能：
 * 1. 加载 Hermes Studio 前端（通过 intent 传入 server_url）
 * 2. 自动登录（用户名/密码 → JWT token → 注入 HTML）
 * 3. 修复朗读功能：注入 hermes_server_url 到 localStorage（前端 TTS API 依赖此值）
 * 4. 修复语音输入：处理 WebRTC 麦克风权限请求
 * 5. 三击返回键：连续按 3 次弹出退出登录确认框，回到服务器设置页
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesWeb"
        private const val ASSETS_FRONTEND_PATH = "hermes"
        private const val PREFS_NAME = "hermes_startup"
        private const val KEY_FIRST_LAUNCH = "first_launch_completed"

        // 双击退出参数
        private const val BACK_PRESS_TIMEOUT_MS = 1500L // 1.5 秒内连续按才计数
        private const val BACK_PRESS_THRESHOLD = 2      // 连续 2 次触发

        private val STATIC_FILE_EXTENSIONS = setOf(
            "html", "js", "css", "json", "svg", "png", "jpg", "jpeg",
            "gif", "ico", "woff", "woff2", "ttf", "eot", "otf", "webp"
        )
        private val API_PATH_PREFIXES = setOf(
            "/api/", "/v1/", "/health", "/upload", "/webhook", "/socket.io/"
        )
    }

    private lateinit var webView: WebView
    private var serverUrl: String = ""
    private var serverOrigin: String = ""
    private var loginUsername: String = ""
    private var loginPassword: String = ""
    /** 登录成功后获取的 JWT token，用于注入 HTML */
    private var authToken: String? = null
    /** 标记是否正在恢复状态（横竖屏切换），避免重复触发加载 */
    private var isRestoringState = false

    // ===== 三击退出相关 =====
    /** 记录连续返回键按下的时间戳 */
    private var lastBackPressTime = 0L
    /** 连续按下的次数 */
    private var backPressCount = 0

    // ===== 语音输入权限相关 =====
    private val PERMISSION_REQUEST_RECORD_AUDIO = 2001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 从 Splash 主题切回正常主题（消除启动背景）
        setTheme(R.style.Theme_HermesStudioMobile)
        super.onCreate(savedInstanceState)

        // 从 Intent 获取服务器 URL 和登录凭据
        serverUrl = intent.getStringExtra("server_url") ?: ""
        loginUsername = intent.getStringExtra("server_username") ?: ""
        loginPassword = intent.getStringExtra("server_password") ?: ""

        // 如果 Intent 没有 URL，检查已保存的活动服务器
        if (serverUrl.isEmpty()) {
            val serverManager = ServerManager(this)
            serverUrl = serverManager.getActiveServerUrl() ?: ""
            val activeServer = serverManager.getActiveServer()
            if (activeServer != null) {
                if (loginUsername.isEmpty()) loginUsername = activeServer.username
                if (loginPassword.isEmpty()) loginPassword = activeServer.password
            }
        }

        if (serverUrl.isEmpty()) {
            startActivity(Intent(this, ConfigActivity::class.java))
            finish()
            return
        }

        serverOrigin = extractOrigin(serverUrl)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        // 如果有保存的 WebView 状态，优先恢复（横竖屏切换后）
        if (savedInstanceState != null && savedInstanceState.getBoolean("webview_has_state", false)) {
            if (loginUsername.isNotBlank() && loginPassword.isNotBlank()) {
                authToken = savedInstanceState.getString("auth_token")
                if (authToken != null) {
                    Log.d(TAG, "Restoring WebView state with auth token")
                    isRestoringState = true
                    configureWebView()
                    webView.restoreState(savedInstanceState)
                    return
                }
            }
        }

        // 如果在凭据，先登录获取 token，再配置 WebView 并加载页面
        if (loginUsername.isNotBlank() && loginPassword.isNotBlank()) {
            performLoginFirst()
        } else {
            configureWebView()
            loadFrontend()
        }
    }

    /**
     * 先登录获取 token，再配置 WebView 并加载页面
     * 确保 token 在 SPA 初始化前就绪
     */
    private fun performLoginFirst() {
        val loginUrl = "${serverOrigin}/api/auth/login"
        val jsonBody = JSONObject().apply {
            put("username", loginUsername)
            put("password", loginPassword)
        }

        Thread {
            try {
                Log.d(TAG, "Login first: POST $loginUrl")
                val url = URL(loginUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(jsonBody.toString())
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val json = JSONObject(response)
                    val token = json.optString("token", "")
                    if (token.isNotBlank()) {
                        authToken = token
                        Log.d(TAG, "Login success, token=${token.take(20)}...")
                    }
                } else {
                    val errorReader = BufferedReader(InputStreamReader(conn.errorStream))
                    val errorResponse = errorReader.readText()
                    errorReader.close()
                    Log.w(TAG, "Login failed, HTTP $responseCode: $errorResponse")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Login error: ${e.message}", e)
            }

            // 无论登录成功与否，都加载页面（失败则手动登录）
            Handler(Looper.getMainLooper()).post {
                if (authToken == null) {
                    Log.w(TAG, "No auth token, will load without auto-login")
                }
                configureWebView()
                loadFrontend()
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        // ===== 启动缓存加速（Stale-While-Revalidate） =====
        // 非首次启动：先取缓存数据秒开，等页面加载完成后再从网络更新
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstLaunch = !prefs.getBoolean(KEY_FIRST_LAUNCH, false)
        val startupCacheMode = if (!isRestoringState && !isFirstLaunch) {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        } else {
            WebSettings.LOAD_DEFAULT
        }
        Log.d(TAG, "Startup mode: ${if (isFirstLaunch) "first-launch" else "cached-revalidate"}")

        val settings = webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = startupCacheMode
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            defaultTextEncodingName = "UTF-8"
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            setNeedInitialFocus(true)

            // 启用保存表单数据，部分 SPA 的表单填充也受益
            saveFormData = true

            // ===== 语音输入支持配置 =====
            // 允许 WebView 获取用户媒体（麦克风/摄像头）
            // 【为什么需要】前端语音输入功能使用 WebRTC getUserMedia API，
            //              需要 WebView 允许媒体访问。
            mediaPlaybackRequiresUserGesture = false
        }

        // 配置 ServiceWorker 支持
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                val swController = android.webkit.ServiceWorkerController.getInstance()
                val swSettings = swController.serviceWorkerWebSettings
                swSettings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                Log.d(TAG, "ServiceWorkerController configured")
            } catch (e: Exception) {
                Log.w(TAG, "ServiceWorkerController not available: ${e.message}")
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val uri = request.url
                if (!url.startsWith(serverOrigin)) return null
                val path = uri.path ?: return null
                if (API_PATH_PREFIXES.any { path.startsWith(it) }) return null
                return tryServeLocalAsset(path)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // ===== 修复朗读功能：注入 hermes_server_url 到 localStorage =====
                // 【为什么需要】前端 TTS API（useSpeech.ts）调用时，
                //              通过 localStorage.getItem('hermes_server_url') 获取服务器地址，
                //              拼接出 TTS API 路径（/api/hermes/tts）。
                //              如果不注入此值，TTS 请求会发到空地址，导致朗读功能失败。
                // 【注入内容】服务器源地址（origin），如 http://192.168.1.100:8648
                val escapedOrigin = serverOrigin.replace("'", "\\'")
                injectJs(
                    "localStorage.setItem('hermes_server_url','$escapedOrigin');" +
                    "console.log('[HermesMobile] Injected hermes_server_url:', '$escapedOrigin');"
                )

                // 注入移动端标记
                injectJs("localStorage.setItem('hermes_is_mobile','true')")

                // 非首次启动 + 缓存模式加载完成后，切回 LOAD_DEFAULT
                // 确保后续 API 请求走网络获取最新数据
                if (view != null && view.settings.cacheMode == WebSettings.LOAD_CACHE_ELSE_NETWORK) {
                    Log.d(TAG, "Cache-first page loaded, switching to LOAD_DEFAULT")
                    view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    view.evaluateJavascript("console.log('[HermesMobile] Cache mode restored to LOAD_DEFAULT')", null)
                }

                // 首次加载完成，标记为非首次启动
                if (!isRestoringState) {
                    prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
                }

                // 注入横屏适配脚本
                injectOrientationHandler()
            }
        }

        // ===== 自定义 WebChromeClient：处理语音输入权限请求 =====
        // 【为什么需要】前端语音输入功能使用 WebRTC 的 getUserMedia API，
        //              WebChromeClient.onPermissionRequest 是 WebView 处理媒体权限的标准方式。
        //              不处理此回调，WebView 默认拒绝麦克风访问，语音输入功能不可用。
        webView.webChromeClient = object : WebChromeClient() {

            // 委托给原始 HermesChromeClient 处理 JS 弹窗和日志
            private val delegate = HermesChromeClient()

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                delegate.onProgressChanged(view, newProgress)
            }

            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?
            ): Boolean = delegate.onJsAlert(view, url, message, result)

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?
            ): Boolean = delegate.onJsConfirm(view, url, message, result)

            override fun onReceivedTitle(view: WebView?, title: String?) {
                delegate.onReceivedTitle(view, title)
            }

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                return delegate.onConsoleMessage(consoleMessage)
            }

            /**
             * 处理 WebRTC 权限请求（麦克风、摄像头）
             * 【为什么需要】WebView 内前端调用 navigator.mediaDevices.getUserMedia({ audio: true })
             *              时，会通过此回调请求权限。不处理则静默拒绝，语音输入功能不可用。
             * 【处理方式】直接授予 RESOURCE_AUDIO_CAPTURE 权限（麦克风）
             */
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val requestedResources = request.resources

                // 检查是否请求了音频捕获权限
                val hasAudio = requestedResources.any {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }

                if (hasAudio) {
                    Log.d(TAG, "WebRTC permission request: audio capture")
                    // 授予音频捕获权限
                    request.grant(requestedResources)
                } else {
                    // 其他权限按默认处理
                    request.deny()
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun loadFrontend() {
        webView.loadUrl(serverUrl)
    }

    private fun tryServeLocalAsset(path: String): WebResourceResponse? {
        val assetPath = when {
            path == "/" || path.isEmpty() -> "$ASSETS_FRONTEND_PATH/index.html"
            path.startsWith("/") -> "$ASSETS_FRONTEND_PATH${path}"
            else -> "$ASSETS_FRONTEND_PATH/$path"
        }
        val extension = assetPath.substringAfterLast('.', "").lowercase()
        if (extension !in STATIC_FILE_EXTENSIONS) return null

        val inputStream: InputStream = try {
            assets.open(assetPath)
        } catch (e: Exception) {
            return null
        }

        val mimeType = getMimeType(extension)
        val encoding = if (extension == "html" || extension == "js" || extension == "css") "UTF-8" else null

        // 在 </head> 前注入 localStorage 设置脚本
        // 包括 auth token（自动登录）和 hermes_server_url（TTS/API 通信）
        // 【为什么需要】TTS 朗读功能（useSpeech.ts）通过 localStorage.getItem('hermes_server_url')
        //              获取服务器地址拼接 API 路径，不注入则朗读功能不可用。
        //              语音输入功能的 STT API 同样依赖此值。
        if (extension == "html") {
            try {
                val html = inputStream.bufferedReader(Charsets.UTF_8).readText()
                inputStream.close()
                val sb = StringBuilder()
                sb.append("<script>try{")
                // 注入 hermes_server_url（TTS 和 API 通信必需）
                val escapedOrigin = serverOrigin.replace("'", "\\'").replace("\"", "\\\"")
                sb.append("localStorage.setItem('hermes_server_url','$escapedOrigin');")
                // 注入 auth token（自动登录跳过登录页）
                if (authToken != null) {
                    val escapedToken = authToken!!.replace("'", "\\'").replace("\"", "\\\"")
                    sb.append("localStorage.setItem('hermes_api_key','$escapedToken');")
                }
                sb.append("console.log('[HermesMobile] Server URL and tokens injected before SPA init')")
                sb.append("}catch(e){console.error('[HermesMobile] HTML inject failed:',e)}</script>")
                val injectScript = sb.toString()
                val modifiedHtml = html.replace("</head>", "$injectScript</head>")
                Log.d(TAG, "Injected server URL and tokens into HTML (${html.length} -> ${modifiedHtml.length} bytes)")
                return WebResourceResponse(mimeType, encoding, ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject into HTML: ${e.message}")
                // 回退到原 HTML
                return WebResourceResponse(mimeType, encoding, inputStream)
            }
        }

        return WebResourceResponse(mimeType, encoding, inputStream)
    }

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

    private fun extractOrigin(url: String): String {
        val normalized = url.trimEnd('/')
        val schemeEnd = normalized.indexOf("://")
        if (schemeEnd < 0) return normalized
        val pathStart = normalized.indexOf('/', schemeEnd + 3)
        return if (pathStart >= 0) normalized.substring(0, pathStart) else normalized
    }

    private fun injectJs(js: String) {
        webView.evaluateJavascript("""
            (function() {
                try {
                    $js
                } catch(e) {
                    console.error('[HermesMobile] JS inject failed:', e);
                }
            })();
        """.trimIndent(), null)
    }

    /**
     * 注入横竖屏适配 JS
     * 在每次方向变化时通知 SPA 调整布局和 viewport
     */
    private fun injectOrientationHandler() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val js = """
            localStorage.setItem('hermes_is_mobile', 'true');
            localStorage.setItem('hermes_orientation', '${if (isLandscape) "landscape" else "portrait"}');
            // 触发 orientationchange 事件让 SPA 重新布局
            window.dispatchEvent(new Event('orientationchange'));
            // 调整 viewport 缩放
            var meta = document.querySelector('meta[name="viewport"]');
            if (meta) {
                meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
            }
            console.log('[HermesMobile] Orientation: ${if (isLandscape) "landscape" else "portrait"}');
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 保存 WebView 状态和 auth token，用于横竖屏切换后恢复
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.saveState(outState)
            outState.putBoolean("webview_has_state", true)
        }
        // 保存 auth token 以防需要重新注入
        if (authToken != null) {
            outState.putString("auth_token", authToken)
        }
    }

    /**
     * 配置变化时不重建 Activity，手动处理布局变化
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}")
        // 注入方向变化事件，通知 SPA 重新布局
        if (::webView.isInitialized) {
            injectOrientationHandler()
        }
    }

    /**
     * 双击返回键：退出登录回到服务器设置界面
     *
     * 【实现逻辑】
     * 1. 如果 WebView 可以后退，优先 WebView 后退
     * 2. 如果 WebView 不能后退（已是最顶层页面）：
     *    a. 记录按下时间戳，1.5 秒内连续按才累加计数
     *    b. 连续按 2 次 → 弹出退出登录确认对话框
     *    c. 确认后回到 ConfigActivity（服务器设置界面）
     *    d. 超时后重置计数
     */
    override fun onBackPressed() {
        // 优先 WebView 后退
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }

        val currentTime = System.currentTimeMillis()

        // 如果距离上次按下超过超时时间，重置计数
        if (currentTime - lastBackPressTime > BACK_PRESS_TIMEOUT_MS) {
            backPressCount = 0
        }

        lastBackPressTime = currentTime
        backPressCount++

        if (backPressCount >= BACK_PRESS_THRESHOLD) {
            // 达到 2 次，弹出退出登录确认框
            backPressCount = 0 // 重置计数
            showLogoutDialog()
        } else {
            // 提示继续按可退出登录
            Toast.makeText(
                this,
                "再按一次返回键可退出登录",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 显示退出登录确认对话框
     * 确认后：清除 WebView 状态，跳转到 ConfigActivity（服务器设置界面）
     */
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("确定要退出当前会话，回到服务器设置界面吗？")
            .setPositiveButton("确定退出") { _, _ ->
                performLogout()
            }
            .setNegativeButton("取消", null)
            .setCancelable(true)
            .show()
    }

    /**
     * 执行退出登录操作
     * 1. 清除 WebView 缓存和数据
     * 2. 跳转到 ConfigActivity（服务器设置界面）
     * 3. 关闭当前 Activity
     */
    private fun performLogout() {
        Log.d(TAG, "Performing logout, returning to ConfigActivity")

        // 清除 WebView 数据（cookie、缓存、DOM 存储）
        if (::webView.isInitialized) {
            webView.clearCache(true)
            webView.clearHistory()
            // 清除 DOM 存储（localStorage），确保退出后 token 被清除
            webView.evaluateJavascript(
                "try{localStorage.clear();console.log('[HermesMobile] localStorage cleared on logout')}catch(e){}",
                null
            )
        }

        // 跳转到服务器设置界面
        val intent = Intent(this, ConfigActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    /**
     * 请求录音权限
     * Android 6.0+ 需要运行时动态请求 RECORD_AUDIO 权限
     * 【为什么需要】前端语音输入功能需要麦克风访问权限，
     *              不请求权限则 WebRTC getUserMedia 调用失败。
     */
    private fun requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    PERMISSION_REQUEST_RECORD_AUDIO
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()

        // 每次恢复时请求录音权限（如果尚未授予）
        // 这样即使用户第一次拒绝，下次打开还有机会授权
        requestRecordAudioPermission()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}