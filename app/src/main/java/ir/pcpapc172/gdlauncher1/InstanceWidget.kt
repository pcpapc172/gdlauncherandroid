package ir.pcpapc172.gdlauncher1

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class InstanceWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != PINNED) return
        val widget = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val id = intent.data?.lastPathSegment ?: return
        if (AppWidgetManager.getInstance(context).getAppWidgetInfo(widget)?.provider ==
            ComponentName(context, InstanceWidget::class.java)) {
            configure(context, widget, id)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = prefs(context).edit()
        for (id in ids) edit.remove(id.toString())
        edit.apply()
    }

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        val storage = prefs(context)
        val edit = storage.edit()
        oldIds.zip(newIds).forEach { (old, new) ->
            edit.putString(new.toString(), storage.getString(old.toString(), null))
            if (old != new) edit.remove(old.toString())
        }
        edit.apply()
    }

    companion object {
        private const val PINNED = "ir.pcpapc172.gdlauncher1.WIDGET_PINNED"
        private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
        fun instances(context: Context): List<Instance> = InstanceStore(context).let {
            it.custom() + it.cached()
        }

        fun canPin(context: Context) = Build.VERSION.SDK_INT >= 26 &&
            AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

        fun pin(context: Context, id: String) {
            if (Build.VERSION.SDK_INT < 26 || !canPin(context)) error(context.getString(R.string.widget_unavailable))
            val callback = Intent(context, InstanceWidget::class.java).setAction(PINNED)
                .setData(Uri.Builder().scheme("gdlauncher").authority("widget").appendPath(id).build())
            // The widget host supplies AppWidgetManager.EXTRA_APPWIDGET_ID.
            val flags = PendingIntent.FLAG_ONE_SHOT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val completion = PendingIntent.getBroadcast(context, 0, callback, flags)
            check(AppWidgetManager.getInstance(context).requestPinAppWidget(
                ComponentName(context, InstanceWidget::class.java), null, completion
            )) { context.getString(R.string.widget_unavailable) }
        }

        fun configure(context: Context, widget: Int, id: String) {
            prefs(context).edit().putString(widget.toString(), id).apply()
            render(context, AppWidgetManager.getInstance(context), widget)
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for (id in manager.getAppWidgetIds(ComponentName(context, InstanceWidget::class.java)))
                render(context, manager, id)
        }

        private fun render(context: Context, manager: AppWidgetManager, widget: Int) {
            val id = prefs(context).getString(widget.toString(), null)
            val instance = instances(context).firstOrNull { it.id == id }
            val views = RemoteViews(context.packageName, R.layout.instance_widget)
            views.setTextViewText(R.id.widgetTitle, instance?.name ?: context.getString(R.string.app_name))
            val open = if (instance != null)
                Intent(context, InstanceWidgetLaunch::class.java).putExtra("instance", instance.id)
            else Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(R.id.widgetCard, PendingIntent.getActivity(
                context, widget, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            manager.updateAppWidget(widget, views)
        }
    }
}

class InstanceWidgetConfiguration : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        DynamicColors.applyToActivityIfAvailable(this)
        setResult(RESULT_CANCELED)
        val widget = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widget)?.provider
        if (provider != ComponentName(this, InstanceWidget::class.java)) {
            finish()
            return
        }
        val instances = InstanceWidget.instances(this)
        if (instances.isEmpty()) {
            Toast.makeText(this, R.string.empty_title, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.widget_select)
            .setItems(instances.map { it.name }.toTypedArray()) { _, index ->
                InstanceWidget.configure(this, widget, instances[index].id)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget))
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }
}

class InstanceWidgetLaunch : android.app.Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getStringExtra("instance")
        val instance = InstanceWidget.instances(this).firstOrNull { it.id == id }
        if (instance == null || runCatching { launchInstance(instance) }.isFailure) {
            Toast.makeText(this, R.string.not_installed, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}

fun Context.launchInstance(instance: Instance) {
    val target = if (instance.usesGeode) instance.geodePackage else instance.pkg
    val component = instance.main?.let(ComponentName::unflattenFromString)
    if (component != null) {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setComponent(component))
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // Older catalog entries sometimes name an activity that changed.
        }
    }
    startActivity(packageManager.getLaunchIntentForPackage(target) ?: error("App has no launcher activity"))
}
