"""Sinkronisasi push/pull.

Aturan:
- Semua mutasi seorang pengguna diserialkan dengan lock baris `sync_counters` (FOR UPDATE). Karena itu
  nomor `seq` bertambah sesuai urutan commit, sehingga pull berbasis `seq > cursor` tidak melewatkan
  perubahan dari transaksi yang commit belakangan.
- `mutation_id` terikat ke user_id dan hash payload. Retry identik mengembalikan hasil tersimpan;
  mutation_id sama dengan payload berbeda ditolak.
- Update/delete memakai optimistic concurrency (`base_version`). Ketidakcocokan menghasilkan
  `conflict` beserta salinan server; tidak ada edit yang ditimpa diam-diam.
- Delete adalah tombstone. Delete course ikut men-tombstone catatan dan tugasnya.
"""

import base64
import hashlib
import json
import uuid
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, Field, ValidationError, field_validator
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.orm import Session

from app.db.models import Course, Note, SyncChange, SyncCounter, SyncMutation, Task

# ---------- Kontrak API ----------


class EntityType(StrEnum):
    COURSE = "course"
    NOTE = "note"
    TASK = "task"


class Operation(StrEnum):
    CREATE = "create"
    UPDATE = "update"
    DELETE = "delete"


class MutationIn(BaseModel):
    mutation_id: uuid.UUID
    entity_type: EntityType
    entity_id: uuid.UUID
    operation: Operation
    base_version: int = Field(ge=0)
    payload: dict[str, Any] = {}


class PushRequest(BaseModel):
    mutations: list[MutationIn]


class MutationResult(BaseModel):
    mutation_id: uuid.UUID
    entity_type: EntityType
    entity_id: uuid.UUID
    status: Literal["applied", "conflict", "rejected"]
    version: int | None = None
    server_entity: dict[str, Any] | None = None
    error_code: str | None = None


class PushResponse(BaseModel):
    results: list[MutationResult]


class ChangeOut(BaseModel):
    entity_type: EntityType
    entity: dict[str, Any]


class PullResponse(BaseModel):
    changes: list[ChangeOut]
    next_cursor: str
    has_more: bool


# ---------- Payload per entity ----------


class CoursePayload(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    semester: str | None = Field(default=None, max_length=40)

    @field_validator("name")
    @classmethod
    def _strip(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("kosong")
        return v.strip()


class NotePayload(BaseModel):
    course_id: uuid.UUID
    title: str = Field(min_length=1, max_length=120)
    content: str = Field(max_length=100_000)


class TaskPayload(BaseModel):
    course_id: uuid.UUID
    title: str = Field(min_length=1, max_length=120)
    description: str = Field(max_length=2_000)
    due_at: int  # epoch millis UTC
    time_zone_id: str = Field(min_length=1, max_length=64)
    status: Literal["TODO", "DONE"]


_MODELS = {EntityType.COURSE: Course, EntityType.NOTE: Note, EntityType.TASK: Task}
_PAYLOADS = {EntityType.COURSE: CoursePayload, EntityType.NOTE: NotePayload, EntityType.TASK: TaskPayload}


class CursorError(ValueError):
    pass


# ---------- Serialisasi ----------


def _ms(dt: datetime | None) -> int | None:
    return None if dt is None else int(dt.timestamp() * 1000)


def entity_to_dict(entity_type: EntityType, row: Course | Note | Task) -> dict[str, Any]:
    data: dict[str, Any] = {
        "id": str(row.id),
        "version": row.version,
        "updated_at": _ms(row.updated_at),
        "deleted_at": _ms(row.deleted_at),
    }
    match entity_type:
        case EntityType.COURSE:
            data |= {"name": row.name, "semester": row.semester}
        case EntityType.NOTE:
            data |= {"course_id": str(row.course_id), "title": row.title, "content": row.content}
        case EntityType.TASK:
            data |= {
                "course_id": str(row.course_id),
                "title": row.title,
                "description": row.description,
                "due_at": _ms(row.due_at),
                "time_zone_id": row.time_zone_id,
                "status": row.status,
            }
    return data


def encode_cursor(seq: int) -> str:
    return base64.urlsafe_b64encode(f"v1:{seq}".encode()).decode().rstrip("=")


def decode_cursor(cursor: str | None) -> int:
    if not cursor:
        return 0
    try:
        raw = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)).decode()
        prefix, _, value = raw.partition(":")
        seq = int(value)
        if prefix != "v1" or seq < 0:
            raise ValueError
        return seq
    except (ValueError, UnicodeDecodeError) as exc:
        raise CursorError("cursor tidak valid") from exc


