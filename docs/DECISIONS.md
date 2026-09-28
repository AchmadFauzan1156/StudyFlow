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

## Fase 3

36. **Provider AI default: Groq free plan, model `openai/gpt-oss-120b`** — atas permintaan pemilik proyek agar memakai model gratis. Perbandingan (dokumentasi resmi, 28 Sep 2026):
    - **Groq free plan:** 30 permintaan/menit, 1.000/hari, dan 8K token/menit per model. Data API tidak dipakai untuk training dan tidak disimpan secara default (kecuali ≤30 hari untuk investigasi gangguan/penyalahgunaan). Mendukung `json_schema` strict.
    - **Gemini free tier:** data dipakai untuk meningkatkan produk Google, jadi kurang cocok untuk catatan pribadi.
    - **OpenRouter `:free`:** hanya 50 permintaan/hari tanpa membeli kredit.
37. **Adapter generik OpenAI-compatible** — satu implementasi untuk Groq, Gemini (endpoint kompatibel OpenAI), dan penyedia serupa. Cukup mengganti base URL, model, label, dan pemberitahuan data; tidak ada SDK provider di backend.
38. **Pembatasan free tier** (semua dapat diubah lewat environment):
    - 10 permintaan AI per pengguna per hari (spesifikasi menyebut 20; diturunkan untuk layanan gratis).
    - 800 per hari untuk seluruh server (di bawah batas 1.000/hari).
    - Maksimal 12.000 karakter per catatan, supaya input dan output muat di 8K token/menit.
    - Jeda 8 detik antar panggilan provider.
    - Maksimal 3 percobaan untuk gangguan sementara.
39. **Kuota direservasi atomik saat job dibuat** (`INSERT … ON CONFLICT DO UPDATE … WHERE count < limit`) untuk pengguna dan global dalam transaksi yang sama. Kuota dikembalikan untuk kegagalan yang bukan salah pengguna (output tidak valid, provider tidak tersedia/menolak, materi berubah/dihapus). Diuji dengan 8 permintaan paralel.
40. **Hanya versi catatan yang sudah tersinkron yang bisa diproses** — klien mengirim `note_version`; server menolak jika berbeda (409), dan worker menggagalkan job jika catatan berubah sebelum diproses. Hasil untuk versi lama tidak pernah menimpa versi baru; hasil lama diberi label "dari versi catatan lama".
41. **Catatan yang terlalu panjang ditolak (413) dengan batas yang jelas** — tidak dipotong diam-diam (spesifikasi bagian 7). Rangkuman per bagian untuk dokumen panjang menunggu fase 4.
42. **Structured output `json_schema` strict + validasi server** — output tidak valid dicoba ulang tepat satu kali lalu gagal dengan pesan jelas. Model mengembalikan `correct_index`; server membuat ID pilihan `a–d` dan ID topik stabil (`<note_id>:<slug topik>`).
43. **Prompt memperlakukan materi sebagai data** — materi dibungkus `<materi>…</materi>` (penutup blok di dalam materi dinetralkan), dengan instruksi eksplisit untuk mengabaikan perintah di dalam materi. Model tidak diberi tool atau akses data lain.
44. **Kunci jawaban di tabel `question_keys` terpisah** — `GET /v1/quizzes/{id}` tidak pernah mengirim kunci atau pembahasan. Penilaian deterministik di server, attempt idempotent per `Idempotency-Key`, dan judul sumber dipetakan dari metadata server.
45. **Worker = proses Python terpisah (`python -m app.worker`)** dengan antrean tabel PostgreSQL:
    - Klaim job memakai `FOR UPDATE SKIP LOCKED` dan lease 180 detik.
    - Job yang lease-nya habis bisa diambil ulang.
    - Hanya pemegang lease (attempt yang sama dan masih `running`) yang boleh menulis hasil.
    - Heartbeat dicatat saat klaim. Lease 180 detik > 2 × timeout panggilan 60 detik, jadi belum perlu heartbeat berkala.
46. **Kuis dikerjakan online** (spesifikasi): rangkuman, kuis, dan hasil yang pernah dibuka di-cache di tabel Room `remote_cache` (database per akun), jadi bisa dibaca offline dengan label "Offline". Status "soal sudah dilaporkan" belum disimpan di klien; server mengabaikan laporan ganda.
47. **Persetujuan pengiriman data diminta sekali per akun per provider** — teks pemberitahuan berasal dari server (`STUDYFLOW_AI_DATA_NOTICE`), sehingga tetap akurat jika provider diganti. Mode demo diberi label "Demo, bukan AI" di semua layar.
48. **`httpx2` sebagai klien HTTP runtime** — penerus httpx yang direkomendasikan Starlette; API-nya sama dengan httpx, dan juga dipakai untuk TestClient.

## Fase 4 dan penggantian provider ke Gemini

