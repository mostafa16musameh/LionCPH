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
    val problemIndex: String,
    val directSubmitUrl: String
)

object CodeforcesSubmitService {
    val SUPPORTED_LANGUAGES = listOf(
        CfLanguage(91, "GNU G++23 14.2 (64 bit, msys2)"),
        CfLanguage(89, "GNU G++20 13.2 (64 bit, winlibs)"),
        CfLanguage(73, "GNU G++17 9.2.0 (64 bit, msys2)"),
        CfLanguage(54, "GNU G++17 7.3.0")
    )

    /**
     * Detects the C++ standard from CMakeLists.txt or LionCPH compiler args
     * and maps it to the exact matching Codeforces languageId.
     */
    fun resolveCodeforcesLanguage(projectRoot: Path, settings: CphSettings): CfLanguage {
        val overrideId = settings.cfLanguageOverride.trim().toIntOrNull()
        if (overrideId != null) {
            SUPPORTED_LANGUAGES.find { it.id == overrideId }?.let { return it }
        }

        // 1. Check CMakeLists.txt CMAKE_CXX_STANDARD first (matches CLion's active project standard)
        val cmakeFile = projectRoot.resolve("CMakeLists.txt")
        if (Files.isRegularFile(cmakeFile)) {
            runCatching {
                val cmake = Files.readString(cmakeFile, StandardCharsets.UTF_8)
                val match = Regex("""CMAKE_CXX_STANDARD\s+(\d{2})""").find(cmake)
                when (match?.groupValues?.get(1)) {
                    "23", "26" -> return SUPPORTED_LANGUAGES[0] // G++23
                    "20" -> return SUPPORTED_LANGUAGES[1]       // G++20
                    "17" -> return SUPPORTED_LANGUAGES[2]       // G++17 (64 bit)
                }
            }
        }

        // 2. Fallback to LionCPH compiler flags
        val args = settings.compilerArgs.lowercase()
        return when {
            args.contains("c++23") || args.contains("gnu++23") -> SUPPORTED_LANGUAGES[0]
            args.contains("c++20") || args.contains("gnu++20") -> SUPPORTED_LANGUAGES[1]
            else -> SUPPORTED_LANGUAGES[2]
        }
    }

    /**
     * Parses any Codeforces problem URL (contest, problemset, gym, group)
     * and builds the exact direct Submit URL for that specific problem.
     */
    fun parseCodeforcesUrl(url: String): CfProblemRef? {
        val trimmed = url.trim()
        if (!trimmed.contains("codeforces.com", ignoreCase = true)) return null

        // EDU practice: https://codeforces.com/edu/course/<c>/lesson/<l>/practice/contest/<id>/problem/<index>
        Regex("""(https?://[^/]*codeforces\.com/edu/course/\d+/lesson/[^/]+/practice/contest/(\d+))/problem/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (prefix, contestId, index) = m.destructured
                return CfProblemRef(
                    displayLabel = "EDU Contest $contestId — Problem $index",
                    problemIndex = index,
                    directSubmitUrl = "$prefix/submit?submittedProblemIndex=$index"
                )
            }

        // Group contest: https://codeforces.com/group/<groupId>/contest/<contestId>/problem/<index>
        Regex("""codeforces\.com/group/([^/]+)/contest/(\d+)/problem/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (groupId, contestId, index) = m.destructured
                return CfProblemRef(
                    displayLabel = "Group $groupId / Contest $contestId — Problem $index",
                    problemIndex = index,
                    directSubmitUrl = "https://codeforces.com/group/$groupId/contest/$contestId/submit?submittedProblemIndex=$index"
                )
            }

        // Standard contest: https://codeforces.com/contest/<contestId>/problem/<index>
        Regex("""codeforces\.com/contest/(\d+)/problem/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (contestId, index) = m.destructured
                return CfProblemRef(
                    displayLabel = "Contest $contestId — Problem $index",
                    problemIndex = index,
                    directSubmitUrl = "https://codeforces.com/contest/$contestId/submit?submittedProblemIndex=$index"
                )
            }

        // Gym: https://codeforces.com/gym/<gymId>/problem/<index>
        Regex("""codeforces\.com/gym/(\d+)/problem/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (gymId, index) = m.destructured
                return CfProblemRef(
                    displayLabel = "Gym $gymId — Problem $index",
                    problemIndex = index,
                    directSubmitUrl = "https://codeforces.com/gym/$gymId/submit?submittedProblemIndex=$index"
                )
            }

        // Problemset: https://codeforces.com/problemset/problem/<contestId>/<index>
        Regex("""codeforces\.com/problemset/problem/(\d+)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let { m ->
                val (contestId, index) = m.destructured
                return CfProblemRef(
                    displayLabel = "Problemset $contestId$index",
                    problemIndex = index,
                    directSubmitUrl = "https://codeforces.com/problemset/submit?submittedProblemCode=$contestId$index"
                )
            }

        return CfProblemRef(
            displayLabel = trimmed,
            problemIndex = "",
            directSubmitUrl = trimmed
        )
    }
}
