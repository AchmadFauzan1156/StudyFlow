# StudyFlow backend

FastAPI untuk API StudyFlow. Worker (antrean `ai_jobs` di PostgreSQL) ditambahkan di fase 3 dari codebase yang sama.

```powershell
uv sync
Copy-Item .env.example .env
uv run uvicorn app.main:app --reload --port 8000
uv run pytest
uv run ruff check .
```

| Modul | Isi |
|---|---|
| `app/api/` | Route (`/health`) dan format error `{code, message, request_id, details}` |
| `app/core/` | Konfigurasi dari environment (`STUDYFLOW_*`) |
| `app/domain/` | Schema output AI dan validasi struktur kuis |
| `app/providers/` | Interface provider AI, fake provider deterministik (development), factory |
| `tests/` | Tes API, provider, dan validasi kuis |

Kontrak lengkap: [../docs/API.md](../docs/API.md).
