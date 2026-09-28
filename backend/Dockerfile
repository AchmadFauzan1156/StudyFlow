# Satu image untuk API dan worker; perintah dipilih saat menjalankan container.
FROM python:3.13-slim

COPY --from=ghcr.io/astral-sh/uv:0.12.19 /uv /uvx /bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PYTHONUNBUFFERED=1

WORKDIR /app

# Dependency dulu (cache layer), sesuai uv.lock.
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

COPY app ./app
COPY migrations ./migrations
COPY alembic.ini ./
RUN uv sync --locked --no-dev

# Jalankan sebagai pengguna non-root.
RUN useradd --create-home --uid 10001 studyflow && mkdir -p /data && chown studyflow /data
USER studyflow

ENV PATH="/app/.venv/bin:$PATH" \
    STUDYFLOW_STORAGE_LOCAL_DIR=/data/storage

EXPOSE 8000
# API (default). Worker: `python -m app.worker`. Migrasi: `alembic upgrade head`.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
