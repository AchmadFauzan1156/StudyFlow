"""Model SQLAlchemy. Skema kanonis dibuat oleh migrasi Alembic (backend/migrations)."""

import uuid
from datetime import date, datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    BigInteger,
    Boolean,
    Date,
    DateTime,
    ForeignKey,
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


# ---------- Fase 3: AI ----------


class AIJob(Base):
    """Pekerjaan AI persisten. Worker mengklaim dengan FOR UPDATE SKIP LOCKED dan lease."""

    __tablename__ = "ai_jobs"
    __table_args__ = (UniqueConstraint("user_id", "idempotency_key", name="uq_ai_jobs_idempotency"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    type: Mapped[str] = mapped_column(String(16))  # summary | quiz
    source_type: Mapped[str] = mapped_column(String(16))  # note
    source_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    source_version: Mapped[int] = mapped_column(Integer)
    params: Mapped[dict] = mapped_column(JSONB)
    idempotency_key: Mapped[str] = mapped_column(String(128))
    status: Mapped[str] = mapped_column(String(16))  # queued | running | succeeded | failed
    attempts: Mapped[int] = mapped_column(Integer, server_default="0")
    run_after: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    lease_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    heartbeat_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    quota_day: Mapped[date] = mapped_column(Date)
    error_code: Mapped[str | None] = mapped_column(String(64))
    result_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Summary(Base):
    __tablename__ = "summaries"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    job_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), unique=True)
    source_type: Mapped[str] = mapped_column(String(16))
    source_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    source_version: Mapped[int] = mapped_column(Integer)
    content: Mapped[str] = mapped_column(Text)
    model_id: Mapped[str] = mapped_column(String(120))
    prompt_version: Mapped[str] = mapped_column(String(40))
    is_demo: Mapped[bool] = mapped_column(Boolean)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Quiz(Base):
    __tablename__ = "quizzes"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    job_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), unique=True)
    source_type: Mapped[str] = mapped_column(String(16))
    source_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    source_version: Mapped[int] = mapped_column(Integer)
    title: Mapped[str] = mapped_column(String(200))
    model_id: Mapped[str] = mapped_column(String(120))
    prompt_version: Mapped[str] = mapped_column(String(40))
    is_demo: Mapped[bool] = mapped_column(Boolean)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Question(Base):
    """Soal yang boleh dikirim ke klien sebelum dijawab (tanpa kunci)."""

    __tablename__ = "questions"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    quiz_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("quizzes.id"), index=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    position: Mapped[int] = mapped_column(Integer)
    topic_id: Mapped[str] = mapped_column(String(120))
    question: Mapped[str] = mapped_column(Text)
    options: Mapped[list] = mapped_column(JSONB)


class QuestionKey(Base):
    """Kunci jawaban; hanya dibaca server saat menilai attempt."""

    __tablename__ = "question_keys"

    question_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("questions.id"), primary_key=True)
    correct_option_id: Mapped[str] = mapped_column(String(16))
    explanation: Mapped[str] = mapped_column(Text)
    source_refs: Mapped[list] = mapped_column(JSONB)


class Attempt(Base):
    __tablename__ = "attempts"
    __table_args__ = (UniqueConstraint("user_id", "idempotency_key", name="uq_attempts_idempotency"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    quiz_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("quizzes.id"), index=True)
    idempotency_key: Mapped[str] = mapped_column(String(128))
    score: Mapped[int] = mapped_column(Integer)
    total: Mapped[int] = mapped_column(Integer)
    submitted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Answer(Base):
    __tablename__ = "answers"

    attempt_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("attempts.id"), primary_key=True)
    question_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("questions.id"), primary_key=True)
    selected_option_id: Mapped[str | None] = mapped_column(String(16))
    correct: Mapped[bool] = mapped_column(Boolean)


class QuestionReport(Base):
    """Soal yang ditandai bermasalah oleh pengguna; tidak dihitung dalam progres."""

    __tablename__ = "question_reports"

    question_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("questions.id"), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    reason: Mapped[str | None] = mapped_column(String(500))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class AIUsageDaily(Base):
    """Kuota harian (UTC). scope = user_id atau 'global' (batas free tier provider)."""

    __tablename__ = "ai_usage_daily"

    scope: Mapped[str] = mapped_column(String(64), primary_key=True)
    day: Mapped[date] = mapped_column(Date, primary_key=True)
    count: Mapped[int] = mapped_column(Integer)


