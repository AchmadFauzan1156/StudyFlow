import threading

from fastapi.testclient import TestClient

from app.core.config import AIProviderKind, AppEnv, Settings, get_settings
from app.main import create_app


class FakeWorker:
    def __init__(self) -> None:
        self.started = threading.Event()
        self.stopped = threading.Event()

    def run_forever(self, poll_seconds: float = 2.0, stop: threading.Event | None = None) -> None:
        self.started.set()
        assert stop is not None
        stop.wait()
        self.stopped.set()


def _app(monkeypatch, worker, **overrides):
    settings = Settings(_env_file=None, app_env=AppEnv.TEST, ai_provider=AIProviderKind.NONE, **overrides)
    get_settings.cache_clear()
    monkeypatch.setattr("app.main.get_settings", lambda: settings)
    return create_app(worker_factory=lambda s: worker)


def test_embedded_worker_starts_with_app_and_stops_on_shutdown(monkeypatch):
    worker = FakeWorker()
    app = _app(monkeypatch, worker, embedded_worker=True)
    with TestClient(app) as client:
        assert worker.started.wait(2)
        assert client.get("/health").status_code == 200
    assert worker.stopped.wait(2)
    assert not app.state.embedded_worker_thread.is_alive()


def test_embedded_worker_disabled_by_default(monkeypatch):
    worker = FakeWorker()
    app = _app(monkeypatch, worker)
    with TestClient(app):
        assert not worker.started.wait(0.2)
    assert app.state.embedded_worker_thread is None


def test_unbuildable_worker_keeps_api_alive(monkeypatch):
    # Mis. STUDYFLOW_DATABASE_URL kosong: build_worker mengembalikan None.
    app = _app(monkeypatch, None, embedded_worker=True)
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
    assert app.state.embedded_worker_thread is None


def test_embedded_worker_survives_database_error(monkeypatch):
    # Mis. koneksi pooler Supabase terputus sesaat: worker tertanam harus tetap hidup.
    from sqlalchemy.exc import OperationalError

    from app.worker.runner import Worker

    stop = threading.Event()
    calls = []

    class FlakyWorker(Worker):
        def run_once(self) -> bool:
            calls.append(1)
            if len(calls) == 1:
                raise OperationalError("select 1", {}, Exception("server closed the connection"))
            stop.set()
            return False

    worker = FlakyWorker(None, Settings(_env_file=None, app_env=AppEnv.TEST), None)
    thread = threading.Thread(target=worker.run_forever, kwargs={"poll_seconds": 0.01, "stop": stop}, daemon=True)
    thread.start()
    thread.join(5)
    assert not thread.is_alive()
    assert len(calls) == 2
