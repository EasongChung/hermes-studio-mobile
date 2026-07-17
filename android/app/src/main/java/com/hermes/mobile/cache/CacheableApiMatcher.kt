package com.hermes.mobile.cache

import java.util.Locale

/**
 * CacheableApiMatcher - Sprint 8 透明 API 缓存精确匹配器。
 *
 * 【是什么】
 * 集中判断 WebView 发起的请求是否属于已确认安全的会话类 GET API，
 * 以及哪些写操作会导致会话缓存失效。
 *
 * 【为什么需要】
 * 会话本地缓存不能散落在 MainActivity 中用零散字符串判断，
 * 否则后续接入缓存时难以审查哪些接口会被缓存，容易误缓存登录、写操作、文件、WebSocket 等高风险接口。
 *
 * 【当前阶段】
 * Sprint 8 已通过后端 Koa 路由和前端 API 调用静态分析确认真实路径。
 * 这里只缓存会话摘要列表 GET，不缓存消息正文、上下文、usage、搜索、文件、WebSocket 等动态/敏感接口。
 */
object CacheableApiMatcher {

    /**
     * 允许透明缓存的精确 GET 路径。
     *
     * 【主聊天页】
     * /api/hermes/sessions 是 ChatView/ChatStore.loadSessions → fetchSessions 的真实接口，
     * 也是启动后侧边栏会话列表的主要数据源。query（source/profile）参与 cache key，不在此处展开。
     *
     * 【会话监控面板】
     * /api/hermes/sessions/conversations 由 ConversationMonitorPane 使用，保留缓存以加速该面板。
     *
     * 【明确不缓存】
     * /api/hermes/sessions/:id、messages、context、usage、search、workspace-file 等动态/敏感接口。
     * 这里用精确 path 集合匹配，不会误命中 /api/hermes/sessions/xxx。
     */
    private const val SESSION_LIST_PATH = "/api/hermes/sessions"
    private const val CONVERSATION_SUMMARY_PATH = "/api/hermes/sessions/conversations"

    private val cacheableGetPaths = setOf(
        SESSION_LIST_PATH,
        CONVERSATION_SUMMARY_PATH
    )

    /**
     * 会影响会话摘要列表有效性的写操作精确规则。
     *
     * 【注意】Socket.IO 的 /chat-run 发送消息不作为缓存候选；如后续要根据 socket 写入事件失效缓存，
     * 应在 MainActivity 中单独审慎处理，避免心跳/轮询导致缓存过度清理。
     */
    private data class MutationRule(
        val method: String,
        val pathRegex: Regex
    )

    private val mutationRules = listOf(
        MutationRule("POST", Regex("^/api/chat-run/runs$")),

        MutationRule("DELETE", Regex("^/api/hermes/sessions/[^/]+$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/batch-delete$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/[^/]+/rename$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/[^/]+/archive$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/[^/]+/unarchive$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/[^/]+/workspace$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/[^/]+/model$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/hermes/[^/]+/import$")),

        MutationRule("PUT", Regex("^/api/hermes/sessions/[^/]+/workspace-file/write$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/[^/]+/workspace-file/mkdir$")),
        MutationRule("DELETE", Regex("^/api/hermes/sessions/[^/]+/workspace-file/delete$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/[^/]+/workspace-file/rename$")),
        MutationRule("POST", Regex("^/api/hermes/sessions/[^/]+/workspace-file/copy$"))
    )

    /**
     * 判断是否属于 API 路径。
     *
     * @param path URL path，例如 /api/hermes/sessions/conversations
     * @return true 表示这是需要被 API 观测逻辑关注的路径
     */
    fun isApiPath(path: String): Boolean {
        return path.startsWith("/api/") ||
            path.startsWith("/v1/") ||
            path.startsWith("/health") ||
            path.startsWith("/upload") ||
            path.startsWith("/webhook") ||
            path.startsWith("/socket.io/")
    }

    /**
     * 判断是否为透明 API 缓存候选。
     *
     * @param method HTTP 方法，只有 GET 可进入缓存候选
     * @param path URL path，不包含 query
     * @return true 表示可作为会话摘要列表缓存候选进入透明缓存逻辑
     */
    fun isCacheCandidate(method: String, path: String): Boolean {
        val normalizedMethod = method.trim().uppercase(Locale.ROOT)
        val normalizedPath = normalizePath(path)

        // 只允许 GET 请求进入缓存候选。POST/PUT/PATCH/DELETE 都可能改变后端状态，不能缓存响应。
        if (normalizedMethod != "GET") return false

        // 使用精确白名单，避免误缓存会话详情、消息正文、上下文、usage、搜索、文件、WebSocket 等接口。
        return normalizedPath in cacheableGetPaths
    }

    /**
     * 判断某个写操作是否可能影响会话缓存。
     *
     * @param method HTTP 方法
     * @param path URL path
     * @return true 表示后续应清理或标记相关会话缓存 stale
     */
    fun isConversationMutation(method: String, path: String): Boolean {
        val normalizedMethod = method.trim().uppercase(Locale.ROOT)
        if (normalizedMethod !in setOf("POST", "PUT", "PATCH", "DELETE")) return false

        val normalizedPath = normalizePath(path)
        return mutationRules.any { rule ->
            rule.method == normalizedMethod && rule.pathRegex.matches(normalizedPath)
        }
    }

    /**
     * 判断是否为 Socket.IO 长轮询写入请求。
     *
     * 【为什么单独判断】
     * Web 端主要通过 Socket.IO 的 /chat-run namespace 发送消息。WebSocket 帧本身不会以普通 HTTP API
     * 形式进入缓存匹配器；但 Socket.IO 在 polling fallback 或握手阶段会出现 POST /socket.io/ 写入请求。
     * 这里仅识别 POST + transport=polling，不缓存任何 Socket.IO 响应，只作为会话摘要缓存失效触发信号。
     */
    fun isSocketIoPollingMutation(method: String, path: String, query: String?): Boolean {
        val normalizedMethod = method.trim().uppercase(Locale.ROOT)
        if (normalizedMethod != "POST") return false

        val normalizedPath = normalizePath(path)
        if (normalizedPath != "/socket.io" && normalizedPath != "/socket.io/") return false

        val normalizedQuery = query.orEmpty().lowercase(Locale.ROOT)
        return normalizedQuery.contains("transport=polling")
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim().lowercase(Locale.ROOT)
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }
}
