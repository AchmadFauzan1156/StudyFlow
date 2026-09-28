"""Model SQLAlchemy. Skema kanonis dibuat oleh migrasi Alembic (backend/migrations)."""

import uuid
from datetime import datetime

from sqlalchemy import (
    BigInteger,
    DateTime,
    ForeignKeyConstraint,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class Profile(Base):
    __tablename__ = "profiles"

    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    display_name: Mapped[str | None] = mapped_column(String(120))
    timezone: Mapped[str] = mapped_column(String(64), server_default="Asia/Jakarta")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SyncedEntity:
    """Kolom bersama entity yang disinkronkan."""

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    version: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Course(SyncedEntity, Base):
    __tablename__ = "courses"
    # Target FK komposit (id, user_id): anak hanya bisa menunjuk course milik pengguna yang sama.
    __table_args__ = (UniqueConstraint("id", "user_id", name="uq_courses_id_user"),)

    name: Mapped[str] = mapped_column(String(80))
    semester: Mapped[str | None] = mapped_column(String(40))


class Note(SyncedEntity, Base):
    __tablename__ = "notes"
    __table_args__ = (
        ForeignKeyConstraint(
            ["course_id", "user_id"], ["courses.id", "courses.user_id"], name="fk_notes_course_owner"
        ),
    )

    course_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    title: Mapped[str] = mapped_column(String(120))
    content: Mapped[str] = mapped_column(Text)


class Task(SyncedEntity, Base):
    __tablename__ = "tasks"
    __table_args__ = (
        ForeignKeyConstraint(
            ["course_id", "user_id"], ["courses.id", "courses.user_id"], name="fk_tasks_course_owner"
        ),
    )

    course_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    title: Mapped[str] = mapped_column(String(120))
    description: Mapped[str] = mapped_column(Text)
    due_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    time_zone_id: Mapped[str] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(16))


class SyncCounter(Base):
    """Satu baris per pengguna; di-lock FOR UPDATE untuk menserialkan mutasi dan urutan cursor."""

    __tablename__ = "sync_counters"

    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    last_seq: Mapped[int] = mapped_column(BigInteger, server_default="0")


class SyncChange(Base):
    """Satu baris per entity (di-upsert tiap perubahan); pull membaca baris dengan seq > cursor."""

    __tablename__ = "sync_changes"
    __table_args__ = (UniqueConstraint("user_id", "seq", name="uq_sync_changes_user_seq"),)

    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    entity_type: Mapped[str] = mapped_column(String(16), primary_key=True)
    entity_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    seq: Mapped[int] = mapped_column(BigInteger)


class SyncMutation(Base):
    """Hasil mutasi yang sudah diproses; retry dengan mutation_id sama mengembalikan hasil yang sama."""

    __tablename__ = "sync_mutations"

    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    mutation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    payload_hash: Mapped[str] = mapped_column(String(64))
    result: Mapped[dict] = mapped_column(JSONB)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
