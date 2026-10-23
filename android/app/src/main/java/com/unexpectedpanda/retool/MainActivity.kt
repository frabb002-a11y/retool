package com.unexpectedpanda.retool

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker

class MainActivity : ComponentActivity() {
    private val model: RetoolModel by viewModels()
    private var pendingAction: (() -> Unit)? = null

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
        ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) action?.invoke() else model.notify("Storage permission is required to save the DAT on this Android version.")
    }

    private val openDats = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.importUris(this, uris)
    }

    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // The folder can still be read for this session.
            }
            model.importTree(this, uri)
        }
    }

    private val openSupport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pendingSupport
        pendingSupport = null
        if (uri != null && request != null) {
            model.importSupportFile(this, uri, request.first, request.second)
        }
    }

    private var pendingSupport: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RetoolScaffold(
                        model = model,
                        onAddFiles = { openDats.launch(arrayOf("*/*")) },
                        onAddFolder = { openFolder.launch(null) },
                        onProcess = {
                            withStorage { model.process(this) }
                        },
                        onShare = { model.shareSaved(this) },
                        onPickSupport = { system, field ->
                            pendingSupport = system to field
                            openSupport.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
        }
    }

    private fun withStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PermissionChecker.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingAction = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

private enum class Tab(val label: String) { Process("Process"), Global("Global"), System("System"), Data("Data") }

@Composable
private fun RetoolScaffold(
    model: RetoolModel,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onProcess: () -> Unit,
    onShare: () -> Unit,
    onPickSupport: (String, String) -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.Process) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {},
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.banner?.let { text ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text)
                        TextButton(onClick = model::dismissBanner) { Text("Dismiss") }
                    }
                }
            }
            if (!model.ready) {
                Text(if (model.status.isBlank()) "Starting the filter…" else model.status)
                return@Column
            }
            when (tab) {
                Tab.Process -> ProcessPage(model, onAddFiles, onAddFolder, onProcess, onShare)
                Tab.Global -> SettingsPage(
                    title = "Global settings",
                    profile = model.ui.global,
                    catalog = model.catalog,
                    system = false,
                    onChange = model::updateGlobal,
                    onPickSupport = { _ -> },
                )
                Tab.System -> SystemPage(model, onPickSupport)
                Tab.Data -> DataPage(model)
            }
        }
    }
}

@Composable
private fun ProcessPage(
    model: RetoolModel,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onProcess: () -> Unit,
    onShare: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Filter a DAT", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Add a Redump or No-Intro DAT, or paste a URL and download one. " +
                "Retool writes a new DAT and saves it to Downloads/Retool. The original file stays where it is.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAddFiles, enabled = !model.busy) { Text("Add DATs") }
            OutlinedButton(onClick = onAddFolder, enabled = !model.busy) { Text("Add folder") }
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("DAT URL") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.downloadDat(url) }, enabled = !model.busy && url.isNotBlank()) {
                Text("Download DAT")
            }
            OutlinedButton(onClick = model::importExample, enabled = !model.busy) { Text("Try example") }
        }
        if (model.ui.files.isEmpty()) {
            Text("No DAT files yet.")
        } else {
            model.ui.files.forEach { file ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                        Text(file.systemName.ifBlank { "Unknown system" })
                        TextButton(onClick = { model.removeFile(file) }) { Text("Remove") }
                    }
                }
            }
        }
        Button(onClick = onProcess, enabled = !model.busy && model.ui.files.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Process")
        }
        OutlinedButton(onClick = onShare, enabled = model.savedFiles.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Share the DAT")
        }
        Text("Log", style = MaterialTheme.typography.titleMedium)
        Text(
            model.log.ifBlank { "Processing details show up here." },
            fontFamily = FontFamily.Monospace,
        )
        if (model.log.isNotBlank()) {
            TextButton(onClick = model::clearLog) { Text("Clear log") }
        }
    }
}

@Composable
private fun SystemPage(model: RetoolModel, onPickSupport: (String, String) -> Unit) {
    val names = model.ui.files.map { it.systemName }.distinct().filter { it.isNotBlank() }
    if (names.isEmpty()) {
        Text("Add a DAT on the Process tab. Its system settings show up here.")
        return
    }
    val selected = model.selectedSystem?.takeIf { it in names } ?: names.first()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { name ->
                FilterChip(selected = name == selected, onClick = { model.selectSystem(name) }, label = { Text(name) })
            }
        }
        val profile = model.ui.systems[selected]
        if (profile == null) {
            Text("This system is using global settings.")
            Button(onClick = { model.customizeSystem(selected) }) { Text("Customize this system") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model.clearSystem(selected) }) { Text("Use global settings") }
            }
            SettingsPage(
                title = selected,
                profile = profile,
                catalog = model.catalog,
                system = true,
                onChange = { transform -> model.updateSystem(selected, transform) },
                onPickSupport = { field -> onPickSupport(selected, field) },
            )
        }
    }
}

