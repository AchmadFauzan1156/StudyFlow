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

## Fase 2

19. **Klien memanggil REST Supabase Auth lewat Ktor, tanpa SDK Supabase** — sesuai spesifikasi, dan cukup untuk `signup`, `token` (password/refresh), dan `logout`. Hanya publishable key yang ada di aplikasi.
20. **Backend memverifikasi JWT lewat JWKS (ES256/RS256)** — rekomendasi Supabase untuk signing key asimetris. HS256 hanya aktif jika `STUDYFLOW_JWT_HS256_SECRET` diisi (proyek legacy atau tes). `aud` bisa dikonfigurasi dengan default `authenticated`, karena dokumentasi JWT Supabase tidak menyebut nilainya secara eksplisit.
21. **Database per akun di klien (`studyflow-user-<id>.db`), mode lokal tetap di `studyflow-local.db`** — pergantian akun cukup berpindah file; data akun lain tidak pernah dimuat. Logout menyembunyikan database akun tetapi tidak menghapusnya, supaya perubahan yang belum terkirim tidak hilang. Preferensi tema tetap di database lokal karena berlaku per perangkat.
22. **Penyimpanan sesi**: Android memakai Keystore AES-GCM (EncryptedSharedPreferences sudah deprecated), Windows memakai DPAPI via JNA, OS desktop lain memory-only. Yang disimpan hanya refresh token dan user id; access token diambil ulang lewat refresh. Refresh token hasil rotasi selalu disimpan.
23. **Sesi dipulihkan tanpa jaringan** — setelah aplikasi dibuka, data akun langsung bisa dipakai offline. Jika refresh token dicabut, status menjadi "Sesi berakhir" tanpa menghapus data.
24. **Server menyimpan satu baris `sync_changes` per entity (di-upsert)** — pull mengembalikan state terbaru tiap entity sekali saja, sehingga cursor tidak pernah "kedaluwarsa" karena log dipangkas. Cursor tidak valid tetap memicu full resync di klien.
25. **Serialisasi per pengguna dengan `SELECT … FOR UPDATE` pada `sync_counters`** — implementasi awal yang disarankan spesifikasi. Nomor `seq` mengikuti urutan commit, sehingga pull tidak melewatkan transaksi yang commit belakangan. Diuji dengan 4 thread paralel.
26. **Versi dicek sebelum validasi induk** — update catatan berbasis versi lama setelah mata kuliahnya dihapus menghasilkan `conflict` (klien menerima salinan server yang terhapus), bukan `parent_not_found`.
27. **Klien mengirim satu mutasi per entity per batch, lalu me-rebase mutasi berikutnya ke versi hasil `applied`** — perubahan beruntun offline (create → update) tidak dianggap konflik. Entri yang sudah pernah dikirim tidak diubah, supaya hash payload untuk retry tetap sama.
28. **Konflik ditahan di tabel lokal `sync_conflicts` sampai pengguna memilih** — "Pakai versi saya" mengirim UPDATE/DELETE baru di atas versi server; "Pakai versi server" menimpa lokal dan membuang mutasi tertahan. Pull tidak pernah menimpa entity yang masih punya outbox atau konflik.
29. **Pull diterapkan dalam satu transaksi setelah semua halaman diterima, mata kuliah lebih dulu** — mencegah pelanggaran FK lokal saat catatan datang sebelum induknya, dan cursor hanya maju jika semua perubahan tersimpan.
30. **Mutasi `rejected` dibuang (tidak diulang tanpa akhir)** — penyebabnya permanen (induk dihapus, ID milik akun lain, payload tidak valid).
31. **Impor data mode lokal memakai UUID v8 turunan SHA-256 dari (user_id, id lokal)** — impor ulang ke akun yang sama tidak menggandakan data, dan akun berbeda mendapat ID berbeda (ID entity unik global di server). Data mode lokal tidak dihapus. Tawaran impor muncul sekali per akun, dan tombol impor tetap tersedia di Pengaturan.
32. **Pemicu sinkronisasi**: saat masuk, 1,5 detik setelah outbox berubah, setiap 5 menit saat aplikasi terbuka, dan manual. Kegagalan jaringan dicoba ulang dengan backoff 5 detik sampai maksimal 5 menit. Sinkronisasi background saat aplikasi tertutup berada di luar scope.
33. **Konfigurasi server aplikasi dibuat saat build (`AppConfig`) dari `local.properties`/environment** — nilai publik, bukan rahasia. Nilai kosong berarti mode lokal saja.
34. **Build debug Android mengizinkan cleartext hanya ke `10.0.2.2`/`localhost`/`127.0.0.1`** — untuk backend lokal; build release tetap HTTPS-only.
35. **Migrasi Room 1 → 2 memakai AutoMigration** — hanya menambah tabel `sync_conflicts`. Diuji dengan membuka file database skema v1 yang berisi data.
