package ir.pcpapc172.gdlauncher1.patch

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

private val signingLock = Any()

/** A private per-install key, backed up only through an explicit password-protected export. */
class SigningIdentity(context: Context) {
    private val file = File(context.filesDir, "signing/identity.p12")
    private val prefs = context.getSharedPreferences("signing", Context.MODE_PRIVATE)

    private fun load(): Pair<PrivateKey, X509Certificate> =
        synchronized(signingLock) {
            file.parentFile!!.mkdirs()
            val password =
                prefs.getString("password", null)
                    ?: java.util.UUID.randomUUID().toString().also {
                        prefs.edit().putString("password", it).commit()
                    }
            val store = KeyStore.getInstance("PKCS12")
            if (file.exists()) file.inputStream().use { store.load(it, password.toCharArray()) }
            else {
                store.load(null, null)
                val key =
                    KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
                val now = System.currentTimeMillis()
                val subject = X500Name("CN=GD Instance Local Signing")
                val builder =
                    JcaX509v3CertificateBuilder(
                        subject,
                        BigInteger(128, SecureRandom()),
                        Date(now - 86400000),
                        Date(now + 30L * 365 * 86400000),
                        subject,
                        key.public,
                    )
                val provider = BouncyCastleProvider()
                val cert =
                    JcaX509CertificateConverter()
                        .setProvider(provider)
                        .getCertificate(
                            builder.build(
                                JcaContentSignerBuilder("SHA256withRSA")
                                    .setProvider(provider)
                                    .build(key.private)
                            )
                        )
                store.setKeyEntry("instance", key.private, password.toCharArray(), arrayOf(cert))
                val temp = File(file.parentFile, "identity.tmp")
                temp.outputStream().use { store.store(it, password.toCharArray()) }
                check(temp.renameTo(file))
            }
            (store.getKey("instance", password.toCharArray()) as PrivateKey) to
                (store.getCertificate("instance") as X509Certificate)
        }

    fun sign(input: File, output: File) {
        val (key, cert) = load()
        val config = ApkSigner.SignerConfig.Builder("instance", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        val verification =
            ApkVerifier.Builder(output).setMinCheckedPlatformVersion(24).build().verify()
        check(verification.isVerified) { "Signed APK verification failed: ${verification.errors}" }
    }

    fun export(password: CharArray): ByteArray {
        require(password.size >= 12) { "Use a password of at least 12 characters" }
        val (key, cert) = load()
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("instance", key, password, arrayOf(cert))
        return java.io.ByteArrayOutputStream().use {
            store.store(it, password)
            it.toByteArray()
        }
    }

    fun restore(bytes: ByteArray, password: CharArray) {
        val store = KeyStore.getInstance("PKCS12")
        bytes.inputStream().use { store.load(it, password) }
        require(
            store.getKey("instance", password) is PrivateKey &&
                store.getCertificate("instance") is X509Certificate
        ) {
            "Not a launcher signing backup"
        }
        val proof = java.security.Signature.getInstance("SHA256withRSA")
        proof.initSign(store.getKey("instance", password) as PrivateKey)
        proof.update(byteArrayOf(1, 2, 3))
        val signature = proof.sign()
        proof.initVerify(store.getCertificate("instance"))
        proof.update(byteArrayOf(1, 2, 3))
        require(proof.verify(signature)) { "Signing key does not match its certificate" }
        file.parentFile!!.mkdirs()
        val temp = File(file.parentFile, "restore.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(file))
        prefs.edit().putString("password", String(password)).commit()
    }
}
