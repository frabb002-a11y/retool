package com.unexpectedpanda.retool

import org.json.JSONArray
import org.json.JSONObject

data class Profile(
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val localizations: List<String> = emptyList(),
    val video: List<String> = emptyList(),
    val excludes: String = "",
    val disable1g1r: Boolean = false,
    val preferRegions: Boolean = false,
    val modern: Boolean = false,
    val oldest: Boolean = false,
    val demoteUnl: Boolean = false,
    val noOverrides: Boolean = false,
    val compilations: String = "",
    val reprocess: Boolean = false,
    val originalHeader: Boolean = false,
    val useMachine: Boolean = false,
    val splitRegions: Boolean = false,
    val removesDat: Boolean = false,
    val report: Boolean = false,
    val listNames: Boolean = false,
    val prefix: String = "",
    val suffix: String = "",
    val labelMia: Boolean = false,
    val labelRetro: Boolean = false,
    val preferRetro: Boolean = false,
    val warnings: Boolean = false,
    val legacy: Boolean = false,
    val trace: String = "",
    val replace: Boolean = false,
    val excludeText: String = "",
    val includeText: String = "",
    val filtersText: String = "",
    val overrideRegions: Boolean = false,
    val overrideLanguages: Boolean = false,
    val overrideLocalizations: Boolean = false,
    val overrideVideo: Boolean = false,
    val overrideExclusions: Boolean = false,
    val overrideOptions: Boolean = false,
    val overridePostFilters: Boolean = false,
    val overridePaths: Boolean = false,
    val outputPath: String = "",
    val cloneListPath: String = "",
    val metadataPath: String = "",
    val miaPath: String = "",
    val raPath: String = "",
) {
    fun detached(): Profile = copy(
        regions = regions.toList(),
        languages = languages.toList(),
        localizations = localizations.toList(),
        video = video.toList(),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("regions", JSONArray(regions))
        put("languages", JSONArray(languages))
        put("localizations", JSONArray(localizations))
        put("video", JSONArray(video))
        put("excludes", excludes)
        put("disable1g1r", disable1g1r)
        put("preferRegions", preferRegions)
        put("modern", modern)
        put("oldest", oldest)
        put("demoteUnl", demoteUnl)
        put("noOverrides", noOverrides)
        put("compilations", compilations)
        put("reprocess", reprocess)
        put("originalHeader", originalHeader)
        put("useMachine", useMachine)
        put("splitRegions", splitRegions)
        put("removesDat", removesDat)
        put("report", report)
        put("listNames", listNames)
        put("prefix", prefix)
        put("suffix", suffix)
        put("labelMia", labelMia)
        put("labelRetro", labelRetro)
        put("preferRetro", preferRetro)
        put("warnings", warnings)
        put("legacy", legacy)
        put("trace", trace)
        put("replace", replace)
        put("excludeText", excludeText)
        put("includeText", includeText)
        put("filtersText", filtersText)
        put("overrideRegions", overrideRegions)
        put("overrideLanguages", overrideLanguages)
        put("overrideLocalizations", overrideLocalizations)
        put("overrideVideo", overrideVideo)
        put("overrideExclusions", overrideExclusions)
        put("overrideOptions", overrideOptions)
        put("overridePostFilters", overridePostFilters)
        put("overridePaths", overridePaths)
        put("outputPath", outputPath)
        put("cloneListPath", cloneListPath)
        put("metadataPath", metadataPath)
        put("miaPath", miaPath)
        put("raPath", raPath)
    }

    companion object {
        fun fromJson(obj: JSONObject): Profile = Profile(
            regions = obj.stringList("regions"),
            languages = obj.stringList("languages"),
            localizations = obj.stringList("localizations"),
            video = obj.stringList("video"),
            excludes = obj.optString("excludes"),
            disable1g1r = obj.optBoolean("disable1g1r"),
            preferRegions = obj.optBoolean("preferRegions"),
            modern = obj.optBoolean("modern"),
            oldest = obj.optBoolean("oldest"),
            demoteUnl = obj.optBoolean("demoteUnl"),
            noOverrides = obj.optBoolean("noOverrides"),
            compilations = obj.optString("compilations"),
            reprocess = obj.optBoolean("reprocess"),
            originalHeader = obj.optBoolean("originalHeader"),
            useMachine = obj.optBoolean("useMachine"),
            splitRegions = obj.optBoolean("splitRegions"),
            removesDat = obj.optBoolean("removesDat"),
            report = obj.optBoolean("report"),
            listNames = obj.optBoolean("listNames"),
            prefix = obj.optString("prefix"),
            suffix = obj.optString("suffix"),
            labelMia = obj.optBoolean("labelMia"),
            labelRetro = obj.optBoolean("labelRetro"),
            preferRetro = obj.optBoolean("preferRetro"),
            warnings = obj.optBoolean("warnings"),
            legacy = obj.optBoolean("legacy"),
            trace = obj.optString("trace"),
            replace = obj.optBoolean("replace"),
            excludeText = obj.optString("excludeText"),
            includeText = obj.optString("includeText"),
            filtersText = obj.optString("filtersText"),
            overrideRegions = obj.optBoolean("overrideRegions"),
            overrideLanguages = obj.optBoolean("overrideLanguages"),
            overrideLocalizations = obj.optBoolean("overrideLocalizations"),
            overrideVideo = obj.optBoolean("overrideVideo"),
            overrideExclusions = obj.optBoolean("overrideExclusions"),
            overrideOptions = obj.optBoolean("overrideOptions"),
            overridePostFilters = obj.optBoolean("overridePostFilters"),
            overridePaths = obj.optBoolean("overridePaths"),
            outputPath = obj.optString("outputPath"),
            cloneListPath = obj.optString("cloneListPath"),
            metadataPath = obj.optString("metadataPath"),
            miaPath = obj.optString("miaPath"),
            raPath = obj.optString("raPath"),
        )
    }
}

