"""Tes integrasi dengan PostgreSQL sungguhan.

Jalankan `docker compose up -d` lalu set STUDYFLOW_TEST_DATABASE_URL, mis.
postgresql+psycopg://studyflow:studyflow-dev@localhost:54329/studyflow_test
Tanpa variabel itu, tes integrasi dilewati.
"""

import os
import time
import uuid
from collections.abc import Callable

import jwt
import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from app.core.config import AIProviderKind, AppEnv, Settings, get_settings
from app.main import create_app

TEST_DB_URL = os.environ.get("STUDYFLOW_TEST_DATABASE_URL")
SUPABASE_URL = "http://auth.test.local"
JWT_SECRET = "test-secret-with-at-least-32-bytes-000"

pytestmark = pytest.mark.skipif(not TEST_DB_URL, reason="STUDYFLOW_TEST_DATABASE_URL tidak diset")

_DATA_TABLES = "sync_mutations, sync_changes, sync_counters, tasks, notes, courses, profiles"


@pytest.fixture(scope="session")
def engine():
    if not TEST_DB_URL:
        pytest.skip("STUDYFLOW_TEST_DATABASE_URL tidak diset")
    cfg = Config(os.path.join(os.path.dirname(__file__), "..", "..", "alembic.ini"))
    cfg.set_main_option("script_location", os.path.join(os.path.dirname(__file__), "..", "..", "migrations"))
    cfg.cmd_opts = type("Opts", (), {"x": [f"url={TEST_DB_URL}"]})()
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")
    eng = create_engine(TEST_DB_URL)
    yield eng
    eng.dispose()


@pytest.fixture(autouse=True)
def _clean(engine):
    with engine.begin() as conn:
        conn.execute(text(f"TRUNCATE {_DATA_TABLES}"))


@pytest.fixture
def settings() -> Settings:
    return Settings(
        _env_file=None,
        app_env=AppEnv.TEST,
        ai_provider=AIProviderKind.NONE,
        database_url=TEST_DB_URL,
        supabase_url=SUPABASE_URL,
        jwt_hs256_secret=JWT_SECRET,
    )


@pytest.fixture
def client(settings, monkeypatch) -> TestClient:
    monkeypatch.setattr("app.main.get_settings", lambda: settings)
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    return TestClient(app, raise_server_exceptions=False)


def make_token(user_id: uuid.UUID, *, secret: str = JWT_SECRET, aud: str = "authenticated",
               iss: str = f"{SUPABASE_URL}/auth/v1", exp_delta: int = 3600) -> str:
    now = int(time.time())
    return jwt.encode(
        {"sub": str(user_id), "aud": aud, "iss": iss, "exp": now + exp_delta, "iat": now,
         "role": "authenticated"},
        secret,
        algorithm="HS256",
    )


@pytest.fixture
def auth() -> Callable[[uuid.UUID], dict[str, str]]:
    return lambda user_id: {"Authorization": f"Bearer {make_token(user_id)}"}
