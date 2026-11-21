package ir.pcpapc172.gdlauncher1

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.google.gson.Gson
import java.io.File

object DownloadControl {
    private fun prefs(context: Context) = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun remember(context: Context, instance: Instance, update: Boolean) {
        context.getSystemService(NotificationManager::class.java).cancel("download:${instance.id}", 1)
        prefs(context).edit().putString("job:${instance.id}", Gson().toJson(instance))
            .putBoolean("update:${instance.id}", update).remove("stop:${instance.id}").apply()
    }

    fun stopped(context: Context, id: String) = prefs(context).getString("stop:$id", null)
    fun paused(context: Context, id: String) = stopped(context, id) == "pause"
    fun wasLauncherUpdateCancelled(context: Context, code: Long?) =
        code == prefs(context).getLong("dismissedLauncherVersion", -1L)

    fun clearPaused(context: Context) {
        val storage = prefs(context)
        val edit = storage.edit()
        storage.all.filter { (key, value) -> key.startsWith("stop:") && value == "pause" }
            .keys.forEach { key -> edit.remove(key) }
        edit.apply()
    }

    fun resume(context: Context, id: String) {
        val saved = prefs(context).getString("job:$id", null) ?: error("No paused download")
        val instance = Gson().fromJson(saved, Instance::class.java)
        InstanceWorker.enqueue(context, instance, prefs(context).getBoolean("update:$id", false))
    }

    fun directory(context: Context, id: String) = File(context.filesDir, "downloads/$id")
    fun partial(context: Context, id: String, url: String, sha256: String?) =
        File(directory(context, id), hash("$url:$sha256") + ".part")

    fun command(context: Context, id: String, action: String): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java)
            .setAction(action)
            .setData(android.net.Uri.parse("gdlauncher://download/$id"))
            .putExtra("instance", id)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun stop(context: Context, id: String, pause: Boolean) {
        val mode = if (pause) "pause" else "cancel"
        val storage = prefs(context)
        val edit = storage.edit().putString("stop:$id", mode)
        if (!pause && id == UpdateCatalog.LAUNCHER) {
            val code = storage.getString("job:$id", null)?.let {
                runCatching { Gson().fromJson(it, Instance::class.java).versionCode }.getOrNull()
            }
            if (code != null) edit.putLong("dismissedLauncherVersion", code)
        }
        edit.apply()
        if (!pause) {
            context.getSystemService(NotificationManager::class.java).cancel("download:$id", 1)
            WorkManager.getInstance(context).cancelUniqueWork(InstanceWorker.name(id))
            directory(context, id).deleteRecursively()
        }
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("instance") ?: return
        if (!Regex("[A-Za-z0-9_-]{1,80}").matches(id)) return
        when (intent.action) {
            "pause_download" -> DownloadControl.stop(context, id, true)
            "cancel_download" -> DownloadControl.stop(context, id, false)
            "resume_download" -> if (DownloadControl.paused(context, id)) {
                val result = goAsync()
                val manager = WorkManager.getInstance(context)
                val jobs = manager.getWorkInfosForUniqueWork(InstanceWorker.name(id))
                jobs.addListener({
                    try {
                        if (jobs.get().none { !it.state.isFinished } && DownloadControl.paused(context, id))
                            DownloadControl.resume(context, id)
                    } finally {
                        result.finish()
                    }
                }, androidx.core.content.ContextCompat.getMainExecutor(context))
            }
        }
    }
}
