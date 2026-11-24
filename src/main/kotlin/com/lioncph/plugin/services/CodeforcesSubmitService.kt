package com.lioncph.plugin.services

import com.lioncph.plugin.models.CphSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class CfLanguage(
    val id: Int,
    val displayName: String
)

data class CfProblemRef(
    val displayLabel: String,
    val contestId: Int?,
    val problemIndex: String,
    val problemBaseUrl: String,
    val directSubmitUrl: String,
    val mySubmissionsUrl: String,
    val standingsUrl: String
)

object CodeforcesSubmitService {
    val SUPPORTED_LANGUAGES = listOf(
        CfLanguage(91, "GNU G++23 14.2 (64 bit, msys2)"),
        CfLanguage(89, "GNU G++20 13.2 (64 bit, winlibs)"),
        CfLanguage(73, "GNU G++17 9.2.0 (64 bit, msys2)"),
        CfLanguage(54, "GNU G++17 7.3.0")
    )

    fun resolveCodeforcesLanguage(projectRoot: Path, settings: CphSettings): CfLanguage {
        val overrideId = settings.cfLanguageOverride.trim().toIntOrNull()
        if (overrideId != null) {
            SUPPORTED_LANGUAGES.find { it.id == overrideId }?.let { return it }
        }

        val cmakeFile = projectRoot.resolve("CMakeLists.txt")
        if (Files.isRegularFile(cmakeFile)) {
            runCatching {
                val cmake = Files.readString(cmakeFile, StandardCharsets.UTF_8)
                val match = Regex("""CMAKE_CXX_STANDARD\s+(\d{2})""").find(cmake)
                when (match?.groupValues?.get(1)) {
                    "23", "26" -> return SUPPORTED_LANGUAGES[0]
                    "20" -> return SUPPORTED_LANGUAGES[1]
                    "17" -> return SUPPORTED_LANGUAGES[2]
                }
            }
        }

        val args = settings.compilerArgs.lowercase()
        return when {
            args.contains("c++23") || args.contains("gnu++23") -> SUPPORTED_LANGUAGES[0]
            args.contains("c++20") || args.contains("gnu++20") -> SUPPORTED_LANGUAGES[1]
            else -> SUPPORTED_LANGUAGES[2]
        }
    }

    fun parseCodeforcesUrl(url: String): CfProblemRef? {
        val trimmed = url.trim()
        if (!trimmed.contains("codeforces.com", ignoreCase = true)) return null

        // EDU practice
        Regex("""(https?://[^/]*codeforces\.com/edu/course/\d+/lesson/[^/]+/practice/contest/(\d+))/problem/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (prefix, contestId, index) = m.destructured
                return CfProblemRef(
                    displayLabel = "EDU Contest $contestId — Problem $index",
                    contestId = contestId.toIntOrNull(),
                    problemIndex = index,
                    problemBaseUrl = "$prefix/problem/",
                    directSubmitUrl = "$prefix/submit?submittedProblemIndex=$index",
                    mySubmissionsUrl = "$prefix/my",
                    standingsUrl = "$prefix/standings"
                )
            }

        // Group contest
        Regex("""codeforces\.com/group/([^/]+)/contest/(\d+)/problem/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (groupId, contestId, index) = m.destructured
                val base = "https://codeforces.com/group/$groupId/contest/$contestId"
                return CfProblemRef(
                    displayLabel = "Group $groupId / Contest $contestId — Problem $index",
                    contestId = contestId.toIntOrNull(),
                    problemIndex = index,
                    problemBaseUrl = "$base/problem/",
                    directSubmitUrl = "$base/submit?submittedProblemIndex=$index",
                    mySubmissionsUrl = "$base/my",
                    standingsUrl = "$base/standings"
                )
            }

        // Standard contest
        Regex("""codeforces\.com/contest/(\d+)/problem/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (contestId, index) = m.destructured
                val base = "https://codeforces.com/contest/$contestId"
                return CfProblemRef(
                    displayLabel = "Contest $contestId — Problem $index",
                    contestId = contestId.toIntOrNull(),
                    problemIndex = index,
                    problemBaseUrl = "$base/problem/",
                    directSubmitUrl = "$base/submit?submittedProblemIndex=$index",
                    mySubmissionsUrl = "$base/my",
                    standingsUrl = "$base/standings"
                )
            }

        // Gym
        Regex("""codeforces\.com/gym/(\d+)/problem/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (gymId, index) = m.destructured
                val base = "https://codeforces.com/gym/$gymId"
                return CfProblemRef(
                    displayLabel = "Gym $gymId — Problem $index",
                    contestId = gymId.toIntOrNull(),
                    problemIndex = index,
                    problemBaseUrl = "$base/problem/",
                    directSubmitUrl = "$base/submit?submittedProblemIndex=$index",
                    mySubmissionsUrl = "$base/my",
                    standingsUrl = "$base/standings"
                )
            }

        // Problemset
        Regex("""codeforces\.com/problemset/problem/(\d+)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (contestId, index) = m.destructured
                return CfProblemRef(
                    displayLabel = "Problemset $contestId$index",
                    contestId = contestId.toIntOrNull(),
                    problemIndex = index,
                    problemBaseUrl = "https://codeforces.com/problemset/problem/$contestId/",
                    directSubmitUrl = "https://codeforces.com/problemset/submit?submittedProblemCode=$contestId$index",
                    mySubmissionsUrl = "https://codeforces.com/problemset/status?my=on",
                    standingsUrl = "https://codeforces.com/problemset"
                )
            }

        return CfProblemRef(
            displayLabel = trimmed,
            contestId = null,
            problemIndex = "",
            problemBaseUrl = "",
            directSubmitUrl = trimmed,
            mySubmissionsUrl = "https://codeforces.com/problemset/status?my=on",
                    standingsUrl = "https://codeforces.com/problemset"
        )
    }
}
