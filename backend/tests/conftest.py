import pytest
from fastapi.testclient import TestClient

from app.core.config import AIProviderKind, AppEnv, Settings, get_settings
from app.main import create_app


@pytest.fixture
def make_client(monkeypatch):
    def _make(**overrides) -> TestClient:
        settings = Settings(_env_file=None, **overrides)
        get_settings.cache_clear()
        monkeypatch.setattr("app.main.get_settings", lambda: settings)
        app = create_app()
        app.dependency_overrides[get_settings] = lambda: settings
        return TestClient(app, raise_server_exceptions=False)

    yield _make
    get_settings.cache_clear()


@pytest.fixture
def client(make_client) -> TestClient:
    return make_client(app_env=AppEnv.TEST, ai_provider=AIProviderKind.NONE)
