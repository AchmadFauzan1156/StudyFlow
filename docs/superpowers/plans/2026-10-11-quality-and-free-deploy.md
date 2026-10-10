# Peningkatan Kualitas dan Deploy Gratis — Rencana Implementasi

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merapikan StudyFlow dengan lint, tes ViewModel, Compose UI test, dan refactor UI tanpa perubahan perilaku, memverifikasi backend dengan Playwright, men-deploy backend secara gratis (Render + Supabase + Gemini), lalu menghasilkan installer `.exe` dan APK release.

**Architecture:** Tooling dan tes dikerjakan lebih dulu sebagai jaring pengaman, baru kemudian refactor yang hanya memindahkan kode. Backend mendapat mode "worker tertanam" (thread di dalam proses API) agar muat di satu web service gratis. Installer dibangun dengan URL produksi yang tertanam lewat `generateAppConfig`.

**Tech Stack:** Kotlin 2.4.20, Compose Multiplatform 1.12.1, Room 2.8.5, Koin 4.2.2, Gradle 9.6, FastAPI, SQLAlchemy, Alembic, pytest, ruff, Docker, Render, Supabase, Gemini, Playwright MCP, Claude in Chrome.

**Spec:** `docs/superpowers/specs/2026-10-11-quality-and-free-deploy-design.md`

## Global Constraints

- Tidak ada fitur yang dihapus. Semua tes lama (`:shared:jvmTest`, `uv run pytest`) tetap lulus.
- Commit **tanpa** trailer `Co-Authored-By` atau atribusi Claude. Pesan commit berbahasa Indonesia, mengikuti gaya `git log`.
- Dokumen dan aplikasi tidak menyebut nama orang atau kata "teman". Penerima disebut "pengguna".
- Rahasia (key, password, isi `.env`, `keystore.properties`) tidak pernah masuk repo atau chat.
- Biaya Rp0: Render Free, Supabase Free, Gemini free tier. Kalau ada langkah yang meminta kartu atau pembayaran, berhenti dan tanya pemilik.
- Teks UI tidak berubah. Refactor hanya memindahkan kode.
- Urutan: Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10.
- Build Gradle di Windows: `.\gradlew.bat`. Kalau muncul `Unable to delete directory`, jalankan `.\gradlew.bat --stop` lalu ulangi (lihat PROGRESS checkpoint 1).

## Review Focus

1. **Shutdown mode tertanam saat job sedang berjalan:** thread worker tidak boleh menahan proses API lebih dari batas `join(timeout)`. Job yang terputus diambil ulang setelah lease habis. → tes di Task 6.
2. **`STUDYFLOW_EMBEDDED_WORKER=true` tanpa `database_url`:** API tetap start dan `/health` tetap 200, cukup log error tanpa crash. → tes di Task 6.
3. **`PORT` dari Render bukan 8000:** `start.sh` wajib memakai `${PORT}`. → verifikasi build Docker di Task 7.
4. **Lebar jendela tepat 600 dp:** sidebar muncul (≥ 600). Di 599 dp yang muncul bottom bar. → Compose UI test di Task 3.
5. **Ctrl+angka saat fokus di kolom teks:** pintasan tetap berpindah menu dan tidak mengetik angka ke dialog. → perilaku saat ini dipertahankan dan dipinkan oleh tes di Task 3 (fokus root, tanpa dialog terbuka).

---

### Task 1: ktlint, `.gitignore`, dan CI

**Files:**
- Modify: `gradle/libs.versions.toml` (tambah plugin `ktlint`)
- Modify: `build.gradle.kts` (terapkan plugin ke semua subproject)
- Create: `.editorconfig`
- Modify: `.gitignore` (tambah `bin/`)
- Modify: `.github/workflows/ci.yml` (job `app`: tambah `ktlintCheck`)
- Modify: `docs/DEPENDENCIES.md` (catat versi plugin)

**Interfaces:**
- Produces: task Gradle `ktlintCheck` dan `ktlintFormat` di `:shared`, `:androidApp`, dan `:desktopApp`.

- [ ] **Step 1: Pastikan versi plugin.** Cari versi terbaru `org.jlleitschuh.gradle.ktlint` di https://plugins.gradle.org/plugin/org.jlleitschuh.gradle.ktlint yang mendukung Gradle 9. Kalau tidak ada yang mendukung, pakai `com.diffplug.spotless` dengan `kotlin { ktlint() }`. Catat versinya di `docs/DEPENDENCIES.md`.

- [ ] **Step 2: Tambahkan plugin** di `gradle/libs.versions.toml`:

```toml
[versions]
ktlint-gradle = "<versi dari Step 1>"

[plugins]
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint-gradle" }
```

Lalu di `build.gradle.kts` root:

```kotlin
plugins {
    // ...plugin yang sudah ada...
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter { exclude { it.file.path.contains("generated") } }
    }
}
```

- [ ] **Step 3: Buat `.editorconfig`.**

```ini
root = true

[*.{kt,kts}]
max_line_length = 140
ktlint_code_style = ktlint_official
ktlint_function_naming_ignore_when_annotated_with = Composable
ktlint_standard_function-signature = disabled
ktlint_standard_class-signature = disabled
```

- [ ] **Step 4: Jalankan cek awal** untuk melihat jumlah pelanggaran: `.\gradlew.bat ktlintCheck --console=plain`. Hasilnya diharapkan FAIL dengan daftar pelanggaran.

