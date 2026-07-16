package com.hermes.mobile.cache

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * ApiCacheManager - Sprint 8 原始 API 响应缓存管理器。
 *
 * 【是什么】
 * 在 App 私有目录中保存、读取和清理 API 原始响应缓存。
 *
 * 【设计原则】
 * 1. 不解析业务 JSON：缓存层只保存 Server 原始响应，避免 Android 层理解前端业务结构。
 * 2. 元数据与响应体分离：便于容量管理和审计。
 * 3. 异常安全：任何读写失败都返回 null/false，不影响 WebView 原网络流程。
 * 4. 隐私保护：目录位于 App 私有 files/api_cache，不写外部存储。
 */
class ApiCacheManager(
    context: Context,
    private val policy: CachePolicy = CachePolicy()
) {

    companion object {
        private const val TAG = "HermesApiCache"
        private const val CACHE_ROOT = "api_cache"
        private const val ENTRIES_DIR = "entries"
        private const val BODIES_DIR = "bodies"
    }

    private val rootDir: File = File(context.filesDir, CACHE_ROOT)
    private val entriesDir: File = File(rootDir, ENTRIES_DIR)
    private val bodiesDir: File = File(rootDir, BODIES_DIR)

    init {
        ensureDirs()
    }

    /**
     * 读取缓存元数据与响应体。
     *
     * @return Pair(entry, bodyBytes)，读取失败或 body 缺失时返回 null。
     */
    fun read(cacheKey: String): Pair<ApiCacheEntry, ByteArray>? {
        return try {
            ensureDirs()
            val entryFile = File(entriesDir, "$cacheKey.json")
            if (!entryFile.exists()) return null

            val entry = ApiCacheEntry.fromJson(JSONObject(entryFile.readText(Charsets.UTF_8)))
            val bodyFile = File(bodiesDir, entry.bodyFile)
            if (!bodyFile.exists()) return null

            entry to bodyFile.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Read cache failed: ${e.message}")
            null
        }
    }

    /**
     * 写入原始 API 响应缓存。
     *
     * @return true 表示写入成功；false 表示响应过大或写入失败。
     */
    fun write(
        cacheKey: String,
        serverHash: String,
        userHash: String,
        method: String,
        pathAndQuery: String,
        contentType: String,
        body: ByteArray
    ): Boolean {
        return try {
            ensureDirs()
            if (body.size > policy.maxBodyBytes) {
                Log.d(TAG, "Skip cache: body too large size=${body.size}")
                return false
            }

            val bodyFileName = "$cacheKey.body"
            val bodyFile = File(bodiesDir, bodyFileName)
            bodyFile.writeBytes(body)

            val entry = ApiCacheEntry(
                cacheKey = cacheKey,
                serverHash = serverHash,
                userHash = userHash,
                method = method.uppercase(),
                pathAndQuery = pathAndQuery,
                contentType = contentType,
                savedAt = System.currentTimeMillis(),
                ttlMs = policy.ttlMs,
                bodyFile = bodyFileName,
                bodySize = body.size.toLong()
            )
            File(entriesDir, "$cacheKey.json").writeText(entry.toJson().toString(), Charsets.UTF_8)
            trimToSize()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Write cache failed: ${e.message}")
            false
        }
    }

    /**
     * 清理指定 server + user 维度的缓存。
     *
     * 【为什么需要】退出登录、删除服务器、切换账号时必须清理或隔离缓存，避免敏感会话残留。
     */
    fun clearFor(serverHash: String, userHash: String) {
        try {
            ensureDirs()
            entriesDir.listFiles()?.forEach { entryFile ->
                val entry = runCatching {
                    ApiCacheEntry.fromJson(JSONObject(entryFile.readText(Charsets.UTF_8)))
                }.getOrNull() ?: return@forEach

                if (entry.serverHash == serverHash && entry.userHash == userHash) {
                    File(bodiesDir, entry.bodyFile).delete()
                    entryFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clear cache failed: ${e.message}")
        }
    }

    /**
     * 清理指定 server 维度的全部缓存。
     *
     * 【为什么需要】手动登录场景下，Android 可能无法在退出时还原具体 userIdentity。
     * 为了避免敏感会话残留，可退化为清理当前服务器下全部 API 缓存。
     */
    fun clearForServer(serverHash: String) {
        try {
            ensureDirs()
            entriesDir.listFiles()?.forEach { entryFile ->
                val entry = runCatching {
                    ApiCacheEntry.fromJson(JSONObject(entryFile.readText(Charsets.UTF_8)))
                }.getOrNull() ?: return@forEach

                if (entry.serverHash == serverHash) {
                    File(bodiesDir, entry.bodyFile).delete()
                    entryFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clear server cache failed: ${e.message}")
        }
    }

    /**
     * 清理过期缓存并按容量上限淘汰最旧缓存。
     */
    fun trimToSize() {
        try {
            ensureDirs()
            val entries = readAllEntries().toMutableList()

            // 先清理超过 stale 保留期的缓存。
            entries.filter { it.isExpired(policy) }.forEach { deleteEntry(it) }

            var aliveEntries = readAllEntries().sortedBy { it.savedAt }.toMutableList()
            var totalBytes = aliveEntries.sumOf { it.bodySize }

            // 再按 savedAt 从旧到新淘汰，直到总容量低于上限。
            while (totalBytes > policy.maxTotalBytes && aliveEntries.isNotEmpty()) {
                val oldest = aliveEntries.removeAt(0)
                totalBytes -= oldest.bodySize
                deleteEntry(oldest)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Trim cache failed: ${e.message}")
        }
    }

    private fun ensureDirs() {
        if (!entriesDir.exists()) entriesDir.mkdirs()
        if (!bodiesDir.exists()) bodiesDir.mkdirs()
    }

    private fun readAllEntries(): List<ApiCacheEntry> {
        return entriesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching {
                    ApiCacheEntry.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
                }.getOrNull()
            }
            ?: emptyList()
    }

    private fun deleteEntry(entry: ApiCacheEntry) {
        File(bodiesDir, entry.bodyFile).delete()
        File(entriesDir, "${entry.cacheKey}.json").delete()
    }
}
