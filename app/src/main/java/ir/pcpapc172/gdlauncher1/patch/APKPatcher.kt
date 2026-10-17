package ir.pcpapc172.gdlauncher1.patch

import android.content.Context
import ir.pcpapc172.gdlauncher1.GAME_PACKAGE
import ir.pcpapc172.gdlauncher1.Validation
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool

/** No shell commands or on-device compiler. Every supported payload is rebuilt and re-signed. */
class APKPatcher(private val sign: (File, File) -> Unit) {
    constructor(
        context: Context
    ) : this({ input, output -> SigningIdentity(context).sign(input, output) })

    fun patch(
        input: File,
        output: File,
        target: String,
        label: String,
        geodeIdentity: String? = null,
        cancelled: () -> Boolean = { false },
    ) =
        patchLock.withLock {
            require(Validation.packageName(target) && target.length == GAME_PACKAGE.length) {
                "Target must be a valid 24-character package"
            }
            require(
                geodeIdentity == null ||
                    Validation.packageName(geodeIdentity) &&
                        geodeIdentity.length == 18 &&
                        geodeIdentity != "com.geode.launcher"
            ) {
                "Invalid Geode identity"
            }
            require(target != GAME_PACKAGE) { "Do not overwrite the original game" }
            val unsigned = File(output.parentFile, "${output.name}.unsigned")
            output.parentFile!!.mkdirs()
            var manifestSeen = false
            var totalBytes = 0L
            try {
                ZipFile(input).use { zip ->
                    val expanded = zip.entries().asSequence().sumOf { it.size.coerceAtLeast(0) }
                    require(expanded <= 2L * 1024 * 1024 * 1024) { "APK expands beyond 2 GiB" }
                    require(output.parentFile!!.usableSpace > expanded * 3 + 32L * 1024 * 1024) {
                        "Not enough storage to rebuild this APK"
                    }
                    val counter = CountingOutput(unsigned.outputStream())
                    ZipOutputStream(counter).use { out ->
                        val names = mutableSetOf<String>()
                        for (entry in zip.entries()) {
                            check(!cancelled()) { "Cancelled" }
                            val name = entry.name
                            val outputName =
                                name
                                    .replace(GAME_PACKAGE, target)
                                    .replace(
                                        GAME_PACKAGE.replace('.', '/'),
                                        target.replace('.', '/'),
                                    )
                            require(names.add(outputName)) {
                                "Duplicate APK entry after retargeting: $name"
                            }
                            require(!name.startsWith('/') && name.split('/').none { it == ".." }) {
                                "Unsafe APK entry"
                            }
                            if (entry.isDirectory || signature(name)) continue
                            require(entry.size in 0..512L * 1024 * 1024) {
                                "APK entry is too large: $name"
                            }
                            totalBytes += entry.size
                            require(totalBytes <= 2L * 1024 * 1024 * 1024) {
                                "APK expands beyond 2 GiB"
                            }
                            val structured =
                                name == "AndroidManifest.xml" ||
                                    name.endsWith(".dex") ||
                                    name == "resources.arsc" ||
                                    name.endsWith(".xml")
                            val staged = File(output.parentFile, "entry.tmp")
                            require(
                                !structured ||
                                    entry.size <=
                                        minOf(
                                            128L * 1024 * 1024,
                                            Runtime.getRuntime().maxMemory() / 3,
                                        )
                            ) {
                                "Not enough memory to rebuild $name"
                            }
                            var data =
                                if (structured)
                                    zip.getInputStream(entry).use { readLimited(it, entry.size) }
                                else null
                            if (!structured)
                                zip.getInputStream(entry).use { stream ->
                                    staged.outputStream().buffered().use { sink ->
                                        BinaryPatch.replaceStream(
                                            BoundedInput(stream, entry.size),
                                            sink,
                                            GAME_PACKAGE,
                                            target,
                                            name.endsWith(".so"),
                                            java.util.function.BooleanSupplier { cancelled() },
                                        )
                                    }
                                }
                            if (data != null) {
                                var patched: ByteArray = data
                                require(
                                    !name.endsWith(".so") ||
                                        !BinaryPatch.contains(
                                            patched,
                                            GAME_PACKAGE.replace('.', '_'),
                                        )
                                ) {
                                    "Native JNI symbols need a full rebuild; refusing unsafe symbol-table edits"
                                }
                                if (name == "AndroidManifest.xml") {
                                    manifestSeen = true
                                    patched =
                                        if (geodeIdentity == null)
                                            BinaryPatch.manifest(
                                                patched,
                                                GAME_PACKAGE,
                                                target,
                                                label,
                                                false,
                                            )
                                        else
                                            BinaryPatch.manifest(
                                                patched,
                                                "com.geode.launcher",
                                                geodeIdentity,
                                                label,
                                                true,
                                            )
                                }
                                BinaryPatch.replaceInPlace(patched, GAME_PACKAGE, target)
                                BinaryPatch.replaceInPlace(
                                    patched,
                                    GAME_PACKAGE.replace('.', '/'),
                                    target.replace('.', '/'),
                                )
                                BinaryPatch.replaceInPlace(
                                    patched,
                                    GAME_PACKAGE.replace('.', '_'),
                                    target.replace('.', '_'),
                                )
                                if (geodeIdentity != null && name.endsWith(".dex")) {
                                    // Keep descriptors/JNI class names intact. Only the runtime
                                    // application identity changes.
                                    patched = replaceIdentityConstants(patched, geodeIdentity)
                                }
                                if (name.endsWith(".dex")) {
                                    require(
                                        patched.size >= 112 &&
                                            String(patched, 0, 4, Charsets.US_ASCII) == "dex\n"
                                    ) {
                                        "Unsupported DEX format"
                                    }
                                    val dex =
                                        DexBackedDexFile.fromInputStream(
                                            Opcodes.getDefault(),
                                            patched.inputStream().buffered(),
                                        )
                                    val store = MemoryDataStore()
                                    DexPool.writeTo(store, dex)
                                    patched = store.data.copyOf(store.size)
                                    store.close()
                                }
                                check(
                                    !BinaryPatch.contains(patched, GAME_PACKAGE) &&
                                        !BinaryPatch.contains(
                                            patched,
                                            GAME_PACKAGE.replace('.', '/'),
                                        )
                                ) {
                                    "Unpatched game reference in $name"
                                }
                                data = patched
                            }
                            val bytesSize = data?.size?.toLong() ?: staged.length()
                            // Store and align native libraries to 16 KiB, resources to 4 bytes.
                            // This supports extractNativeLibs=false.
                            val stored = name.endsWith(".so") || name == "resources.arsc"
                            val next = ZipEntry(outputName).apply { time = 0L }
                            if (stored) {
                                next.method = ZipEntry.STORED
                                next.size = bytesSize
                                val crc = CRC32()
                                if (data != null) crc.update(data)
                                else
                                    staged.inputStream().use { stream ->
                                        val b = ByteArray(65536)
                                        while (true) {
                                            val n = stream.read(b)
                                            if (n < 0) break
                                            crc.update(b, 0, n)
                                        }
                                    }
                                next.crc = crc.value
                                // ZipOutputStream adds a 9-byte timestamp extra when time is before
                                // 1980. Use DOS epoch instead.
                                next.time = 315619200000L
                                val align = if (name.endsWith(".so")) 16384 else 4
                                val base =
                                    counter.count + 30 + outputName.toByteArray(Charsets.UTF_8).size
                                var padding = ((align - base % align) % align).toInt()
                                if (padding in 1..3) padding += align
                                if (padding > 0)
                                    next.extra =
                                        ByteArray(padding).also {
                                            it[0] = 0x35
                                            it[1] = 0xd9.toByte()
                                            it[2] = ((padding - 4) and 255).toByte()
                                            it[3] = ((padding - 4) shr 8).toByte()
                                        }
                            }
                            out.putNextEntry(next)
                            if (data != null) out.write(data)
                            else
                                staged.inputStream().use { stream ->
                                    val b = ByteArray(65536)
                                    while (true) {
                                        check(!cancelled()) { "Cancelled" }
                                        val n = stream.read(b)
                                        if (n < 0) break
                                        out.write(b, 0, n)
                                    }
                                }
                            out.closeEntry()
                            staged.delete()
                        }
                    }
                }
                require(manifestSeen) { "APK has no manifest" }
                check(!cancelled()) { "Cancelled" }
                sign(unsigned, output)
            } catch (e: Exception) {
                output.delete()
                throw e
            } finally {
                unsigned.delete()
                File(output.parentFile, "entry.tmp").delete()
            }
        }