- [ ] **Step 5: Commit konfigurasi dulu**, tanpa format:

```bash
git add gradle/libs.versions.toml build.gradle.kts .editorconfig docs/DEPENDENCIES.md
git commit -m "Tooling: ktlint untuk Kotlin"
```

- [ ] **Step 6: Format otomatis** dengan `.\gradlew.bat ktlintFormat`. Pelanggaran yang tidak bisa diperbaiki otomatis (mis. wildcard import) diperbaiki manual, tanpa mengubah logika. Hindari aturan baru dimatikan; kalau sebuah aturan terpaksa dimatikan, beri alasannya di `.editorconfig`.

- [ ] **Step 7: Verifikasi** dengan `.\gradlew.bat ktlintCheck :shared:jvmTest :desktopApp:jar --console=plain`. Hasilnya harus BUILD SUCCESSFUL.

- [ ] **Step 8: Commit format sebagai commit tersendiri.**

```bash
git add -u
git commit -m "Format: rapikan kode Kotlin dengan ktlint (tanpa perubahan perilaku)"
```

- [ ] **Step 9: Tambahkan `bin/` ke `.gitignore`** (di bagian Build & IDE). Ubah langkah CI job `app`:

```yaml
      - name: Tes dan build
        run: ./gradlew ktlintCheck :shared:jvmTest :androidApp:assembleDebug :desktopApp:jar --console=plain
```

Pastikan `git status` tidak lagi menampilkan `desktopApp/bin/`.

```bash
git add .gitignore .github/workflows/ci.yml
git commit -m "CI: jalankan ktlintCheck; abaikan output IDE bin/"
```

---

### Task 2: Tes ViewModel

**Files:**
- Create: `shared/src/jvmTest/kotlin/id/studyflow/ui/ViewModelTest.kt`
- Modify: `shared/build.gradle.kts` (dependensi `jvmTest` untuk Dispatchers.Main: `kotlinx-coroutines-swing`)

**Interfaces:**
- Consumes: `Device`, `FakeClock`, `MemorySessionStore`, dan `testServerConfig` dari `jvmTest/.../data/TestSupport.kt`. Juga `CoursesViewModel(CourseRepository)`, `HomeViewModel(CourseRepository, TaskRepository, AppClock, SessionManager, ConflictRepository)`, dan `TaskEditorViewModel(courseId, taskId, TaskRepository, AppClock)`.

- [ ] **Step 1: Tambah dependensi** di `shared/build.gradle.kts` (blok `sourceSets`):

```kotlin
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }
```

`viewModelScope` memakai `Dispatchers.Main.immediate`. Di JVM, Main disediakan oleh coroutines-swing.

- [ ] **Step 2: Tulis tes.**

```kotlin
package id.studyflow.ui

import id.studyflow.data.Device
import id.studyflow.domain.TaskStatus
import id.studyflow.ui.courses.CoursesViewModel
import id.studyflow.ui.dashboard.HomeViewModel
import id.studyflow.ui.tasks.TaskEditorViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ViewModelTest {
    private val device = Device(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

    @AfterTest
    fun close() = device.close()

    private suspend fun <T> Flow<T>.firstMatching(predicate: (T) -> Boolean): T = withTimeout(5_000) { first(predicate) }

    @Test
    fun coursesViewModelSavesAndReportsValidationError() = runBlocking<Unit> {
        val vm = CoursesViewModel(device.courses)
        var saved = false
        vm.save(null, "Kriptografi", "Ganjil 2026") { saved = true }
        val list = vm.courses.firstMatching { it?.isNotEmpty() == true }!!
        assertEquals("Kriptografi", list.single().name)
        assertEquals(true, saved)

        vm.save(null, "   ", "") { error("tidak boleh tersimpan") }
        assertNotNull(vm.error.firstMatching { it != null })
        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun homeCountsOpenAndOverdueTasksAndToggleDone() = runBlocking<Unit> {
        val courseId = device.courses.create("Basis Data", null)
        val past = device.clock.now - 60_000
        val future = device.clock.now + 86_400_000
        device.tasks.create(courseId, id.studyflow.data.repository.TaskInput("Lewat", "", past, "Asia/Jakarta"))
        device.tasks.create(courseId, id.studyflow.data.repository.TaskInput("Besok", "", future, "Asia/Jakarta"))
        val vm = HomeViewModel(device.courses, device.tasks, device.clock, device.session(), device.conflicts)

        val state = vm.state.firstMatching { !it.loading && it.openCount == 2 }
        assertEquals(1, state.overdueCount)
        assertEquals(1, state.courseCount)

        vm.toggleDone(state.upcoming.first { it.title == "Lewat" })
        val after = vm.state.firstMatching { it.openCount == 1 }
        assertEquals(0, after.overdueCount)
    }

    @Test
    fun taskEditorDefaultsToTomorrow2359AndSaves() = runBlocking<Unit> {
        val courseId = device.courses.create("Jaringan", null)
        val vm = TaskEditorViewModel(courseId, null, device.tasks, device.clock)
        val initial = vm.state.value
        assertEquals(23 to 59, initial.hour to initial.minute)
        assertEquals("Asia/Jakarta", initial.timeZoneId)

        vm.onTitleChange("Laporan praktikum")
        var done = false
        vm.save { done = true }
        val task = device.tasks.observeAll().firstMatching { it.isNotEmpty() }.single()
        assertEquals("Laporan praktikum", task.title)
        assertEquals(TaskStatus.TODO, task.status)
        withTimeout(5_000) { while (!done) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun taskEditorRejectsBlankTitle() = runBlocking<Unit> {
        val courseId = device.courses.create("Jaringan", null)
        val vm = TaskEditorViewModel(courseId, null, device.tasks, device.clock)
        vm.save { error("tidak boleh tersimpan") }
        val state = vm.state.firstMatching { it.error != null }
        assertEquals(false, state.saving)
    }
}
```