49. **Provider AI default diganti dari Groq ke Google Gemini** — atas permintaan pemilik proyek (29 Sep 2026), agar satu penyedia gratis melayani generasi dan embedding. Keputusan #36 (Groq sebagai default) digantikan; Groq tetap tersedia sebagai `openai_compatible`.
50. **API native Gemini, bukan endpoint kompatibel OpenAI** — dokumentasi resmi menyebut endpoint kompatibel OpenAI masih beta, dan dukungan `strict` untuk schema tidak didokumentasikan.
    - Generasi: `models/{model}:generateContent` dengan `responseMimeType: application/json` dan `responseJsonSchema`.
    - API key dikirim lewat header `x-goog-api-key`, tidak pernah di URL.
    - Bagian `thought` di respons diabaikan.
    - `finishReason` `MAX_TOKENS` dianggap output tidak valid; `SAFETY`/`blockReason` dianggap ditolak.
51. **Model: `gemini-3.8-flash` (generasi) dan `gemini-embedding-001` (embedding)** — keduanya berstatus stable di dokumentasi. `gemini-embedding-2` tidak dipakai karena dokumentasinya menyebut dua nama berbeda (`-2` dan `-2-preview`).
52. **Embedding 768 dimensi dengan normalisasi L2 manual** — dokumentasi mewajibkan normalisasi untuk `gemini-embedding-001` pada dimensi selain 3072. Dimensi dikunci di migrasi `0003` (`vector(768)`), sehingga mengganti model atau dimensi berarti migrasi dan reindex. Kolom `embedding_model` mencegah pencampuran ruang embedding saat retrieval. `taskType` `RETRIEVAL_DOCUMENT`/`RETRIEVAL_QUERY` dan `title` dokumen dikirim lewat `embedContentConfig`.
53. **Pemberitahuan data Gemini yang jujur** — sesuai ketentuan layanan gratis Gemini API:
    - Isi dan hasil dipakai Google untuk meningkatkan produk dan dapat dibaca peninjau manusia.
    - Jangan kirim informasi sensitif, rahasia, atau pribadi.
    - Layanan untuk usia 18+.

    Teks ini default untuk provider `gemini` dan ditampilkan sebelum catatan, pertanyaan, atau PDF dikirim.
54. **Batas default diturunkan untuk Gemini** — 200 permintaan AI global per hari dan jeda 6 detik, karena angka batas free tier Gemini hanya terlihat di AI Studio (tidak tercantum di dokumentasi). Kegagalan 429 tetap dijadwalkan ulang.
55. **Dokumen adalah entity server-only (tidak lewat protokol sinkronisasi)** — aplikasi membaca daftar dokumen lewat API dan meng-cache-nya per akun. Mata kuliah harus sudah tersinkron (FK kepemilikan `(course_id, user_id)` di server).
56. **Validasi unggah di API, ekstraksi di worker** — API memeriksa ukuran, signature `%PDF-`, enkripsi, dan jumlah halaman sebelum menyimpan. Worker mengekstrak teks per halaman dengan pypdf di **proses terpisah** yang punya batas waktu. Batas memori tidak diterapkan karena tidak portabel di Windows (dicatat sebagai keterbatasan). PDF dengan teks total < 200 karakter dianggap scan dan berstatus `unsupported`.
57. **Chunk ±450 kata dengan overlap ±70 kata** (≈600/90 token) memakai nomor halaman fisik dan offset karakter — parameter percobaan yang bisa diubah lewat environment.
58. **Publikasi indexing atomik dan dijaga lease** — halaman, potongan, dan `active_version_id` ditulis dalam satu transaksi oleh pemegang lease. Jika dokumen dihapus saat diproses, hasil tidak dipublikasikan.
59. **Exact search pgvector (tanpa indeks ANN)** sesuai spesifikasi. Indeks HNSW ditambahkan setelah volume data terukur membutuhkannya.
60. **Tanpa konteks yang cukup, model tidak dipanggil** — ambang `rag_max_distance` menentukan relevansi. Nilai default 0,55 adalah perkiraan awal yang **wajib dikalibrasi** dengan `app.eval.rag_eval` memakai Gemini; skor kemiripan bukan probabilitas kebenaran.
61. **Model hanya menerima ID pendek sumber (`S1…`)** — server menolak ID di luar hasil retrieval, lalu memetakan judul dan halaman dari database. Ini mencegah sumber fiktif, tetapi tidak membuktikan kebenaran jawaban; tombol cuplikan sumber tersedia untuk verifikasi pengguna.
62. **Satu thread tanya materi per mata kuliah** (thread per dokumen didukung API, belum di UI). Riwayat pesan di-cache per akun untuk dibaca offline.
63. **Storage: folder lokal untuk development, Supabase Storage bucket privat untuk produksi** lewat REST (`POST/GET /storage/v1/object/{bucket}/{key}`, `DELETE` dengan `prefixes`) dan secret key yang hanya ada di backend. Key berkas acak dengan prefix pemilik dan tidak memuat nama berkas asli. Cleanup dijalankan sebagai job yang bisa diulang.
64. **Membuka PDF memakai penampil bawaan platform** — desktop membuka berkas sementara lewat `java.awt.Desktop`, Android lewat `FileProvider` (cache privat, izin baca sementara). Penampil bawaan tidak bisa diarahkan ke halaman tertentu, jadi aplikasi menampilkan nomor halaman dan cuplikannya.
