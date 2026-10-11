package id.studyflow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.studyflow.data.InMemoryDatabases
import id.studyflow.data.auth.MemorySessionStore
import id.studyflow.data.auth.SecureSessionStore
import id.studyflow.data.local.DatabaseFactory
import id.studyflow.data.remote.ServerConfig
import id.studyflow.data.remote.studyFlowDefaults
import id.studyflow.data.repository.CourseRepository
import id.studyflow.data.repository.NoteRepository
import id.studyflow.data.repository.SettingsRepository
import id.studyflow.data.repository.TaskInput
import id.studyflow.data.repository.TaskRepository
import id.studyflow.di.dataModule
import id.studyflow.di.viewModelModule
import id.studyflow.domain.TaskStatus
import id.studyflow.domain.ThemeMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Clock

/**
 * Menyimpan screenshot layar utama untuk dokumentasi UI/UX (sebelum/sesudah).
 * Hanya berjalan jika STUDYFLOW_SCREENSHOT_DIR diset, mis.:
 * `$env:STUDYFLOW_SCREENSHOT_DIR="docs/ui-ux/screenshots/before"; .\gradlew :shared:jvmTest --tests "*ScreenshotCapture*"`
 */
@OptIn(ExperimentalTestApi::class)
@Suppress("DEPRECATION") // Sama seperti AppFlowTest: interaksi navigation harus di EDT.
class ScreenshotCapture {
    private val outputDir: File? = System.getenv("STUDYFLOW_SCREENSHOT_DIR")?.let(::File)

    private class Layout(val name: String, val width: Int, val height: Int)

    private val phone = Layout("phone", 412, 892)
    private val desktop = Layout("desktop", 1280, 800)