- [ ] **Step 3: Jalankan** `.\gradlew.bat :shared:jvmTest --tests "id.studyflow.ui.ViewModelTest" --console=plain`. Hasilnya harus PASS. Ini tes karakterisasi untuk kode yang sudah ada, jadi tes yang gagal berarti ada bug sungguhan, atau asumsi tes yang salah. Selidiki dengan superpowers:systematic-debugging dan jangan langsung melonggarkan assert.

- [ ] **Step 4: Commit.**

```bash
git add shared/build.gradle.kts shared/src/jvmTest/kotlin/id/studyflow/ui/ViewModelTest.kt
git commit -m "Tes: ViewModel mata kuliah, beranda, dan editor tugas"
```

---

### Task 3: Compose UI test untuk alur utama

**Files:**
- Modify: `shared/build.gradle.kts` (`jvmTest`: `compose.uiTest` dan `compose.desktop.currentOs`)
- Create: `shared/src/jvmTest/kotlin/id/studyflow/ui/AppFlowTest.kt`
- Modify (hanya `testTag`, tanpa perubahan teks): `shared/src/commonMain/kotlin/id/studyflow/ui/App.kt`

**Interfaces:**
- Consumes: `dataModule` dan `viewModelModule` dari `id.studyflow.di`, `InMemoryDatabases` dari TestSupport, dan `MemorySessionStore`.
- Produces: `testTag` `"sidebar"` (PermanentDrawerSheet) dan `"bottomBar"` (NavigationBar) di `App.kt`. Tag ini dipakai lagi di Task 4.

- [ ] **Step 1: Dependensi.**

```kotlin
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
```

- [ ] **Step 2: Tulis tes yang gagal** (tag belum ada):

```kotlin
package id.studyflow.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalTestApi::class)
class AppFlowTest {
    private val databases = InMemoryDatabases()

    @BeforeTest
    fun start() {
        startKoin {
            modules(
                module {
                    single<DatabaseFactory> { databases }
                    single<SecureSessionStore> { MemorySessionStore() }
                    single { HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }) { studyFlowDefaults() } }
                    // Mode lokal: konfigurasi server kosong.
                    single { ServerConfig("", "", "") }
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

    private fun androidx.compose.ui.test.ComposeUiTest.app(widthDp: Int) =
        setContent { Box(Modifier.requiredSize(widthDp.dp, 760.dp)) { App() } }

    @Test
    fun wideLayoutShowsSidebarAndNarrowShowsBottomBar() = runComposeUiTest {
        var width by androidx.compose.runtime.mutableIntStateOf(600)
        setContent { Box(Modifier.requiredSize(width.dp, 760.dp)) { App() } }
        onNodeWithTag("sidebar").assertIsDisplayed()
        width = 599
        waitForIdle()
        onNodeWithTag("bottomBar").assertIsDisplayed()
    }

    @Test
    fun addCourseNoteAndTaskThenMarkDone() = runComposeUiTest {
        app(1120)
        onNodeWithTag("sidebar").assertIsDisplayed()
        onNodeWithText("Mata Kuliah", useUnmergedTree = true).performClick()
        onNodeWithText("Tambah mata kuliah").performClick()
        onNode(hasSetTextAction() and hasText("Nama")).performTextInput("Kriptografi")
        onNodeWithText("Simpan").performClick()
        waitUntil(5_000) { onAllNodes(hasText("Kriptografi")).fetchSemanticsNodes().isNotEmpty() }

        onAllNodes(hasText("Kriptografi"))[0].performClick()
        onNodeWithText("Catatan").performClick() // FAB "Catatan" di detail
        onNode(hasSetTextAction() and hasText("Judul")).performTextInput("RSA")
        onNodeWithText("Simpan").performClick()
        waitUntil(5_000) { onAllNodes(hasText("RSA")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Tugas").performClick()
        onNode(hasSetTextAction() and hasText("Judul tugas")).performTextInput("Laporan")
        onNodeWithText("Simpan").performClick()

        onNodeWithText("Beranda", useUnmergedTree = true).performClick()
        waitUntil(5_000) { onAllNodes(hasText("Laporan")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun ctrlNumberSwitchesTopLevelScreen() = runComposeUiTest {
        app(1120)
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Four) } }
        waitUntil(5_000) { onAllNodes(hasText("Pengaturan")).fetchSemanticsNodes().size >= 2 } // menu + judul layar
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Two) } }
        waitUntil(5_000) { onAllNodes(hasText("Tambah mata kuliah")).fetchSemanticsNodes().isNotEmpty() }
    }
}
```

