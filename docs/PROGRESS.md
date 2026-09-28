# Progres

## Checkpoint 6 — Fase 6: rilis demonstrasi (29 September 2026)

### Selesai
- `backend/Dockerfile`: satu image untuk API dan worker, non-root, dependency terkunci. `backend/docker-compose.demo.yml` menjalankan PostgreSQL, migrasi, API, dan worker dengan provider demo.
- `docs/DEPLOYMENT.md`: Supabase (Auth, JWKS, bucket privat, migrasi), menjalankan container, konfigurasi produksi, rilis Android/desktop, backup dan pemulihan, serta CI.
- `docs/DEMO.md` dan `backend/scripts/make_demo_pdfs.py`: skrip demo end-to-end dengan PDF demo berlabel.
- Android: signing release dari `keystore.properties`/environment; tanpa keystore, APK release dibuat unsigned.
- Desktop: task `packageZip` (folder portabel + runtime Java).
- `.gitleaks.toml` dan workflow `.github/workflows/ci.yml`: gitleaks, tes backend dengan pgvector, tes shared, dan build Android/desktop.

### Perintah yang dijalankan dan hasilnya

| Perintah | Hasil |
|---|---|
| `docker compose -f docker-compose.demo.yml up --build -d` | Image terbangun; migrasi `Exited (0)`; API dan worker berjalan; `/health` OK |
| 5 tes E2E Kotlin terhadap stack Docker | Lulus (sinkronisasi, konflik, retry, isolasi, kuis via worker, progres/kartu, PDF → tanya materi); log worker tanpa error |
| gitleaks v8.30.1 pada riwayat git dan folder kerja | Awalnya 7 temuan (semua nilai uji palsu); setelah allowlist sempit: tidak ada kebocoran |
| `gradlew :androidApp:assembleRelease` tanpa/dengan keystore uji | `androidApp-release-unsigned.apk`; dengan keystore sekali pakai → `androidApp-release.apk` terverifikasi `apksigner` (keystore dihapus) |
| `gradlew :desktopApp:packageZip` | `StudyFlow-0.1.0-windows.zip` (±79 MB) |
| `uv run python scripts/make_demo_pdfs.py` | 4 PDF demo (3 materi + 1 scan kosong) |
| CI GitHub Actions | Run pertama gagal (tag `setup-uv@v10` tidak ada; `gradlew` tanpa izin eksekusi). Setelah diperbaiki, run kedua lulus di ketiga job: gitleaks, backend, dan aplikasi. |

### Keterbatasan
- MSI/EXE belum dibuat (butuh WiX Toolset).
- Belum ada deployment nyata; tidak ada deployment berbayar yang dijalankan.
- Verifikasi dengan Supabase dan Gemini sungguhan masih menunggu key milik pemilik proyek (lihat checkpoint 4).
- iOS belum dibangun maupun diuji (butuh macOS/Xcode).

---

## Checkpoint 5 — Fase 5: progres per topik, rekomendasi, flashcard (29 September 2026)

### Selesai
- **Backend**:
  - `GET /v1/progress/topics`: akurasi per topik tanpa soal yang dilaporkan dan jawaban kosong, dengan rekomendasi "perlu latihan" (≥ 5 jawaban dan < 60%, dapat diubah).
  - Flashcard dari soal yang pernah dijawab salah: `GET /v1/flashcards/due` dan `POST /v1/flashcards/{id}/review` (Leitner 1/2/4/8/16 hari).
  - Migrasi `0004` (RLS default-deny).
- **Aplikasi**:
  - Layar Progres menampilkan "Penguasaan per topik" (bilah akurasi, label "Perlu latihan", ketuk untuk membuka catatan sumber di layar belajar), dengan cache offline.
  - Layar **Latihan kartu**: lihat jawaban, lalu "Sudah ingat"/"Belum ingat".

### Perintah yang dijalankan dan hasilnya