@Composable
private fun ColumnScope.SettingsPage(
    title: String,
    profile: Profile,
    catalog: Catalog,
    system: Boolean,
    onChange: ((Profile) -> Profile) -> Unit,
    onPickSupport: (String) -> Unit,
) {
    val sections = buildList {
        add("Regions")
        add("Languages")
        add("Local names")
        add("Video")
        add("Exclusions")
        add("Options")
        add("Overrides")
        add("Filters")
        if (system) add("Paths")
    }
    var section by remember(title) { mutableStateOf(sections.first()) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sections.forEach { name ->
                FilterChip(selected = section == name, onClick = { section = name }, label = { Text(name) })
            }
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (section) {
                "Regions" -> {
                    if (system) {
                        OverrideSwitch(profile.overrideRegions, "Override global region order") {
                            onChange { it.copy(overrideRegions = !it.overrideRegions) }
                        }
                    }
                    Text("Higher in the list wins. A region left out is excluded.")
                    OrderEditor(
                        selected = profile.regions,
                        available = catalog.regions.filter { it !in profile.regions },
                        onChange = { next -> onChange { it.copy(regions = next) } },
                    )
                }
                "Languages" -> {
                    if (system) {
                        OverrideSwitch(profile.overrideLanguages, "Override global languages") {
                            onChange { it.copy(overrideLanguages = !it.overrideLanguages) }
                        }
                    }
                    Text("Titles must support a language in this list, in this order. Leave it empty to keep every language.")
                    OrderEditor(
                        selected = profile.languages,
                        available = catalog.languages.filter { it !in profile.languages },
                        onChange = { next -> onChange { it.copy(languages = next) } },
                    )
                }
                "Local names" -> {
                    if (system) {
                        OverrideSwitch(profile.overrideLocalizations, "Override global local names") {
                            onChange { it.copy(overrideLocalizations = !it.overrideLocalizations) }
                        }
                    }
                    Text("When this list is not empty, output titles use local names for these languages. Metadata files are required.")
                    OrderEditor(
                        selected = profile.localizations,
                        available = catalog.languages.filter { it !in profile.localizations },
                        onChange = { next -> onChange { it.copy(localizations = next) } },
                    )
                }
                "Video" -> {
                    if (system) {
                        OverrideSwitch(profile.overrideVideo, "Override global video order") {
                            onChange { it.copy(overrideVideo = !it.overrideVideo) }
                        }
                    }
                    Text("Priority for NTSC, PAL, and the other video tags. Every standard stays in the list.")
                    OrderEditor(
                        selected = profile.video.ifEmpty { catalog.video },
                        available = emptyList(),
                        reorderOnly = true,
                        onChange = { next -> onChange { it.copy(video = next) } },
                    )
                }
                "Exclusions" -> {
                    if (system) {
                        OverrideSwitch(profile.overrideExclusions, "Override global exclusions") {
                            onChange { it.copy(overrideExclusions = !it.overrideExclusions) }
                        }
                    }
                    val unlicensed = listOf("f", "p", "u").all { it in profile.excludes }
                    FilterChip(
                        selected = unlicensed,
                        onClick = {
                            onChange { profile ->
                                val next = profile.excludes.toMutableSetOfLetters()
                                if (unlicensed) next.removeAll(listOf("f", "p", "u")) else next.addAll(listOf("f", "p", "u"))
                                profile.copy(excludes = next.joinExcludes())
                            }
                        },
                        label = { Text("All unlicensed types") },
                    )
                    EXCLUDES.forEach { (letter, label) ->
                        FilterChip(
                            selected = letter in profile.excludes,
                            onClick = {
                                onChange { current ->
                                    val next = current.excludes.toMutableSetOfLetters()
                                    if (letter in next) next.remove(letter) else next.add(letter)
                                    current.copy(excludes = next.joinExcludes())
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                "Options" -> OptionsBlock(profile, onChange, system)
                "Overrides" -> {
                    Text("One title per line. Plain text is a partial match, a leading / is a regex, a leading | is the whole title, and <> also covers clones.")
                    Multiline("Exclude titles", profile.excludeText) { value -> onChange { it.copy(excludeText = value) } }
                    Multiline("Include titles", profile.includeText) { value -> onChange { it.copy(includeText = value) } }
                }
                "Filters" -> {
                    if (system) {
                        OverrideSwitch(profile.overridePostFilters, "Override global post filters") {
                            onChange { it.copy(overridePostFilters = !it.overridePostFilters) }
                        }
                    }
                    Text("After 1G1R, keep only titles that match these lines. Plain text, /regex, or |full title.")
                    Multiline("Post filters", profile.filtersText) { value -> onChange { it.copy(filtersText = value) } }
                }
                "Paths" -> {
                    OverrideSwitch(profile.overridePaths, "Override global support files") {
                        onChange { it.copy(overridePaths = !it.overridePaths) }
                    }
                    Text("Output still lands in Downloads/Retool. These files replace the default clone list, metadata, MIA list, or RetroAchievements list for this system.")
                    PathRow("Clone list", profile.cloneListPath) { onPickSupport("clone") }
                    PathRow("Metadata", profile.metadataPath) { onPickSupport("metadata") }
                    PathRow("MIA", profile.miaPath) { onPickSupport("mia") }
                    PathRow("RetroAchievements", profile.raPath) { onPickSupport("ra") }
                    TextButton(onClick = {
                        onChange {
                            it.copy(cloneListPath = "", metadataPath = "", miaPath = "", raPath = "")
                        }
                    }) { Text("Clear custom files") }
                }
            }
        }
    }
}

@Composable
private fun OptionsBlock(profile: Profile, onChange: ((Profile) -> Profile) -> Unit, system: Boolean) {
    if (system) {
        OverrideSwitch(profile.overrideOptions, "Override global options") {
            onChange { it.copy(overrideOptions = !it.overrideOptions) }
        }
    }
    Toggle("Prefer regions over languages", profile.preferRegions) {
        onChange { it.copy(preferRegions = !it.preferRegions) }
    }
    Toggle("Prefer titles from modern platforms", profile.modern) {
        onChange { it.copy(modern = !it.modern) }
    }
    Toggle("Prefer oldest versions", profile.oldest) { onChange { it.copy(oldest = !it.oldest) } }
    Toggle("Prefer licensed over unlicensed", profile.demoteUnl) {
        onChange { it.copy(demoteUnl = !it.demoteUnl) }
    }
    Toggle("Prefer RetroAchievements titles", profile.preferRetro) {
        onChange { it.copy(preferRetro = !it.preferRetro) }
    }
    Toggle("Disable 1G1R", profile.disable1g1r) {
        onChange { it.copy(disable1g1r = !it.disable1g1r, legacy = if (!it.disable1g1r) false else it.legacy) }
    }
    Toggle("Disable global and system title overrides", profile.noOverrides) {
        onChange { it.copy(noOverrides = !it.noOverrides) }
    }
    Text("Compilations")
    Text(COMPILATION_HELP[profile.compilations].orEmpty())
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COMPILATIONS.forEach { (value, label) ->
            FilterChip(
                selected = profile.compilations == value,
                onClick = { onChange { it.copy(compilations = value) } },
                label = { Text(label) },
            )
        }
    }
    Text("Output", style = MaterialTheme.typography.titleMedium)
    Toggle("Allow already processed DATs", profile.reprocess) { onChange { it.copy(reprocess = !it.reprocess) } }
    Toggle("Keep the original DAT header", profile.originalHeader) {
        onChange { it.copy(originalHeader = !it.originalHeader) }
    }
    Toggle("Use <machine> instead of <game>", profile.useMachine) {
        onChange { it.copy(useMachine = !it.useMachine) }
    }
    Toggle("Split output by region", profile.splitRegions && !profile.legacy) {
        onChange { it.copy(splitRegions = !it.splitRegions, legacy = false) }
    }
    Toggle("Also write a DAT of removed titles", profile.removesDat) {
        onChange { it.copy(removesDat = !it.removesDat) }
    }
    Toggle("Also write a kept/removed report", profile.report) { onChange { it.copy(report = !it.report) } }
    Toggle("Also write a title-name list", profile.listNames) { onChange { it.copy(listNames = !it.listNames) } }
    if (profile.listNames) {
        Multiline("Prefix", profile.prefix) { value -> onChange { it.copy(prefix = value) } }
        Multiline("Suffix", profile.suffix) { value -> onChange { it.copy(suffix = value) } }
    }
    Text("Labels from online data. Download clone lists on the Data tab or these do nothing useful.")
    Toggle("Add MIA attributes", profile.labelMia) { onChange { it.copy(labelMia = !it.labelMia) } }
    Toggle("Add RetroAchievements attributes", profile.labelRetro) {
        onChange { it.copy(labelRetro = !it.labelRetro) }
    }
    Toggle("Report clone-list warnings", profile.warnings) { onChange { it.copy(warnings = !it.warnings) } }
    Toggle("Legacy parent/clone DAT", profile.legacy && !profile.disable1g1r && !profile.splitRegions) {
        onChange { it.copy(legacy = !it.legacy, splitRegions = false, disable1g1r = false) }
    }
    Toggle("Replace the input file", profile.replace) { onChange { it.copy(replace = !it.replace) } }
    Text("Replace deletes Retool's copy of the DAT and writes the result beside it. The file you originally picked is left alone, because Android does not give the filter delete access to it. The new DAT is still copied to Downloads.")
    Multiline("Trace regex", profile.trace) { value -> onChange { it.copy(trace = value) } }
    Text("A trace follows one title and does not write a DAT. Leave this empty when you want a file.")
    Text("Single-core mode is always on. Android cannot run Retool's process pool. Pausing on a warning is off, because that waits for an Enter key the phone does not have.")
}

@Composable
private fun DataPage(model: RetoolModel) {
    var title by remember { mutableStateOf("") }
    var names by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Data", style = MaterialTheme.typography.headlineSmall)
        Text("Retool ${model.catalog.version}. Clone lists and metadata are downloaded from GitHub. The filter runs without them, and it is less accurate until they are present.")
        OutlinedTextField(
            value = model.ui.metadataUrl,
            onValueChange = model::setMetadataUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Clone list and metadata URL") },
        )
        Button(onClick = model::updateCloneLists, enabled = !model.busy) { Text("Download clone lists and metadata") }
        Text("Clone list name tool", style = MaterialTheme.typography.titleMedium)
        Text("Enter a full DAT title, including regions, to see the names Retool uses in clone lists.")
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Full title") },
        )
        Button(onClick = { model.describeTitle(title) { names = it } }, enabled = title.isNotBlank() && !model.busy) {
            Text("Show names")
        }
        if (names.isNotBlank()) Text(names)
        Text("What is different on Android", style = MaterialTheme.typography.titleMedium)
        Text(
            "Drag-and-drop is a file picker, a folder picker, or a URL. " +
                "The processed DAT is saved to Downloads/Retool so it shows up in the Downloads app, and it can be shared. " +
                "Window size, desktop fonts, and the taskbar icon have no phone equivalent. " +
                "The filter always uses one core. " +
                "Pause-on-warning is unavailable because it blocks on a keyboard. " +
                "There is no stop button: the filter has no safe cancel point, and killing it would close the app. " +
                "Per-system output folders are not a free-form path. Android content URIs are not files the filter can open, so custom clone lists and metadata are copied into the app, and every result is also placed in Downloads.",
        )
    }
}

@Composable
private fun OrderEditor(
    selected: List<String>,
    available: List<String>,
    reorderOnly: Boolean = false,
    onChange: (List<String>) -> Unit,
) {
    Text("Priority", style = MaterialTheme.typography.titleMedium)
    if (selected.isEmpty()) Text("Nothing selected.")
    selected.forEachIndexed { index, name ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, Modifier.weight(1f).padding(top = 8.dp))
            TextButton(onClick = {
                if (index > 0) onChange(selected.move(index, index - 1))
            }, enabled = index > 0) { Text("Up") }
            TextButton(onClick = {
                if (index < selected.lastIndex) onChange(selected.move(index, index + 1))
            }, enabled = index < selected.lastIndex) { Text("Down") }
            if (!reorderOnly) {
                TextButton(onClick = { onChange(selected.filterIndexed { item, _ -> item != index }) }) { Text("Out") }
            }
        }
    }
    if (!reorderOnly && available.isNotEmpty()) {
        Text("Available", style = MaterialTheme.typography.titleMedium)
        available.sorted().forEach { name ->
            TextButton(onClick = { onChange(selected + name) }) { Text("Add $name") }
        }
    }
}

@Composable
private fun OverrideSwitch(checked: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = checked, onClick = onClick, label = { Text(label) })
}

@Composable
private fun Toggle(label: String, checked: Boolean, onClick: () -> Unit) {
    FilterChip(selected = checked, onClick = onClick, label = { Text(label) })
}

@Composable
private fun Multiline(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = 2,
    )
}

@Composable
private fun PathRow(label: String, path: String, onPick: () -> Unit) {
    Text(label)
    Text(path.ifBlank { "Default file" })
    OutlinedButton(onClick = onPick) { Text("Choose $label") }
}

private fun String.toMutableSetOfLetters(): MutableSet<String> =
    filter { !it.isWhitespace() }.map { it.toString() }.toMutableSet()

private fun Set<String>.joinExcludes(): String = sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it }).joinToString("")

private fun List<String>.move(from: Int, to: Int): List<String> {
    val next = toMutableList()
    val item = next.removeAt(from)
    next.add(to, item)
    return next
}