Catatan untuk pelaksana:
- Label field catatan dan teks tombol di `NoteEditor.kt` harus dicek dulu. Jika labelnya bukan "Judul", sesuaikan **tesnya**, bukan UI-nya.
- Langkah "tandai selesai" di Beranda memakai checkbox pada kartu tugas. Cari node-nya dengan `onNode(hasContentDescription(...))` sesuai `contentDescription` di `ui/components/Components.kt`, lalu `performClick()` dan pastikan "Laporan" tidak lagi muncul di daftar deadline terbuka. Kalau checkbox belum punya `contentDescription`, tambahkan `testTag("taskToggle-${task.id}")` tanpa mengubah tampilan.

- [ ] **Step 3: Jalankan tes**: `.\gradlew.bat :shared:jvmTest --tests "id.studyflow.ui.AppFlowTest"`. Hasil yang diharapkan: FAIL, karena node dengan tag `sidebar` tidak ditemukan.

- [ ] **Step 4: Tambahkan tag** di `App.kt`: `Modifier.width(272.dp).fillMaxHeight().testTag("sidebar")` pada `PermanentDrawerSheet`, dan `Modifier.testTag("bottomBar")` pada `NavigationBar`. Import `androidx.compose.ui.platform.testTag`.

- [ ] **Step 5: Jalankan lagi** sampai semua PASS. Cocokkan teks node dengan kode sumber, jangan mengubah teks UI.

- [ ] **Step 6: Commit.**

```bash
git add shared/build.gradle.kts shared/src/jvmTest/kotlin/id/studyflow/ui/AppFlowTest.kt shared/src/commonMain
git commit -m "Tes: Compose UI untuk alur mata kuliah, catatan, tugas, layout, dan pintasan"
```

---

### Task 4: Pecah `App.kt`

**Files:**
- Create: `shared/src/commonMain/kotlin/id/studyflow/ui/navigation/TopLevel.kt` (data `TopLevel`, `topLevels`, `navigateTopLevel`)
- Create: `shared/src/commonMain/kotlin/id/studyflow/ui/navigation/Sidebar.kt` (`@Composable fun Sidebar(...)`, beserta hover dan sorotan mata kuliah)
- Create: `shared/src/commonMain/kotlin/id/studyflow/ui/navigation/BottomBar.kt`
- Create: `shared/src/commonMain/kotlin/id/studyflow/ui/navigation/Shortcuts.kt` (`fun Modifier.topLevelShortcuts(onSelect: (Int) -> Unit): Modifier`)
- Modify: `shared/src/commonMain/kotlin/id/studyflow/ui/App.kt`

**Interfaces:**
- Produces:
  - `internal data class TopLevel(val label: String, val icon: ImageVector, val route: Any, val routeClass: KClass<*>)`
  - `internal val topLevels: List<TopLevel>`
  - `@Composable internal fun Sidebar(currentIndex: Int, backStackEntry: NavBackStackEntry?, onSelect: (Int) -> Unit, onOpenCourse: (String) -> Unit)`
  - `@Composable internal fun BottomBar(currentIndex: Int, onSelect: (Int) -> Unit)`
  - `internal fun Modifier.topLevelShortcuts(onSelect: (Int) -> Unit): Modifier`

- [ ] **Step 1:** Baca `App.kt` sampai habis. Pindahkan kode **apa adanya** ke file-file di atas, termasuk konstanta `HOVER_CLOSE_DELAY_MS`, `COURSES_INDEX`, dan `shortcutKeys`. `App()` hanya menyusun `Sidebar`, `BottomBar`, `NavHost`, dan dialog. `testTag` `sidebar` dan `bottomBar` ikut dipindah.
- [ ] **Step 2:** Jalankan `.\gradlew.bat ktlintCheck :shared:jvmTest --console=plain`. Semua harus PASS, termasuk `AppFlowTest`.
- [ ] **Step 3:** Cek manual: `.\gradlew.bat :desktopApp:run`. Pastikan hover daftar mata kuliah, sorotan mata kuliah aktif, Ctrl+1..4, dan bottom bar di jendela sempit masih berfungsi.
- [ ] **Step 4:** Commit: `git commit -am "Refactor: pecah App.kt menjadi sidebar, bottom bar, dan pintasan"` (tambahkan dulu file baru dengan `git add`).

---

### Task 5: Pecah layar Study dan Materials

**Files:**
- Modify/Create di `shared/src/commonMain/kotlin/id/studyflow/ui/study/`: satu file untuk setiap `@Composable` layar publik di `StudyScreens.kt`. Nama file mengikuti nama fungsi layarnya, mis. `SummaryScreen.kt`, `QuizScreen.kt`, `QuizHistoryScreen.kt`, `TopicsScreen.kt`. Komponen privat yang dipakai bersama pindah ke `StudyComponents.kt`.
- Modify/Create di `ui/materials/`: `MaterialsViewModels.kt` dipecah menjadi `MaterialsViewModel.kt`, `DocumentViewModel.kt`, `AskViewModel.kt`, dan `SourceViewModel.kt`. `MaterialsScreens.kt` dipecah per layar.

**Interfaces:**
- Nama kelas, nama fungsi, package, dan visibilitas **tidak berubah**. Hanya lokasi file yang berubah, jadi `AppModule.kt` dan pemanggil lain tidak perlu diubah.

