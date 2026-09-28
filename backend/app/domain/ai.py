"""Antrean job AI, kuota, penyimpanan hasil, dan penilaian kuis.

Semua query terikat user_id. Kuota direservasi atomik saat job dibuat sehingga permintaan paralel
tidak bisa melewati batas, dan dikembalikan jika job gagal karena sebab di luar kendali pengguna.
"""

import uuid
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from typing import Any

from sqlalchemy import select, text, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.errors import ApiError
from app.core.config import Settings
from app.db.models import (
    AIJob,
    AIUsageDaily,
    Answer,
    Attempt,
    Note,
    Question,
    QuestionKey,
    Quiz,
    Summary,
    UsageRecord,
)
from app.domain.schemas import GenerationResult, QuizDraft, SummaryDraft

GLOBAL_SCOPE = "global"

# Kode kegagalan yang bukan kesalahan pengguna: kuota dikembalikan.
REFUNDABLE_ERRORS = {"provider_unavailable", "provider_rejected", "invalid_output", "source_changed", "source_deleted"}


def utc_today() -> date:
    return datetime.now(UTC).date()


# ---------- Antrean ----------


@dataclass(frozen=True)
class JobRequest:
    type: str  # summary | quiz
    note_id: uuid.UUID
    note_version: int
    params: dict[str, Any]
    idempotency_key: str


def enqueue(session: Session, settings: Settings, user_id: uuid.UUID, req: JobRequest) -> AIJob:
    existing = _job_by_key(session, user_id, req.idempotency_key)
    if existing is not None:
        return _same_request_or_conflict(existing, req)

    note = session.get(Note, req.note_id)
    if note is None or note.user_id != user_id or note.deleted_at is not None:
        raise ApiError(404, "not_found", "Catatan tidak ditemukan")
    if note.version != req.note_version:
        raise ApiError(409, "source_version_mismatch",
                       "Catatan di server berbeda dengan versi yang dipilih. Sinkronkan dulu lalu coba lagi.")
    if not note.content.strip():
        raise ApiError(422, "source_empty", "Catatan masih kosong")
    size = len(note.title) + len(note.content)
    if size > settings.ai_max_input_chars:
        # Tidak memotong diam-diam: pengguna diberi tahu batasnya.
        raise ApiError(413, "source_too_large",
                       f"Catatan terlalu panjang untuk diproses ({size} karakter, maks. {settings.ai_max_input_chars}).",
                       {"length": size, "max": settings.ai_max_input_chars})

    today = utc_today()
    reserve_generative(session, settings, user_id, today)

    job = AIJob(
        id=uuid.uuid4(), user_id=user_id, type=req.type, source_type="note", source_id=note.id,
        source_version=note.version, params=req.params, idempotency_key=req.idempotency_key,
        status="queued", attempts=0, quota_day=today,
    )
    session.add(job)
    try:
        session.commit()
    except IntegrityError:
        # Permintaan paralel dengan idempotency key yang sama: kembalikan job yang menang.
        session.rollback()
        existing = _job_by_key(session, user_id, req.idempotency_key)
        if existing is None:
            raise
        return _same_request_or_conflict(existing, req)
    return job


def _job_by_key(session: Session, user_id: uuid.UUID, key: str) -> AIJob | None:
    return session.scalars(
        select(AIJob).where(AIJob.user_id == user_id, AIJob.idempotency_key == key)
    ).one_or_none()


def _same_request_or_conflict(job: AIJob, req: JobRequest) -> AIJob:
    same = (job.type, job.source_id, job.source_version, job.params) == (
        req.type, req.note_id, req.note_version, req.params,
    )
    if not same:
        raise ApiError(409, "idempotency_key_reused", "Idempotency-Key sudah dipakai untuk permintaan lain")
    return job


def _reserve(session: Session, scope: str, day: date, limit: int) -> bool:
    stmt = pg_insert(AIUsageDaily).values(scope=scope, day=day, count=1)
    stmt = stmt.on_conflict_do_update(
        index_elements=["scope", "day"],
        set_={"count": AIUsageDaily.count + 1},
        where=AIUsageDaily.count < limit,
    ).returning(AIUsageDaily.count)
    return limit > 0 and session.execute(stmt).scalar_one_or_none() is not None


def generative_scopes(user_id: uuid.UUID) -> list[str]:
    return [str(user_id), GLOBAL_SCOPE]


def index_scopes(user_id: uuid.UUID) -> list[str]:
    return [f"idx:{user_id}", "global:idx"]


