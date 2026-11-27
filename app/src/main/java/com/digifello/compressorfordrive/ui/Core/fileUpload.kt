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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UploadResult(val fileId: String, val size: Long, val md5: String?)

class UploadVerificationException(message: String) : IOException(message)

class DriveUploader(
    private val tokenProvider: suspend () -> String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun replaceFile(
        fileId: String,
        local: File,
        newName: String,
        newMimeType: String,
        onProgress: (uploaded: Long) -> Unit = {},
    ): UploadResult = withContext(Dispatchers.IO) {
        val total = local.length()
        require(total > 0) { "Empty file" }

        val session = startSession(fileId, total, newName, newMimeType)
        val json = uploadChunks(session, local, total, onProgress)

        val driveMd5 = json.optString("md5Checksum").ifEmpty { null }
        val localMd5 = md5(local)
        if (driveMd5 != null && !driveMd5.equals(localMd5, ignoreCase = true)) {
            throw UploadVerificationException("Uploaded file does not match. Old version was kept.")
        }
        UploadResult(
            fileId = json.getString("id"),
            size = json.optString("size").toLongOrNull() ?: total,
            md5 = driveMd5,
        )
    }

    suspend fun deleteOldRevisions(fileId: String): Int = withContext(Dispatchers.IO) {
        val revisions = mutableListOf<Pair<String, String>>()
        var pageToken: String? = null
        do {
            val url = "$API/files/$fileId/revisions".toHttpUrl().newBuilder()
                .addQueryParameter("fields", "nextPageToken,revisions(id,modifiedTime)")
                .addQueryParameter("pageSize", "200")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = call(Request.Builder().url(url).get()).use { JSONObject(it.body!!.string()) }
            json.optJSONArray("revisions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    revisions += r.getString("id") to r.optString("modifiedTime")
                }
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)

        if (revisions.size <= 1) return@withContext 0
        val newest = revisions.maxByOrNull { it.second }!!.first

        var deleted = 0
        for ((id, _) in revisions) {
            if (id == newest) continue
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
            .put("appProperties", JSONObject().put(DriveApi.PROP_COMPRESSED, state))
            .toString()
        call(
            Request.Builder().url("$API/files/$fileId")
                .patch(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        ).close()
    }

    private suspend fun startSession(fileId: String, total: Long, name: String, mime: String): String {
        val url = "$UPLOAD/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id,size,md5Checksum")
            .build()
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", mime)
            .put("appProperties", JSONObject().put(DriveApi.PROP_COMPRESSED, DriveApi.STATE_PENDING))
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
                            else -> throw DriveApiException(r.code, "Upload failed ${r.code}: ${r.body?.string()}")
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
        var response = http.newCall(base.newBuilder().header("Authorization", "Bearer ${tokenProvider()}").build()).execute()
        if (response.code == 401) {
            response.close()
            response = http.newCall(base.newBuilder().header("Authorization", "Bearer ${tokenProvider()}").build()).execute()
        }
        if (!response.isSuccessful) {
            val message = response.body?.string().orEmpty()
            response.close()
            throw DriveApiException(response.code, "Drive API ${response.code}: $message")
        }
        return response
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val CHUNK = 8L * 1024 * 1024
        const val MAX_RETRIES = 5
    }
}