- [ ] **Step 1:** Daftar deklarasi tingkat atas: `grep -n "^fun \|^class \|^private fun \|^internal fun \|^@Composable" StudyScreens.kt MaterialsScreens.kt MaterialsViewModels.kt`.
- [ ] **Step 2:** Pindahkan per kelompok. Deklarasi `private` yang dipakai lintas file baru diubah menjadi `internal`, dan itu satu-satunya perubahan visibilitas yang boleh.
- [ ] **Step 3:** Jalankan `.\gradlew.bat ktlintCheck :shared:jvmTest :androidApp:assembleDebug :desktopApp:jar --console=plain`. Hasilnya harus BUILD SUCCESSFUL.
- [ ] **Step 4:** Commit: `Refactor: satu file per layar dan ViewModel di Belajar dan Materi`.

---

### Task 6: Mode worker tertanam (backend)

**Files:**
- Create: `backend/app/worker/bootstrap.py` (`build_worker(settings) -> Worker | None`)
- Modify: `backend/app/worker/__main__.py` (pakai `build_worker`)
- Modify: `backend/app/worker/runner.py` (`run_forever(poll_seconds=2.0, stop: threading.Event | None = None)`)
- Modify: `backend/app/core/config.py` (`embedded_worker: bool = False`)
- Modify: `backend/app/main.py` (lifespan)
- Test: `backend/tests/test_embedded_worker.py` (unit, tanpa DB) dan `backend/tests/integration/test_embedded_worker.py`

**Interfaces:**
- Produces:
  - `build_worker(settings: Settings) -> Worker | None`: mengembalikan None (dan mencatat error ke log) jika `database_url` kosong atau storage gagal.
  - `Worker.run_forever(poll_seconds: float = 2.0, stop: threading.Event | None = None) -> None`
  - `create_app(worker_factory: Callable[[Settings], Worker | None] = build_worker) -> FastAPI`
  - Atribut `app.state.embedded_worker_thread: threading.Thread | None`

- [ ] **Step 1: Tulis tes unit yang gagal** di `backend/tests/test_embedded_worker.py`:

```python
import threading

from fastapi.testclient import TestClient

from app.core.config import AIProviderKind, AppEnv, Settings, get_settings
from app.main import create_app


class FakeWorker:
    def __init__(self) -> None:
        self.started = threading.Event()
        self.stopped = threading.Event()

    def run_forever(self, poll_seconds: float = 2.0, stop: threading.Event | None = None) -> None:
        self.started.set()
        assert stop is not None
        stop.wait()
        self.stopped.set()


def _app(monkeypatch, worker, **overrides):
    settings = Settings(_env_file=None, app_env=AppEnv.TEST, ai_provider=AIProviderKind.NONE, **overrides)
    get_settings.cache_clear()
    monkeypatch.setattr("app.main.get_settings", lambda: settings)
    return create_app(worker_factory=lambda s: worker)


def test_embedded_worker_starts_with_app_and_stops_on_shutdown(monkeypatch):
    worker = FakeWorker()
    app = _app(monkeypatch, worker, embedded_worker=True)
    with TestClient(app) as client:
        assert worker.started.wait(2)
        assert client.get("/health").status_code == 200
    assert worker.stopped.wait(2)
    assert not app.state.embedded_worker_thread.is_alive()


def test_embedded_worker_disabled_by_default(monkeypatch):
    worker = FakeWorker()
    app = _app(monkeypatch, worker)
    with TestClient(app):
        assert not worker.started.wait(0.2)
    assert app.state.embedded_worker_thread is None


def test_missing_database_keeps_api_alive(monkeypatch):
    app = _app(monkeypatch, None, embedded_worker=True)
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
    assert app.state.embedded_worker_thread is None
```

- [ ] **Step 2:** Jalankan `cd backend; uv run pytest tests/test_embedded_worker.py -v`. Hasil yang diharapkan: FAIL (`create_app() got an unexpected keyword argument 'worker_factory'`).

- [ ] **Step 3: Implementasi.**

`app/core/config.py`, di dekat `ai_job_lease_seconds`:

```python
    # Jalankan worker sebagai thread di dalam proses API (hosting gratis dengan satu service).
    embedded_worker: bool = False
```

`app/worker/runner.py`:

```python
    def run_forever(self, poll_seconds: float = 2.0, stop: threading.Event | None = None) -> None:
        logger.info("Worker AI berjalan")
        while stop is None or not stop.is_set():
            if not self.run_once():
                if stop is None:
                    self.sleep(poll_seconds)
                else:
                    stop.wait(poll_seconds)
        logger.info("Worker AI berhenti")
```

Tambahkan juga `import threading` di bagian atas file.

`app/worker/bootstrap.py`:

```python
"""Membangun Worker dari Settings; dipakai proses worker terpisah dan mode tertanam."""

import logging

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.core.config import Settings
from app.documents.storage import StorageError, get_storage
from app.providers.base import ProviderUnavailableError
from app.providers.factory import get_embedder, get_provider
from app.worker.runner import Worker

logger = logging.getLogger("studyflow.worker")


def build_worker(settings: Settings) -> Worker | None:
    if not settings.database_url:
        logger.error("STUDYFLOW_DATABASE_URL belum diset")
        return None
    try:
        provider = get_provider(settings)
    except ProviderUnavailableError:
        provider = None
        logger.warning("Provider AI generatif tidak dikonfigurasi: job rangkuman/kuis/tanya materi ditunda.")
    try:
        embedder = get_embedder(settings)
    except ProviderUnavailableError:
        embedder = None
        logger.warning("Embedding tidak dikonfigurasi: job indexing dokumen dan tanya materi ditunda.")
    try:
        storage = get_storage(settings)
    except StorageError as exc:
        logger.error("%s", exc)
        return None
    factory = sessionmaker(bind=create_engine(settings.database_url, pool_pre_ping=True), expire_on_commit=False)
    return Worker(factory, settings, provider, embedder, storage)
```