    private fun replaceIdentityConstants(input: ByteArray, identity: String): ByteArray {
        // Match a complete MUTF-8 string, not class names that share the same prefix.
        var data = input
        for (suffix in
            listOf("", ".user", ".fileprovider", ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")) {
            val old = "com.geode.launcher$suffix\u0000"
            val new = "$identity$suffix\u0000"
            BinaryPatch.replaceInPlace(data, old, new)
        }
        return data
    }

    private fun signature(name: String): Boolean {
        val n = name.uppercase(java.util.Locale.ROOT)
        return n == "META-INF/MANIFEST.MF" ||
            n.startsWith("META-INF/") &&
                (n.endsWith(".SF") ||
                    n.endsWith(".RSA") ||
                    n.endsWith(".DSA") ||
                    n.endsWith(".EC") ||
                    n.substringAfter("META-INF/").startsWith("SIG-"))
    }

    private fun readLimited(input: java.io.InputStream, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val b = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = input.read(b)
            if (n < 0) break
            total += n
            require(total <= size && total <= 512L * 1024 * 1024) {
                "APK entry exceeds declared size"
            }
            out.write(b, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private val patchLock = java.util.concurrent.locks.ReentrantLock()
    }

    private class BoundedInput(private val source: java.io.InputStream, private val limit: Long) :
        java.io.InputStream() {
        private var count = 0L

        override fun read(): Int {
            val result = source.read()
            if (result >= 0) {
                count++
                require(count <= limit) { "ZIP entry exceeds its declared size" }
            }
            return result
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val result = source.read(buffer, offset, length)
            if (result > 0) {
                count += result
                require(count <= limit) { "ZIP entry exceeds its declared size" }
            }
            return result
        }
    }

    private class CountingOutput(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
