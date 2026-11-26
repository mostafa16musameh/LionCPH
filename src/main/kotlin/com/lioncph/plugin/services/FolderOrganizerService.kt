package com.lioncph.plugin.services

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.lioncph.plugin.models.CphSettings
import com.lioncph.plugin.models.CphStorage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class OrganizedDestination(
    val directory: Path,
    val fileName: String,
    val cmakeTargetHint: String
)

/**
 * Smart workspace organizer for LionCPH.
 * Supports 3 modes:
 * - "flat": saves directly in project root
 * - "single": one folder per Sheet / Contest / Problemset
 * - "hierarchy": Groups/<RealGroupName>/<SheetName>, Contests/<ContestName>, Problemset/, EDU/<Topic>/<Lesson>, Gym/<GymName>, Practice/
 */
object FolderOrganizerService {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // Built-in popular Codeforces CP training groups (groupId -> clean folder name)
    private val KNOWN_GROUPS: Map<String, String> = mapOf(
        "MWSDmqGsZm" to "ICPC_Assiut_University_Community",
        "yg7WhsFsAp" to "ICPC_Assiut_Newcomers",
        "gWv54e8k2B" to "ICPC_Assiut_Juniors",
        "c3FDl9EUi9" to "ICPC_Assiut_Phase_1",
        "u3Q45B1a3A" to "ICPC_Assiut_Phase_2",
        "3nQaj5LsGe" to "ICPC_Assiut_Standard"
    )

    private val sessionGroupNameCache = ConcurrentHashMap<String, String>()
    private val MANAGED_ROOT_FOLDERS = setOf("Groups", "Contests", "Problemset", "Practice", "Gym", "EDU")

    fun groupNamesFile(projectRoot: Path): Path = projectRoot.resolve(".cph").resolve("group_names.json")

