package com.lioncph.plugin.models

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

data class TestCase(
    val id: String = UUID.randomUUID().toString(),
    var input: String = "",
    var output: String = ""
)

data class TestResult(
    val id: String,
    val status: String, // "IDLE", "RUNNING", "AC", "WA", "TLE", "RE", "CE"
    val timeMs: Long = 0,
    val stdout: String = "",
    val stderr: String = "",
    val checkerLog: String = "",
    val diffHint: String = "",
    val exitCode: Int = 0
)

/**
 * Compatible with original CPH .prob JSON schema.
 */
data class ProblemData(
    var name: String = "Local",
    var url: String = "",
    var interactive: Boolean = false,
    var memoryLimit: Int = 256,
    var timeLimit: Long = 2000,
    var srcPath: String = "",
    var group: String = "local",
    var local: Boolean = true,
    var tests: MutableList<TestCase> = mutableListOf(),
    var customCheckerPath: String = "",
    var useCustomChecker: Boolean = false
)

data class CphSettings(
    var compilerPath: String = "auto",
    var compilerArgs: String = "-std=c++23 -O2 -Wall",
    var defaultTimeLimitMs: Long = 2000,
    var setOnlineJudge: Boolean = true,
    var ignoreTrailingSpaces: Boolean = true,
    var autoSyncCMake: Boolean = true,
    var cfLanguageOverride: String = "auto", // "auto", "91" (G++23), "89" (G++20), "73" (G++17 64), "54" (G++17)
    var companionPort: Int = 27121,
    var pythonPath: String = "python3",
    var cppTemplate: String = """
        // Problem: ${'$'}name
        // URL: ${'$'}url

        #include <bits/stdc++.h>
        using namespace std;

        int main() {
            ios::sync_with_stdio(false);
            cin.tie(nullptr);

            return 0;
        }
    """.trimIndent()
)

object CphStorage {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private fun cphDir(projectRoot: Path): Path = projectRoot.resolve(".cph")

