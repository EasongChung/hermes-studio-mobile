package com.hermes.mobile.webui

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebUI 本地资源包管理器。
 *
 * 【职责】
 * 1. 按 serverOrigin 隔离存储运行时下载的前端静态资源
 * 2. 拦截优先返回本地 active 版本；否则调用方可回落 APK assets / 网络
 * 3. 后台探测服务端 index 指纹，新版本后台下载，pending 至下次激活
 * 4. 每 origin 最多保留 [WebUiBundlePolicy.maxVersionsPerOrigin] 个版本
 * 5. 加载失败时可 rollback 到 previous
 *
 * 【安全】
 * - 仅写入 App 私有 filesDir
 * - 仅下载同源静态扩展名，限制单文件/总量
 * - 日志不打印 token、HTML 正文
 */
class WebUiBundleManager(
    context: Context,
    private val policy: WebUiBundlePolicy = WebUiBundlePolicy()
) {
    companion object {
        private const val TAG = "HermesWebUi"
        private const val ROOT = "webui"
        private const val STATE_FILE = "state.json"
        private const val META_FILE = "meta.json"
    }

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, ROOT)
    private val stateLock = Any()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hermes-webui-bundle").apply { isDaemon = true }
    }
    private val probing = AtomicBoolean(false)

    fun ensureOriginReady(serverOrigin: String) {
        try {
            originDir(serverOrigin).mkdirs()
            versionsDir(serverOrigin).mkdirs()
            if (!stateFile(serverOrigin).exists()) {
                writeState(serverOrigin, WebUiOriginState())
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureOriginReady failed: ${e.message}")
        }
    }

    /**
     * 打开本地 active 版本中的文件；不存在返回 null。
     * path 为 URL path，如 / 或 /assets/index-xx.js
     */
    fun openActiveFile(serverOrigin: String, path: String): Pair<InputStream, String>? {
        return try {
            val state = readState(serverOrigin)
            val activeId = state.activeId ?: return null
            openVersionFile(serverOrigin, activeId, path)
        } catch (e: Exception) {
            Log.w(TAG, "openActiveFile failed: ${e.message}")
            null
        }
    }

    fun openVersionFile(serverOrigin: String, versionId: String, path: String): Pair<InputStream, String>? {
        val rel = pathToRelative(path)
        val root = versionDir(serverOrigin, versionId).canonicalFile
        val file = File(root, rel).canonicalFile
        if (!file.exists() || !file.isFile) return null
        // 防止路径穿越：必须落在版本目录内（含 separator，避免 /a 匹配 /ab）
        val rootPath = root.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        if (file != root && !file.path.startsWith(rootPath)) return null
        val ext = rel.substringAfterLast('.', "").lowercase()
        return FileInputStream(file) to ext
    }

    /**
     * 下次 loadFrontend 前调用：把 pending 升为 active，旧 active 变 previous。
     * @return true 表示发生了切换
     */
    fun activatePendingIfAny(serverOrigin: String): Boolean {
        return try {
            val state = readState(serverOrigin)
            val pending = state.pendingActivateId ?: return false
            val pendingDir = versionDir(serverOrigin, pending)
            if (!pendingDir.exists() || !File(pendingDir, "index.html").exists()) {
                Log.w(TAG, "pending missing index, clear pending id=$pending")
                writeState(serverOrigin, state.copy(pendingActivateId = null))
                return false
            }
            val newState = state.copy(
                previousId = state.activeId ?: state.previousId,
                activeId = pending,
                pendingActivateId = null
            )
            writeState(serverOrigin, newState)
            Log.i(TAG, "activated pending id=$pending previous=${newState.previousId}")
            trimVersions(serverOrigin)
            true
        } catch (e: Exception) {
            Log.w(TAG, "activatePending failed: ${e.message}")
            false
        }
    }

    /**
     * 主框架加载失败时回退到 previous。
     * @return 是否成功切换
     */
    fun rollbackToPrevious(serverOrigin: String): Boolean {
        return try {
            val state = readState(serverOrigin)
            val prev = state.previousId ?: return false
            if (prev == state.activeId) return false
            val prevDir = versionDir(serverOrigin, prev)
            if (!File(prevDir, "index.html").exists()) {
                Log.w(TAG, "rollback target missing index id=$prev")
                return false
            }
            // 标记坏 active
            state.activeId?.let { markFailed(serverOrigin, it) }
            writeState(
                serverOrigin,
                state.copy(
                    activeId = prev,
                    previousId = null,
                    pendingActivateId = null
                )
            )
            Log.i(TAG, "rollback to previous id=$prev")
            true
        } catch (e: Exception) {
            Log.w(TAG, "rollback failed: ${e.message}")
            false
        }
    }

    /**
     * 调度后台探测（节流 + 单飞）。
     */
    fun scheduleProbe(serverOrigin: String, force: Boolean = false) {
        if (serverOrigin.isBlank()) return
        ensureOriginReady(serverOrigin)
        executor.execute {
            if (!probing.compareAndSet(false, true)) {
                Log.d(TAG, "probe skip: already running")
                return@execute
            }
            try {
                probeAndMaybeDownload(serverOrigin, force)
            } finally {
                probing.set(false)
            }
        }
    }

    private fun probeAndMaybeDownload(serverOrigin: String, force: Boolean) {
        var state = readState(serverOrigin)
        val now = System.currentTimeMillis()

        // 进程被杀可能导致 downloadingId 残留：无对应 tmp 则清锁，避免永久跳过探测
        state.downloadingId?.let { id ->
            val tmp = File(versionsDir(serverOrigin), ".tmp-$id")
            if (!tmp.exists()) {
                Log.w(TAG, "clear stale downloadingId=$id")
                state = state.copy(downloadingId = null)
                writeState(serverOrigin, state)
            } else if (!force) {
                Log.d(TAG, "probe skip: downloading id=$id")
                return
            }
        }

        if (!force && state.lastProbeAt > 0 && now - state.lastProbeAt < policy.probeMinIntervalMs) {
            Log.d(TAG, "probe skip: interval origin=${originKey(serverOrigin)}")
            return
        }

        val indexUrl = serverOrigin.trimEnd('/') + "/"
        Log.d(TAG, "probe start")
        val htmlBytes = downloadBytes(indexUrl) ?: run {
            writeState(serverOrigin, state.copy(lastProbeAt = now))
            Log.w(TAG, "probe failed: cannot fetch index")
            return
        }
        if (htmlBytes.size > policy.maxFileBytes) {
            Log.w(TAG, "probe skip: index too large size=${htmlBytes.size}")
            writeState(serverOrigin, state.copy(lastProbeAt = now))
            return
        }
        val html = String(htmlBytes, Charsets.UTF_8)
        val contentHash = sha256Hex(normalizeHtmlForHash(html))
        val versionId = contentHash.take(16)
        writeState(
            serverOrigin,
            readState(serverOrigin).copy(lastProbeAt = now, lastProbeHash = contentHash)
        )

        val current = readState(serverOrigin)
        val activeMeta = current.activeId?.let { readMeta(serverOrigin, it) }
        val localHasVersion = File(versionDir(serverOrigin, versionId), "index.html").exists()
        val alreadyKnown = activeMeta?.contentHash == contentHash ||
            current.pendingActivateId == versionId ||
            (current.versionOrder.contains(versionId) && localHasVersion)

        if (alreadyKnown) {
            // 已有同 hash：若尚未 active 且目录完整，可标 pending
            if (current.activeId != versionId &&
                localHasVersion &&
                current.pendingActivateId != versionId
            ) {
                writeState(serverOrigin, current.copy(pendingActivateId = versionId))
                Log.i(TAG, "probe: existing version marked pending id=$versionId")
            } else {
                Log.d(TAG, "probe: up-to-date hash=${contentHash.take(12)}")
            }
            // 若还没有任何 active，把该版本或现有目录激活
            if (current.activeId == null && localHasVersion) {
                writeState(
                    serverOrigin,
                    readState(serverOrigin).copy(activeId = versionId, pendingActivateId = null)
                )
            }
            return
        }

        Log.i(TAG, "probe: newer hash=${contentHash.take(12)} id=$versionId")
        downloadVersion(serverOrigin, versionId, contentHash, htmlBytes)
    }

    private fun downloadVersion(
        serverOrigin: String,
        versionId: String,
        contentHash: String,
        indexHtmlBytes: ByteArray
    ) {
        val state0 = readState(serverOrigin)
        writeState(serverOrigin, state0.copy(downloadingId = versionId))
        val tmpDir = File(versionsDir(serverOrigin), ".tmp-$versionId")
        val finalDir = versionDir(serverOrigin, versionId)
        try {
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()

            // 写 index.html
            File(tmpDir, "index.html").writeBytes(indexHtmlBytes)
            var totalBytes = indexHtmlBytes.size.toLong()
            val html = String(indexHtmlBytes, Charsets.UTF_8)
            val queue = ArrayDeque<String>()
            val seen = linkedSetOf<String>()
            WebUiAssetParser.extractPathsFromHtml(html, serverOrigin).forEach { p ->
                if (shouldFetchPath(p) && seen.add(p)) queue.add(p)
            }

            var cssDepthBudget = policy.maxCssDepth
            while (queue.isNotEmpty() && seen.size <= policy.maxFilesPerVersion) {
                val path = queue.removeFirst()
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty() && ext !in policy.staticExtensions) continue
                if (path == "/" || path.endsWith("/index.html")) continue

                val url = serverOrigin.trimEnd('/') + path
                val bytes = downloadBytes(url) ?: continue
                if (bytes.size > policy.maxFileBytes) {
                    Log.d(TAG, "skip large file path=$path size=${bytes.size}")
                    continue
                }
                totalBytes += bytes.size
                if (totalBytes > policy.maxVersionBytes) {
                    throw IllegalStateException("version exceeds maxVersionBytes")
                }
                val rel = pathToRelative(path)
                val out = File(tmpDir, rel)
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)

                if (ext == "css" && cssDepthBudget > 0) {
                    val more = WebUiAssetParser.extractPathsFromCss(
                        String(bytes, Charsets.UTF_8),
                        serverOrigin,
                        path
                    )
                    cssDepthBudget--
                    more.forEach { p ->
                        if (shouldFetchPath(p) && seen.add(p)) queue.add(p)
                    }
                }
            }

            // 原子切换目录
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) {
                // 跨设备 rename 失败则拷贝
                tmpDir.copyRecursively(finalDir, overwrite = true)
                tmpDir.deleteRecursively()
            }

            val meta = WebUiVersionMeta(
                id = versionId,
                contentHash = contentHash,
                source = WebUiVersionMeta.SOURCE_SERVER,
                createdAt = System.currentTimeMillis(),
                status = WebUiVersionMeta.STATUS_READY,
                byteSize = totalBytes
            )
            writeMeta(serverOrigin, meta)

            val st = readState(serverOrigin)
            val order = (listOf(versionId) + st.versionOrder).distinct()
            // 首次无 active：直接作为 active，便于立刻本地优先
            val next = if (st.activeId == null) {
                st.copy(
                    activeId = versionId,
                    pendingActivateId = null,
                    downloadingId = null,
                    versionOrder = order
                )
            } else {
                st.copy(
                    pendingActivateId = versionId,
                    downloadingId = null,
                    versionOrder = order
                )
            }
            writeState(serverOrigin, next)
            Log.i(
                TAG,
                "download ok id=$versionId files≈${seen.size + 1} bytes=$totalBytes " +
                    "mode=${if (next.activeId == versionId) "active" else "pending"}"
            )
            trimVersions(serverOrigin)
        } catch (e: Exception) {
            Log.w(TAG, "download failed id=$versionId: ${e.message}")
            try {
                tmpDir.deleteRecursively()
            } catch (_: Exception) {
            }
            val st = readState(serverOrigin)
            writeState(serverOrigin, st.copy(downloadingId = null))
        }
    }

    fun trimVersions(serverOrigin: String) {
        try {
            val state = readState(serverOrigin)
            val versionsRoot = versionsDir(serverOrigin)
            if (!versionsRoot.exists()) return

            // 清理残留 tmp
            versionsRoot.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith(".tmp-")) {
                    f.deleteRecursively()
                }
            }

            val protected = linkedSetOf<String>()
            state.activeId?.let { protected.add(it) }
            state.previousId?.let { protected.add(it) }
            state.pendingActivateId?.let { protected.add(it) }

            val dirs = versionsRoot.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.toMutableList()
                ?: return

            // 按 meta.createdAt 旧→新
            dirs.sortBy { readMeta(serverOrigin, it.name)?.createdAt ?: it.lastModified() }

            // 优先删 failed
            val failed = dirs.filter {
                readMeta(serverOrigin, it.name)?.status == WebUiVersionMeta.STATUS_FAILED &&
                    it.name !in protected
            }
            for (f in failed) {
                if (dirs.size <= policy.maxVersionsPerOrigin) break
                deleteVersionDir(serverOrigin, f.name)
                dirs.removeAll { it.name == f.name }
            }

            while (dirs.size > policy.maxVersionsPerOrigin) {
                val victim = dirs.firstOrNull { it.name !in protected } ?: break
                deleteVersionDir(serverOrigin, victim.name)
                dirs.removeAll { it.name == victim.name }
            }

            // 容量软限制
            var total = dirs.sumOf { dirSize(it) }
            if (total > policy.maxOriginBytes) {
                val ordered = dirs.sortedBy { readMeta(serverOrigin, it.name)?.createdAt ?: it.lastModified() }
                for (d in ordered) {
                    if (total <= policy.maxOriginBytes) break
                    if (d.name in protected) continue
                    val sz = dirSize(d)
                    deleteVersionDir(serverOrigin, d.name)
                    total -= sz
                    dirs.removeAll { it.name == d.name }
                }
            }

            val remain = dirs.map { it.name }
            val st = readState(serverOrigin)
            writeState(serverOrigin, st.copy(versionOrder = remain))
            Log.d(TAG, "trim done keep=${remain.size} protected=${protected.size}")
        } catch (e: Exception) {
            Log.w(TAG, "trim failed: ${e.message}")
        }
    }

    private fun deleteVersionDir(serverOrigin: String, id: String) {
        try {
            versionDir(serverOrigin, id).deleteRecursively()
            Log.d(TAG, "deleted version id=$id")
        } catch (e: Exception) {
            Log.w(TAG, "delete version failed id=$id: ${e.message}")
        }
    }

    private fun markFailed(serverOrigin: String, id: String) {
        val meta = readMeta(serverOrigin, id) ?: return
        writeMeta(serverOrigin, meta.copy(status = WebUiVersionMeta.STATUS_FAILED))
    }

    private fun shouldFetchPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (policy.networkOnlyPathPrefixes.any { path.startsWith(it) }) return false
        val ext = path.substringAfterLast('.', "").lowercase()
        // 无扩展名的 SPA 路由不下载
        if (ext.isEmpty() || path.endsWith("/")) return false
        return ext in policy.staticExtensions
    }

    private fun pathToRelative(path: String): String {
        val p = when {
            path.isBlank() || path == "/" -> "index.html"
            path.endsWith("/") -> path.trimStart('/') + "index.html"
            else -> path.trimStart('/')
        }
        return p.replace("..", "_")
    }

    private fun downloadBytes(urlStr: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "http $code for ${url.path}")
                return null
            }
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.d(TAG, "download error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun normalizeHtmlForHash(html: String): String {
        // 去掉易变空白，降低无意义抖动（非加密用途）
        return html.replace("\r\n", "\n").replace(Regex(">[ \\t]+<"), "><").trim()
    }

    private fun sha256Hex(text: String): String {
        val dig = MessageDigest.getInstance("SHA-256")
        val bytes = dig.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun originKey(serverOrigin: String): String {
        val dig = MessageDigest.getInstance("SHA-256")
        val bytes = dig.digest(serverOrigin.trimEnd('/').toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun originDir(serverOrigin: String) = File(rootDir, originKey(serverOrigin))
    private fun versionsDir(serverOrigin: String) = File(originDir(serverOrigin), "versions")
    private fun versionDir(serverOrigin: String, id: String) = File(versionsDir(serverOrigin), id)
    private fun stateFile(serverOrigin: String) = File(originDir(serverOrigin), STATE_FILE)

    private fun readState(serverOrigin: String): WebUiOriginState {
        synchronized(stateLock) {
            return try {
                val f = stateFile(serverOrigin)
                if (!f.exists()) WebUiOriginState()
                else WebUiOriginState.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
            } catch (_: Exception) {
                WebUiOriginState()
            }
        }
    }

    private fun writeState(serverOrigin: String, state: WebUiOriginState) {
        synchronized(stateLock) {
            try {
                originDir(serverOrigin).mkdirs()
                versionsDir(serverOrigin).mkdirs()
                val f = stateFile(serverOrigin)
                val tmp = File(f.parentFile, "$STATE_FILE.tmp")
                tmp.writeText(state.toJson().toString(), Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "writeState failed: ${e.message}")
            }
        }
    }

    private fun readMeta(serverOrigin: String, id: String): WebUiVersionMeta? {
        return try {
            val f = File(versionDir(serverOrigin, id), META_FILE)
            if (!f.exists()) return null
            WebUiVersionMeta.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMeta(serverOrigin: String, meta: WebUiVersionMeta) {
        val f = File(versionDir(serverOrigin, meta.id), META_FILE)
        f.parentFile?.mkdirs()
        f.writeText(meta.toJson().toString(), Charsets.UTF_8)
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var sum = 0L
        dir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        return sum
    }

    fun getActiveVersionId(serverOrigin: String): String? = readState(serverOrigin).activeId

    fun getDebugSnapshot(serverOrigin: String): String {
        val s = readState(serverOrigin)
        return "active=${s.activeId} previous=${s.previousId} pending=${s.pendingActivateId} " +
            "downloading=${s.downloadingId} versions=${s.versionOrder.size}"
    }
}
