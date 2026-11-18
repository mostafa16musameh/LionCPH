package com.lioncph.plugin.services

import com.intellij.openapi.application.PathManager
import com.lioncph.plugin.models.CphSettings
import com.lioncph.plugin.models.ProblemData
import com.lioncph.plugin.models.TestCase
import com.lioncph.plugin.models.TestResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CompileOutcome(
    val success: Boolean,
    val binaryPath: Path?,
    val stderr: String = ""
)

object JudgeService {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private const val MAX_OUTPUT_BYTES = 512 * 1024

    @Volatile
    private var cachedCompilerBinDir: String? = null

    private data class CompileCacheEntry(val signature: String, val binaryPath: Path)
    private val compileCache = ConcurrentHashMap<String, CompileCacheEntry>()

    fun clearCompileCache() {
        compileCache.clear()
    }

    fun resolveCompiler(configured: String): String {
        val trimmed = configured.trim()
        if (trimmed.isNotEmpty() && !trimmed.equals("auto", ignoreCase = true)) {
            val explicit = Path.of(trimmed)
            if (Files.isRegularFile(explicit)) {
                cachedCompilerBinDir = explicit.parent?.toAbsolutePath()?.toString()
                return explicit.toAbsolutePath().toString()
            }
            findOnPath(trimmed)?.let { found ->
                cachedCompilerBinDir = found.parent?.toAbsolutePath()?.toString()
                return found.toAbsolutePath().toString()
            }
        }

        val candidates = mutableListOf<Path>()

        runCatching {
            val ideHome = Path.of(PathManager.getHomePath())
            candidates.add(ideHome.resolve("bin/mingw/bin/g++.exe"))
            candidates.add(ideHome.resolve("bin/mingw/bin/clang++.exe"))
        }

        if (isWindows) {
            listOf("g++.exe", "clang++.exe").forEach { exe ->
                findOnPath(exe)?.let { candidates.add(it) }
            }
            listOf(
                "C:\\msys64\\ucrt64\\bin\\g++.exe",
                "C:\\msys64\\mingw64\\bin\\g++.exe",
                "C:\\msys64\\clang64\\bin\\clang++.exe",
                "C:\\mingw64\\bin\\g++.exe",
                "C:\\MinGW\\bin\\g++.exe",
                "C:\\TDM-GCC-64\\bin\\g++.exe"
            ).forEach { candidates.add(Path.of(it)) }
        } else {
            listOf("g++", "clang++").forEach { exe ->
                findOnPath(exe)?.let { candidates.add(it) }
            }
            listOf(
                "/usr/bin/g++",
                "/usr/local/bin/g++",
                "/opt/homebrew/bin/g++",
                "/usr/bin/clang++",
                "/usr/local/bin/clang++"
            ).forEach { candidates.add(Path.of(it)) }
        }

        val resolved = candidates.firstOrNull { Files.isRegularFile(it) }
        if (resolved != null) {
            cachedCompilerBinDir = resolved.parent?.toAbsolutePath()?.toString()
            return resolved.toAbsolutePath().toString()
        }

        return trimmed.ifEmpty { "g++" }
    }

