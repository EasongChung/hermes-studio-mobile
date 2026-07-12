package com.hermes.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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

/**
 * MainActivity - Hermes Studio Mobile 主界面
 *
 * 设计原则：混合加载 + 自动登录 token 注入
 *
 * 如果有用户名/密码，先调用登录 API 获取 JWT token，
 * 然后在 shouldInterceptRequest 中拦截 HTML 响应，
 * 在 </head> 前注入 script 设置 localStorage，
 * 确保 SPA 初始化前 token 已就绪，自动跳过登录页。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesWeb"
        private const val ASSETS_FRONTEND_PATH = "hermes"
        private const val PREFS_NAME = "hermes_startup"
        private const val KEY_FIRST_LAUNCH = "first_launch_completed"
        private val STATIC_FILE_EXTENSIONS = setOf(
            "html", "js", "css", "json", "svg", "png", "jpg", "jpeg",
            "gif", "ico", "woff", "woff2", "ttf", "eot", "otf", "webp"
        )
        private val API_PATH_PREFIXES = setOf(
            "/api/", "/v1/", "/health", "/upload", "/webhook", "/socket.io/"
        )
    }

    private lateinit var webView: WebView
    private lateinit var ttsManager: TtsManager
    private var serverUrl: String = ""
    private var serverOrigin: String = ""
    private var loginUsername: String = ""
    private var loginPassword: String = ""
    /** 登录成功后获取的 JWT token，用于注入 HTML */
    private var authToken: String? = null
    /** 标记是否正在恢复状态（横竖屏切换），避免重复触发加载 */
    private var isRestoringState = false

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

        // 初始化 TTS 引擎
        ttsManager = TtsManager(this)
        ttsManager.init()

        // 注册 JS 接口（暴露给前端调用 Android 原生 TTS）
        webView.addJavascriptInterface(HermesJsInterface(), "HermesAndroid")

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

                // 注入 TTS polyfill，覆盖 window.speechSynthesis
                // Android WebView 不支持 Web Speech API，需要 polyfill 将朗读请求路由到原生 TTS
                injectTtsPolyfill()
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

        // 如果是 HTML 且有 token，在 </head> 前注入 localStorage 设置脚本
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

    // =====================================================================
    // injectTtsPolyfill — 注入 Web Speech API polyfill
    //
    // Hermes 前端使用 window.speechSynthesis（Web Speech API）朗读 AI 回复，
    // 但 Android WebView 不支持此 API。本方法注入 polyfill 覆盖
    // window.speechSynthesis，将 speak() / cancel() 等调用路由到
    // HermesAndroid JS 接口（经由 @JavascriptInterface 调用原生 TTS）。
    //
    // 关键思路：不修改前端代码，纯注入式 polyfill 方案。
    // =====================================================================
    private fun injectTtsPolyfill() {
        val js = """
            (function() {
                // 仅在 speechSynthesis 不可用或不完整时注入 polyfill
                if (!window.speechSynthesis || typeof window.speechSynthesis.speak !== 'function') {
                    // 使用一个数组缓存待朗读的 utterance，便于后续扩展
                    var pendingUtterances = [];

                    window.speechSynthesis = {
                        speaking: false,
                        pending: false,

                        /**
                         * 朗读语音
                         * 将 utterance.text 通过 HermesAndroid 原生接口传递给 Android TTS 引擎
                         */
                        speak: function(utterance) {
                            if (utterance && utterance.text) {
                                pendingUtterances.push(utterance);
                                try {
                                    HermesAndroid.speak(utterance.text);
                                    this.speaking = true;
                                    console.log('[HermesTTS] speak: "' + utterance.text.substring(0, 50) + '..."');
                                } catch(e) {
                                    console.error('[HermesTTS] HermesAndroid.speak error:', e);
                                }
                            }
                        },

                        /**
                         * 取消朗读
                         */
                        cancel: function() {
                            pendingUtterances = [];
                            this.speaking = false;
                            this.pending = false;
                            try {
                                HermesAndroid.stopSpeaking();
                                console.log('[HermesTTS] cancel');
                            } catch(e) {
                                console.error('[HermesTTS] HermesAndroid.stopSpeaking error:', e);
                            }
                        },

                        /**
                         * 暂停朗读（Android TTS 不支持暂停，直接停止）
                         */
                        pause: function() {
                            this.pending = true;
                            try {
                                HermesAndroid.stopSpeaking();
                                console.log('[HermesTTS] pause');
                            } catch(e) {
                                console.error('[HermesTTS] pause error:', e);
                            }
                        },

                        /**
                         * 恢复朗读（Android TTS 不支持恢复，重新朗读最新的一条）
                         */
                        resume: function() {
                            this.pending = false;
                            if (pendingUtterances.length > 0) {
                                var last = pendingUtterances[pendingUtterances.length - 1];
                                try {
                                    HermesAndroid.speak(last.text);
                                    this.speaking = true;
                                    console.log('[HermesTTS] resume');
                                } catch(e) {
                                    console.error('[HermesTTS] resume error:', e);
                                }
                            }
                        },

                        /**
                         * 获取可用语音列表（Android WebView 无法获取，返回空数组）
                         */
                        getVoices: function() {
                            return [];
                        },

                        /**
                         * 语音变更回调（Android 不支持动态语音切换，置空）
                         */
                        onvoiceschanged: null
                    };

                    // 监听原生 TTS 事件（通过 CustomEvent 通知前端朗读状态变化）
                    document.addEventListener('tts:started', function() {
                        window.speechSynthesis.speaking = true;
                    });
                    document.addEventListener('tts:finished', function() {
                        window.speechSynthesis.speaking = false;
                        window.speechSynthesis.pending = false;
                    });
                    document.addEventListener('tts:error', function() {
                        window.speechSynthesis.speaking = false;
                        window.speechSynthesis.pending = false;
                    });

                    console.log('[HermesMobile] TTS polyfill injected: window.speechSynthesis -> HermesAndroid');
                } else {
                    console.log('[HermesMobile] Native speechSynthesis available, no polyfill needed');
                }
            })();
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

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // =====================================================================
    // HermesJsInterface — JS 桥接接口
    //
    // 通过 @JavascriptInterface 注解暴露方法给前端 JavaScript 调用。
    // 在 Android WebView 中，JS 只能调用带此注解的 public 方法。
    // 前端通过 window.HermesAndroid.speak(text) 和
    // window.HermesAndroid.stopSpeaking() 调用原生 TTS。
    // =====================================================================
    inner class HermesJsInterface {

        /**
         * 朗读指定文本
         * 由前端 window.speechSynthesis.speak(utterance) 被 polyfill 拦截后调用
         * @param text 要朗读的文本内容
         */
        @JavascriptInterface
        fun speak(text: String) {
            Log.d(TAG, "JS bridge: speak() called, text.length=${text.length}")
            ttsManager.speak(text)
        }

        /**
         * 停止朗读
         * 由前端 window.speechSynthesis.cancel() 被 polyfill 拦截后调用
         */
        @JavascriptInterface
        fun stopSpeaking() {
            Log.d(TAG, "JS bridge: stopSpeaking() called")
            ttsManager.stop()
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
        // 释放 TTS 引擎资源（停止朗读 + 关闭引擎）
        if (::ttsManager.isInitialized) {
            ttsManager.destroy()
        }
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}