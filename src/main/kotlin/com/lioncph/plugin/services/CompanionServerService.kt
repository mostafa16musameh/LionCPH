package com.lioncph.plugin.services

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lioncph.plugin.models.CphStorage
import com.lioncph.plugin.models.ProblemData
import com.lioncph.plugin.models.TestCase
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

data class PendingSubmit(
    val problemName: String,
    val url: String,
    val sourceCode: String,
    val languageId: Int = 89
)

object CompanionServerService {
    private val gson = Gson()
    private val servers = mutableMapOf<Int, HttpServer>()
    private val serverExecutors = mutableListOf<java.util.concurrent.ExecutorService>()
    @Volatile private var onProblemReceived: ((Path, ProblemData) -> Unit)? = null
    @Volatile private var projectRootProvider: (() -> Path?)? = null

    @Volatile
    var pendingSubmit: PendingSubmit? = null

    @Volatile
    private var lastCphSubmitPollMs: Long = 0L

    fun isCphSubmitExtensionActive(): Boolean {
        return (System.currentTimeMillis() - lastCphSubmitPollMs) < 5_000L
    }

    fun activePorts(): List<Int> = servers.keys.sorted()

    @Synchronized
    fun start(primaryPort: Int, rootProvider: () -> Path?, onReceived: (Path, ProblemData) -> Unit) {
        projectRootProvider = rootProvider
        onProblemReceived = onReceived

        val targetPorts = linkedSetOf(primaryPort, 27121, 10045)
        if (servers.keys == targetPorts) return
        stop()

        targetPorts.forEach { port ->
            runCatching {
                val httpServer = HttpServer.create(InetSocketAddress(port), 0)
                httpServer.createContext("/") { exchange ->
                    if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                        val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                        val response = "OK".toByteArray(StandardCharsets.UTF_8)
                        exchange.sendResponseHeaders(200, response.size.toLong())
                        exchange.responseBody.use { it.write(response) }
                        handlePayload(body)
                    } else {
                        exchange.sendResponseHeaders(204, -1)
                        exchange.close()
                    }
                }
                httpServer.createContext("/getSubmit") { exchange ->
                    lastCphSubmitPollMs = System.currentTimeMillis()
                    exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                    val sub = pendingSubmit
                    if (sub != null) {
                        pendingSubmit = null
                        val bytes = gson.toJson(sub).toByteArray(StandardCharsets.UTF_8)
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    } else {
                        exchange.sendResponseHeaders(404, -1)
                        exchange.close()
                    }
                }
                val exec = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "LionCPH-Companion-$port").apply { isDaemon = true }
                }
                serverExecutors.add(exec)
                httpServer.executor = exec
                httpServer.start()
                servers[port] = httpServer
            }
        }
    }

    fun importFromJson(root: Path, jsonBody: String): Pair<Path, ProblemData>? {
        return parseAndSave(root, jsonBody)
    }

    private fun handlePayload(jsonBody: String) {
        val root = projectRootProvider?.invoke() ?: return
        val result = parseAndSave(root, jsonBody) ?: return
        onProblemReceived?.invoke(result.first, result.second)
    }

    private fun parseAndSave(root: Path, jsonBody: String): Pair<Path, ProblemData>? {
        return runCatching {
            val obj = JsonParser.parseString(jsonBody).asJsonObject
            val rawName = obj.get("name")?.asString ?: "Problem"
            val url = obj.get("url")?.asString ?: ""
            val group = obj.get("group")?.asString ?: "Competitive Companion"
            val timeLimit = obj.get("timeLimit")?.asLong ?: 2000L
            val memoryLimit = obj.get("memoryLimit")?.asInt ?: 256
            val interactive = obj.get("interactive")?.asBoolean ?: false

            val tests = mutableListOf<TestCase>()
            val testsArray = obj.getAsJsonArray("tests")
            testsArray?.forEach { el ->
                val t = el.asJsonObject
                tests.add(
                    TestCase(
                        input = t.get("input")?.asString ?: "",
                        output = t.get("output")?.asString ?: ""
                    )
                )
            }
            if (tests.isEmpty()) {
                tests.add(TestCase(input = "", output = ""))
            }

            val settings = CphStorage.loadSettings(root)
            val dest = FolderOrganizerService.resolveDestination(root, rawName, group, url, settings)
            val cppFile = dest.directory.resolve(dest.fileName)
            val relPath = root.relativize(cppFile).toString().replace('\\', '/')
            if (!Files.exists(cppFile)) {
                val dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                val code = settings.cppTemplate
                    .replace("\$name", rawName)
                    .replace("\$url", url)
                    .replace("\$date", dateStr)
                    .replace("\$time", timeStr)
                Files.writeString(cppFile, code + "\n", StandardCharsets.UTF_8)
            }

            CMakeSyncService.registerPluginFile(root, dest.cmakeTargetHint, relPath)

            val problem = ProblemData(
                name = rawName,
                url = url,
                interactive = interactive,
                memoryLimit = memoryLimit,
                timeLimit = timeLimit,
                srcPath = cppFile.toAbsolutePath().toString(),
                group = group,
                local = false,
                tests = tests
            )
            CphStorage.saveProblem(root, cppFile, problem)
            cppFile to problem
        }.getOrNull()
    }

    @Synchronized
    fun rebind(rootProvider: () -> Path?, onReceived: (Path, ProblemData) -> Unit) {
        projectRootProvider = rootProvider
        onProblemReceived = onReceived
    }

    @Synchronized
    fun stop() {
        servers.values.forEach { runCatching { it.stop(0) } }
        servers.clear()
        serverExecutors.forEach { runCatching { it.shutdownNow() } }
        serverExecutors.clear()
    }
}
