package com.psphere.sync

import android.util.Log
import android.webkit.MimeTypeMap
import com.psphere.data.TokenDataStore
import com.psphere.network.ApiClient
import com.psphere.network.SyncCheckRequest
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant

private const val TAG = "SyncManager"

data class SyncPlan(
    val total: Int,
    val toUpload: List<FileHasher.FileEntry>,
    val skipped: Int,
)

data class SyncResult(
    val uploaded: Int,
    val failed: Int,
    val skipped: Int,
)

object SyncManager {

    suspend fun buildSyncPlan(context: android.content.Context, rootDir: File): SyncPlan {
        val store = TokenDataStore(context)
        val jwt = store.jwtToken.first() ?: error("Not authenticated")
        val deviceToken = store.deviceToken.first() ?: error("Device not registered")

        val allFiles = FileHasher.scanDirectory(rootDir)
        if (allFiles.isEmpty()) return SyncPlan(0, emptyList(), 0)

        val response = ApiClient.api.syncCheck(
            bearer = "Bearer $jwt",
            body = SyncCheckRequest(deviceToken = deviceToken, hashes = allFiles.map { it.hash })
        )

        val missingSet = response.missing.toHashSet()
        val toUpload = allFiles.filter { missingSet.contains(it.hash) }

        return SyncPlan(
            total = allFiles.size,
            toUpload = toUpload,
            skipped = allFiles.size - toUpload.size,
        )
    }

    suspend fun executePlan(
        context: android.content.Context,
        plan: SyncPlan,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncResult {
        val store = TokenDataStore(context)
        val jwt = store.jwtToken.first() ?: error("Not authenticated")
        val deviceToken = store.deviceToken.first() ?: error("Device not registered")
        val api = ApiClient.api

        var uploaded = 0
        var failed = 0

        plan.toUpload.forEachIndexed { index, entry ->
            try {
                val mimeType = mimeTypeOf(entry.file) ?: "application/octet-stream"
                val filePart = MultipartBody.Part.createFormData(
                    "file",
                    entry.file.name,
                    entry.file.asRequestBody(mimeType.toMediaTypeOrNull())
                )
                api.uploadFile(
                    bearer = "Bearer $jwt",
                    file = filePart,
                    deviceToken = deviceToken.toRequestBody("text/plain".toMediaTypeOrNull()),
                    filePath = entry.relativePath.toRequestBody("text/plain".toMediaTypeOrNull()),
                    hash = entry.hash.toRequestBody("text/plain".toMediaTypeOrNull()),
                    modifiedAt = Instant.ofEpochMilli(entry.modifiedAt).toString()
                        .toRequestBody("text/plain".toMediaTypeOrNull()),
                )
                uploaded++
                Log.d(TAG, "Uploaded ${entry.relativePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Upload failed for ${entry.relativePath}: ${e.message}")
                failed++
            }
            onProgress(index + 1, plan.toUpload.size)
        }

        return SyncResult(uploaded = uploaded, failed = failed, skipped = plan.skipped)
    }

    private fun mimeTypeOf(file: File): String? {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}