`app/worker/__main__.py`:

```python
import logging
import sys

from app.core.config import get_settings
from app.worker.bootstrap import build_worker

logger = logging.getLogger("studyflow.worker")


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    worker = build_worker(get_settings())
    if worker is None:
        return 1
    try:
        worker.run_forever()
    except KeyboardInterrupt:
        logger.info("Worker dihentikan")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

`app/main.py`:

```python
import logging
import threading
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app import __version__
from app.api import ai, documents, health, progress, sync
from app.api.errors import install_error_handling
from app.core.config import Settings, get_settings
from app.worker.bootstrap import build_worker
from app.worker.runner import Worker

logger = logging.getLogger("studyflow.api")

# Batas tunggu thread worker saat shutdown; job yang terputus diambil ulang setelah lease habis.
WORKER_STOP_TIMEOUT_SECONDS = 10.0


def create_app(worker_factory: Callable[[Settings], Worker | None] = build_worker) -> FastAPI:
    settings = get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.embedded_worker_thread = None
        stop = threading.Event()
        if settings.embedded_worker:
            worker = worker_factory(settings)
            if worker is None:
                logger.error("Worker tertanam tidak dijalankan; job AI akan menunggu.")
            else:
                thread = threading.Thread(
                    target=worker.run_forever, kwargs={"stop": stop}, name="embedded-worker", daemon=True
                )
                thread.start()
                app.state.embedded_worker_thread = thread
        try:
            yield
        finally:
            stop.set()
            thread = app.state.embedded_worker_thread
            if thread is not None:
                thread.join(WORKER_STOP_TIMEOUT_SECONDS)

    app = FastAPI(title="StudyFlow API", version=__version__, lifespan=lifespan)
    app.state.embedded_worker_thread = None
    install_error_handling(app)
    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origins,
            allow_methods=["*"],
            allow_headers=["Authorization", "Content-Type", "Idempotency-Key"],
        )
    app.include_router(health.router)
    app.include_router(sync.router)
    app.include_router(ai.router)
    app.include_router(documents.router)
    app.include_router(progress.router)
    return app


app = create_app()
```

- [ ] **Step 4:** Jalankan `uv run pytest tests/test_embedded_worker.py -v`. Hasilnya harus 3 PASS.

- [ ] **Step 5: Tes integrasi** di `backend/tests/integration/test_embedded_worker.py`. Tes ini dilewati tanpa DB. Ia memakai fixture `client` versi sendiri dengan `embedded_worker=True` dan `ai_provider=fake`:

```python
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from app.core.config import AIProviderKind, AppEnv, Settings, get_settings
from app.main import create_app
from tests.integration.conftest import JWT_SECRET, SUPABASE_URL, TEST_DB_URL, make_token

pytestmark = pytest.mark.integration


def test_job_completes_without_separate_worker(engine, monkeypatch):
    settings = Settings(
        _env_file=None, app_env=AppEnv.TEST, ai_provider=AIProviderKind.FAKE, database_url=TEST_DB_URL,
        supabase_url=SUPABASE_URL, jwt_hs256_secret=JWT_SECRET, ai_min_interval_seconds=0, embedded_worker=True,
    )
    monkeypatch.setattr("app.main.get_settings", lambda: settings)
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    user = uuid.uuid4()
    headers = {"Authorization": f"Bearer {make_token(user)}"}
    course_id, note_id = uuid.uuid4(), uuid.uuid4()
    with TestClient(app) as client:
        client.post("/v1/sync/push", headers=headers, json={"mutations": [
            {"mutation_id": str(uuid.uuid4()), "entity_type": "course", "entity_id": str(course_id),
             "operation": "create", "base_version": 0, "payload": {"name": "Kriptografi"}},
            {"mutation_id": str(uuid.uuid4()), "entity_type": "note", "entity_id": str(note_id),
             "operation": "create", "base_version": 0,
             "payload": {"course_id": str(course_id), "title": "RSA", "content": "RSA memakai dua bilangan prima."}},
        ]})
        job = client.post("/v1/summaries", headers=headers | {"Idempotency-Key": uuid.uuid4().hex},
                          json={"note_id": str(note_id), "note_version": 1}).json()
        deadline = time.monotonic() + 20
        status = None
        while time.monotonic() < deadline:
            status = client.get(f"/v1/jobs/{job['id']}", headers=headers).json()["status"]
            if status == "succeeded":
                break
            time.sleep(0.2)
        assert status == "succeeded"
