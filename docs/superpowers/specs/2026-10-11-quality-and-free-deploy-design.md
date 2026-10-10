# Spesifikasi: peningkatan kualitas dan deploy gratis

Tanggal: 11 Oktober 2026. Status: disetujui pemilik.

Dokumen dan aplikasi tidak menyebut nama orang atau kata "teman"; penerima disebut "pengguna".

## Tujuan

StudyFlow (portofolio) dirapikan dan diberi jaring pengaman tes, lalu dibuat bisa dipakai orang lain tanpa biaya: installer Windows `.exe` dan APK Android yang langsung terhubung ke backend online gratis.

**Kriteria sukses**
- Tidak ada fitur yang hilang. Semua tes lama tetap lulus.
- Lint Kotlin berjalan di CI.
- Alur utama aplikasi tercakup tes UI otomatis.
- Alur backend terverifikasi lewat Playwright, baik lokal maupun online.
- Pengguna lain bisa memasang `.exe` atau APK, mendaftar, lalu memakai akun, sinkronisasi, AI, dan PDF.

**Batasan**
- Tanpa biaya. Render, Supabase, dan Gemini memakai paket gratis.
- Commit tanpa atribusi Claude.
- Rahasia tidak masuk ke repo atau chat.
- Perubahan dikerjakan bertahap dengan urutan 3 → 2 → 1 → 4 → 5 → 6.

## Item 3: tooling kualitas

- Plugin Gradle ktlint dipasang untuk `shared`, `androidApp`, dan `desktopApp`. Fungsi `@Composable` boleh diawali huruf besar (aturan nama fungsi diatur lewat `.editorconfig`).
- Format ulang seluruh kode Kotlin masuk **satu commit tersendiri** yang hanya berisi format, tanpa perubahan perilaku.
- `ktlintCheck` ditambahkan ke job `app` di CI.
- `desktopApp/bin/` (output IDE) dimasukkan ke `.gitignore`.
- Kalau plugin ktlint tidak kompatibel dengan Gradle 9.6 atau Kotlin 2.4, kita pakai Spotless + ktlint. Keputusannya dicatat di `docs/DEPENDENCIES.md`.

## Item 2: tes otomatis aplikasi

- **Tes ViewModel** (`jvmTest`, Room in-memory atau berkas sementara, `AppClock` tiruan): CoursesViewModel, HomeViewModel, TaskEditorViewModel, NoteEditorViewModel, ProgressViewModel.
- **Compose UI test** (`runComposeUiTest`, dependensi `compose.uiTest`) dengan modul Koin uji (mode lokal, database sementara):
  1. Tambah mata kuliah, lalu mata kuliah itu muncul di daftar.
  2. Buka mata kuliah, tambah catatan, lalu catatan tersimpan dan tampil.
  3. Tambah tugas dengan deadline, lalu tugas muncul di Beranda. Tandai selesai, lalu statusnya berubah.
  4. Navigasi: sidebar pada lebar ≥ 600 dp dan bottom navigation pada lebar < 600 dp. Ctrl+1..4 berpindah ke menu yang benar.
- Elemen penting diberi `testTag` seperlunya. Teks dan tampilan tidak berubah.

## Item 1: refactor struktur UI (tanpa perubahan perilaku)

- `App.kt`: sidebar (header, daftar mata kuliah yang muncul saat hover, sorotan mata kuliah yang terbuka) dipindah ke `ui/navigation/Sidebar.kt`, pintasan keyboard ke `ui/navigation/Shortcuts.kt`, dan bottom bar ke file sendiri. `App()` hanya merangkai. Import dirapikan.
- `StudyScreens.kt`, `MaterialsScreens.kt`, dan `MaterialsViewModels.kt` dipecah satu file per layar atau ViewModel.
- Refactor dianggap aman kalau tes item 2 tetap lulus dan diff-nya hanya memindahkan kode.

## Item 4: Playwright untuk backend lokal

