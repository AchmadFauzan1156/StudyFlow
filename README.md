<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/png/horizontal/studyflow-horizontal-on-dark-800.png">
    <img alt="StudyFlow" src="docs/brand/png/horizontal/studyflow-horizontal-800.png" width="360">
  </picture>
</p>

# StudyFlow

Aplikasi belajar pribadi untuk mahasiswa. Simpan catatan per mata kuliah, kelola deadline tugas, sinkronkan antar perangkat, buat rangkuman dan kuis dengan AI, dan tanyakan isi PDF materi dengan rujukan halaman yang bisa diperiksa.

| Bagian | Teknologi |
|---|---|
| Aplikasi (Android dan desktop Windows) | Kotlin Multiplatform + Compose Multiplatform |
| Backend (API dan worker AI) | Python + FastAPI + PostgreSQL (pgvector) |
| Akun dan database terkelola | Supabase |
| AI | Google Gemini (free tier), atau API kompatibel OpenAI |

**Ingin langsung memakai aplikasinya?** Unduh installer Windows (`.exe`) atau APK Android dari [Releases](https://github.com/AchmadFauzan1156/StudyFlow/releases/latest); langkahnya ada di [docs/INSTALL.md](docs/INSTALL.md). Aplikasi rilis sudah terhubung ke server online gratis.

## Daftar isi

1. [Fitur](#fitur)
2. [Tech stack dan versi](#tech-stack-dan-versi)
3. [Mulai cepat: mode lokal](#mulai-cepat-mode-lokal)
4. [Prasyarat](#prasyarat)
5. [Perintah aplikasi](#perintah-aplikasi)
6. [Menjalankan backend](#menjalankan-backend)
7. [Mengaktifkan akun dan sinkronisasi](#mengaktifkan-akun-dan-sinkronisasi)
8. [Mengaktifkan AI](#mengaktifkan-ai)
9. [Penyimpanan PDF](#penyimpanan-pdf)
10. [Tes](#tes)
11. [Masalah yang sering muncul](#masalah-yang-sering-muncul)
12. [Konfigurasi dan rahasia](#konfigurasi-dan-rahasia)
13. [Struktur proyek dan dokumen lain](#struktur-proyek-dan-dokumen-lain)

## Fitur

Semua fase yang direncanakan (0–6) sudah selesai.

- **Mode lokal tanpa akun.** Mata kuliah, catatan, dan tugas tersimpan di perangkat dan bisa dipakai tanpa internet.
- **Akun dan sinkronisasi offline-first.** Perubahan disimpan dulu di perangkat, lalu dikirim saat online. Konflik antar perangkat ditampilkan untuk dipilih, dan data tiap akun terpisah.
- **Rangkuman dan kuis pilihan ganda** dari catatan, dengan kuota harian dan riwayat skor yang bisa dibaca offline.
- **Tanya materi.** Unggah PDF berbasis teks, ajukan pertanyaan, dan dapatkan jawaban beserta sumbernya (judul dan halaman).
- **Progres belajar.** Penguasaan per topik, rekomendasi latihan, dan latihan kartu (Leitner) dari soal yang pernah dijawab salah.
- **Siap dirilis.** Docker untuk API dan worker, paket Android dan desktop, serta CI dengan pemindai rahasia.

## Tech stack dan versi

Versi aplikasi: **0.2.1** (`studyflow.version` di `gradle.properties`). Versi library aplikasi diatur di `gradle/libs.versions.toml`; versi backend dikunci di `backend/uv.lock`. Alasan pemilihan dan cara memperbarui ada di [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md).

### Aplikasi (Android dan Windows)

| Komponen | Versi | Kegunaan |
|---|---|---|
| Kotlin (Multiplatform) | 2.4.20 | Bahasa dan kode bersama Android + desktop |
| Compose Multiplatform | 1.12.1 | UI bersama |
| Material 3 (Compose) | 1.9.0 | Komponen UI |
| Material Icons (core) | 1.7.3 | Ikon |
| Compose Resources | 1.12.1 | Font dan ikon yang dibundel |
| AndroidX Navigation Compose | 2.9.2 | Navigasi antar layar |
| AndroidX Lifecycle (ViewModel) | 2.11.0 | ViewModel dan lifecycle |
| AndroidX Activity Compose | 1.13.0 | Entry point Android |
| Room (KMP) + SQLite bundled | 2.8.5 / 2.6.2 | Database lokal offline-first |
| KSP | 2.3.12 | Generator kode Room |
| Koin | 4.2.2 | Dependency injection |
| Ktor Client | 3.6.0 | HTTP ke backend dan Supabase Auth |
| kotlinx.coroutines | 1.11.0 | Asinkron |
| kotlinx.serialization | 1.11.0 | JSON |
| kotlinx-datetime | 0.8.0 | Tanggal, jam, zona waktu |
| JNA | 5.19.1 | Penyimpanan sesi terenkripsi (DPAPI) di Windows |
| Font Plus Jakarta Sans | 2.7.1 (OFL) | Tipografi aplikasi dan logo |

| Build dan target | Versi |
|---|---|
| Gradle | 9.6.0 |
| Android Gradle Plugin | 9.4.1 |
| JDK untuk build | 17+ (rilis 0.2.0 dibuat dengan JDK 21; bytecode target 17) |
| Android | minSdk 26 (Android 8.0), targetSdk 36, compileSdk 37 |
| Paket desktop | jpackage (Compose Desktop) + WiX v3 untuk installer `.exe`/`.msi` |
| Lint | ktlint-gradle 14.2.0 (gaya `intellij_idea`) |
| Tes | kotlin.test, Compose UI Test 1.12.1, Ktor MockEngine |

### Backend (API dan worker AI)

| Komponen | Versi | Kegunaan |
|---|---|---|
| Python | ≥ 3.12 (image Docker: 3.13) | Runtime |
| FastAPI (`fastapi[standard]`) | 0.141.1 | API HTTP |
| Uvicorn | 0.54.0 | Server ASGI |
| Pydantic / pydantic-settings | 2.13.5 / 2.15.0 | Validasi dan konfigurasi |
| SQLAlchemy | 2.1.1 | ORM |
| Alembic | 1.20.0 | Migrasi database |
| psycopg (binary) | 3.3.6 | Driver PostgreSQL |
| pgvector (Python) | 0.5.0 | Kolom vektor untuk pencarian materi |
| PyJWT (crypto) | 2.15.0 | Verifikasi token Supabase (JWKS ES256) |
| pypdf | 6.19.0 | Ekstraksi teks PDF |
| httpx2 | 2.13.1 | HTTP ke Gemini dan Supabase Storage |
| python-multipart | 0.0.32 | Unggah berkas |
| uv | 0.12.19 | Manajer dependency dan lingkungan |
| pytest / ruff | 9.1.1 / 0.16.9 | Tes dan lint |
| fastapi-cloud-cli | 0.26.0 | Deploy ke FastAPI Cloud |

### Layanan dan infrastruktur

| Layanan | Paket | Kegunaan |
|---|---|---|
| FastAPI Cloud | Hobby (gratis) | Hosting API + worker tertanam |
| Supabase | Free | Auth (email/kata sandi), PostgreSQL + pgvector, Storage PDF privat |
| Google Gemini API | Free tier | `gemini-3.8-flash` (rangkuman, kuis, jawaban), `gemini-embedding-001` (768 dimensi) |
| Docker | — | `pgvector/pgvector:pg17` untuk pengembangan dan tes integrasi |
| GitHub Actions | — | CI: gitleaks, ruff + pytest backend, ktlint + tes aplikasi + build Android/desktop |
| GitHub Releases | — | Distribusi `.exe`, ZIP portabel, dan APK |

## Mulai cepat: mode lokal

Cara tercepat untuk mencoba aplikasi. Tidak perlu backend, akun, atau API key.

```powershell
git clone https://github.com/AchmadFauzan1156/StudyFlow.git
cd StudyFlow
.\gradlew.bat :desktopApp:run
```

Build pertama mengunduh Gradle dan dependensi, jadi bisa memakan waktu beberapa menit. Di macOS/Linux, ganti `.\gradlew.bat` dengan `./gradlew`.

Tanpa konfigurasi server, aplikasi berjalan dalam **mode lokal** dan tombol masuk tidak aktif. Untuk akun, sinkronisasi, dan AI, lanjutkan ke bagian [backend](#menjalankan-backend).

## Prasyarat

| Untuk | Yang dibutuhkan |
|---|---|
| Aplikasi desktop | JDK 17+ (diuji dengan JDK 25). Gradle tidak perlu dipasang; gunakan wrapper. |
| Aplikasi Android | Android SDK dengan platform **API 37**, dan `local.properties` di root proyek. |
| Backend | Python 3.12+, [uv](https://docs.astral.sh/uv/), dan Docker untuk PostgreSQL lokal. |
| Installer Windows (`packageExe`) | Fitur Windows **.NET Framework 3.5** (NetFx3) untuk WiX; WiX v3 diunduh otomatis oleh Gradle. |

`local.properties` biasanya dibuat otomatis oleh Android Studio. Kalau belum ada, buat sendiri:

```properties
sdk.dir=C\:/Users/<nama>/AppData/Local/Android/Sdk
```

Jika platform API 37 belum terpasang tetapi lisensi SDK sudah disetujui, Gradle mengunduhnya sendiri saat build pertama.

## Perintah aplikasi

| Perintah | Hasil |
|---|---|
| `.\gradlew.bat :desktopApp:run` | Menjalankan aplikasi desktop |
| `.\gradlew.bat :androidApp:assembleDebug` | APK debug di `androidApp\build\outputs\apk\debug\` |
| `.\gradlew.bat :androidApp:installDebug` | Memasang APK ke emulator atau perangkat yang terhubung |
| `.\gradlew.bat :shared:jvmTest` | Tes kode bersama (logika, Room, sinkronisasi, sesi) |
| `.\gradlew.bat :androidApp:assembleRelease` | APK rilis (bertanda tangan jika `keystore.properties` ada) |
| `.\gradlew.bat :desktopApp:packageZip` | Paket desktop portabel di `desktopApp\build\dist\` |

Data aplikasi desktop tersimpan di `%APPDATA%\StudyFlow\` (Windows) atau `~/.studyflow/`: `studyflow-local.db` untuk mode lokal dan `studyflow-user-<id>.db` untuk tiap akun.

## Menjalankan backend

Backend terdiri dari dua proses dari satu codebase: **API** dan **worker**. Worker yang memanggil AI dan memproses PDF, jadi tanpa worker permintaan AI hanya mengantre.

```powershell
cd backend
docker compose up -d                 # PostgreSQL (pgvector) di localhost:54329
uv sync                              # pasang dependensi
Copy-Item .env.example .env          # lalu isi nilainya; jangan commit .env
uv run alembic upgrade head          # buat tabel
uv run uvicorn app.main:app --reload --port 8000
```

Di terminal kedua, jalankan worker:

```powershell
cd backend
uv run python -m app.worker
```

Periksa di `http://127.0.0.1:8000/health`. Dokumentasi interaktif ada di `http://127.0.0.1:8000/docs`.

Ingin mencoba semuanya dengan Docker dan tanpa API key? Jalankan `docker compose -f docker-compose.demo.yml up --build` dan ikuti [docs/DEMO.md](docs/DEMO.md).

## Mengaktifkan akun dan sinkronisasi

Ada dua tempat yang perlu diisi: aplikasi dan backend. Nilainya diambil dari dashboard Supabase (**Project Settings > API Keys**, dan tombol **Connect**).

**1. Aplikasi: `local.properties` di root proyek.** Nilai ini bukan rahasia dan dibaca saat build, jadi aplikasi harus di-build ulang setelah diubah.

```properties
studyflow.supabase.url=https://<project-ref>.supabase.co
studyflow.supabase.publishableKey=sb_publishable_...
studyflow.api.baseUrl=http://127.0.0.1:8000
# Opsional, untuk emulator Android yang menjangkau komputer lewat 10.0.2.2:
studyflow.api.baseUrl.android=http://10.0.2.2:8000
```

Setiap baris harus berbentuk `nama=nilai`. Padanan environment variable: `STUDYFLOW_SUPABASE_URL`, `STUDYFLOW_SUPABASE_PUBLISHABLE_KEY`, `STUDYFLOW_API_BASE_URL`, `STUDYFLOW_API_BASE_URL_ANDROID`.

**2. Backend: `backend/.env`.**

```properties
STUDYFLOW_SUPABASE_URL=https://<project-ref>.supabase.co
```

Gunakan URL proyek saja, tanpa `/rest/v1` atau `/auth/v1` di belakangnya.

**3. Database di Supabase (opsional).** Untuk memakai database Supabase alih-alih Docker lokal, isi `STUDYFLOW_DATABASE_URL` dengan connection string proyek (awali dengan `postgresql+psycopg://`), lalu jalankan `uv run alembic upgrade head`. Migrasi mengaktifkan RLS default-deny dan mencabut hak `anon`/`authenticated` pada tabel bisnis; aplikasi hanya mengakses data lewat backend.

Catatan keamanan:

- Jangan pernah menaruh service-role key atau kunci AI di aplikasi.
- Build debug Android mengizinkan HTTP tanpa TLS hanya ke `10.0.2.2`, `localhost`, dan `127.0.0.1`. Build rilis mewajibkan HTTPS.
- Token disimpan terenkripsi: Android Keystore (AES-GCM) dan Windows DPAPI. Di desktop non-Windows sesi hanya disimpan di memori, jadi pengguna perlu masuk lagi setelah aplikasi ditutup.

## Mengaktifkan AI

Default-nya AI mati (`none`): fitur AI dan unggah PDF membalas 503, dan fitur lain tetap berjalan.

### Google Gemini (free tier)

1. Buat API key di https://aistudio.google.com/apikey. Jangan tempel key ke chat atau commit.
2. Isi di `backend/.env`:
   ```properties
   STUDYFLOW_AI_PROVIDER=gemini
   STUDYFLOW_EMBEDDING_PROVIDER=gemini
   STUDYFLOW_AI_API_KEY=<key kamu>
   ```
3. Jalankan ulang API **dan** worker.

| Kegunaan | Model default |
|---|---|
| Rangkuman, kuis, jawaban | `gemini-3.8-flash` (`generateContent` dengan output JSON sesuai schema) |
| Embedding PDF | `gemini-embedding-001` (768 dimensi) |

Key yang sama dipakai untuk keduanya. Model generasi bisa diganti lewat `STUDYFLOW_AI_MODEL`. Mengganti model atau dimensi embedding berarti semua PDF harus diindeks ulang.

**Penting (ketentuan free tier Gemini):** Google dapat memakai isi yang dikirim beserta hasilnya untuk meningkatkan produknya, dan peninjau manusia dapat membacanya. Google juga meminta agar informasi sensitif, rahasia, atau pribadi tidak dikirim, dan layanan ini untuk usia 18+. Aplikasi menampilkan pemberitahuan ini dan meminta persetujuan sebelum catatan, pertanyaan, atau PDF dikirim. Pengecualian: di EEA, Swiss, dan UK berlaku ketentuan layanan berbayar.

### Batas bawaan agar tetap gratis

Semua bisa diubah di `.env`. Batas free tier per project terlihat di Google AI Studio.

| Batas | Nilai default |
|---|---|
| Permintaan AI per pengguna per hari | 10 |
| Permintaan AI untuk seluruh server per hari | 200 |
| Unggahan PDF per pengguna per hari (kuota terpisah) | 20 |
| Panjang catatan | 12.000 karakter |
| Ukuran PDF | 10 MB dan 100 halaman |
| Jeda antar panggilan AI di worker | 6 detik |
| Percobaan ulang saat 429 atau timeout | maksimal 3 |

Yang melebihi batas ditolak dengan pesan jelas, tidak dipotong diam-diam. Kuota dikembalikan jika job gagal bukan karena pengguna, atau jika pertanyaan tidak punya konteks di materi (model tidak dipanggil).

### Pilihan lain

- **Groq atau API kompatibel OpenAI:** `STUDYFLOW_AI_PROVIDER=openai_compatible` (default `openai/gpt-oss-120b`). Groq tidak menyediakan embedding, jadi `STUDYFLOW_EMBEDDING_PROVIDER` tetap `gemini` dengan `STUDYFLOW_EMBEDDING_API_KEY` terpisah.
- **Tanpa API key:** `STUDYFLOW_AI_PROVIDER=fake` dan `STUDYFLOW_EMBEDDING_PROVIDER=fake`. Hasilnya berlabel **[Demo]** dan tidak memakai AI. Hanya untuk development; ditolak saat `STUDYFLOW_APP_ENV=production`.

### Kalibrasi tanya materi

Ambang relevansi diatur dengan fixture evaluasi yang memakai embedding Gemini sungguhan:

```powershell
uv run python -m app.eval.rag_eval tests/fixtures/rag_eval.json
```

Laporan menampilkan hit@k serta jarak pertanyaan relevan dan di luar materi. Atur `STUDYFLOW_RAG_MAX_DISTANCE` di antara keduanya.

## Penyimpanan PDF

- **Development:** `STUDYFLOW_STORAGE_BACKEND=local` menyimpan berkas di `backend/.storage/` (di-ignore Git).
- **Supabase:** buat bucket **privat** bernama `documents`, lalu isi:
  ```properties
  STUDYFLOW_STORAGE_BACKEND=supabase
  STUDYFLOW_SUPABASE_SERVICE_KEY=<secret key, hanya di backend>
  ```
  Berkas hanya diakses lewat backend setelah pemeriksaan kepemilikan.

## Tes

### Backend

```powershell
cd backend
uv run pytest                                    # tes unit (tanpa database)
$env:STUDYFLOW_TEST_DATABASE_URL = "postgresql+psycopg://studyflow:studyflow-dev@localhost:54329/studyflow_test"
uv run pytest                                    # + tes integrasi PostgreSQL
uv run ruff check .
```

Tanpa `STUDYFLOW_TEST_DATABASE_URL`, tes integrasi dilewati.

### End-to-end dua perangkat

Jalankan backend dan worker dengan JWT HS256 uji dan provider demo (tanpa API key):

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

Lalu jalankan tes Kotlin dengan secret yang sama:

```powershell
$env:STUDYFLOW_E2E_API_URL = "http://127.0.0.1:8766"
$env:STUDYFLOW_E2E_JWT_SECRET = "e2e-secret-with-at-least-32-bytes-00000"
$env:STUDYFLOW_E2E_AI = "1"
.\gradlew.bat :shared:jvmTest --tests "*TwoDeviceE2ETest*" --rerun-tasks
```

Tanpa variabel `STUDYFLOW_E2E_*`, tes E2E dilewati.

## Masalah yang sering muncul

| Gejala | Penyebab dan solusi |
|---|---|
| `uv` tidak dikenali | uv belum ada di PATH. Pasang lewat [panduan resminya](https://docs.astral.sh/uv/), atau `python -m pip install --user uv` lalu panggil sebagai `python -m uv ...`. |
| Tombol masuk tidak aktif | `local.properties` belum berisi nilai Supabase, atau barisnya tidak berbentuk `nama=nilai`. Isi, lalu build ulang aplikasi. |
| Backend menolak token setelah berhasil masuk | `STUDYFLOW_SUPABASE_URL` memuat akhiran seperti `/rest/v1/`. Gunakan URL proyek saja. |
| Daftar gagal dengan "email rate limit exceeded" | Email bawaan Supabase hanya beberapa per jam. Untuk development, matikan **Confirm email** di **Authentication > Sign In / Providers > Email**; untuk produksi, pasang SMTP sendiri. |
| `password authentication failed for user "postgres"` | Password di `STUDYFLOW_DATABASE_URL` salah. Reset di **Project Settings > Database** dan perbarui URL-nya. |
| Fitur AI atau unggah PDF membalas 503 | Provider masih `none`, atau key kosong. Lihat [Mengaktifkan AI](#mengaktifkan-ai). |
| Permintaan AI tidak pernah selesai | Worker belum dijalankan. |
| Build Android gagal mencari platform | Platform API 37 belum terpasang dan lisensi SDK belum disetujui. Pasang lewat SDK Manager di Android Studio. |

## Konfigurasi dan rahasia

- Kunci AI, kredensial database, dan service-role key hanya ada di backend dan worker.
- `backend/.env.example` berisi semua variabel yang dikenal, tanpa nilai rahasia. Salin ke `.env` (di-ignore Git).
- `local.properties`, `.env`, dan `keystore.properties` di-ignore Git. Jangan commit ketiganya.

## Struktur proyek dan dokumen lain

| Lokasi | Isi |
|---|---|
| `shared/` | UI bersama, ViewModel, domain, repository, Room, klien Auth dan sinkronisasi (commonMain); adapter Android dan desktop |
| `androidApp/` | Entry point Android |
| `desktopApp/` | Entry point desktop dan packaging |
| `backend/` | FastAPI (API) dan worker AI; migrasi Alembic; provider AI (Gemini, OpenAI-compatible, demo); dokumen PDF dan RAG (pgvector) |
| `docs/` | Rencana, keputusan, dependency, kontrak API, progres |

| Dokumen | Isi |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Memasang aplikasi di Windows dan Android (untuk pengguna) |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Deployment ke server (termasuk deploy gratis FastAPI Cloud + Supabase + Gemini), build rilis, backup |
| [docs/DEMO.md](docs/DEMO.md) | Skrip demo end-to-end tanpa API key |
| [docs/API.md](docs/API.md) | Kontrak API |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Keputusan teknis dan alasannya |
| [docs/PROGRESS.md](docs/PROGRESS.md) | Rincian progres per fase |
| [docs/PLAN.md](docs/PLAN.md) | Rencana fase |
