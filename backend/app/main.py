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


def create_app(worker_factory: Callable[[Settings], Worker | None] = build_worker) -> FastAPI:
    settings = get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        # Mode tertanam (STUDYFLOW_EMBEDDED_WORKER): worker berjalan sebagai thread di proses API.
        stop = threading.Event()
        if settings.embedded_worker:
            worker = worker_factory(settings)
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
