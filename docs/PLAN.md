# Rencana implementasi

Sumber: spesifikasi arsitektur StudyFlow (28 September 2026). Estimasi waktu sengaja tidak dicantumkan.

| Fase | Isi | Kriteria selesai | Status |
|---|---|---|---|
| 0. Bootstrap | Audit environment, matriks dependency, Gradle wrapper, scaffold KMP (Android + desktop), FastAPI `/health`, format error, fake provider, README | Android compile, desktop berjalan, `/health` OK, hambatan dicatat | Selesai |
| 1. Lokal | UI adaptif, CRUD mata kuliah/catatan/tugas, Room + outbox, tema | CRUD bertahan setelah restart, deadline benar, berjalan tanpa koneksi | Selesai |
| 2. Akun dan sync | Supabase Auth via Ktor, `SecureSessionStore`, database per akun, SQLAlchemy + Alembic + RLS default-deny, `/v1/sync/push` & `/v1/sync/pull`, konflik, impor data lokal | Dua perangkat sinkron; retry tidak menggandakan; konflik terdeteksi; logout mengisolasi data | Selesai |
| 3. AI catatan | Tabel `ai_jobs` + worker (SKIP LOCKED, lease, heartbeat, retry), provider nyata, rangkuman, kuis, attempt, kuota | Materi teks → hasil valid; kuota/timeout tertangani; skor deterministik | Berikutnya |
| 4. PDF dan RAG | Storage privat, pypdf, chunk, embedding pgvector, retrieval terfilter pengguna, penampil sumber | PDF teks dapat ditanya; scan ditolak jelas; sumber cocok; akun lain tidak terbaca | — |
| 5. Personalisasi | Flashcard, histori topik, rekomendasi latihan rule-based | Rekomendasi mengikuti hasil tersimpan | — |
| 6. Rilis demo | Packaging, backup, Docker, dokumentasi demo, no-secret scan | Instalasi terdokumentasi, demo end-to-end | — |

## Langkah fase 3 (rinci)

1. Pilih satu provider AI nyata. Adapter di `backend/app/providers/`, nama model lewat environment; pastikan kebijakan retensi data provider sebelum menjanjikan apa pun ke pengguna.
2. Migrasi: `ai_jobs` (status, attempts, lease_until, heartbeat_at, error_code), `summaries`, `quizzes`/`questions`, `question_keys` (server-only), `attempts`/`answers`, `usage_records`.
3. Worker Python terpisah: klaim job atomik dengan `FOR UPDATE SKIP LOCKED`, lease + heartbeat, retry terbatas, penulisan hasil idempotent.
4. Endpoint `POST /v1/summaries`, `POST /v1/quizzes` (202 + `job_id`), `GET /v1/jobs/{id}`, `GET /v1/quizzes/{id}` tanpa answer key, `POST /v1/quizzes/{id}/attempts` dengan penilaian deterministik di server.
5. Kuota 20 job generatif/pengguna/hari, direservasi atomik sebelum job diantrekan.
6. Aplikasi: persetujuan sebelum materi dikirim ke provider, tab Belajar (rangkuman, kuis online), polling job berjeda, riwayat hasil yang bisa dibaca offline.
7. Tes: output AI invalid (retry sekali lalu gagal jelas), timeout/429 provider, API key kosong, attempt dikirim dua kali, worker crash dan pemulihan lease.
