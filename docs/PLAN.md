# Rencana implementasi

Sumber: spesifikasi arsitektur StudyFlow (28 September 2026). Estimasi waktu sengaja tidak dicantumkan.

| Fase | Isi | Kriteria selesai | Status |
|---|---|---|---|
| 0. Bootstrap | Audit environment, matriks dependency, Gradle wrapper, scaffold KMP (Android + desktop), FastAPI `/health`, format error, fake provider, README | Android compile, desktop berjalan, `/health` OK, hambatan dicatat | Selesai |
| 1. Lokal | UI adaptif, CRUD mata kuliah/catatan/tugas, Room + outbox, tema | CRUD bertahan setelah restart, deadline benar, berjalan tanpa koneksi | Selesai |
| 2. Akun dan sync | Supabase Auth via Ktor, `SecureSessionStore`, database per akun, SQLAlchemy + Alembic + RLS default-deny, `/v1/sync/push` & `/v1/sync/pull`, konflik, impor data lokal | Dua perangkat sinkron; retry tidak menggandakan; konflik terdeteksi; logout mengisolasi data | Selesai |
| 3. AI catatan | Tabel `ai_jobs` + worker (SKIP LOCKED, lease, heartbeat, retry), provider nyata, rangkuman, kuis, attempt, kuota | Materi teks → hasil valid; kuota/timeout tertangani; skor deterministik | Selesai |
| 4. PDF dan RAG | Storage privat, pypdf, chunk, embedding pgvector, retrieval terfilter pengguna, penampil sumber | PDF teks dapat ditanya; scan ditolak jelas; sumber cocok; akun lain tidak terbaca | Selesai |
| 5. Personalisasi | Flashcard, histori topik, rekomendasi latihan rule-based | Rekomendasi mengikuti hasil tersimpan | Berikutnya |
| 6. Rilis demo | Packaging, backup, Docker, dokumentasi demo, no-secret scan | Instalasi terdokumentasi, demo end-to-end | — |

## Langkah fase 5 (rinci)

1. Akurasi per topik dari `answers` + `questions.topic_id`, tanpa soal yang dilaporkan bermasalah (`question_reports`).
2. Rekomendasi rule-based: minimal 5 jawaban pada satu topik dan akurasi < 60% → usulan latihan ulang (ambang = keputusan produk, bisa diubah).
3. Endpoint progres per topik; layar Progres menampilkan topik lemah, dengan tautan ke sumber (catatan) untuk ditinjau.
4. Flashcard dari soal/pembahasan yang salah dijawab (tanpa panggilan AI tambahan); jadwal ulang sederhana.
5. Tes: soal dilaporkan tidak dihitung, ambang topik, isolasi akun.

Sebelum fase 5, sebaiknya kalibrasi `STUDYFLOW_RAG_MAX_DISTANCE` dengan Gemini (`app.eval.rag_eval`) dan evaluasi manual kualitas kuis/jawaban pada materi contoh.
