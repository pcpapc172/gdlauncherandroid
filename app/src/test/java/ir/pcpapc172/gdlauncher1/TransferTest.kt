package ir.pcpapc172.gdlauncher1

import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferTest {
    @get:Rule val dir = TemporaryFolder()

    private fun response(data: ByteArray, code: Int = 200, contentRange: String? = null): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.com/game.apk").build())
            .protocol(Protocol.HTTP_1_1).message("Fixture").code(code)
            .header("ETag", "\"same-release\"")
            .apply { if (contentRange != null) header("Content-Range", contentRange) }
            .body(data.toResponseBody()).build()

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }

    @Test fun pausedTransferResumesFromDisk() {
        val apk = ByteArray(96_000) { (it % 255).toByte() }
        val partial = File(dir.root, "paused.part")
        val out = File(dir.root, "game.apk")
        var stopped = false
        try {
            Network.transfer(out, sha(apk), { stopped }, partial, { _, _, _, _ -> stopped = true }) {
                assertFalse(it.containsKey("Range"))
                response(apk)
            }
            fail("expected pause")
        } catch (_: InterruptedIOException) {
            assertFalse(out.exists())
        }
        val offset = partial.length().toInt()
        assertTrue(offset in 1 until apk.size)
        Network.transfer(out, sha(apk), { false }, partial, { _, _, _, _ -> }) {
            assertEquals("bytes=$offset-", it["Range"])
            assertEquals("\"same-release\"", it["If-Range"])
            response(apk.copyOfRange(offset, apk.size), 206, "bytes $offset-${apk.lastIndex}/${apk.size}")
        }
        assertArrayEquals(apk, out.readBytes())
        assertFalse(partial.exists())
    }

    @Test fun serverCanRestartRangeButCannotInstallBadChecksum() {
        val partial = File(dir.root, "paused.part").apply { writeText("stale") }
        val out = File(dir.root, "game.apk")
        val bytes = "fresh".toByteArray()
        Network.transfer(out, sha(bytes), { false }, partial, { _, _, _, _ -> }) {
            assertEquals("bytes=5-", it["Range"])
            response(bytes)
        }
        assertArrayEquals(bytes, out.readBytes())
        try {
            Network.transfer(File(dir.root, "invalid.apk"), sha(bytes), { false }, partial,
                { _, _, _, _ -> }) { response("wrong".toByteArray()) }
            fail("expected checksum failure")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Download checksum mismatch", expected.message)
            assertFalse(partial.exists())
            assertFalse(File(dir.root, "invalid.apk").exists())
        }
    }
}
