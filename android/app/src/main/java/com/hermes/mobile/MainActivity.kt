package com.hermes.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
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
                injectMobileFlag()
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