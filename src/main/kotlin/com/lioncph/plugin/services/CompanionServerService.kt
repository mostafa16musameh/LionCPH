package com.lioncph.plugin.services

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lioncph.plugin.models.CphStorage
import com.lioncph.plugin.models.ProblemData
import com.lioncph.plugin.models.TestCase
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
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
    private fun com.google.gson.JsonObject.strOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    private fun com.google.gson.JsonObject.intOrNull(key: String): Int? =
        runCatching { get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt }.getOrNull()

    private fun com.google.gson.JsonObject.longOrNull(key: String): Long? =
        runCatching { get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asLong }.getOrNull()

    private fun com.google.gson.JsonObject.boolOrNull(key: String): Boolean? =
        runCatching { get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean }.getOrNull()

    private fun com.google.gson.JsonObject.objOrNull(key: String): com.google.gson.JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun com.google.gson.JsonObject.arrOrNull(key: String): com.google.gson.JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private const val MAX_REQUEST_BYTES = 5 * 1024 * 1024 // 5 MB safety cap
    private val gson = Gson()
    private val servers = mutableMapOf<Int, MutableList<HttpServer>>()
    private val serverExecutors = mutableListOf<java.util.concurrent.ExecutorService>()
    @Volatile private var onProblemReceived: ((Path, ProblemData, Boolean) -> Unit)? = null
    private val seenBatchIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var projectRootProvider: (() -> Path?)? = null

    @Volatile
    var pendingSubmit: PendingSubmit? = null

    @Volatile
    private var lastCphSubmitPollMs: Long = 0L

    fun isCphSubmitExtensionActive(): Boolean {
        return (System.currentTimeMillis() - lastCphSubmitPollMs) < 5_000L
    }

    fun activePorts(): List<Int> = servers.keys.sorted()

    private fun isAllowedOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme in setOf("chrome-extension", "moz-extension", "safari-web-extension")) {
            return host.isNotBlank()
        }
        if (scheme == "http") {
            return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        }
        return false
    }

    private fun isAllowedHostHeader(hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        val trimmed = hostHeader.trim().lowercase()
        val hostOnly = if (trimmed.startsWith("[")) {
            trimmed.substringBefore("]") + "]"
        } else {
            trimmed.substringBefore(":")
        }
        return hostOnly == "localhost" || hostOnly == "127.0.0.1" || hostOnly == "[::1]"
    }

    private fun readBounded(stream: InputStream, maxBytes: Int): String? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read == -1) break
            total += read
            if (total > maxBytes) return null
            out.write(buf, 0, read)
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    @Synchronized
    fun start(primaryPort: Int, rootProvider: () -> Path?, onReceived: (Path, ProblemData, Boolean) -> Unit) {
        projectRootProvider = rootProvider
        onProblemReceived = onReceived

        val safePrimary = if (primaryPort in 1..65535) primaryPort else 27121
        val targetPorts = linkedSetOf(safePrimary, 27121, 10045)
        if (servers.keys == targetPorts) return
        stop()

        val loopbackAddrs = listOf("127.0.0.1", "::1").mapNotNull { host ->
            runCatching { InetAddress.getByName(host) }.getOrNull()?.takeIf { it.isLoopbackAddress }
        }.distinct()
        targetPorts.forEach { port ->
            val boundForPort = mutableListOf<HttpServer>()
            loopbackAddrs.forEach { addr ->
                runCatching {
                    val httpServer = HttpServer.create(InetSocketAddress(addr, port), 0)
                    httpServer.createContext("/") { exchange ->
                        val isLoopback = exchange.remoteAddress?.address?.isLoopbackAddress == true
                        val origin = exchange.requestHeaders.getFirst("Origin")
                        val hostHeader = exchange.requestHeaders.getFirst("Host")
                        if (!isLoopback || !isAllowedHostHeader(hostHeader) || !isAllowedOrigin(origin)) {
                            exchange.sendResponseHeaders(403, -1)
                            exchange.close()
                            return@createContext
                        }
                        if (!origin.isNullOrBlank()) {
                            exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
                            exchange.responseHeaders.add("Vary", "Origin")
                        }
                        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                            exchange.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
                            exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
                            exchange.sendResponseHeaders(204, -1)
                            exchange.close()
                            return@createContext
                        }
                        if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                            val body = readBounded(exchange.requestBody, MAX_REQUEST_BYTES)
                            if (body == null) {
                                val err = "Payload Too Large".toByteArray(StandardCharsets.UTF_8)
                                exchange.sendResponseHeaders(413, err.size.toLong())
                                exchange.responseBody.use { it.write(err) }
                                return@createContext
                            }
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
                        val isLoopback = exchange.remoteAddress?.address?.isLoopbackAddress == true
                        val origin = exchange.requestHeaders.getFirst("Origin")
                        val hostHeader = exchange.requestHeaders.getFirst("Host")
                        if (!isLoopback || !isAllowedHostHeader(hostHeader) || !isAllowedOrigin(origin)) {
                            exchange.sendResponseHeaders(403, -1)
                            exchange.close()
                            return@createContext
                        }
                        if (!origin.isNullOrBlank()) {
                            exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
                            exchange.responseHeaders.add("Vary", "Origin")
                        }
                        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                            exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, OPTIONS")
                            exchange.sendResponseHeaders(204, -1)
                            exchange.close()
                            return@createContext
                        }
                        lastCphSubmitPollMs = System.currentTimeMillis()
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
                        Thread(r, "LionCPH-Companion-$port-${addr.hostAddress}").apply { isDaemon = true }
                    }
                    serverExecutors.add(exec)
                    httpServer.executor = exec
                    httpServer.start()
                    boundForPort.add(httpServer)
                }
            }
            if (boundForPort.isNotEmpty()) {
                servers[port] = boundForPort
            }
        }
    }

    fun importFromJson(root: Path, jsonBody: String): Pair<Path, ProblemData>? {
        return parseAndSave(root, jsonBody)
    }

    private fun handlePayload(jsonBody: String) {
        val root = projectRootProvider?.invoke() ?: return
        val batchObj = runCatching { JsonParser.parseString(jsonBody).asJsonObject.objOrNull("batch") }.getOrNull()
        val batchId = batchObj?.strOrNull("id").orEmpty()
        val batchSize = batchObj?.intOrNull("size") ?: 1
        val result = parseAndSave(root, jsonBody) ?: return
        // If Competitive Companion sends a full contest/sheet batch (e.g. 7–26 problems at once),
        // create all files cleanly in Groups/Contests, but only auto-open the first problem in the editor
        // so CLion does not get flooded with 26 simultaneous tabs.
        val shouldOpenInEditor = if (batchSize > 1 && batchId.isNotBlank()) {
            if (seenBatchIds.size > 64) seenBatchIds.clear()
            seenBatchIds.add(batchId)
        } else {
            true
        }
        onProblemReceived?.invoke(result.first, result.second, shouldOpenInEditor)
    }

    private fun parseAndSave(root: Path, jsonBody: String): Pair<Path, ProblemData>? {
        return runCatching {
            val obj = JsonParser.parseString(jsonBody).asJsonObject
            val rawName = obj.strOrNull("name") ?: "Problem"
            val url = obj.strOrNull("url") ?: ""
            val group = obj.strOrNull("group") ?: "Competitive Companion"
            val timeLimit = obj.longOrNull("timeLimit") ?: 2000L
            val memoryLimit = obj.intOrNull("memoryLimit") ?: 256
            val interactive = obj.boolOrNull("interactive") ?: false

            val tests = mutableListOf<TestCase>()
            val testsArray = obj.arrOrNull("tests")
            testsArray?.forEach { el ->
                val t = el.asJsonObject
                tests.add(
                    TestCase(
                        input = t.strOrNull("input") ?: "",
                        output = t.strOrNull("output") ?: ""
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

            val existing = CphStorage.loadProblemOrNull(root, cppFile)
            val problem = ProblemData(
                name = rawName,
                url = url,
                interactive = interactive,
                memoryLimit = memoryLimit,
                timeLimit = timeLimit,
                srcPath = cppFile.toAbsolutePath().toString(),
                group = group,
                local = false,
                tests = tests,
                customCheckerPath = existing?.customCheckerPath ?: "",
                useCustomChecker = existing?.useCustomChecker ?: false,
                note = existing?.note ?: "",
                detectedFloatTolerance = existing?.detectedFloatTolerance ?: 0.0
            )
            CphStorage.saveProblem(root, cppFile, problem)
            cppFile to problem
        }.getOrNull()
    }

    @Synchronized
    fun rebind(rootProvider: () -> Path?, onReceived: (Path, ProblemData, Boolean) -> Unit) {
        projectRootProvider = rootProvider
        onProblemReceived = onReceived
    }

    @Synchronized
    fun stop() {
        servers.values.flatten().forEach { runCatching { it.stop(0) } }
        servers.clear()
        serverExecutors.forEach { runCatching { it.shutdownNow() } }
        serverExecutors.clear()
    }
}
