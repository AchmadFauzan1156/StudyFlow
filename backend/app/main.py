from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app import __version__
from app.api import ai, documents, health, progress, sync
from app.api.errors import install_error_handling
from app.core.config import get_settings


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title="StudyFlow API", version=__version__)
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