| Perintah | Hasil |
|---|---|
| `uv run pytest` dengan PostgreSQL | 110 lulus. 3 tes baru: akurasi/rekomendasi/pengurutan topik, jawaban kosong dan soal dilaporkan tidak dihitung, kartu hanya dari jawaban salah dengan jadwal Leitner, dan isolasi akun. Tes dibuat deterministik dengan memilih pengecoh `[Demo]`, karena percobaan pertama sempat flaky. |
| `gradlew clean :shared:jvmTest --rerun-tasks` (E2E aktif) | 59 lulus; E2E alur kuis kini juga memeriksa progres per topik dan pengurangan kartu setelah review |
| `gradlew :androidApp:assembleDebug :desktopApp:jar` | Berhasil tanpa peringatan di kode proyek |

### Keterbatasan
- UI fase 5 belum diuji manual lewat screenshot (dihemat atas permintaan pemilik proyek). Logika dan API tercakup tes E2E.
- Ambang rekomendasi dan interval Leitner adalah nilai awal yang perlu dievaluasi dengan pengguna.
- Kartu latihan hanya tersedia online.

### Langkah berikutnya (fase 6)
Lihat [PLAN.md](PLAN.md#langkah-fase-6-rinci): Docker, packaging, CI dengan pemindai rahasia, panduan deployment, dan demo end-to-end.

---

## Checkpoint 4 — Fase 4: PDF dan tanya materi, provider Gemini (29 September 2026)

Keputusan pemilik proyek: provider AI diganti dari Groq ke **Google Gemini** (gratis) untuk generasi dan embedding. Rincian di [DECISIONS.md](DECISIONS.md) #49–#64.

### Selesai

**Backend**
- Adapter Gemini native:
  - `gemini-3.8-flash` lewat `generateContent` dengan `responseJsonSchema`.
  - `gemini-embedding-001` 768 dimensi lewat `batchEmbedContents` dengan normalisasi L2.
  - Key lewat header `x-goog-api-key`.
  - Groq tetap tersedia sebagai `openai_compatible`.
- Pemberitahuan data free tier Gemini yang jujur: data dipakai Google, bisa dibaca peninjau manusia, jangan kirim data sensitif, usia 18+.
- Unggah PDF: batas ukuran, signature, enkripsi, jumlah halaman, idempotency, dan kuota unggah terpisah. Storage lokal atau Supabase (bucket privat).
- Worker:
  - Ekstraksi teks per halaman di proses terpisah dengan batas waktu; PDF scan diberi status `unsupported`.
  - Peringatan halaman tanpa teks dan kemungkinan rumus/tabel rusak.
  - Chunking dengan nomor halaman, embedding, dan publikasi atomik yang dijaga lease.
  - Cleanup dokumen yang bisa diulang.
- Tanya materi:
  - Retrieval pgvector yang selalu difilter pengguna, mata kuliah, versi aktif, dan model embedding, dengan ambang jarak.
  - Tanpa konteks, model tidak dipanggil.
  - ID sumber dari model divalidasi; judul dan halaman dipetakan dari database.
- Endpoint dokumen, pratinjau halaman, unduh privat, hapus, cuplikan sumber, thread, dan pesan. Detail di [API.md](API.md).
- Migrasi `0003` (pgvector, dokumen, halaman, potongan, thread, pesan; RLS default-deny).
- Fixture evaluasi RAG (3 materi, 6 pertanyaan, 2 di luar materi) dan `python -m app.eval.rag_eval` untuk kalibrasi ambang dengan Gemini.

**Aplikasi**
- Tab **Materi**:
  - Unggah PDF lewat pemilih berkas (Android `OpenDocument`, desktop dialog berkas) dengan persetujuan pengiriman data.
  - Status (Diproses/Siap/Tidak didukung/Gagal) dengan polling otomatis.
  - "Coba lagi" setelah gagal jaringan memakai Idempotency-Key yang sama.
  - Hapus dengan konfirmasi.
- **Detail dokumen**: pratinjau teks per halaman (bertahap), peringatan ekstraksi, "Buka PDF" (penampil bawaan; Android lewat `FileProvider`), dan hapus.
- **Tanya materi** (dari tab Belajar):
  - Percakapan per mata kuliah, dengan persetujuan sebelum pertanyaan pertama.
  - Indikator saat jawaban diproses, dan label demo.
  - Chip sumber "judul · hal. x–y" yang membuka layar **Sumber** (cuplikan, rentang halaman fisik, Buka PDF).
- Daftar dokumen, pratinjau, pesan, dan cuplikan di-cache per akun sehingga bisa dibaca offline.

### Perintah yang dijalankan dan hasilnya

| Perintah | Hasil |
|---|---|
| `uv run pytest` dengan `STUDYFLOW_TEST_DATABASE_URL` (PostgreSQL + pgvector) | 107 lulus. Termasuk tes unit Gemini (bentuk request, key tidak di URL, bagian thought, MAX_TOKENS/SAFETY/blockReason, 429, batching dan normalisasi embedding, dimensi salah), PDF (signature, ukuran, halaman, enkripsi, rusak, ekstraksi subprocess, deteksi scan), chunking, storage (path traversal, REST Supabase), dan integrasi fase 4 (unggah → index → pratinjau → unduh; idempotensi; 4 jenis penolakan; scan → unsupported; kuota unggah; akses lintas akun 404; jawaban hanya bersumber dari dokumen sendiri meski akun lain punya dokumen serupa; pertanyaan di luar materi tanpa memanggil model dan kuota kembali; ID sumber karangan ditolak setelah satu retry; tanya idempotent; hapus → berkas, teks, dan embedding dibersihkan dan retrieval tidak lagi menemukannya; dokumen dihapus saat indexing tidak dipublikasikan; baseline evaluasi RAG). |
| `uv run ruff check .` | Bersih |
| `gradlew clean :shared:jvmTest --rerun-tasks` (E2E aktif, API + worker provider demo) | 59 lulus, termasuk 5 `MaterialsTest` (multipart + Idempotency-Key, berkas terlalu besar ditolak sebelum dikirim, daftar dokumen offline, sumber terhapus tidak diambil dari cache, mata kuliah harus tersinkron) dan E2E unggah PDF sungguhan → indexing → pratinjau → unduh → tanya → sumber → akun lain ditolak → hapus. |
| `gradlew :androidApp:assembleDebug :desktopApp:jar` | Berhasil tanpa peringatan di kode proyek |
| Uji manual desktop (auth tiruan + API + worker demo) | Tab Materi → Unggah PDF lewat dialog berkas Windows → dialog persetujuan → "Diproses…" berubah menjadi "Siap" → pratinjau 3 halaman → Belajar → Tanya materi → persetujuan → jawaban dengan chip sumber → layar Sumber dengan cuplikan dan halaman |

### Keterbatasan dan hambatan

- **Belum diuji dengan Gemini sungguhan.** API key harus dibuat sendiri oleh pemilik proyek. Adapter mengikuti dokumentasi resmi (dicek 28–29 Sep 2026) dan diuji dengan transport tiruan. Langkah verifikasi:
  1. Isi `STUDYFLOW_AI_PROVIDER=gemini`, `STUDYFLOW_EMBEDDING_PROVIDER=gemini`, dan `STUDYFLOW_AI_API_KEY`.
  2. Jalankan `python -m app.eval.rag_eval`, lalu atur `STUDYFLOW_RAG_MAX_DISTANCE` dari hasilnya.
  3. Uji kuis dan tanya materi pada 2–3 materi contoh dan periksa manual.
- Dokumentasi Gemini menandai `taskType`/`outputDimensionality` tingkat atas sebagai deprecated dan menyarankan `embedContentConfig` (yang dipakai). Jika API menolak field ini, penyesuaiannya ada di `app/providers/gemini.py`.
- Angka batas free tier Gemini tidak tercantum di dokumentasi (hanya di AI Studio); kuota default dibuat konservatif.
- Ekstraksi PDF dibatasi waktu, tetapi tidak dibatasi memori (tidak portabel di Windows).
- Belum ada rangkuman dokumen panjang per bagian, unggah versi baru untuk dokumen yang sama, thread per dokumen di UI, indeks ANN, atau OCR.
- Android: unggah dan buka PDF sudah dikompilasi, tetapi belum diuji manual di emulator pada checkpoint ini.

### Langkah berikutnya (fase 5)

Lihat [PLAN.md](PLAN.md#langkah-fase-5-rinci): progres per topik tanpa soal yang dilaporkan, rekomendasi latihan rule-based, dan flashcard. Sebaiknya kalibrasi RAG dengan Gemini lebih dulu.

---

## Checkpoint 3 — Fase 3: rangkuman dan kuis AI gratis (28 September 2026)

Keputusan pemilik proyek: memakai **model AI gratis** dengan pembatasan. Dipilih Groq free plan (`openai/gpt-oss-120b`); perbandingan dan alasannya di [DECISIONS.md](DECISIONS.md) #36–#48.

### Selesai

**Backend**
- Adapter provider OpenAI-compatible (default Groq) dengan structured output `json_schema` strict.
  - Error provider dikelompokkan menjadi sementara (429/5xx/timeout, dengan `Retry-After`), ditolak (4xx), dan output tidak valid.
  - Isi error provider tidak diteruskan ke pengguna.
- Prompt memperlakukan materi sebagai data (perlindungan prompt injection), dengan `PROMPT_VERSION` tercatat di setiap hasil.
- Migrasi `0002`: `ai_jobs`, `summaries`, `quizzes`, `questions`, `question_keys` (khusus server), `attempts`, `answers`, `question_reports`, `ai_usage_daily`, `usage_records`. Semua tabel memakai RLS default-deny.
- Kuota direservasi atomik (per pengguna dan global) dan dikembalikan untuk kegagalan non-pengguna. Batas panjang materi dan jumlah soal.
- Worker `python -m app.worker`:
  - Klaim job dengan `SKIP LOCKED` dan lease; pemulihan lease yang habis.
  - Output tidak valid dicoba ulang satu kali.
  - Jadwal ulang saat 429/timeout, dan jeda antar panggilan.
  - Hasil hanya ditulis oleh pemegang lease; job gagal jika catatan berubah.
- Endpoint: `/v1/ai/info`, `/v1/summaries`, `/v1/quizzes`, `/v1/jobs/{id}`, attempt dengan penilaian deterministik dan idempotent, riwayat, hasil per catatan, dan laporan soal. Detail di [API.md](API.md).

**Aplikasi**
- Tab **Belajar** di detail mata kuliah: daftar catatan dengan penanda "Belum tersinkron".
- Layar belajar catatan:
  - Kuota harian dan batas karakter, serta tombol rangkuman dan kuis (3/5/10 soal).
  - Persetujuan pengiriman data (teks dari server, sekali per akun per provider).
  - Status job dengan polling berjeda (2–10 detik, berhenti saat layar ditutup).
  - Pesan jelas untuk setiap kode kegagalan, dan daftar hasil dengan skor terbaik serta label versi lama.
- Layar **Rangkuman**: sumber, versi, model, label demo/versi lama.
- Layar **Kuis** (online):
  - Pilih jawaban lalu kirim; retry memakai Idempotency-Key yang sama.
  - Skor, tanda benar/salah, pembahasan, judul sumber, "Tandai soal bermasalah", dan "Kerjakan ulang".
- **Progres**: riwayat kuis dan rata-rata benar. Hasil lama bisa dibuka offline.
- Cache Room `remote_cache` (skema v3, AutoMigration) per akun untuk membaca hasil offline.

### Perintah yang dijalankan dan hasilnya

| Perintah | Hasil |
|---|---|
| `uv run pytest` dengan `STUDYFLOW_TEST_DATABASE_URL` | 68 lulus. Termasuk 14 unit adapter provider (MockTransport: schema strict, material dibungkus, 429 + Retry-After, 5xx, timeout, key salah tanpa bocor, output rusak/terpotong) dan 16 integrasi AI (503 tanpa provider, idempotensi, 404 lintas akun, 409 versi, 413 terlalu panjang, kuota pengguna/global, 8 permintaan paralel tidak melewati kuota, worker menghasilkan rangkuman/kuis tanpa membocorkan kunci, penilaian + attempt ganda, laporan soal, materi berubah → gagal + kuota kembali, label versi lama, retry output tidak valid tepat sekali, 429 → jadwal ulang lalu menyerah, pemulihan lease + worker basi tidak bisa menulis, klaim paralel tidak bentrok, jeda antar panggilan). Tes RLS kini mencakup `ai_jobs`, `question_keys`, `attempts`. |
| `uv run ruff check .` | Bersih |
| `gradlew clean :shared:jvmTest --rerun-tasks` (dengan `STUDYFLOW_E2E_*` dan `STUDYFLOW_E2E_AI=1`) | 53 lulus. 6 `AiRepositoryTest` (kesiapan catatan, cache offline, cache tidak dipakai saat `not_found`, cache dan persetujuan per akun, pesan kuota, Idempotency-Key sama setelah gagal jaringan) dan E2E alur AI melalui API + worker sungguhan (job → kuis → attempt idempotent → riwayat). |
| `gradlew :androidApp:assembleDebug :desktopApp:jar` | Berhasil tanpa peringatan di kode proyek |
| Uji manual desktop (auth tiruan + API + worker, provider demo) | Tab Belajar → layar belajar (kuota 0/10) → kuis 3 soal → dialog persetujuan → "menunggu antrean" → hasil muncul (kuota 1/10) → jawab 1 benar, 1 salah, 1 kosong → skor 1/3 dengan warna dan pembahasan → laporkan soal → riwayat di Progres → API dimatikan → hasil kuis tetap terbuka dengan label "Offline" → rangkuman dibuat dan ditampilkan |

### Keterbatasan dan hambatan

- **Belum diuji dengan Groq sungguhan.** API key harus dibuat sendiri oleh pemilik proyek (tidak boleh dibuat atau diminta lewat chat). Adapter mengikuti dokumentasi resmi Groq (endpoint kompatibel OpenAI, `json_schema` strict untuk `openai/gpt-oss-120b`) dan diuji dengan transport tiruan. Kualitas soal model gratis dalam bahasa Indonesia belum dievaluasi. Langkah verifikasi:
  1. Isi `STUDYFLOW_AI_PROVIDER=openai_compatible` dan `STUDYFLOW_AI_API_KEY`.
  2. Jalankan API dan worker.
  3. Buat kuis dari 2–3 catatan contoh, lalu periksa manual kebenaran soal dan pembahasan.
- Batas token per menit Groq (8K) tidak dihitung per token. Worker memakai jeda 8 detik dan jadwal ulang saat 429; di beban tinggi job bisa tertunda, tetapi tidak hilang.
- Evaluasi semantik (apakah soal benar menurut materi) belum ada; yang tersedia validasi struktur dan tombol "Tandai soal bermasalah". Soal yang dilaporkan belum dikeluarkan dari perhitungan progres (fase 5).
- Status "soal sudah dilaporkan" tidak disimpan di klien setelah layar ditutup; server mengabaikan laporan ganda.
- Rangkuman dokumen panjang (per bagian) dan PDF menunggu fase 4.

### Langkah berikutnya (fase 4)

Lihat [PLAN.md](PLAN.md#langkah-fase-4-rinci). **Keputusan yang dibutuhkan:** Groq tidak menyediakan embedding, jadi untuk RAG gratis perlu dipilih:
- Embedding Gemini (gratis, tetapi datanya dipakai Google).
- Model embedding lokal ringan di worker.
- Mulai dari pencarian full-text PostgreSQL tanpa embedding.

---

## Checkpoint 2 — Fase 2: akun dan sinkronisasi (28 September 2026)

### Selesai

**Backend**
- Validasi access token Supabase: JWKS (ES256/RS256) dengan cache 10 menit, HS256 opsional untuk legacy/tes; `iss`, `aud`, `exp`, dan `sub` wajib; `user_id` hanya dari `sub`.
- `GET /v1/me`, `POST /v1/sync/push`, `GET /v1/sync/pull`. Kontrak lengkap di [API.md](API.md).
- Migrasi Alembic `0001`:
  - Tabel `profiles`, `courses`, `notes`, `tasks`, `sync_counters`, `sync_changes`, `sync_mutations`.
  - FK kepemilikan komposit `(course_id, user_id)`.
  - RLS aktif di semua tabel tanpa policy, dan hak `anon`/`authenticated` dicabut.
- `docker-compose.yml` untuk PostgreSQL lokal (pgvector) beserta role tiruan Supabase.

**Aplikasi**
- Masuk/daftar lewat REST Supabase Auth (Ktor), dengan pesan error berbahasa Indonesia.
- Konfirmasi email didukung.
- Refresh token dengan rotasi; 401 memicu satu kali refresh lalu request diulang.
- Sesi terenkripsi: Android Keystore (AES-GCM), Windows DPAPI. OS lain memory-only.
- Sesi dipulihkan tanpa jaringan; bila refresh token dicabut, status menjadi "Sesi berakhir".
- Database per akun (`studyflow-user-<id>.db`). Logout kembali ke mode lokal tanpa menghapus perubahan yang belum terkirim.
- Mesin sinkronisasi:
  - Outbox dikirim dengan retry idempotent, dan mutasi berikutnya di-rebase setelah `applied`.
  - Konflik ditahan sampai pengguna memilih.
  - Pull atomik dengan urutan induk lebih dulu, tidak menimpa edit tertunda, dan full resync bila cursor tidak valid.
- Pemicu sinkronisasi: saat masuk, setelah edit (debounce 1,5 detik), berkala 5 menit, manual, dan backoff eksponensial saat gagal.
- UI:
  - Layar Masuk/Daftar.
  - Bagian Akun di Pengaturan: status sinkronisasi, perubahan tertunda, Sinkronkan sekarang, Keluar dengan peringatan, impor data lokal.
  - Kartu status akun dan banner konflik di Beranda.
  - Layar konflik ("Pakai versi saya" / "Pakai versi server").
- Impor data mode lokal ke akun (ID stabil per akun, impor ulang tidak menggandakan), ditawarkan sekali setelah masuk.
- Room skema v2 (tabel `sync_conflicts`) dengan AutoMigration dari v1.
- `AppConfig` dibuat saat build dari `local.properties`/environment. Tanpa konfigurasi, aplikasi berjalan di mode lokal.
- Build debug Android mengizinkan HTTP hanya ke host lokal.

### Perintah yang dijalankan dan hasilnya

| Perintah | Hasil |
|---|---|
| `docker compose up -d` + `uv run alembic upgrade head` | Migrasi berhasil; RLS aktif (`relrowsecurity = t`) |
| `uv run pytest` dengan `STUDYFLOW_TEST_DATABASE_URL` | 33 lulus: 14 unit + 19 integrasi PostgreSQL (auth: token salah secret/aud/iss/kedaluwarsa; idempotensi; `mutation_id` dipakai ulang; konflik; payload invalid; cascade; induk wajib; batas batch; isolasi dua akun; role `anon`/`authenticated` ditolak; paging/cursor; cursor invalid; 4 push paralel dengan `seq` unik berurutan) |
| `uv run ruff check .` | Bersih |
| `gradlew clean :shared:jvmTest --rerun-tasks` (dengan `STUDYFLOW_E2E_*`) | 46 lulus: 11 mesin sinkronisasi (MockEngine), 11 sesi/isolasi/impor/DPAPI, 1 migrasi v1→v2, 3 E2E dua perangkat melawan backend + PostgreSQL asli, 20 tes lama |
| `gradlew :androidApp:assembleDebug :desktopApp:jar` | Berhasil, tanpa peringatan compiler di kode proyek |
| Uji manual desktop: dua instance (folder data berbeda) + auth tiruan lokal + backend | Login gagal lalu berhasil; tawaran impor → data lokal tersinkron; perangkat B melihat data A; edit bersamaan → banner konflik → "Pakai versi saya" → B menerima versi A; tutup/buka ulang → sesi pulih (DPAPI + refresh); Keluar → server `/logout`, `session.bin` terhapus, kembali ke data mode lokal |
| Uji manual emulator API 36 | Login via `10.0.2.2`; `force-stop` → sesi pulih (Keystore); mata kuliah baru sampai di server |

Auth tiruan dan data uji hanya dipakai sementara di scratchpad; tidak masuk repo, dan data uji sudah dihapus dari `%APPDATA%` serta database dev.

### Keterbatasan dan hambatan

- **Belum diuji dengan proyek Supabase sungguhan.** Tidak ada kredensial proyek di sesi ini (dan tidak boleh diminta lewat chat). Endpoint dan format token mengikuti dokumentasi resmi Supabase/GoTrue, dan diuji dengan tiruan yang meniru formatnya. Langkah verifikasi saat proyek tersedia:
  1. Isi `local.properties` dan `backend/.env`.
  2. Jalankan `alembic upgrade head`.
  3. Daftar, lalu masuk dari dua perangkat.
- Verifikasi JWKS (ES256/RS256) hanya diuji lewat jalur kode yang sama dengan HS256; belum ada tes dengan kunci asimetris sungguhan.
- Tidak ada sinkronisasi background saat aplikasi tertutup (di luar scope spesifikasi).
- Logout tidak menghapus file database akun (disengaja; lihat DECISIONS #21). Menghapus akun beserta datanya masuk fase berikutnya.
- Masalah watcher folder sesi pada build Gradle (lihat checkpoint 1) masih berlaku; build diverifikasi dari salinan di luar folder kerja.

### Langkah berikutnya (fase 3)

Lihat [PLAN.md](PLAN.md#langkah-fase-3-rinci). **Keputusan yang dibutuhkan darimu sebelum mulai:** provider AI mana yang dipakai (mis. Anthropic, OpenAI, atau Google). Pilihan ini menentukan adapter, model, dan kebijakan retensi data yang ditampilkan ke pengguna. Tanpa keputusan itu, fase 3 tetap bisa dikerjakan dengan fake provider berlabel demo sampai provider dipilih.

---

## Checkpoint 1 — Fase 0 dan 1 (28 September 2026)

### Environment yang diaudit

| Komponen | Temuan |
|---|---|
| OS | Windows 11 Pro 10.0.22621 |
| JDK | Oracle JDK 25.0.2 (PATH); Android Studio JBR 25.0.2. `JAVA_HOME` tidak diset — wrapper memakai `java` dari PATH. |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`: platform 34–37, build-tools 34–36.1, emulator, AVD `Medium_Phone_API_36.1` |
| Gradle | Tidak terpasang global; wrapper 9.6.0 dibuat (checksum dipin) |
| Python | 3.14.3; `uv` 0.12.19 dipasang via `pip install --user uv` |
| Lainnya | Git 2.45, GitHub CLI 2.92, Docker 29.1 |

`AGENTS.md` tidak ada di repository.

### Selesai

**Fase 0 — Bootstrap**
- Matriks versi diverifikasi dan dicatat di [DEPENDENCIES.md](DEPENDENCIES.md).
- Proyek KMP: `shared` (plugin Android-KMP library AGP 9), `androidApp`, `desktopApp`.
- Backend FastAPI: `GET /health`, format error seragam dengan `request_id` buatan server, konfigurasi `STUDYFLOW_*`, interface `AIProvider`, `FakeProvider` deterministik (hanya non-produksi, output berlabel `[Demo]`), schema kuis/rangkuman, validator struktur kuis.
- Dokumentasi: README, PLAN, DECISIONS, DEPENDENCIES, API, `.env.example`.

**Fase 1 — Lokal**
- Room 2.8.5 + SQLite bundled: tabel `courses`, `notes`, `tasks`, `local_outbox`, `app_settings` (skema diekspor ke `shared/schemas/`).
- CRUD mata kuliah, catatan, dan tugas; hapus = tombstone; hapus mata kuliah ikut men-tombstone catatan/tugasnya.
- Setiap mutasi menulis entri outbox (`mutation_id` unik, `base_version`, payload JSON) dalam transaksi yang sama.
- Deadline disimpan sebagai instant UTC + zona waktu; status Terlambat / Hari ini / Segera / Selesai; default tugas baru besok 23.59.
- UI Compose adaptif: bottom navigation (< 600 dp) dan sidebar (≥ 600 dp); Beranda (deadline terdekat), Mata Kuliah, detail dengan tab Catatan/Materi/Tugas/Belajar, editor catatan dan tugas (date/time picker), Progres, Pengaturan (tema Sistem/Terang/Gelap tersimpan, jumlah perubahan menunggu sinkronisasi).
- Tab Materi dan Belajar menampilkan penjelasan bahwa fitur itu memerlukan akun (tidak ada data demo yang dicampur).

### Perintah yang dijalankan dan hasilnya

| Perintah | Hasil |
|---|---|
| `gradlew :shared:jvmTest` | 20 tes lulus (8 repository Room nyata termasuk buka-tutup file DB, 8 deadline/zona waktu, 4 validasi) |
| `gradlew :androidApp:assembleDebug` | Berhasil, `androidApp-debug.apk` ± 19 MB |
| `gradlew :desktopApp:run` | Jendela terbuka; diuji manual dengan input otomatis: tambah mata kuliah, catatan, tugas (date picker), tutup, jalankan ulang → data tetap ada; tema terang; layout sempit menampilkan bottom navigation |
| APK di emulator API 36 (`Medium_Phone_API_36.1`) | Berjalan tanpa crash; tambah mata kuliah → `force-stop` → buka ulang → data tetap ada |
| `uv run pytest` (backend) | 14 tes lulus |
| `uv run ruff check .` | Bersih |
| `uv run uvicorn app.main:app` + `curl /health` | `200 {"status":"ok",…,"ai_mode":"unconfigured"}`; route tak dikenal → 404 dengan format error |

### Keterbatasan dan hambatan

- **iOS tidak dibangun/diuji** — butuh macOS/Xcode (di luar target awal).
- **Belum ada akun, sinkronisasi, dan AI nyata** — sesuai rencana fase 2–3. Outbox terisi tetapi belum dikirim.
- **Build di folder yang sedang diawasi sesi Claude desktop**: Gradle kadang gagal menghapus direktori `build/` (`IOException: Unable to delete directory`) karena watcher memegang handle direktori. Build yang sama dari salinan di luar folder tersebut berhasil konsisten. Tidak terkait kode proyek; jika muncul, tutup watcher/IDE yang mengawasi folder atau jalankan `.\gradlew.bat --stop` lalu hapus folder `build`.
- Uji UI dilakukan manual dengan input otomatis, belum ada tes UI Compose otomatis.
- Label bawaan date picker di Android mengikuti bahasa perangkat.
- Installer MSI/EXE belum dibuat (fase 6).

### Langkah berikutnya (fase 2)

Lihat [PLAN.md](PLAN.md#langkah-fase-2-rinci): SQLAlchemy + Alembic + RLS default-deny, validasi JWT Supabase, `/v1/sync/push` idempotent dan `/v1/sync/pull` berbasis cursor, `SecureSessionStore`, database per akun, UI konflik, serta tes isolasi dua akun. Untuk melanjutkan, tulis **"lanjut"**.