class UsageRecord(Base):
    __tablename__ = "usage_records"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    job_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    operation: Mapped[str] = mapped_column(String(16))
    model_id: Mapped[str] = mapped_column(String(120))
    input_units: Mapped[int | None] = mapped_column(Integer)
    output_units: Mapped[int | None] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(32))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


# ---------- Fase 4: dokumen & tanya materi ----------

EMBEDDING_DIM = 768  # harus sama dengan migrasi 0003 dan STUDYFLOW_EMBEDDING_DIM


class Document(Base):
    __tablename__ = "documents"
    __table_args__ = (
        ForeignKeyConstraint(["course_id", "user_id"], ["courses.id", "courses.user_id"],
                             name="fk_documents_course_owner"),
        UniqueConstraint("user_id", "idempotency_key", name="uq_documents_idempotency"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    course_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    title: Mapped[str] = mapped_column(String(200))
    idempotency_key: Mapped[str] = mapped_column(String(128))
    active_version_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class DocumentVersion(Base):
    __tablename__ = "document_versions"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    document_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("documents.id"), index=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    version_number: Mapped[int] = mapped_column(Integer)
    storage_key: Mapped[str] = mapped_column(String(300))
    content_hash: Mapped[str] = mapped_column(String(64))
    size_bytes: Mapped[int] = mapped_column(Integer)
    page_count: Mapped[int] = mapped_column(Integer)
    # queued | processing | ready | failed | unsupported
    status: Mapped[str] = mapped_column(String(16))
    error_code: Mapped[str | None] = mapped_column(String(64))
    warnings: Mapped[list] = mapped_column(JSONB, server_default="[]")
    embedding_model: Mapped[str | None] = mapped_column(String(120))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    ready_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class DocumentPage(Base):
    __tablename__ = "document_pages"

    version_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("document_versions.id"),
                                                  primary_key=True)
    page_number: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    text: Mapped[str] = mapped_column(Text)


class DocumentChunk(Base):
    __tablename__ = "document_chunks"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    document_version_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("document_versions.id"),
                                                           index=True)
    chunk_index: Mapped[int] = mapped_column(Integer)
    text: Mapped[str] = mapped_column(Text)
    page_start: Mapped[int] = mapped_column(Integer)
    page_end: Mapped[int] = mapped_column(Integer)
    char_start: Mapped[int] = mapped_column(Integer)
    char_end: Mapped[int] = mapped_column(Integer)
    embedding_model: Mapped[str] = mapped_column(String(120))
    embedding: Mapped[list] = mapped_column(Vector(EMBEDDING_DIM))


class ChatThread(Base):
    __tablename__ = "chat_threads"
    __table_args__ = (
        ForeignKeyConstraint(["course_id", "user_id"], ["courses.id", "courses.user_id"],
                             name="fk_chat_threads_course_owner"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    course_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    document_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class ChatMessage(Base):
    __tablename__ = "chat_messages"
    __table_args__ = (UniqueConstraint("user_id", "idempotency_key", name="uq_chat_messages_idempotency"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    thread_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("chat_threads.id"), index=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    role: Mapped[str] = mapped_column(String(16))  # user | assistant
    content: Mapped[str] = mapped_column(Text)
    source_refs: Mapped[list] = mapped_column(JSONB, server_default="[]")
    answerable: Mapped[bool | None] = mapped_column(Boolean)
    is_demo: Mapped[bool] = mapped_column(Boolean, server_default="false")
    model_id: Mapped[str | None] = mapped_column(String(120))
    reply_to: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    job_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True))
    idempotency_key: Mapped[str | None] = mapped_column(String(128))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


# ---------- Fase 5: flashcard ----------


class FlashcardReview(Base):
    """Jadwal ulang (sistem Leitner) untuk soal yang pernah dijawab salah."""

    __tablename__ = "flashcard_reviews"

    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    question_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("questions.id"), primary_key=True)
    box: Mapped[int] = mapped_column(Integer)
    due_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    reviewed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