def payload_hash(m: MutationIn) -> str:
    canonical = json.dumps(
        {
            "entity_type": m.entity_type.value,
            "entity_id": str(m.entity_id),
            "operation": m.operation.value,
            "base_version": m.base_version,
            "payload": m.payload,
        },
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


# ---------- Push ----------


class _PushContext:
    def __init__(self, session: Session, user_id: uuid.UUID) -> None:
        self.session = session
        self.user_id = user_id
        session.execute(
            pg_insert(SyncCounter).values(user_id=user_id, last_seq=0).on_conflict_do_nothing()
        )
        self.counter = session.execute(
            select(SyncCounter).where(SyncCounter.user_id == user_id).with_for_update()
        ).scalar_one()
        self.now = datetime.now(UTC)

    def record_change(self, entity_type: EntityType, entity_id: uuid.UUID) -> None:
        self.counter.last_seq += 1
        stmt = pg_insert(SyncChange).values(
            user_id=self.user_id, entity_type=entity_type.value, entity_id=entity_id,
            seq=self.counter.last_seq,
        )
        self.session.execute(
            stmt.on_conflict_do_update(
                index_elements=["user_id", "entity_type", "entity_id"],
                set_={"seq": stmt.excluded.seq},
            )
        )
        # Flush agar unique (user_id, seq) tetap valid untuk perubahan berikutnya dalam batch.
        self.session.flush()


def push(session: Session, user_id: uuid.UUID, mutations: list[MutationIn]) -> list[MutationResult]:
    ctx = _PushContext(session, user_id)
    results: list[MutationResult] = []
    for m in mutations:
        digest = payload_hash(m)
        stored = session.get(SyncMutation, (user_id, m.mutation_id))
        if stored is not None:
            if stored.payload_hash == digest:
                results.append(MutationResult.model_validate(stored.result))
            else:
                results.append(_rejected(m, "mutation_id_reused"))
            continue
        with session.begin_nested():
            result = _apply(ctx, m)
        session.add(
            SyncMutation(
                user_id=user_id, mutation_id=m.mutation_id, payload_hash=digest,
                result=result.model_dump(mode="json"),
            )
        )
        session.flush()
        results.append(result)
    session.commit()
    return results


def _rejected(m: MutationIn, code: str) -> MutationResult:
    return MutationResult(
        mutation_id=m.mutation_id, entity_type=m.entity_type, entity_id=m.entity_id,
        status="rejected", error_code=code,
    )


def _applied(m: MutationIn, version: int) -> MutationResult:
    return MutationResult(
        mutation_id=m.mutation_id, entity_type=m.entity_type, entity_id=m.entity_id,
        status="applied", version=version,
    )


def _conflict(m: MutationIn, row: Course | Note | Task) -> MutationResult:
    return MutationResult(
        mutation_id=m.mutation_id, entity_type=m.entity_type, entity_id=m.entity_id,
        status="conflict", version=row.version, server_entity=entity_to_dict(m.entity_type, row),
    )


def _parent_ok(ctx: _PushContext, course_id: uuid.UUID) -> bool:
    course = ctx.session.get(Course, course_id)
    return course is not None and course.user_id == ctx.user_id and course.deleted_at is None


def _apply(ctx: _PushContext, m: MutationIn) -> MutationResult:
    model = _MODELS[m.entity_type]
    row = ctx.session.get(model, m.entity_id, with_for_update=True)
    if row is not None and row.user_id != ctx.user_id:
        # Jangan bocorkan keberadaan data milik pengguna lain.
        return _rejected(m, "not_found")

    if m.operation is Operation.DELETE:
        if row is None:
            return _rejected(m, "not_found")
        if row.deleted_at is not None:
            return _applied(m, row.version)
        if row.version != m.base_version:
            return _conflict(m, row)
        _tombstone(ctx, m.entity_type, row)
        return _applied(m, row.version)

    # Cek versi sebelum validasi payload/induk: klien butuh salinan server untuk menyelesaikan konflik.
    if m.operation is Operation.CREATE and row is not None:
        return _conflict(m, row)
    if m.operation is Operation.UPDATE:
        if row is None:
            return _rejected(m, "not_found")
        if row.version != m.base_version:
            return _conflict(m, row)

    try:
        payload = _PAYLOADS[m.entity_type].model_validate(m.payload)
    except ValidationError:
        return _rejected(m, "invalid_payload")
    fields = _fields_from_payload(m.entity_type, payload)
    if "course_id" in fields and not _parent_ok(ctx, fields["course_id"]):
        return _rejected(m, "parent_not_found")

    if m.operation is Operation.CREATE:
        row = model(id=m.entity_id, user_id=ctx.user_id, version=1, created_at=ctx.now,
                    updated_at=ctx.now, **fields)
        ctx.session.add(row)
    else:
        for key, value in fields.items():
            setattr(row, key, value)
        # Update dengan base_version yang cocok juga memulihkan entity yang terhapus
        # (dipakai saat pengguna memilih "pakai versi saya" pada konflik hapus).
        row.deleted_at = None
        row.version += 1
        row.updated_at = ctx.now
    ctx.session.flush()
    ctx.record_change(m.entity_type, row.id)
    return _applied(m, row.version)


def _fields_from_payload(entity_type: EntityType, payload: BaseModel) -> dict[str, Any]:
    data = payload.model_dump()
    if entity_type is EntityType.TASK:
        data["due_at"] = datetime.fromtimestamp(data["due_at"] / 1000, tz=UTC)
    return data


def _tombstone(ctx: _PushContext, entity_type: EntityType, row: Course | Note | Task) -> None:
    row.deleted_at = ctx.now
    row.updated_at = ctx.now
    row.version += 1
    ctx.session.flush()
    ctx.record_change(entity_type, row.id)
    if entity_type is EntityType.COURSE:
        for child_type, child_model in ((EntityType.NOTE, Note), (EntityType.TASK, Task)):
            children = ctx.session.scalars(
                select(child_model).where(
                    child_model.user_id == ctx.user_id,
                    child_model.course_id == row.id,
                    child_model.deleted_at.is_(None),
                ).with_for_update()
            ).all()
            for child in children:
                _tombstone(ctx, child_type, child)


# ---------- Pull ----------


def pull(session: Session, user_id: uuid.UUID, cursor: str | None, limit: int) -> PullResponse:
    after = decode_cursor(cursor)
    counter = session.get(SyncCounter, user_id)
    if after > (counter.last_seq if counter else 0):
        raise CursorError("cursor di luar jangkauan")
    rows = session.scalars(
        select(SyncChange)
        .where(SyncChange.user_id == user_id, SyncChange.seq > after)
        .order_by(SyncChange.seq)
        .limit(limit + 1)
    ).all()
    has_more = len(rows) > limit
    rows = rows[:limit]

    entities: dict[tuple[str, uuid.UUID], Course | Note | Task] = {}
    for entity_type in EntityType:
        ids = [r.entity_id for r in rows if r.entity_type == entity_type.value]
        if ids:
            model = _MODELS[entity_type]
            for e in session.scalars(select(model).where(model.user_id == user_id, model.id.in_(ids))):
                entities[(entity_type.value, e.id)] = e

    changes = [
        ChangeOut(entity_type=EntityType(r.entity_type),
                  entity=entity_to_dict(EntityType(r.entity_type), entities[(r.entity_type, r.entity_id)]))
        for r in rows
        if (r.entity_type, r.entity_id) in entities
    ]
    next_seq = rows[-1].seq if rows else after
    return PullResponse(changes=changes, next_cursor=encode_cursor(next_seq), has_more=has_more)
