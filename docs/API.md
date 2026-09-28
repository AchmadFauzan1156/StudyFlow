# Kontrak API

Status: `GET /health`, `GET /v1/me`, `POST /v1/sync/push`, dan `GET /v1/sync/pull` sudah diimplementasikan (fase 2). Endpoint lain adalah kontrak rancangan (spesifikasi bagian 10).

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

## Rancangan

| Endpoint | Fungsi | Fase |
|---|---|---|
| `POST /v1/summaries` | `source_ref` + `source_version` → 202 + `job_id` | 3 |
| `GET /v1/summaries/{id}` | Hasil + rujukan | 3 |
| `POST /v1/quizzes` | `source_ref` + `source_version` + `question_count` → 202 | 3 |
| `GET /v1/quizzes/{id}` | Soal tanpa answer key | 3 |
| `POST /v1/quizzes/{id}/attempts` | Jawaban + `Idempotency-Key` → skor dan pembahasan | 3 |
| `GET /v1/attempts` | Riwayat berhalaman | 3 |
| `GET /v1/jobs/{id}` | Status, tahap, `result_type`, `result_id` | 3 |
| `POST /v1/documents` | Multipart PDF + `course_id` → 202 | 4 |
| `GET /v1/documents/{id}` | Metadata dan status | 4 |
| `GET /v1/documents/{id}/download` | Unduh privat setelah cek kepemilikan | 4 |
| `DELETE /v1/documents/{id}` | Tombstone + job cleanup | 4 |
| `POST /v1/chat/threads` | Thread dalam scope mata kuliah | 4 |
| `POST /v1/chat/threads/{id}/messages` | Pertanyaan → 202 + `job_id` | 4 |
| `GET /v1/chat/threads/{id}/messages` | Pesan + sumber | 4 |

## Schema output AI (sudah ada di `backend/app/domain/schemas.py`)

Kuis: `title`, `questions[{id, topic_id, question, options[{id, text}], correct_option_id, explanation, source_refs}]`. Validasi struktur (`validate_quiz`): jumlah soal sesuai, 2–6 pilihan, id dan teks pilihan unik, tepat satu jawaban benar, dan semua `source_refs` berasal dari materi yang dipilih. Lolos validasi tidak berarti benar secara semantik.
