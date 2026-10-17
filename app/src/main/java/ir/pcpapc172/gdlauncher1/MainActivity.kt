package ir.pcpapc172.gdlauncher1

import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import ir.pcpapc172.gdlauncher1.databinding.ActivityMainBinding
import ir.pcpapc172.gdlauncher1.patch.SigningIdentity
import java.io.File
import java.text.DateFormat
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: InstanceStore
    private lateinit var adapter: Rows
    private var custom = listOf<Instance>()
    private var server = listOf<Instance>()
    private var installedSnapshot = mapOf<String, android.content.pm.PackageInfo>()
    private var appIcons = mapOf<String, android.graphics.drawable.Drawable>()
    private var snapshotJob: Job? = null
    private var operations = mapOf<String, WorkInfo>()
    private var refreshJob: Job? = null
    private var query = ""
    private var sort = 0
    private var pendingInstall: Pair<String, String>? = null
    private var sourceFor: String? = null
    private var pendingExport: ByteArray?
        get() = File(cacheDir, "pending-export.bin").takeIf { it.isFile }?.readBytes()
        set(value) {
            val file = File(cacheDir, "pending-export.bin")
            if (value == null) file.delete() else file.writeBytes(value)
        }

    private var editorView: View? = null
    private var editingId: String? = null
    private var importKey = false
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private val permission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pending = pendingInstall
            pendingInstall = null
            if (
                pending != null &&
                    (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())
            )
                installGroup(pending.first, pending.second)
            else if (pending != null) message(getString(R.string.install_permission_needed))
        }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val confirmation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { render() }
    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                lifecycleScope.launch {
                    try {
                        val bytes =
                            withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)!!.use {
                                    it.readBytesLimited(if (importKey) 1_000_000 else 1_000_000)
                                }
                            }
                        if (importKey)
                            password(getString(R.string.restore_key)) { pass ->
                                background {
                                    SigningIdentity(this@MainActivity)
                                        .restore(bytes, pass.toCharArray())
                                    message(getString(R.string.key_restored))
                                }
                            }
                        else {
                            require(operations.values.none { !it.state.isFinished }) {
                                getString(R.string.wait_operation)
                            }
                            custom =
                                withContext(Dispatchers.IO) {
                                    store.import(String(bytes, Charsets.UTF_8))
                                }
                            render()
                            message(getString(R.string.import_complete))
                        }
                    } catch (e: Exception) {
                        error(e)
                    } finally {
                        importKey = false
                    }
                }
        }
    private val saveDocument =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val data = pendingExport
            pendingExport = null
            if (uri != null && data != null)
                background {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(data) }
                    }
                    message(getString(R.string.export_complete))
                }
        }
    private val chooseApk =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = sourceFor
            sourceFor = null
            if (uri != null && target != null)
                background {
                    require(operations[target]?.state?.isFinished != false) {
                        getString(R.string.wait_operation)
                    }
                    withContext(Dispatchers.IO) {
                        val file = store.source(target)
                        file.parentFile!!.mkdirs()
                        val part = File(file.parentFile, "$target.part")
                        try {
                            contentResolver.openInputStream(uri)!!.use { input ->
                                part.outputStream().use { out ->
                                    val b = ByteArray(65536)
                                    var total = 0L
                                    while (true) {
                                        val n = input.read(b)
                                        if (n < 0) break
                                        total += n
                                        require(
                                            total <= 2L * 1024 * 1024 * 1024 &&
                                                part.parentFile!!.usableSpace >
                                                    n + 32L * 1024 * 1024
                                        ) {
                                            "APK too large or storage full"
                                        }
                                        out.write(b, 0, n)
                                    }
                                }
                            }
                            val info = packageManager.getPackageArchiveInfo(part.path, 0)
                            require(info?.packageName == GAME_PACKAGE) {
                                "Select an original Geometry Dash APK"
                            }
                            check(part.renameTo(file))
                        } finally {
                            part.delete()
                        }
                    }
                    withContext(Dispatchers.IO) { store.root(target).deleteRecursively() }
                    message(getString(R.string.source_selected))
                    render()
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            prefs.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        store = InstanceStore(this)
        custom = store.custom()
        server = store.cached()
        sort = prefs.getInt("sort", 0)
        sourceFor = savedInstanceState?.getString("sourceFor")
        pendingInstall =
            savedInstanceState?.getString("pendingId")?.let {
                it to (savedInstanceState.getString("pendingGroup") ?: "game")
            }
        importKey = savedInstanceState?.getBoolean("importKey") ?: false
        adapter = Rows()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        ItemTouchHelper(
                object :
                    ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                    override fun onMove(
                        rv: RecyclerView,
                        from: RecyclerView.ViewHolder,
                        to: RecyclerView.ViewHolder,
                    ): Boolean {
                        if (sort != 0 || query.isNotEmpty()) return false
                        val a =
                            adapter.currentList.getOrNull(from.bindingAdapterPosition)
                                ?: return false
                        val b =
                            adapter.currentList.getOrNull(to.bindingAdapterPosition) ?: return false
                        if (!a.item.isCustom || !b.item.isCustom || a.favorite != b.favorite)
                            return false
                        val entries = custom.toMutableList()
                        val index = entries.indexOfFirst { it.id == a.item.id }
                        val target = entries.indexOfFirst { it.id == b.item.id }
                        if (index < 0 || target < 0) return false
                        entries.add(target, entries.removeAt(index))
                        custom = entries
                        store.save(custom)
                        render()
                        return true
                    }

                    override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}
                }
            )
            .attachToRecyclerView(binding.recyclerView)
        binding.swipeRefresh.setOnRefreshListener { refresh() }
        binding.fabAddCustom.setOnClickListener { edit(null) }
        binding.search.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?) = false

                override fun onQueryTextChange(text: String?): Boolean {
                    query = text.orEmpty()
                    render()
                    return true
                }
            }
        )
        WorkManager.getInstance(this).getWorkInfosByTagLiveData("instances").observe(this) { items
            ->
            operations =
                items
                    .groupBy { info ->
                        info.tags
                            .firstOrNull { it.startsWith("instance:") }
                            ?.removePrefix("instance:") ?: ""
                    }
                    .mapValues { (_, jobs) ->
                        jobs.firstOrNull { !it.state.isFinished }
                            ?: jobs.maxByOrNull { info ->
                                info.tags
                                    .firstOrNull { it.startsWith("created:") }
                                    ?.removePrefix("created:")
                                    ?.toLongOrNull() ?: 0L
                            }!!
                    }
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                render()
        }
        render()
        refresh()
        handleInstallResult(intent)
        if (store.hasRecovery()) message(getString(R.string.recovery_notice))
        savedInstanceState?.getString("editorDraft")?.let { json ->
            runCatching {
                val draft = Gson().fromJson(json, Instance::class.java)
                edit(
                    custom.firstOrNull { it.id == savedInstanceState.getString("editingId") },
                    draft,
                )
            }
        }
        query = savedInstanceState?.getString("query").orEmpty()
        binding.search.setQuery(query, false)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("query", query)
        editorView?.let { view ->
            val id = editingId ?: UUID.randomUUID().toString()
            val draft =
                Instance(
                    id = id,
                    name = view.findViewById<TextInputEditText>(R.id.etName).text.toString(),
                    pkg = view.findViewById<TextInputEditText>(R.id.etPackage).text.toString(),
                    mode =
                        listOf("clone", "shortcut", "apk")[
                            view.findViewById<Spinner>(R.id.instanceMode).selectedItemPosition],
                    main = view.findViewById<TextInputEditText>(R.id.etMain).text.toString(),
                    settings =
                        view.findViewById<TextInputEditText>(R.id.etSettings).text.toString(),
                    geodeUrl =
                        if (view.findViewById<CheckBox>(R.id.cbUseGeode).isChecked) "latest"
                        else null,
                )
            out.putString("editorDraft", Gson().toJson(draft))
            out.putString("editingId", editingId)
        }
        out.putString("sourceFor", sourceFor)
        out.putBoolean("importKey", importKey)
        pendingInstall?.let {
            out.putString("pendingId", it.first)
            out.putString("pendingGroup", it.second)
        }
        super.onSaveInstanceState(out)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            render()
            refreshInstalled()
        }
    }

    private fun refreshInstalled() {
        snapshotJob?.cancel()
        snapshotJob =
            lifecycleScope.launch {
                try {
                    val snapshot =
                        withContext(Dispatchers.IO) {
                            packageManager.getInstalledPackages(0).associateBy { it.packageName }
                        }
                    val icons =
                        withContext(Dispatchers.IO) {
                            (custom + server)
                                .mapNotNull { i ->
                                    snapshot[i.pkg]?.let {
                                        runCatching {
                                                i.pkg to packageManager.getApplicationIcon(i.pkg)
                                            }
                                            .getOrNull()
                                    }
                                }
                                .toMap()
                        }
                    installedSnapshot = snapshot
                    appIcons = icons
                    render()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error(e)
                }
            }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallResult(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleInstallResult(intent: Intent) {
        if (intent.action != Installer.ACTION) return
        val token = intent.getStringExtra("install_token") ?: return
        val callbacks = getSharedPreferences("installer", MODE_PRIVATE)
        if (callbacks.getString(token, null) != intent.getStringExtra("install_package")) return
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) runCatching { confirmation.launch(confirm) }.onFailure(::error)
        } else {
            callbacks.edit().remove(token).apply()
            message(
                if (status == PackageInstaller.STATUS_SUCCESS) getString(R.string.install_success)
                else
                    getString(
                        R.string.install_failed,
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty(),
                    )
            )
            refreshInstalled()
            render()
            // Installation is sequential because game and Geode are separate package identities.
            if (status == PackageInstaller.STATUS_SUCCESS) {
                val pkg = intent.getStringExtra("install_package")
                (custom + server)
                    .firstOrNull { it.pkg == pkg && it.usesGeode }
                    ?.let { instance ->
                        if (
                            apks(instance.id, "geode").isNotEmpty() &&
                                !installed(instance.geodePackage)
                        )
                            AlertDialog.Builder(this)
                                .setMessage(R.string.install_geode_next)
                                .setPositiveButton(R.string.install) { _, _ ->
                                    installGroup(instance.id, "geode")
                                }
                                .setNegativeButton(R.string.later, null)
                                .show()
                    }
            }
        }
        intent.action = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        listOf(
                R.string.refresh,
                R.string.check_update,
                R.string.sort,
                R.string.import_instances,
                R.string.export_instances,
                R.string.export_key,
                R.string.restore_key,
                R.string.theme,
                R.string.storage,
                R.string.export_recovery,
                R.string.diagnostics,
            )
            .forEach { menu.add(0, it, 0, it) }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.string.refresh -> refresh()
            R.string.check_update -> checkUpdate()
            R.string.sort ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.sort)
                    .setSingleChoiceItems(resources.getStringArray(R.array.sort_modes), sort) {
                        dialog,
                        which ->
                        sort = which
                        prefs.edit().putInt("sort", which).apply()
                        render()
                        dialog.dismiss()
                    }
                    .show()
            R.string.import_instances -> {
                importKey = false
                openDocument.launch(arrayOf("application/json", "text/plain"))
            }
            R.string.export_instances -> {
                pendingExport = store.export().toByteArray()
                saveDocument.launch("gd-instances.json")
            }
            R.string.export_key ->
                password(getString(R.string.export_key)) { pass ->
                    background {
                        pendingExport =
                            withContext(Dispatchers.IO) {
                                SigningIdentity(this@MainActivity).export(pass.toCharArray())
                            }
                        saveDocument.launch("gd-signing-backup.p12")
                    }
                }
            R.string.restore_key ->
                if (operations.values.any { !it.state.isFinished })
                    message(getString(R.string.wait_operation))
                else
                    AlertDialog.Builder(this)
                        .setMessage(R.string.restore_key_warning)
                        .setPositiveButton(R.string.continue_action) { _, _ ->
                            importKey = true
                            openDocument.launch(
                                arrayOf("application/octet-stream", "application/x-pkcs12", "*/*")
                            )
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
            R.string.theme ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.theme)
                    .setItems(R.array.theme_modes) { _, which ->
                        val mode =
                            listOf(
                                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                                AppCompatDelegate.MODE_NIGHT_NO,
                                AppCompatDelegate.MODE_NIGHT_YES,
                            )[which]
                        prefs.edit().putInt("theme", mode).apply()
                        AppCompatDelegate.setDefaultNightMode(mode)
                    }
                    .show()
            R.string.export_recovery -> {
                pendingExport = store.recovery().toByteArray()
                saveDocument.launch("gd-recovery.json")
            }
            R.string.storage -> storage()
            R.string.diagnostics -> {
                val text =
                    "GD Launcher ${BuildConfig.VERSION_NAME}\nAndroid ${Build.VERSION.SDK_INT}\n${Build.SUPPORTED_ABIS.joinToString()}\nLast refresh: ${store.lastRefresh()}\n" +
                        operations.entries.joinToString("\n") {
                            "${it.key}: ${it.value.state} ${it.value.outputData.getString("error").orEmpty()}"
                        }
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    ClipData.newPlainText("Diagnostics", text)
                )
                message(getString(R.string.diagnostics_copied))
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refresh() {
        refreshJob?.cancel()
        binding.swipeRefresh.isRefreshing = true
        refreshJob =
            lifecycleScope.launch {
                try {
                    val valid =
                        withContext(Dispatchers.IO) {
                            val json =
                                Network.text(
                                    "https://api.pcpapc172.ir/archive/android_instances.json"
                                )
                            val array = com.google.gson.JsonParser.parseString(json).asJsonArray
                            require(array.size() <= 500) { "Too many server entries" }
                            val raw =
                                array.map { element ->
                                    require(
                                        element.isJsonObject &&
                                            element.asJsonObject.has("id") &&
                                            element.asJsonObject.has("name")
                                    ) {
                                        "Server entries require id and name"
                                    }
                                    Gson().fromJson(element, Instance::class.java)
                                }
                            val result =
                                raw.map { entry ->
                                    require(entry.id != "launcher_update") {
                                        "Reserved instance ID"
                                    }
                                    entry
                                        .copy(
                                            pkg =
                                                entry.pkg.takeIf { !it.isNullOrBlank() }
                                                    ?: Instance.packageFor(entry.id),
                                            mode = "clone",
                                            isCustom = false,
                                        )
                                        .also(Validation::check)
                                }
                            require(
                                result.map { it.id }.distinct().size == result.size &&
                                    result.map { it.pkg }.distinct().size == result.size
                            ) {
                                "Duplicate server entries"
                            }
                            require(
                                result.none { s -> custom.any { it.id == s.id || it.pkg == s.pkg } }
                            ) {
                                "Server entry conflicts with a custom entry"
                            }
                            result
                        }
                    server = valid
                    store.cache(valid)
                    refreshInstalled()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message(getString(R.string.refresh_failed, e.message.orEmpty()))
                } finally {
                    if (isActive) {
                        binding.swipeRefresh.isRefreshing = false
                        render()
                    }
                }
            }
    }

    private fun render() {
        if (!::adapter.isInitialized) return
        val list =
            (custom + server).filter {
                it.name.contains(query, true) || it.pkg.contains(query, true)
            }
        val sorted =
            when (sort) {
                1 -> list.sortedBy { it.name.lowercase() }
                2 -> list.sortedByDescending { installed(it.pkg) }
                else -> list
            }.sortedByDescending { isFavorite(it) }
        adapter.submitList(
            sorted.map {
                Row(
                    it,
                    operations[it.id],
                    installed(it.pkg),
                    installed(it.geodePackage),
                    ready(it.id),
                    isFavorite(it),
                    installedVersion(it.pkg),
                )
            }
        )
        binding.emptyState.visibility =
            if (sorted.isEmpty() && !binding.swipeRefresh.isRefreshing) View.VISIBLE else View.GONE
        binding.status.text =
            if (store.lastRefresh() == 0L) getString(R.string.no_cache)
            else
                getString(
                    R.string.last_refresh,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(java.util.Date(store.lastRefresh())),
                )
        binding.status.setOnClickListener { refresh() }
        val update = operations["launcher_update"]
        binding.updateReady.visibility =
            if (ready("launcher_update") || update != null && !update.state.isFinished) View.VISIBLE
            else View.GONE
        binding.updateReady.isEnabled =
            ready("launcher_update") && update?.state?.isFinished != false
        binding.updateReady.text =
            if (update != null && !update.state.isFinished) getString(R.string.preparing)
            else getString(R.string.install_launcher_update)
        binding.updateReady.setOnClickListener { installGroup("launcher_update", "game") }
        if (
            update?.state == WorkInfo.State.FAILED &&
                prefs.getString("last_update_error", null) != update.id.toString()
        ) {
            prefs.edit().putString("last_update_error", update.id.toString()).apply()
            message(update.outputData.getString("error") ?: getString(R.string.update_failed))
        }
    }

    private fun isFavorite(i: Instance) =
        if (i.isCustom) i.favorite else prefs.getBoolean("favorite:${i.id}", i.favorite)

    private fun installed(pkg: String) = installedSnapshot.containsKey(pkg)

    @Suppress("DEPRECATION")
    private fun installedCode(pkg: String): Long =
        installedSnapshot[pkg]?.let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        } ?: 0L

    private fun installedVersion(pkg: String) = installedSnapshot[pkg]?.versionName

    private fun apks(id: String, group: String) =
        File(store.root(id), group)
            .listFiles()
            ?.filter { it.extension == "apk" }
            ?.sortedBy { it.name } ?: emptyList()

    private fun preparedCode(id: String): Long =
        runCatching { File(store.root(id), "version.txt").readText().toLong() }.getOrDefault(0L)

    private fun ready(id: String) =
        if (id == "launcher_update")
            apks(id, "game").isNotEmpty() && preparedCode(id) > BuildConfig.VERSION_CODE
        else (custom + server).firstOrNull { it.id == id }?.let(store::prepared) ?: false

    private fun prepare(i: Instance) {
        try {
            if (i.mode == "apk" && !store.source(i.id).exists()) {
                sourceFor = i.id
                chooseApk.launch(
                    arrayOf("application/vnd.android.package-archive", "application/octet-stream")
                )
                return
            }
            if (i.mode == "clone" && i.url.isNullOrBlank() && !installed(GAME_PACKAGE)) {
                message(getString(R.string.original_required))
                return
            }
            if (
                Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
            )
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            InstanceWorker.enqueue(this, i)
            message(getString(R.string.queued))
        } catch (e: Exception) {
            error(e)
        }
    }

    private fun installGroup(id: String, group: String) {
        if (operations[id]?.state?.isFinished == false) {
            message(getString(R.string.wait_operation))
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstall = id to group
            permission.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        val i = (custom + server).find { it.id == id }
        val pkg =
            if (id == "launcher_update") packageName
            else if (group == "geode") i?.geodePackage else i?.pkg
        if (pkg == null) return
        if (id != "launcher_update" && i != null && !store.prepared(i)) {
            message(getString(R.string.prepare_again_required))
            return
        }
        background {
            withContext(Dispatchers.IO) {
                val files = apks(id, group)
                require(files.isNotEmpty()) { "No prepared APKs for this component" }
                files.forEach { apk ->
                    java.util.zip.ZipFile(apk).use { zip ->
                        val actual =
                            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use {
                                ir.pcpapc172.gdlauncher1.patch.BinaryPatch.packageName(
                                    it.readBytesLimited(8 * 1024 * 1024)
                                )
                            }
                        require(actual == pkg) {
                            "Prepared APK belongs to $actual; prepare this instance again"
                        }
                    }
                }
                Installer.install(this@MainActivity, files, pkg)
            }
            message(getString(R.string.installer_opening))
        }
    }

    private fun launch(i: Instance, settings: Boolean = false) {
        try {
            val target = if (i.usesGeode) i.geodePackage else i.pkg
            val component = if (settings) i.settings else i.main
            val explicit =
                component?.takeIf { it.isNotBlank() }?.let { ComponentName.unflattenFromString(it) }
            val intent =
                when {
                    settings && i.usesGeode && explicit == null ->
                        Intent()
                            .setComponent(
                                ComponentName(
                                    i.geodePackage,
                                    "com.geode.launcher.preferences.SettingsActivity",
                                )
                            )
                    explicit != null -> Intent(Intent.ACTION_MAIN).setComponent(explicit)
                    settings ->
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${i.pkg}"),
                        )
                    else -> packageManager.getLaunchIntentForPackage(target)
                }
            if (intent == null) error("App has no launcher activity")
            try {
                startActivity(intent)
            } catch (e: Exception) {
                if (!settings && explicit != null) {
                    val fallback = packageManager.getLaunchIntentForPackage(target) ?: throw e
                    startActivity(fallback)
                } else throw e
            }
        } catch (e: Exception) {
            error(e)
        }
    }

    private fun actions(i: Instance) {
        val labels = buildList {
            if (installed(if (i.usesGeode) i.geodePackage else i.pkg)) add(R.string.launch)
            if (installed(i.pkg)) add(R.string.settings)
            if (ready(i.id)) {
                add(R.string.install_prepared)
                if (i.usesGeode) add(R.string.install_geode)
            }
            if (i.mode != "shortcut") add(R.string.prepare_again)
            if (i.mode == "apk") add(R.string.select_source)
            if (i.isCustom) add(R.string.edit)
            add(R.string.favorite)
            if (installed(i.pkg) || installed(i.geodePackage)) add(R.string.uninstall)
            if (i.isCustom) add(R.string.remove)
            if (store.root(i.id).exists()) add(R.string.delete_downloads)
        }
        AlertDialog.Builder(this)
            .setTitle(i.name)
            .setItems(labels.map(::getString).toTypedArray()) { _, n ->
                when (labels[n]) {
                    R.string.launch -> launch(i)
                    R.string.settings -> launch(i, true)
                    R.string.install_prepared -> installGroup(i.id, "game")
                    R.string.install_geode -> installGroup(i.id, "geode")
                    R.string.prepare_again -> prepare(i)
                    R.string.select_source -> {
                        if (i.mode == "apk") {
                            sourceFor = i.id
                            chooseApk.launch(
                                arrayOf(
                                    "application/vnd.android.package-archive",
                                    "application/octet-stream",
                                )
                            )
                        } else message(getString(R.string.local_source_only))
                    }
                    R.string.edit ->
                        if (i.isCustom) edit(i) else message(getString(R.string.server_readonly))
                    R.string.favorite -> {
                        if (i.isCustom) {
                            custom =
                                custom.map {
                                    if (it.id == i.id) it.copy(favorite = !i.favorite) else it
                                }
                            store.save(custom)
                        } else prefs.edit().putBoolean("favorite:${i.id}", !isFavorite(i)).apply()
                        render()
                    }
                    R.string.uninstall ->
                        AlertDialog.Builder(this)
                            .setTitle(R.string.uninstall)
                            .setItems(
                                if (i.usesGeode) arrayOf(i.pkg, i.geodePackage) else arrayOf(i.pkg)
                            ) { _, which ->
                                runCatching {
                                        startActivity(
                                            Intent(
                                                Intent.ACTION_DELETE,
                                                Uri.parse(
                                                    "package:${if(which==0)i.pkg else i.geodePackage}"
                                                ),
                                            )
                                        )
                                    }
                                    .onFailure(::error)
                            }
                            .show()
                    R.string.remove -> remove(i)
                    R.string.delete_downloads ->
                        if (operations[i.id]?.state?.isFinished == false)
                            message(getString(R.string.wait_operation))
                        else
                            background {
                                withContext(Dispatchers.IO) { store.root(i.id).deleteRecursively() }
                                render()
                            }
                }
            }
            .show()
    }

    private fun remove(i: Instance) {
        if (!i.isCustom) {
            message(getString(R.string.server_readonly))
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.remove_warning)
            .setPositiveButton(R.string.remove) { _, _ ->
                val index = custom.indexOfFirst { it.id == i.id }
                if (index >= 0) {
                    custom = custom.filterNot { it.id == i.id }
                    store.save(custom)
                    render()
                    Snackbar.make(binding.root, R.string.removed, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) {
                            custom =
                                custom.toMutableList().apply { add(index.coerceAtMost(size), i) }
                            store.save(custom)
                            render()
                        }
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun edit(existing: Instance?, draft: Instance? = null) {
        val initial = draft ?: existing
        val view = layoutInflater.inflate(R.layout.dialog_custom_instance, null)
        val name = view.findViewById<TextInputEditText>(R.id.etName)
        val pkg = view.findViewById<TextInputEditText>(R.id.etPackage)
        val advanced = view.findViewById<TextInputEditText>(R.id.etPackageAdv)
        val settings = view.findViewById<TextInputEditText>(R.id.etSettings)
        val main = view.findViewById<TextInputEditText>(R.id.etMain)
        val geode = view.findViewById<CheckBox>(R.id.cbUseGeode)
        val layout = view.findViewById<LinearLayout>(R.id.layoutAdvanced)
        val mode = view.findViewById<Spinner>(R.id.instanceMode)
        mode.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.instance_modes),
            )
        val id = initial?.id ?: UUID.randomUUID().toString()
        val modes = listOf("clone", "shortcut", "apk")
        mode.setSelection(modes.indexOf(initial?.mode ?: "clone").coerceAtLeast(0))
        name.setText(initial?.name.orEmpty())
        pkg.setText(initial?.pkg ?: Instance.packageFor(id))
        geode.isChecked = initial?.usesGeode == true
        advanced.visibility = View.GONE
        view.findViewById<TextInputLayout>(R.id.tilPackage).visibility = View.GONE
        settings.setText(initial?.settings.orEmpty())
        main.setText(initial?.main.orEmpty())
        view.findViewById<Button>(R.id.btnGeneratePackage).setOnClickListener {
            pkg.setText(Instance.packageFor(UUID.randomUUID().toString()))
        }
        view.findViewById<Button>(R.id.btnBrowseApps).setOnClickListener {
            pickApp { p, label ->
                mode.setSelection(1)
                pkg.setText(p)
                if (name.text.isNullOrBlank()) name.setText(label)
            }
        }
        view.findViewById<Button>(R.id.btnToggleAdvanced).setOnClickListener {
            layout.visibility = if (layout.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        val packageLayout = pkg.parent.parent as? TextInputLayout
        packageLayout?.isCounterEnabled = true
        fun validatePackage() {
            val value = pkg.text.toString().trim()
            val fixed = mode.selectedItemPosition != 1 && existing?.url.isNullOrBlank()
            packageLayout?.counterMaxLength = if (fixed) 24 else -1
            packageLayout?.error =
                when {
                    value.isBlank() -> null
                    !Validation.packageName(value) -> getString(R.string.invalid_package)
                    fixed && value.length != 24 -> getString(R.string.package_length)
                    else -> null
                }
        }
        pkg.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    validatePackage()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
        name.filters = arrayOf(android.text.InputFilter.LengthFilter(100))
        validatePackage()
        mode.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    selected: View?,
                    position: Int,
                    id: Long,
                ) {
                    geode.isEnabled = position != 1
                    if (position == 1) geode.isChecked = false
                    validatePackage()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        editorView = view
        editingId = existing?.id
        val dialog =
            AlertDialog.Builder(this)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .create()
        dialog.setOnDismissListener {
            if (editorView === view) {
                editorView = null
                editingId = null
            }
        }
        view.findViewById<Button>(R.id.btnCreate).apply {
            setText(if (existing == null) R.string.create else R.string.save)
            setOnClickListener {
                try {
                    val newMode = modes[mode.selectedItemPosition]
                    val i =
                        Instance(
                            id,
                            name.text.toString().trim(),
                            pkg.text.toString().trim(),
                            url = existing?.url?.takeIf { newMode == existing.mode },
                            version = existing?.version,
                            favorite = existing?.favorite ?: false,
                            sha256 = existing?.sha256,
                            signerSha256 = existing?.signerSha256,
                            versionCode = existing?.versionCode,
                            settings = settings.text.toString().trim().ifBlank { null },
                            main = main.text.toString().trim().ifBlank { null },
                            geodeUrl =
                                if (geode.isChecked) existing?.geodeUrl ?: "latest" else null,
                            mode = newMode,
                        )
                    Validation.check(i)
                    require((custom + server).none { it.id != id && it.pkg == i.pkg }) {
                        "This package is already in the list"
                    }
                    if (existing != null)
                        require(operations[id]?.state?.isFinished != false) {
                            "Cancel the active operation before editing"
                        }
                    if (existing != null && existing != i) {
                        store.root(id).deleteRecursively()
                    }
                    custom =
                        if (existing == null) listOf(i) + custom
                        else custom.map { if (it.id == id) i else it }
                    store.save(custom)
                    render()
                    dialog.dismiss()
                    if (i.mode == "apk" && !store.source(id).exists()) {
                        sourceFor = id
                        chooseApk.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream",
                            )
                        )
                    }
                } catch (e: Exception) {
                    pkg.error = e.message
                    name.error =
                        if (name.text.isNullOrBlank()) getString(R.string.name_required) else null
                }
            }
        }
        dialog.show()
    }

    private fun pickApp(picked: (String, String) -> Unit) {
        background {
            val apps =
                withContext(Dispatchers.IO) {
                    packageManager
                        .getInstalledApplications(0)
                        .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                        .map { it to packageManager.getApplicationLabel(it).toString() }
                        .sortedBy { it.second.lowercase() }
                }
            val layout =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 10, 20, 10)
                }
            val search =
                EditText(this@MainActivity).apply {
                    setHint(R.string.search_apps)
                    isSingleLine = true
                }
            val list = ListView(this@MainActivity)
            layout.addView(search)
            layout.addView(
                list,
                LinearLayout.LayoutParams(
                    -1,
                    (resources.displayMetrics.heightPixels * 0.55).toInt(),
                ),
            )
            var filtered = apps
            fun update(text: String) {
                filtered =
                    apps.filter {
                        it.second.contains(text, true) || it.first.packageName.contains(text, true)
                    }
                list.adapter =
                    object :
                        ArrayAdapter<String>(
                            this@MainActivity,
                            android.R.layout.simple_list_item_1,
                            filtered.map { "${it.second}\n${it.first.packageName}" },
                        ) {
                        override fun getView(
                            position: Int,
                            convertView: View?,
                            parent: ViewGroup,
                        ): View {
                            val text = super.getView(position, convertView, parent) as TextView
                            val drawable =
                                packageManager.getApplicationIcon(filtered[position].first).mutate()
                            val size = (40 * resources.displayMetrics.density).toInt()
                            drawable.setBounds(0, 0, size, size)
                            text.setCompoundDrawablesRelative(drawable, null, null, null)
                            text.compoundDrawablePadding = 16
                            return text
                        }
                    }
            }
            update("")
            search.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        update(s.toString())
                    }

                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
            )
            val dialog =
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.pick_app)
                    .setView(layout)
                    .setNegativeButton(R.string.cancel, null)
                    .create()
            list.setOnItemClickListener { _, _, position, _ ->
                val app = filtered[position]
                picked(app.first.packageName, app.second)
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun checkUpdate() {
        background {
            val update =
                withContext(Dispatchers.IO) {
                    JSONObject(
                        Network.text("https://api.pcpapc172.ir/archive/latestandroidlauncher.json")
                    )
                }
            if (update.getLong("version_code") <= BuildConfig.VERSION_CODE) {
                message(getString(R.string.up_to_date))
                return@background
            }
            val url = update.getString("download_url")
            require(Validation.https(url)) { "Update URL must use HTTPS" }
            val i =
                Instance(
                    "launcher_update",
                    getString(R.string.app_name),
                    packageName,
                    url,
                    sha256 = update.optString("sha256").ifBlank { null },
                    mode = "clone",
                )
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.update_available)
                .setMessage(
                    update.optString("version_name") + "\n\n" + update.optString("changelog")
                )
                .setPositiveButton(R.string.download) { _, _ ->
                    runCatching { InstanceWorker.enqueue(this@MainActivity, i) }.onFailure(::error)
                }
                .setNegativeButton(R.string.later, null)
                .show()
            // User consent remains required; server flags cannot lock users out of their launcher.
        }
    }

    private fun storage() {
        background {
            val size =
                withContext(Dispatchers.IO) {
                    File(filesDir, "apks").walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.storage)
                .setMessage(getString(R.string.storage_summary, size / 1024 / 1024))
                .setPositiveButton(R.string.delete_downloads) { _, _ ->
                    if (operations.values.any { !it.state.isFinished })
                        message(getString(R.string.wait_operation))
                    else
                        background {
                            withContext(Dispatchers.IO) {
                                File(filesDir, "apks").deleteRecursively()
                                File(filesDir, "staging").deleteRecursively()
                            }
                            render()
                        }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun password(title: String, action: (String) -> Unit) {
        val field =
            EditText(this).apply {
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setHint(R.string.password_hint)
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(field)
                .setPositiveButton(R.string.continue_action, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (field.text.length < 12) field.error = getString(R.string.password_hint)
                else {
                    dialog.dismiss()
                    action(field.text.toString())
                }
            }
        }
        dialog.show()
    }

    private fun background(action: suspend CoroutineScope.() -> Unit) {
        lifecycleScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(e)
            }
        }
    }

    private fun error(e: Throwable) {
        message(e.message ?: e.javaClass.simpleName)
    }

    private fun message(text: String) {
        if (!isFinishing) Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    data class Row(
        val item: Instance,
        val work: WorkInfo?,
        val installed: Boolean,
        val geodeInstalled: Boolean,
        val ready: Boolean,
        val favorite: Boolean,
        val version: String?,
    )

    inner class Rows :
        ListAdapter<Row, Rows.Holder>(
            object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(a: Row, b: Row) = a.item.id == b.item.id

                override fun areContentsTheSame(a: Row, b: Row) = a == b
            }
        ) {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvName)
            val version: TextView = view.findViewById(R.id.tvVersion)
            val action: Button = view.findViewById(R.id.btnAction)
            val settings: ImageButton = view.findViewById(R.id.btnSettings)
            val uninstall: ImageButton = view.findViewById(R.id.btnUninstall)
            val more: ImageButton = view.findViewById(R.id.btnDeleteCustom)
            val progress: LinearLayout = view.findViewById(R.id.layoutProgress)
            val bar: ProgressBar = view.findViewById(R.id.progressBar)
            val percent: TextView = view.findViewById(R.id.tvPercentage)
            val icon: ImageView = view.findViewById(R.id.appIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
            Holder(layoutInflater.inflate(R.layout.item_instance, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val row = getItem(position)
            val i = row.item
            val work = row.work
            val active = work != null && !work.state.isFinished
            h.name.text = if (row.favorite) getString(R.string.favorite_name, i.name) else i.name
            h.version.text =
                row.version
                    ?: i.version
                    ?: getString(if (i.mode == "shortcut") R.string.shortcut else R.string.vanilla)
            h.icon.setImageDrawable(
                appIcons[i.pkg]
                    ?: androidx.core.content.ContextCompat.getDrawable(
                        this@MainActivity,
                        R.mipmap.ic_launcher,
                    )
            )
            h.progress.visibility =
                if (active || work?.state == WorkInfo.State.FAILED) View.VISIBLE else View.GONE
            val percent = work?.progress?.getInt("percent", -1) ?: -1
            h.bar.isIndeterminate = percent < 0
            h.bar.progress = percent.coerceAtLeast(0)
            h.percent.text =
                if (work?.state == WorkInfo.State.FAILED) work.outputData.getString("error")
                else work?.progress?.getString("status") ?: getString(R.string.queued)
            h.action.isEnabled = true
            when {
                active -> {
                    h.action.setText(R.string.cancel)
                    h.action.setOnClickListener {
                        WorkManager.getInstance(this@MainActivity)
                            .cancelUniqueWork(InstanceWorker.name(i.id))
                    }
                }
                row.installed &&
                    i.usesGeode &&
                    !row.geodeInstalled &&
                    apks(i.id, "geode").isNotEmpty() -> {
                    h.action.setText(R.string.install_geode)
                    h.action.setOnClickListener { installGroup(i.id, "geode") }
                }
                row.installed &&
                    i.versionCode != null &&
                    installedCode(i.pkg) < i.versionCode &&
                    !row.ready -> {
                    h.action.setText(R.string.update_instance)
                    h.action.setOnClickListener { prepare(i) }
                }
                row.installed && row.ready && preparedCode(i.id) > installedCode(i.pkg) -> {
                    h.action.setText(R.string.install_update)
                    h.action.setOnClickListener { installGroup(i.id, "game") }
                }
                row.installed && (!i.usesGeode || row.geodeInstalled) -> {
                    h.action.setText(R.string.launch)
                    h.action.setOnClickListener { launch(i) }
                }
                row.ready -> {
                    h.action.setText(R.string.install)
                    h.action.setOnClickListener { installGroup(i.id, "game") }
                }
                i.mode == "shortcut" -> {
                    h.action.setText(R.string.not_installed)
                    h.action.isEnabled = false
                    h.action.setOnClickListener(null)
                }
                else -> {
                    h.action.setText(
                        if (work?.state == WorkInfo.State.FAILED) R.string.retry
                        else R.string.prepare
                    )
                    h.action.setOnClickListener { prepare(i) }
                }
            }
            h.settings.visibility = if (row.installed) View.VISIBLE else View.GONE
            h.settings.setOnClickListener { launch(i, true) }
            h.uninstall.visibility = View.GONE
            h.more.visibility = View.VISIBLE
            h.more.setImageResource(android.R.drawable.ic_menu_more)
            h.more.contentDescription = getString(R.string.more_actions)
            h.more.setOnClickListener { actions(i) }
        }
    }
}
