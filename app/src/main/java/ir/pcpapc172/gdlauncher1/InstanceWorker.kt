package ir.pcpapc172.gdlauncher1

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.android.apksig.ApkVerifier
import com.google.gson.Gson
import ir.pcpapc172.gdlauncher1.patch.APKPatcher
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class InstanceWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(
                NotificationChannel(
                    "operations",
                    applicationContext.getString(R.string.operations),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        val notification =
            NotificationCompat.Builder(applicationContext, "operations")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(applicationContext.getString(R.string.operations))
                .setOngoing(true)
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        applicationContext,
                        0,
                        android.content.Intent(applicationContext, MainActivity::class.java),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .addAction(
                    android.R.drawable.ic_delete,
                    applicationContext.getString(R.string.cancel),
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
                )
                .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(
                id.hashCode(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        else ForegroundInfo(id.hashCode(), notification)
    }

    override suspend fun doWork(): Result =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var staging: File? = null
            try {
                setForeground(getForegroundInfo())
                val instance =
                    Gson()
                        .fromJson(
                            inputData.getString("instance") ?: error("Missing instance"),
                            Instance::class.java,
                        )
                Validation.check(instance)
                val store = InstanceStore(applicationContext)
                val root = store.root(instance.id)
                staging = File(applicationContext.filesDir, "staging/$id").also { it.mkdirs() }
                val work = staging!!
                fun status(text: String, percent: Int = -1) {
                    setProgressAsync(workDataOf("status" to text, "percent" to percent))
                }
                if (instance.id == "launcher_update") {
                    val apk = File(work, "game/base.apk")
                    status("Downloading launcher update")
                    Network.download(instance.url!!, apk, instance.sha256, { isStopped }) {
                        percent,
                        bytes,
                        total,
                        speed ->
                        status(
                            "Downloading launcher update\n" +
                                applicationContext.getString(
                                    R.string.download_metrics,
                                    bytes / 1024 / 1024,
                                    if (total > 0) (total / 1024 / 1024).toString() else "?",
                                    speed,
                                ),
                            percent,
                        )
                    }
                    verify(apk, applicationContext.packageName, instance.signerSha256)
                    verifySameSigner(apk, applicationContext.packageName)
                } else if (!instance.url.isNullOrBlank()) {
                    val apk = File(work, "game/base.apk")
                    status("Downloading APK")
                    Network.download(instance.url, apk, instance.sha256, { isStopped }) {
                        percent,
                        bytes,
                        total,
                        speed ->
                        status(
                            "Downloading APK\n" +
                                applicationContext.getString(
                                    R.string.download_metrics,
                                    bytes / 1024 / 1024,
                                    if (total > 0) (total / 1024 / 1024).toString() else "?",
                                    speed,
                                ),
                            percent,
                        )
                    }
                    verify(apk, instance.pkg, instance.signerSha256)
                } else {
                    val sources =
                        if (instance.mode == "apk") listOf(store.source(instance.id))
                        else {
                            val app =
                                applicationContext.packageManager.getApplicationInfo(
                                    GAME_PACKAGE,
                                    0,
                                )
                            listOf(File(app.sourceDir)) +
                                (app.splitSourceDirs?.map(::File) ?: emptyList())
                        }
                    require(sources.all { it.isFile }) { "Source APK is missing; select it again" }
                    require(
                        work.usableSpace > sources.sumOf { it.length() } * 3 + 64L * 1024 * 1024
                    ) {
                        "Not enough storage to rebuild APKs"
                    }
                    require(
                        sources.any { source ->
                            java.util.zip.ZipFile(source).use { zip ->
                                zip.entries().asSequence().any {
                                    it.name.matches(Regex("lib/[^/]+/libcocos2d(?:cpp)?\\.so"))
                                }
                            }
                        }
                    ) {
                        "Game native libraries are missing. Use the complete installed game or a standalone APK."
                    }
                    val patcher = APKPatcher(applicationContext)
                    sources.forEachIndexed { index, source ->
                        status("Patching game APK ${index+1}/${sources.size}")
                        verify(source, GAME_PACKAGE, null)
                        val output =
                            File(work, "game/${if(index==0)"base" else "split_$index"}.apk")
                        patcher.patch(
                            source,
                            output,
                            instance.pkg,
                            instance.name,
                            cancelled = { isStopped },
                        )
                        verify(output, instance.pkg, null)
                    }
                }
                if (instance.usesGeode) {
                    status("Finding latest Geode launcher")
                    val release =
                        if (instance.geodeUrl == "latest")
                            Network.latestGeode(
                                prefer32 =
                                    Build.SUPPORTED_64_BIT_ABIS.isEmpty() ||
                                        File(work, "game").listFiles().orEmpty().none { apk ->
                                            java.util.zip.ZipFile(apk).use { zip ->
                                                zip.entries().asSequence().any {
                                                    it.name.startsWith("lib/arm64-v8a/") ||
                                                        it.name.startsWith("lib/x86_64/")
                                                }
                                            }
                                        }
                            )
                        else instance.geodeUrl!! to null
                    val source = File(work, "geode-source.apk")
                    Network.download(release.first, source, release.second, { isStopped }) {
                        percent,
                        bytes,
                        total,
                        speed ->
                        status(
                            "Downloading Geode launcher\n" +
                                applicationContext.getString(
                                    R.string.download_metrics,
                                    bytes / 1024 / 1024,
                                    if (total > 0) (total / 1024 / 1024).toString() else "?",
                                    speed,
                                ),
                            percent,
                        )
                    }
                    verify(source, "com.geode.launcher", null)
                    status("Retargeting Geode launcher")
                    val output = File(work, "geode/base.apk")
                    APKPatcher(applicationContext)
                        .patch(
                            source,
                            output,
                            instance.pkg,
                            "${instance.name} · Geode",
                            instance.geodePackage,
                            { isStopped },
                        )
                    verify(output, instance.geodePackage, null)
                    source.delete()
                }
                check(!isStopped) { "Cancelled" }
                File(work, "recipe.txt").writeText(store.recipe(instance))
                @Suppress("DEPRECATION")
                val preparedInfo =
                    applicationContext.packageManager.getPackageArchiveInfo(
                        File(work, "game/base.apk").path,
                        0,
                    )
                val preparedVersion =
                    if (Build.VERSION.SDK_INT >= 28) preparedInfo?.longVersionCode
                    else preparedInfo?.versionCode?.toLong()
                File(work, "version.txt").writeText((preparedVersion ?: 0L).toString())
                // Publish only complete, verified output. Keep previous output intact on any
                // earlier failure.
                val backup = File(root.parentFile, "${root.name}.previous")
                backup.deleteRecursively()
                root.parentFile!!.mkdirs()
                if (root.exists()) check(root.renameTo(backup)) { "Cannot replace previous output" }
                if (!work.renameTo(root)) {
                    backup.renameTo(root)
                    error("Cannot publish APK output")
                }
                backup.deleteRecursively()
                Result.success(workDataOf("status" to "Ready to install"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is IOException && runAttemptCount < 2 && !isStopped) {
                    setProgress(
                        workDataOf(
                            "status" to
                                applicationContext.getString(
                                    R.string.waiting_retry,
                                    e.message.orEmpty(),
                                ),
                            "percent" to -1,
                        )
                    )
                    Result.retry()
                } else
                    Result.failure(
                        workDataOf("error" to (e.message ?: e.javaClass.simpleName).take(1500))
                    )
            } finally {
                staging?.deleteRecursively()
            }
        }

    private fun verify(file: File, expected: String, signer: String?) {
        val result = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(24).build().verify()
        require(result.isVerified) { "APK signature verification failed" }
        val actual =
            java.util.zip.ZipFile(file).use { zip ->
                zip.getInputStream(
                        zip.getEntry("AndroidManifest.xml") ?: error("APK has no manifest")
                    )
                    .use {
                        ir.pcpapc172.gdlauncher1.patch.BinaryPatch.packageName(
                            it.readBytesLimited(8 * 1024 * 1024)
                        )
                    }
            }
        require(actual == expected) { "Expected $expected, received $actual" }
        if (signer != null)
            require(
                result.signerCertificates.any { certificate ->
                    MessageDigest.getInstance("SHA-256")
                        .digest(certificate.encoded)
                        .joinToString("") { "%02x".format(it) }
                        .equals(signer, true)
                }
            ) {
                "Unexpected APK signer"
            }
    }

    @Suppress("DEPRECATION")
    private fun verifySameSigner(file: File, pkg: String) {
        val flags = PackageManager.GET_SIGNATURES
        val installed =
            applicationContext.packageManager
                .getPackageInfo(pkg, flags)
                .signatures
                .map { it.toByteArray().toList() }
                .toSet()
        val downloaded =
            applicationContext.packageManager
                .getPackageArchiveInfo(file.path, flags)!!
                .signatures
                .map { it.toByteArray().toList() }
                .toSet()
        require(installed == downloaded) {
            "Update signing certificate differs from the installed launcher"
        }
    }

    companion object {
        fun name(id: String) = "instance:$id"

        fun enqueue(context: Context, instance: Instance) {
            Validation.check(instance)
            val request =
                OneTimeWorkRequestBuilder<InstanceWorker>()
                    .setInputData(workDataOf("instance" to Gson().toJson(instance)))
                    .setConstraints(
                        Constraints.Builder()
                            .apply {
                                if (instance.usesGeode || !instance.url.isNullOrBlank())
                                    setRequiredNetworkType(NetworkType.CONNECTED)
                            }
                            .build()
                    )
                    .addTag("instances")
                    .addTag(name(instance.id))
                    .addTag("created:${System.currentTimeMillis()}")
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        java.util.concurrent.TimeUnit.SECONDS,
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(name(instance.id), ExistingWorkPolicy.KEEP, request)
        }
    }
}
