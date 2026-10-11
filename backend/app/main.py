import logging
import threading
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app import __version__
from app.api import ai, documents, health, progress, sync
from app.api.errors import install_error_handling
from app.core.config import Settings, get_settings
from app.worker.bootstrap import build_worker
from app.worker.runner import Worker

logger = logging.getLogger("studyflow.api")

# Batas tunggu thread worker saat shutdown; job yang terputus diambil ulang setelah lease habis.
WORKER_STOP_TIMEOUT_SECONDS = 10.0


def _configure_logging() -> None:
    """Uvicorn hanya mengonfigurasi lognya sendiri; pasang handler untuk logger aplikasi sekali saja."""
    package_logger = logging.getLogger("studyflow")
    if not package_logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
        package_logger.addHandler(handler)
        package_logger.setLevel(logging.INFO)
        package_logger.propagate = False


def create_app(worker_factory: Callable[[Settings], Worker | None] = build_worker) -> FastAPI:
    _configure_logging()
    settings = get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        # Mode tertanam (STUDYFLOW_EMBEDDED_WORKER): worker berjalan sebagai thread di proses API.
        stop = threading.Event()
        if settings.embedded_worker:
            try:
                worker = worker_factory(settings)
            except Exception:
                # Mis. format STUDYFLOW_DATABASE_URL salah: API tetap hidup, hanya worker yang tidak jalan.
                logger.exception("Gagal membangun worker tertanam")
                worker = None
            if worker is None:
                logger.error("Worker tertanam tidak dijalankan; job AI akan menunggu.")
            else:
                thread = threading.Thread(
                    target=worker.run_forever, kwargs={"stop": stop}, name="embedded-worker", daemon=True
                )
                thread.start()
                app.state.embedded_worker_thread = thread
        try:
            yield
        finally:
            stop.set()
            thread = app.state.embedded_worker_thread
            if thread is not None:
                thread.join(WORKER_STOP_TIMEOUT_SECONDS)

    app = FastAPI(title="StudyFlow API", version=__version__, lifespan=lifespan)
    app.state.embedded_worker_thread = None
    install_error_handling(app)
    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origins,
            allow_methods=["*"],
            allow_headers=["Authorization", "Content-Type", "Idempotency-Key"],
        )
    app.include_router(health.router)
    app.include_router(sync.router)
    app.include_router(ai.router)
    app.include_router(documents.router)
    app.include_router(progress.router)
    return app


app = create_app()
