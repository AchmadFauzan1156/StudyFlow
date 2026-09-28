# Kontrak API

Status: `GET /health` sudah diimplementasikan. Endpoint lain adalah kontrak rancangan (spesifikasi bagian 10) dan akan diimplementasikan per fase.

## Konvensi

- Endpoint bisnis memakai prefix `/v1` dan header `Authorization: Bearer <JWT Supabase>`. `user_id` diambil dari klaim `sub`; request tidak pernah boleh menentukan identitas.
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

## Rancangan

| Endpoint | Fungsi | Fase |
|---|---|---|
| `GET /v1/me` | Profil pengguna | 2 |
| `POST /v1/sync/push` | Batch mutasi `{mutation_id, entity_type, entity_id, operation, base_version, payload}`; hasil per mutasi | 2 |
| `GET /v1/sync/pull?cursor=…` | Perubahan berhalaman + cursor berikutnya | 2 |
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
