# Rencana implementasi

Sumber: spesifikasi arsitektur StudyFlow (28 September 2026). Estimasi waktu sengaja tidak dicantumkan.

| Fase | Isi | Kriteria selesai | Status |
|---|---|---|---|
| 0. Bootstrap | Audit environment, matriks dependency, Gradle wrapper, scaffold KMP (Android + desktop), FastAPI `/health`, format error, fake provider, README | Android compile, desktop berjalan, `/health` OK, hambatan dicatat | Selesai |
| 1. Lokal | UI adaptif, CRUD mata kuliah/catatan/tugas, Room + outbox, tema | CRUD bertahan setelah restart, deadline benar, berjalan tanpa koneksi | Selesai |
| 2. Akun dan sync | Supabase Auth via Ktor, `SecureSessionStore`, database per akun, SQLAlchemy + Alembic + RLS default-deny, `/v1/sync/push` & `/v1/sync/pull`, konflik, impor data lokal | Dua perangkat sinkron; retry tidak menggandakan; konflik terdeteksi; logout mengisolasi data | Selesai |
| 3. AI catatan | Tabel `ai_jobs` + worker (SKIP LOCKED, lease, heartbeat, retry), provider nyata, rangkuman, kuis, attempt, kuota | Materi teks → hasil valid; kuota/timeout tertangani; skor deterministik | Selesai |
| 4. PDF dan RAG | Storage privat, pypdf, chunk, embedding pgvector, retrieval terfilter pengguna, penampil sumber | PDF teks dapat ditanya; scan ditolak jelas; sumber cocok; akun lain tidak terbaca | Berikutnya |
| 5. Personalisasi | Flashcard, histori topik, rekomendasi latihan rule-based | Rekomendasi mengikuti hasil tersimpan | — |
| 6. Rilis demo | Packaging, backup, Docker, dokumentasi demo, no-secret scan | Instalasi terdokumentasi, demo end-to-end | — |

## Langkah fase 4 (rinci)

1. **Embedding gratis.** Groq tidak menyediakan endpoint embedding. Pilih salah satu dan catat dimensinya:
   - Endpoint embedding Gemini, dengan konsekuensi kebijakan data free tier.
   - Model embedding lokal ringan di worker (spesifikasi mengecualikan model AI lokal hanya untuk generasi).
   - Pencarian full-text PostgreSQL sebagai tahap awal.
2. Storage privat (Supabase Storage) dan unggah PDF lewat backend: batas 10 MB/100 halaman, cek signature file, tolak PDF terenkripsi.
3. Worker: ekstraksi pypdf per halaman; deteksi PDF scan (tanpa teks) → status `unsupported` dengan pesan jelas; chunk 500–800 token dengan overlap, lengkap dengan halaman dan offset.
4. pgvector: tabel `document_chunks` dengan filter `user_id` + versi aktif; publikasi indeks secara atomik; cleanup saat dokumen dihapus.
5. Tanya materi: retrieval top-k, jawaban dengan `source chunk IDs` yang divalidasi server, dan penampil sumber (halaman PDF).
6. Rangkuman dokumen panjang per bagian lalu digabung, dengan kuota yang sama.
7. Tes: PDF scan/rusak/besar, prompt injection di dokumen, retrieval lintas akun, pertanyaan di luar materi, dan fixture evaluasi RAG.
