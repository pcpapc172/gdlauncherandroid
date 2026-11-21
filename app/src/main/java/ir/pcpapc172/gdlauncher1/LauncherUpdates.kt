package ir.pcpapc172.gdlauncher1

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LauncherUpdates(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val button: Button,
    private val install: (String) -> Unit,
) {
    private data class Release(
        @SerializedName("version_code") val code: Int,
        @SerializedName("version_name") val version: String?,
        @SerializedName("download_url") val url: String?,
        val sha256: String?,
    )

    private val prefs = activity.getSharedPreferences("launcher_update", Context.MODE_PRIVATE)
    private val manager = activity.getSystemService(DownloadManager::class.java)
    private var job: Job? = null
    private var ready: File? = null

    @Suppress("DEPRECATION")
    private fun version(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    fun check(manual: Boolean = false) {
        if (ready != null) {
            if (manual) message(R.string.launcher_update_ready)
            return
        }
        if (job?.isActive == true) {
            if (manual) message(R.string.launcher_update_checking)
            return
        }
        job = scope.launch {
            var downloadId = prefs.getLong("download", -1L)
            try {
                val current = withContext(Dispatchers.IO) {
                    version(activity.packageManager.getPackageInfo(activity.packageName, 0))
                }
                val pendingCode = prefs.getInt("code", 0)
                val pendingHash = prefs.getString("sha256", null)
                if (downloadId >= 0 && pendingCode > current && pendingHash != null &&
                    DIGEST.matches(pendingHash)) {
                    showDownloading()
                    awaitDownload(downloadId, pendingCode, pendingHash)
                    return@launch
                }
                if (downloadId >= 0) withContext(Dispatchers.IO) { manager.remove(downloadId) }
                downloadId = -1L
                prefs.edit().clear().apply()
                val release = withContext(Dispatchers.IO) { latest() }
                if (release.code <= current) {
                    if (manual) message(R.string.launcher_up_to_date)
                    return@launch
                }
                val hash = requireNotNull(release.sha256)
                downloadId = withContext(Dispatchers.IO) {
                    val destination = updateFile(release.code)
                    require(!destination.exists() || destination.delete()) { "Cannot replace previous installer" }
                    val request = DownloadManager.Request(Uri.parse(release.url))
                        .setTitle(activity.getString(R.string.launcher_update_download))
                        .setDescription(release.version.orEmpty())
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(
                            activity, Environment.DIRECTORY_DOWNLOADS, destination.name
                        )
                    manager.enqueue(request).also { id ->
                        prefs.edit().putLong("download", id).putInt("code", release.code)
                            .putString("sha256", hash).apply()
                    }
                }
                showDownloading()
                awaitDownload(downloadId, release.code, hash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LauncherUpdates", "Launcher update failed", e)
                button.visibility = View.GONE
                if (downloadId >= 0) {
                    withContext(Dispatchers.IO) { manager.remove(downloadId) }
                    prefs.edit().clear().apply()
                }
                if (manual || downloadId >= 0) {
                    Toast.makeText(activity, activity.getString(R.string.launcher_update_failed, e.message),
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun latest(): Release {
        val connection = URL(MANIFEST).openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        val release = try {
            require(connection.responseCode == 200) { "GitHub returned HTTP ${connection.responseCode}" }
            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(65_537)
                var count = 0
                while (count < buffer.size) {
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n == -1) break
                    count += n
                }
                require(count in 1..65_536) { "Invalid launcher update manifest size" }
                Gson().fromJson(String(buffer, 0, count, Charsets.UTF_8), Release::class.java)
                    ?: error("Empty launcher update manifest")
            }
        } finally {
            connection.disconnect()
        }
        val uri = Uri.parse(release.url.orEmpty())
        require(release.code > 0 && !release.version.isNullOrBlank() &&
            release.sha256?.let(DIGEST::matches) == true &&
            uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
            uri.path?.startsWith("/pcpapc172/gdlauncherandroid/releases/download/") == true) {
            "Invalid launcher update manifest"
        }
        return release
    }

    private fun showDownloading() {
        button.text = activity.getString(R.string.launcher_update_download)
        button.isEnabled = false
        button.visibility = View.VISIBLE
    }

    private suspend fun awaitDownload(id: Long, code: Int, sha256: String) {
        while (true) {
            val status = withContext(Dispatchers.IO) {
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (!cursor.moveToFirst()) error("Download was removed")
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                } ?: error("Download was removed")
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) break
            if (status == DownloadManager.STATUS_FAILED) error("APK download failed")
            delay(1_000)
        }
        val file = withContext(Dispatchers.IO) {
            val apk = updateFile(code)
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    digest.update(buffer, 0, bytes)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(sha256, true)) {
                "APK checksum mismatch"
            }
            val pkg = activity.packageManager.getPackageArchiveInfo(apk.path, 0)
                ?: error("Downloaded file is not an APK")
            require(pkg.packageName == activity.packageName && version(pkg) == code.toLong()) {
                "APK package or version does not match the release"
            }
            apk
        }
        ready = file
        button.text = activity.getString(R.string.launcher_update_ready)
        button.isEnabled = true
        button.setOnClickListener { install(file.absolutePath) }
    }

    private fun updateFile(code: Int) = File(
        requireNotNull(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "GDLauncher-$code.apk"
    )

    private fun message(id: Int) = Toast.makeText(activity, id, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MANIFEST =
            "https://github.com/pcpapc172/gdlauncherandroid/releases/latest/download/latestandroidlauncher.json"
        private val DIGEST = Regex("[a-fA-F0-9]{64}")
    }
}
