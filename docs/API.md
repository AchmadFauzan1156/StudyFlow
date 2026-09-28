# Kontrak API

Status: endpoint kesehatan, akun, sinkronisasi (fase 2), AI catatan (fase 3), serta dokumen dan tanya materi (fase 4) sudah diimplementasikan.

## Konvensi

- Endpoint bisnis memakai prefix `/v1` dan header `Authorization: Bearer <access token Supabase>`. Server memverifikasi signature (JWKS ES256/RS256 dari `<SUPABASE_URL>/auth/v1/.well-known/jwks.json`; HS256 hanya jika secret dikonfigurasi), `iss = <SUPABASE_URL>/auth/v1`, `aud` (default `authenticated`), dan `exp`. `user_id` diambil dari klaim `sub`; request tidak pernah boleh menentukan identitas.
- Setiap response membawa header `X-Request-ID` yang dibuat server.
- Operasi yang dapat diulang menerima header `Idempotency-Key`.

### Format error

```json
{ "code": "not_found", "message": "Not Found", "request_id": "c3d2…", "details": null }
```

| Status | `code` | Kapan |
|---|---|---|
| 401 | `unauthorized` | Sesi tidak valid |
| 404 | `not_found` | Resource tidak ada atau bukan milik pengguna |
| 409 | `conflict` | Konflik versi / sinkronisasi |
| 413 | `payload_too_large` | Berkas/input terlalu besar |
| 422 | `invalid_input` | Validasi gagal; `details` berisi `loc`, `msg`, `type` |
| 429 | `quota_exceeded` | Kuota harian habis |
| 503 | `service_unavailable` | Provider AI tidak dikonfigurasi/tersedia |
| 500 | `internal_error` | Kesalahan tak terduga (detail hanya di log server) |

## Terimplementasi

### `GET /health`

```json
{ "status": "ok", "version": "0.1.0", "environment": "development", "ai_mode": "unconfigured" }
```

`ai_mode`: `unconfigured` (tidak ada provider), `demo` (fake provider, hanya non-produksi), `live` (provider nyata, fase 3). Tidak pernah memuat rahasia.

### `GET /v1/me`

Membuat profil jika belum ada. Respons: `{ "user_id": "...", "display_name": null, "timezone": "Asia/Jakarta" }`.

### `POST /v1/sync/push`

Maksimal 100 mutasi per permintaan (413 jika lebih). Mutasi diproses berurutan; hasil dikembalikan per mutasi.

```json
{
  "mutations": [
    {
      "mutation_id": "uuid",
      "entity_type": "course | note | task",
      "entity_id": "uuid",
      "operation": "create | update | delete",
      "base_version": 0,
      "payload": { "name": "Kriptografi", "semester": null }
    }
  ]
}
```

Payload per tipe (field snake_case):

| Tipe | Payload create/update |
|---|---|
| `course` | `name` (1–80), `semester` (≤40, opsional) |
| `note` | `course_id`, `title` (1–120), `content` (≤100.000) |
| `task` | `course_id`, `title` (1–120), `description` (≤2.000), `due_at` (epoch ms UTC), `time_zone_id`, `status` (`TODO`/`DONE`) |

Payload `delete` diabaikan.

Respons: `{ "results": [ { "mutation_id", "entity_type", "entity_id", "status", "version", "server_entity", "error_code" } ] }`

| `status` | Arti |
|---|---|
| `applied` | Diterapkan; `version` = versi baru entity. |
| `conflict` | `base_version` tidak cocok (atau create untuk ID yang sudah ada). `server_entity` berisi salinan server; tidak ada yang ditimpa. |
| `rejected` | Permanen: `not_found` (tidak ada/bukan milik pengguna), `parent_not_found`, `invalid_payload`, `mutation_id_reused`. |

Aturan:
- **Idempotent**: `mutation_id` diikat ke pengguna dan hash payload. Retry identik mengembalikan hasil yang sama; `mutation_id` sama dengan payload berbeda ditolak.
- **Optimistic concurrency**: update/delete wajib `base_version` = versi server saat ini. Versi dicek sebelum validasi induk, sehingga klien selalu menerima salinan server saat ada konflik.
- Update dengan `base_version` yang cocok memulihkan entity yang terhapus (dipakai saat pengguna memilih "pakai versi saya" pada konflik hapus).
- Delete adalah tombstone. Delete `course` ikut men-tombstone catatan dan tugasnya.
- Anak (`note`/`task`) hanya boleh menunjuk `course` milik pengguna yang sama dan belum dihapus. Ini dijaga FK komposit `(course_id, user_id)` di database.

### `GET /v1/sync/pull?cursor=&limit=`

`limit` default 200, maksimal 500. Respons: `{ "changes": [ { "entity_type", "entity": { ... } } ], "next_cursor": "...", "has_more": false }`.

