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
    val status: String, // "IDLE", "RUNNING", "AC", "WA", "TLE", "MLE", "RE", "CE"
    val timeMs: Long = 0,
    val memoryKb: Long = 0,
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
    var useCustomChecker: Boolean = false,
    var note: String = "",
    var detectedFloatTolerance: Double = 0.0
)

data class CphSettings(
    var compilerPath: String = "auto",
    var compilerArgs: String = "-std=c++23 -O2 -Wall",
    var defaultTimeLimitMs: Long = 2000,
    var setOnlineJudge: Boolean = true,
    var enableSanitizers: Boolean = false,
    var floatToleranceMode: String = "auto", // "auto", "exact", "custom"
    var floatTolerance: Double = 0.0, // used when mode == "custom"
    var ignoreTrailingSpaces: Boolean = true,
    var autoSyncCMake: Boolean = true,
    var organizeByContest: Boolean = false,
    var organizationMode: String = "flat", // "flat", "single", "hierarchy"
    var fileNameStyle: String = "full",    // "full" (H_Two_numbers.cpp), "short" (H.cpp)
    var cfHandle: String = "",
    var cfApiKey: String = "",
    var cfApiSecret: String = "",
    var cfLanguageOverride: String = "auto", // "auto", "91" (G++23), "89" (G++20), "73" (G++17 64), "54" (G++17)
    var companionPort: Int = 27121,
    var pythonPath: String = "python3",
    var cppTemplate: String = DEFAULT_CPP_TEMPLATE
) {
    companion object {
        val DEFAULT_CPP_TEMPLATE: String = """
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
    }
}

data class CpSnippet(
    var name: String = "",
    var code: String = ""
)

data class SnippetLibrary(
    var snippets: MutableList<CpSnippet> = mutableListOf()
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

    /**
     * Resolves the target directory for an imported problem when organizeByContest is enabled:
     * - Codeforces Group/Sheet -> sanitized sheet/group folder
     * - Codeforces Contest -> sanitized contest folder
     * - Problemset / standalone practice -> Practice/
     */
    fun resolveTargetDir(projectRoot: Path, group: String, url: String, settings: CphSettings): Path {
        if (!settings.organizeByContest) return projectRoot

        val folderName = when {
            url.contains("/problemset/", ignoreCase = true) -> "Practice"
            group.isNotBlank() && !group.equals("local", ignoreCase = true) && !group.equals("Competitive Companion", ignoreCase = true) -> {
                // If group has "Community - Sheet #1 ...", prefer the specific sheet/contest part after " - "
                val specific = if (group.contains(" - ")) group.substringAfter(" - ").trim() else group.trim()
                specific.replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                    .trim()
                    .replace(Regex("\\s+"), "_")
                    .take(42)
                    .trim('_')
                    .ifBlank { "Contest" }
            }
            url.contains("/contest/", ignoreCase = true) -> {
                val cid = Regex("""/contest/(\d+)""").find(url)?.groupValues?.get(1)
                if (cid != null) "Contest_$cid" else "Contests"
            }
            url.contains("/gym/", ignoreCase = true) -> {
                val gid = Regex("""/gym/(\d+)""").find(url)?.groupValues?.get(1)
                if (gid != null) "Gym_$gid" else "Gym"
            }
            else -> "Practice"
        }

        val target = projectRoot.resolve(folderName)
        Files.createDirectories(target)
        return target
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
        val baseName = cppFile.fileName.toString().substringBeforeLast(".")
        val pathHash = (cppFile.toAbsolutePath().normalize().toString().hashCode().toUInt()).toString(16)
        val binDir = dir.resolve("bin")
        runCatching { Files.deleteIfExists(binDir.resolve("${baseName}_$pathHash.exe")) }
        runCatching { Files.deleteIfExists(binDir.resolve("${baseName}_$pathHash")) }
        runCatching { Files.deleteIfExists(binDir.resolve("$baseName.exe")) }
        runCatching { Files.deleteIfExists(binDir.resolve(baseName)) }
    }

    fun snippetsFile(projectRoot: Path): Path = cphDir(projectRoot).resolve("snippets.json")

    fun defaultSnippets(): MutableList<CpSnippet> = mutableListOf(
        CpSnippet(
            "Fast Power (modPow)",
            """long long modPow(long long a, long long e, long long mod) {
    long long r = 1 % mod;
    a %= mod;
    while (e > 0) {
        if (e & 1) r = (__int128)r * a % mod;
        a = (__int128)a * a % mod;
        e >>= 1;
    }
    return r;
}"""
        ),
        CpSnippet(
            "DSU (Union-Find)",
            """struct DSU {
    vector<int> p, sz;
    DSU(int n) : p(n), sz(n, 1) { iota(p.begin(), p.end(), 0); }
    int find(int x) { return p[x] == x ? x : p[x] = find(p[x]); }
    bool unite(int a, int b) {
        a = find(a); b = find(b);
        if (a == b) return false;
        if (sz[a] < sz[b]) swap(a, b);
        p[b] = a; sz[a] += sz[b];
        return true;
    }
};"""
        ),
        CpSnippet(
            "Sieve of Eratosthenes",
            """vector<int> sieve(int n) {
    vector<bool> isPrime(n + 1, true);
    vector<int> primes;
    isPrime[0] = isPrime[1] = false;
    for (int i = 2; i <= n; ++i) {
        if (isPrime[i]) {
            primes.push_back(i);
            for (long long j = 1LL * i * i; j <= n; j += i) isPrime[j] = false;
        }
    }
    return primes;
}"""
        ),
        CpSnippet(
            "Dijkstra Shortest Path",
            """vector<long long> dijkstra(int src, const vector<vector<pair<int, long long>>>& adj) {
    const long long INF = (1LL << 60);
    vector<long long> dist(adj.size(), INF);
    priority_queue<pair<long long, int>, vector<pair<long long, int>>, greater<>> pq;
    dist[src] = 0;
    pq.push({0, src});
    while (!pq.empty()) {
        auto [d, u] = pq.top(); pq.pop();
        if (d != dist[u]) continue;
        for (auto [v, w] : adj[u]) {
            if (d + w < dist[v]) {
                dist[v] = d + w;
                pq.push({dist[v], v});
            }
        }
    }
    return dist;
}"""
        ),
        CpSnippet(
            "Segment Tree (Sum)",
            """struct SegTree {
    int n;
    vector<long long> t;
    SegTree(int n) : n(n), t(4 * n, 0) {}
    void update(int v, int tl, int tr, int pos, long long val) {
        if (tl == tr) { t[v] = val; return; }
        int tm = (tl + tr) / 2;
        if (pos <= tm) update(2 * v, tl, tm, pos, val);
        else update(2 * v + 1, tm + 1, tr, pos, val);
        t[v] = t[2 * v] + t[2 * v + 1];
    }
    long long query(int v, int tl, int tr, int l, int r) {
        if (l > r) return 0;
        if (l == tl && r == tr) return t[v];
        int tm = (tl + tr) / 2;
        return query(2 * v, tl, tm, l, min(r, tm)) +
               query(2 * v + 1, tm + 1, tr, max(l, tm + 1), r);
    }
};"""
        ),
        CpSnippet(
            "Binary Search on Answer",
            """long long lo = 0, hi = 1e18;
while (lo < hi) {
    long long mid = lo + (hi - lo) / 2;
    if (check(mid)) hi = mid;
    else lo = mid + 1;
}"""
        )
    )

    fun loadSnippets(projectRoot: Path): MutableList<CpSnippet> {
        val file = snippetsFile(projectRoot)
        if (Files.isRegularFile(file)) {
            runCatching {
                val json = Files.readString(file, StandardCharsets.UTF_8)
                val parsed = gson.fromJson(json, SnippetLibrary::class.java)
                if (parsed?.snippets != null && parsed.snippets.isNotEmpty()) {
                    return parsed.snippets.filter { it.name != null && it.code != null }.toMutableList()
                }
            }
        }
        return defaultSnippets()
    }

    fun saveSnippets(projectRoot: Path, snippets: List<CpSnippet>) {
        val dir = cphDir(projectRoot)
        Files.createDirectories(dir)
        Files.writeString(snippetsFile(projectRoot), gson.toJson(SnippetLibrary(snippets.toMutableList())), StandardCharsets.UTF_8)
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
        if (p.note == null) p.note = ""
        if (p.detectedFloatTolerance.isNaN() || p.detectedFloatTolerance < 0.0) p.detectedFloatTolerance = 0.0
        if (p.tests == null) p.tests = mutableListOf()
        if (p.timeLimit <= 0L) p.timeLimit = 2000L
        if (p.memoryLimit <= 0) p.memoryLimit = 256
        return p
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeSettings(s: CphSettings, rawJson: String): CphSettings {
        val defaults = CphSettings()
        if (s.compilerPath == null) s.compilerPath = defaults.compilerPath
        if (s.compilerArgs == null) s.compilerArgs = defaults.compilerArgs
        if (s.cfLanguageOverride == null) s.cfLanguageOverride = defaults.cfLanguageOverride
        if (s.cfHandle == null) s.cfHandle = defaults.cfHandle
        if (s.cfApiKey == null) s.cfApiKey = defaults.cfApiKey
        if (s.cfApiSecret == null) s.cfApiSecret = defaults.cfApiSecret
        if (s.organizationMode == null) s.organizationMode = if (s.organizeByContest) "hierarchy" else "flat"
        if (s.fileNameStyle == null) s.fileNameStyle = defaults.fileNameStyle
        if (s.pythonPath == null) s.pythonPath = defaults.pythonPath
        if (s.cppTemplate == null) s.cppTemplate = defaults.cppTemplate
        if (s.floatToleranceMode == null || s.floatToleranceMode !in setOf("auto", "exact", "custom")) s.floatToleranceMode = "auto"
        if (s.floatTolerance.isNaN() || s.floatTolerance < 0.0) s.floatTolerance = 0.0
        if (s.defaultTimeLimitMs <= 0L) s.defaultTimeLimitMs = defaults.defaultTimeLimitMs
        if (s.companionPort <= 0) s.companionPort = defaults.companionPort
        if (!rawJson.contains("autoSyncCMake")) s.autoSyncCMake = true
        if (!rawJson.contains("ignoreTrailingSpaces")) s.ignoreTrailingSpaces = true
        if (!rawJson.contains("setOnlineJudge")) s.setOnlineJudge = true
        return s
    }
}
