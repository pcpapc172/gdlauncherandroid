package ir.pcpapc172.gdlauncher1

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

object Installer {
    const val ACTION = "ir.pcpapc172.gdlauncher1.INSTALL_RESULT"

    fun install(context: Context, files: List<File>, pkg: String) {
        require(files.isNotEmpty() && files.all { it.isFile }) { "No prepared APKs" }
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(pkg)
                setSize(files.sumOf { it.length() })
                if (Build.VERSION.SDK_INT >= 31)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                files.forEach { file ->
                    session.openWrite(file.name, 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
                val token = java.util.UUID.randomUUID().toString()
                context
                    .getSharedPreferences("installer", Context.MODE_PRIVATE)
                    .edit()
                    .putString(token, pkg)
                    .commit()
                val intent =
                    Intent(context, MainActivity::class.java)
                        .putExtra("install_token", token)
                        .setAction(ACTION)
                        .putExtra("install_package", pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val flags =
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getActivity(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }
}
