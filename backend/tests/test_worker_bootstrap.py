import threading

from app.core.config import AIProviderKind, AppEnv, Settings
from app.worker.bootstrap import build_worker
from app.worker.runner import Worker


def test_build_worker_without_database_returns_none():
    settings = Settings(_env_file=None, app_env=AppEnv.TEST, ai_provider=AIProviderKind.NONE, database_url=None)
    assert build_worker(settings) is None


def test_run_forever_returns_when_stop_is_set():
    settings = Settings(_env_file=None, app_env=AppEnv.TEST, ai_provider=AIProviderKind.NONE)
    worker = Worker(lambda: None, settings, provider=None)
    worker.run_once = lambda: False  # antrean selalu kosong
    stop = threading.Event()
    thread = threading.Thread(target=worker.run_forever, kwargs={"poll_seconds": 0.01, "stop": stop})
    thread.start()
    stop.set()
    thread.join(2)
    assert not thread.is_alive()