    private val lifecycleOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private fun startApp(databases: InMemoryDatabases) {
        startKoin {
            modules(
                module {
                    single<DatabaseFactory> { databases }
                    single<SecureSessionStore> { MemorySessionStore() }
                    single { HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }) { studyFlowDefaults() } }
                    // Seperti build rilis (server terkonfigurasi); jaringan tetap palsu.
                    single { ServerConfig("https://contoh.invalid", "kunci-publik", "https://api.contoh.invalid") }
                },
                dataModule,
                viewModelModule,
            )
        }
    }

    private fun seed(theme: ThemeMode) = runBlocking {
        val koin = GlobalContext.get()
        koin.get<SettingsRepository>().setThemeMode(theme)
        val courses = koin.get<CourseRepository>()
        val notes = koin.get<NoteRepository>()
        val tasks = koin.get<TaskRepository>()
        val now = Clock.System.now().toEpochMilliseconds()
        val hour = 3_600_000L
        val zone = "Asia/Jakarta"

        val crypto = courses.create("Kriptografi", "Ganjil 2026")
        val database = courses.create("Basis Data", "Ganjil 2026")
        courses.create("Jaringan Komputer", null)

        notes.create(
            crypto,
            "RSA dan faktorisasi prima",
            "RSA adalah algoritma kunci publik yang memakai dua bilangan prima besar p dan q.\n\n" +
                "1. Hitung n = p × q dan φ(n) = (p − 1)(q − 1).\n2. Pilih e yang relatif prima terhadap φ(n).\n" +
                "3. Hitung d sebagai invers e modulo φ(n).\n\nKeamanannya bergantung pada sulitnya memfaktorkan n.",
        )
        notes.create(crypto, "AES: struktur ronde", "SubBytes, ShiftRows, MixColumns, AddRoundKey.")
        notes.create(database, "Normalisasi 1NF–3NF", "Menghilangkan redundansi dan anomali pembaruan.")

        tasks.create(crypto, TaskInput("Laporan praktikum 3", "Analisis waktu enkripsi.", now - 20 * hour, zone))
        tasks.create(crypto, TaskInput("Kuis RSA", "", now + 3 * hour, zone))
        tasks.create(database, TaskInput("Desain ERD perpustakaan", "Minimal 6 entitas.", now + 40 * hour, zone))
        tasks.create(crypto, TaskInput("Tugas besar enkripsi berkas", "", now + 9 * 24 * hour, zone))
        val done = tasks.create(database, TaskInput("Resume bab 2", "", now - 48 * hour, zone))
        tasks.setStatus(done, TaskStatus.DONE)
    }

    private fun ComposeUiTest.show(layout: Layout) = setContent {
        CompositionLocalProvider(
            LocalLifecycleOwner provides lifecycleOwner,
            LocalDensity provides Density(2f),
        ) {
            Box(Modifier.requiredSize(layout.width.dp, layout.height.dp)) { App() }
        }
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
    }

    private fun ComposeUiTest.ui(action: () -> Unit) {
        runOnUiThread(action)
        settle()
    }

    private fun ComposeUiTest.shot(name: String) {
        val dir = outputDir ?: return
        dir.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    private fun ComposeUiTest.openTopLevel(label: String) = ui { onNode(hasText(label) and isSelectable()).performClick() }

    private fun ComposeUiTest.click(text: String) = ui { onAllNodes(hasText(text))[0].performClick() }

    private fun capture(layout: Layout, theme: ThemeMode, full: Boolean) {
        val databases = InMemoryDatabases()
        startApp(databases)
        try {
            seed(theme)
            runDesktopComposeUiTest(layout.width * 2, layout.height * 2) {
                val prefix = "${layout.name}-${theme.name.lowercase()}"
                show(layout)
                settle()
                shot("$prefix-01-beranda")
                openTopLevel("Mata Kuliah")
                shot("$prefix-02-mata-kuliah")
                click("Kriptografi")
                shot("$prefix-03-detail-catatan")
                if (full) {
                    ui { onNode(hasText("Tugas") and isSelectable()).performClick() }
                    shot("$prefix-04-detail-tugas")
                    ui { onNode(hasText("Materi") and isSelectable()).performClick() }
                    shot("$prefix-05-detail-materi")
                    ui { onNode(hasText("Belajar") and isSelectable()).performClick() }
                    shot("$prefix-06-detail-belajar")
                    ui { onNode(hasText("Catatan") and isSelectable()).performClick() }
                    click("RSA dan faktorisasi prima")
                    shot("$prefix-07-editor-catatan")
                    ui { onAllNodes(hasContentDescription("Kembali", substring = true))[0].performClick() }
                    ui { onNode(hasText("Tugas") and isSelectable()).performClick() }
                    click("Kuis RSA")
                    shot("$prefix-08-editor-tugas")
                    ui { onAllNodes(hasContentDescription("Kembali", substring = true))[0].performClick() }
                    ui { onAllNodes(hasContentDescription("Kembali", substring = true))[0].performClick() }
                }
                openTopLevel("Progres")
                shot("$prefix-09-progres")
                openTopLevel("Pengaturan")
                shot("$prefix-10-pengaturan")
                if (full) {
                    openTopLevel("Beranda")
                    click("Mode lokal")
                    shot("$prefix-11-akun")
                }
                mainClock.advanceTimeBy(2_000)
                waitForIdle()
            }
        } finally {
            stopKoin()
            databases.closeAll()
        }
    }

    private fun captureEmpty(layout: Layout) {
        val databases = InMemoryDatabases()
        startApp(databases)
        try {
            runDesktopComposeUiTest(layout.width * 2, layout.height * 2) {
                show(layout)
                settle()
                shot("${layout.name}-light-00-beranda-kosong")
                openTopLevel("Mata Kuliah")
                shot("${layout.name}-light-00-mata-kuliah-kosong")
                mainClock.advanceTimeBy(2_000)
                waitForIdle()
            }
        } finally {
            stopKoin()
            databases.closeAll()
        }
    }

    @Test
    fun captureScreens() {
        if (outputDir == null) return
        for (layout in listOf(phone, desktop)) {
            capture(layout, ThemeMode.LIGHT, full = true)
            capture(layout, ThemeMode.DARK, full = false)
            captureEmpty(layout)
        }
    }
}
