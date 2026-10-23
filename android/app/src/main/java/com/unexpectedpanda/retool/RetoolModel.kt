package com.unexpectedpanda.retool

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class RetoolModel(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf(UiState())
        private set
    var catalog by mutableStateOf(Catalog())
        private set
    var log by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var status by mutableStateOf("Starting the filter…")
        private set
    var banner by mutableStateOf<String?>(null)
        private set
    var savedFiles by mutableStateOf<List<File>>(emptyList())
        private set
    var selectedSystem by mutableStateOf<String?>(null)
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var bridge: PyObject? = null
    private val stateFile = File(app.filesDir, "ui-state.json")

    init {
        executor.execute { start() }
    }

    private fun start() {
        try {
            val root = getApplication<Application>().filesDir
            File(root, "config").mkdirs()
            getApplication<Application>().assets.open("config/internal-config.json").use { input ->
                File(root, "config/internal-config.json").outputStream().use { input.copyTo(it) }
            }
            if (!Python.isStarted()) {
                Python.start(com.chaquo.python.android.AndroidPlatform(getApplication()))
            }
            val py = Python.getInstance()
            val module = py.getModule("modules.android_bridge")
            module.callAttr("set_listener", LogSink { line ->
                main.post {
                    log = (log + line).takeLast(24_000)
                }
            })
            module.callAttr("configure", root.absolutePath)
            bridge = module
            val parsed = JSONObject(module.callAttr("catalog").toString())
            val loadedCatalog = Catalog(
                regions = parsed.stringList("regions"),
                languages = parsed.stringList("languages"),
                video = parsed.stringList("video"),
                version = parsed.optString("version"),
            )
            val loaded = if (stateFile.isFile) {
                UiState.fromJson(stateFile.readText())
            } else {
                UiState()
            }
            val global = if (loaded.initialized) {
                loaded.global
            } else {
                loaded.global.copy(regions = loadedCatalog.regions, video = loadedCatalog.video)
            }
            val next = loaded.copy(initialized = true, global = global).withoutMissingFiles()
            main.post {
                catalog = loadedCatalog
                ui = next
                selectedSystem = next.files.firstOrNull()?.systemName
                ready = true
                status = ""
                persist(next)
            }
        } catch (error: Exception) {
            main.post {
                ready = false
                status = error.stackTraceToString()
            }
        }
    }

    fun dismissBanner() {
        banner = null
    }

    fun clearLog() {
        log = ""
    }

    fun selectSystem(name: String) {
        selectedSystem = name
    }

    fun updateGlobal(transform: (Profile) -> Profile) {
        update(ui.copy(global = transform(ui.global)))
    }

    fun updateSystem(name: String, transform: (Profile) -> Profile) {
        val current = ui.systems[name] ?: return
        update(ui.copy(systems = ui.systems + (name to transform(current))))
    }

    fun customizeSystem(name: String) {
        if (name.isBlank() || name in ui.systems) return
        update(ui.copy(systems = ui.systems + (name to ui.global.detached())))
        selectedSystem = name
    }

    fun clearSystem(name: String) {
        update(ui.copy(systems = ui.systems - name))
    }

    fun setMetadataUrl(url: String) {
        update(ui.copy(metadataUrl = url))
    }

    fun removeFile(file: DatFile) {
        update(ui.copy(files = ui.files.filterNot { it.path == file.path }))
    }

    fun notify(text: String) {
        banner = text
    }

    fun importExample() {
        executor.execute {
            markBusy(true)
            try {
                val dest = File(inbox(), "Retool Android Example.dat")
                getApplication<Application>().assets.open("example.dat").use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                addLocalFile(dest)
            } catch (error: Exception) {
                postBanner(error.message ?: "Could not open the example DAT")
            } finally {
                markBusy(false)
            }
        }
    }

    fun importUris(context: Context, uris: List<Uri>) {
        executor.execute {
            markBusy(true)
            try {
                uris.forEach { uri ->
                    val dest = copyUri(context, uri, inbox())
                    addLocalFile(dest)
                }
            } catch (error: Exception) {
                postBanner(error.message ?: "Could not read that file")
            } finally {
                markBusy(false)
            }
        }
    }

    fun importTree(context: Context, tree: Uri) {
        executor.execute {
            markBusy(true)
            try {
                val root = DocumentFile.fromTreeUri(context, tree)
                val found = root?.listFiles().orEmpty().filter { file ->
                    file.isFile && file.name.orEmpty().endsWith(".dat", ignoreCase = true)
                }
                if (found.isEmpty()) {
                    postBanner("That folder has no .dat files in its top level.")
                }
                found.forEach { file ->
                    val dest = copyUri(context, file.uri, inbox(), file.name ?: "import.dat")
                    addLocalFile(dest)
                }
            } catch (error: Exception) {
                postBanner(error.message ?: "Could not read that folder")
            } finally {
                markBusy(false)
            }
        }
    }

    fun downloadDat(urlText: String) {
        val url = urlText.trim()
        if (url.isEmpty()) return
        executor.execute {
            markBusy(true)
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "Retool")
                }
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Download failed (${connection.responseCode})")
                }
                val name = connection.headerFields["Content-Disposition"]
                    ?.firstOrNull()
                    ?.substringAfter("filename=", "")
                    ?.trim('"', ' ', ';')
                    ?.takeIf { it.isNotBlank() }
                    ?: URL(url).path.substringAfterLast('/').ifBlank { "download.dat" }
                val dest = unique(inbox(), if (name.endsWith(".dat", true)) name else "$name.dat")
                connection.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                connection.disconnect()
                addLocalFile(dest)
                postBanner("Downloaded ${dest.name}")
            } catch (error: Exception) {
                postBanner(error.message ?: "Download failed")
            } finally {
                markBusy(false)
            }
        }
    }

    fun importSupportFile(context: Context, uri: Uri, system: String, field: String) {
        executor.execute {
            markBusy(true)
            try {
                val dest = copyUri(context, uri, File(getApplication<Application>().filesDir, "custom"))
                updateSystem(system) { profile ->
                    when (field) {
                        "clone" -> profile.copy(cloneListPath = dest.absolutePath, overridePaths = true)
                        "metadata" -> profile.copy(metadataPath = dest.absolutePath, overridePaths = true)
                        "mia" -> profile.copy(miaPath = dest.absolutePath, overridePaths = true)
                        else -> profile.copy(raPath = dest.absolutePath, overridePaths = true)
                    }
                }
            } catch (error: Exception) {
                postBanner(error.message ?: "Could not copy that file")
            } finally {
                markBusy(false)
            }
        }
    }

    fun updateCloneLists() {
        val module = bridge ?: return
        val url = ui.metadataUrl
        executor.execute {
            markBusy(true)
            try {
                val result = JSONObject(module.callAttr("update_lists", url).toString())
                if (result.optBoolean("ok")) {
                    postBanner("Clone lists and metadata are up to date.")
                } else {
                    postBanner(result.optString("error", "Update failed"))
                }
            } catch (error: Exception) {
                postBanner(error.stackTraceToString().take(2000))
            } finally {
                markBusy(false)
            }
        }
    }

    fun describeTitle(title: String, onResult: (String) -> Unit) {
        val module = bridge ?: return
        executor.execute {
            try {
                val parsed = JSONObject(module.callAttr("title_names", title).toString())
                val text = buildString {
                    append("Short name\n")
                    append(parsed.optString("short"))
                    append("\n\nGroup name\n")
                    append(parsed.optString("group"))
                    append("\n\nRegion-free name\n")
                    append(parsed.optString("regionFree"))
                }
                main.post { onResult(text) }
            } catch (error: Exception) {
                main.post { onResult(error.message ?: "Could not read that title") }
            }
        }
    }

    fun process(context: Context) {
        val module = bridge ?: return
        if (ui.files.isEmpty()) {
            banner = "Add a DAT file first, or try the example."
            return
        }
        if (ui.global.regions.isEmpty()) {
            banner = "Choose at least one region."
            return
        }
        if (ui.global.trace.isNotBlank()) {
            banner = null
        }
        executor.execute {
            markBusy(true)
            savedFiles = emptyList()
            try {
                val output = File(getApplication<Application>().filesDir, "output")
                output.mkdirs()
                val result = JSONObject(module.callAttr("run", ui.jobJson(output.absolutePath)).toString())
                val produced = mutableListOf<File>()
                val array = result.optJSONArray("files")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val file = File(array.getString(i))
                        if (file.isFile) produced += file
                    }
                }
                val exported = produced.map { exportToDownloads(context, it) }
                val errors = result.optJSONArray("errors")
                val errorText = buildString {
                    if (errors != null) {
                        for (i in 0 until errors.length()) {
                            append(errors.getString(i))
                            append("\n")
                        }
                    }
                    if (!result.optBoolean("ok") && result.has("error")) {
                        append(result.optString("error"))
                    }
                }.trim()
                main.post {
                    savedFiles = produced
                    banner = when {
                        produced.isEmpty() && ui.global.trace.isNotBlank() ->
                            "Trace finished. Trace mode does not write a DAT. The log has the steps."
                        produced.isEmpty() && errorText.isNotBlank() -> errorText.take(1500)
                        produced.isEmpty() -> "Finished, and no DAT was written. The titles may all have been filtered out."
                        else -> "Saved ${exported.size} file(s) to Downloads/Retool:\n" + exported.joinToString("\n")
                    }
                }
            } catch (error: Exception) {
                postBanner(error.stackTraceToString().take(2000))
            } finally {
                markBusy(false)
            }
        }
    }

    fun shareSaved(context: Context) {
        val files = savedFiles.filter { it.isFile }
        if (files.isEmpty()) {
            banner = "Process a DAT first."
            return
        }
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }
        val uris = ArrayList<Uri>()
        files.forEach { file ->
            val dest = File(shared, file.name)
            file.copyTo(dest, overwrite = true)
            uris += FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share DAT"))
    }

    private fun addLocalFile(dest: File) {
        val module = bridge ?: return
        val parsed = JSONObject(module.callAttr("identify", dest.absolutePath).toString())
        if (!parsed.optBoolean("ok")) {
            postBanner(parsed.optString("error", "That file is not a DAT Retool can read"))
            return
        }
        val system = parsed.optString("systemName")
        val entry = DatFile(dest.absolutePath, dest.name, system)
        main.post {
            val without = ui.files.filterNot { it.path == dest.absolutePath }
            update(ui.copy(files = without + entry))
            selectedSystem = system
        }
    }

    private fun update(next: UiState) {
        ui = next
        persist(next)
    }

    private fun persist(next: UiState) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(next.toJson().toString())
    }

    private fun inbox(): File = File(getApplication<Application>().filesDir, "inbox").apply { mkdirs() }

    private fun markBusy(value: Boolean) {
        main.post { busy = value }
    }

    private fun postBanner(text: String) {
        main.post { banner = text }
    }

    private fun UiState.withoutMissingFiles(): UiState =
        copy(files = files.filter { File(it.path).isFile })
}

