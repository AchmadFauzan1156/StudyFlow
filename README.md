# StudyFlow

Aplikasi belajar pribadi untuk mahasiswa: simpan materi per mata kuliah, kelola deadline, sinkronkan antar perangkat, lalu (tahap berikutnya) buat rangkuman dan kuis dengan AI serta tanya materi dengan rujukan yang dapat diperiksa.

- **Aplikasi**: Kotlin Multiplatform + Compose Multiplatform (Android dan desktop Windows).
- **Backend**: Python + FastAPI + PostgreSQL (Supabase untuk Auth dan database terkelola).

Status saat ini: **fase 0–2 selesai**. Mode lokal tanpa akun, lalu akun Supabase dengan sinkronisasi offline-first (outbox, konflik, isolasi per akun). Rincian di [docs/PROGRESS.md](docs/PROGRESS.md), rencana di [docs/PLAN.md](docs/PLAN.md).

## Struktur

| Lokasi | Isi |
|---|---|
| `shared/` | UI bersama, ViewModel, domain, repository, Room, klien Auth/sinkronisasi (commonMain); adapter Android dan desktop |
| `androidApp/` | Entry point Android |
| `desktopApp/` | Entry point desktop dan packaging |
| `backend/` | FastAPI: `/health`, `/v1/me`, `/v1/sync/push`, `/v1/sync/pull`; migrasi Alembic; fake provider AI |
| `docs/` | Rencana, keputusan, dependency, kontrak API, progres |

## Prasyarat

- JDK 17+ (diuji dengan JDK 25). Gradle tidak perlu dipasang; gunakan wrapper.
- Android SDK dengan platform **API 37**. `local.properties` di root (dibuat otomatis oleh Android Studio):
  ```properties
  sdk.dir=C\:/Users/<nama>/AppData/Local/Android/Sdk
  ```
- Python 3.12+ dan [uv](https://docs.astral.sh/uv/) untuk backend; Docker untuk PostgreSQL lokal.

## Menjalankan aplikasi

```powershell
.\gradlew.bat :desktopApp:run            # aplikasi desktop
.\gradlew.bat :androidApp:assembleDebug  # APK di androidApp\build\outputs\apk\debug\
.\gradlew.bat :androidApp:installDebug   # pasang ke emulator/perangkat yang terhubung
.\gradlew.bat :shared:jvmTest            # tes shared (logika, Room, sinkronisasi, sesi)
```

macOS/Linux: ganti `.\gradlew.bat` dengan `./gradlew`.

Tanpa konfigurasi server, aplikasi berjalan dalam **mode lokal** (tidak ada tombol masuk yang aktif). Data desktop ada di `%APPDATA%\StudyFlow\` (Windows) atau `~/.studyflow/`: `studyflow-local.db` untuk mode lokal dan `studyflow-user-<id>.db` per akun.

### Mengaktifkan akun dan sinkronisasi

Nilai berikut **bukan rahasia** (URL dan publishable key) dan dibaca saat build dari `local.properties` atau environment variable:

```properties
studyflow.supabase.url=https://<project-ref>.supabase.co
studyflow.supabase.publishableKey=sb_publishable_...
studyflow.api.baseUrl=http://127.0.0.1:8000
# Opsional, untuk emulator Android yang menjangkau host lewat 10.0.2.2:
studyflow.api.baseUrl.android=http://10.0.2.2:8000
```

Padanan environment variable: `STUDYFLOW_SUPABASE_URL`, `STUDYFLOW_SUPABASE_PUBLISHABLE_KEY`, `STUDYFLOW_API_BASE_URL`, `STUDYFLOW_API_BASE_URL_ANDROID`. Jangan pernah menaruh service-role key atau kunci AI di aplikasi.

Build debug Android mengizinkan HTTP tanpa TLS hanya ke `10.0.2.2`, `localhost`, dan `127.0.0.1`; build release mewajibkan HTTPS.

Token disimpan terenkripsi: Android Keystore (AES-GCM) dan Windows DPAPI. Di desktop non-Windows sesi hanya disimpan di memori, jadi pengguna perlu masuk lagi setelah aplikasi ditutup.

## Menjalankan backend

```powershell
cd backend
docker compose up -d                 # PostgreSQL (pgvector) di localhost:54329
uv sync
Copy-Item .env.example .env          # isi STUDYFLOW_SUPABASE_URL, dll.; jangan commit .env
uv run alembic upgrade head
uv run uvicorn app.main:app --reload --port 8000
```

Buka `http://127.0.0.1:8000/health` dan dokumentasi interaktif di `http://127.0.0.1:8000/docs`.

Di Supabase: jalankan `uv run alembic upgrade head` dengan `STUDYFLOW_DATABASE_URL` proyekmu. Migrasi mengaktifkan RLS default-deny dan mencabut hak `anon`/`authenticated` pada tabel bisnis.

### Tes backend

```powershell
cd backend
uv run pytest                                    # tes unit (tanpa database)
$env:STUDYFLOW_TEST_DATABASE_URL = "postgresql+psycopg://studyflow:studyflow-dev@localhost:54329/studyflow_test"
uv run pytest                                    # + tes integrasi PostgreSQL
uv run ruff check .
```

### Tes end-to-end dua perangkat

Jalankan backend dengan JWT HS256 uji, lalu tes Kotlin memakai secret yang sama:

```powershell
cd backend
$env:STUDYFLOW_DATABASE_URL = "postgresql+psycopg://studyflow:studyflow-dev@localhost:54329/studyflow"
$env:STUDYFLOW_SUPABASE_URL = "http://auth.test.local"
$env:STUDYFLOW_JWT_HS256_SECRET = "e2e-secret-with-at-least-32-bytes-00000"
uv run uvicorn app.main:app --port 8766
```

```powershell
$env:STUDYFLOW_E2E_API_URL = "http://127.0.0.1:8766"
$env:STUDYFLOW_E2E_JWT_SECRET = "e2e-secret-with-at-least-32-bytes-00000"
.\gradlew.bat :shared:jvmTest --tests "*TwoDeviceE2ETest*" --rerun-tasks
```

Tanpa kedua variabel `STUDYFLOW_E2E_*`, tes E2E dilewati.

## Konfigurasi dan rahasia

- Kunci AI, kredensial database, dan service-role key hanya ada di backend/worker.
- `backend/.env.example` berisi semua variabel yang dikenal, tanpa nilai rahasia. Salin ke `.env` (di-ignore Git).
- Tanpa provider AI (`STUDYFLOW_AI_PROVIDER=none`, default), fitur AI mengembalikan 503 dan fitur lain tetap berjalan. `fake` hanya untuk development, dan semua hasilnya berlabel **[Demo]**.
