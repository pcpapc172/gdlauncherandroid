package ir.pcpapc172.gdlauncher1

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.Cursor
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

// ---------------------------------------------------------------------------
// Data models
// ---------------------------------------------------------------------------

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
    // @Transient keeps these out of JSON serialisation
    @Transient var downloadId: Long = -1L,
    @Transient var isDownloading: Boolean = false,
    @Transient var downloadProgress: Int = 0
)

data class LauncherUpdate(
    @SerializedName("version_code") val versionCode: Int,
    @SerializedName("version_name") val versionName: String,
    @SerializedName("download_url") val downloadUrl: String,
    @SerializedName("changelog")    val changelog: String?,
    @SerializedName("force_update") val forceUpdate: Boolean = false
)

interface ApiService {
    @GET("archive/android_instances.json")
    suspend fun getInstances(): List<Instance>

    @GET("archive/latestandroidlauncher.json")
    suspend fun getLauncherUpdate(): LauncherUpdate
}

// ---------------------------------------------------------------------------
// MainActivity
// ---------------------------------------------------------------------------

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: InstanceAdapter
    private val instancesList = mutableListOf<Instance>()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private val activeDownloads = mutableMapOf<Long, Int>() // downloadId -> position
    private var downloadReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG                  = "GDManager"
        private const val PREFS_NAME           = "gd_launcher_prefs"
        private const val KEY_CUSTOM_INSTANCES = "custom_instances"
        private const val KEY_SERVER_CACHE     = "server_instances_cache"
        private const val KEY_INSTANCE_COUNTER = "instance_counter"
        private const val REQUIRED_PKG_LENGTH  = 24
        private const val DEFAULT_GEODE_URL    =
            "https://github.com/geode-sdk/android/releases/latest/download/Geode.apk"
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)

            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            setupRecyclerView()
            loadPersistedInstances()   // populate from cache immediately
            fetchDataFromServer()      // then silently refresh from network
            registerDownloadReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        saveCustomInstances()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        downloadReceiver?.let { unregisterReceiver(it) }
    }

    // -----------------------------------------------------------------------
    // Menu
    // -----------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh        -> { fetchDataFromServer(); true }
        R.id.action_check_update   -> { checkForLauncherUpdate(); true }
        R.id.action_install_custom -> { showCustomInstanceDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /**
     * Load custom instances + cached server instances from SharedPreferences.
     * This is the offline path – works with zero network.
     */
    private fun loadPersistedInstances() {
        instancesList.clear()

        // Custom instances
        try {
            val json = prefs.getString(KEY_CUSTOM_INSTANCES, null)
            if (!json.isNullOrEmpty()) {
                val type = object : TypeToken<List<Instance>>() {}.type
                val customs: List<Instance> = gson.fromJson(json, type)
                instancesList.addAll(customs.filter { it.isCustom })
                Log.d(TAG, "Loaded ${customs.size} custom instances")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom instances", e)
        }

        // Cached server instances
        try {
            val json = prefs.getString(KEY_SERVER_CACHE, null)
            if (!json.isNullOrEmpty()) {
                val type = object : TypeToken<List<Instance>>() {}.type
                val cached: List<Instance> = gson.fromJson(json, type)
                instancesList.addAll(cached.filter { !it.isCustom })
                Log.d(TAG, "Loaded ${cached.size} cached server instances")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached server instances", e)
        }

        adapter.notifyDataSetChanged()
    }

    private fun saveCustomInstances() {
        try {
            val customs = instancesList.filter { it.isCustom }
            prefs.edit().putString(KEY_CUSTOM_INSTANCES, gson.toJson(customs)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom instances", e)
        }
    }

    private fun cacheServerInstances(instances: List<Instance>) {
        try {
            prefs.edit()
                .putString(KEY_SERVER_CACHE, gson.toJson(instances.filter { !it.isCustom }))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error caching server instances", e)
        }
    }

    // -----------------------------------------------------------------------
    // Networking
    // -----------------------------------------------------------------------

    private fun buildApi(): ApiService = Retrofit.Builder()
        .baseUrl("http://api.pcpapc172.ir/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiService::class.java)

    private fun fetchDataFromServer() {
        binding.loadingSpinner.visibility = View.VISIBLE
        scope.launch {
            try {
                val serverData = withContext(Dispatchers.IO) { buildApi().getInstances() }
                val valid = serverData.filter { !it.name.isNullOrEmpty() && !it.id.isNullOrEmpty() }

                // Keep custom instances, replace server ones
                instancesList.removeAll { !it.isCustom }
                instancesList.addAll(valid)

                cacheServerInstances(valid)
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                Toast.makeText(
                    this@MainActivity,
                    "Offline – showing cached data",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.loadingSpinner.visibility = View.GONE
            }
        }
    }

    private fun checkForLauncherUpdate() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { buildApi().getLauncherUpdate() }
                val cur  = packageManager.getPackageInfo(packageName, 0).versionCode
                if (info.versionCode > cur) showUpdateDialog(info)
                else Toast.makeText(this@MainActivity, "You're up to date!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateDialog(update: LauncherUpdate) {
        val msg = buildString {
            append("v${update.versionName} is available!\n\n")
            update.changelog?.let { append("What's new:\n$it\n\n") }
            append("Update now?")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage(msg)
            .setPositiveButton("Update") { _, _ -> downloadLauncherUpdate(update) }
            .setNegativeButton("Later", null)
            .apply { if (update.forceUpdate) setCancelable(false) }
            .show()
    }

    private fun downloadLauncherUpdate(update: LauncherUpdate) {
        val req = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("GD Launcher Update")
            .setDescription("v${update.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "GDLauncher_update.apk")
        (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------------------------------------------
    // Download & install pre-patched APKs
    // -----------------------------------------------------------------------

    fun startDownload(instance: Instance, position: Int) {
        val url = instance.url
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, "No download URL for this instance", Toast.LENGTH_LONG).show()
            return
        }

        val outDir  = File(getExternalFilesDir(null), "instances").also { it.mkdirs() }
        val outFile = File(outDir, "${instance.id}.apk")

        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading ${instance.name}")
            .setDescription("Pre-patched GD instance")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(outFile))

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dlId = dm.enqueue(req)

        instance.downloadId       = dlId
        instance.isDownloading    = true
        instance.downloadProgress = 0
        activeDownloads[dlId]     = position
        adapter.notifyItemChanged(position)

        pollDownloadProgress(dlId, position, instance, outFile)
    }

    private fun pollDownloadProgress(
        downloadId: Long,
        position: Int,
        instance: Instance,
        outFile: File
    ) {
        scope.launch(Dispatchers.IO) {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var done = false
            while (!done) {
                delay(500)
                val cursor: Cursor? = dm.query(DownloadManager.Query().setFilterById(downloadId))
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val bytes  = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total  = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                        if (total > 0) instance.downloadProgress = ((bytes * 100) / total).toInt()

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                done = true
                                instance.isDownloading    = false
                                instance.downloadProgress = 100
                                activeDownloads.remove(downloadId)
                                withContext(Dispatchers.Main) {
                                    adapter.notifyItemChanged(position)
                                    promptInstall(outFile.absolutePath, instance)
                                }
                            }
                            DownloadManager.STATUS_FAILED -> {
                                done = true
                                instance.isDownloading = false
                                instance.downloadId    = -1L
                                activeDownloads.remove(downloadId)
                                withContext(Dispatchers.Main) {
                                    adapter.notifyItemChanged(position)
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Download failed for ${instance.name}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            else -> withContext(Dispatchers.Main) {
                                adapter.updateProgress(position)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (::adapter.isInitialized) adapter.notifyDataSetChanged()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }
    }

    // -----------------------------------------------------------------------
    // Install prompt
    // -----------------------------------------------------------------------

    private fun promptInstall(path: String, instance: Instance) {
        if (!File(path).exists()) {
            Toast.makeText(this, "APK file not found", Toast.LENGTH_LONG).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Install ${instance.name}?")
            .setMessage("Package: ${instance.pkg}\n\nTap Install to proceed.")
            .setPositiveButton("Install") { _, _ -> installApk(path) }
            .setNegativeButton("Cancel", null)
            .show()
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
        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
            Toast.makeText(this, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // -----------------------------------------------------------------------
    // Package helpers
    // -----------------------------------------------------------------------

    fun isPackageInstalled(pkgName: String?): Boolean {
        if (pkgName.isNullOrEmpty()) return false
        return try { packageManager.getPackageInfo(pkgName, 0); true } catch (e: Exception) { false }
    }

    fun uninstallApp(pkg: String) {
        val clean = pkg.trim()
        if (!isPackageInstalled(clean)) { Toast.makeText(this, "Not installed", Toast.LENGTH_SHORT).show(); return }
        try {
            startActivity(Intent(Intent.ACTION_DELETE).apply { data = Uri.parse("package:$clean") })
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchGame(instance: Instance) {
        val pkg = instance.pkg?.trim() ?: ""
        try {
            val intent = instance.main?.trim()?.split("/")?.takeIf { it.size == 2 }?.let { parts ->
                Intent(Intent.ACTION_MAIN).apply {
                    component = android.content.ComponentName(parts[0], parts[1])
                }
            } ?: packageManager.getLaunchIntentForPackage(pkg)

            intent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(this)
            } ?: Toast.makeText(this, "Could not launch", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Launch failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // -----------------------------------------------------------------------
    // Custom instance dialog
    // -----------------------------------------------------------------------

    private fun generateUniquePackageName(): String {
        val counter = prefs.getInt(KEY_INSTANCE_COUNTER, 1)
        prefs.edit().putInt(KEY_INSTANCE_COUNTER, counter + 1).apply()
        val base   = "com.gd.inst.v"
        val padded = counter.toString().padStart(REQUIRED_PKG_LENGTH - base.length, '0')
        return (base + padded).take(REQUIRED_PKG_LENGTH)
    }

    private fun showCustomInstanceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_custom_instance, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(view).create()

        val etName        = view.findViewById<TextInputEditText>(R.id.etName)
        val cbUseGeode    = view.findViewById<CheckBox>(R.id.cbUseGeode)
        val layoutAdv     = view.findViewById<LinearLayout>(R.id.layoutAdvanced)
        val btnToggleAdv  = view.findViewById<Button>(R.id.btnToggleAdvanced)
        val etPackage     = view.findViewById<TextInputEditText>(R.id.etPackage)
        val tilPackage    = view.findViewById<TextInputLayout>(R.id.tilPackage)
        val etSettings    = view.findViewById<TextInputEditText>(R.id.etSettings)
        val etMain        = view.findViewById<TextInputEditText>(R.id.etMain)
        val btnGenerate   = view.findViewById<Button>(R.id.btnGeneratePackage)
        val btnCreate     = view.findViewById<Button>(R.id.btnCreate)

        layoutAdv.visibility = View.GONE
        btnToggleAdv.setOnClickListener {
            val show = layoutAdv.visibility == View.GONE
            layoutAdv.visibility = if (show) View.VISIBLE else View.GONE
            btnToggleAdv.text    = if (show) "Hide Advanced ▲" else "Show Advanced ▼"
        }

        etPackage.setText(generateUniquePackageName())
        etPackage.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val len = s?.length ?: 0
                when {
                    len == REQUIRED_PKG_LENGTH -> { tilPackage.error = null; tilPackage.helperText = "✓ Valid"; btnCreate.isEnabled = true }
                    len < REQUIRED_PKG_LENGTH  -> { tilPackage.error = "Too short – need ${REQUIRED_PKG_LENGTH - len} more"; btnCreate.isEnabled = false }
                    else                       -> { tilPackage.error = "Too long – remove ${len - REQUIRED_PKG_LENGTH} chars"; btnCreate.isEnabled = false }
                }
            }
        })
        etPackage.setText(etPackage.text) // trigger validation

        btnGenerate.setOnClickListener { etPackage.setText(generateUniquePackageName()) }

        btnCreate.setOnClickListener {
            val name     = etName.text.toString().trim()
            val pkg      = etPackage.text.toString().trim()
            val useGeode = cbUseGeode.isChecked

            if (name.isEmpty()) { Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (pkg.length != REQUIRED_PKG_LENGTH) { Toast.makeText(this, "Package must be $REQUIRED_PKG_LENGTH chars", Toast.LENGTH_LONG).show(); return@setOnClickListener }

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
            binding.recyclerView.scrollToPosition(0)
            saveCustomInstances() // persist before any refresh can wipe it
            dialog.dismiss()
            Toast.makeText(this, "Created '${instance.name}'", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // -----------------------------------------------------------------------
    // RecyclerView
    // -----------------------------------------------------------------------

    private fun setupRecyclerView() {
        adapter = InstanceAdapter(instancesList, this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    inner class InstanceAdapter(
        private val list: List<Instance>,
        private val ctx: Context
    ) : RecyclerView.Adapter<InstanceAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName         : TextView     = view.findViewById(R.id.tvName)
            val tvVersion      : TextView     = view.findViewById(R.id.tvVersion)
            val btnAction      : Button       = view.findViewById(R.id.btnAction)
            val btnSettings    : ImageButton  = view.findViewById(R.id.btnSettings)
            val btnUninstall   : ImageButton  = view.findViewById(R.id.btnUninstall)
            val layoutProgress : LinearLayout = view.findViewById(R.id.layoutProgress)
            val progressBar    : ProgressBar  = view.findViewById(R.id.progressBar)
            val tvPercentage   : TextView     = view.findViewById(R.id.tvPercentage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_instance, parent, false))

        @SuppressLint("RecyclerView")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            try {
                val item        = list[position]
                val pkgName     = item.pkg?.trim() ?: ""
                val isInstalled = isPackageInstalled(pkgName)
                val hasUrl      = !item.url.isNullOrEmpty()

                holder.tvName.text    = item.name ?: "Unknown"
                holder.tvVersion.text = "Ver: ${item.version ?: "?"}"

                if (item.isDownloading) {
                    holder.layoutProgress.visibility = View.VISIBLE
                    holder.progressBar.progress      = item.downloadProgress
                    holder.tvPercentage.text         = "${item.downloadProgress}%"
                    holder.btnAction.isEnabled       = false
                    holder.btnAction.text            = "Downloading…"
                    holder.btnSettings.visibility    = View.GONE
                    holder.btnUninstall.visibility   = View.GONE
                } else {
                    holder.layoutProgress.visibility = View.GONE
                    holder.btnAction.isEnabled       = true

                    when {
                        isInstalled -> {
                            holder.btnAction.text = "Launch"
                            holder.btnAction.setOnClickListener { (ctx as? MainActivity)?.launchGame(item) }
                            holder.btnSettings.visibility = if (!item.settings.isNullOrEmpty()) View.VISIBLE else View.GONE
                            holder.btnSettings.setOnClickListener { (ctx as? MainActivity)?.openSettings(item) }
                            holder.btnUninstall.visibility = View.VISIBLE
                            holder.btnUninstall.setOnClickListener { (ctx as? MainActivity)?.uninstallApp(pkgName) }
                        }
                        hasUrl -> {
                            holder.btnAction.text = "Download & Install"
                            holder.btnAction.setOnClickListener {
                                (ctx as? MainActivity)?.startDownload(item, holder.adapterPosition)
                            }
                            holder.btnSettings.visibility  = View.GONE
                            holder.btnUninstall.visibility = View.GONE
                        }
                        else -> {
                            // Custom instance, no URL – must be sideloaded externally
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
            if (payloads.isNotEmpty() && payloads[0] == "progress") {
                val item = list[position]
                if (item.isDownloading) {
                    holder.progressBar.progress = item.downloadProgress
                    holder.tvPercentage.text    = "${item.downloadProgress}%"
                }
            } else super.onBindViewHolder(holder, position, payloads)
        }

        fun updateProgress(position: Int) = notifyItemChanged(position, "progress")

        override fun getItemCount(): Int = list.size
    }
}
