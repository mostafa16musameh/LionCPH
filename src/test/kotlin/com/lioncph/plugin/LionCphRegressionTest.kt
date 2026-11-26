package com.lioncph.plugin

import com.lioncph.plugin.models.CphSettings
import com.lioncph.plugin.models.CphStorage
import com.lioncph.plugin.models.ProblemData
import com.lioncph.plugin.models.TestCase
import com.lioncph.plugin.services.CMakeSyncService
import com.lioncph.plugin.services.CodeforcesSubmitService
import com.lioncph.plugin.services.CodeforcesVerdictService
import com.lioncph.plugin.services.CompanionServerService
import com.lioncph.plugin.services.FolderOrganizerService
import com.lioncph.plugin.services.JudgeService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LionCphRegressionTest {

    // =========================================================================
    // ISSUE 1: Secret Persistence, Multi-Window Safety & Read-Only HOME
    // =========================================================================
    @Test
    fun testSecretPersistenceAndMultiWindowIsolation() {
        val prevHome = System.getProperty("user.home")
        val tmp = Files.createTempDirectory("lioncph_test_secret_")
        try {
            val fakeHome = tmp.resolve("home")
            val projA = tmp.resolve("projA")
            val projB = tmp.resolve("projB")
            Files.createDirectories(fakeHome)
            Files.createDirectories(projA)
            Files.createDirectories(projB)
            System.setProperty("user.home", fakeHome.toAbsolutePath().toString())

            // Save secret from Project A (updateSecret = true)
            val sA = CphSettings(cfHandle = "userA", cfApiKey = "keyA", cfApiSecret = "SECRET_123")
            assertTrue(CphStorage.saveSettings(projA, sA, updateSecret = true))
            val projAJson = Files.readString(CphStorage.settingsFile(projA), StandardCharsets.UTF_8)
            assertFalse(projAJson.contains("SECRET_123"), "Secret must NEVER be written into project settings")

            // Project B saves unrelated setting with stale empty secret (updateSecret = false)
            val sB = CphSettings(setOnlineJudge = false, cfApiSecret = "")
            assertTrue(CphStorage.saveSettings(projB, sB, updateSecret = false))

            // Reloading Project A must still retrieve SECRET_123
            val reloadedA = CphStorage.loadSettings(projA)
            assertEquals("SECRET_123", reloadedA.cfApiSecret)

            // Intentional secret deletion (updateSecret = true with empty string)
            assertTrue(CphStorage.saveSettings(projA, reloadedA.copy(cfApiSecret = ""), updateSecret = true))
            assertEquals("", CphStorage.loadSettings(projA).cfApiSecret)
        } finally {
            System.setProperty("user.home", prevHome)
            tmp.toFile().deleteRecursively()
        }
    }

    // =========================================================================
    // ISSUE 2: Live Verdict Cross-Project Isolation
    // =========================================================================
    @Test
    fun testLiveVerdictPerProjectSessionIsolation() {
        val refA = CodeforcesSubmitService.parseCodeforcesUrl("https://codeforces.com/contest/2283/problem/A")!!
        val refB = CodeforcesSubmitService.parseCodeforcesUrl("https://codeforces.com/contest/2283/problem/B")!!
        try {
            CodeforcesVerdictService.startPolling("handleA", refA, "", "", 1L, sessionKey = "proj_A") {}
            CodeforcesVerdictService.startPolling("handleB", refB, "", "", 1L, sessionKey = "proj_B") {}

            assertTrue(CodeforcesVerdictService.isPollingActive("proj_A"))
            assertTrue(CodeforcesVerdictService.isPollingActive("proj_B"))

            // Stopping Project A must NOT stop Project B
            CodeforcesVerdictService.stopPolling("proj_A")
            assertFalse(CodeforcesVerdictService.isPollingActive("proj_A"))
            assertTrue(CodeforcesVerdictService.isPollingActive("proj_B"))
        } finally {
            CodeforcesVerdictService.stopPolling("proj_A")
            CodeforcesVerdictService.stopPolling("proj_B")
            assertFalse(CodeforcesVerdictService.isPollingActive("proj_B"))
        }
    }

    // =========================================================================
    // ISSUE 3: Stop Must Cancel Compilation & Custom Checker Immediately
    // =========================================================================
    @Test
    fun testCancelledCompileAndCheckerRespectCancelFlag() {
        val tmp = Files.createTempDirectory("lioncph_test_cancel_")
        try {
            val cpp = tmp.resolve("A.cpp")
            Files.writeString(cpp, "int main(){return 0;}\n", StandardCharsets.UTF_8)
            val cancelFlag = AtomicBoolean(true)
            val activeProcs = ConcurrentHashMap.newKeySet<Process>()

            val compileOutcome = JudgeService.compile(tmp, cpp, CphSettings(), cancelFlag, activeProcs)
            assertFalse(compileOutcome.success)
            assertTrue(compileOutcome.stderr.contains("cancelled", ignoreCase = true))
            assertTrue(activeProcs.isEmpty())

            val runOutcome = JudgeService.runTestCase(
                cpp,
                TestCase(input = "", output = ""),
                ProblemData(useCustomChecker = true, customCheckerPath = tmp.resolve("c.py").toString()),
                CphSettings(),
                cancelFlag,
                activeProcs
            )
            assertEquals("IDLE", runOutcome.status)
            assertTrue(activeProcs.isEmpty())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // =========================================================================
    // ISSUE 4: Compile Cache Content-Hash Reliability & Scientific Tolerance
    // =========================================================================
    @Test
    fun testFloatToleranceScientificAndOutputCompare() {
        val tol = JudgeService.detectToleranceFromTests(listOf(TestCase(output = "1.5e-6")))
        assertTrue(tol > 0.0 && tol <= 1e-6)
        assertTrue(JudgeService.compareOutputs("1.500000", "1.5000001", ignoreTrailingSpaces = true, floatTolerance = 1e-6))
        assertFalse(JudgeService.compareOutputs("YES", "YES ", ignoreTrailingSpaces = false, floatTolerance = 0.0))
    }

    // =========================================================================
    // ISSUE 5: Companion Server Host DNS-Rebinding & Origin CSRF Protection
    // =========================================================================
    @Test
    fun testCompanionServerSecurityAndCompatibility() {
        val tmp = Files.createTempDirectory("lioncph_test_comp_")
        val testPort = 39421
        try {
            CompanionServerService.start(testPort, { tmp }) { _, _, _ -> }
            assertTrue(testPort in CompanionServerService.activePorts())

            val client = HttpClient.newHttpClient()
            // 1. Adversarial Origin on POST / -> MUST return 403
            val evilPost = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$testPort/"))
                    .header("Origin", "http://localhost.evil.com")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, evilPost.statusCode())

            // 2. Adversarial Origin on /getSubmit -> MUST return 403
            val evilGet = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$testPort/getSubmit"))
                    .header("Origin", "https://evil.example")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(403, evilGet.statusCode())

            // 3. Legitimate extension Origin on /getSubmit -> 404 (no pending submit) with echoed CORS
            val okExt = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$testPort/getSubmit"))
                    .header("Origin", "chrome-extension://testext")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, okExt.statusCode())
            assertEquals("chrome-extension://testext", okExt.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
        } finally {
            CompanionServerService.stop()
            tmp.toFile().deleteRecursively()
        }
    }

    // =========================================================================
    // ISSUE 6: Folder Organizer Transaction Safety & Problem File Isolation
    // =========================================================================
    @Test
    fun testProblemIsolationAndNonAsciiContainment() {
        val tmp = Files.createTempDirectory("lioncph_test_org_")
        try {
            // Two A.cpp in different sheets must NEVER collide on delete
            val s1 = tmp.resolve("Groups/Sheet_1/A.cpp")
            val s2 = tmp.resolve("Groups/Sheet_2/A.cpp")
            Files.createDirectories(s1.parent)
            Files.createDirectories(s2.parent)
            Files.writeString(s1, "int main(){return 1;}")
            Files.writeString(s2, "int main(){return 2;}")
            CphStorage.saveProblem(tmp, s1, ProblemData(name = "S1 A", tests = mutableListOf(TestCase(input = "1", output = "1"))))
            CphStorage.saveProblem(tmp, s2, ProblemData(name = "S2 A", tests = mutableListOf(TestCase(input = "2", output = "2"))))

            CphStorage.deleteProblem(tmp, s2)
            assertNull(CphStorage.loadProblemOrNull(tmp, s2))
            val loadedS1 = CphStorage.loadProblemOrNull(tmp, s1)
            assertNotNull(loadedS1)
            assertEquals("S1 A", loadedS1.name)

            // Non-ASCII / Arabic group names must stay inside projectRoot (never escape to /)
            val dest = FolderOrganizerService.resolveDestination(
                tmp, "مسألة", "منصة - مسابقة", "https://example.com/p/1",
                CphSettings(organizationMode = "hierarchy", organizeByContest = true)
            )
            assertTrue(dest.directory.toAbsolutePath().normalize().startsWith(tmp.toAbsolutePath().normalize()))

            // CMake path-boundary registration (A.cpp must NOT be skipped because of Groups/Sheet_1/A.cpp)
            Files.writeString(tmp.resolve("CMakeLists.txt"), "cmake_minimum_required(VERSION 3.20)\nproject(CP)\n")
            CMakeSyncService.registerPluginFile(tmp, "Sheet_1_A", "Groups/Sheet_1/A.cpp")
            CMakeSyncService.registerPluginFile(tmp, "A", "A.cpp")
            val targets = Files.readString(tmp.resolve(".cph/lioncph_targets.cmake"), StandardCharsets.UTF_8)
            assertTrue(targets.contains("\${CMAKE_CURRENT_SOURCE_DIR}/Groups/Sheet_1/A.cpp"))
            assertTrue(targets.contains("\${CMAKE_CURRENT_SOURCE_DIR}/A.cpp"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