class LogSink(private val append: (String) -> Unit) {
    fun onLog(text: String) {
        append(text)
    }
}

private fun exportToDownloads(context: Context, file: File): String {
    val display = file.name
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, display)
            put(MediaStore.Downloads.MIME_TYPE, if (display.endsWith(".txt")) "text/plain" else "application/xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Retool")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create Downloads/Retool/$display")
        context.contentResolver.openOutputStream(uri).use { out ->
            checkNotNull(out)
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return display
    }
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Retool",
    )
    dir.mkdirs()
    val dest = unique(dir, display)
    file.copyTo(dest, overwrite = true)
    return dest.name
}

private fun copyUri(context: Context, uri: Uri, directory: File, fallbackName: String? = null): File {
    directory.mkdirs()
    val name = fallbackName ?: displayName(context, uri) ?: "import.dat"
    val dest = unique(directory, name)
    context.contentResolver.openInputStream(uri).use { input ->
        checkNotNull(input) { "Could not open $name" }
        dest.outputStream().use { input.copyTo(it) }
    }
    return dest
}

private fun displayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}

private fun unique(directory: File, name: String): File {
    val clean = name.replace(Regex("[\\\\/]+"), "-")
    var dest = File(directory, clean)
    var index = 2
    val stem = clean.substringBeforeLast('.', clean)
    val ext = clean.substringAfterLast('.', "")
    while (dest.exists()) {
        val next = if (ext == clean) "$stem ($index)" else "$stem ($index).$ext"
        dest = File(directory, next)
        index += 1
    }
    return dest
}

private fun org.json.JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) add(array.getString(i))
    }
}
