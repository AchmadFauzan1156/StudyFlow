from typing import Annotated

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.api.auth import CurrentUser
from app.api.errors import ApiError
from app.core.config import Settings, get_settings
from app.db.models import Profile
from app.db.session import DbSession
from app.domain import sync

router = APIRouter(prefix="/v1")


class MeResponse(BaseModel):
    user_id: str
    display_name: str | None
    timezone: str


@router.get("/me", response_model=MeResponse)
def me(user_id: CurrentUser, db: DbSession) -> MeResponse:
    db.execute(pg_insert(Profile).values(user_id=user_id).on_conflict_do_nothing())
    db.commit()
    profile = db.get(Profile, user_id)
    return MeResponse(user_id=str(user_id), display_name=profile.display_name, timezone=profile.timezone)


@router.post("/sync/push", response_model=sync.PushResponse)
def sync_push(
    body: sync.PushRequest,
    user_id: CurrentUser,
    db: DbSession,
    settings: Annotated[Settings, Depends(get_settings)],
) -> sync.PushResponse:
    if len(body.mutations) > settings.sync_push_max_mutations:
        raise ApiError(413, "payload_too_large",
                       f"Maksimal {settings.sync_push_max_mutations} mutasi per permintaan")
    return sync.PushResponse(results=sync.push(db, user_id, body.mutations))


@router.get("/sync/pull", response_model=sync.PullResponse)
def sync_pull(
    user_id: CurrentUser,
    db: DbSession,
    settings: Annotated[Settings, Depends(get_settings)],
    cursor: str | None = None,
    limit: Annotated[int, Query(ge=1)] = 200,
) -> sync.PullResponse:
    try:
        return sync.pull(db, user_id, cursor, min(limit, settings.sync_pull_max_limit))
    except sync.CursorError:
        # Klien harus melakukan full resync (cursor kosong) tanpa membuang outbox-nya.
        raise ApiError(400, "cursor_invalid", "Cursor sinkronisasi tidak valid; lakukan sinkronisasi ulang") from None
