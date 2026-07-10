package com.hermes.mobile.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * ServerManager - 多服务器管理
 *
 * 替代 ServerConfig，支持多服务器 CRUD 操作。
 * 数据以 JSON 数组格式存储在 SharedPreferences 中。
 *
 * 存储结构：
 * - hermes_servers: JSON 数组，包含所有服务器条目
 * - hermes_active_server_id: 当前选中的服务器 ID（空=未选择）
 *
 * JSON 格式示例：
 * [
 *   {"id": "uuid1", "name": "家庭服务器", "url": "http://192.168.1.100:8648"},
 *   {"id": "uuid2", "name": "公司服务器", "url": "http://10.0.0.100:8648"}
 * ]
 */
class ServerManager(context: Context) {

    companion object {
        private const val PREF_NAME = "hermes_mobile_prefs"
        private const val KEY_SERVERS = "hermes_servers"
        private const val KEY_ACTIVE_SERVER_ID = "hermes_active_server_id"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 获取所有服务器列表
     */
    fun getAllServers(): List<ServerEntry> {
        val jsonStr = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonStr)
            (0 until jsonArray.length()).map { i ->
                ServerEntry.fromJson(jsonArray.getJSONObject(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存服务器列表（覆盖写入）
     */
    private fun saveAllServers(servers: List<ServerEntry>) {
        val jsonArray = JSONArray()
        servers.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_SERVERS, jsonArray.toString()).apply()
    }

    /**
     * 添加服务器
     * @return 添加后的条目（含生成的 ID）
     */
    fun addServer(name: String, url: String): ServerEntry {
        val servers = getAllServers().toMutableList()
        val entry = ServerEntry(name = name, url = url.trimEnd('/'))
        servers.add(entry)
        saveAllServers(servers)
        return entry
    }

    /**
     * 更新服务器
     */
    fun updateServer(id: String, name: String, url: String): Boolean {
        val servers = getAllServers().toMutableList()
        val index = servers.indexOfFirst { it.id == id }
        if (index < 0) return false
        servers[index] = servers[index].copy(name = name, url = url.trimEnd('/'))
        saveAllServers(servers)
        return true
    }

    /**
     * 删除服务器
     */
    fun deleteServer(id: String): Boolean {
        val servers = getAllServers().toMutableList()
        val removed = servers.removeAll { it.id == id }
        if (removed) {
            saveAllServers(servers)
            // 如果删除的是当前活动服务器，清除活动 ID
            if (getActiveServerId() == id) {
                clearActiveServer()
            }
        }
        return removed
    }

    /**
     * 获取当前活动的服务器 ID
     */
    fun getActiveServerId(): String? {
        return prefs.getString(KEY_ACTIVE_SERVER_ID, null)
    }

    /**
     * 设置当前活动的服务器 ID
     */
    fun setActiveServerId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_SERVER_ID, id).apply()
    }

    /**
     * 清除活动服务器
     */
    fun clearActiveServer() {
        prefs.edit().remove(KEY_ACTIVE_SERVER_ID).apply()
    }

    /**
     * 获取当前活动的服务器条目
     */
    fun getActiveServer(): ServerEntry? {
        val activeId = getActiveServerId() ?: return null
        return getAllServers().find { it.id == activeId }
    }

    /**
     * 获取当前活动的服务器 URL
     */
    fun getActiveServerUrl(): String? {
        return getActiveServer()?.url
    }

    /**
     * 判断是否有活动服务器
     */
    fun hasActiveServer(): Boolean {
        return getActiveServerId() != null && getActiveServerUrl() != null
    }

    /**
     * 获取服务器数量
     */
    fun count(): Int {
        return getAllServers().size
    }
}