    private fun findOnPath(executable: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        val names = if (isWindows && !executable.lowercase().endsWith(".exe")) {
            listOf("$executable.exe", executable)
        } else {
            listOf(executable)
        }
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            for (name in names) {
                val p = runCatching { Path.of(dir.trim(), name) }.getOrNull() ?: continue
                if (Files.isRegularFile(p)) return p
            }
        }
        return null
    }

    private fun applyCompilerPathEnv(pb: ProcessBuilder) {
        val binDir = cachedCompilerBinDir ?: return
        val env = pb.environment()
        val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val current = env[key].orEmpty()
        if (!current.contains(binDir, ignoreCase = isWindows)) {
            env[key] = if (current.isBlank()) binDir else "$binDir${File.pathSeparator}$current"
        }
    }

    fun compile(projectRoot: Path, cppFile: Path, settings: CphSettings): CompileOutcome {
        val buildDir = projectRoot.resolve(".cph").resolve("bin")
        Files.createDirectories(buildDir)

        val baseName = cppFile.fileName.toString().substringBeforeLast('.')
        val pathHash = (cppFile.toAbsolutePath().normalize().toString().hashCode().toUInt()).toString(16)
        val exeName = if (isWindows) "${baseName}_$pathHash.exe" else "${baseName}_$pathHash"
        val binaryPath = buildDir.resolve(exeName)

        val compiler = resolveCompiler(settings.compilerPath)
        val mtime = runCatching { Files.getLastModifiedTime(cppFile).toMillis() }.getOrDefault(-1L)
        val signature = "$mtime|$compiler|${settings.compilerArgs}|${settings.setOnlineJudge}"
        val cacheKey = cppFile.toAbsolutePath().toString()
        val cached = compileCache[cacheKey]
        if (cached != null && cached.signature == signature && Files.isRegularFile(cached.binaryPath)) {
            return CompileOutcome(true, cached.binaryPath)
        }

        val rawArgs = settings.compilerArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var outcome = runCompile(compiler, rawArgs, settings.setOnlineJudge, cppFile, binaryPath)

        if (!outcome.success && rawArgs.any { it.equals("-std=c++23", true) } &&
            outcome.stderr.contains("c++23", ignoreCase = true)
        ) {
            for (fallbackStd in listOf("-std=c++20", "-std=c++17")) {
                val patchedArgs = rawArgs.map { if (it.equals("-std=c++23", true)) fallbackStd else it }
                outcome = runCompile(compiler, patchedArgs, settings.setOnlineJudge, cppFile, binaryPath)
                if (outcome.success) break
            }
        }

        if (outcome.success && outcome.binaryPath != null) {
            compileCache[cacheKey] = CompileCacheEntry(signature, outcome.binaryPath)
        } else {
            compileCache.remove(cacheKey)
        }

        return outcome
    }

    private fun runCompile(
        compiler: String,
        args: List<String>,
        setOnlineJudge: Boolean,
        cppFile: Path,
        binaryPath: Path
    ): CompileOutcome {
        val cmd = mutableListOf<String>()
        cmd.add(compiler)
        cmd.addAll(args)
        if (setOnlineJudge) {
            cmd.add("-DONLINE_JUDGE")
        } else {
            cmd.add("-DLOCAL")
        }
        cmd.add(cppFile.toAbsolutePath().toString())
        cmd.add("-o")
        cmd.add(binaryPath.toAbsolutePath().toString())

        return try {
            val pb = ProcessBuilder(cmd)
                .directory(cppFile.parent.toFile())
                .redirectErrorStream(false)
            applyCompilerPathEnv(pb)
            val process = pb.start()

            val stdoutFuture = CompletableFuture.supplyAsync {
                readCapped(process.inputStream, MAX_OUTPUT_BYTES)
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                readCapped(process.errorStream, MAX_OUTPUT_BYTES)
            }
            val finished = process.waitFor(30, TimeUnit.SECONDS)

            if (!finished) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor()
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                CompileOutcome(false, null, "Compilation timed out after 30s.")
            } else {
                val stdout = stdoutFuture.get(2, TimeUnit.SECONDS)
                val stderr = stderrFuture.get(2, TimeUnit.SECONDS)
                if (process.exitValue() != 0) {
                    val combined = listOf(stderr, stdout).filter { it.isNotBlank() }.joinToString("\n")
                    CompileOutcome(false, null, combined.ifBlank { "Compilation failed with exit code ${process.exitValue()}" })
                } else {
                    CompileOutcome(true, binaryPath, stderr)
                }
            }
        } catch (e: Exception) {
            CompileOutcome(false, null, "Failed to run compiler '$compiler': ${e.message}")
        }
    }

    fun runTestCase(
        binaryPath: Path,
        testCase: TestCase,
        problem: ProblemData,
        settings: CphSettings,
        cancelled: AtomicBoolean? = null,
        activeProcesses: MutableSet<Process>? = null
    ): TestResult {
        if (cancelled?.get() == true) {
            return TestResult(testCase.id, "IDLE")
        }
        val timeLimit = problem.timeLimit.coerceAtLeast(100L)
        var process: Process? = null
        return try {
            val pb = ProcessBuilder(binaryPath.toAbsolutePath().toString())
                .directory(binaryPath.parent.toFile())
            applyCompilerPathEnv(pb)

            val startNs = System.nanoTime()
            process = pb.start()
            activeProcesses?.add(process)

            val stdoutFuture = CompletableFuture.supplyAsync {
                readCapped(process.inputStream, MAX_OUTPUT_BYTES)
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                readCapped(process.errorStream, MAX_OUTPUT_BYTES)
            }
            val stdinFuture = CompletableFuture.runAsync {
                runCatching {
                    process.outputStream.use { out ->
                        out.write(testCase.input.toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                    }
                }
            }

            // Poll in short slices so Stop/Cancel responds immediately
            var finished = false
            val deadlineNs = startNs + timeLimit * 1_000_000L
            while (System.nanoTime() < deadlineNs) {
                if (cancelled?.get() == true) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    stdinFuture.cancel(true)
                    stdoutFuture.cancel(true)
                    stderrFuture.cancel(true)
                    return TestResult(testCase.id, "IDLE")
                }
                if (process.waitFor(40, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            }

            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)

            if (!finished) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                stdinFuture.cancel(true)
                val partialOut = runCatching { stdoutFuture.get(300, TimeUnit.MILLISECONDS) }.getOrDefault("")
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                return TestResult(
                    id = testCase.id,
                    status = "TLE",
                    timeMs = timeLimit,
                    stdout = partialOut,
                    stderr = "Time Limit Exceeded (>${timeLimit}ms)"
                )
            }

            val stdout = runCatching { stdoutFuture.get(1, TimeUnit.SECONDS) }.getOrDefault("")
            val stderr = runCatching { stderrFuture.get(1, TimeUnit.SECONDS) }.getOrDefault("")
            val exitCode = process.exitValue()

            if (exitCode != 0) {
                val signalHint = describeExitCode(exitCode)
                val errMsg = if (stderr.isNotBlank()) "$signalHint\n$stderr" else signalHint
                return TestResult(
                    id = testCase.id,
                    status = "RE",
                    timeMs = elapsedMs,
                    stdout = stdout,
                    stderr = errMsg,
                    exitCode = exitCode
                )
            }

            if (problem.useCustomChecker && problem.customCheckerPath.isNotBlank()) {
                val (passed, log) = runCustomChecker(problem.customCheckerPath, testCase, stdout, settings)
                return TestResult(
                    id = testCase.id,
                    status = if (passed) "AC" else "WA",
                    timeMs = elapsedMs,
                    stdout = stdout,
                    stderr = stderr,
                    checkerLog = log,
                    exitCode = 0
                )
            }

            val passed = compareOutputs(testCase.output, stdout, settings.ignoreTrailingSpaces)
            val diffHint = if (passed) "" else firstDiffHint(testCase.output, stdout, settings.ignoreTrailingSpaces)
            TestResult(
                id = testCase.id,
                status = if (passed) "AC" else "WA",
                timeMs = elapsedMs,
                stdout = stdout,
                stderr = stderr,
                diffHint = diffHint,
                exitCode = 0
            )
        } catch (e: Exception) {
            TestResult(
                id = testCase.id,
                status = "RE",
                timeMs = 0,
                stdout = "",
                stderr = e.message ?: "Execution failed"
            )
        } finally {
            if (process != null) activeProcesses?.remove(process)
        }
    }

    private fun readCapped(stream: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        var truncated = false
        stream.use { s ->
            while (true) {
                val read = s.read(buffer)
                if (read == -1) break
                val remaining = maxBytes - total
                if (remaining > 0) {
                    val toWrite = minOf(read, remaining)
                    out.write(buffer, 0, toWrite)
                    total += toWrite
                } else {
                    truncated = true
                }
            }
        }
        val text = out.toString(StandardCharsets.UTF_8)
        return if (truncated) "$text\n... [Output truncated at ${maxBytes / 1024} KB]" else text
    }

    private fun describeExitCode(code: Int): String {
        return if (isWindows) {
            when (code) {
                -1073741819 -> "Runtime Error: Access Violation / SIGSEGV (0xC0000005)"
                -1073741571 -> "Runtime Error: Stack Overflow (0xC00000FD)"
                -1073741676 -> "Runtime Error: Division by Zero / SIGFPE (0xC0000094)"
                -1073740791 -> "Runtime Error: Stack Buffer Overrun (0xC0000409)"
                3 -> "Runtime Error: Aborted / Assertion Failed (SIGABRT)"
                else -> "Runtime Error (exit code $code)"
            }
        } else {
            when (code) {
                139 -> "Runtime Error: Segmentation Fault (SIGSEGV)"
                136 -> "Runtime Error: Floating Point / Division by Zero (SIGFPE)"
                134 -> "Runtime Error: Aborted / Assertion Failed (SIGABRT)"
                137 -> "Runtime Error: Killed (SIGKILL)"
                else -> "Runtime Error (exit code $code)"
            }
        }
    }

    fun compareOutputs(expected: String, received: String, ignoreTrailingSpaces: Boolean): Boolean {
        return normalize(expected, ignoreTrailingSpaces) == normalize(received, ignoreTrailingSpaces)
    }

    private fun firstDiffHint(expected: String, received: String, ignoreTrailingSpaces: Boolean): String {
        val expLines = normalize(expected, ignoreTrailingSpaces).lines()
        val recLines = normalize(received, ignoreTrailingSpaces).lines()
        val maxLen = maxOf(expLines.size, recLines.size)
        for (i in 0 until maxLen) {
            val e = expLines.getOrNull(i)
            val r = recLines.getOrNull(i)
            if (e != r) {
                val eShow = (e ?: "<EOF>").take(60)
                val rShow = (r ?: "<EOF>").take(60)
                return "Diff at line ${i + 1}: expected '$eShow', got '$rShow'"
            }
        }
        return ""
    }

    private fun normalize(text: String, ignoreTrailingSpaces: Boolean): String {
        val unix = text.replace("\r\n", "\n").replace("\r", "\n")
        return if (ignoreTrailingSpaces) {
            unix.lines()
                .map { it.trimEnd() }
                .dropLastWhile { it.isEmpty() }
                .joinToString("\n")
        } else {
            unix.trimEnd('\n')
        }
    }

    private fun runCustomChecker(
        checkerPath: String,
        testCase: TestCase,
        received: String,
        settings: CphSettings
    ): Pair<Boolean, String> {
        var tempDir: Path? = null
        return try {
            val checkerTempDir = Files.createTempDirectory("lioncph_checker_")
            tempDir = checkerTempDir
            val inFile = checkerTempDir.resolve("input.txt")
            val expFile = checkerTempDir.resolve("expected.txt")
            val outFile = checkerTempDir.resolve("received.txt")
            Files.writeString(inFile, testCase.input, StandardCharsets.UTF_8)
            Files.writeString(expFile, testCase.output, StandardCharsets.UTF_8)
            Files.writeString(outFile, received, StandardCharsets.UTF_8)

            val process = ProcessBuilder(
                settings.pythonPath.ifBlank { "python3" },
                checkerPath,
                inFile.toString(),
                expFile.toString(),
                outFile.toString()
            ).redirectErrorStream(true).start()

            val logFuture = CompletableFuture.supplyAsync {
                readCapped(process.inputStream, MAX_OUTPUT_BYTES)
            }
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor()
                logFuture.cancel(true)
                return false to "Checker timed out after 10s."
            }
            val log = logFuture.get(2, TimeUnit.SECONDS)
            val ok = process.exitValue() == 0
            ok to log
        } catch (e: Exception) {
            false to "Checker error: ${e.message}"
        } finally {
            tempDir?.toFile()?.deleteRecursively()
        }
    }
}
