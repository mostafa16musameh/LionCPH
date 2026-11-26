package com.lioncph.plugin.models

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    var organizeByContest: Boolean = true,
    var organizationMode: String = "hierarchy", // "flat", "single", "hierarchy"
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
    private val secretFileLock = Any()

    private fun writeFileAtomically(target: Path, content: String, restrictPosixPerms: Boolean = false) {
        val parent = target.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = (parent ?: Path.of(".")).resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8)
            if (restrictPosixPerms) {
                runCatching {
                    val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                    Files.setPosixFilePermissions(tmp, perms)
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            if (restrictPosixPerms) {
                runCatching {
                    val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                    Files.setPosixFilePermissions(target, perms)
                }
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

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
        // Safe fallback: only match another .prob with same filename if its recorded srcPath matches this exact file
        val normalizedTarget = cppFile.toAbsolutePath().normalize()
        val dir = cphDir(projectRoot)
        if (Files.isDirectory(dir)) {
            val prefix = ".${cppFile.fileName}_"
            runCatching {
                Files.list(dir).use { stream ->
                    val candidates = stream.filter {
                        val n = it.fileName.toString()
                        n.startsWith(prefix) && n.endsWith(".prob")
                    }.toList()
                    for (cand in candidates) {
                        val parsed = runCatching {
                            gson.fromJson(Files.readString(cand, StandardCharsets.UTF_8), ProblemData::class.java)
                        }.getOrNull() ?: continue
                        val safeParsed = sanitizeProblem(parsed)
                        val candSrc = safeParsed.srcPath.takeIf { it.isNotBlank() }?.let {
                            runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull()
                        }
                        if (candSrc == normalizedTarget) {
                            return safeParsed
                        }
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
        writeFileAtomically(probFile, gson.toJson(problem), restrictPosixPerms = false)
    }

    fun deleteProblem(projectRoot: Path, cppFile: Path) {
        val dir = cphDir(projectRoot)
        runCatching { Files.deleteIfExists(problemFileFor(projectRoot, cppFile)) }
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
                if (parsed?.snippets != null) {
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

    private fun userSecretsFile(): Path {
        val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
        return Path.of(home, ".lioncph", "secrets.json")
    }

    private data class UserSecrets(var cfApiSecret: String = "")

    private fun loadUserSecret(): String = synchronized(secretFileLock) {
        val file = userSecretsFile()
        if (!Files.isRegularFile(file)) return ""
        return runCatching {
            val parsed = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), UserSecrets::class.java)
            parsed?.cfApiSecret.orEmpty()
        }.getOrDefault("")
    }

    private fun saveUserSecret(secret: String): Boolean = synchronized(secretFileLock) {
        return runCatching {
            val file = userSecretsFile()
            writeFileAtomically(file, gson.toJson(UserSecrets(secret)), restrictPosixPerms = true)
            true
        }.getOrDefault(false)
    }

    fun loadSettings(projectRoot: Path): CphSettings {
        val file = settingsFile(projectRoot)
        if (Files.isRegularFile(file)) {
            runCatching {
                val json = Files.readString(file, StandardCharsets.UTF_8)
                val parsed = gson.fromJson(json, CphSettings::class.java)
                if (parsed != null) {
                    val sanitized = sanitizeSettings(parsed, json)
                    if (sanitized.cfApiSecret.isNotBlank()) {
                        // Auto-migrate legacy plaintext secret out of project repo into ~/.lioncph/secrets.json
                        // Only strip from project settings after the new secret is confirmed persisted.
                        if (saveUserSecret(sanitized.cfApiSecret)) {
                            saveSettings(projectRoot, sanitized, updateSecret = false)
                        }
                    } else {
                        sanitized.cfApiSecret = loadUserSecret()
                    }
                    return sanitized
                }
            }
        }
        return CphSettings(cfApiSecret = loadUserSecret())
    }

    /**
     * Persists project settings (always stripping cfApiSecret from project-local JSON).
     * When [updateSecret] is true, atomically updates ~/.lioncph/secrets.json and returns false if secret persistence fails
     * (never silently discarding a write failure and never corrupting a previously stored secret).
     */
    fun saveSettings(projectRoot: Path, settings: CphSettings, updateSecret: Boolean = false): Boolean {
        var secretOk = true
        if (updateSecret) {
            secretOk = saveUserSecret(settings.cfApiSecret)
        }
        val projectOk = runCatching {
            val sanitizedForProject = settings.copy(cfApiSecret = "")
            writeFileAtomically(settingsFile(projectRoot), gson.toJson(sanitizedForProject), restrictPosixPerms = false)
            true
        }.getOrDefault(false)
        return secretOk && projectOk
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
        if (s.companionPort !in 1..65535) s.companionPort = defaults.companionPort
        if (!rawJson.contains("autoSyncCMake")) s.autoSyncCMake = true
        if (!rawJson.contains("ignoreTrailingSpaces")) s.ignoreTrailingSpaces = true
        if (!rawJson.contains("setOnlineJudge")) s.setOnlineJudge = true
        return s
    }
}
