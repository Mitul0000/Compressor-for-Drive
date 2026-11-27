package com.digifello.compressorfordrive.ui.Core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class UploadResult(val fileId: String, val size: Long, val md5: String)

class UploadVerificationException(message: String) : IOException(message)

/** The file was modified in Drive after we downloaded it, so we must not overwrite it. */
class SourceChangedException : IOException("File changed in Drive while it was being processed")

class DriveUploader(
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * @param expectedCurrentMd5 md5 the live file must still have (the version we downloaded).
     * If it differs, [SourceChangedException] is thrown and nothing is uploaded. Pass null only
     * when restoring an original over our own bad upload.
     * @param modifiedTime original modified time, kept so Drive's date ordering is not scrambled.
     */
    suspend fun replaceFile(
        fileId: String,
        local: File,
        newName: String,
        newMimeType: String,
        modifiedTime: String?,
        expectedCurrentMd5: String?,
        onProgress: (uploaded: Long) -> Unit = {},
    ): UploadResult = withContext(Dispatchers.IO) {
        val total = local.length()
        require(total > 0) { "Empty file" }

        if (expectedCurrentMd5 != null) {
            val now = fileState(fileId)
            if (!now.md5.equals(expectedCurrentMd5, ignoreCase = true)) throw SourceChangedException()
        }

        val localMd5 = local.md5Hex()
        val session = startSession(fileId, total, newName, newMimeType, modifiedTime, localMd5)
        val json = uploadChunks(session, local, total, onProgress)

        val driveMd5 = json.optString("md5Checksum").ifEmpty { null }
            ?: throw UploadVerificationException("Drive returned no checksum, so the upload could not be verified.")
        if (!driveMd5.equals(localMd5, ignoreCase = true)) {
            throw UploadVerificationException("Uploaded file does not match.")
        }
        val driveSize = json.optString("size").toLongOrNull()
        if (driveSize != null && driveSize != total) {
            throw UploadVerificationException("Uploaded file size does not match.")
        }
        UploadResult(fileId = json.getString("id"), size = driveSize ?: total, md5 = driveMd5)
    }

    /**
     * Deletes every revision except the current one, but only if the live file still has
     * [expectedMd5]. If it does not, nothing is deleted.
     */
    suspend fun deleteOldRevisions(fileId: String, expectedMd5: String): Int = withContext(Dispatchers.IO) {
        val state = fileState(fileId)
        if (!state.md5.equals(expectedMd5, ignoreCase = true)) {
            throw UploadVerificationException("File changed after upload. Old versions were kept.")
        }
        val head = state.headRevisionId ?: return@withContext 0

        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = "$API/files/$fileId/revisions".toHttpUrl().newBuilder()
                .addQueryParameter("fields", "nextPageToken,revisions(id)")
                .addQueryParameter("pageSize", "200")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = call(Request.Builder().url(url).get()).use { JSONObject(it.body!!.string()) }
            json.optJSONArray("revisions")?.let { arr ->
                for (i in 0 until arr.length()) ids += arr.getJSONObject(i).getString("id")
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)

        var deleted = 0
        for (id in ids) {
            if (id == head) continue
            try {
                call(Request.Builder().url("$API/files/$fileId/revisions/$id").delete()).close()
                deleted++
            } catch (_: DriveApiException) {
            }
        }
        deleted
    }

    suspend fun setState(fileId: String, state: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put(
                "appProperties",
                JSONObject()
                    .put(DriveApi.PROP_COMPRESSED, state)
                    // null removes the property; it is only needed while pending
                    .put(DriveApi.PROP_EXPECTED_MD5, JSONObject.NULL)
            )
            .toString()
        call(
            Request.Builder().url("$API/files/$fileId")
                .patch(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        ).close()
    }

    private suspend fun fileState(fileId: String): DriveFileState {
        val url = "$API/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "md5Checksum,headRevisionId")
            .build()
        val json = call(Request.Builder().url(url).get()).use { JSONObject(it.body!!.string()) }
        return DriveFileState(
            md5 = json.optString("md5Checksum").ifEmpty { null },
            headRevisionId = json.optString("headRevisionId").ifEmpty { null },
        )
    }

    private suspend fun startSession(
        fileId: String,
        total: Long,
        name: String,
        mime: String,
        modifiedTime: String?,
        localMd5: String,
    ): String {
        val url = "$UPLOAD/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id,size,md5Checksum")
            .build()
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", mime)
            .put(
                "appProperties",
                JSONObject()
                    .put(DriveApi.PROP_COMPRESSED, DriveApi.STATE_PENDING)
                    .put(DriveApi.PROP_EXPECTED_MD5, localMd5)
            )
            .apply { if (modifiedTime != null) put("modifiedTime", modifiedTime) }
            .toString()
        val builder = Request.Builder().url(url)
            .patch(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", total.toString())
        return call(builder).use {
            it.header("Location") ?: throw IOException("No upload session URL returned")
        }
    }

    private suspend fun uploadChunks(
        session: String,
        file: File,
        total: Long,
        onProgress: (Long) -> Unit,
    ): JSONObject {
        var offset = 0L
        var failures = 0
        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                val size = minOf(CHUNK, total - offset).toInt()
                val buffer = ByteArray(size)
                raf.seek(offset)
                raf.readFully(buffer)

                val request = Request.Builder().url(session)
                    .put(buffer.toRequestBody(null))
                    .header("Content-Range", "bytes $offset-${offset + size - 1}/$total")
                    .build()
                try {
                    http.newCall(request).execute().use { r ->
                        when {
                            r.code == 200 || r.code == 201 -> {
                                onProgress(total)
                                return JSONObject(r.body!!.string())
                            }
                            r.code == 308 -> {
                                offset = nextOffset(r)
                                onProgress(offset)
                                failures = 0
                            }
                            r.code >= 500 || r.code == 429 -> throw IOException("Server error ${r.code}")
                            else -> throw DriveApiException(
                                r.code,
                                driveErrorMessage(r.code, r.body?.string().orEmpty())
                            )
                        }
                    }
                } catch (e: DriveApiException) {
                    throw e
                } catch (e: IOException) {
                    if (++failures > MAX_RETRIES) throw e
                    delay(1000L * failures)
                    when (val state = queryProgress(session, total)) {
                        is Progress.Done -> return state.json
                        is Progress.At -> offset = state.offset
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("Upload ended unexpectedly")
    }

    private sealed interface Progress {
        data class At(val offset: Long) : Progress
        data class Done(val json: JSONObject) : Progress
    }

    private fun queryProgress(session: String, total: Long): Progress {
        val request = Request.Builder().url(session)
            .put(ByteArray(0).toRequestBody(null))
            .header("Content-Range", "bytes */$total")
            .build()
        http.newCall(request).execute().use { r ->
            return when (r.code) {
                200, 201 -> Progress.Done(JSONObject(r.body!!.string()))
                308 -> Progress.At(nextOffset(r))
                else -> throw IOException("Could not resume upload: ${r.code}")
            }
        }
    }

    private fun nextOffset(r: Response): Long =
        r.header("Range")?.substringAfter("-")?.toLongOrNull()?.plus(1) ?: 0L

    private suspend fun call(builder: Request.Builder): Response {
        val base = builder.build()
        var response = http.newCall(
            base.newBuilder().header("Authorization", "Bearer ${tokenProvider(false)}").build()
        ).execute()
        if (response.code == 401) {
            response.close()
            response = http.newCall(
                base.newBuilder().header("Authorization", "Bearer ${tokenProvider(true)}").build()
            ).execute()
        }
        if (!response.isSuccessful) {
            val message = response.body?.string().orEmpty()
            val code = response.code
            response.close()
            throw DriveApiException(code, driveErrorMessage(code, message))
        }
        return response
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val CHUNK = 8L * 1024 * 1024
        const val MAX_RETRIES = 5
    }
}