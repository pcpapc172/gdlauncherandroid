package ir.pcpapc172.gdlauncher1

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.app.PendingIntent
import androidx.work.*
import com.android.apksig.ApkVerifier
import com.google.gson.Gson
import ir.pcpapc172.gdlauncher1.patch.APKPatcher
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class InstanceWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        notificationInfo("Preparing download", -1)

    private fun notificationInfo(status: String, percent: Int): ForegroundInfo {
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
                .setOnlyAlertOnce(true)
                .setContentText(status.lineSequence().first())
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .apply {
                    if (inputData.getBoolean("download", false))
                        addAction(android.R.drawable.ic_media_pause,
                            applicationContext.getString(R.string.pause_download), DownloadControl.command(applicationContext, inputData.getString("instance_id").orEmpty(), "pause_download"))
                }
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
                    DownloadControl.command(applicationContext, inputData.getString("instance_id").orEmpty(), "cancel_download"),
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

    @SuppressLint("MissingPermission") // Android 13+ permission is checked before posting.
    private fun notifyResult(instanceId: String, message: String, paused: Boolean = false) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = NotificationCompat.Builder(context, "operations")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.operations))
            .setContentText(message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply {
                if (paused) {
                    addAction(android.R.drawable.ic_media_play, context.getString(R.string.resume_download),
                        DownloadControl.command(context, instanceId, "resume_download"))
                    addAction(android.R.drawable.ic_delete, context.getString(R.string.cancel_download),
                        DownloadControl.command(context, instanceId, "cancel_download"))
                }
            }
            .build()
        context.getSystemService(NotificationManager::class.java).notify("download:$instanceId", 1, note)
    }

    override suspend fun doWork(): Result =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var staging: File? = null
            val instanceId = inputData.getString("instance_id").orEmpty()
            fun stopped() = isStopped || DownloadControl.stopped(applicationContext, instanceId) != null
            fun paused() = DownloadControl.paused(applicationContext, instanceId)
            try {
                setForeground(getForegroundInfo())
                val original =
                    Gson()
                        .fromJson(
                            inputData.getString("instance") ?: error("Missing instance"),
                            Instance::class.java,
                        )
                Validation.check(original)
                if (stopped()) {
                    if (paused()) notifyResult(instanceId, applicationContext.getString(R.string.download_paused), paused = true)
                    return@withContext Result.success()
                }
                val updating = inputData.getBoolean("update", false) || original.id == UpdateCatalog.LAUNCHER
                val instance = original.updateManifest?.takeIf { it.isNotBlank() && (updating || original.url.isNullOrBlank()) }
                    ?.let { UpdateCatalog.read(it, original) } ?: original
                if (updating) {
                    require(instance.versionCode != null && !instance.sha256.isNullOrBlank() &&
                        !instance.url.isNullOrBlank()) { "Update requires an APK, version code, and SHA-256" }
                    if (instance.versionCode <= installedVersion(instance.pkg))
                        return@withContext Result.success(workDataOf("status" to "Already up to date"))
                }
                val store = InstanceStore(applicationContext)
                val root = store.root(instance.id)
                val backup = File(root.parentFile, "${root.name}.previous")
                if (backup.exists() && !root.exists())
                    check(backup.renameTo(root)) { "Cannot restore previous APK output" }
                staging = File(applicationContext.filesDir, "staging/$id").also { it.mkdirs() }
                val work = staging
                var lastNotification = 0L
                fun status(text: String, percent: Int = -1) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastNotification >= 750 || percent == 100) {
                        setForegroundAsync(notificationInfo(text, percent))
                        lastNotification = now
                    }
                    setProgressAsync(workDataOf("status" to text, "percent" to percent))
                }
                fun download(url: String, apk: File, sha256: String?, title: String) {
                    status(title)
                    val partial = DownloadControl.partial(applicationContext, instance.id, url, sha256)
                    Network.download(url, apk, sha256, ::stopped, partial) { percent, bytes, total, speed ->
                        status(title + "\n" + applicationContext.getString(
                            R.string.download_metrics, bytes / 1024 / 1024,
                            if (total > 0) (total / 1024 / 1024).toString() else "?", speed
                        ), percent)
                    }
                }
                if (!instance.url.isNullOrBlank()) {
                    val apk = File(work, "game/base.apk")
                    val title = if (instance.id == UpdateCatalog.LAUNCHER) "Downloading launcher update"
                        else if (updating) "Downloading instance update" else "Downloading APK"
                    download(instance.url, apk, instance.sha256, title)
                    status("Verifying APK")
                    verify(apk, instance.pkg, instance.signerSha256)
                    if (installedVersion(instance.pkg) > 0) verifySameSigner(apk, instance.pkg)
                    if (instance.versionCode != null)
                        require(archiveVersion(apk) == instance.versionCode) { "APK version does not match the catalog" }
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
                            cancelled = ::stopped,
                        )
                        verify(output, instance.pkg, null)
                    }
                }
                if (updating && instance.usesGeode && File(root, "geode").isDirectory) {
                    check(File(root, "geode").copyRecursively(File(work, "geode"))) {
                        "Could not keep the prepared Geode launcher"
                    }
                } else if (instance.usesGeode && (!updating || installedVersion(instance.geodePackage) == 0L)) {
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
                    download(release.first, source, release.second, "Downloading Geode launcher")
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
                            ::stopped,
                        )
                    verify(output, instance.geodePackage, null)
                    source.delete()
                }
                check(!stopped()) { "Cancelled" }
                File(work, "recipe.txt").writeText(store.recipe(original))
                File(work, "version.txt").writeText(archiveVersion(File(work, "game/base.apk")).toString())
                // Publish only complete, verified output. Keep previous output intact on any
                // earlier failure.
                check(backup.deleteRecursively()) { "Cannot clear previous APK output" }
                root.parentFile!!.mkdirs()
                if (root.exists()) check(root.renameTo(backup)) { "Cannot replace previous output" }
                if (!work.renameTo(root)) {
                    backup.renameTo(root)
                    error("Cannot publish APK output")
                }
                backup.deleteRecursively()
                DownloadControl.directory(applicationContext, instance.id).deleteRecursively()
                if (inputData.getBoolean("download", false))
                    notifyResult(instanceId, applicationContext.getString(R.string.download_ready, original.name))
                Result.success(workDataOf("status" to "Ready to install"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (paused()) {
                    notifyResult(instanceId, applicationContext.getString(R.string.download_paused), paused = true)
                    Result.success(workDataOf("status" to "Paused"))
                } else if (stopped()) Result.success()
                else if (e is IOException && runAttemptCount < 2) {
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
                if (DownloadControl.stopped(applicationContext, instanceId) == "cancel")
                    DownloadControl.directory(applicationContext, instanceId).deleteRecursively()
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
    private fun version(info: android.content.pm.PackageInfo) =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun archiveVersion(file: File) = version(
        applicationContext.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: error("Could not read APK version")
    )

    private fun installedVersion(pkg: String) = try {
        version(applicationContext.packageManager.getPackageInfo(pkg, 0))
    } catch (_: PackageManager.NameNotFoundException) {
        0L
    }

    @Suppress("DEPRECATION")
    private fun verifySameSigner(file: File, pkg: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        fun fingerprints(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else info.signatures).orEmpty().map { it.toByteArray().toList() }.toSet()
        val manager = applicationContext.packageManager
        val installed = fingerprints(manager.getPackageInfo(pkg, flags))
        val downloaded = fingerprints(manager.getPackageArchiveInfo(file.path, flags)
            ?: error("Could not read APK signing certificate"))
        require(installed.isNotEmpty() && installed == downloaded) {
            "Update signing certificate differs from the installed app"
        }
    }

    companion object {
        fun name(id: String) = "instance:$id"

        fun enqueue(context: Context, instance: Instance, update: Boolean = false) {
            Validation.check(instance)
            val download = instance.usesGeode || !instance.url.isNullOrBlank() ||
                !instance.updateManifest.isNullOrBlank()
            val data = workDataOf("instance" to Gson().toJson(instance),
                "instance_id" to instance.id, "download" to download, "update" to update)
            DownloadControl.remember(context, instance, update)
            val request =
                OneTimeWorkRequestBuilder<InstanceWorker>()
                    .setInputData(data)
                    .setConstraints(
                        Constraints.Builder()
                            .apply {
                                if (download)
                                    setRequiredNetworkType(NetworkType.CONNECTED)
                            }
                            .build()
                    )
                    .apply { if (download) addTag("download") }
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
