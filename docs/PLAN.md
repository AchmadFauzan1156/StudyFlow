# Rencana implementasi

Sumber: spesifikasi arsitektur StudyFlow (28 September 2026). Estimasi waktu sengaja tidak dicantumkan.

| Fase | Isi | Kriteria selesai | Status |
|---|---|---|---|
| 0. Bootstrap | Audit environment, matriks dependency, Gradle wrapper, scaffold KMP (Android + desktop), FastAPI `/health`, format error, fake provider, README | Android compile, desktop berjalan, `/health` OK, hambatan dicatat | Selesai |
| 1. Lokal | UI adaptif, CRUD mata kuliah/catatan/tugas, Room + outbox, tema | CRUD bertahan setelah restart, deadline benar, berjalan tanpa koneksi | Selesai |
| 2. Akun dan sync | Supabase Auth via Ktor, `SecureSessionStore`, database per akun, SQLAlchemy + Alembic + RLS default-deny, `/v1/sync/push` & `/v1/sync/pull`, konflik, impor data lokal | Dua perangkat sinkron; retry tidak menggandakan; konflik terdeteksi; logout mengisolasi data | Berikutnya |
| 3. AI catatan | Tabel `ai_jobs` + worker (SKIP LOCKED, lease, heartbeat, retry), provider nyata, rangkuman, kuis, attempt, kuota | Materi teks → hasil valid; kuota/timeout tertangani; skor deterministik | — |
| 4. PDF dan RAG | Storage privat, pypdf, chunk, embedding pgvector, retrieval terfilter pengguna, penampil sumber | PDF teks dapat ditanya; scan ditolak jelas; sumber cocok; akun lain tidak terbaca | — |
| 5. Personalisasi | Flashcard, histori topik, rekomendasi latihan rule-based | Rekomendasi mengikuti hasil tersimpan | — |
| 6. Rilis demo | Packaging, backup, Docker, dokumentasi demo, no-secret scan | Instalasi terdokumentasi, demo end-to-end | — |

## Langkah fase 2 (rinci)

1. Backend: tambah SQLAlchemy, psycopg, Alembic; migrasi awal `profiles`, `courses`, `notes`, `tasks`, `sync_mutations`, `sync_changes` dengan RLS default-deny.
2. Dependency auth: validasi JWT Supabase (signature, issuer, audience, expiry); `user_id` hanya dari `sub`.
3. `POST /v1/sync/push`: idempotent per `mutation_id` + `payload_hash`, optimistic concurrency `base_version`, cascade delete course → anak.
4. `GET /v1/sync/pull`: opaque cursor dari counter perubahan per pengguna yang di-lock dalam transaksi yang sama dengan update entity.
5. Aplikasi: `SecureSessionStore` (Android Keystore; desktop memory-only jika credential store belum tersedia), klien Ktor, sinkronisasi outbox dengan backoff, UI konflik.
6. Tes wajib: offline → reconnect, retry tanpa duplikat, edit bersamaan dua perangkat, ganti akun, isolasi dua akun.