    fun loadGroupNames(projectRoot: Path): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        map.putAll(KNOWN_GROUPS)
        val file = groupNamesFile(projectRoot)
        if (Files.isRegularFile(file)) {
            runCatching {
                val json = Files.readString(file, StandardCharsets.UTF_8)
                val type = object : TypeToken<Map<String, String>>() {}.type
                val parsed: Map<String, String>? = gson.fromJson(json, type)
                if (parsed != null) {
                    val invalidNames = setOf("contests", "dashboard", "codeforces", "login", "members", "standings")
                    for ((k, v) in parsed) {
                        val cleaned = sanitizeSegment(v, 40)
                        if (k.isNotBlank() && cleaned.isNotBlank() && cleaned.lowercase() !in invalidNames) {
                            map[k] = cleaned
                        }
                    }
                }
            }
        }
        return map
    }

    fun saveGroupName(projectRoot: Path, groupId: String, friendlyName: String) {
        val clean = sanitizeSegment(friendlyName, 40)
        if (groupId.isBlank() || clean.isBlank()) return
        val map = loadGroupNames(projectRoot)
        map[groupId] = clean
        sessionGroupNameCache[groupId] = clean
        runCatching {
            val dir = projectRoot.resolve(".cph")
            Files.createDirectories(dir)
            Files.writeString(groupNamesFile(projectRoot), gson.toJson(map), StandardCharsets.UTF_8)
        }
    }

    /**
     * Resolves a human-readable group folder name for a Codeforces groupId:
     * 1. Saved user/cached name in .cph/group_names.json or built-in KNOWN_GROUPS
     * 2. Fast web fetch of https://codeforces.com/group/<groupId> page title (cached permanently on success)
     * 3. Fallback: Group_<groupId> (consistent for all sheets in the same group)
     */
    fun resolveGroupDisplayName(projectRoot: Path, groupId: String): String {
        if (groupId.isBlank()) return "Group"
        sessionGroupNameCache[groupId]?.let { return it }

        val saved = loadGroupNames(projectRoot)
        saved[groupId]?.let {
            sessionGroupNameCache[groupId] = it
            return it
        }

        val fetched = fetchGroupNameFromWeb(groupId)
        if (!fetched.isNullOrBlank()) {
            val clean = sanitizeSegment(fetched, 40)
            if (clean.isNotBlank()) {
                saveGroupName(projectRoot, groupId, clean)
                return clean
            }
        }

        val fallback = "Group_${sanitizeSegment(groupId, 20)}"
        sessionGroupNameCache[groupId] = fallback
        return fallback
    }

    private fun fetchGroupNameFromWeb(groupId: String): String? {
        val invalidNames = setOf("contests", "dashboard", "codeforces", "login", "members", "standings", "enter")
        fun cleanHtml(s: String): String = s
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "")
            .replace("&#39;", "")
            .replace("&nbsp;", " ")
            .trim()

        return runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://codeforces.com/group/$groupId/contests"))
                .timeout(Duration.ofSeconds(3))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) return@runCatching null
            val body = resp.body()

            // 1. Primary: Codeforces group sidebar header link <a href="/group/<groupId>">Real Group Name</a>
            val escapedId = Regex.escape(groupId)
            val linkMatches = Regex("""<a[^>]*href="/group/$escapedId/?"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(body)
            for (m in linkMatches) {
                val candidate = cleanHtml(m.groupValues[1])
                if (candidate.isNotBlank() && candidate.lowercase() !in invalidNames && !candidate.contains("Just a moment", true)) {
                    return@runCatching candidate
                }
            }

            // 2. Fallback: <title> only if it actually contains a non-generic group name
            val titleRaw = Regex("""<title>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.get(1)?.trim() ?: return@runCatching null
            val cleaned = cleanHtml(titleRaw)
                .replace(Regex("""\s*-\s*Codeforces\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^Contests\s*-\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^Dashboard\s*-\s*""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (cleaned.isBlank() || cleaned.lowercase() in invalidNames || cleaned.contains("Just a moment", true)) {
                null
            } else {
                cleaned
            }
        }.getOrNull()
    }

    fun resolveDestination(
        projectRoot: Path,
        rawName: String,
        group: String,
        url: String,
        settings: CphSettings
    ): OrganizedDestination {
        val mode = effectiveMode(settings)
        val fileName = buildFileName(rawName, url, settings)
        val baseSlug = fileName.substringBeforeLast('.')

        if (mode == "flat") {
            return OrganizedDestination(projectRoot, fileName, baseSlug)
        }

        val relativeFolder = resolveRelativeFolder(projectRoot, group, url, mode).trim('/')
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val candidateDir = if (relativeFolder.isBlank()) normalizedRoot else normalizedRoot.resolve(relativeFolder).normalize()
        val targetDir = if (candidateDir.startsWith(normalizedRoot)) candidateDir else normalizedRoot
        Files.createDirectories(targetDir)

        val folderTag = targetDir.fileName?.toString().orEmpty()
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .take(20)
            .trim('_')
        val targetHint = if (folderTag.isNotEmpty() && !baseSlug.startsWith(folderTag, true)) {
            "${folderTag}_$baseSlug"
        } else {
            baseSlug
        }

        return OrganizedDestination(targetDir, fileName, targetHint)
    }

    fun effectiveMode(settings: CphSettings): String {
        val m = settings.organizationMode.trim().lowercase()
        if (m in setOf("flat", "single", "hierarchy")) return m
        return if (settings.organizeByContest) "hierarchy" else "flat"
    }

    fun buildFileName(rawName: String, url: String, settings: CphSettings): String {
        val problemsetMatch = Regex("""codeforces\.com/problemset/problem/(\d+)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE).find(url)
        val indexOnly = extractProblemIndex(rawName, url)

        if (settings.fileNameStyle.equals("short", ignoreCase = true) && indexOnly.isNotBlank()) {
            val shortBase = if (problemsetMatch != null) {
                "${problemsetMatch.groupValues[1]}${problemsetMatch.groupValues[2]}"
            } else {
                indexOnly
            }
            return "$shortBase.cpp"
        }

        val slug = sanitizeSegment(rawName, maxLen = 48).ifBlank { "problem_${rawName.hashCode().toUInt()}" }
        val finalBase = if (problemsetMatch != null && !slug.startsWith(problemsetMatch.groupValues[1])) {
            "${problemsetMatch.groupValues[1]}_$slug"
        } else {
            slug
        }
        return "$finalBase.cpp"
    }

    private fun extractProblemIndex(rawName: String, url: String): String {
        Regex("""/problem/([A-Za-z0-9]+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE).find(url)
            ?.groupValues?.get(1)?.let { return it }
        Regex("""^([A-Za-z]\d?)\s*[\.\-:]""").find(rawName.trim())
            ?.groupValues?.get(1)?.let { return it }
        return ""
    }

    private fun stripCodeforcesPrefix(group: String): String {
        return group.trim()
            .replace(Regex("""^Codeforces\s*-\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun resolveRelativeFolder(projectRoot: Path, group: String, url: String, mode: String): String {
        val lowerUrl = url.lowercase()
        val parts = group.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }

        // 1. Codeforces Problemset -> Dedicated Problemset folder
        if (lowerUrl.contains("codeforces.com/problemset/")) {
            return "Problemset"
        }

        // 2. Codeforces EDU
        if (lowerUrl.contains("codeforces.com/edu/")) {
            val cleaned = parts.filterNot { it.equals("Codeforces", true) || it.startsWith("ITMO Academy", true) }
            val leaf = sanitizeSegment(cleaned.lastOrNull() ?: "EDU_Practice", 36).ifBlank { "EDU_Practice" }
            return if (mode == "hierarchy") {
                val topic = sanitizeSegment(cleaned.firstOrNull() ?: "Course", 32).ifBlank { "Course" }
                if (topic != leaf) "EDU/$topic/$leaf" else "EDU/$leaf"
            } else {
                "EDU_$leaf"
            }
        }

        // 3. Codeforces Group / Sheet (ALWAYS anchored by real Group Name resolved from groupId!)
        if (lowerUrl.contains("codeforces.com/group/")) {
            val groupId = extractGroupId(url)
            val realGroupName = resolveGroupDisplayName(projectRoot, groupId)
            val sheetRaw = stripCodeforcesPrefix(group)
            val sheetName = if (sheetRaw.isNotBlank() && !sheetRaw.equals("local", true) && !sheetRaw.equals("Competitive Companion", true)) {
                sanitizeSegment(sheetRaw, 46)
            } else {
                extractContestLabel(url)
            }
            return if (mode == "hierarchy") {
                if (sheetName.isNotBlank() && !sheetName.equals(realGroupName, true)) {
                    "Groups/$realGroupName/$sheetName"
                } else {
                    "Groups/$realGroupName"
                }
            } else {
                if (sheetName.isNotBlank()) "${realGroupName}_$sheetName".take(54) else realGroupName
            }
        }

        // 4. Codeforces Gym
        if (lowerUrl.contains("codeforces.com/gym/")) {
            val gymRaw = stripCodeforcesPrefix(group)
            val gymName = sanitizeSegment(gymRaw, 44).ifBlank { extractContestLabel(url) }
            return if (mode == "hierarchy") "Gym/$gymName" else gymName
        }

        // 5. Codeforces Contest
        if (lowerUrl.contains("codeforces.com/contest/")) {
            val contestRaw = stripCodeforcesPrefix(group)
            val contestName = sanitizeSegment(contestRaw, 46).ifBlank { extractContestLabel(url) }
            return if (mode == "hierarchy") "Contests/$contestName" else contestName
        }

        // 6. Other Judges (AtCoder, CSES, VJudge, LeetCode, etc.)
        if (parts.size >= 2) {
            val platform = sanitizeSegment(parts.first(), 24).ifBlank { "Judge" }
            val contest = sanitizeSegment(parts.drop(1).joinToString("_"), 40).ifBlank { "Contest" }
            return if (mode == "hierarchy") "$platform/$contest" else contest
        }

        if (group.isNotBlank() && !group.equals("local", true) && !group.equals("Competitive Companion", true)) {
            val name = sanitizeSegment(group, 40).ifBlank { "Contest" }
            return if (mode == "hierarchy") "Contests/$name" else name
        }

        return "Practice"
    }

    fun extractGroupId(url: String): String {
        return Regex("""/group/([^/]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1) ?: ""
    }

    private fun extractContestLabel(url: String): String {
        val cid = Regex("""/(?:contest|gym)/(\d+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        return if (cid != null) "Contest_$cid" else "Contest"
    }

    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun sanitizeSegment(raw: String, maxLen: Int = 42): String {
        val cleaned = raw
            .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .take(maxLen)
            .trim('_', '-', '.')
        return if (cleaned.uppercase() in WINDOWS_RESERVED) "p_$cleaned" else cleaned
    }

    /**
     * Safely organizes LionCPH-tracked .cpp files from BOTH project root AND previously organized
     * LionCPH folders (Groups/, Contests/, Problemset/, Practice/, Gym/, EDU/) into their clean
     * canonical destinations, then prunes any empty leftover directories.
     * Never touches non-CPH files (such as main.cpp or user scripts).
     */
    @Synchronized
    fun organizeExistingRootFiles(projectRoot: Path, settings: CphSettings): Int =
        organizeExistingRootFilesWithMap(projectRoot, settings).size

    @Synchronized
    fun organizeExistingRootFilesWithMap(projectRoot: Path, settings: CphSettings): Map<Path, Path> {
        if (effectiveMode(settings) == "flat") return emptyMap()
        val movedMap = LinkedHashMap<Path, Path>()

        val candidates = mutableListOf<Path>()
        // 1. Direct files in project root
        runCatching {
            Files.list(projectRoot).use { stream ->
                stream.filter { p ->
                    Files.isRegularFile(p) &&
                        (p.fileName.toString().endsWith(".cpp", true) || p.fileName.toString().endsWith(".cc", true))
                }.forEach { candidates.add(it) }
            }
        }
        // 2. Files inside LionCPH-managed folders (so old messy Groups/MWSDmqGsZm or Groups/Sheet_1_Data_type get cleaned up too!)
        for (folder in MANAGED_ROOT_FOLDERS) {
            val dir = projectRoot.resolve(folder)
            if (Files.isDirectory(dir)) {
                runCatching {
                    Files.walk(dir, 5).use { stream ->
                        stream.filter { p ->
                            Files.isRegularFile(p) &&
                                (p.fileName.toString().endsWith(".cpp", true) || p.fileName.toString().endsWith(".cc", true))
                        }.forEach { candidates.add(it) }
                    }
                }
            }
        }

        for (cppFile in candidates) {
            runCatching {
                if (!Files.isRegularFile(cppFile)) return@runCatching
                val problem = CphStorage.loadProblemOrNull(projectRoot, cppFile) ?: return@runCatching
                if (problem.local && problem.url.isBlank()) return@runCatching

                val dest = resolveDestination(projectRoot, problem.name, problem.group, problem.url, settings)
                val targetFile = dest.directory.resolve(cppFile.fileName.toString())
                if (targetFile.toAbsolutePath().normalize() == cppFile.toAbsolutePath().normalize()) return@runCatching
                if (Files.exists(targetFile)) return@runCatching

                val oldProbFile = CphStorage.problemFileFor(projectRoot, cppFile)
                val newProbFile = CphStorage.problemFileFor(projectRoot, targetFile)
                val originalSrcPath = problem.srcPath
                try {
                    Files.move(cppFile, targetFile, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(cppFile, targetFile)
                }
                // Write the new .prob atomically BEFORE deleting oldProbFile; rollback move if .prob persistence fails
                val savedNewProb = runCatching {
                    CphStorage.saveProblem(projectRoot, targetFile, problem)
                    Files.isRegularFile(newProbFile)
                }.getOrDefault(false)
                if (!savedNewProb) {
                    problem.srcPath = originalSrcPath
                    runCatching { Files.deleteIfExists(newProbFile) }
                    runCatching {
                        try {
                            Files.move(targetFile, cppFile, StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: Exception) {
                            Files.move(targetFile, cppFile)
                        }
                    }
                    return@runCatching
                }
                if (oldProbFile != newProbFile) {
                    runCatching { Files.deleteIfExists(oldProbFile) }
                }

                val relPath = projectRoot.relativize(targetFile).toString().replace('\\', '/')
                CMakeSyncService.registerPluginFile(projectRoot, dest.cmakeTargetHint, relPath)
                movedMap[cppFile.toAbsolutePath().normalize()] = targetFile.toAbsolutePath().normalize()
            }
        }

        // Prune empty leftover folders inside managed roots (e.g. old Groups/MWSDmqGsZm or Groups/Sheet_1_Data_type)
        for (folder in MANAGED_ROOT_FOLDERS) {
            val dir = projectRoot.resolve(folder)
            if (Files.isDirectory(dir)) {
                pruneEmptyDirs(dir)
            }
        }
        if (movedMap.isNotEmpty()) {
            CMakeSyncService.pruneMissingTargets(projectRoot)
        }

        return movedMap
    }

    private fun pruneEmptyDirs(root: Path) {
        runCatching {
            val dirs = Files.walk(root).use { stream ->
                stream.filter { Files.isDirectory(it) }.toList()
            }.sortedByDescending { it.nameCount }
            for (d in dirs) {
                if (d == root) continue
                val isEmpty = Files.list(d).use { !it.findFirst().isPresent }
                if (isEmpty) runCatching { Files.deleteIfExists(d) }
            }
        }
    }

    /**
     * Collects all Codeforces groupIds currently referenced by .prob files in this project.
     */
    fun discoverProjectGroupIds(projectRoot: Path): List<Pair<String, String>> {
        val ids = LinkedHashSet<String>()
        val cphDir = projectRoot.resolve(".cph")
        if (Files.isDirectory(cphDir)) {
            runCatching {
                Files.list(cphDir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".prob") }.forEach { probPath ->
                        val text = runCatching { Files.readString(probPath, StandardCharsets.UTF_8) }.getOrDefault("")
                        Regex("""codeforces\.com/group/([^/"'\\]+)""", RegexOption.IGNORE_CASE)
                            .findAll(text)
                            .forEach { m -> ids.add(m.groupValues[1]) }
                    }
                }
            }
        }
        val names = loadGroupNames(projectRoot)
        return ids.map { id -> id to (names[id] ?: "Group_$id") }
    }
}
