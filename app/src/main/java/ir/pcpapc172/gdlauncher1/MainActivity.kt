package ir.pcpapc172.gdlauncher1

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import ir.pcpapc172.gdlauncher1.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.io.File

data class Instance(
    @SerializedName("id")         val id: String?,
    @SerializedName("name")       val name: String?,
    @SerializedName("pkg")        val pkg: String?,
    @SerializedName("url")        val url: String?,
    @SerializedName("version")    val version: String?,
    @SerializedName("settings")   val settings: String?,
    @SerializedName("main")       val main: String?,
    @SerializedName("geode_url")  val geodeUrl: String? = null,
    @SerializedName("is_custom")  val isCustom: Boolean = false,
    @Transient var downloadId: Long = -1L,
    @Transient var isDownloading: Boolean = false,
    @Transient var downloadProgress: Int = 0
)

interface ApiService {
    @GET("archive/android_instances.json")
    suspend fun getInstances(): List<Instance>
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: InstanceAdapter
    private val instancesList = mutableListOf<Instance>()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private var installedPackages = emptyMap<String, Boolean>()
    private var installedRefresh: Job? = null
    private lateinit var launcherUpdates: LauncherUpdates

    companion object {
        private const val TAG                  = "GDManager"
        private const val PREFS_NAME           = "gd_launcher_prefs"
        private const val KEY_CUSTOM_INSTANCES = "custom_instances"
        private const val KEY_SERVER_CACHE     = "server_instances_cache"
        private const val KEY_INSTANCE_COUNTER = "instance_counter"
        private const val DEFAULT_GEODE_URL    =
            "https://github.com/geode-sdk/android/releases/latest/download/Geode.apk"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            WindowCompat.getInsetsController(window, binding.root).isAppearanceLightNavigationBars =
                resources.getBoolean(R.bool.light_system_bars)
            setSupportActionBar(binding.toolbar)
            launcherUpdates = LauncherUpdates(
                this, scope, binding.launcherUpdatePanel, binding.launcherUpdateStatus,
                binding.launcherUpdateProgress, binding.launcherUpdate, ::installApk
            )
            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            setupRecyclerView()
            loadPersistedInstances()
            fetchDataFromServer()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::launcherUpdates.isInitialized) launcherUpdates.check()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refreshInstalledRows()
    }

    override fun onPause() {
        super.onPause()
        saveCustomInstances()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh        -> { fetchDataFromServer(); true }
        R.id.action_check_update   -> { if (::launcherUpdates.isInitialized) launcherUpdates.check(manual = true); true }
        R.id.action_install_custom -> { showCustomInstanceDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun loadPersistedInstances() {
        instancesList.clear()
        try {
            val json = prefs.getString(KEY_CUSTOM_INSTANCES, null)
            if (!json.isNullOrEmpty()) {
                val type = object : TypeToken<List<Instance>>() {}.type
                val customs: List<Instance> = gson.fromJson(json, type)
                instancesList.addAll(customs.filter { it.isCustom })
            }
        } catch (e: Exception) { Log.e(TAG, "Error loading custom instances", e) }

        try {
            val json = prefs.getString(KEY_SERVER_CACHE, null)
            if (!json.isNullOrEmpty()) {
                val type = object : TypeToken<List<Instance>>() {}.type
                val cached: List<Instance> = gson.fromJson(json, type)
                instancesList.addAll(cached.filter { !it.isCustom })
            }
        } catch (e: Exception) { Log.e(TAG, "Error loading cached server instances", e) }

        if (instancesList.isNotEmpty()) adapter.notifyItemRangeInserted(0, instancesList.size)
    }

    private fun saveCustomInstances() {
        try {
            prefs.edit().putString(KEY_CUSTOM_INSTANCES, gson.toJson(instancesList.filter { it.isCustom })).apply()
        } catch (e: Exception) { Log.e(TAG, "Error saving custom instances", e) }
    }

    private fun cacheServerInstances(instances: List<Instance>) {
        try {
            prefs.edit().putString(KEY_SERVER_CACHE, gson.toJson(instances.filter { !it.isCustom })).apply()
        } catch (e: Exception) { Log.e(TAG, "Error caching server instances", e) }
    }

    // ── Networking ──────────────────────────────────────────────────────────

    private fun buildApi(): ApiService = Retrofit.Builder()
        .baseUrl("http://api.pcpapc172.ir/")
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(ApiService::class.java)

    private fun fetchDataFromServer() {
        binding.loadingSpinner.visibility = View.VISIBLE
        scope.launch {
            try {
                val serverData = withContext(Dispatchers.IO) { buildApi().getInstances() }
                val valid = serverData.filter { !it.name.isNullOrEmpty() && !it.id.isNullOrEmpty() }
                val old = instancesList.toList()
                val active = old.filter { !it.isCustom && it.isDownloading }.associateBy { it.id }
                val listedIds = valid.map { it.id }.toSet()
                val rows = old.filter { it.isCustom } +
                    valid.map { active[it.id] ?: it } + active.values.filterNot { it.id in listedIds }
                val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize() = old.size
                    override fun getNewListSize() = rows.size
                    override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                        val before = old[oldPosition]
                        val after = rows[newPosition]
                        return before.isCustom == after.isCustom && before.id == after.id
                    }
                    override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                        old[oldPosition] == rows[newPosition]
                })
                instancesList.clear()
                instancesList.addAll(rows)
                cacheServerInstances(valid)
                diff.dispatchUpdatesTo(adapter)
                refreshInstalledRows()
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                Toast.makeText(this@MainActivity, "Offline – showing cached data", Toast.LENGTH_SHORT).show()
            } finally {
                binding.loadingSpinner.visibility = View.INVISIBLE
            }
        }
    }

    // ── Download & install ──────────────────────────────────────────────────

    private fun startDownload(instance: Instance) {
        val url = instance.url
        if (url.isNullOrEmpty() || instance.isDownloading) return
        val outDir = File(getExternalFilesDir(null), "instances").also { it.mkdirs() }
        val outFile = File(outDir, "${instance.id}.apk")
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading ${instance.name}")
            .setDescription("Pre-patched GD instance")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(outFile))
        val downloads = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = downloads.enqueue(request)
        instance.downloadId = id
        instance.isDownloading = true
        instance.downloadProgress = 0
        instanceRow(instance) { adapter.notifyItemChanged(it) }
        pollDownloadProgress(id, instance, outFile)
    }

    private inline fun instanceRow(instance: Instance, action: (Int) -> Unit) {
        val row = instancesList.indexOfFirst { it === instance }
        if (row >= 0) action(row)
    }

    private fun pollDownloadProgress(downloadId: Long, instance: Instance, outFile: File) {
        scope.launch {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (true) {
                delay(500)
                val snapshot = withContext(Dispatchers.IO) {
                    manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        status to if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
                    }
                } ?: continue
                val (status, progress) = snapshot
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED -> {
                        instance.isDownloading = false
                        instance.downloadProgress = if (status == DownloadManager.STATUS_SUCCESSFUL) 100 else 0
                        if (status == DownloadManager.STATUS_FAILED) instance.downloadId = -1L
                        instanceRow(instance) { adapter.notifyItemChanged(it) }
                        if (status == DownloadManager.STATUS_SUCCESSFUL) promptInstall(outFile.absolutePath, instance)
                        else Toast.makeText(this@MainActivity, "Download failed for ${instance.name}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    else -> if (instance.downloadProgress != progress) {
                        instance.downloadProgress = progress
                        instanceRow(instance) { adapter.updateProgress(it) }
                    }
                }
            }
        }
    }

    private fun refreshInstalledRows() {
        installedRefresh?.cancel()
        val packages = instancesList.mapNotNull { it.pkg?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        installedRefresh = scope.launch {
            val next = withContext(Dispatchers.IO) { packages.associateWith(::isPackageInstalled) }
            val previous = installedPackages
            installedPackages = next
            instancesList.forEachIndexed { row, item ->
                val pkg = item.pkg?.trim()
                if (previous[pkg] != next[pkg] && !item.isDownloading) adapter.notifyItemChanged(row)
            }
        }
    }

    // ── Install ─────────────────────────────────────────────────────────────

    private fun promptInstall(path: String, instance: Instance) {
        if (!File(path).exists()) { Toast.makeText(this, "APK file not found", Toast.LENGTH_LONG).show(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Install ${instance.name}?")
            .setMessage("Package: ${instance.pkg}\n\nTap Install to proceed.")
            .setPositiveButton("Install") { _, _ -> installApk(path) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun installApk(path: String) {
        try {
            val file = File(path)
            if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_LONG).show(); return }
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) { Toast.makeText(this, "Install error: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    // ── Package helpers ──────────────────────────────────────────────────────

    fun isPackageInstalled(pkgName: String?): Boolean {
        if (pkgName.isNullOrEmpty()) return false
        return try { packageManager.getPackageInfo(pkgName, 0); true } catch (e: Exception) { false }
    }

    fun uninstallApp(pkg: String) {
        val clean = pkg.trim()
        if (!isPackageInstalled(clean)) { Toast.makeText(this, "Not installed", Toast.LENGTH_SHORT).show(); return }
        try { startActivity(Intent(Intent.ACTION_DELETE).apply { data = Uri.parse("package:$clean") }) }
        catch (e: Exception) { Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    fun openSettings(instance: Instance) {
        val pkg = instance.pkg?.trim() ?: ""
        if (!isPackageInstalled(pkg)) { Toast.makeText(this, "Not installed", Toast.LENGTH_SHORT).show(); return }
        try {
            instance.settings?.trim()?.split("/")?.takeIf { it.size == 2 }?.let { parts ->
                startActivity(Intent().apply {
                    component = android.content.ComponentName(parts[0], parts[1])
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
            } ?: Toast.makeText(this, "Settings not configured", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show() }
    }

    fun launchGame(instance: Instance) {
        val pkg = instance.pkg?.trim() ?: ""
        try {
            val intent = instance.main?.trim()?.split("/")?.takeIf { it.size == 2 }?.let { parts ->
                Intent(Intent.ACTION_MAIN).apply { component = android.content.ComponentName(parts[0], parts[1]) }
            } ?: packageManager.getLaunchIntentForPackage(pkg)
            intent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(this)
            } ?: Toast.makeText(this, "Could not launch", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "Launch failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun removeCustomInstance(instance: Instance) {
        val position = instancesList.indexOfFirst { it === instance }
        if (position < 0) return
        instancesList.removeAt(position)
        adapter.notifyItemRemoved(position)
        saveCustomInstances()
        Toast.makeText(this, "Removed '${instance.name}'", Toast.LENGTH_SHORT).show()
    }

    // ── Browse installed apps ────────────────────────────────────────────────

    fun showAppPicker(onPicked: (pkg: String, label: String) -> Unit) {
        val apps = packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { packageManager.getApplicationLabel(it).toString() }

        val labels = apps.map { packageManager.getApplicationLabel(it).toString() }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Pick an app")
            .setItems(labels) { _, which ->
                val app = apps[which]
                onPicked(app.packageName, labels[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Custom instance dialog ───────────────────────────────────────────────

    private fun generateUniquePackageName(): String {
        val counter = prefs.getInt(KEY_INSTANCE_COUNTER, 1)
        prefs.edit().putInt(KEY_INSTANCE_COUNTER, counter + 1).apply()
        val base   = "com.gd.inst.v"
        val padded = counter.toString().padStart(24 - base.length, '0')
        return (base + padded).take(24)
    }

    private fun showCustomInstanceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_custom_instance, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        val etName       = view.findViewById<TextInputEditText>(R.id.etName)
        val etPackage    = view.findViewById<TextInputEditText>(R.id.etPackage)
        val btnBrowse    = view.findViewById<Button>(R.id.btnBrowseApps)
        val cbUseGeode   = view.findViewById<CheckBox>(R.id.cbUseGeode)
        val layoutAdv    = view.findViewById<LinearLayout>(R.id.layoutAdvanced)
        val btnToggleAdv = view.findViewById<Button>(R.id.btnToggleAdvanced)
        val tilPackageAdv= view.findViewById<TextInputLayout>(R.id.tilPackage)
        val etPackageAdv = view.findViewById<TextInputEditText>(R.id.etPackageAdv)
        val etSettings   = view.findViewById<TextInputEditText>(R.id.etSettings)
        val etMain       = view.findViewById<TextInputEditText>(R.id.etMain)
        val btnGenerate  = view.findViewById<Button>(R.id.btnGeneratePackage)
        val btnCreate    = view.findViewById<Button>(R.id.btnCreate)

        layoutAdv.visibility = View.GONE

        // Browse button — pick from installed apps
        btnBrowse.setOnClickListener {
            showAppPicker { pkg, label ->
                etPackage.setText(pkg)
                if (etName.text.isNullOrEmpty()) etName.setText(label)
            }
        }

        // Advanced toggle
        btnToggleAdv.setOnClickListener {
            val show = layoutAdv.visibility == View.GONE
            layoutAdv.visibility = if (show) View.VISIBLE else View.GONE
            btnToggleAdv.text    = if (show) "Hide Advanced ▲" else "Show Advanced ▼"
        }

        etPackageAdv.setText(generateUniquePackageName())
        btnGenerate.setOnClickListener { etPackageAdv.setText(generateUniquePackageName()) }

        btnCreate.setOnClickListener {
            val name     = etName.text.toString().trim()
            // prefer the main package field; fall back to advanced field
            val pkg      = etPackage.text.toString().trim()
                .ifEmpty { etPackageAdv.text.toString().trim() }
            val useGeode = cbUseGeode.isChecked

            if (name.isEmpty()) { Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (pkg.isEmpty())  { Toast.makeText(this, "Package is required – tap Browse or enter manually", Toast.LENGTH_LONG).show(); return@setOnClickListener }

            val instance = Instance(
                id       = "custom_${System.currentTimeMillis()}",
                name     = name,
                pkg      = pkg,
                url      = null,
                version  = if (useGeode) "Geode" else "Vanilla",
                settings = etSettings.text?.toString()?.trim()?.ifEmpty { null },
                main     = etMain.text?.toString()?.trim()?.ifEmpty { null },
                geodeUrl = if (useGeode) DEFAULT_GEODE_URL else null,
                isCustom = true
            )

            instancesList.add(0, instance)
            adapter.notifyItemInserted(0)
            refreshInstalledRows()
            binding.recyclerView.scrollToPosition(0)
            saveCustomInstances()
            dialog.dismiss()
            Toast.makeText(this, "Created '${instance.name}'", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // ── RecyclerView ─────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = InstanceAdapter(instancesList, this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        (binding.recyclerView.itemAnimator as? DefaultItemAnimator)?.supportsChangeAnimations = false
    }

    inner class InstanceAdapter(
        private val list: List<Instance>,
        private val ctx: Context
    ) : RecyclerView.Adapter<InstanceAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName          : TextView    = view.findViewById(R.id.tvName)
            val tvVersion       : TextView    = view.findViewById(R.id.tvVersion)
            val btnAction       : Button      = view.findViewById(R.id.btnAction)
            val btnSettings     : ImageButton = view.findViewById(R.id.btnSettings)
            val btnUninstall    : ImageButton = view.findViewById(R.id.btnUninstall)
            val btnDeleteCustom : ImageButton = view.findViewById(R.id.btnDeleteCustom)
            val layoutProgress  : LinearLayout= view.findViewById(R.id.layoutProgress)
            val progressBar     : LinearProgressIndicator = view.findViewById(R.id.progressBar)
            val tvPercentage    : TextView    = view.findViewById(R.id.tvPercentage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_instance, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            try {
                val item        = list[position]
                val pkgName     = item.pkg?.trim() ?: ""
                val installed = installedPackages[pkgName]
                val hasUrl      = !item.url.isNullOrEmpty()

                holder.tvName.text    = item.name ?: "Unknown"
                holder.tvVersion.text = "Ver: ${item.version ?: "?"}"

                // Delete button — custom instances only, always visible in corner
                if (item.isCustom) {
                    holder.btnDeleteCustom.visibility = View.VISIBLE
                    holder.btnDeleteCustom.setOnClickListener {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("Remove '${item.name}'?")
                            .setMessage("This removes it from the list only. The app stays installed.")
                            .setPositiveButton("Remove") { _, _ ->
                                removeCustomInstance(item)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                } else {
                    holder.btnDeleteCustom.visibility = View.GONE
                }

                if (item.isDownloading) {
                    holder.layoutProgress.visibility = View.VISIBLE
                    bindProgress(holder, item.downloadProgress)
                    holder.btnAction.isEnabled       = false
                    holder.btnAction.text            = "Downloading…"
                    holder.btnSettings.visibility    = View.GONE
                    holder.btnUninstall.visibility   = View.GONE
                } else {
                    holder.layoutProgress.visibility = View.GONE
                    holder.btnAction.isEnabled       = true

                    when {
                        installed == null && pkgName.isNotEmpty() -> {
                            holder.btnAction.text = getString(R.string.checking_instance)
                            holder.btnAction.isEnabled = false
                            holder.btnSettings.visibility = View.GONE
                            holder.btnUninstall.visibility = View.GONE
                        }
                        installed == true -> {
                            holder.btnAction.text = "Launch"
                            holder.btnAction.setOnClickListener { (ctx as? MainActivity)?.launchGame(item) }
                            holder.btnSettings.visibility = if (!item.settings.isNullOrEmpty()) View.VISIBLE else View.GONE
                            holder.btnSettings.setOnClickListener { (ctx as? MainActivity)?.openSettings(item) }
                            holder.btnUninstall.visibility = View.VISIBLE
                            holder.btnUninstall.setOnClickListener { (ctx as? MainActivity)?.uninstallApp(pkgName) }
                        }
                        hasUrl -> {
                            holder.btnAction.text = "Download & Install"
                            holder.btnAction.setOnClickListener { startDownload(item) }
                            holder.btnSettings.visibility  = View.GONE
                            holder.btnUninstall.visibility = View.GONE
                        }
                        else -> {
                            holder.btnAction.text      = "Not Installed"
                            holder.btnAction.isEnabled = false
                            holder.btnSettings.visibility  = View.GONE
                            holder.btnUninstall.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error binding position $position", e)
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == "progress" } && list[position].isDownloading) {
                bindProgress(holder, list[position].downloadProgress, animate = true)
            } else {
                onBindViewHolder(holder, position)
            }
        }

        private fun bindProgress(holder: Holder, progress: Int, animate: Boolean = false) {
            holder.progressBar.setProgressCompat(progress, animate && progress > holder.progressBar.progress)
            val label = "$progress%"
            if (holder.tvPercentage.text != label) holder.tvPercentage.text = label
        }

        fun updateProgress(position: Int) = notifyItemChanged(position, "progress")
        override fun getItemCount(): Int = list.size
    }
}