data class DatFile(val path: String, val name: String, val systemName: String)

data class Catalog(
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val video: List<String> = emptyList(),
    val version: String = "",
)

data class UiState(
    val initialized: Boolean = false,
    val files: List<DatFile> = emptyList(),
    val global: Profile = Profile(),
    val systems: Map<String, Profile> = emptyMap(),
    val metadataUrl: String = "https://raw.githubusercontent.com/unexpectedpanda/retool-clonelists-metadata/main",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("initialized", initialized)
        put("metadataUrl", metadataUrl)
        put("global", global.toJson())
        val systemObj = JSONObject()
        systems.forEach { (name, profile) -> systemObj.put(name, profile.toJson()) }
        put("systems", systemObj)
        val fileArr = JSONArray()
        files.forEach { file ->
            fileArr.put(
                JSONObject()
                    .put("path", file.path)
                    .put("name", file.name)
                    .put("systemName", file.systemName),
            )
        }
        put("files", fileArr)
    }

    fun jobJson(outputDir: String): String = JSONObject().apply {
        put("outputDir", outputDir)
        put("metadataUrl", metadataUrl)
        put("global", global.toJson())
        val systemObj = JSONObject()
        systems.forEach { (name, profile) -> systemObj.put(name, profile.toJson()) }
        put("systems", systemObj)
        val fileArr = JSONArray()
        files.forEach { file ->
            fileArr.put(JSONObject().put("path", file.path).put("systemName", file.systemName))
        }
        put("files", fileArr)
    }.toString()

    companion object {
        fun fromJson(text: String): UiState {
            val obj = JSONObject(text)
            val systems = mutableMapOf<String, Profile>()
            val systemObj = obj.optJSONObject("systems") ?: JSONObject()
            for (key in systemObj.keys()) {
                systems[key] = Profile.fromJson(systemObj.getJSONObject(key))
            }
            val files = mutableListOf<DatFile>()
            val fileArr = obj.optJSONArray("files") ?: JSONArray()
            for (i in 0 until fileArr.length()) {
                val file = fileArr.getJSONObject(i)
                files += DatFile(file.getString("path"), file.optString("name"), file.optString("systemName"))
            }
            return UiState(
                initialized = obj.optBoolean("initialized"),
                files = files,
                global = Profile.fromJson(obj.optJSONObject("global") ?: JSONObject()),
                systems = systems,
                metadataUrl = obj.optString(
                    "metadataUrl",
                    "https://raw.githubusercontent.com/unexpectedpanda/retool-clonelists-metadata/main",
                ),
            )
        }
    }
}

private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            add(array.getString(i))
        }
    }
}

val EXCLUDES = listOf(
    "D" to "Add-ons",
    "a" to "Applications",
    "A" to "Audio",
    "b" to "Bad dumps",
    "B" to "BIOS",
    "o" to "Bonus discs",
    "c" to "Coverdiscs",
    "d" to "Demos",
    "e" to "Educational",
    "f" to "Aftermarket",
    "g" to "Games",
    "k" to "MIA",
    "m" to "Manuals",
    "M" to "Multimedia",
    "p" to "Pirate",
    "P" to "Preproduction",
    "r" to "Promotional",
    "u" to "Unlicensed",
    "v" to "Video",
)

val COMPILATIONS = listOf(
    "" to "Default",
    "i" to "Prefer individual titles",
    "k" to "Keep individual titles and compilations",
    "o" to "Optimize for least duplication",
)

val COMPILATION_HELP = mapOf(
    "" to "Chooses individual titles most of the time. A compilation is kept when it ranks higher or contains a unique title.",
    "i" to "Chooses individual titles and discards compilations unless they contain a unique game.",
    "k" to "Compares individual titles only with each other, and compilations only with other compilations.",
    "o" to "Prefers compilations to reduce the file count. This mode is still marked beta upstream.",
)
