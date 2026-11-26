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
import java.util.Locale
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
    val isFinal: Boolean = false,
    val virtualStartEpochSec: Long = 0L,
    val contestDurationSec: Long = 0L
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

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val scheduler = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "LionCPH-CF-API").apply { isDaemon = true }
    }
    private val pollScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LionCPH-CF-Poll").apply { isDaemon = true }
    }

    private val contestProblemsCache = ConcurrentHashMap<Int, List<Pair<String, String>>>()
    private val contestDurationCache = ConcurrentHashMap<Int, Long>()

    private data class GroupContestMeta(
        val name: String,
        val durationSeconds: Long,
        val liveMode: String = "", // "LIVE", "BEFORE", or ""
        val liveRemainingSec: Long = 0L
    )

    private fun parseDurationTextToSeconds(raw: String): Long {
        val clean = raw.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").trim()
        // Format: D:HH:MM (e.g. 2:05:30)
        Regex("""\b(\d+):(\d{2}):(\d{2})\b""").find(clean)?.let { m ->
            val a = m.groupValues[1].toLongOrNull() ?: 0L
            val b = m.groupValues[2].toLongOrNull() ?: 0L
            val c = m.groupValues[3].toLongOrNull() ?: 0L
            return a * 86400L + b * 3600L + c * 60L
        }
        // Format: HH:MM (e.g. 01:30, 02:00, 00:45)
        Regex("""\b(\d{1,3}):(\d{2})\b""").find(clean)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues[2].toLongOrNull() ?: 0L
            return h * 3600L + min * 60L
        }
        // Format: N days / N hours
        Regex("""(\d+)\s*days?""", RegexOption.IGNORE_CASE).find(clean)?.let { m ->
            return (m.groupValues[1].toLongOrNull() ?: 0L) * 86400L
        }
        Regex("""(\d+)\s*hours?""", RegexOption.IGNORE_CASE).find(clean)?.let { m ->
            return (m.groupValues[1].toLongOrNull() ?: 0L) * 3600L
        }
        return 0L
    }

    private fun parseCountdownToSeconds(raw: String): Long {
        val clean = raw.replace(Regex("<[^>]+>"), " ").trim()
        Regex("""\b(\d+):(\d{2}):(\d{2})\b""").find(clean)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues[2].toLongOrNull() ?: 0L
            val s = m.groupValues[3].toLongOrNull() ?: 0L
            return h * 3600L + min * 60L + s
        }
        return 0L
    }

    private fun fetchGroupContestMetaFromWeb(groupId: String, contestId: Int): GroupContestMeta? {
        if (groupId.isBlank()) return null
        return runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://codeforces.com/group/$groupId/contests"))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (resp.statusCode() != 200) return@runCatching null
            val html = resp.body()
            val rowMatch = Regex(
                """<tr[^>]*data-contestId\s*=\s*["']$contestId["'][^>]*>(.*?)</tr>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(html) ?: return@runCatching null
            val rowHtml = rowMatch.groupValues[1]
            val cells = Regex("""<td[^>]*>(.*?)</td>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(rowHtml).map { it.groupValues[1] }.toList()
            if (cells.isEmpty()) return@runCatching null

            val rawName = unescapeHtml(cells[0].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("""Enter\s*».*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""Virtual participation.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .ifBlank { "Contest $contestId" }

            // Length column is usually cells[2] or cells[3] (search non-name cells for HH:MM / days)
            var durationSec = 0L
            for (i in 1 until cells.size) {
                val cellText = cells[i]
                if (cellText.contains("countdown", ignoreCase = true) || cellText.contains("Before ", ignoreCase = true)) continue
                val d = parseDurationTextToSeconds(cellText)
                if (d in 300L..3_000_000L) {
                    durationSec = d
                    break
                }
            }

            var liveMode = ""
            var liveRemSec = 0L
            val cdMatch = Regex("""class\s*=\s*["'][^"']*countdown[^"']*["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(rowHtml)
            if (cdMatch != null) {
                val rem = parseCountdownToSeconds(cdMatch.groupValues[1])
                if (rem > 0L) {
                    liveRemSec = rem
                    liveMode = if (rowHtml.contains("Before start", ignoreCase = true)) "BEFORE" else "LIVE"
                }
            }

            if (durationSec > 0L) {
                if (contestDurationCache.size >= 64) contestDurationCache.clear()
                contestDurationCache[contestId] = durationSec
            }
            GroupContestMeta(rawName, durationSec, liveMode, liveRemSec)
        }.getOrNull()
    }

    private val activeTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val sessionGenerations = ConcurrentHashMap<String, AtomicLong>()

    private fun generationFor(sessionKey: String): AtomicLong =
        sessionGenerations.computeIfAbsent(sessionKey) { AtomicLong(0L) }

    fun stopPolling(sessionKey: String = "default") {
        generationFor(sessionKey).incrementAndGet()
        activeTasks.remove(sessionKey)?.cancel(true)
    }

    fun isPollingActive(sessionKey: String): Boolean {
        val f = activeTasks[sessionKey] ?: return false
        return !f.isCancelled && !f.isDone
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
            JsonParser.parseString(body).asJsonObject.strOrNull("comment")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Codeforces API returned HTTP $fallbackStatus."
    }

    private val apiRateLock = Any()
    private var lastApiRequestMs: Long = 0L

    private fun awaitApiRateSlot() {
        synchronized(apiRateLock) {
            val now = System.currentTimeMillis()
            val waitMs = (lastApiRequestMs + 2100L) - now
            if (waitMs > 0L) {
                runCatching { Thread.sleep(waitMs) }
            }
            lastApiRequestMs = System.currentTimeMillis()
        }
    }

    private fun sendApiGet(url: String, timeoutSec: Long = 8): HttpResponse<String> {
        awaitApiRateSlot()
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("User-Agent", "LionCPH/1.9.5")
            .GET()
            .build()
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if ((resp.statusCode() == 400 || resp.statusCode() == 429 || resp.statusCode() == 503) &&
            resp.body().contains("Call limit", ignoreCase = true)
        ) {
            runCatching { Thread.sleep(2200L) }
            awaitApiRateSlot()
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
        return resp
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

                val isGroup = cfRef.directSubmitUrl.contains("/group/", ignoreCase = true)
                val isGym = cfRef.directSubmitUrl.contains("/gym/", ignoreCase = true)
                val isNonGym = !isGroup && !isGym
                val hasAuth = apiKey.isNotBlank() && apiSecret.isNotBlank()

                // 0. For standard non-gym contests (Div.1/2/3/4, Edu, Global), use contest.list?gym=false + user.status.
                //    Codeforces restricts non-gym contest.standings to bare "?contestId=<id>" with no extra params,
                //    so contest.list + user.status avoids HTTP 400 completely and supports LIVE, BEFORE, VIRTUAL, and FINISHED.
                if (isNonGym) {
                    val listResp = sendApiGet("https://codeforces.com/api/contest.list?gym=false", 8)
                    if (listResp.statusCode() == 200) {
                        val listRoot = runCatching { JsonParser.parseString(listResp.body()).asJsonObject }.getOrNull()
                        val arr = if (listRoot?.strOrNull("status") == "OK") listRoot.arrOrNull("result") else null
                        val matchContest = arr?.firstOrNull { it.asJsonObject.intOrNull("id") == cid }?.asJsonObject
                        if (matchContest != null) {
                            val name = matchContest.strOrNull("name") ?: "Contest $cid"
                            val phase = matchContest.strOrNull("phase") ?: ""
                            val duration = matchContest.longOrNull("durationSeconds") ?: 0L
                            if (duration > 0L) {
                                if (contestDurationCache.size >= 64) contestDurationCache.clear()
                                contestDurationCache[cid] = duration
                            }
                            val startTime = matchContest.longOrNull("startTimeSeconds") ?: 0L
                            val relTime = matchContest.longOrNull("relativeTimeSeconds")
                            val nowSec = System.currentTimeMillis() / 1000L

                            if (phase == "CODING" && duration > 0L) {
                                val liveEnd = if (relTime != null) nowSec + (duration - relTime) else startTime + duration
                                if (liveEnd > nowSec) {
                                    onResult(CfContestTimerSync(cid, name, "LIVE", liveEnd, duration, "Synced with live Codeforces contest"))
                                    return@execute
                                }
                            }
                            if (phase == "BEFORE" && relTime != null && relTime < 0L) {
                                onResult(CfContestTimerSync(cid, name, "BEFORE", nowSec + (-relTime), duration, "Contest has not started yet"))
                                return@execute
                            }
                            // Check active VIRTUAL participation via user.status (avoids banned handles= param on contest.standings)
                            if (cleanHandle.isNotEmpty() && duration > 0L) {
                                val uUrl = buildApiUrl("user.status", mapOf("handle" to cleanHandle, "from" to "1", "count" to "25"), "", "")
                                val uResp = sendApiGet(uUrl, 7)
                                if (uResp.statusCode() == 200) {
                                    val uRoot = runCatching { JsonParser.parseString(uResp.body()).asJsonObject }.getOrNull()
                                    val subs = if (uRoot?.strOrNull("status") == "OK") uRoot.arrOrNull("result") else null
                                    var bestVirtualEnd = 0L
                                    if (subs != null) {
                                        for (el in subs) {
                                            val sub = el.asJsonObject
                                            if (sub.intOrNull("contestId") != cid) continue
                                            val author = sub.objOrNull("author") ?: continue
                                            if (author.strOrNull("participantType") == "VIRTUAL") {
                                                val vStart = author.longOrNull("startTimeSeconds") ?: continue
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
                            }
                            onResult(CfContestTimerSync(cid, name, "FINISHED", 0L, duration, "This contest is finished (Practice mode)."))
                            return@execute
                        }
                    }
                }

                // 0b. For Codeforces Groups (University sheets, Assiut, etc.), read exact sheet length & live countdown
                //     from https://codeforces.com/group/<groupId>/contests and check active Virtual via status API.
                var groupMeta: GroupContestMeta? = null
                if (isGroup) {
                    val groupId = Regex("""/group/([^/]+)""", RegexOption.IGNORE_CASE).find(cfRef.directSubmitUrl)?.groupValues?.get(1).orEmpty()
                    groupMeta = fetchGroupContestMetaFromWeb(groupId, cid)
                    val nowSec = System.currentTimeMillis() / 1000L
                    if (groupMeta != null && groupMeta.liveMode.isNotEmpty() && groupMeta.liveRemainingSec > 0L) {
                        val msg = if (groupMeta.liveMode == "BEFORE") "Group contest has not started yet" else "Synced with live Group contest"
                        onResult(CfContestTimerSync(cid, groupMeta.name, groupMeta.liveMode, nowSec + groupMeta.liveRemainingSec, groupMeta.durationSeconds, msg))
                        return@execute
                    }
                    val knownDur = groupMeta?.durationSeconds ?: contestDurationCache[cid] ?: 0L
                    if (cleanHandle.isNotEmpty() && knownDur > 0L) {
                        val stUrl = if (hasAuth) {
                            buildApiUrl("contest.status", mapOf("contestId" to cid.toString(), "handle" to cleanHandle, "from" to "1", "count" to "15"), apiKey, apiSecret)
                        } else {
                            buildApiUrl("user.status", mapOf("handle" to cleanHandle, "from" to "1", "count" to "25"), "", "")
                        }
                        val stResp = sendApiGet(stUrl, 7)
                        if (stResp.statusCode() == 200) {
                            val stRoot = runCatching { JsonParser.parseString(stResp.body()).asJsonObject }.getOrNull()
                            val subs = if (stRoot?.strOrNull("status") == "OK") stRoot.arrOrNull("result") else null
                            var bestVirtualEnd = 0L
                            if (subs != null) {
                                for (el in subs) {
                                    val sub = el.asJsonObject
                                    if (sub.intOrNull("contestId") != cid) continue
                                    val author = sub.objOrNull("author") ?: continue
                                    if (author.strOrNull("participantType") == "VIRTUAL") {
                                        val vStart = author.longOrNull("startTimeSeconds") ?: continue
                                        val vEnd = vStart + knownDur
                                        if (vEnd > bestVirtualEnd) bestVirtualEnd = vEnd
                                    }
                                }
                            }
                            if (bestVirtualEnd > nowSec) {
                                onResult(CfContestTimerSync(cid, groupMeta?.name ?: "Contest $cid", "VIRTUAL", bestVirtualEnd, knownDur, "Synced with your active Group Virtual"))
                                return@execute
                            }
                        }
                    }
                }

                // 1. Query contest.standings for Gym / Group contests (or fallback if contest not in contest.list)
                var resp = if (isNonGym) {
                    sendApiGet("https://codeforces.com/api/contest.standings?contestId=$cid", 10)
                } else {
                    sendApiGet(buildApiUrl("contest.standings", params, apiKey, apiSecret), 7)
                }
                val primaryResp = resp
                if (resp.statusCode() != 200 && !isNonGym && hasAuth && !isGroup) {
                    resp = sendApiGet(buildApiUrl("contest.standings", params, "", ""), 7)
                }
                if (resp.statusCode() != 200 && cleanHandle.isNotEmpty() && !isNonGym) {
                    val basicParams = mapOf("contestId" to cid.toString(), "from" to "1", "count" to "1")
                    resp = sendApiGet(buildApiUrl("contest.standings", basicParams, apiKey, apiSecret), 7)
                }
                if (resp.statusCode() != 200) {
                    if (isGroup && groupMeta != null && groupMeta.durationSeconds > 0L) {
                        onResult(CfContestTimerSync(cid, groupMeta.name, "FINISHED", 0L, groupMeta.durationSeconds, "Group sheet ready (Practice/Virtual mode)."))
                        return@execute
                    }
                    val apiComment = extractApiComment(primaryResp.body(), primaryResp.statusCode())
                    val friendlyMsg = when {
                        isGroup && !hasAuth ->
                            "Group contest timer requires CF API Key & Secret in Settings (Codeforces: $apiComment)."
                        isGroup && apiComment.contains("not found", ignoreCase = true) ->
                            "Codeforces API does not expose live/virtual timer data for this Group contest ($apiComment)."
                        else ->
                            "Codeforces API error (HTTP ${primaryResp.statusCode()}): $apiComment"
                    }
                    onResult(CfContestTimerSync(cid, "Contest $cid", "ERROR", 0L, 0L, friendlyMsg))
                    return@execute
                }

                val root = JsonParser.parseString(resp.body()).asJsonObject
                if (root.strOrNull("status") != "OK") {
                    val err = root.strOrNull("comment") ?: "Codeforces API error"
                    onResult(CfContestTimerSync(cid, "Contest $cid", "ERROR", 0L, 0L, err))
                    return@execute
                }

                val res = root.objOrNull("result") ?: run { onResult(CfContestTimerSync(cid, "Contest $cid", "ERROR", 0L, 0L, "Empty API result")); return@execute }
                val contestObj = res.objOrNull("contest") ?: run { onResult(CfContestTimerSync(cid, "Contest $cid", "ERROR", 0L, 0L, "Missing contest object")); return@execute }
                val name = contestObj.strOrNull("name") ?: "Contest $cid"
                val phase = contestObj.strOrNull("phase") ?: ""
                val duration = contestObj.longOrNull("durationSeconds") ?: 0L
                val startTime = contestObj.longOrNull("startTimeSeconds") ?: 0L
                val relTime = contestObj.longOrNull("relativeTimeSeconds")
                val nowSec = System.currentTimeMillis() / 1000L

                // 1. Check active VIRTUAL participation for this user
                if (cleanHandle.isNotEmpty() && duration > 0L) {
                    val rows = res.arrOrNull("rows")
                    var bestVirtualEnd = 0L
                    if (rows != null) {
                        for (el in rows) {
                            val party = el.asJsonObject.objOrNull("party") ?: continue
                            val pType = party.strOrNull("participantType") ?: ""
                            if (pType == "VIRTUAL") {
                                val vStart = party.longOrNull("startTimeSeconds") ?: continue
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
                val isGroupOrGym = cfRef.directSubmitUrl.contains("/group/", ignoreCase = true) ||
                    cfRef.directSubmitUrl.contains("/gym/", ignoreCase = true)
                val url = buildApiUrl(
                    "contest.standings",
                    mapOf("contestId" to cid.toString(), "from" to "1", "count" to "1"),
                    if (isGroupOrGym) apiKey else "",
                    if (isGroupOrGym) apiSecret else ""
                )
                var resp = sendApiGet(url, 6)
                if (resp.statusCode() != 200 && !isGroupOrGym) {
                    resp = sendApiGet("https://codeforces.com/api/contest.standings?contestId=$cid", 10)
                }
                if (resp.statusCode() != 200) return@runCatching null
                val root = JsonParser.parseString(resp.body()).asJsonObject
                if (root.strOrNull("status") != "OK") return@runCatching null
                val arr = root.objOrNull("result")?.arrOrNull("problems") ?: return@runCatching null
                arr.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val idx = obj.strOrNull("index") ?: return@mapNotNull null
                    val name = obj.strOrNull("name") ?: idx
                    idx to name
                }
            }.getOrNull()

            if (!fetched.isNullOrEmpty()) {
                if (contestProblemsCache.size >= 64) contestProblemsCache.clear()
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
                if (root.strOrNull("status") != "OK") {
                    val comment = root.strOrNull("comment") ?: "Unknown API error"
                    onResult(emptyList(), comment)
                    return@execute
                }
                val arr = root.arrOrNull("result") ?: run {
                    onResult(emptyList(), null)
                    return@execute
                }
                val rows = arr.map { el ->
                    val sub = el.asJsonObject
                    val id = sub.longOrNull("id") ?: 0L
                    val cid = sub.intOrNull("contestId") ?: (cfRef?.contestId ?: 0)
                    val prob = sub.objOrNull("problem")
                    val idx = prob?.strOrNull("index").orEmpty()
                    val name = prob?.strOrNull("name").orEmpty()
                    val verdict = sub.strOrNull("verdict") ?: "TESTING"
                    val passed = sub.intOrNull("passedTestCount") ?: 0
                    val timeMs = sub.intOrNull("timeConsumedMillis") ?: 0
                    val memMb = String.format(Locale.US, "%.1f", (sub.longOrNull("memoryConsumedBytes") ?: 0L) / (1024.0 * 1024.0))
                    val lang = sub.strOrNull("programmingLanguage").orEmpty()
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
                if (root.strOrNull("status") != "OK") {
                    onResult(emptyList(), root.strOrNull("comment") ?: "API error")
                    return@execute
                }
                val arr = root.arrOrNull("result") ?: run {
                    onResult(emptyList(), null)
                    return@execute
                }
                val upcoming = arr.mapNotNull { el ->
                    val c = el.asJsonObject
                    if (c.strOrNull("phase") != "BEFORE") return@mapNotNull null
                    val id = c.intOrNull("id") ?: return@mapNotNull null
                    val name = c.strOrNull("name") ?: "Contest $id"
                    val relSec = c.longOrNull("relativeTimeSeconds") ?: 0L
                    val startsIn = (-relSec).coerceAtLeast(0L)
                    val duration = c.longOrNull("durationSeconds") ?: 7200L
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

    fun startPolling(
        handle: String,
        cfRef: CfProblemRef,
        apiKey: String,
        apiSecret: String,
        submittedAfterEpochSec: Long,
        sessionKey: String = "default",
        onUpdate: (LiveVerdictUpdate) -> Unit
    ) {
        stopPolling(sessionKey)
        val genCounter = generationFor(sessionKey)
        val myGen = genCounter.incrementAndGet()
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

        val future = pollScheduler.scheduleWithFixedDelay({
            if (genCounter.get() != myGen) return@scheduleWithFixedDelay
            val nowMs = System.currentTimeMillis()
            if (nowMs < nextAllowedAtMs.get()) return@scheduleWithFixedDelay

            val step = attempts.incrementAndGet()
            if (step > 80) {
                if (genCounter.get() == myGen) {
                    onUpdate(LiveVerdictUpdate("CF Live: Timed out waiting for submission", "INFO", isFinal = true))
                    stopPolling(sessionKey)
                }
                return@scheduleWithFixedDelay
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
                if (root.strOrNull("status") != "OK") {
                    if (root.strOrNull("comment")?.contains("Call limit", true) == true) {
                        nextAllowedAtMs.set(System.currentTimeMillis() + 2200L)
                    }
                    return@runCatching
                }

                val submissions = root.arrOrNull("result") ?: return@runCatching
                val expectedIdx = cfRef.problemIndex
                val match = submissions.firstOrNull { el ->
                    val sub = el.asJsonObject
                    val created = sub.longOrNull("creationTimeSeconds") ?: 0L
                    val idx = sub.objOrNull("problem")?.strOrNull("index").orEmpty()
                    val subCid = sub.intOrNull("contestId")
                    val timeOk = created >= submittedAfterEpochSec - 20L
                    val idxOk = expectedIdx.isBlank() || idx.equals(expectedIdx, ignoreCase = true)
                    val cidOk = cfRef.contestId == null || subCid == null || subCid == cfRef.contestId
                    timeOk && idxOk && cidOk
                }?.asJsonObject ?: return@runCatching

                val verdict = match.strOrNull("verdict") ?: "TESTING"
                val passedTests = match.intOrNull("passedTestCount") ?: 0
                val timeMs = match.intOrNull("timeConsumedMillis") ?: 0
                val memBytes = match.longOrNull("memoryConsumedBytes") ?: 0L
                val memMb = String.format(Locale.US, "%.1f", memBytes / (1024.0 * 1024.0))
                val probIdx = match.objOrNull("problem")?.strOrNull("index").orEmpty()
                val authorObj = match.objOrNull("author")
                val vStart = if (authorObj?.strOrNull("participantType") == "VIRTUAL") {
                    authorObj.longOrNull("startTimeSeconds") ?: 0L
                } else 0L
                val subCidForDur = match.intOrNull("contestId") ?: cfRef.contestId
                val vDur = if (vStart > 0L && subCidForDur != null) contestDurationCache[subCidForDur] ?: 0L else 0L

                if (genCounter.get() != myGen) return@runCatching
                when (verdict) {
                    "TESTING" -> {
                        onUpdate(LiveVerdictUpdate("CF Live [$probIdx]: Running on test ${passedTests + 1}...", "TESTING", virtualStartEpochSec = vStart, contestDurationSec = vDur))
                    }
                    "OK" -> {
                        onUpdate(LiveVerdictUpdate("CF Live [$probIdx]: ACCEPTED (${timeMs}ms, ${memMb} MB)", "OK", isFinal = true, virtualStartEpochSec = vStart, contestDurationSec = vDur))
                        stopPolling(sessionKey)
                    }
                    else -> {
                        val pretty = verdict.replace('_', ' ')
                        val detail = if (verdict == "COMPILATION_ERROR") pretty else "$pretty on test ${passedTests + 1} (${timeMs}ms)"
                        onUpdate(LiveVerdictUpdate("CF Live [$probIdx]: $detail", "FAIL", isFinal = true, virtualStartEpochSec = vStart, contestDurationSec = vDur))
                        stopPolling(sessionKey)
                    }
                }
            }
        }, 500, 2200, TimeUnit.MILLISECONDS)
        val prev = activeTasks.put(sessionKey, future)
        prev?.cancel(true)
        if (genCounter.get() != myGen) {
            future.cancel(true)
            activeTasks.remove(sessionKey, future)
        }
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