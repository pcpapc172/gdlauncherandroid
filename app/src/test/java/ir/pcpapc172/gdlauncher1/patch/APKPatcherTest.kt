package ir.pcpapc172.gdlauncher1.patch

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.*
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class APKPatcherTest {
    private val game = "com.robtopx.geometryjump"
    private val target = "ir.gd.i0123456789abcdef0"

    @Test
    fun refusesInflationBeyondDeclaredEntrySize() {
        val dir = kotlin.io.path.createTempDirectory("apk-bounds").toFile()
        try {
            val input = File(dir, "input.apk")
            ZipOutputStream(input.outputStream()).use { zip ->
                for ((name, bytes) in
                    mapOf(
                        "AndroidManifest.xml" to BinaryPatchTest.fixture(true, game),
                        "assets/oversized.bin" to game.toByteArray(),
                    )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val corrupt = input.readBytes()
            val b = ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN)
            for (p in 0 until corrupt.size - 46) if (b.getInt(p) == 0x02014b50) {
                val length = b.getShort(p + 28).toInt() and 65535
                if (String(corrupt, p + 46, length, Charsets.UTF_8) == "assets/oversized.bin")
                    b.putInt(p + 24, 1)
            }
            input.writeBytes(corrupt)
            val output = File(dir, "output.apk")
            assertThrows(IllegalArgumentException::class.java) {
                APKPatcher { _, _ -> error("Must not sign") }.patch(input, output, target, "Clone")
            }
            assertFalse(output.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rebuildsDexPatchesEveryAbiAndAlignsNativeEntries() {
        val dir = kotlin.io.path.createTempDirectory("apk-test").toFile()
        try {
            val source = File(dir, "source.apk")
            val output = File(dir, "patched.apk")
            val field =
                ImmutableField(
                    "Lsample/Game;",
                    "TARGET",
                    "Ljava/lang/String;",
                    25,
                    ImmutableStringEncodedValue(game),
                    emptyList(),
                    emptySet(),
                )
            val definition =
                ImmutableClassDef(
                    "Lsample/Game;",
                    1,
                    "Ljava/lang/Object;",
                    emptyList(),
                    null,
                    emptyList(),
                    listOf(field),
                    emptyList(),
                )
            val dex = MemoryDataStore()
            DexPool.writeTo(dex, ImmutableDexFile(Opcodes.getDefault(), listOf(definition)))
            ZipOutputStream(source.outputStream()).use { zip ->
                val entries =
                    linkedMapOf(
                        "AndroidManifest.xml" to BinaryPatchTest.fixture(true, game),
                        "classes.dex" to dex.data,
                        "resources.arsc" to game.toByteArray(),
                        "META-INF/OLD.SF" to byteArrayOf(1),
                    )
                for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) for (lib in
                    listOf("libcocos2d.so", "libcocos2dcpp.so")) entries["lib/$abi/$lib"] =
                    ("padding/$game/$game").toByteArray()
                entries["assets/$game/path.txt"] = game.toByteArray()
                entries["assets/deep.bin"] = ("x".repeat(65530) + game).toByteArray()
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            APKPatcher { input, out ->
                    input.copyTo(out, true)
                    Unit
                }
                .patch(source, output, target, "Clone")
            ZipFile(output).use { zip ->
                assertNull(zip.getEntry("META-INF/OLD.SF"))
                assertNotNull(zip.getEntry("assets/$target/path.txt"))
                assertNull(zip.getEntry("assets/$game/path.txt"))
                for (entry in zip.entries()) {
                    val data = zip.getInputStream(entry).readBytes()
                    assertFalse("Unpatched ${entry.name}", BinaryPatch.contains(data, game))
                    if (entry.name.endsWith(".so")) assertEquals(ZipEntry.STORED, entry.method)
                }
                val bytes = zip.getInputStream(zip.getEntry("classes.dex")).readBytes()
                val rebuilt =
                    DexBackedDexFile.fromInputStream(
                        Opcodes.getDefault(),
                        bytes.inputStream().buffered(),
                    )
                assertEquals(
                    target,
                    (rebuilt.classes.single().staticFields.single().initialValue
                            as StringEncodedValue)
                        .value,
                )
                assertArrayEquals(
                    bytes.copyOfRange(12, 32),
                    MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size)),
                )
                val checksum = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value
                assertEquals(
                    checksum,
                    ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and
                        0xffffffffL,
                )
            }
            val bytes = output.readBytes()
            for (p in 0 until bytes.size - 30) if (
                bytes[p] == 0x50.toByte() &&
                    bytes[p + 1] == 0x4b.toByte() &&
                    bytes[p + 2] == 3.toByte() &&
                    bytes[p + 3] == 4.toByte()
            ) {
                val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val len = header.getShort(p + 26).toInt() and 65535
                val extra = header.getShort(p + 28).toInt() and 65535
                val name = String(bytes, p + 30, len, Charsets.UTF_8)
                if (name.endsWith(".so")) assertEquals(0, (p + 30 + len + extra) % 16384)
                if (name == "resources.arsc") assertEquals(0, (p + 30 + len + extra) % 4)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancelledPatchingDoesNotPublishOutput() {
        val dir = kotlin.io.path.createTempDirectory("apk-cancel").toFile()
        try {
            val input = File(dir, "input.apk")
            ZipOutputStream(input.outputStream()).use {
                it.putNextEntry(ZipEntry("AndroidManifest.xml"))
                it.write(BinaryPatchTest.fixture(true, game))
                it.closeEntry()
            }
            val output = File(dir, "output.apk")
            assertThrows(IllegalStateException::class.java) {
                APKPatcher { _, _ -> error("Must not sign") }
                    .patch(input, output, target, "Clone", cancelled = { true })
            }
            assertFalse(output.exists())
            assertFalse(File(dir, "output.apk.unsigned").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Optional real release fixture supplied by the validation environment; no game APK is
     * redistributed.
     */
    @Test
    fun retargetsAndSignsRealGeodeFixture() {
        val path = System.getenv("GD_GEODE_FIXTURE")
        assumeTrue("Set GD_GEODE_FIXTURE to a downloaded upstream launcher APK", path != null)
        val input = File(path!!)
        val dir = kotlin.io.path.createTempDirectory("geode-test").toFile()
        try {
            val key =
                java.security.KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(2048) }
                    .generateKeyPair()
            val provider = org.bouncycastle.jce.provider.BouncyCastleProvider()
            val name = org.bouncycastle.asn1.x500.X500Name("CN=Ephemeral test key")
            val cert =
                org.bouncycastle.cert.jcajce
                    .JcaX509CertificateConverter()
                    .setProvider(provider)
                    .getCertificate(
                        org.bouncycastle.cert.jcajce
                            .JcaX509v3CertificateBuilder(
                                name,
                                java.math.BigInteger.ONE,
                                java.util.Date(0),
                                java.util.Date(System.currentTimeMillis() + 86400000),
                                name,
                                key.public,
                            )
                            .build(
                                org.bouncycastle.operator.jcajce
                                    .JcaContentSignerBuilder("SHA256withRSA")
                                    .setProvider(provider)
                                    .build(key.private)
                            )
                    )
            val output = File(dir, "geode.apk")
            APKPatcher { source, out ->
                    ApkSigner.Builder(
                            listOf(
                                ApkSigner.SignerConfig.Builder("test", key.private, listOf(cert))
                                    .build()
                            )
                        )
                        .setInputApk(source)
                        .setOutputApk(out)
                        .setMinSdkVersion(24)
                        .setV1SigningEnabled(true)
                        .setV2SigningEnabled(true)
                        .setV3SigningEnabled(true)
                        .build()
                        .sign()
                }
                .patch(input, output, target, "Test game · Geode", "gd.g0123456789abcd")
            assertTrue(
                ApkVerifier.Builder(output)
                    .setMinCheckedPlatformVersion(24)
                    .build()
                    .verify()
                    .isVerified
            )
            ZipFile(output).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
                assertEquals("gd.g0123456789abcd", BinaryPatch.packageName(manifest))
                assertTrue(BinaryPatch.contains(manifest, "com.geode.launcher.MainActivity"))
                assertTrue(BinaryPatch.contains(manifest, "gd.g0123456789abcd.fileprovider"))
                for (entry in zip.entries()) if (entry.name.endsWith(".dex")) {
                    val data = zip.getInputStream(entry).readBytes()
                    assertFalse(BinaryPatch.contains(data, game))
                    DexBackedDexFile.fromInputStream(
                            Opcodes.getDefault(),
                            data.inputStream().buffered(),
                        )
                        .classes
                        .forEach { clazz ->
                            clazz.methods.forEach { method ->
                                method.implementation?.instructions?.forEach { it.opcode }
                            }
                        }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
