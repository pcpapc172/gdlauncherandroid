package ir.pcpapc172.gdlauncher1

import org.json.JSONObject

object UpdateCatalog {
    const val LAUNCHER = "launcher_update"
    const val LAUNCHER_URL = "https://pcpapc172.ir/archive/latestandroidlauncher.json"
    const val INSTANCES_URL = "https://pcpapc172.ir/archive/android_instances.json"

    fun read(url: String, instance: Instance): Instance {
        val json = JSONObject(Network.text(url))
        val sha = json.getString("sha256")
        require(Regex("[a-fA-F0-9]{64}").matches(sha)) { "Update manifest needs a SHA-256 checksum" }
        val rawCode = json.get("version_code")
        require(rawCode is Int || rawCode is Long) { "Update version_code must be an integer" }
        val versionCode = (rawCode as Number).toLong()
        val download = java.net.URI(url).resolve(json.getString("download_url")).toString()
        return instance.copy(
            url = download,
            sha256 = sha,
            versionCode = versionCode,
            version = json.optString("version_name").ifBlank { null },
        ).also(Validation::check)
    }
}
