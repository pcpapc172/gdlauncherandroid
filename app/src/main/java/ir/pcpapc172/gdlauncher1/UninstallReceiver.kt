package ir.pcpapc172.gdlauncher1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class UninstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // This runs when the user finishes the uninstall (either confirmed or cancelled)
        // We don't need to do much, just maybe refresh the UI if the app is open
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)

        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Uninstall Successful", Toast.LENGTH_SHORT).show()
        }
    }
}