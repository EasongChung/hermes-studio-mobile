package com.hermes.mobile.webui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 单版本元数据。
 */
data class WebUiVersionMeta(
    val id: String,
    val contentHash: String,
    val source: String,
    val createdAt: Long,
    val status: String,
    val byteSize: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("contentHash", contentHash)
        .put("source", source)
        .put("createdAt", createdAt)
        .put("status", status)
        .put("byteSize", byteSize)

    companion object {
        const val SOURCE_SERVER = "server"
        const val SOURCE_APK = "apk"
        const val STATUS_READY = "ready"
        const val STATUS_FAILED = "failed"
        const val STATUS_PENDING = "pending"

        fun fromJson(obj: JSONObject): WebUiVersionMeta = WebUiVersionMeta(
            id = obj.optString("id"),
            contentHash = obj.optString("contentHash"),
            source = obj.optString("source", SOURCE_SERVER),
            createdAt = obj.optLong("createdAt", 0L),
            status = obj.optString("status", STATUS_READY),
            byteSize = obj.optLong("byteSize", 0L)
        )
    }
}

/**
 * 某 serverOrigin 下的 WebUI 包状态。
 */
data class WebUiOriginState(
    val activeId: String? = null,
    val previousId: String? = null,
    val pendingActivateId: String? = null,
    val lastProbeAt: Long = 0L,
    val lastProbeHash: String? = null,
    val downloadingId: String? = null,
    val versionOrder: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("activeId", activeId)
        .put("previousId", previousId)
        .put("pendingActivateId", pendingActivateId)
        .put("lastProbeAt", lastProbeAt)
        .put("lastProbeHash", lastProbeHash)
        .put("downloadingId", downloadingId)
        .put("versionOrder", JSONArray(versionOrder))

    companion object {
        fun fromJson(obj: JSONObject): WebUiOriginState {
            val order = mutableListOf<String>()
            val arr = obj.optJSONArray("versionOrder")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isNotBlank()) order.add(id)
                }
            }
            return WebUiOriginState(
                activeId = obj.optString("activeId").ifBlank { null },
                previousId = obj.optString("previousId").ifBlank { null },
                pendingActivateId = obj.optString("pendingActivateId").ifBlank { null },
                lastProbeAt = obj.optLong("lastProbeAt", 0L),
                lastProbeHash = obj.optString("lastProbeHash").ifBlank { null },
                downloadingId = obj.optString("downloadingId").ifBlank { null },
                versionOrder = order
            )
        }
    }
}