- Setiap entity muncul sekali dengan state terbaru (termasuk tombstone: `deleted_at` ≠ null).
- `entity` memuat `id`, `version`, `updated_at`, `deleted_at` (epoch ms), plus field tipe seperti pada payload.
- Cursor bersifat opaque. Cursor tidak valid atau di luar jangkauan → 400 `cursor_invalid`; klien melakukan full resync (cursor kosong) tanpa membuang outbox.
- Urutan aman terhadap transaksi bersamaan: semua mutasi satu pengguna diserialkan dengan lock baris `sync_counters`, sehingga `seq` naik sesuai urutan commit.

### Perilaku klien

- Outbox dikirim tertua dulu, satu mutasi per entity per batch. Setelah `applied`, mutasi berikutnya untuk entity itu di-rebase ke versi baru.
- `conflict` disimpan di tabel lokal `sync_conflicts`. Mutasi entity itu ditahan sampai pengguna memilih "Pakai versi saya" (dikirim ulang di atas versi server) atau "Pakai versi server".
- Pull tidak menimpa entity yang masih punya perubahan lokal tertunda atau konflik. Semua halaman pull diterapkan dalam satu transaksi, mata kuliah lebih dulu.
- 401 memicu satu kali refresh token lalu request diulang.

### AI catatan (fase 3)

Semua endpoint membutuhkan login. Endpoint yang membuat sesuatu mewajibkan header `Idempotency-Key` (8–128 karakter). Mengirim ulang dengan key yang sama mengembalikan hasil yang sama; key yang sama untuk permintaan berbeda menghasilkan 409 `idempotency_key_reused`.

| Endpoint | Fungsi |
|---|---|
| `GET /v1/ai/info` | `{available, mode (unconfigured/demo/live), provider_label, model, data_notice, daily_limit, used_today, max_input_chars, quiz_min_questions, quiz_max_questions}` |
| `POST /v1/summaries` | `{note_id, note_version}` → **202** job |
| `POST /v1/quizzes` | `{note_id, note_version, question_count}` → **202** job |
| `GET /v1/jobs/{id}` | `{id, type, status (queued/running/succeeded/failed), source_id, source_version, error_code, result_id, created_at}` |
| `GET /v1/summaries/{id}` | Isi rangkuman, `source` (`title`, `version`, `current_version`, `outdated`, `deleted`), `model_id`, `prompt_version`, `is_demo` |
| `GET /v1/quizzes/{id}` | Soal dan pilihan **tanpa** kunci jawaban dan pembahasan |
| `POST /v1/quizzes/{id}/attempts` | `{answers: {question_id: option_id \| null}}` → skor dan hasil per soal (`correct_option_id`, `explanation`, `sources[{source_id, title}]`). Penilaian deterministik di server; judul sumber diambil dari metadata server. |
| `GET /v1/attempts?quiz_id=&limit=` | Riwayat attempt terbaru |
| `GET /v1/attempts/{id}` | Detail hasil satu attempt |
| `GET /v1/notes/{id}/ai-results` | Rangkuman dan kuis untuk catatan (termasuk skor terbaik) dan job yang belum selesai/gagal |
| `POST /v1/questions/{id}/report` | Tandai soal bermasalah → 204 |

Error saat membuat job:

| Status | `code` | Arti |
|---|---|---|
| 503 | `service_unavailable` | Provider AI belum dikonfigurasi (kuota tidak dipakai) |
| 404 | `not_found` | Catatan tidak ada atau milik akun lain |
| 409 | `source_version_mismatch` | Versi catatan di server berbeda; klien perlu sinkron dulu |
| 413 | `source_too_large` | Melebihi `max_input_chars`; `details` berisi `length` dan `max` |
| 422 | `source_empty` / `invalid_input` | Catatan kosong / jumlah soal di luar batas |
| 429 | `quota_exceeded` / `service_quota_exceeded` | Kuota harian pengguna / kuota global layanan gratis habis |

`error_code` job yang gagal: `source_changed`, `source_deleted`, `invalid_output`, `provider_unavailable`, `provider_rejected`. Untuk semua kode ini kuota dikembalikan.

Alur worker:
1. Klaim job dengan `FOR UPDATE SKIP LOCKED` dan lease.
2. Pastikan versi catatan masih sama.
3. Panggil provider dengan structured output (`json_schema` strict).
4. Validasi struktur. Jika output tidak valid, coba ulang **satu kali**, lalu gagal.
5. Jika provider membalas 429, 5xx, atau timeout, jadwalkan ulang (menghormati `Retry-After`, backoff 15 detik sampai 10 menit, maks. `ai_max_attempts`).
6. Tulis hasil hanya jika lease masih dipegang worker itu.

### Dokumen PDF dan tanya materi (fase 4)