- Jalankan `docker compose -f backend/docker-compose.demo.yml up --build`.
- Playwright MCP memverifikasi:
  - `/health` (status ok, `ai_mode` demo).
  - Swagger `/docs` termuat tanpa error console.
  - Alur HTTP lewat `browser_evaluate`/fetch dengan token demo HS256: push/pull sinkronisasi, buat job rangkuman, lalu hasil berlabel `[Demo]` muncul.
- Ini verifikasi manual-otomatis, bukan bagian CI. Hasilnya dicatat di `docs/PROGRESS.md`.

## Item 5: backend online gratis

**Platform:** Render Free (web service Docker), Supabase Free (Auth, Postgres + pgvector, Storage bucket privat `documents`), dan Gemini free tier. Kalau Render ternyata mewajibkan kartu dan pemilik menolak, cadangannya Hugging Face Spaces (Docker), dengan syarat masih gratis.

**Perubahan kode**
- Setting baru `STUDYFLOW_EMBEDDED_WORKER` (default `false`). Kalau `true`, lifespan FastAPI menjalankan `Worker` di thread daemon. Thread itu memakai `threading.Event` sebagai sinyal berhenti, dan sinyal itu dikirim saat shutdown.
- `Worker.run_forever` menerima parameter `stop` opsional. Perilaku lama, tanpa `stop`, tetap sama.
- Inisialisasi provider, embedder, dan storage yang sekarang ada di `app/worker/__main__.py` dipindah ke fungsi bersama, lalu dipakai oleh `python -m app.worker` maupun mode tertanam.
- Skrip `backend/docker/start.sh`: `alembic upgrade head`, lalu `uvicorn` di port `${PORT:-8000}`. CMD default Dockerfile tidak berubah.
- `render.yaml` (blueprint): satu web service Docker gratis, `dockerCommand` memakai `start.sh`, health check `/health`, dan env sensitif ditandai `sync: false` supaya diisi lewat dashboard.
- Mode dua proses yang sudah ada tetap didukung.

**Catatan operasional**
- Koneksi database memakai URL **pooler Supabase (session mode)** karena koneksi langsung Supabase memakai IPv6.
- Disk Render tidak permanen, jadi `STUDYFLOW_STORAGE_BACKEND=supabase` wajib.
- Layanan tidur setelah sekitar 15 menit tanpa trafik dan butuh sekitar 1 menit untuk bangun. Worker ikut tidur, dan job yang tertunda diproses setelah layanan bangun. Ini dijelaskan di panduan.
- Konfirmasi email Supabase dimatikan, atau batas email gratis dijelaskan di panduan.

**Tes**
- Unit test mode tertanam: thread mulai saat startup, berhenti saat shutdown, dan job dibuat lewat API lalu selesai tanpa proses worker terpisah.
- Uji `start.sh` lewat build Docker lokal.
- Setelah online, Playwright memverifikasi `https://<service>.onrender.com/health` dan `/docs`.

**Konfigurasi lewat browser pemilik (Claude in Chrome)**
- Pemilik sendiri yang login dan mendaftar. Password atau data kartu tidak diisi oleh Claude.
- Claude menyalin key antar-dashboard dan meminta konfirmasi sebelum membuat atau menghapus layanan.
- Panduan langkah untuk pemilik ditulis di `docs/DEPLOYMENT.md` (bagian "Deploy gratis").

## Item 6: installer untuk pengguna

- **Windows:** `:desktopApp:packageExe` (jpackage). Butuh WiX Toolset v3 (gratis), yang dipasang di mesin pemilik setelah izin. URL Supabase dan Render tertanam saat build.
- **Android:** `:androidApp:assembleRelease` dengan keystore milik pemilik, di luar repo.
- Kedua berkas dibagikan manual (Drive atau WhatsApp). Panduan instalasi untuk pengguna, termasuk peringatan SmartScreen dan "Instal aplikasi tidak dikenal", ditulis di `docs/DEMO.md` atau berkas baru `docs/INSTALL.md`.
- Verifikasi: pasang `.exe` hasil build di mesin pemilik, daftar akun baru, buat catatan, lalu catatan muncul di perangkat kedua (APK di emulator).

## Di luar cakupan

Target Wasm/web, Play Store, sertifikat code signing berbayar, notifikasi OS, dan OCR.
