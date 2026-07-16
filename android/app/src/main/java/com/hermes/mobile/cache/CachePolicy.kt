package com.hermes.mobile.cache

/**
 * CachePolicy - Sprint 8 API 缓存策略。
 *
 * 【是什么】
 * 定义透明 API 缓存的时间、大小和容量限制。
 *
 * 【为什么需要】
 * 会话记录可能包含长文本、代码块和工具输出。如果不限制大小，缓存目录会无限增长；
 * 如果不设置 TTL，用户可能长期看到过旧会话列表。
 */
data class CachePolicy(
    /** 缓存新鲜期。超过后仍可作为 stale 数据先展示，但必须后台刷新。 */
    val ttlMs: Long = 5 * 60 * 1000L,

    /** stale 数据最长保留时间。超过后应清理，避免隐私数据长期残留。 */
    val staleRetentionMs: Long = 7 * 24 * 60 * 60 * 1000L,

    /** 单个响应最大字节数，超过则不缓存，避免大文件或异常响应占满空间。 */
    val maxBodyBytes: Long = 2 * 1024 * 1024L,

    /** 缓存目录最大总容量，超过后按 savedAt 从旧到新淘汰。 */
    val maxTotalBytes: Long = 50 * 1024 * 1024L
)
