from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app import __version__
from app.core.config import Settings, get_settings
from app.providers.factory import provider_mode

router = APIRouter()


class HealthResponse(BaseModel):
    status: str
    version: str
    environment: str
    ai_mode: str


@router.get("/health", response_model=HealthResponse)
def health(settings: Annotated[Settings, Depends(get_settings)]) -> HealthResponse:
    # Tidak pernah memuat rahasia; ai_mode hanya "unconfigured" atau "demo" ("live" setelah provider nyata ada).
    return HealthResponse(
        status="ok",
        version=__version__,
        environment=settings.app_env.value,
        ai_mode=provider_mode(settings),
    )
