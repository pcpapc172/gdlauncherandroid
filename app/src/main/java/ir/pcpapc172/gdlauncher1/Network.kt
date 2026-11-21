package ir.pcpapc172.gdlauncher1

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object Network {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .followSslRedirects(false)
            .build()

    private fun response(url: String, additionalHeaders: Map<String, String> = emptyMap(),
                         rangeRequest: Boolean = false): okhttp3.Response {
        require(Validation.https(url)) { "Only HTTPS URLs are accepted" }
        val response =
            client
                .newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "GDInstanceLauncher/${BuildConfig.VERSION_NAME}")
                        .apply { additionalHeaders.forEach { (name, value) -> header(name, value) } }
                        .build()
                )
                .execute()
        if (!response.isSuccessful && !(rangeRequest && response.code == 416)) {
            val code = response.code
            response.close()
            if (code in 400..499 && code != 408 && code != 429)
                throw IllegalArgumentException("HTTP $code")
            throw IOException("HTTP $code")
        }
        return response
    }

    fun text(url: String): String =
        response(url).use { r ->
            val b = r.body ?: error("Empty response")
            require(b.contentLength() <= 1_000_000) { "Metadata too large" }
            b.byteStream().use { stream ->
                val bytes = stream.readBytesLimited(1_000_000)
                String(bytes, Charsets.UTF_8)
            }
        }

    fun download(
        url: String,
        out: File,
        sha256: String? = null,
        cancelled: () -> Boolean = { false },
        partial: File? = null,
        progress: (Int, Long, Long, Long) -> Unit = { _, _, _, _ -> },
    ) = transfer(out, sha256, cancelled, partial, progress) { headers ->
        response(url, headers, headers.containsKey("Range"))
    }

    internal fun transfer(
        out: File,
        sha256: String?,
        cancelled: () -> Boolean,
        partial: File?,
        progress: (Int, Long, Long, Long) -> Unit,
        open: (Map<String, String>) -> okhttp3.Response,
    ) {
        val file = partial ?: out
        val validator = partial?.let { File(it.path + ".etag") }
        var offset = partial?.length() ?: 0L
        val etag = validator?.takeIf { it.isFile }?.readText().orEmpty()
        if (sha256 == null && etag.isEmpty()) offset = 0
        val max = 2L * 1024 * 1024 * 1024
        check(!cancelled()) { "Cancelled" }
        try {
            fun request(at: Long): okhttp3.Response {
                val headers = mutableMapOf("Accept-Encoding" to "identity")
                if (at > 0) {
                    headers["Range"] = "bytes=$at-"
                    if (etag.isNotEmpty()) headers["If-Range"] = etag
                }
                return open(headers)
            }
            var download = request(offset)
            if (download.code == 416) {
                download.close()
                offset = 0
                download = request(0)
            }
            download.use { r ->
                val body = r.body ?: error("Empty download")
                val total = when (r.code) {
                    200 -> {
                        offset = 0
                        body.contentLength()
                    }
                    206 -> {
                        val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                            .matchEntire(r.header("Content-Range").orEmpty())
                            ?: error("Invalid download range")
                        val (start, last, length) = range.destructured
                        require(offset > 0 && start.toLong() == offset &&
                            last.toLong() == length.toLong() - 1 &&
                            (etag.isEmpty() || r.header("ETag") == etag)) {
                            "Server returned an incompatible download range"
                        }
                        length.toLong()
                    }
                    else -> error("Unexpected download response: HTTP ${r.code}")
                }
                require(total <= max && offset <= max) { "Download exceeds 2 GiB" }
                file.parentFile!!.mkdirs()
                val remaining = if (total >= 0) total - offset else 64L * 1024 * 1024
                require(file.parentFile!!.usableSpace > remaining + 32L * 1024 * 1024) {
                    "Not enough free storage"
                }
                if (offset == 0L && validator != null) {
                    validator.delete()
                    file.outputStream().close()
                    val strongETag = r.header("ETag")?.takeIf { it.firstOrNull() == '"' }
                    if (strongETag != null) validator.writeText(strongETag)
                }
                var received = offset
                var lastPercent = -2
                var lastUpdate = 0L
                val started = System.nanoTime()
                body.byteStream().use { input ->
                    java.io.FileOutputStream(file, offset > 0).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (cancelled()) throw java.io.InterruptedIOException("Download interrupted")
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            require(received <= max && (total < 0 || received <= total)) {
                                "Download is larger than expected"
                            }
                            output.write(buffer, 0, n)
                            val percent = if (total > 0) (received * 100 / total).toInt() else -1
                            val now = System.nanoTime()
                            if (percent != lastPercent || now - lastUpdate >= 1_000_000_000L) {
                                progress(percent, received, total,
                                    ((received - offset) * 1_000_000_000.0 /
                                        (now - started).coerceAtLeast(1L) / 1024).toLong())
                                lastPercent = percent
                                lastUpdate = now
                            }
                        }
                    }
                }
                if (total >= 0 && received != total) throw IOException("Incomplete download")
            }
            if (sha256 != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (cancelled()) throw java.io.InterruptedIOException("Verification interrupted")
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        digest.update(buffer, 0, bytes)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual.equals(sha256, true)) { "Download checksum mismatch" }
            }
            if (partial != null) {
                out.parentFile!!.mkdirs()
                check(file.renameTo(out)) { "Could not stage the downloaded APK" }
                validator?.delete()
            }
        } catch (e: Exception) {
            if (partial == null || e !is IOException) {
                file.delete()
                validator?.delete()
            }
            throw e
        }
    }

    fun latestGeode(prefer32: Boolean = false): Pair<String, String?> {
        val release =
            JSONObject(
                text("https://api.github.com/repos/geode-sdk/android-launcher/releases/latest")
            )
        val assets = release.getJSONArray("assets")
        val candidates =
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter {
                    it.getString("name").endsWith(".apk") &&
                        !it.getString("name").contains("play", true)
                }
        val asset =
            candidates.firstOrNull { it.getString("name").contains("universal", true) }
                ?: candidates
                    .filter { it.getString("name").contains("android32", true) == prefer32 }
                    .singleOrNull()
                ?: error("Geode release has no unambiguous compatible APK")
        val digest =
            asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        return asset.getString("browser_download_url") to digest
    }
}

fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val b = ByteArray(8192)
    while (true) {
        val n = read(b)
        if (n < 0) break
        require(out.size() + n <= max) { "File too large" }
        out.write(b, 0, n)
    }
    return out.toByteArray()
}
