package com.hermes.mobile.webui

/**
 * WebUI 本地资源包策略。
 *
 * 【目标】
 * - 拦截优先本地（运行时版本包 → APK 出厂包 → 网络）
 * - 后台探测服务端静态入口 hash，有新版则后台下载
 * - 下次进入主页 / loadFrontend 时再激活新版（不打断当前会话）
 * - 每个 serverOrigin 最多保留 3 个版本；失败回退上一版；过期自动删
 */
data class WebUiBundlePolicy(
    /** 每个服务器保留的版本上限（含 active / previous / pending） */
    val maxVersionsPerOrigin: Int = 3,

    /** 探测最小间隔，避免频繁拉首页 */
    val probeMinIntervalMs: Long = 30L * 60L * 1000L,

    /** 进入主页后延迟再探测，错开登录与首屏 API */
    val probeInitialDelayMs: Long = 5_000L,

    /** 单文件下载上限 */
    val maxFileBytes: Long = 5L * 1024L * 1024L,

    /** 单版本总容量上限 */
    val maxVersionBytes: Long = 80L * 1024L * 1024L,

    /** 单 origin 下 WebUI 目录总容量软上限（trim 后仍超则再删旧版） */
    val maxOriginBytes: Long = 200L * 1024L * 1024L,

    /** 从 HTML/CSS 递归收集引用的最大文件数 */
    val maxFilesPerVersion: Int = 400,

    /** CSS url() 解析深度（相对引用再抓一轮） */
    val maxCssDepth: Int = 1,

    /** 允许下载/拦截的静态扩展名 */
    val staticExtensions: Set<String> = setOf(
        "html", "js", "css", "json", "svg", "png", "jpg", "jpeg",
        "gif", "ico", "woff", "woff2", "ttf", "eot", "otf", "webp", "map", "txt"
    ),

    /** 不进入 WebUI 包的路径前缀 */
    val networkOnlyPathPrefixes: Set<String> = setOf(
        "/api/", "/v1/", "/health", "/upload", "/webhook", "/socket.io/"
    )
)
