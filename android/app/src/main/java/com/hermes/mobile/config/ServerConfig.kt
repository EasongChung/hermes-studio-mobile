package com.hermes.mobile.config

import android.content.Context
import android.content.SharedPreferences

/**
 * ServerConfig - 服务器地址持久化存储
 *
 * 使用 SharedPreferences 保存用户配置的 Hermes Server URL。
 * 所有读写操作在主线程执行（数据量小，无需异步）。
 *
 * 存储键值说明：
 * - hermes_server_url: 用户配置的服务器 HTTP 地址
 * - hermes_is_configured: 标记是否已完成配置
 */
class ServerConfig(context: Context) {

    companion object {
        private const val PREF_NAME = "hermes_mobile_prefs"
        private const val KEY_SERVER_URL = "hermes_server_url"
        private const val KEY_IS_CONFIGURED = "hermes_is_configured"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 保存服务器 URL
     * @param url HTTP 地址（如 http://192.168.1.100:8648）
     */
    fun saveServerUrl(url: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, url.trimEnd('/'))
            .putBoolean(KEY_IS_CONFIGURED, true)
            .apply()
    }

    /**
     * 读取服务器 URL
     * @return 已保存的 URL，未配置返回 null
     */
    fun getServerUrl(): String? {
        return prefs.getString(KEY_SERVER_URL, null)
    }

    /**
     * 判断是否已完成配置
     * @return true=已配置 false=未配置
     */
    fun isConfigured(): Boolean {
        return prefs.getBoolean(KEY_IS_CONFIGURED, false)
    }

    /**
     * 清除配置（允许用户重新设置）
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .putBoolean(KEY_IS_CONFIGURED, false)
            .apply()
    }
}