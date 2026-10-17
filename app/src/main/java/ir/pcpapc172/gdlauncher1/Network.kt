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

    private fun response(url: String): okhttp3.Response {
        require(Validation.https(url)) { "Only HTTPS URLs are accepted" }
        val response =
            client
                .newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "GDInstanceLauncher/2.1")
                        .build()
                )
                .execute()
        if (!response.isSuccessful) {
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
        progress: (Int, Long, Long, Long) -> Unit = { _, _, _, _ -> },
    ) {
        response(url).use { r ->
            val body = r.body ?: error("Empty download")
            val total = body.contentLength()
            val max = 2L * 1024 * 1024 * 1024
            require(total <= max) { "Download exceeds 2 GiB" }
            out.parentFile!!.mkdirs()
            require(
                out.parentFile!!.usableSpace > maxOf(total, 64L * 1024 * 1024) + 32L * 1024 * 1024
            ) {
                "Not enough free storage"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var last = -2
            val started = System.nanoTime()
            var lastUpdate = 0L
            try {
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val b = ByteArray(65536)
                        while (true) {
                            check(!cancelled()) { "Cancelled" }
                            val n = input.read(b)
                            if (n < 0) break
                            received += n
                            require(received <= max) { "Download too large" }
                            output.write(b, 0, n)
                            digest.update(b, 0, n)
                            val percent =
                                if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100)
                                else -1
                            val now = System.nanoTime()
                            if (percent != last || now - lastUpdate >= 1_000_000_000L) {
                                progress(
                                    percent,
                                    received,
                                    total,
                                    (received * 1_000_000_000.0 /
                                            (now - started).coerceAtLeast(1L) /
                                            1024)
                                        .toLong(),
                                )
                                last = percent
                                lastUpdate = now
                            }
                        }
                    }
                }
                require(total < 0 || received == total) { "Incomplete download" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(sha256 == null || actual.equals(sha256, true)) {
                    "Download checksum mismatch"
                }
            } catch (e: Exception) {
                out.delete()
                throw e
            }
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
