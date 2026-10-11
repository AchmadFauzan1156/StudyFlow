package id.studyflow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.studyflow.config.AppConfig
import id.studyflow.data.InMemoryDatabases
import id.studyflow.data.auth.MemorySessionStore
import id.studyflow.data.auth.SecureSessionStore
import id.studyflow.data.local.DatabaseFactory
import id.studyflow.data.remote.ServerConfig
import id.studyflow.data.remote.studyFlowDefaults
import id.studyflow.di.dataModule
import id.studyflow.di.viewModelModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Alur utama aplikasi dalam mode lokal (tanpa server), dengan database in-memory. */
@OptIn(ExperimentalTestApi::class)
@Suppress("DEPRECATION") // runComposeUiTest v2 mengirim input dari thread tes; navigation mewajibkan main thread.
class AppFlowTest {
    private val databases = InMemoryDatabases()

    @BeforeTest
    fun start() = startWith(ServerConfig("", "", ""))

    /** Build rilis: server terkonfigurasi tetapi belum masuk; jaringan tetap palsu. */
    private fun useConfiguredServer() {
        stopKoin()
        startWith(ServerConfig("https://contoh.invalid", "kunci-publik", "https://api.contoh.invalid"))
    }

    private fun startWith(serverConfig: ServerConfig) {
        startKoin {
            modules(
                module {
                    single<DatabaseFactory> { databases }
                    single<SecureSessionStore> { MemorySessionStore() }
                    single { HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }) { studyFlowDefaults() } }
                    single { serverConfig }
                },
                dataModule,
                viewModelModule,
            )
        }
    }

    @AfterTest
    fun stop() {
        stopKoin()
        databases.closeAll()
    }

    /**
     * Lifecycle milik tes yang tetap RESUMED. Saat scene uji ditutup, navigation 2.9 mencoba memindahkan entri yang
     * masih INITIALIZED ke DESTROYED dan melempar IllegalStateException; tes tidak menguji penutupan jendela.
     */
    private val lifecycleOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private fun ComposeUiTest.setApp(width: () -> Int) = setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
            Box(Modifier.requiredSize(width().dp, 740.dp)) { App() }
        }
    }

    /** Interaksi dijalankan di EDT: navigation hanya boleh mengubah lifecycle entri dari main thread. */
    private fun ComposeUiTest.ui(action: () -> Unit) {
        runOnUiThread(action)
        waitForIdle()
    }

    /** Tombol tambah (FAB) yang teksnya sama dengan nama tab; tab bersifat selectable, FAB tidak. */
    private fun ComposeUiTest.fab(text: String) = onNode(hasText(text) and !hasAnyAncestor(isSelectable()), useUnmergedTree = true)

    private fun ComposeUiTest.showApp() = setApp { 1000 }

    /** Tunggu animasi transisi navigasi selesai agar scene uji bisa ditutup dengan bersih. */
    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    private fun ComposeUiTest.waitForText(text: String) = waitUntil(timeoutMillis = 5_000) {
        onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun sidebarFrom600dpAndBottomBarBelow() = runComposeUiTest {
        var width by mutableIntStateOf(600)
        setApp { width }
        onNodeWithTag("sidebar").assertIsDisplayed()
        width = 599
        waitForIdle()
        onNodeWithTag("bottomBar").assertIsDisplayed()
    }

    @Test
    fun addCourseNoteAndTaskThenMarkTaskDone() = runComposeUiTest {
        showApp()
        ui { onNode(hasText("Mata Kuliah") and isSelectable()).performClick() }
        ui { onNode(hasText("Tambah mata kuliah")).performClick() }
        ui { onAllNodes(hasSetTextAction())[0].performTextInput("Kriptografi") }
        ui { onNode(hasText("Simpan")).performClick() }
        waitForText("Kriptografi")

        ui { onAllNodes(hasText("Kriptografi"))[0].performClick() }
        ui { fab("Catatan").performClick() }
        ui { onAllNodes(hasSetTextAction())[0].performTextInput("RSA") }
        ui { onNode(hasText("Simpan")).performClick() }
        waitForText("RSA")

        ui { onNode(hasText("Tugas") and isSelectable()).performClick() }
        ui { fab("Tugas").performClick() }
        ui { onAllNodes(hasSetTextAction())[0].performTextInput("Laporan") }
        ui { onNode(hasText("Simpan")).performClick() }
        waitForText("Laporan")

        ui { onNode(hasText("Beranda") and isSelectable()).performClick() }
        waitForText("Laporan")
        ui { onAllNodes(isToggleable())[0].performClick() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Laporan")).fetchSemanticsNodes().isEmpty() }
        settle()
    }

    @Test
    fun settingsShowsBuildVersion() = runComposeUiTest {
        showApp()
        ui { onNode(hasText("Pengaturan") and isSelectable()).performClick() }
        waitForText(AppConfig.appVersion)
        settle()
    }

    @Test
    fun ctrlNumberSwitchesTopLevelScreen() = runComposeUiTest {
        showApp()
        waitForIdle()
        ui { onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Four) } } }
        // Label menu dan judul layar Pengaturan.
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Pengaturan")).fetchSemanticsNodes().size >= 2 }
        ui { onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Two) } } }
        waitForText("Tambah mata kuliah")
        settle()
    }

    private fun ComposeUiTest.openStudyTabOfNewCourse() {
        ui { onNode(hasText("Mata Kuliah") and isSelectable()).performClick() }
        ui { onNode(hasText("Tambah mata kuliah")).performClick() }
        ui { onAllNodes(hasSetTextAction())[0].performTextInput("Kriptografi") }
        ui { onNode(hasText("Simpan")).performClick() }
        waitForText("Kriptografi")
        ui { onAllNodes(hasText("Kriptografi"))[0].performClick() }
        ui { onNode(hasText("Belajar") and isSelectable()).performClick() }
    }

    @Test
    fun accountGatedTabOffersSignInWhenServerConfigured() = runComposeUiTest {
        useConfiguredServer()
        showApp()
        openStudyTabOfNewCourse()
        ui { onNode(hasText("Masuk atau daftar")).performClick() }
        waitForText("Akun StudyFlow")
        settle()
    }

    @Test
    fun accountGatedTabHasNoSignInWithoutServer() = runComposeUiTest {
        showApp()
        openStudyTabOfNewCourse()
        waitForText("Belajar dengan AI")
        onAllNodes(hasText("Masuk atau daftar")).assertCountEquals(0)
        settle()
    }

    @Test
    fun savingNoteShowsConfirmation() = runComposeUiTest {
        showApp()
        ui { onNode(hasText("Mata Kuliah") and isSelectable()).performClick() }
        ui { onNode(hasText("Tambah mata kuliah")).performClick() }
        ui { onAllNodes(hasSetTextAction())[0].performTextInput("Kriptografi") }
        ui { onNode(hasText("Simpan")).performClick() }
        waitForText("Mata kuliah disimpan")
        ui { onAllNodes(hasText("Kriptografi"))[0].performClick() }
        ui { fab("Catatan").performClick() }
        ui { onAllNodes(hasSetTextAction())[0].performTextInput("RSA") }
        ui { onNode(hasText("Simpan")).performClick() }
        waitForText("Catatan disimpan")
        settle()
    }
}
