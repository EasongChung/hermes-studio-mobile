package com.hermes.mobile.webui

import java.net.URI

/**
 * 从 HTML/CSS 中提取同源静态资源路径，供后台打包下载。
 * 不做完整浏览器解析；漏抓的资源在拦截 miss 时回落网络。
 */
object WebUiAssetParser {

    private val htmlRefRegex = Regex(
        """(?i)(?:src|href)\s*=\s*["']([^"']+)["']"""
    )
    private val cssUrlRegex = Regex(
        """(?i)url\(\s*['"]?([^'")\s]+)['"]?\s*\)"""
    )

    fun extractPathsFromHtml(html: String, serverOrigin: String): Set<String> {
        val out = linkedSetOf<String>()
        htmlRefRegex.findAll(html).forEach { m ->
            normalizeToPath(m.groupValues[1], serverOrigin)?.let { out.add(it) }
        }
        // Vite 等可能在 modulepreload 里
        Regex("""(?i)content\s*=\s*["']([^"']+)["']""").findAll(html).forEach { m ->
            val v = m.groupValues[1]
            if (v.startsWith("/") || v.startsWith("http")) {
                normalizeToPath(v, serverOrigin)?.let { out.add(it) }
            }
        }
        return out
    }

    fun extractPathsFromCss(css: String, serverOrigin: String, basePath: String): Set<String> {
        val out = linkedSetOf<String>()
        cssUrlRegex.findAll(css).forEach { m ->
            val raw = m.groupValues[1].trim()
            if (raw.startsWith("data:") || raw.startsWith("#")) return@forEach
            normalizeToPath(raw, serverOrigin, basePath)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 将 URL 或相对路径规范为以 / 开头的 path（不含 query/hash）。
     */
    fun normalizeToPath(
        raw: String,
        serverOrigin: String,
        basePath: String = "/"
    ): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("data:") || value.startsWith("blob:") ||
            value.startsWith("javascript:") || value.startsWith("#")
        ) {
            return null
        }
        return try {
            val originUri = URI(serverOrigin)
            val resolved = when {
                value.startsWith("//") -> URI("${originUri.scheme}:$value")
                value.startsWith("http://") || value.startsWith("https://") -> URI(value)
                value.startsWith("/") -> URI(serverOrigin.trimEnd('/') + value)
                else -> {
                    val baseDir = if (basePath.endsWith("/")) basePath else basePath.substringBeforeLast('/') + "/"
                    URI(serverOrigin.trimEnd('/') + baseDir + value).normalize()
                }
            }
            if (!sameOrigin(originUri, resolved)) return null
            var path = resolved.path ?: return null
            if (path.isBlank()) path = "/"
            if (!path.startsWith("/")) path = "/$path"
            // 去掉 query/fragment（URI.path 已不含）
            path
        } catch (_: Exception) {
            null
        }
    }

    private fun sameOrigin(a: URI, b: URI): Boolean {
        val hostA = (a.host ?: "").lowercase()
        val hostB = (b.host ?: "").lowercase()
        if (hostA.isBlank() || hostB.isBlank() || hostA != hostB) return false
        val portA = if (a.port == -1) defaultPort(a.scheme) else a.port
        val portB = if (b.port == -1) defaultPort(b.scheme) else b.port
        return portA == portB && (a.scheme ?: "").equals(b.scheme ?: "", ignoreCase = true)
    }

    private fun defaultPort(scheme: String?): Int = when (scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
}
