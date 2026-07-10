package com.hermes.mobile.config

import org.json.JSONObject
import java.util.UUID

/**
 * ServerEntry - 服务器条目数据模型
 *
 * 代表一个 Hermes 服务器配置，包含名称、URL、登录凭据等。
 * 支持 JSON 序列化/反序列化，用于 SharedPreferences 持久化存储。
 *
 * @param id 唯一标识（UUID），用于区分不同服务器
 * @param name 用户自定义名称（如 "家庭服务器"、"公司服务器"）
 * @param url 服务器 HTTP 地址（如 http://192.168.1.100:8648）
 * @param username 登录用户名（可选，用于自动登录跳过 WebUI 登录页）
 * @param password 登录密码（可选，用于自动登录跳过 WebUI 登录页）
 */
data class ServerEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = ""
) {
    /**
     * 是否有登录凭据
     */
    fun hasCredentials(): Boolean = username.isNotBlank() && password.isNotBlank()

    /**
     * 序列化为 JSON 对象
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("url", url)
            put("username", username)
            put("password", password)
        }
    }

    companion object {
        /**
         * 从 JSON 对象反序列化
         */
        fun fromJson(json: JSONObject): ServerEntry {
            return ServerEntry(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                url = json.optString("url", ""),
                username = json.optString("username", ""),
                password = json.optString("password", "")
            )
        }
    }
}