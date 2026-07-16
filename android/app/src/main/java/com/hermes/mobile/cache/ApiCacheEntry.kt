package com.hermes.mobile.cache

import org.json.JSONObject

/**
 * ApiCacheEntry - API 响应缓存元数据。
 *
 * 【是什么】
 * 记录一个缓存响应的索引信息，包括缓存 Key、server/user 隔离摘要、路径、Content-Type、保存时间和 body 文件名。
 *
 * 【为什么不直接把 body 放进元数据】
 * 响应体可能较大，放入 JSON 元数据会导致读写慢且难以控制容量；
 * 因此元数据和 body 分离存储，元数据只保存可审计的小字段。
 */
data class ApiCacheEntry(
    val cacheKey: String,
    val serverHash: String,
    val userHash: String,
    val method: String,
    val pathAndQuery: String,
    val contentType: String,
    val savedAt: Long,
    val ttlMs: Long,
    val bodyFile: String,
    val bodySize: Long
) {

    /**
     * 判断缓存是否仍在新鲜期内。
     * 超过 TTL 不代表不能展示，只代表后台必须刷新。
     */
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs - savedAt <= ttlMs
    }

    /**
     * 判断缓存是否超过 stale 最长保留时间。
     * 超过后应清理，避免旧会话数据长期残留。
     */
    fun isExpired(policy: CachePolicy, nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs - savedAt > policy.staleRetentionMs
    }

    /**
     * 序列化为 JSON，用于写入 entries/<cacheKey>.json。
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("cacheKey", cacheKey)
            put("serverHash", serverHash)
            put("userHash", userHash)
            put("method", method)
            put("pathAndQuery", pathAndQuery)
            put("contentType", contentType)
            put("savedAt", savedAt)
            put("ttlMs", ttlMs)
            put("bodyFile", bodyFile)
            put("bodySize", bodySize)
        }
    }

    companion object {
        /**
         * 从 JSON 反序列化元数据。
         */
        fun fromJson(json: JSONObject): ApiCacheEntry {
            return ApiCacheEntry(
                cacheKey = json.optString("cacheKey"),
                serverHash = json.optString("serverHash"),
                userHash = json.optString("userHash"),
                method = json.optString("method"),
                pathAndQuery = json.optString("pathAndQuery"),
                contentType = json.optString("contentType", "application/json; charset=utf-8"),
                savedAt = json.optLong("savedAt"),
                ttlMs = json.optLong("ttlMs"),
                bodyFile = json.optString("bodyFile"),
                bodySize = json.optLong("bodySize")
            )
        }
    }
}
