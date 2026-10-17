package ir.pcpapc172.gdlauncher1

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.util.UUID

const val GAME_PACKAGE = "com.robtopx.geometryjump"

data class Instance(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val pkg: String = "",
    val url: String? = null,
    val version: String? = null,
    val settings: String? = null,
    val main: String? = null,
    @SerializedName("geode_url") val geodeUrl: String? = null,
    @SerializedName("is_custom") val isCustom: Boolean = true,
    val mode: String = "clone",
    val favorite: Boolean = false,
    @SerializedName("version_code") val versionCode: Long? = null,
    val sha256: String? = null,
    @SerializedName("signer_sha256") val signerSha256: String? = null,
) {
    val geodePackage: String
        get() = "gd.g" + hash(pkg).take(14)

    val usesGeode: Boolean
        get() = !geodeUrl.isNullOrBlank()

    companion object {
        fun hash(value: String) =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
                "%02x".format(it)
            }

        fun packageFor(id: String) = "ir.gd.i" + hash(id).take(17)
    }
}

fun hash(value: String) = Instance.hash(value)

object Validation {
    private val packagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    fun packageName(value: String) = value.length <= 200 && packagePattern.matches(value)

    fun https(value: String) =
        try {
            val u = java.net.URI(value)
            u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null
        } catch (_: Exception) {
            false
        }

    fun component(value: String?): Boolean =
        value.isNullOrBlank() ||
            (value.length < 400 &&
                value.split('/').let {
                    it.size == 2 &&
                        packageName(it[0]) &&
                        Regex("\\.?[A-Za-z_][A-Za-z0-9_.$]*").matches(it[1])
                })

    fun check(i: Instance) {
        require(Regex("[A-Za-z0-9_-]{1,80}").matches(i.id)) { "Invalid instance ID" }
        require(i.name.isNotBlank() && i.name.length <= 100) {
            "Name must contain 1–100 characters"
        }
        require(packageName(i.pkg)) { "Invalid package name" }
        require(
            i.pkg != GAME_PACKAGE && i.pkg != "ir.pcpapc172.gdlauncher1" ||
                i.mode == "shortcut" ||
                i.id == "launcher_update"
        ) {
            "Choose a separate instance package"
        }
        require(i.mode in setOf("clone", "shortcut", "apk")) { "Unknown instance type" }
        if (i.mode != "shortcut" && i.url.isNullOrBlank())
            require(i.pkg.length == 24) { "Patched packages must be exactly 24 characters" }
        require(i.version == null || i.version.length <= 100) { "Version label is too long" }
        require(i.url == null || i.url.length <= 2048) { "APK URL is too long" }
        require(i.geodeUrl == null || i.geodeUrl.length <= 2048) { "Geode URL is too long" }
        require(i.versionCode == null || i.versionCode > 0) { "Version code must be positive" }
        require(i.url.isNullOrBlank() || https(i.url)) { "APK URL must use HTTPS" }
        require(i.geodeUrl.isNullOrBlank() || i.geodeUrl == "latest" || https(i.geodeUrl)) {
            "Geode URL must use HTTPS"
        }
        if (i.usesGeode)
            require(i.mode != "shortcut" && i.pkg.length == 24) {
                "Geode is supported for 24-character game clones"
            }
        val componentPackage = if (i.usesGeode) i.geodePackage else i.pkg
        for (value in listOf(i.main, i.settings)) require(
            value.isNullOrBlank() || value.substringBefore('/') == componentPackage
        ) {
            "Activity must belong to $componentPackage"
        }
        require(component(i.main) && component(i.settings)) {
            "Use package/.Activity or package/full.Activity"
        }
        for (digest in listOf(i.sha256, i.signerSha256)) require(
            digest == null || Regex("[a-fA-F0-9]{64}").matches(digest)
        ) {
            "Invalid SHA-256 digest"
        }
    }
}

class InstanceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("gd_launcher_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<Instance>>() {}.type

    fun custom(): List<Instance> = read("custom_instances", true)

    fun cached(): List<Instance> = read("server_instances_cache", false)

    private fun read(key: String, custom: Boolean): List<Instance> {
        val original = prefs.getString(key, "[]") ?: "[]"
        return try {
            val array = com.google.gson.JsonParser.parseString(original).asJsonArray
            require(array.size() <= 500) { "Too many saved entries" }
            var invalid = false
            val result =
                array.mapNotNull { element ->
                    runCatching {
                            val raw = gson.fromJson(element, Instance::class.java)
                            val mode =
                                if (!element.asJsonObject.has("mode") && custom) "shortcut"
                                else raw.mode
                            val i =
                                raw.copy(
                                    isCustom = custom,
                                    mode = mode,
                                    geodeUrl = if (mode == "shortcut") null else raw.geodeUrl,
                                    pkg =
                                        raw.pkg.takeIf { !it.isNullOrBlank() }
                                            ?: Instance.packageFor(raw.id),
                                )
                            Validation.check(i)
                            i
                        }
                        .onFailure { invalid = true }
                        .getOrNull()
                }
            if (invalid) prefs.edit().putString("${key}_recovery", original).apply()
            result.distinctBy { it.id }.distinctBy { it.pkg }
        } catch (e: Exception) {
            prefs.edit().putString("${key}_recovery", original).apply()
            emptyList()
        }
    }

    fun recovery(): String =
        gson.toJson(
            mapOf(
                "custom" to prefs.getString("custom_instances_recovery", null),
                "server" to prefs.getString("server_instances_cache_recovery", null),
            )
        )

    fun hasRecovery() =
        prefs.contains("custom_instances_recovery") ||
            prefs.contains("server_instances_cache_recovery")

    fun save(items: List<Instance>) {
        items.forEach(Validation::check)
        prefs.edit().putString("custom_instances", gson.toJson(items)).apply()
    }

    fun cache(items: List<Instance>) {
        prefs
            .edit()
            .putString(
                "server_instances_cache",
                gson.toJson(items.map { it.copy(isCustom = false) }),
            )
            .putLong("last_refresh", System.currentTimeMillis())
            .apply()
    }

    fun lastRefresh() = prefs.getLong("last_refresh", 0)

    fun root(id: String): File {
        require(Regex("[A-Za-z0-9_-]{1,80}").matches(id))
        return File(context.filesDir, "apks/$id")
    }

    fun source(id: String): File {
        require(Regex("[A-Za-z0-9_-]{1,80}").matches(id))
        return File(context.filesDir, "sources/$id.apk")
    }

    fun recipe(i: Instance) =
        hash(gson.toJson(i.copy(isCustom = false, favorite = false, settings = null, main = null)))

    fun prepared(i: Instance): Boolean =
        runCatching {
                File(root(i.id), "recipe.txt").readText() == recipe(i) &&
                    File(root(i.id), "game").listFiles().orEmpty().any { it.extension == "apk" }
            }
            .getOrDefault(false)

    fun export(): String = gson.toJson(custom())

    fun import(text: String): List<Instance> {
        require(text.length <= 1_000_000) { "Import too large" }
        val imported: List<Instance> =
            gson.fromJson(text, type) ?: error("Expected an instance array")
        require(imported.size <= 500) { "Too many instances" }
        val merged = custom().associateBy { it.id }.toMutableMap()
        for (raw in imported) {
            val i = raw.copy(isCustom = true)
            require(i.id != "launcher_update") { "Reserved instance ID" }
            Validation.check(i)
            merged[i.id] = i
        }
        require(merged.values.map { it.pkg }.distinct().size == merged.size) {
            "Duplicate package names"
        }
        val result = merged.values.toList()
        save(result)
        return result
    }
}