```

Sesuaikan body `/v1/summaries` dengan helper `request_summary` di `tests/integration/test_ai.py`.

- [ ] **Step 6:** Jalankan `cd backend; docker compose up -d`, lalu `$env:STUDYFLOW_TEST_DATABASE_URL="postgresql+psycopg://studyflow:studyflow-dev@localhost:54329/studyflow_test"; uv run pytest; uv run ruff check .`. Semua harus PASS.

- [ ] **Step 7:** Commit: `Backend: mode worker tertanam untuk hosting satu service`.

---

### Task 7: Docker start script, `render.yaml`, dan Playwright lokal

**Files:**
- Create: `backend/docker/start.sh`
- Modify: `backend/Dockerfile` (salin `docker/start.sh`, `chmod +x`; CMD default tidak berubah)
- Create: `render.yaml` (root repo)
- Modify: `docs/PROGRESS.md` (hasil verifikasi)

- [ ] **Step 1: `backend/docker/start.sh`**

```sh
#!/bin/sh
# Mode satu service (mis. Render Free): migrasi, lalu API dengan worker tertanam.
set -e
alembic upgrade head
exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-8000}"
```

Tambahkan ke `Dockerfile`, sebelum `USER studyflow`:

```dockerfile
COPY docker/start.sh /app/start.sh
RUN chmod +x /app/start.sh
```

Periksa `.gitattributes`: `*.sh` harus memakai `eol=lf`. Kalau belum, tambahkan `*.sh text eol=lf`.

- [ ] **Step 2: `render.yaml`**

```yaml
services:
  - type: web
    name: studyflow-api
    runtime: docker
    plan: free
    rootDir: backend
    dockerfilePath: ./Dockerfile
    dockerCommand: /app/start.sh
    healthCheckPath: /health
    autoDeploy: true
    envVars:
      - key: STUDYFLOW_APP_ENV
        value: production
      - key: STUDYFLOW_EMBEDDED_WORKER
        value: "true"
      - key: STUDYFLOW_STORAGE_BACKEND
        value: supabase
      - key: STUDYFLOW_AI_PROVIDER
        value: gemini
      - key: STUDYFLOW_EMBEDDING_PROVIDER
        value: gemini
      - key: STUDYFLOW_DATABASE_URL
        sync: false
      - key: STUDYFLOW_SUPABASE_URL
        sync: false
      - key: STUDYFLOW_SUPABASE_SERVICE_KEY
        sync: false
      - key: STUDYFLOW_AI_API_KEY
        sync: false
```

- [ ] **Step 3: Verifikasi start script.** Jalankan `docker compose -f docker-compose.demo.yml up -d db`. Lalu build dan jalankan image dengan `PORT=10000`, `STUDYFLOW_EMBEDDED_WORKER=true`, dan env demo yang sama dengan compose demo:

```powershell
docker build -t studyflow-backend backend
docker run --rm -d --name sf-single --network backend_default -p 10000:10000 -e PORT=10000 -e STUDYFLOW_EMBEDDED_WORKER=true -e STUDYFLOW_APP_ENV=development -e STUDYFLOW_DATABASE_URL=postgresql+psycopg://studyflow:studyflow-dev@db:5432/studyflow -e STUDYFLOW_SUPABASE_URL=http://auth.demo.local -e STUDYFLOW_JWT_HS256_SECRET=demo-only-secret-change-me-000000000000 -e STUDYFLOW_AI_PROVIDER=fake -e STUDYFLOW_EMBEDDING_PROVIDER=fake -e STUDYFLOW_AI_MIN_INTERVAL_SECONDS=0 studyflow-backend /app/start.sh
```

Nama network bisa dicek dengan `docker network ls`.

- [ ] **Step 4: Playwright MCP** (verifikasi lewat browser):
  1. `browser_navigate` ke `http://127.0.0.1:10000/health`, lalu pastikan JSON `status: ok` dan `ai_mode` demo.
  2. `browser_navigate` ke `/docs`. `browser_snapshot` harus menampilkan judul "StudyFlow API", dan `browser_console_messages` tidak boleh berisi error.
  3. Buat token demo HS256 dengan `uv run python -c "import jwt,time,uuid; n=int(time.time()); print(jwt.encode({'sub':str(uuid.uuid4()),'aud':'authenticated','iss':'http://auth.demo.local/auth/v1','exp':n+3600,'iat':n,'role':'authenticated'},'demo-only-secret-change-me-000000000000',algorithm='HS256'))"`. Klaim token harus disamakan dengan `make_token` di `tests/integration/conftest.py`. Token ini hanya untuk demo lokal.
  4. Lewat `browser_evaluate`, jalankan `fetch`: push course dan note, `POST /v1/summaries`, lalu polling `/v1/jobs/{id}` sampai `succeeded`, kemudian `GET /v1/summaries/{result_id}`. Isinya harus diawali `[Demo`.
  5. `browser_take_screenshot` halaman `/docs` sebagai bukti.
- [ ] **Step 5:** Hentikan container dengan `docker stop sf-single` dan `docker compose -f backend/docker-compose.demo.yml down`. Catat hasilnya di `docs/PROGRESS.md` (checkpoint baru, tanggal 11 Oktober 2026). Commit: `Deploy: start script satu service, blueprint Render, verifikasi Playwright lokal`.

---

### Task 8: Konfigurasi layanan online (lewat browser pemilik)

Task ini memakai Claude in Chrome dan pemilik ikut serta. **Pemilik** yang login dan mendaftar, serta mengisi password dan data kartu kalau diminta. Kalau diminta kartu atau pembayaran, berhenti dan tanya pemilik. **Claude** menavigasi, mengisi form konfigurasi, dan menyalin key langsung antar-dashboard. Key tidak ditulis di chat atau file. Minta konfirmasi sebelum menekan tombol yang membuat atau menghapus resource.

