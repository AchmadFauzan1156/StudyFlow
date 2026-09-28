# StudyFlow

Aplikasi belajar pribadi untuk mahasiswa: simpan materi per mata kuliah, kelola deadline, lalu (tahap berikutnya) buat rangkuman dan kuis dengan AI serta tanya materi dengan rujukan yang dapat diperiksa.

- **Aplikasi**: Kotlin Multiplatform + Compose Multiplatform (Android dan desktop Windows).
- **Backend**: Python + FastAPI (API dan worker satu codebase).

Status saat ini: **fase 0–1 selesai** — aplikasi berjalan sepenuhnya lokal (mata kuliah, catatan, deadline tersimpan di Room/SQLite, tanpa akun). Rincian di [docs/PROGRESS.md](docs/PROGRESS.md), rencana di [docs/PLAN.md](docs/PLAN.md).

## Struktur

| Lokasi | Isi |
|---|---|
| `shared/` | UI bersama, ViewModel, domain, repository, database Room (commonMain), adapter Android (androidMain) dan desktop (jvmMain) |
| `androidApp/` | Entry point Android |
| `desktopApp/` | Entry point desktop dan packaging |
| `backend/` | FastAPI: `/health`, format error, kontrak provider AI, fake provider untuk development |
| `docs/` | Rencana, keputusan, dependency, kontrak API, progres |

## Prasyarat

- JDK 17 atau lebih baru (diuji dengan JDK 25). Gradle tidak perlu dipasang — gunakan wrapper.
- Android SDK dengan platform **API 37** (untuk build Android). Buat `local.properties` di root:
  ```properties
  sdk.dir=C\:/Users/<nama>/AppData/Local/Android/Sdk
  ```
  (Android Studio membuat file ini otomatis saat proyek dibuka.)
- Python 3.12+ dan [uv](https://docs.astral.sh/uv/) untuk backend.

## Menjalankan aplikasi

PowerShell (Windows):

```powershell
.\gradlew.bat :desktopApp:run            # aplikasi desktop
.\gradlew.bat :androidApp:assembleDebug  # APK di androidApp\build\outputs\apk\debug\
.\gradlew.bat :androidApp:installDebug   # pasang ke emulator/perangkat yang terhubung
.\gradlew.bat :shared:jvmTest            # tes logika bersama + repository Room
```

macOS/Linux: ganti `.\gradlew.bat` dengan `./gradlew`.

Data desktop disimpan di `%APPDATA%\StudyFlow\studyflow-local.db` (Windows) atau `~/.studyflow/`.

Paket installer desktop (MSI/EXE) dapat dibuat dengan `.\gradlew.bat :desktopApp:packageDistributionForCurrentOS` (membutuhkan JDK dengan `jpackage`; untuk MSI juga WiX Toolset).

## Menjalankan backend

```powershell
cd backend
uv sync
Copy-Item .env.example .env          # sesuaikan bila perlu; jangan commit .env
uv run uvicorn app.main:app --reload --port 8000
uv run pytest
uv run ruff check .
```

Buka `http://127.0.0.1:8000/health`. Dokumentasi interaktif: `http://127.0.0.1:8000/docs`.

## Konfigurasi dan rahasia

- Aplikasi tidak membawa kunci AI atau kredensial database. Semua rahasia hanya di backend/worker.
- `backend/.env.example` berisi semua variabel yang dikenal, tanpa nilai rahasia. Salin ke `.env` (sudah di-ignore Git).
- Tanpa provider AI (`STUDYFLOW_AI_PROVIDER=none`, default), fitur AI akan mengembalikan 503 dan fitur lain tetap berjalan. `fake` hanya untuk development dan semua hasilnya berlabel **[Demo]**.
