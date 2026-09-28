from collections.abc import Iterator
from functools import lru_cache
from typing import Annotated

from fastapi import Depends
from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.api.errors import ApiError
from app.core.config import Settings, get_settings


@lru_cache(maxsize=4)
def _engine_for(url: str) -> Engine:
    return create_engine(url, pool_pre_ping=True)


def get_db(settings: Annotated[Settings, Depends(get_settings)]) -> Iterator[Session]:
    if not settings.database_url:
        raise ApiError(503, "service_unavailable", "Database belum dikonfigurasi di server ini")
    factory = sessionmaker(bind=_engine_for(settings.database_url), expire_on_commit=False)
    with factory() as session:
        yield session


DbSession = Annotated[Session, Depends(get_db)]