def reserve_generative(session: Session, settings: Settings, user_id: uuid.UUID, day: date) -> None:
    if not _reserve(session, str(user_id), day, settings.ai_user_daily_limit):
        session.rollback()
        raise ApiError(429, "quota_exceeded",
                       f"Kuota AI harianmu ({settings.ai_user_daily_limit} permintaan) sudah habis. Coba lagi besok.")
    if not _reserve(session, GLOBAL_SCOPE, day, settings.ai_global_daily_limit):
        session.rollback()
        raise ApiError(429, "service_quota_exceeded", "Kuota harian layanan AI gratis sudah habis. Coba lagi besok.")


def reserve_indexing(session: Session, settings: Settings, user_id: uuid.UUID, day: date) -> None:
    user_scope, global_scope = index_scopes(user_id)
    if not _reserve(session, user_scope, day, settings.index_user_daily_limit):
        session.rollback()
        raise ApiError(429, "quota_exceeded",
                       f"Batas unggah materi harianmu ({settings.index_user_daily_limit} dokumen) sudah tercapai.")
    if not _reserve(session, global_scope, day, settings.index_global_daily_limit):
        session.rollback()
        raise ApiError(429, "service_quota_exceeded", "Kuota harian pemrosesan dokumen sudah habis. Coba lagi besok.")


def refund_quota(session: Session, job: AIJob) -> None:
    for scope in job.params.get("quota_scopes", generative_scopes(job.user_id)):
        session.execute(
            update(AIUsageDaily)
            .where(AIUsageDaily.scope == scope, AIUsageDaily.day == job.quota_day, AIUsageDaily.count > 0)
            .values(count=AIUsageDaily.count - 1)
        )


def usage_today(session: Session, user_id: uuid.UUID) -> int:
    row = session.get(AIUsageDaily, (str(user_id), utc_today()))
    return row.count if row else 0


# ---------- Worker: klaim, jadwal ulang, selesai ----------


def claim_next_job(session: Session, lease_seconds: int) -> tuple[uuid.UUID, int] | None:
    """Klaim satu job secara atomik. Job `running` yang lease-nya habis dapat diambil ulang."""
    row = session.execute(
        text(
            """
            UPDATE ai_jobs SET status = 'running', attempts = attempts + 1,
                lease_until = now() + make_interval(secs => :lease), heartbeat_at = now(), updated_at = now()
            WHERE id = (
                SELECT id FROM ai_jobs
                WHERE (status = 'queued' AND run_after <= now())
                   OR (status = 'running' AND lease_until < now())
                ORDER BY run_after, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING id, attempts
            """
        ),
        {"lease": lease_seconds},
    ).one_or_none()
    session.commit()
    return (row.id, row.attempts) if row else None


def lock_owned_job(session: Session, job_id: uuid.UUID, attempt: int) -> AIJob | None:
    """Job hanya boleh diubah oleh worker pemegang lease (attempt yang sama dan masih running)."""
    job = session.get(AIJob, job_id, with_for_update=True)
    if job is None or job.status != "running" or job.attempts != attempt:
        return None
    return job


def mark_succeeded(session: Session, job: AIJob, result_id: uuid.UUID | None, model_id: str | None,
                   input_units: int | None = None, output_units: int | None = None) -> None:
    """Menandai job selesai (dipanggil di dalam transaksi yang memegang lock job)."""
    job.status = "succeeded"
    job.result_id = result_id
    job.error_code = None
    job.lease_until = None
    job.updated_at = datetime.now(UTC)
    if model_id:
        session.add(UsageRecord(user_id=job.user_id, job_id=job.id, operation=job.type, model_id=model_id,
                                input_units=input_units, output_units=output_units, status="succeeded"))


def reschedule(session: Session, job_id: uuid.UUID, attempt: int, delay_seconds: float, code: str) -> None:
    job = lock_owned_job(session, job_id, attempt)
    if job is None:
        session.rollback()
        return
    job.status = "queued"
    job.run_after = datetime.now(UTC) + timedelta(seconds=delay_seconds)
    job.lease_until = None
    job.error_code = code
    job.updated_at = datetime.now(UTC)
    session.commit()


