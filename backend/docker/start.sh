#!/bin/sh
# Mode satu service (mis. Render Free): migrasi database, lalu API dengan worker tertanam.
set -e
alembic upgrade head
exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-8000}"
