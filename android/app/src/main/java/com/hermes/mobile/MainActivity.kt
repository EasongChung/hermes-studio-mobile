package com.hermes.mobile

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.appcompat.app.AppCompatActivity
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
import java.util.Locale

/**
 * MainActivity - Hermes Studio Mobile 主界面
 *
 * v0.2.0 新增功能：
 * - 上拉刷新：SwipeRefreshLayout 包裹 WebView
 * - 网络错误提示：红色横幅 + 重试按钮
 * - TTS 朗读：Android 系统 TextToSpeech 朗读助手回复
 * - 语音输入：系统 RecognizerIntent 麦克风转文字
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesWeb"
        private const val ASSETS_FRONTEND_PATH = "hermes"
        private const val PREFS_NAME = "hermes_startup"
        private const val KEY_FIRST_LAUNCH = "first_launch_completed"
        private const val REQUEST_CODE_SPEECH_INPUT = 1001
        private val STATIC_FILE_EXTENSIONS = setOf(
            "html", "js", "css", "json", "svg", "png", "jpg", "jpeg",
            "gif", "ico", "woff", "woff2", "ttf", "eot", "otf", "webp"
        )
        private val API_PATH_PREFIXES = setOf(
            "/api/", "/v1/", "/health", "/upload", "/webhook", "/socket.io/"
        )
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var errorBanner: LinearLayout
    private lateinit var retryBtn: Button
    private lateinit var voiceInputBtn: ImageButton
    private lateinit var ttsStopBtn: ImageButton
    private lateinit var ttsManager: TtsManager

    private var serverUrl: String = ""
    private var serverOrigin: String = ""
    private var loginUsername: String = ""
    private var loginPassword: String = ""
    private var authToken: String? = null
    private var isRestoringState = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_HermesStudioMobile)
        super.onCreate(savedInstanceState)

        // 从 Intent 获取服务器 URL 和登录凭据
        serverUrl = intent.getStringExtra("server_url") ?: ""
        loginUsername = intent.getStringExtra("server_username") ?: ""
        loginPassword = intent.getStringExtra("server_password") ?: ""

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
        swipeRefresh = findViewById(R.id.swipeRefresh)
        errorBanner = findViewById(R.id.errorBanner)
        retryBtn = findViewById(R.id.retryBtn)
        voiceInputBtn = findViewById(R.id.voiceInputBtn)
        ttsStopBtn = findViewById(R.id.ttsStopBtn)

        // 初始化 TTS
        ttsManager = TtsManager(this)
        ttsManager.init()

        // ===== 上拉刷新配置 =====
        swipeRefresh.setColorSchemeResources(
            android.R.color.holo_blue_light,
            android.R.color.holo_purple,
            android.R.color.holo_red_light
        )
        swipeRefresh.setOnRefreshListener {
            Log.d(TAG, "Pull-up-to-refresh triggered")
            hideErrorBanner()
            webView.reload()
            // 超时保护：如果5秒后刷新的旋转圈没消失，强制取消
            Handler(Looper.getMainLooper()).postDelayed({
                if (swipeRefresh.isRefreshing) {
                    swipeRefresh.isRefreshing = false
                }
            }, 5000)
        }

        // ===== 重试按钮 =====
        retryBtn.setOnClickListener {
            hideErrorBanner()
            webView.reload()
        }

        // ===== 语音输入按钮 =====
        voiceInputBtn.setOnClickListener {
            startVoiceInput()
        }

        // ===== TTS 停止按钮 =====
        ttsStopBtn.setOnClickListener {
            ttsManager.stop()
            Toast.makeText(this, "朗读已停止", Toast.LENGTH_SHORT).show()
        }

        // 恢复状态或加载新页面
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

        if (loginUsername.isNotBlank() && loginPassword.isNotBlank()) {
            performLoginFirst()
        } else {
            configureWebView()
            loadFrontend()
        }
    }

    /**
     * 启动系统语音识别
     */
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你想对 Hermes 说的话")
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "设备不支持语音识别", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 语音识别结果回调
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]
                Log.d(TAG, "Voice input: $spokenText")
                // 将语音识别结果填充到输入框
                val escapedText = spokenText.replace("'", "\\'").replace("\n", "\\n")
                injectJs("""
                    var input = document.querySelector('textarea, [contenteditable="true"], .input-area, .message-input');
                    if (input) {
                        input.value = '$escapedText';
                        input.dispatchEvent(new Event('input', {bubbles: true}));
                        input.focus();
                    }
                """.trimIndent())
                Toast.makeText(this, "语音已识别：$spokenText", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
            saveFormData = true
        }

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
                // 隐藏刷新动画
                swipeRefresh.isRefreshing = false
                // 注入移动端标记
                injectJs("localStorage.setItem('hermes_is_mobile','true')")

                if (view != null && view.settings.cacheMode == WebSettings.LOAD_CACHE_ELSE_NETWORK) {
                    Log.d(TAG, "Cache-first page loaded, switching to LOAD_DEFAULT")
                    view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                }

                if (!isRestoringState) {
                    prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
                }

                injectOrientationHandler()
                // 注入 TTS 和语音控制接口
                injectNativeInterface()
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "WebView error: code=$errorCode, desc=$description, url=$failingUrl")
                // 只在主页面加载错误时显示横幅（非子资源加载）
                if (failingUrl == serverUrl || failingUrl == null || errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT) {
                    showErrorBanner(description ?: "网络连接失败")
                }
            }
        }
        webView.webChromeClient = HermesChromeClient()
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

        if (extension == "html" && authToken != null) {
            try {
                val html = inputStream.bufferedReader(Charsets.UTF_8).readText()
                inputStream.close()
                val escapedToken = authToken!!.replace("'", "\\'")
                val injectScript = "<script>try{localStorage.setItem('hermes_api_key','$escapedToken');console.log('[HermesMobile] Token injected into HTML before SPA init')}catch(e){console.error('[HermesMobile] HTML inject failed:',e)}</script>"
                val modifiedHtml = html.replace("</head>", "$injectScript</head>")
                Log.d(TAG, "Injected auth token into HTML response (${html.length} -> ${modifiedHtml.length} bytes)")
                return WebResourceResponse(mimeType, encoding, ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject token into HTML: ${e.message}")
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
                try { $js } catch(e) { console.error('[HermesMobile] JS inject failed:', e); }
            })();
        """.trimIndent(), null)
    }

    private fun injectOrientationHandler() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val js = """
            localStorage.setItem('hermes_is_mobile', 'true');
            localStorage.setItem('hermes_orientation', '${if (isLandscape) "landscape" else "portrait"}');
            window.dispatchEvent(new Event('orientationchange'));
            var meta = document.querySelector('meta[name="viewport"]');
            if (meta) {
                meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
            }
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 注入 TTS 和语音控制的 JS 接口
     * 让 SPA 可以通过 JS 调用 TTS 朗读消息
     */
    private fun injectNativeInterface() {
        val js = """
            // 注入 HermesMobile 原生 API
            if (!window.HermesMobile) {
                window.HermesMobile = {};
                // TTS 朗读文本
                window.HermesMobile.speak = function(text) {
                    Android.speak(text);
                };
                // 停止朗读
                window.HermesMobile.stopSpeaking = function() {
                    Android.stopSpeaking();
                };
                // 启动语音输入
                window.HermesMobile.startVoiceInput = function() {
                    Android.startVoiceInput();
                };
                console.log('[HermesMobile] Native interface injected');
            }
        """.trimIndent()
        // 通过 JSInterface 暴露给 WebView
        webView.addJavascriptInterface(HermesJsInterface(), "Android")
        webView.evaluateJavascript(js, null)
    }

    /**
     * JS 接口类：暴露给 JavaScript 调用
     * 需要在 @JavascriptInterface 方法上标注
     */
    @Suppress("unused")
    inner class HermesJsInterface {
        @android.webkit.JavascriptInterface
        fun speak(text: String) {
            Log.d(TAG, "JS called speak: ${text.take(50)}...")
            runOnUiThread {
                ttsManager.speak(text)
            }
        }

        @android.webkit.JavascriptInterface
        fun stopSpeaking() {
            Log.d(TAG, "JS called stopSpeaking")
            runOnUiThread {
                ttsManager.stop()
            }
        }
    }

    // ===== 网络错误提示 =====

    private fun showErrorBanner(message: String) {
        errorBanner.visibility = View.VISIBLE
        val errorText = findViewById<TextView>(R.id.errorText)
        errorText?.text = message
        Log.d(TAG, "Error banner shown: $message")
    }

    private fun hideErrorBanner() {
        errorBanner.visibility = View.GONE
    }

    // ===== 生命周期 =====

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.saveState(outState)
            outState.putBoolean("webview_has_state", true)
        }
        if (authToken != null) {
            outState.putString("auth_token", authToken)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}")
        if (::webView.isInitialized) {
            injectOrientationHandler()
        }
    }

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
        if (::ttsManager.isInitialized) ttsManager.destroy()
        super.onDestroy()
    }
}