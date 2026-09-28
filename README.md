# StudyFlow

Aplikasi belajar pribadi untuk mahasiswa: simpan materi per mata kuliah, kelola deadline, sinkronkan antar perangkat, buat rangkuman dan kuis dengan AI, serta tanya isi PDF materi dengan rujukan halaman yang dapat diperiksa.

- **Aplikasi**: Kotlin Multiplatform + Compose Multiplatform (Android dan desktop Windows).
- **Backend**: Python + FastAPI + PostgreSQL (Supabase untuk Auth dan database terkelola).

Status saat ini: **fase 0–4 selesai**:
- Mode lokal tanpa akun.
- Akun Supabase dengan sinkronisasi offline-first (outbox, konflik, isolasi per akun).
- Rangkuman dan kuis pilihan ganda dari catatan memakai **Google Gemini (free tier)** dengan kuota harian, beserta riwayat skor yang bisa dibaca offline.
- Unggah PDF berbasis teks dan **tanya materi** dengan sumber (judul dan halaman) yang dipetakan server.

Rincian di [docs/PROGRESS.md](docs/PROGRESS.md), rencana di [docs/PLAN.md](docs/PLAN.md).

## Struktur

| Lokasi | Isi |
|---|---|
| `shared/` | UI bersama, ViewModel, domain, repository, Room, klien Auth/sinkronisasi (commonMain); adapter Android dan desktop |
| `androidApp/` | Entry point Android |
| `desktopApp/` | Entry point desktop dan packaging |
| `backend/` | FastAPI (API) + worker AI dalam satu codebase; migrasi Alembic; provider AI (Gemini, OpenAI-compatible, demo); dokumen PDF dan RAG (pgvector) |
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

### Mengaktifkan AI (Google Gemini, free tier)

1. Buat API key sendiri di https://aistudio.google.com/apikey. Jangan tempel key ke chat atau commit.
2. Isi di `backend/.env`:
   ```properties
   STUDYFLOW_AI_PROVIDER=gemini
   STUDYFLOW_EMBEDDING_PROVIDER=gemini
   STUDYFLOW_AI_API_KEY=<key kamu>
   ```
   - Model generasi default: `gemini-3.8-flash` (`generateContent` dengan output JSON sesuai schema).
   - Embedding: `gemini-embedding-001` (768 dimensi). Key yang sama dipakai untuk keduanya.
3. Jalankan API dan **worker** (proses terpisah; worker yang memanggil AI dan memproses PDF):
   ```powershell
   uv run uvicorn app.main:app --port 8000
   uv run python -m app.worker
   ```
4. Kalibrasi ambang relevansi tanya materi dengan fixture evaluasi (memakai embedding Gemini sungguhan):
   ```powershell
   uv run python -m app.eval.rag_eval tests/fixtures/rag_eval.json
   ```
   Laporan ini menampilkan hit@k dan jarak pertanyaan relevan vs di luar materi; atur `STUDYFLOW_RAG_MAX_DISTANCE` di antaranya.

**Penting (ketentuan free tier Gemini):** Google dapat memakai isi yang dikirim beserta hasilnya untuk meningkatkan produknya, dan peninjau manusia dapat membacanya. Google juga meminta agar informasi sensitif, rahasia, atau pribadi tidak dikirim, dan layanan ini untuk usia 18+. Aplikasi menampilkan pemberitahuan ini dan meminta persetujuan sebelum catatan, pertanyaan, atau PDF dikirim. Pengecualian: di EEA, Swiss, dan UK berlaku ketentuan layanan berbayar.

Pembatasan agar tetap gratis (semua dapat diubah di `.env`; batas free tier per project terlihat di Google AI Studio):
- 10 permintaan AI per pengguna per hari, dan 200 per hari untuk seluruh server.
- 20 unggahan PDF per pengguna per hari, dengan kuota terpisah dari permintaan AI.
- Maksimal 12.000 karakter per catatan. PDF maksimal 10 MB dan 100 halaman. Yang melebihi ditolak dengan jelas, tidak dipotong diam-diam.
- Jeda minimal 6 detik antar panggilan AI di worker.
- Jika penyedia membalas 429 atau timeout, job dijadwalkan ulang (maks. 3 percobaan).
- Kuota dikembalikan jika job gagal bukan karena pengguna, atau jika pertanyaan tidak punya konteks di materi (model tidak dipanggil).

