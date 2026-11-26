package com.lioncph.plugin.services

import com.google.gson.JsonParser
import com.lioncph.plugin.models.TestCase
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class LiveVerdictUpdate(
    val text: String,
    val state: String, // "WAITING", "TESTING", "OK", "FAIL", "INFO"
    val isFinal: Boolean = false
)

data class CfContestTimerSync(
    val contestId: Int,
    val contestName: String,
    val mode: String, // "LIVE", "VIRTUAL", "BEFORE", "FINISHED", "ERROR"
    val endEpochSec: Long,
    val durationSeconds: Long,
    val message: String = ""
)

data class CfProblemNavInfo(
    val currentIndex: String,
    val currentPosition: Int,
    val totalCount: Int,
    val prevIndex: String?,
    val nextIndex: String?,
    val nextName: String?,
    val isLast: Boolean
)

data class CfSubmissionRow(
    val id: Long,
    val contestId: Int,
    val problemLabel: String,
    val verdict: String,
    val passedTests: Int,
    val timeMs: Int,
    val memoryMb: String,
    val language: String,
    val submissionUrl: String
)

data class CfUpcomingContest(
    val id: Int,
    val name: String,
    val startsInSeconds: Long,
    val durationSeconds: Long,
    val url: String
)

data class FetchedCfProblem(
    val name: String,
    val url: String,
    val timeLimitMs: Long,
    val tests: List<TestCase>,
    val blockedByCloudflare: Boolean = false,
    val problemNotFound: Boolean = false,
    val detectedFloatTolerance: Double = 0.0
)

