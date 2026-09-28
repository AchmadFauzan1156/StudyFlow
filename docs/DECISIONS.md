# Catatan keputusan

Format: keputusan — alasan. Keputusan produk utama mengikuti spesifikasi arsitektur; yang dicatat di sini adalah pilihan implementasi dan asumsi.

## Fase 0–1

1. **Struktur modul `shared` + `androidApp` + `desktopApp`** — AGP 9 mewajibkan entry point Android dipisah dari modul KMP (plugin `com.android.kotlin.multiplatform.library`). Tidak ada modul Gradle tambahan; fitur dikelompokkan per package di `commonMain`.
2. **Room 2.8.5, bukan Room 3** — Room 3 masih alpha pada saat bootstrap.
3. **Semua entity memakai UUID string, kolom `version`, `deleted_at` (tombstone), dan `pending_sync`** — menyiapkan sinkronisasi fase 2 tanpa migrasi skema. `version = 0` berarti belum pernah tersinkron.
4. **Outbox ditulis sejak fase 1** — setiap mutasi lokal dan entri `local_outbox` ditulis dalam satu transaksi (`useWriterConnection { immediateTransaction { … } }`). Outbox belum dikirim ke server; jumlahnya terlihat di Pengaturan.
5. **Hapus mata kuliah → tombstone course, catatan, dan tugasnya; outbox hanya berisi mutasi DELETE course** — server di fase 2 wajib menerapkan cascade yang sama agar mutasi anak tidak ganda.
6. **Update tanpa perubahan tidak menulis outbox** — mengurangi mutasi dan potensi konflik yang tidak perlu.
7. **Deadline = instant UTC (`due_at`, epoch ms) + `time_zone_id` saat dibuat** — makna deadline tidak bergeser saat perangkat pindah zona. Editor menampilkan jam pada zona asal tugas; daftar dan status ("Hari ini", "Segera", "Terlambat") dihitung pada zona perangkat saat ini. Jika zona perangkat tidak dapat dibaca, default `Asia/Jakarta`.
8. **"Segera" = ≤ 3 hari kalender** — ambang UI yang dapat diubah (`DUE_SOON_DAYS`).
9. **Mode lokal memakai database `studyflow-local.db`** — database per akun dibuat di fase 2 agar data akun berbeda tidak tercampur. Desktop menyimpan di `%APPDATA%\StudyFlow` (Windows) atau `~/.studyflow`.
10. **Preferensi tema disimpan di tabel `app_settings` Room** — tidak menambah library DataStore/Settings.
11. **Layout adaptif: < 600 dp bottom navigation, ≥ 600 dp sidebar permanen** — satu breakpoint cukup untuk Android ponsel dan desktop.
12. **Locale desktop disetel ke `id-ID`** — komponen bawaan Material (date picker) mengikuti locale JVM; Android memakai locale perangkat.
13. **`compileSdk 37`, `targetSdk 36`** — Compose 1.12.1 mensyaratkan compileSdk 37; targetSdk tidak dinaikkan agar perilaku runtime tidak berubah tanpa pengujian.
14. **Backend: provider AI dipilih lewat `STUDYFLOW_AI_PROVIDER` (`none` | `fake`)** — `none` membuat fitur AI mengembalikan 503 sementara fitur lain tetap jalan. `fake` hanya diterima jika `STUDYFLOW_APP_ENV` bukan `production` (divalidasi saat konfigurasi dimuat dan saat provider dibuat) dan semua output-nya berlabel `[Demo]` serta `is_demo = true`.
15. **Validasi struktur kuis ditulis sekarang** — fake provider diuji dengan validator yang sama yang akan dipakai provider nyata di fase 3.
16. **Request ID selalu dibuat server** — header `X-Request-ID` dari klien diabaikan agar log tidak bisa disisipi nilai dari luar.
17. **Belum ada Dockerfile, SQLAlchemy, atau Alembic** — ditambahkan saat ada database/deployment (fase 2 dan 6) supaya tidak ada dependency yang belum dipakai.
18. **File instruksi/konfigurasi asisten AI (`.claude/`, `CLAUDE.md`, `AGENTS.md`, `.cursor/`, dll.) di-ignore Git** — sesuai permintaan pemilik repo.