- [ ] **Step 1: Push `main` ke GitHub.** Tanyakan dulu ke pemilik, karena push itu tindakan keluar. Perintahnya: `git push origin main`.
- [ ] **Step 2: Supabase.**
  1. Buat proyek `studyflow` (region Singapore, tier Free). Password database dibuat dan disimpan oleh pemilik.
  2. Authentication > Sign In / Providers > Email: aktif, **Confirm email dimatikan** (batas email gratis).
  3. Storage: buat bucket privat `documents`.
  4. Catat ke dashboard Render (bukan ke chat): Project URL, secret key, dan connection string **Session pooler** (`postgresql+psycopg://...pooler.supabase.com:5432/postgres`).
  5. Publishable key dan Project URL dimasukkan ke `local.properties` pemilik. Nilai ini publik, tetapi file-nya tetap di-ignore.
- [ ] **Step 3: Gemini.** Di Google AI Studio, pilih "Get API key", lalu "Create API key". Key ditempel langsung ke env Render `STUDYFLOW_AI_API_KEY`.
- [ ] **Step 4: Render.** New > Blueprint > pilih repo `StudyFlow`. Render membaca `render.yaml`. Isi 4 env `sync: false`, lalu Apply. Kalau Render meminta kartu, **berhenti** dan tawarkan cadangan Hugging Face Spaces.
- [ ] **Step 5: Migrasi.** Migrasi berjalan otomatis lewat `start.sh`. Periksa log deploy Render: harus ada `alembic upgrade head` tanpa error.
- [ ] **Step 6: Verifikasi Playwright online.** Buka `https://studyflow-api.onrender.com/health` (atau URL yang diberikan Render). Hasilnya harus `200`, dengan `ai_mode` yang menunjukkan Gemini. `/docs` harus termuat tanpa error console.
- [ ] **Step 7: Dokumentasi.** Tambahkan bagian "Deploy gratis (Render + Supabase + Gemini)" ke `docs/DEPLOYMENT.md`, berisi langkah 2–6 tanpa nilai rahasia, termasuk catatan bahwa layanan tidur sekitar 15 menit dan Supabase dijeda setelah sekitar seminggu tidak aktif. Commit: `docs: panduan deploy gratis`.

---

### Task 9: Installer Windows `.exe` dan APK release

**Files:**
- Modify: `desktopApp/build.gradle.kts` (kalau perlu: `windows { menuGroup = "StudyFlow"; shortcut = true; dirChooser = true; upgradeUuid = "<UUID tetap>" }`)
- Create: `docs/INSTALL.md` (panduan pengguna)

- [ ] **Step 1: WiX.** Minta izin pemilik, lalu jalankan `winget install WiXToolset.WiXToolset` (v3). Pastikan `candle.exe` ada di PATH.
- [ ] **Step 2: Konfigurasi build.** Isi `local.properties` pemilik dengan `studyflow.supabase.url`, `studyflow.supabase.publishableKey`, dan `studyflow.api.baseUrl=https://studyflow-api.onrender.com` (URL dari Task 8). `studyflow.api.baseUrl.android` dikosongkan supaya Android memakai URL yang sama.
- [ ] **Step 3: Windows.** Tambahkan blok `windows { ... }` dengan `upgradeUuid` tetap (dibuat sekali dengan `[guid]::NewGuid()`) supaya versi berikutnya bisa meng-upgrade instalasi lama. Jalankan `.\gradlew.bat :desktopApp:packageExe`. Hasilnya `desktopApp\build\compose\binaries\main\exe\StudyFlow-0.1.0.exe`.
- [ ] **Step 4: Android.** Pemilik membuat keystore di luar repo (perintah `keytool` ada di DEPLOYMENT.md) dan `keystore.properties`. Lalu jalankan `.\gradlew.bat :androidApp:assembleRelease`.
- [ ] **Step 5: Verifikasi end-to-end.**
  1. Pasang `.exe` dan jalankan dari Start Menu.
  2. Daftar akun uji, buat mata kuliah dan catatan, lalu minta rangkuman AI. Hasilnya harus muncul, meskipun permintaan pertama bisa lambat karena layanan baru bangun.
  3. Pasang APK di emulator `Medium_Phone_API_36.1` dan masuk dengan akun yang sama. Catatan harus tersinkron.
  4. Hapus akun uji dari Supabase setelah selesai.
- [ ] **Step 6: `docs/INSTALL.md`** untuk pengguna:
  - cara memasang `.exe` (SmartScreen: "More info → Run anyway") dan APK ("Instal aplikasi tidak dikenal");
  - cara mendaftar;
  - pemberitahuan data Gemini free tier;
  - catatan bahwa permintaan pertama bisa lambat sekitar 1 menit.

  Dokumen ini tanpa nama orang. Commit: `Rilis: installer Windows dan panduan instalasi pengguna`.

---

### Task 10: Penutup

- [ ] Jalankan `.\gradlew.bat ktlintCheck :shared:jvmTest :androidApp:assembleDebug :desktopApp:jar` dan `cd backend; uv run ruff check .; uv run pytest`. Semua harus lulus.
- [ ] Perbarui `docs/PLAN.md` (bagian "Setelah fase 6") dan `docs/PROGRESS.md` (checkpoint baru berisi perintah dan hasilnya), lalu README (tautan ke INSTALL.md dan deploy gratis).
- [ ] Gunakan superpowers:requesting-code-review untuk review seluruh branch.
- [ ] Gunakan superpowers:finishing-a-development-branch.
