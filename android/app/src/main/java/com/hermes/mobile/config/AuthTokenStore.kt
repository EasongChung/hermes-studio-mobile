package com.hermes.mobile.config

import android.content.Context
import android.content.SharedPreferences
import com.hermes.mobile.cache.CacheKeyBuilder

/**
 * AuthTokenStore - 按「服务器 origin + 用户名」安全持久化 JWT。
 *
 * 【用途】
 * 冷启动时复用上次登录 token，跳过 POST /api/auth/login 的网络往返（约 0.8~1.2s）。
 *
 * 【安全】
 * - 不写外部存储；仅 App 私有 SharedPreferences
 * - 不把 token 打进 logcat
 * - 退出登录 / 账号切换时必须 clear
 * - Key 使用 origin+username 的 hash，避免多服务器串号
 */
class AuthTokenStore(context: Context) {

    companion object {
        private const val PREF_NAME = "hermes_auth_token_prefs"
        private const val KEY_PREFIX_TOKEN = "token_"
        private const val KEY_PREFIX_SAVED_AT = "saved_at_"
        /** 默认 7 天；过期后强制重新登录 */
        private const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 保存登录成功后的 token。
     * @return true 表示写入成功
     */
    fun save(serverOrigin: String, username: String, token: String): Boolean {
        if (serverOrigin.isBlank() || username.isBlank() || token.isBlank()) return false
        val key = storageKey(serverOrigin, username)
        return try {
            prefs.edit()
                .putString(KEY_PREFIX_TOKEN + key, token)
                .putLong(KEY_PREFIX_SAVED_AT + key, System.currentTimeMillis())
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 读取未过期的 token；过期或不存在返回 null。
     */
    fun load(
        serverOrigin: String,
        username: String,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS
    ): String? {
        if (serverOrigin.isBlank() || username.isBlank()) return null
        val key = storageKey(serverOrigin, username)
        return try {
            val token = prefs.getString(KEY_PREFIX_TOKEN + key, null)?.takeIf { it.isNotBlank() }
                ?: return null
            val savedAt = prefs.getLong(KEY_PREFIX_SAVED_AT + key, 0L)
            if (savedAt <= 0L) return null
            if (System.currentTimeMillis() - savedAt > maxAgeMs) {
                clear(serverOrigin, username)
                return null
            }
            token
        } catch (_: Exception) {
            null
        }
    }

    fun clear(serverOrigin: String, username: String) {
        if (serverOrigin.isBlank() || username.isBlank()) return
        val key = storageKey(serverOrigin, username)
        prefs.edit()
            .remove(KEY_PREFIX_TOKEN + key)
            .remove(KEY_PREFIX_SAVED_AT + key)
            .apply()
    }

    /** 清理该 origin 下所有 token（无法精确到用户时的兜底）。 */
    fun clearForOrigin(serverOrigin: String) {
        if (serverOrigin.isBlank()) return
        val originHash = CacheKeyBuilder.hashText(serverOrigin.trim().trimEnd('/'))
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX_TOKEN) || it.startsWith(KEY_PREFIX_SAVED_AT) }
            .filter { it.contains(originHash) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun storageKey(serverOrigin: String, username: String): String {
        val origin = serverOrigin.trim().trimEnd('/')
        val user = username.trim()
        return CacheKeyBuilder.hashText("$origin|$user")
    }
}
