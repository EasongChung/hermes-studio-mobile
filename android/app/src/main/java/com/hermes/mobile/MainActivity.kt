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
import com.hermes.mobile.cache.ApiCacheManager
import com.hermes.mobile.cache.CacheKeyBuilder
import com.hermes.mobile.cache.CacheableApiMatcher
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
        // ===== Sprint 8 API 缓存调试开关 =====
        // 【ENABLE_API_PROBE_LOG】只控制请求观测日志，不改变网络行为。
        // 【ENABLE_API_RESPONSE_CACHE】控制是否启用透明 API 缓存返回；如出现异常可改为 false 快速回退原网络流程。
        private const val ENABLE_API_PROBE_LOG = true
        private const val ENABLE_API_RESPONSE_CACHE = true
        // Socket.IO polling 写入可能对应聊天发送事件；加冷却避免长轮询/心跳导致缓存频繁清理。
        private const val SOCKET_MUTATION_INVALIDATE_COOLDOWN_MS = 3000L

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
    private lateinit var apiCacheManager: ApiCacheManager
    private var serverUrl: String = ""
    private var serverOrigin: String = ""
    private var loginUsername: String = ""
    private var loginPassword: String = ""
    /** 登录成功后获取的 JWT token，用于注入 HTML */
    private var authToken: String? = null
    /** 标记是否正在恢复状态（横竖屏切换），避免重复触发加载 */
    private var isRestoringState = false
    /** 最近一次因 Socket.IO 写入触发缓存失效的时间，用于节流避免频繁清理。 */
    private var lastSocketMutationInvalidateAtMs = 0L

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
        apiCacheManager = ApiCacheManager(this)

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

                // Sprint 8 API 观测：只记录方法、路径、query 和缓存候选状态，不读取响应体、不打印 token。
                // 【为什么需要】透明缓存必须先确认真实会话 API 路径，避免误缓存登录、写操作、文件、WebSocket 等接口。
                val method = request.method ?: "GET"
                logApiProbe(method, path, uri.encodedQuery)

                // Sprint 8 缓存失效：会话/消息相关写操作会影响列表排序、最后消息或更新时间，
                // 因此在请求发出前先清理当前用户缓存，避免后续继续显示旧会话列表。
                invalidateApiCacheIfNeeded(method, path, uri.encodedQuery)

                // Sprint 8 透明 API 缓存：仅对匹配器确认的会话类 GET 候选生效。
                // 命中本地缓存时立即返回缓存，同时后台刷新远程数据；未命中则继续原网络流程。
                tryServeApiCache(method, path, uri.encodedQuery, request.requestHeaders)?.let { return it }

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

    /**
     * Sprint 8：尝试返回会话类 API 本地缓存，并后台刷新远程数据。
     *
     * 【为什么保守处理用户身份】
     * 如果用户不是通过原生自动登录进入，Android 层可能不知道 Web 前端当前登录的是哪个账号。
     * 为避免不同用户共享同一 server 下的会话缓存，这里在无法识别 userIdentity 时直接放行网络请求。
     */
    private fun tryServeApiCache(
        method: String,
        path: String,
        query: String?,
        requestHeaders: Map<String, String>
    ): WebResourceResponse? {
        if (!ENABLE_API_RESPONSE_CACHE) return null
        if (!CacheableApiMatcher.isCacheCandidate(method, path)) return null
        if (!::apiCacheManager.isInitialized) return null

        val userIdentity = resolveCacheUserIdentity(requestHeaders)
        if (userIdentity.isBlank()) {
            Log.d(TAG, "[HermesCache] skip cache: unknown user identity path=$path")
            return null
        }

        val pathAndQuery = if (query.isNullOrBlank()) path else "$path?$query"
        val cacheKey = CacheKeyBuilder.build(serverOrigin, userIdentity, method, path, query)
        val cached = apiCacheManager.read(cacheKey)

        if (cached != null) {
            val (entry, body) = cached
            Log.d(TAG, "[HermesCache] hit path=$pathAndQuery fresh=${entry.isFresh()} size=${body.size}")
            refreshApiCacheInBackground(method, path, query, cacheKey, userIdentity, requestHeaders)
            val mimeType = entry.contentType.substringBefore(';').ifBlank { "application/json" }
            val encoding = entry.contentType.substringAfter("charset=", "UTF-8")
            return WebResourceResponse(mimeType, encoding, ByteArrayInputStream(body))
        }

        // 无缓存时不阻塞 WebView 原始请求，只后台预热一次，成功后下次启动/请求即可命中。
        Log.d(TAG, "[HermesCache] miss path=$pathAndQuery, warmup in background")
        refreshApiCacheInBackground(method, path, query, cacheKey, userIdentity, requestHeaders)
        return null
    }

    /**
     * 后台刷新 API 缓存。
     *
     * 【安全边界】
     * 只对 GET 候选接口执行；失败只记录日志，不影响当前 WebView 页面。
     */
    private fun refreshApiCacheInBackground(
        method: String,
        path: String,
        query: String?,
        cacheKey: String,
        userIdentity: String,
        requestHeaders: Map<String, String>
    ) {
        if (!method.equals("GET", ignoreCase = true)) return

        Thread {
            try {
                val pathAndQuery = if (query.isNullOrBlank()) path else "$path?$query"
                val requestUrl = "$serverOrigin$pathAndQuery"
                val conn = URL(requestUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("Accept", "application/json")
                applyForwardHeaders(conn, requestHeaders)

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    Log.d(TAG, "[HermesCache] refresh skip path=$pathAndQuery http=$responseCode")
                    conn.disconnect()
                    return@Thread
                }

                val body = conn.inputStream.readBytes()
                val contentType = conn.contentType ?: "application/json; charset=utf-8"
                conn.disconnect()

                val serverHash = CacheKeyBuilder.hashText(serverOrigin)
                val userHash = CacheKeyBuilder.hashText(userIdentity)
                val saved = apiCacheManager.write(
                    cacheKey = cacheKey,
                    serverHash = serverHash,
                    userHash = userHash,
                    method = method,
                    pathAndQuery = pathAndQuery,
                    contentType = contentType,
                    body = body
                )
                Log.d(TAG, "[HermesCache] refresh saved=$saved path=$pathAndQuery size=${body.size}")
            } catch (e: Exception) {
                Log.w(TAG, "[HermesCache] refresh failed path=$path: ${e.message}")
            }
        }.start()
    }

    /**
     * 解析用于缓存隔离的用户身份。
     *
     * 【为什么要读取请求头】
     * 自动登录场景下 Android 层有 loginUsername/authToken；但手动登录后，认证信息可能只存在于 WebView 的请求头中。
     * 这里优先使用原生已知身份，无法获取时再使用 Authorization/Cookie/API Key 的摘要，避免手动登录场景完全无法缓存。
     *
     * 【安全要求】
     * 返回值只作为后续 SHA-256 输入，不直接写文件名；也不打印任何原始认证头。
     */
    private fun resolveCacheUserIdentity(requestHeaders: Map<String, String>): String {
        loginUsername.takeIf { it.isNotBlank() }?.let { return "user:$it" }
        authToken?.takeIf { it.isNotBlank() }?.let { return "token:${it.take(32)}" }

        val authHeader = getHeaderIgnoreCase(requestHeaders, "Authorization")
        if (!authHeader.isNullOrBlank()) return "auth:${CacheKeyBuilder.hashText(authHeader)}"

        val cookieHeader = getHeaderIgnoreCase(requestHeaders, "Cookie")
        if (!cookieHeader.isNullOrBlank()) return "cookie:${CacheKeyBuilder.hashText(cookieHeader)}"

        val apiKeyHeader = getHeaderIgnoreCase(requestHeaders, "X-API-Key")
            ?: getHeaderIgnoreCase(requestHeaders, "x-api-key")
        if (!apiKeyHeader.isNullOrBlank()) return "apiKey:${CacheKeyBuilder.hashText(apiKeyHeader)}"

        return ""
    }

    /**
     * 向后台刷新请求透传必要请求头。
     *
     * 【为什么需要】
     * 前端真实请求可能使用 Cookie、Authorization 或 X-API-Key 鉴权。
     * 如果后台刷新不透传这些头，Server 可能返回 401/403，导致缓存无法写入。
     *
     * 【安全边界】
     * 只透传白名单头，不打印头内容，不透传任意自定义头，避免扩大安全面。
     */
    private fun applyForwardHeaders(conn: HttpURLConnection, requestHeaders: Map<String, String>) {
        val allowedHeaders = setOf(
            "Authorization",
            "Cookie",
            "X-API-Key",
            "x-api-key",
            "Accept-Language",
            "User-Agent"
        )

        allowedHeaders.forEach { headerName ->
            val value = getHeaderIgnoreCase(requestHeaders, headerName)
            if (!value.isNullOrBlank()) {
                conn.setRequestProperty(headerName, value)
            }
        }

        // 自动登录场景兜底：如果前端请求头里没有 Authorization，但原生层有 token，则补 Bearer。
        if (getHeaderIgnoreCase(requestHeaders, "Authorization").isNullOrBlank()) {
            authToken?.takeIf { it.isNotBlank() }?.let { token ->
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
        }
    }

    /**
     * 忽略大小写读取请求头。
     */
    private fun getHeaderIgnoreCase(headers: Map<String, String>, name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
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

    /**
     * Sprint 8：记录 WebView 发起的 API 请求观测日志。
     *
     * 【为什么需要】
     * 会话本地缓存不能盲目拦截所有 /api/ 请求。先通过轻量日志确认启动阶段和会话页实际请求路径，
     * 再把确认安全的会话列表类 GET 接口加入缓存白名单。
     *
     * 【安全要求】
     * 这里只打印 method、path、query 是否存在、候选状态，不打印请求头、token、密码、响应体或会话正文。
     */
    private fun logApiProbe(method: String, path: String, query: String?) {
        if (!ENABLE_API_PROBE_LOG) return

        val isApiPath = CacheableApiMatcher.isApiPath(path)
        if (!isApiPath) return

        val isCacheCandidate = CacheableApiMatcher.isCacheCandidate(method, path)
        val queryState = if (query.isNullOrBlank()) "no-query" else "has-query"

        Log.d(
            TAG,
            "[HermesCacheProbe] method=$method path=$path query=$queryState cacheCandidate=$isCacheCandidate"
        )
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

        // 清理原生 API 响应缓存。
        // 【为什么需要】Sprint 8 新增的会话 API 缓存写在 App 私有 files/api_cache，
        //              不属于 WebView localStorage/cache，退出登录时必须单独清理，避免敏感会话记录残留。
        clearCurrentUserApiCache()

        // 跳转到服务器设置界面
        val intent = Intent(this, ConfigActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    /**
     * 会话相关写操作发生时清理当前用户 API 缓存。
     *
     * 【为什么需要】
     * 发送消息、创建/删除会话会改变会话列表的最后消息、更新时间或排序。
     * 如果不清理缓存，后续启动可能继续显示旧列表。
     */
    private fun invalidateApiCacheIfNeeded(method: String, path: String, query: String?) {
        if (!ENABLE_API_RESPONSE_CACHE) return

        if (CacheableApiMatcher.isConversationMutation(method, path)) {
            Log.d(TAG, "[HermesCache] invalidate by mutation method=$method path=$path")
            clearCurrentUserApiCache()
            return
        }

        // Socket.IO 是 Web 端聊天发送的主通道。这里仅对 POST /socket.io/?transport=polling 做保守失效，
        // 并设置短冷却窗口，避免 polling 心跳或连续事件导致频繁清理缓存。
        if (CacheableApiMatcher.isSocketIoPollingMutation(method, path, query)) {
            val now = System.currentTimeMillis()
            if (now - lastSocketMutationInvalidateAtMs < SOCKET_MUTATION_INVALIDATE_COOLDOWN_MS) {
                Log.d(TAG, "[HermesCache] skip socket invalidate by cooldown path=$path")
                return
            }
            lastSocketMutationInvalidateAtMs = now
            Log.d(TAG, "[HermesCache] invalidate by socket mutation path=$path")
            clearCurrentUserApiCache()
        }
    }

    /**
     * 清理当前 server + user 维度的原生 API 缓存。
     *
     * 【为什么需要】
     * API 缓存不属于 WebView 数据，退出登录时如果不清理，会在 App 私有目录中残留历史会话响应。
     * 为避免误删其他账号/服务器缓存，这里只在能识别当前用户身份时清理对应维度。
     */
    private fun clearCurrentUserApiCache() {
        if (!::apiCacheManager.isInitialized) return

        val serverHash = CacheKeyBuilder.hashText(serverOrigin)
        val userIdentity = when {
            loginUsername.isNotBlank() -> "user:$loginUsername"
            !authToken.isNullOrBlank() -> "token:${authToken!!.take(32)}"
            else -> ""
        }
        if (userIdentity.isBlank()) {
            // 手动登录场景可能无法还原具体 userIdentity。为了隐私优先，退化为清理当前服务器全部缓存。
            apiCacheManager.clearForServer(serverHash)
            Log.d(TAG, "[HermesCache] cleared current server cache because user identity is unknown")
            return
        }

        val userHash = CacheKeyBuilder.hashText(userIdentity)
        apiCacheManager.clearFor(serverHash, userHash)
        Log.d(TAG, "[HermesCache] cleared current user cache")
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