object CodeforcesVerdictService {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LionCPH-CF-API").apply { isDaemon = true }
    }

    private val contestProblemsCache = ConcurrentHashMap<Int, List<Pair<String, String>>>()

    @Volatile
    private var activeTask: ScheduledFuture<*>? = null

    @Synchronized
    fun stopPolling() {
        activeTask?.cancel(true)
        activeTask = null
    }

    fun clearProblemsCache() {
        contestProblemsCache.clear()
    }

    private fun buildApiUrl(
        methodName: String,
        params: Map<String, String>,
        apiKey: String,
        apiSecret: String
    ): String {
        val sorted = TreeMap<String, String>()
        sorted.putAll(params)

        val cleanKey = apiKey.trim()
        val cleanSecret = apiSecret.trim()
        val useAuth = cleanKey.isNotEmpty() && cleanSecret.isNotEmpty()

        if (useAuth) {
            sorted["apiKey"] = cleanKey
            sorted["time"] = (System.currentTimeMillis() / 1000L).toString()
        }

        val query = sorted.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }

        if (!useAuth) {
            return "https://codeforces.com/api/$methodName?$query"
        }

        val rand = (100000 + Random.nextInt(900000)).toString()
        val toSign = "$rand/$methodName?$query#$cleanSecret"
        val hash = MessageDigest.getInstance("SHA-512")
            .digest(toSign.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "https://codeforces.com/api/$methodName?$query&apiSig=$rand$hash"
    }

    private fun extractApiComment(body: String, fallbackStatus: Int): String {
        return runCatching {
            JsonParser.parseString(body).asJsonObject.get("comment")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Codeforces API returned HTTP $fallbackStatus."
    }

    private fun sendApiGet(url: String, timeoutSec: Long = 8): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("User-Agent", "LionCPH/1.9.3")
            .GET()
            .build()
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    /**
     * Fetches real-time remaining duration for the active Codeforces Contest or Virtual participation.
     * Uses server-relative time (durationSeconds - relativeTimeSeconds) so local PC clock skew never drifts.
     * Supports Live Contests, Group Sheets, Gym, and active Virtual Contests (when cfHandle is provided).
     */
    fun fetchContestTimerSync(
        cfRef: CfProblemRef,
        handle: String,
        apiKey: String,
        apiSecret: String,
        onResult: (CfContestTimerSync) -> Unit
    ) {
        val cid = cfRef.contestId ?: return
        scheduler.execute {
            try {
                val cleanHandle = handle.trim()
                val params = mutableMapOf("contestId" to cid.toString())
                if (cleanHandle.isNotEmpty()) {
                    params["handles"] = cleanHandle
                    params["showUnofficial"] = "true"
                } else {
                    params["from"] = "1"
                    params["count"] = "1"
                }

                var resp = sendApiGet(buildApiUrl("contest.standings", params, apiKey, apiSecret), 7)
                if (resp.statusCode() != 200 && (apiKey.isNotBlank() || apiSecret.isNotBlank())) {
                    resp = sendApiGet(buildApiUrl("contest.standings", params, "", ""), 7)
                }
                if (resp.statusCode() != 200 && cleanHandle.isNotEmpty()) {
                    val basicParams = mapOf("contestId" to cid.toString(), "from" to "1", "count" to "1")
                    resp = sendApiGet(buildApiUrl("contest.standings", basicParams, apiKey, apiSecret), 7)
                }
                if (resp.statusCode() != 200) {
                    onResult(CfContestTimerSync(cid, "Contest $cid", "ERROR", 0L, 0L, "Could not reach Codeforces API (HTTP ${resp.statusCode()})."))
                    return@execute
                }

                val root = JsonParser.parseString(resp.body()).asJsonObject
                if (root.get("status")?.asString != "OK") {
                    val err = root.get("comment")?.asString ?: "Codeforces API error"
                    onResult(CfContestTimerSync(cid, "Contest $cid", "ERROR", 0L, 0L, err))
                    return@execute
                }

                val res = root.getAsJsonObject("result")
                val contestObj = res.getAsJsonObject("contest")
                val name = contestObj.get("name")?.asString ?: "Contest $cid"
                val phase = contestObj.get("phase")?.asString ?: ""
                val duration = contestObj.get("durationSeconds")?.asLong ?: 0L
                val startTime = contestObj.get("startTimeSeconds")?.asLong ?: 0L
                val relTime = contestObj.get("relativeTimeSeconds")?.asLong
                val nowSec = System.currentTimeMillis() / 1000L

                // 1. Check active VIRTUAL participation for this user
                if (cleanHandle.isNotEmpty() && duration > 0L) {
                    val rows = res.getAsJsonArray("rows")
                    var bestVirtualEnd = 0L
                    if (rows != null) {
                        for (el in rows) {
                            val party = el.asJsonObject.getAsJsonObject("party") ?: continue
                            val pType = party.get("participantType")?.asString ?: ""
                            if (pType == "VIRTUAL") {
                                val vStart = party.get("startTimeSeconds")?.asLong ?: continue
                                val vEnd = vStart + duration
                                if (vEnd > bestVirtualEnd) bestVirtualEnd = vEnd
                            }
                        }
                    }
                    if (bestVirtualEnd > nowSec) {
                        onResult(CfContestTimerSync(cid, name, "VIRTUAL", bestVirtualEnd, duration, "Synced with your active Virtual contest"))
                        return@execute
                    }
                }

                // 2. Check active LIVE contest / group sheet
                if (phase == "CODING" && duration > 0L) {
                    val liveEnd = if (relTime != null) nowSec + (duration - relTime) else startTime + duration
                    if (liveEnd > nowSec) {
                        onResult(CfContestTimerSync(cid, name, "LIVE", liveEnd, duration, "Synced with live Codeforces contest"))
                        return@execute
                    }
                }

                // 3. Check upcoming BEFORE contest
                if (phase == "BEFORE" && relTime != null && relTime < 0L) {
                    onResult(CfContestTimerSync(cid, name, "BEFORE", nowSec + (-relTime), duration, "Contest has not started yet"))
                    return@execute
                }

                onResult(CfContestTimerSync(cid, name, "FINISHED", 0L, duration, "This contest is finished (Practice mode)."))
            } catch (e: Exception) {
                onResult(CfContestTimerSync(cid, "Contest $cid", "ERROR", 0L, 0L, e.message ?: "Sync failed"))
            }
        }
    }

    fun fetchNavigationInfo(
        cfRef: CfProblemRef,
        apiKey: String,
        apiSecret: String,
        onResult: (CfProblemNavInfo) -> Unit
    ) {
        val cid = cfRef.contestId
        val cur = cfRef.problemIndex.uppercase()
        if (cid == null || cur.isBlank()) return

        contestProblemsCache[cid]?.let { cached ->
            onResult(buildNavFromList(cur, cached))
            return
        }

        scheduler.execute {
            val fetched = runCatching {
                val url = buildApiUrl(
                    "contest.standings",
                    mapOf("contestId" to cid.toString(), "from" to "1", "count" to "1"),
                    apiKey,
                    apiSecret
                )
                val resp = sendApiGet(url, 6)
                if (resp.statusCode() != 200) return@runCatching null
                val root = JsonParser.parseString(resp.body()).asJsonObject
                if (root.get("status")?.asString != "OK") return@runCatching null
                val arr = root.getAsJsonObject("result")?.getAsJsonArray("problems") ?: return@runCatching null
                arr.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val idx = obj.get("index")?.asString ?: return@mapNotNull null
                    val name = obj.get("name")?.asString ?: idx
                    idx to name
                }
            }.getOrNull()

            if (!fetched.isNullOrEmpty()) {
                contestProblemsCache[cid] = fetched
                onResult(buildNavFromList(cur, fetched))
            } else {
                onResult(buildAlphabeticalFallback(cur))
            }
        }
    }

    /**
     * Fetches the user's latest 15 submissions with automatic fallback:
     * 1. Authenticated contest.status (for Groups/Contests when API key+secret are set)
     * 2. Authenticated user.status (if contest.status returns 400)
     * 3. Public user.status (if API key/secret is invalid or not set)
     */
    fun fetchRecentSubmissions(
        handle: String,
        cfRef: CfProblemRef?,
        apiKey: String,
        apiSecret: String,
        onResult: (List<CfSubmissionRow>, String?) -> Unit
    ) {
        val cleanHandle = handle.trim()
        if (cleanHandle.isEmpty()) {
            onResult(emptyList(), "Please set your Codeforces Handle in Settings first.")
            return
        }
        scheduler.execute {
            try {
                val hasAuth = apiKey.isNotBlank() && apiSecret.isNotBlank()
                var resp: HttpResponse<String>? = null

                if (cfRef?.contestId != null && hasAuth) {
                    val cUrl = buildApiUrl(
                        "contest.status",
                        mapOf(
                            "contestId" to cfRef.contestId.toString(),
                            "handle" to cleanHandle,
                            "from" to "1",
                            "count" to "15"
                        ),
                        apiKey,
                        apiSecret
                    )
                    val r = sendApiGet(cUrl)
                    if (r.statusCode() == 200) resp = r
                }

                if (resp == null && hasAuth) {
                    val uAuthUrl = buildApiUrl(
                        "user.status",
                        mapOf("handle" to cleanHandle, "from" to "1", "count" to "15"),
                        apiKey,
                        apiSecret
                    )
                    val r = sendApiGet(uAuthUrl)
                    if (r.statusCode() == 200) resp = r
                }

                if (resp == null) {
                    val uPubUrl = buildApiUrl(
                        "user.status",
                        mapOf("handle" to cleanHandle, "from" to "1", "count" to "15"),
                        "",
                        ""
                    )
                    resp = sendApiGet(uPubUrl)
                }

                if (resp.statusCode() != 200) {
                    val comment = extractApiComment(resp.body(), resp.statusCode())
                    onResult(emptyList(), "Codeforces API error: $comment")
                    return@execute
                }

                val root = JsonParser.parseString(resp.body()).asJsonObject
                if (root.get("status")?.asString != "OK") {
                    val comment = root.get("comment")?.asString ?: "Unknown API error"
                    onResult(emptyList(), comment)
                    return@execute
                }
                val arr = root.getAsJsonArray("result") ?: run {
                    onResult(emptyList(), null)
                    return@execute
                }
                val rows = arr.map { el ->
                    val sub = el.asJsonObject
                    val id = sub.get("id")?.asLong ?: 0L
                    val cid = sub.get("contestId")?.asInt ?: (cfRef?.contestId ?: 0)
                    val prob = sub.getAsJsonObject("problem")
                    val idx = prob?.get("index")?.asString.orEmpty()
                    val name = prob?.get("name")?.asString.orEmpty()
                    val verdict = sub.get("verdict")?.asString ?: "TESTING"
                    val passed = sub.get("passedTestCount")?.asInt ?: 0
                    val timeMs = sub.get("timeConsumedMillis")?.asInt ?: 0
                    val memMb = String.format("%.1f", (sub.get("memoryConsumedBytes")?.asLong ?: 0L) / (1024.0 * 1024.0))
                    val lang = sub.get("programmingLanguage")?.asString.orEmpty()
                    val subUrl = if (cfRef != null && cfRef.mySubmissionsUrl.contains("/group/")) {
                        cfRef.mySubmissionsUrl.substringBeforeLast("/my") + "/submission/$id"
                    } else {
                        "https://codeforces.com/contest/$cid/submission/$id"
                    }
                    CfSubmissionRow(id, cid, "$idx. $name", verdict, passed, timeMs, memMb, lang, subUrl)
                }
                onResult(rows, null)
            } catch (e: Exception) {
                onResult(emptyList(), "Failed to fetch submissions: ${e.message}")
            }
        }
    }

    fun fetchUpcomingContests(onResult: (List<CfUpcomingContest>, String?) -> Unit) {
        scheduler.execute {
            try {
                val resp = sendApiGet("https://codeforces.com/api/contest.list?gym=false")
                if (resp.statusCode() != 200) {
                    onResult(emptyList(), extractApiComment(resp.body(), resp.statusCode()))
                    return@execute
                }
                val root = JsonParser.parseString(resp.body()).asJsonObject
                if (root.get("status")?.asString != "OK") {
                    onResult(emptyList(), root.get("comment")?.asString ?: "API error")
                    return@execute
                }
                val arr = root.getAsJsonArray("result") ?: run {
                    onResult(emptyList(), null)
                    return@execute
                }
                val upcoming = arr.mapNotNull { el ->
                    val c = el.asJsonObject
                    if (c.get("phase")?.asString != "BEFORE") return@mapNotNull null
                    val id = c.get("id")?.asInt ?: return@mapNotNull null
                    val name = c.get("name")?.asString ?: "Contest $id"
                    val relSec = c.get("relativeTimeSeconds")?.asLong ?: 0L
                    val startsIn = (-relSec).coerceAtLeast(0L)
                    val duration = c.get("durationSeconds")?.asLong ?: 7200L
                    CfUpcomingContest(id, name, startsIn, duration, "https://codeforces.com/contestRegistration/$id")
                }.sortedBy { it.startsInSeconds }.take(10)
                onResult(upcoming, null)
            } catch (e: Exception) {
                onResult(emptyList(), "Failed to fetch contests: ${e.message}")
            }
        }
    }

    private fun buildNavFromList(currentIndex: String, problems: List<Pair<String, String>>): CfProblemNavInfo {
        val pos = problems.indexOfFirst { it.first.equals(currentIndex, ignoreCase = true) }
        if (pos == -1) return buildAlphabeticalFallback(currentIndex)
        val prev = problems.getOrNull(pos - 1)?.first
        val nextPair = problems.getOrNull(pos + 1)
        return CfProblemNavInfo(
            currentIndex = problems[pos].first,
            currentPosition = pos + 1,
            totalCount = problems.size,
            prevIndex = prev,
            nextIndex = nextPair?.first,
            nextName = nextPair?.second,
            isLast = (pos == problems.lastIndex)
        )
    }

    private fun buildAlphabeticalFallback(currentIndex: String): CfProblemNavInfo {
        val singleLetter = currentIndex.length == 1 && currentIndex[0] in 'A'..'Z'
        if (!singleLetter) {
            return CfProblemNavInfo(currentIndex, -1, -1, null, null, null, false)
        }
        val ch = currentIndex[0]
        val prev = if (ch > 'A') (ch - 1).toString() else null
        val next = if (ch < 'Z') (ch + 1).toString() else null
        return CfProblemNavInfo(
            currentIndex = currentIndex,
            currentPosition = -1,
            totalCount = -1,
            prevIndex = prev,
            nextIndex = next,
            nextName = null,
            isLast = (ch == 'Z')
        )
    }

    @Synchronized
    fun startPolling(
        handle: String,
        cfRef: CfProblemRef,
        apiKey: String,
        apiSecret: String,
        submittedAfterEpochSec: Long,
        onUpdate: (LiveVerdictUpdate) -> Unit
    ) {
        stopPolling()
        val cleanHandle = handle.trim()
        if (cleanHandle.isEmpty()) return

        val isGroup = cfRef.directSubmitUrl.contains("/group/", ignoreCase = true)
        val hasAuth = apiKey.isNotBlank() && apiSecret.isNotBlank()
        if (isGroup && !hasAuth) {
            onUpdate(
                LiveVerdictUpdate(
                    "CF Live: Add CF API Key/Secret in Settings for Group verdicts",
                    "INFO",
                    isFinal = true
                )
            )
            return
        }

        onUpdate(LiveVerdictUpdate("CF Live ($cleanHandle): Waiting for submission...", "WAITING"))
        val attempts = AtomicInteger(0)
        val nextAllowedAtMs = AtomicLong(0L)

        activeTask = scheduler.scheduleAtFixedRate({
            val nowMs = System.currentTimeMillis()
            if (nowMs < nextAllowedAtMs.get()) return@scheduleAtFixedRate

            val step = attempts.incrementAndGet()
            if (step > 80) {
                onUpdate(LiveVerdictUpdate("CF Live: Timed out waiting for submission", "INFO", isFinal = true))
                stopPolling()
                return@scheduleAtFixedRate
            }

            runCatching {
                val url = if (cfRef.contestId != null && hasAuth) {
                    buildApiUrl(
                        "contest.status",
                        mapOf(
                            "contestId" to cfRef.contestId.toString(),
                            "handle" to cleanHandle,
                            "from" to "1",
                            "count" to "6"
                        ),
                        apiKey,
                        apiSecret
                    )
                } else {
                    buildApiUrl(
                        "user.status",
                        mapOf("handle" to cleanHandle, "from" to "1", "count" to "6"),
                        apiKey,
                        apiSecret
                    )
                }

                var resp = sendApiGet(url, 4)
                if (resp.statusCode() == 400 && cfRef.contestId != null && hasAuth) {
                    // Fallback to user.status if contest.status rejects this contestId
                    val fbUrl = buildApiUrl(
                        "user.status",
                        mapOf("handle" to cleanHandle, "from" to "1", "count" to "6"),
                        apiKey,
                        apiSecret
                    )
                    resp = sendApiGet(fbUrl, 4)
                }
                if (resp.statusCode() == 503 || resp.statusCode() == 429) {
                    nextAllowedAtMs.set(System.currentTimeMillis() + 2200L)
                    return@runCatching
                }
                if (resp.statusCode() != 200) return@runCatching

                val root = JsonParser.parseString(resp.body()).asJsonObject
                if (root.get("status")?.asString != "OK") {
                    if (root.get("comment")?.asString?.contains("Call limit", true) == true) {
                        nextAllowedAtMs.set(System.currentTimeMillis() + 2200L)
                    }
                    return@runCatching
                }

                val submissions = root.getAsJsonArray("result") ?: return@runCatching
                val expectedIdx = cfRef.problemIndex
                val match = submissions.firstOrNull { el ->
                    val sub = el.asJsonObject
                    val created = sub.get("creationTimeSeconds")?.asLong ?: 0L
                    val idx = sub.getAsJsonObject("problem")?.get("index")?.asString.orEmpty()
                    val timeOk = created >= submittedAfterEpochSec - 20L
                    val idxOk = expectedIdx.isBlank() || idx.equals(expectedIdx, ignoreCase = true)
                    timeOk && idxOk
                }?.asJsonObject ?: return@runCatching

                val verdict = match.get("verdict")?.asString ?: "TESTING"
                val passedTests = match.get("passedTestCount")?.asInt ?: 0
                val timeMs = match.get("timeConsumedMillis")?.asInt ?: 0
                val memBytes = match.get("memoryConsumedBytes")?.asLong ?: 0L
                val memMb = String.format("%.1f", memBytes / (1024.0 * 1024.0))
                val probIdx = match.getAsJsonObject("problem")?.get("index")?.asString.orEmpty()

                when (verdict) {
                    "TESTING" -> {
                        onUpdate(LiveVerdictUpdate("CF Live [$probIdx]: Running on test ${passedTests + 1}...", "TESTING"))
                    }
                    "OK" -> {
                        onUpdate(LiveVerdictUpdate("CF Live [$probIdx]: ACCEPTED (${timeMs}ms, ${memMb} MB)", "OK", isFinal = true))
                        stopPolling()
                    }
                    else -> {
                        val pretty = verdict.replace('_', ' ')
                        val detail = if (verdict == "COMPILATION_ERROR") pretty else "$pretty on test ${passedTests + 1} (${timeMs}ms)"
                        onUpdate(LiveVerdictUpdate("CF Live [$probIdx]: $detail", "FAIL", isFinal = true))
                        stopPolling()
                    }
                }
            }
        }, 500, 1100, TimeUnit.MILLISECONDS)
    }

    fun fetchProblemFromWeb(
        targetUrl: String,
        fallbackName: String,
        onResult: (FetchedCfProblem) -> Unit
    ) {
        scheduler.execute {
            val result = runCatching {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                val html = resp.body()
                val status = resp.statusCode()
                // Codeforces redirects (302) or returns 404 when problem index exceeds contest/sheet count
                if (status == 404 || status == 302 || status == 301 ||
                    (status == 200 && !html.contains("problem-statement") && html.contains("datatable"))
                ) {
                    return@runCatching FetchedCfProblem(
                        name = fallbackName,
                        url = targetUrl,
                        timeLimitMs = 2000L,
                        tests = emptyList(),
                        blockedByCloudflare = false,
                        problemNotFound = true
                    )
                }
                if (status != 200 || !html.contains("problem-statement")) {
                    return@runCatching FetchedCfProblem(
                        name = fallbackName,
                        url = targetUrl,
                        timeLimitMs = 2000L,
                        tests = emptyList(),
                        blockedByCloudflare = true,
                        problemNotFound = false
                    )
                }

                val titleMatch = Regex("""<div class="title">([^<]+)</div>""").find(html)
                val title = titleMatch?.groupValues?.get(1)?.let { unescapeHtml(it).trim() } ?: fallbackName

                val tlMatch = Regex("""time-limit.*?(\d+(?:\.\d+)?)\s*second""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)
                val timeLimitMs = tlMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 2000L

                val inputs = Regex("""<div class="input">.*?<pre[^>]*>(.*?)</pre>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .findAll(html).map { cleanCfPre(it.groupValues[1]) }.toList()
                val outputs = Regex("""<div class="output">.*?<pre[^>]*>(.*?)</pre>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .findAll(html).map { cleanCfPre(it.groupValues[1]) }.toList()

                val count = minOf(inputs.size, outputs.size)
                val tests = (0 until count).map { i -> TestCase(input = inputs[i], output = outputs[i]) }
                val tolMatch = Regex("""10\^\{-(\d+)\}|1e-(\d+)|10<sup>-(\d+)</sup>""", RegexOption.IGNORE_CASE).find(html)
                val exp = tolMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                val detectedTol = if (exp != null && exp in 2..12) Math.pow(10.0, -exp.toDouble()) else 0.0
                FetchedCfProblem(title, targetUrl, timeLimitMs, tests, blockedByCloudflare = false, detectedFloatTolerance = detectedTol)
            }.getOrElse {
                FetchedCfProblem(fallbackName, targetUrl, 2000L, emptyList(), blockedByCloudflare = true)
            }
            onResult(result)
        }
    }

    private fun cleanCfPre(raw: String): String {
        var s = raw
        s = s.replace(Regex("""</div>\s*<div[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""</?div[^>]*>""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""<[^>]+>"""), "")
        return unescapeHtml(s).trim('\r', '\n') + "\n"
    }

    private fun unescapeHtml(s: String): String {
        return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }
}