Alternatif: Groq (`STUDYFLOW_AI_PROVIDER=openai_compatible`, default `openai/gpt-oss-120b`) untuk generasi. Groq tidak menyediakan embedding, jadi `STUDYFLOW_EMBEDDING_PROVIDER` tetap `gemini`, dengan `STUDYFLOW_EMBEDDING_API_KEY` terpisah.

Untuk mencoba tanpa API key: `STUDYFLOW_AI_PROVIDER=fake` dan `STUDYFLOW_EMBEDDING_PROVIDER=fake`. Hasilnya berlabel **[Demo]** dan tidak memakai AI.

### Penyimpanan PDF

- **Development:** `STUDYFLOW_STORAGE_BACKEND=local` menyimpan berkas di `backend/.storage/` (di-ignore Git).
- **Supabase:** buat bucket **privat** `documents`, lalu isi:
  ```properties
  STUDYFLOW_STORAGE_BACKEND=supabase
  STUDYFLOW_SUPABASE_SERVICE_KEY=<secret key, hanya di backend>
  ```
  Berkas hanya diakses lewat backend setelah pemeriksaan kepemilikan.

### Tes backend

```powershell
cd backend
uv run pytest                                    # tes unit (tanpa database)
$env:STUDYFLOW_TEST_DATABASE_URL = "postgresql+psycopg://studyflow:studyflow-dev@localhost:54329/studyflow_test"
uv run pytest                                    # + tes integrasi PostgreSQL
uv run ruff check .
```

### Tes end-to-end dua perangkat

Jalankan backend dan worker dengan JWT HS256 uji dan provider demo (tanpa API key), lalu tes Kotlin memakai secret yang sama:

```powershell
cd backend
$env:STUDYFLOW_DATABASE_URL = "postgresql+psycopg://studyflow:studyflow-dev@localhost:54329/studyflow"
$env:STUDYFLOW_SUPABASE_URL = "http://auth.test.local"
$env:STUDYFLOW_JWT_HS256_SECRET = "e2e-secret-with-at-least-32-bytes-00000"
$env:STUDYFLOW_AI_PROVIDER = "fake"
$env:STUDYFLOW_EMBEDDING_PROVIDER = "fake"
$env:STUDYFLOW_AI_MIN_INTERVAL_SECONDS = "0"
$env:STUDYFLOW_RAG_MAX_DISTANCE = "0.9"
uv run alembic upgrade head
Start-Process uv -ArgumentList "run","python","-m","app.worker"
uv run uvicorn app.main:app --port 8766
```

```powershell
$env:STUDYFLOW_E2E_API_URL = "http://127.0.0.1:8766"
$env:STUDYFLOW_E2E_JWT_SECRET = "e2e-secret-with-at-least-32-bytes-00000"
$env:STUDYFLOW_E2E_AI = "1"
.\gradlew.bat :shared:jvmTest --tests "*TwoDeviceE2ETest*" --rerun-tasks
```

Tanpa variabel `STUDYFLOW_E2E_*`, tes E2E dilewati.

## Konfigurasi dan rahasia

- Kunci AI, kredensial database, dan service-role key hanya ada di backend/worker.
- `backend/.env.example` berisi semua variabel yang dikenal, tanpa nilai rahasia. Salin ke `.env` (di-ignore Git).
- Tanpa provider (`STUDYFLOW_AI_PROVIDER=none` / `STUDYFLOW_EMBEDDING_PROVIDER=none`, default), fitur AI dan unggah PDF mengembalikan 503 dan fitur lain tetap berjalan. `fake` hanya untuk development, dan semua hasilnya berlabel **[Demo]**.