| Endpoint | Fungsi |
|---|---|
| `POST /v1/documents` | Multipart: `file` (PDF), `course_id`, `title` (opsional) dan header `Idempotency-Key` → **202** `{id, course_id, title, status, error_code, page_count, size_bytes, warnings, version_id, job_id, created_at}` |
| `GET /v1/documents?course_id=` | Dokumen milik pengguna di mata kuliah itu (tanpa yang dihapus) |
| `GET /v1/documents/{id}` | Metadata dan status: `queued`, `processing`, `ready`, `failed`, `unsupported` |
| `GET /v1/documents/{id}/pages?start=&count=` | Pratinjau teks hasil ekstraksi (maks. 20 halaman per permintaan) |
| `GET /v1/documents/{id}/download` | Berkas PDF setelah cek kepemilikan (`Cache-Control: private, no-store`) |
| `DELETE /v1/documents/{id}` | Tombstone, membatalkan job indexing yang belum berjalan, dan menjadwalkan cleanup berkas/teks/embedding yang bisa diulang → 204 |
| `GET /v1/chunks/{id}` | Cuplikan sumber: `document_title`, `page_start`, `page_end`, `text`. 404 jika dokumen dihapus atau milik akun lain |
| `POST /v1/chat/threads` | `{course_id}` → thread tanya materi untuk mata kuliah itu (dibuat sekali, lalu dipakai ulang) |
| `POST /v1/chat/threads/{id}/messages` | `{content}` dan header `Idempotency-Key` → **202** pesan pengguna dengan `job_status` |
| `GET /v1/chat/threads/{id}/messages` | Pesan pengguna dan jawaban. Jawaban memuat `answerable`, `is_demo`, dan `sources[{chunk_id, document_id, document_title, page_start, page_end, excerpt}]` |

Validasi unggah (sebelum disimpan):

| Kondisi | Status | `code` |
|---|---|---|
| Berkas lebih dari `document_max_bytes` (default 10 MB) | 413 | `file_too_large` |
| Tidak diawali signature `%PDF-` (ekstensi tidak dipercaya) | 422 | `not_pdf` |
| Terkunci password | 422 | `pdf_encrypted` |
| Rusak atau tanpa halaman | 422 | `pdf_invalid` |
| Lebih dari `document_max_pages` (default 100) | 413 | `too_many_pages` |
| Kuota unggah harian habis (terpisah dari kuota AI) | 429 | `quota_exceeded` |

Hasil indexing (`error_code` versi):
- **`pdf_no_text`:** status `unsupported`; PDF scan belum didukung (tanpa OCR).
- **`extract_timeout`, `pdf_invalid`:** status `failed`.
- **Peringatan:** halaman tanpa teks dan kemungkinan rumus/tabel rusak dikembalikan di `warnings`.

Alur tanya materi di worker:
1. Buat embedding pertanyaan (`RETRIEVAL_QUERY`).
2. Cari potongan hanya di dokumen milik pengguna, pada mata kuliah itu, dengan versi aktif dan model embedding yang sama (exact search pgvector, top-k = `rag_top_k`, jarak ≤ `rag_max_distance`).
3. Jika tidak ada potongan yang cukup dekat, jawab "materi belum memuat jawabannya" **tanpa memanggil model**, dan kuota dikembalikan.
4. Jika ada, potongan dikirim dengan ID pendek (`S1…`). Model harus mengembalikan `source_ids` dari daftar itu; ID lain dianggap output tidak valid (coba ulang sekali, lalu gagal).
5. Judul dan halaman sumber diambil server dari metadata, bukan dari teks model.

### Progres dan latihan (fase 5)

| Endpoint | Fungsi |
|---|---|
| `GET /v1/progress/topics?course_id=` | Per topik: `answered`, `correct`, `accuracy`, `needs_practice`, `note_id`, `note_title`, plus `min_answers` dan `accuracy_threshold`. Soal yang dilaporkan dan jawaban kosong tidak dihitung; topik yang perlu latihan diurutkan lebih dulu. |
| `GET /v1/flashcards/due` | Hingga 20 kartu jatuh tempo dari soal yang pernah dijawab salah (tanpa soal yang dilaporkan): `question`, `answer`, `explanation`, `topic_label`, `note_title`, `box`, beserta `due_total` |
| `POST /v1/flashcards/{question_id}/review` | `{remembered}` → Leitner: ingat naik kotak (maks. 5), lupa kembali ke kotak 1; jatuh tempo berikutnya 1/2/4/8/16 hari |

## Rancangan

Semua endpoint dari spesifikasi bagian 10 sudah diimplementasikan. Yang belum: rangkuman dokumen panjang per bagian dan unggah versi baru untuk dokumen yang sama.

## Schema output AI (sudah ada di `backend/app/domain/schemas.py`)

Kuis: `title`, `questions[{id, topic_id, question, options[{id, text}], correct_option_id, explanation, source_refs}]`. Validasi struktur (`validate_quiz`): jumlah soal sesuai, 2–6 pilihan, id dan teks pilihan unik, tepat satu jawaban benar, dan semua `source_refs` berasal dari materi yang dipilih. Lolos validasi tidak berarti benar secara semantik.
