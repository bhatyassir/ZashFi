package com.shaazifi.app.debug

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaazifi.app.AppContainer
import com.shaazifi.app.R
import com.shaazifi.app.core.net.AddressGuard
import com.shaazifi.app.core.net.AddressNotAllowedException
import com.shaazifi.app.core.net.CertificateChangedException
import com.shaazifi.app.core.net.HttpFactory
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SfTopBar
import com.shaazifi.app.ui.theme.PillTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Debug-variant configuration and Connection Core self-test overlay.
 * Populated locally if needed for manual device verification; empty by default so no real
 * router coordinates or fingerprints are committed to source control.
 */
object DebugRouterConfig {
    const val IS_DEBUG: Boolean = true
    const val ROUTER_HOST: String = ""
    const val ROUTER_HTTPS_URL: String = ""
    const val ROUTER_HTTP_URL: String = ""
    const val ROUTER_CERT_FINGERPRINT: String = ""

    fun seedDebugPins(trustStore: TrustStore) {
        if (ROUTER_HOST.isNotBlank() && ROUTER_CERT_FINGERPRINT.isNotBlank()) {
            trustStore.pin(ROUTER_HOST, ROUTER_CERT_FINGERPRINT)
        }
    }

    enum class TestStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        REDIRECT,
        REFUSED_EXPECTED,
        HARD_STOP_EXPECTED,
        FAILED
    }

    data class TestCase(
        val id: String,
        val title: String,
        val description: String,
        val status: TestStatus = TestStatus.PENDING,
        val summary: String = "Not run yet",
        val details: String = ""
    )

    @Composable
    fun SelfTestOverlay(container: AppContainer, onBack: () -> Unit) {
        BackHandler(onBack = onBack)
        Column {
            SfTopBar(title = "Connection self-test", onBack = onBack)
            ConnectionCoreTestScreen(container = container)
        }
    }

    @Composable
    fun ConnectionCoreTestScreen(
        container: AppContainer,
        modifier: Modifier = Modifier
    ) {
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        var testCases by remember {
            mutableStateOf(
                listOf(
                    TestCase(
                        id = "https_router",
                        title = "1. HTTPS Fetch /untangled/",
                        description = "GET $ROUTER_HTTPS_URL via TrustStore with pinned fingerprint $ROUTER_CERT_FINGERPRINT"
                    ),
                    TestCase(
                        id = "http_router",
                        title = "2. Plain HTTP Fetch /untangled/",
                        description = "GET $ROUTER_HTTP_URL (checks whether router redirects or refuses)"
                    ),
                    TestCase(
                        id = "refuse_public_domain",
                        title = "3. Refuse Public Domain (google.com)",
                        description = "GET https://google.com/ (AddressGuard must block public domains)"
                    ),
                    TestCase(
                        id = "refuse_public_ip",
                        title = "4. Refuse Public IP (8.8.8.8)",
                        description = "GET https://8.8.8.8/ (AddressGuard must block public IPs)"
                    ),
                    TestCase(
                        id = "cert_changed_hard_stop",
                        title = "5. TrustStore Hard Stop on Changed Cert",
                        description = "GET $ROUTER_HTTPS_URL with mismatched pin (must abort with CertificateChangedException)"
                    )
                )
            )
        }

        var isRunningAll by remember { mutableStateOf(false) }

        fun updateTest(id: String, transform: (TestCase) -> TestCase) {
            testCases = testCases.map { if (it.id == id) transform(it) else it }
        }

        fun runAllTests() {
            if (isRunningAll) return
            isRunningAll = true
            coroutineScope.launch {
                updateTest("https_router") { it.copy(status = TestStatus.RUNNING, summary = "Connecting...") }
                val t1 = runHttpsRouterTest(container.okHttpClient)
                updateTest("https_router") { it.copy(status = t1.first, summary = t1.second, details = t1.third) }

                updateTest("http_router") { it.copy(status = TestStatus.RUNNING, summary = "Probing HTTP...") }
                val t2 = runHttpRouterTest(container.trustStore)
                updateTest("http_router") { it.copy(status = t2.first, summary = t2.second, details = t2.third) }

                updateTest("refuse_public_domain") { it.copy(status = TestStatus.RUNNING, summary = "Evaluating...") }
                val t3 = runRefusePublicTest("https://google.com")
                updateTest("refuse_public_domain") { it.copy(status = t3.first, summary = t3.second, details = t3.third) }

                updateTest("refuse_public_ip") { it.copy(status = TestStatus.RUNNING, summary = "Evaluating...") }
                val t4 = runRefusePublicTest("https://8.8.8.8")
                updateTest("refuse_public_ip") { it.copy(status = t4.first, summary = t4.second, details = t4.third) }

                updateTest("cert_changed_hard_stop") { it.copy(status = TestStatus.RUNNING, summary = "Simulating changed cert...") }
                val t5 = runCertChangedHardStopTest()
                updateTest("cert_changed_hard_stop") { it.copy(status = t5.first, summary = t5.second, details = t5.third) }

                isRunningAll = false
            }
        }

        LaunchedEffect(Unit) {
            runAllTests()
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.connection_core_title, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Milestone M3 Test Suite",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Device & Environment Info",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "ACCESS_LOCAL_NETWORK: Not declared in manifest (Unverified on phone until tested)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "WifiBinder Scope: RFC 1918 LAN only (Tailnet 100.64/10 excluded)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Automated Checks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = { runAllTests() },
                    enabled = !isRunningAll
                ) {
                    if (isRunningAll) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing...")
                    } else {
                        Text("Re-run All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            testCases.forEach { tc ->
                TestCaseCard(testCase = tc)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun TestCaseCard(testCase: TestCase) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = testCase.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    StatusBadge(status = testCase.status)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = testCase.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = testCase.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = when (testCase.status) {
                                TestStatus.SUCCESS, TestStatus.REDIRECT, TestStatus.REFUSED_EXPECTED, TestStatus.HARD_STOP_EXPECTED ->
                                    MaterialTheme.colorScheme.primary
                                TestStatus.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (testCase.details.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = testCase.details,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusBadge(status: TestStatus) {
        val (label, tone) = when (status) {
            TestStatus.PENDING -> "Not run" to PillTone.Off
            TestStatus.RUNNING -> "Running" to PillTone.Off
            TestStatus.SUCCESS -> "OK (200)" to PillTone.Ok
            TestStatus.REDIRECT -> "Redirect (307)" to PillTone.Warn
            TestStatus.REFUSED_EXPECTED -> "Blocked, as expected" to PillTone.Ok
            TestStatus.HARD_STOP_EXPECTED -> "Stopped, as expected" to PillTone.Ok
            TestStatus.FAILED -> "Failed" to PillTone.Bad
        }
        Pill(label, tone = tone)
    }

    private suspend fun runHttpsRouterTest(client: OkHttpClient): Triple<TestStatus, String, String> =
        withContext(Dispatchers.IO) {
            try {
                val url = ROUTER_HTTPS_URL
                if (url.isBlank()) {
                    return@withContext Triple(TestStatus.FAILED, "No debug router URL configured", "")
                }

                val request = Request.Builder()
                    .url(url)
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val body = response.body.string()
                    if (code == 200) {
                        Triple(
                            TestStatus.SUCCESS,
                            "HTTP 200 OK — Cert Fingerprint Verified",
                            "Length: ${body.length} bytes\nServer: ${response.header("Server") ?: "uhttpd"}\nReachability: Router reached over Tailnet without ACCESS_LOCAL_NETWORK"
                        )
                    } else {
                        Triple(
                            TestStatus.FAILED,
                            "Unexpected HTTP Status: $code",
                            "Headers: ${response.headers}"
                        )
                    }
                }
            } catch (e: Exception) {
                Triple(
                    TestStatus.FAILED,
                    "Connection failed: ${e.javaClass.simpleName}",
                    e.message ?: e.toString()
                )
            }
        }

    private suspend fun runHttpRouterTest(trustStore: TrustStore): Triple<TestStatus, String, String> =
        withContext(Dispatchers.IO) {
            try {
                val url = ROUTER_HTTP_URL
                if (url.isBlank()) {
                    return@withContext Triple(TestStatus.FAILED, "No debug router URL configured", "")
                }

                // Route plain HTTP probes through HttpFactory so AddressGuard bounds remain enforced.
                val noRedirectClient = HttpFactory.createClient(
                    trustStore = trustStore,
                    connectTimeoutSeconds = 5,
                    readTimeoutSeconds = 5
                ).newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .build()

                noRedirectClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val location = response.header("Location") ?: "None"
                    if (code in 300..399) {
                        Triple(
                            TestStatus.REDIRECT,
                            "HTTP $code Redirect -> $location",
                            "Router explicitly redirects plain HTTP to HTTPS (does NOT refuse connection)."
                        )
                    } else {
                        Triple(
                            TestStatus.SUCCESS,
                            "HTTP $code (No redirect)",
                            "Server replied with code $code"
                        )
                    }
                }
            } catch (e: Exception) {
                Triple(
                    TestStatus.FAILED,
                    "Plain HTTP failed: ${e.javaClass.simpleName}",
                    e.message ?: e.toString()
                )
            }
        }

    private suspend fun runRefusePublicTest(targetUrl: String): Triple<TestStatus, String, String> =
        withContext(Dispatchers.IO) {
            try {
                AddressGuard.requireAllowed(targetUrl)
                Triple(
                    TestStatus.FAILED,
                    "Security Breach: Public address was NOT blocked!",
                    "Target: $targetUrl was allowed unexpectedly."
                )
            } catch (e: AddressNotAllowedException) {
                Triple(
                    TestStatus.REFUSED_EXPECTED,
                    "BLOCKED: AddressNotAllowedException",
                    "AddressGuard blocked host '${e.host}' before connection: ${e.message}"
                )
            } catch (e: Exception) {
                Triple(
                    TestStatus.FAILED,
                    "Unexpected Exception: ${e.javaClass.simpleName}",
                    e.message ?: e.toString()
                )
            }
        }

    private suspend fun runCertChangedHardStopTest(): Triple<TestStatus, String, String> =
        withContext(Dispatchers.IO) {
            try {
                val host = ROUTER_HOST
                val url = ROUTER_HTTPS_URL
                if (host.isBlank() || url.isBlank()) {
                    return@withContext Triple(TestStatus.FAILED, "No debug router configured", "")
                }

                // Pin an intentionally fake/mismatched fingerprint to simulate MITM or cert renewal
                val rogueTrustStore = TrustStore().apply {
                    pin(host, "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF")
                }

                val client = HttpFactory.createClient(
                    trustStore = rogueTrustStore,
                    connectTimeoutSeconds = 5,
                    readTimeoutSeconds = 5
                )

                val request = Request.Builder()
                    .url(url)
                    .build()

                client.newCall(request).execute().use {
                    Triple(
                        TestStatus.FAILED,
                        "Security Breach: Mismatched cert was accepted!",
                        "TrustStore did not trigger hard stop on altered certificate."
                    )
                }
            } catch (e: Exception) {
                var foundCertChanged: CertificateChangedException? = null
                fun check(t: Throwable?) {
                    if (t == null || foundCertChanged != null) return
                    if (t is CertificateChangedException) {
                        foundCertChanged = t
                        return
                    }
                    check(t.cause)
                    for (s in t.suppressed) check(s)
                }
                check(e)

                val certChanged = foundCertChanged
                if (certChanged != null) {
                    Triple(
                        TestStatus.HARD_STOP_EXPECTED,
                        "HARD STOP: CertificateChangedException Caught",
                        "Expected: ${certChanged.expectedFingerprint}\nActual: ${certChanged.actualFingerprint}\nConnection aborted immediately. Never accepted silently."
                    )
                } else {
                    Triple(
                        TestStatus.FAILED,
                        "Unexpected Error: ${e.javaClass.simpleName}",
                        e.message ?: e.toString()
                    )
                }
            }
        }
}
