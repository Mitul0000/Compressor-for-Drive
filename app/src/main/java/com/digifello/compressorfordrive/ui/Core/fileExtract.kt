package com.digifello.compressorfordrive.ui.Core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DriveQuota(
    val limit: Long?,
    val usage: Long,
    val usageInDrive: Long,
    val usageInTrash: Long,
)

enum class FileKind { IMAGE, VIDEO, PDF, OTHER }

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val md5: String?,
    val ownedByMe: Boolean,
    val shared: Boolean,
    val modifiedTime: String?,
    val compressState: String?,
) {
    val alreadyCompressed: Boolean get() = compressState != null

    val needsCleanup: Boolean get() = compressState == DriveApi.STATE_PENDING

    val kind: FileKind
        get() = when {
            mimeType == "image/jpeg" || mimeType == "image/png" -> FileKind.IMAGE
            mimeType.startsWith("video/") -> FileKind.VIDEO
            mimeType == "application/pdf" -> FileKind.PDF
            else -> FileKind.OTHER
        }
}

data class KindSummary(val count: Int, val bytes: Long)

fun List<DriveFile>.summarize(): Map<FileKind, KindSummary> =
    groupBy { it.kind }.mapValues { (_, list) -> KindSummary(list.size, list.sumOf { it.size }) }

class DriveApiException(val code: Int, message: String) : IOException(message)

class DriveApi(
    private val tokenProvider: suspend () -> String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun getQuota(): DriveQuota = withContext(Dispatchers.IO) {
        val url = "$BASE/about".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "storageQuota")
            .build()
        val json = JSONObject(execute(url).use { it.body!!.string() }).getJSONObject("storageQuota")
        DriveQuota(
            limit = json.optString("limit").toLongOrNull(),
            usage = json.optString("usage").toLongOrNull() ?: 0L,
            usageInDrive = json.optString("usageInDrive").toLongOrNull() ?: 0L,
            usageInTrash = json.optString("usageInDriveTrash").toLongOrNull() ?: 0L,
        )
    }

    suspend fun listFiles(onProgress: (Int) -> Unit = {}): List<DriveFile> = withContext(Dispatchers.IO) {
        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = "$BASE/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "trashed = false and mimeType != 'application/vnd.google-apps.folder'")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter(
                    "fields",
                    "nextPageToken,files(id,name,mimeType,size,md5Checksum,ownedByMe,shared,modifiedTime,appProperties)"
                )
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = JSONObject(execute(url).use { it.body!!.string() })
            val files = json.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val size = f.optString("size").toLongOrNull() ?: continue
                    all += DriveFile(
                        id = f.getString("id"),
                        name = f.optString("name"),
                        mimeType = f.optString("mimeType"),
                        size = size,
                        md5 = f.optString("md5Checksum").ifEmpty { null },
                        ownedByMe = f.optBoolean("ownedByMe", false),
                        shared = f.optBoolean("shared", false),
                        modifiedTime = f.optString("modifiedTime").ifEmpty { null },
                        compressState = f.optJSONObject("appProperties")
                            ?.optString(PROP_COMPRESSED)
                            ?.ifEmpty { null },
                    )
                }
            }
            onProgress(all.size)
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        all
    }

    suspend fun download(
        fileId: String,
        dest: File,
        onProgress: (downloaded: Long) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val url = "$BASE/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()
        execute(url).use { response ->
            response.body!!.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
            }
        }
        dest
    }

    private suspend fun execute(url: HttpUrl): Response {
        var response = http.newCall(request(url, tokenProvider())).execute()
        if (response.code == 401) {
            response.close()
            response = http.newCall(request(url, tokenProvider())).execute()
        }
        if (!response.isSuccessful) {
            val message = response.body?.string().orEmpty()
            response.close()
            throw DriveApiException(response.code, "Drive API ${response.code}: $message")
        }
        return response
    }

    private fun request(url: HttpUrl, token: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $token").build()

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"

        const val PROP_COMPRESSED = "compressedByApp"

        const val STATE_PENDING = "pending"

        const val STATE_DONE = "done"

        const val STATE_FAILED = "failed"
    }
}