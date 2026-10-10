import time
import uuid

import pytest
from fastapi.testclient import TestClient

from app.core.config import AIProviderKind, AppEnv, Settings, get_settings
from app.main import create_app
from tests.integration.conftest import JWT_SECRET, SUPABASE_URL, TEST_DB_URL, make_token

pytestmark = pytest.mark.integration


def test_job_completes_without_separate_worker_process(engine, monkeypatch):
    settings = Settings(
        _env_file=None, app_env=AppEnv.TEST, ai_provider=AIProviderKind.FAKE, database_url=TEST_DB_URL,
        supabase_url=SUPABASE_URL, jwt_hs256_secret=JWT_SECRET, ai_min_interval_seconds=0, embedded_worker=True,
    )
    monkeypatch.setattr("app.main.get_settings", lambda: settings)
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    headers = {"Authorization": f"Bearer {make_token(uuid.uuid4())}"}
    course_id, note_id = uuid.uuid4(), uuid.uuid4()

    with TestClient(app) as client:
        pushed = client.post("/v1/sync/push", headers=headers, json={"mutations": [
            {"mutation_id": str(uuid.uuid4()), "entity_type": "course", "entity_id": str(course_id),
             "operation": "create", "base_version": 0, "payload": {"name": "Kriptografi"}},
            {"mutation_id": str(uuid.uuid4()), "entity_type": "note", "entity_id": str(note_id),
             "operation": "create", "base_version": 0,
             "payload": {"course_id": str(course_id), "title": "RSA", "content": "RSA memakai dua bilangan prima."}},
        ]})
        assert [r["status"] for r in pushed.json()["results"]] == ["applied", "applied"]
        job = client.post("/v1/summaries", headers=headers | {"Idempotency-Key": uuid.uuid4().hex},
                          json={"note_id": str(note_id), "note_version": 1}).json()

        status = None
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            status = client.get(f"/v1/jobs/{job['id']}", headers=headers).json()["status"]
            if status == "succeeded":
                break
            time.sleep(0.2)
        assert status == "succeeded"
