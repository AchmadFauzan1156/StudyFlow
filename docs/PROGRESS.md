# Progres

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
