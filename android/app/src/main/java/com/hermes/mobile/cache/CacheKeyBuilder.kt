package com.hermes.mobile.cache

import java.security.MessageDigest
import java.util.Locale

/**
 * CacheKeyBuilder - Sprint 8 API 缓存 Key 构建器。
 *
 * 【是什么】
 * 根据 serverOrigin、userIdentity、HTTP method、path、query 生成稳定的 SHA-256 缓存键。
 *
 * 【为什么需要】
 * 会话记录属于敏感数据，同一台手机可能配置多个 Server、多个用户。
 * 如果缓存 Key 只包含 path，可能导致 A 服务器或 A 用户的会话记录被 B 服务器 / B 用户误用。
 * 因此 Key 必须包含 server + user + method + path + query，并最终 hash 成文件名安全的字符串。
 */
object CacheKeyBuilder {

    private const val SEPARATOR = "|"

    /**
     * 构建 API 缓存 Key。
     *
     * @param serverOrigin 服务器源地址，例如 http://192.168.1.100:8648
     * @param userIdentity 当前用户身份标识。可传用户名、用户 ID 或 token 摘要；不要传明文密码
     * @param method HTTP 方法，例如 GET
     * @param path URL path，例如 /api/conversations
     * @param query URL query，可为空；不同分页/筛选参数必须生成不同 Key
     * @return SHA-256 十六进制字符串，可作为缓存文件名
     */
    fun build(
        serverOrigin: String,
        userIdentity: String,
        method: String,
        path: String,
        query: String?
    ): String {
        val rawKey = listOf(
            normalizeOrigin(serverOrigin),
            hashText(userIdentity.ifBlank { "anonymous" }),
            normalizeMethod(method),
            normalizePath(path),
            query.orEmpty()
        ).joinToString(SEPARATOR)

        return hashText(rawKey)
    }

    /**
     * 生成文本的 SHA-256 摘要。
     *
     * 【为什么公开】
     * 后续元数据中需要保存 serverHash/userHash 时，可复用同一摘要函数，避免明文写入文件名或日志。
     */
    fun hashText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * 规范化 Server Origin，去掉末尾斜杠，避免同一 Server 因书写差异生成不同缓存 Key。
     */
    private fun normalizeOrigin(origin: String): String {
        return origin.trim().trimEnd('/')
    }

    /**
     * 规范化 HTTP 方法，统一大写。
     */
    private fun normalizeMethod(method: String): String {
        return method.trim().uppercase(Locale.ROOT)
    }

    /**
     * 规范化 path，确保以 / 开头。
     */
    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }
}