def fail(session: Session, job_id: uuid.UUID, attempt: int, code: str, model_id: str | None = None) -> None:
    job = lock_owned_job(session, job_id, attempt)
    if job is None:
        session.rollback()
        return
    job.status = "failed"
    job.error_code = code
    job.lease_until = None
    job.updated_at = datetime.now(UTC)
    if code in REFUNDABLE_ERRORS:
        refund_quota(session, job)
    if model_id:
        session.add(UsageRecord(user_id=job.user_id, job_id=job.id, operation=job.type, model_id=model_id,
                                status=f"failed:{code}"))
    session.commit()


def complete(session: Session, job_id: uuid.UUID, attempt: int,
             result: GenerationResult[SummaryDraft] | GenerationResult[QuizDraft]) -> bool:
    """Menyimpan hasil secara idempotent. False jika lease sudah hilang (hasil dibuang)."""
    job = lock_owned_job(session, job_id, attempt)
    if job is None:
        session.rollback()
        return False
    result_id = uuid.uuid4()
    common = {
        "id": result_id, "user_id": job.user_id, "job_id": job.id, "source_type": job.source_type,
        "source_id": job.source_id, "source_version": job.source_version, "model_id": result.model_id,
        "prompt_version": result.prompt_version, "is_demo": result.is_demo,
    }
    if job.type == "summary":
        session.add(Summary(content=result.output.content, **common))
    else:
        draft: QuizDraft = result.output
        session.add(Quiz(title=draft.title, **common))
        session.flush()
        for position, q in enumerate(draft.questions):
            question = Question(
                id=uuid.uuid4(), quiz_id=result_id, user_id=job.user_id, position=position,
                topic_id=q.topic_id, question=q.question, options=[o.model_dump() for o in q.options],
            )
            session.add(question)
            session.flush()
            session.add(QuestionKey(
                question_id=question.id, correct_option_id=q.correct_option_id, explanation=q.explanation,
                source_refs=[r.model_dump() for r in q.source_refs],
            ))
    job.status = "succeeded"
    job.result_id = result_id
    job.error_code = None
    job.lease_until = None
    job.updated_at = datetime.now(UTC)
    session.add(UsageRecord(user_id=job.user_id, job_id=job.id, operation=job.type, model_id=result.model_id,
                            input_units=result.input_units, output_units=result.output_units, status="succeeded"))
    session.commit()
    return True


# ---------- Penilaian kuis ----------


def submit_attempt(session: Session, user_id: uuid.UUID, quiz_id: uuid.UUID,
                   answers: dict[uuid.UUID, str | None], idempotency_key: str) -> uuid.UUID:
    quiz = owned_quiz(session, user_id, quiz_id)
    existing = session.scalars(
        select(Attempt).where(Attempt.user_id == user_id, Attempt.idempotency_key == idempotency_key)
    ).one_or_none()
    if existing is not None:
        if existing.quiz_id != quiz.id:
            raise ApiError(409, "idempotency_key_reused", "Idempotency-Key sudah dipakai untuk kuis lain")
        return existing.id

    rows = session.execute(
        select(Question.id, QuestionKey.correct_option_id)
        .join(QuestionKey, QuestionKey.question_id == Question.id)
        .where(Question.quiz_id == quiz.id, Question.user_id == user_id)
    ).all()
    keys = {row.id: row.correct_option_id for row in rows}
    unknown = set(answers) - set(keys)
    if unknown:
        raise ApiError(422, "invalid_input", "Jawaban memuat soal yang bukan bagian kuis ini")

    attempt = Attempt(id=uuid.uuid4(), user_id=user_id, quiz_id=quiz.id, idempotency_key=idempotency_key,
                      score=0, total=len(keys))
    session.add(attempt)
    session.flush()
    score = 0
    for question_id, correct_option in keys.items():
        selected = answers.get(question_id)
        is_correct = selected == correct_option
        score += int(is_correct)
        session.add(Answer(attempt_id=attempt.id, question_id=question_id, selected_option_id=selected,
                           correct=is_correct))
    attempt.score = score
    try:
        session.commit()
    except IntegrityError:
        # Pengiriman ganda paralel: kembalikan attempt yang sudah tersimpan.
        session.rollback()
        winner = session.scalars(
            select(Attempt).where(Attempt.user_id == user_id, Attempt.idempotency_key == idempotency_key)
        ).one()
        return winner.id
    return attempt.id


def owned_quiz(session: Session, user_id: uuid.UUID, quiz_id: uuid.UUID) -> Quiz:
    quiz = session.get(Quiz, quiz_id)
    if quiz is None or quiz.user_id != user_id:
        raise ApiError(404, "not_found", "Kuis tidak ditemukan")
    return quiz