    private fun hashPath(filePath: Path): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(filePath.toAbsolutePath().normalize().toString().toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    fun problemFileFor(projectRoot: Path, cppFile: Path): Path {
        val fileName = cppFile.fileName.toString()
        val hash = hashPath(cppFile)
        return cphDir(projectRoot).resolve(".${fileName}_$hash.prob")
    }

    fun loadProblemOrNull(projectRoot: Path, cppFile: Path): ProblemData? {
        val probFile = problemFileFor(projectRoot, cppFile)
        if (Files.isRegularFile(probFile)) {
            runCatching {
                val json = Files.readString(probFile, StandardCharsets.UTF_8)
                val parsed = gson.fromJson(json, ProblemData::class.java)
                if (parsed != null) return sanitizeProblem(parsed)
            }
        }
        val dir = cphDir(projectRoot)
        if (Files.isDirectory(dir)) {
            val prefix = ".${cppFile.fileName}_"
            runCatching {
                Files.list(dir).use { stream ->
                    val match = stream.filter {
                        val n = it.fileName.toString()
                        n.startsWith(prefix) && n.endsWith(".prob")
                    }.findFirst()
                    if (match.isPresent) {
                        val json = Files.readString(match.get(), StandardCharsets.UTF_8)
                        val parsed = gson.fromJson(json, ProblemData::class.java)
                        if (parsed != null) return sanitizeProblem(parsed)
                    }
                }
            }
        }
        return null
    }

    fun createLocalProblem(projectRoot: Path, cppFile: Path): ProblemData {
        val settings = loadSettings(projectRoot)
        val baseName = cppFile.fileName.toString().substringBeforeLast('.')
        val problem = ProblemData(
            name = "Local: $baseName",
            timeLimit = settings.defaultTimeLimitMs,
            srcPath = cppFile.toAbsolutePath().toString(),
            local = true,
            tests = mutableListOf(TestCase(input = "", output = ""))
        )
        saveProblem(projectRoot, cppFile, problem)
        return problem
    }

    fun saveProblem(projectRoot: Path, cppFile: Path, problem: ProblemData) {
        val dir = cphDir(projectRoot)
        Files.createDirectories(dir)
        problem.srcPath = cppFile.toAbsolutePath().toString()
        val probFile = problemFileFor(projectRoot, cppFile)
        Files.writeString(probFile, gson.toJson(problem), StandardCharsets.UTF_8)
    }

    fun deleteProblem(projectRoot: Path, cppFile: Path) {
        val dir = cphDir(projectRoot)
        runCatching { Files.deleteIfExists(problemFileFor(projectRoot, cppFile)) }
        if (Files.isDirectory(dir)) {
            val prefix = ".${cppFile.fileName}_"
            runCatching {
                Files.list(dir).use { stream ->
                    stream.filter {
                        val n = it.fileName.toString()
                        n.startsWith(prefix) && n.endsWith(".prob")
                    }.forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }
        // Also clean up compiled binary (both legacy and path-hashed names)
        val baseName = cppFile.fileName.toString().substringBeforeLast(".")
        val pathHash = (cppFile.toAbsolutePath().normalize().toString().hashCode().toUInt()).toString(16)
        val binDir = dir.resolve("bin")
        runCatching { Files.deleteIfExists(binDir.resolve("${baseName}_$pathHash.exe")) }
        runCatching { Files.deleteIfExists(binDir.resolve("${baseName}_$pathHash")) }
        runCatching { Files.deleteIfExists(binDir.resolve("$baseName.exe")) }
        runCatching { Files.deleteIfExists(binDir.resolve(baseName)) }
    }

    fun settingsFile(projectRoot: Path): Path = cphDir(projectRoot).resolve("lioncph_settings.json")

    fun loadSettings(projectRoot: Path): CphSettings {
        val file = settingsFile(projectRoot)
        if (Files.isRegularFile(file)) {
            runCatching {
                val json = Files.readString(file, StandardCharsets.UTF_8)
                val parsed = gson.fromJson(json, CphSettings::class.java)
                if (parsed != null) return sanitizeSettings(parsed, json)
            }
        }
        return CphSettings()
    }

    fun saveSettings(projectRoot: Path, settings: CphSettings) {
        val dir = cphDir(projectRoot)
        Files.createDirectories(dir)
        Files.writeString(settingsFile(projectRoot), gson.toJson(settings), StandardCharsets.UTF_8)
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeProblem(p: ProblemData): ProblemData {
        if (p.name == null) p.name = "Local"
        if (p.url == null) p.url = ""
        if (p.srcPath == null) p.srcPath = ""
        if (p.group == null) p.group = "local"
        if (p.customCheckerPath == null) p.customCheckerPath = ""
        if (p.tests == null) p.tests = mutableListOf()
        if (p.timeLimit <= 0L) p.timeLimit = 2000L
        return p
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeSettings(s: CphSettings, rawJson: String): CphSettings {
        val defaults = CphSettings()
        if (s.compilerPath == null) s.compilerPath = defaults.compilerPath
        if (s.compilerArgs == null) s.compilerArgs = defaults.compilerArgs
        if (s.cfLanguageOverride == null) s.cfLanguageOverride = defaults.cfLanguageOverride
        if (s.pythonPath == null) s.pythonPath = defaults.pythonPath
        if (s.cppTemplate == null) s.cppTemplate = defaults.cppTemplate
        if (s.defaultTimeLimitMs <= 0L) s.defaultTimeLimitMs = defaults.defaultTimeLimitMs
        if (s.companionPort <= 0) s.companionPort = defaults.companionPort
        if (!rawJson.contains("autoSyncCMake")) s.autoSyncCMake = true
        if (!rawJson.contains("ignoreTrailingSpaces")) s.ignoreTrailingSpaces = true
        if (!rawJson.contains("setOnlineJudge")) s.setOnlineJudge = true
        return s
    }
}
