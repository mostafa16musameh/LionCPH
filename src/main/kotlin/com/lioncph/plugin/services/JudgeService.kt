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
        val signature = "$mtime|$compiler|${settings.compilerArgs}|${settings.setOnlineJudge}|${settings.enableSanitizers}"
        val cacheKey = cppFile.toAbsolutePath().toString()
        val cached = compileCache[cacheKey]
        if (cached != null && cached.signature == signature && Files.isRegularFile(cached.binaryPath)) {
            return CompileOutcome(true, cached.binaryPath)
        }

        val baseArgs = settings.compilerArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val activeArgs = if (settings.enableSanitizers) {
            baseArgs + listOf("-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-g")
        } else {
            baseArgs
        }
        var outcome = runCompile(compiler, activeArgs, settings.setOnlineJudge, cppFile, binaryPath)

        // Fallback if MinGW/GCC on Windows lacks libasan/libubsan: use GCC STL bounds checking (-D_GLIBCXX_DEBUG)
        if (!outcome.success && settings.enableSanitizers &&
            (outcome.stderr.contains("asan", ignoreCase = true) ||
                outcome.stderr.contains("ubsan", ignoreCase = true) ||
                outcome.stderr.contains("sanitize", ignoreCase = true))
        ) {
            val debugStlArgs = baseArgs + listOf("-D_GLIBCXX_DEBUG", "-D_GLIBCXX_DEBUG_PEDANTIC", "-g")
            outcome = runCompile(compiler, debugStlArgs, settings.setOnlineJudge, cppFile, binaryPath)
        }

        if (!outcome.success && activeArgs.any { it.equals("-std=c++23", true) } &&
            outcome.stderr.contains("c++23", ignoreCase = true)
        ) {
            for (fallbackStd in listOf("-std=c++20", "-std=c++17")) {
                val patchedArgs = activeArgs.map { if (it.equals("-std=c++23", true)) fallbackStd else it }
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

            // Poll in short slices so Stop/Cancel responds immediately and peak memory is sampled
            var finished = false
            var peakMemoryKb = 0L
            val memLimitKb = (problem.memoryLimit.coerceAtLeast(16)).toLong() * 1024L
            val pid = runCatching { process.pid() }.getOrDefault(-1L)
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
                if (!isWindows && pid > 0) {
                    val sampleKb = sampleLinuxMemoryKb(pid)
                    if (sampleKb > peakMemoryKb) peakMemoryKb = sampleKb
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

            if (peakMemoryKb > memLimitKb) {
                val usedMb = String.format("%.1f", peakMemoryKb / 1024.0)
                return TestResult(
                    id = testCase.id,
                    status = "MLE",
                    timeMs = elapsedMs,
                    memoryKb = peakMemoryKb,
                    stdout = stdout,
                    stderr = "Memory Limit Exceeded (${usedMb} MB > ${problem.memoryLimit} MB)",
                    exitCode = 0
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

            val effTol = resolveEffectiveFloatTolerance(problem, settings)
            val passed = compareOutputs(testCase.output, stdout, settings.ignoreTrailingSpaces, effTol)
            val diffHint = if (passed) "" else firstDiffHint(testCase.output, stdout, settings.ignoreTrailingSpaces, effTol)
            TestResult(
                id = testCase.id,
                status = if (passed) "AC" else "WA",
                timeMs = elapsedMs,
                memoryKb = peakMemoryKb,
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

    fun detectToleranceFromTests(tests: List<TestCase>): Double {
        var maxDecimals = 0
        val decRegex = Regex("""^-?\d+\.(\d+)$""")
        for (tc in tests) {
            val tokens = tc.output.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            for (tok in tokens) {
                val m = decRegex.matchEntire(tok) ?: continue
                val d = m.groupValues[1].length
                if (d > maxDecimals) maxDecimals = d
            }
        }
        return when {
            maxDecimals >= 8 -> 1e-9
            maxDecimals >= 4 -> 1e-6
            else -> 0.0
        }
    }

    fun resolveEffectiveFloatTolerance(problem: ProblemData, settings: CphSettings): Double {
        return when (settings.floatToleranceMode.lowercase()) {
            "exact" -> 0.0
            "custom" -> settings.floatTolerance.coerceAtLeast(0.0)
            else -> {
                if (problem.detectedFloatTolerance > 0.0) problem.detectedFloatTolerance
                else detectToleranceFromTests(problem.tests)
            }
        }
    }

    fun formatTolerance(tol: Double): String {
        if (tol <= 0.0) return "Exact"
        return when {
            kotlin.math.abs(tol - 1e-4) < 1e-12 -> "1e-4"
            kotlin.math.abs(tol - 1e-5) < 1e-12 -> "1e-5"
            kotlin.math.abs(tol - 1e-6) < 1e-12 -> "1e-6"
            kotlin.math.abs(tol - 1e-7) < 1e-12 -> "1e-7"
            kotlin.math.abs(tol - 1e-9) < 1e-12 -> "1e-9"
            kotlin.math.abs(tol - 1e-12) < 1e-15 -> "1e-12"
            else -> "%.1e".format(java.util.Locale.US, tol)
        }
    }

    fun compareOutputs(
        expected: String,
        received: String,
        ignoreTrailingSpaces: Boolean,
        floatTolerance: Double = 0.0
    ): Boolean {
        val normExp = normalize(expected, ignoreTrailingSpaces)
        val normRec = normalize(received, ignoreTrailingSpaces)
        if (normExp == normRec) return true
        if (floatTolerance <= 0.0) return false

        val expLines = normExp.lines()
        val recLines = normRec.lines()
        if (expLines.size != recLines.size) return false
        for (i in expLines.indices) {
            if (!linesMatchWithTolerance(expLines[i], recLines[i], floatTolerance)) return false
        }
        return true
    }

    private fun linesMatchWithTolerance(expLine: String, recLine: String, tol: Double): Boolean {
        if (expLine == recLine) return true
        val expTokens = expLine.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val recTokens = recLine.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (expTokens.size != recTokens.size) return false
        for (j in expTokens.indices) {
            val et = expTokens[j]
            val rt = recTokens[j]
            if (et == rt) continue
            val ev = et.toDoubleOrNull()
            val rv = rt.toDoubleOrNull()
            if (ev == null || rv == null || !ev.isFinite() || !rv.isFinite()) return false
            val diff = kotlin.math.abs(ev - rv)
            val scale = maxOf(1.0, kotlin.math.abs(ev))
            if (diff > tol * scale) return false
        }
        return true
    }

    private fun firstDiffHint(
        expected: String,
        received: String,
        ignoreTrailingSpaces: Boolean,
        floatTolerance: Double = 0.0
    ): String {
        val expLines = normalize(expected, ignoreTrailingSpaces).lines()
        val recLines = normalize(received, ignoreTrailingSpaces).lines()
        val maxLen = maxOf(expLines.size, recLines.size)
        for (i in 0 until maxLen) {
            val e = expLines.getOrNull(i)
            val r = recLines.getOrNull(i)
            val matches = if (e != null && r != null && floatTolerance > 0.0) {
                linesMatchWithTolerance(e, r, floatTolerance)
            } else {
                e == r
            }
            if (!matches) {
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

    private fun sampleLinuxMemoryKb(pid: Long): Long {
        return runCatching {
            val statusFile = Path.of("/proc/$pid/status")
            if (!Files.isRegularFile(statusFile)) return 0L
            var hwm = 0L
            var rss = 0L
            for (line in Files.readAllLines(statusFile, StandardCharsets.UTF_8)) {
                if (line.startsWith("VmHWM:")) {
                    hwm = Regex("(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                } else if (line.startsWith("VmRSS:")) {
                    rss = Regex("(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                }
            }
            maxOf(hwm, rss)
        }.getOrDefault(0L)
    }
}
