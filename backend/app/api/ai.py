import uuid
from datetime import datetime
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, Query, Response
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.api.auth import CurrentUser
from app.api.errors import ApiError
from app.core.config import AIProviderKind, Settings, get_settings
from app.db.models import (
    AIJob,
    Answer,
    Attempt,
    Note,
    Question,
    QuestionKey,
    QuestionReport,
    Quiz,
    Summary,
)
from app.db.session import DbSession
from app.domain import ai
from app.providers.factory import provider_mode

router = APIRouter(prefix="/v1")

SettingsDep = Annotated[Settings, Depends(get_settings)]
IdempotencyKey = Annotated[str, Header(alias="Idempotency-Key", min_length=8, max_length=128)]


def _ms(dt: datetime | None) -> int | None:
    return None if dt is None else int(dt.timestamp() * 1000)


# ---------- Info & kuota ----------


class AIInfo(BaseModel):
    available: bool
    mode: str
    provider_label: str | None
    model: str | None
    data_notice: str | None
    daily_limit: int
    used_today: int
    max_input_chars: int
    quiz_min_questions: int
    quiz_max_questions: int


@router.get("/ai/info", response_model=AIInfo)
def ai_info(user_id: CurrentUser, db: DbSession, settings: SettingsDep) -> AIInfo:
    mode = provider_mode(settings)
    live = settings.ai_provider is AIProviderKind.OPENAI_COMPATIBLE
    demo = settings.ai_provider is AIProviderKind.FAKE
    return AIInfo(
        available=mode != "unconfigured",
        mode=mode,
        provider_label=settings.ai_provider_label if live else ("Demo (bukan AI)" if demo else None),
        model=settings.ai_model if live else None,
        data_notice=settings.ai_data_notice if live else (
            "Mode demo: hasil dibuat otomatis dari kalimat catatan tanpa AI dan tidak dikirim ke pihak lain."
            if demo else None
        ),
        daily_limit=settings.ai_user_daily_limit,
        used_today=ai.usage_today(db, user_id),
        max_input_chars=settings.ai_max_input_chars,
        quiz_min_questions=settings.quiz_min_questions,
        quiz_max_questions=settings.quiz_max_questions,
    )


# ---------- Membuat job ----------


class SummaryRequest(BaseModel):
    note_id: uuid.UUID
    note_version: int = Field(ge=1)


class QuizRequest(SummaryRequest):
    question_count: int = Field(default=5)


class JobOut(BaseModel):
    id: uuid.UUID
    type: str
    status: str
    source_id: uuid.UUID
    source_version: int
    error_code: str | None
    result_id: uuid.UUID | None
    created_at: int | None


def _job_out(job: AIJob) -> JobOut:
    return JobOut(id=job.id, type=job.type, status=job.status, source_id=job.source_id,
                  source_version=job.source_version, error_code=job.error_code if job.status == "failed" else None,
                  result_id=job.result_id, created_at=_ms(job.created_at))


def _require_ai(settings: Settings) -> None:
    if settings.ai_provider is AIProviderKind.NONE:
        raise ApiError(503, "service_unavailable", "Fitur AI belum dikonfigurasi di server ini")


@router.post("/summaries", response_model=JobOut, status_code=202)
def create_summary(body: SummaryRequest, key: IdempotencyKey, user_id: CurrentUser, db: DbSession,
                   settings: SettingsDep) -> JobOut:
    _require_ai(settings)
    job = ai.enqueue(db, settings, user_id, ai.JobRequest("summary", body.note_id, body.note_version, {}, key))
    return _job_out(job)


@router.post("/quizzes", response_model=JobOut, status_code=202)
def create_quiz(body: QuizRequest, key: IdempotencyKey, user_id: CurrentUser, db: DbSession,
                settings: SettingsDep) -> JobOut:
    _require_ai(settings)
    if not settings.quiz_min_questions <= body.question_count <= settings.quiz_max_questions:
        raise ApiError(422, "invalid_input",
                       f"Jumlah soal harus {settings.quiz_min_questions}–{settings.quiz_max_questions}")
    job = ai.enqueue(db, settings, user_id, ai.JobRequest(
        "quiz", body.note_id, body.note_version, {"question_count": body.question_count}, key))
    return _job_out(job)


@router.get("/jobs/{job_id}", response_model=JobOut)
def get_job(job_id: uuid.UUID, user_id: CurrentUser, db: DbSession) -> JobOut:
    job = db.get(AIJob, job_id)
    if job is None or job.user_id != user_id:
        raise ApiError(404, "not_found", "Job tidak ditemukan")
    return _job_out(job)


# ---------- Hasil ----------


class SourceInfo(BaseModel):
    note_id: uuid.UUID
    title: str | None
    version: int
    current_version: int | None
    outdated: bool
    deleted: bool


def _source_info(db: DbSession, user_id: uuid.UUID, note_id: uuid.UUID, version: int) -> SourceInfo:
    note = db.scalars(select(Note).where(Note.id == note_id, Note.user_id == user_id)).one_or_none()
    deleted = note is None or note.deleted_at is not None
    current = None if note is None else note.version
    return SourceInfo(note_id=note_id, title=None if note is None else note.title, version=version,
                      current_version=current, outdated=current is not None and current != version, deleted=deleted)


class SummaryOut(BaseModel):
    id: uuid.UUID
    content: str
    source: SourceInfo
    model_id: str
    prompt_version: str
    is_demo: bool
    created_at: int | None


@router.get("/summaries/{summary_id}", response_model=SummaryOut)
def get_summary(summary_id: uuid.UUID, user_id: CurrentUser, db: DbSession) -> SummaryOut:
    s = db.get(Summary, summary_id)
    if s is None or s.user_id != user_id:
        raise ApiError(404, "not_found", "Rangkuman tidak ditemukan")
    return SummaryOut(id=s.id, content=s.content, source=_source_info(db, user_id, s.source_id, s.source_version),
                      model_id=s.model_id, prompt_version=s.prompt_version, is_demo=s.is_demo,
                      created_at=_ms(s.created_at))


class QuestionOut(BaseModel):
    id: uuid.UUID
    position: int
    topic_id: str
    question: str
    options: list[dict[str, str]]


class QuizOut(BaseModel):
    id: uuid.UUID
    title: str
    source: SourceInfo
    model_id: str
    is_demo: bool
    created_at: int | None
    questions: list[QuestionOut]


@router.get("/quizzes/{quiz_id}", response_model=QuizOut)
def get_quiz(quiz_id: uuid.UUID, user_id: CurrentUser, db: DbSession) -> QuizOut:
    quiz = ai.owned_quiz(db, user_id, quiz_id)
    questions = db.scalars(
        select(Question).where(Question.quiz_id == quiz.id, Question.user_id == user_id).order_by(Question.position)
    ).all()
    # Kunci jawaban dan pembahasan sengaja tidak dikirim sebelum submission.
    return QuizOut(
        id=quiz.id, title=quiz.title, source=_source_info(db, user_id, quiz.source_id, quiz.source_version),
        model_id=quiz.model_id, is_demo=quiz.is_demo, created_at=_ms(quiz.created_at),
        questions=[QuestionOut(id=q.id, position=q.position, topic_id=q.topic_id, question=q.question,
                               options=q.options) for q in questions],
    )


# ---------- Attempt ----------


class AttemptRequest(BaseModel):
    answers: dict[uuid.UUID, str | None]


class AnswerResult(BaseModel):
    question_id: uuid.UUID
    selected_option_id: str | None
    correct_option_id: str
    correct: bool
    explanation: str
    sources: list[dict[str, Any]]


class AttemptOut(BaseModel):
    id: uuid.UUID
    quiz_id: uuid.UUID
    score: int
    total: int
    submitted_at: int | None
    results: list[AnswerResult]


def _attempt_out(db: DbSession, user_id: uuid.UUID, attempt: Attempt) -> AttemptOut:
    rows = db.execute(
        select(Answer, QuestionKey, Question.position)
        .join(QuestionKey, QuestionKey.question_id == Answer.question_id)
        .join(Question, Question.id == Answer.question_id)
        .where(Answer.attempt_id == attempt.id, Question.user_id == user_id)
        .order_by(Question.position)
    ).all()
    titles: dict[str, str | None] = {}

    def title_of(source_id: str) -> str | None:
        # Judul sumber dipetakan dari metadata server, bukan dari teks buatan model.
        if source_id not in titles:
            note = db.scalars(select(Note).where(Note.id == uuid.UUID(source_id), Note.user_id == user_id)).one_or_none()
            titles[source_id] = None if note is None else note.title
        return titles[source_id]

    results = [
        AnswerResult(
            question_id=answer.question_id, selected_option_id=answer.selected_option_id,
            correct_option_id=key.correct_option_id, correct=answer.correct, explanation=key.explanation,
            sources=[{"source_id": r["source_id"], "title": title_of(r["source_id"])} for r in key.source_refs],
        )
        for answer, key, _ in rows
    ]
    return AttemptOut(id=attempt.id, quiz_id=attempt.quiz_id, score=attempt.score, total=attempt.total,
                      submitted_at=_ms(attempt.submitted_at), results=results)


@router.post("/quizzes/{quiz_id}/attempts", response_model=AttemptOut)
def submit_attempt(quiz_id: uuid.UUID, body: AttemptRequest, key: IdempotencyKey, user_id: CurrentUser,
                   db: DbSession) -> AttemptOut:
    attempt_id = ai.submit_attempt(db, user_id, quiz_id, body.answers, key)
    return _attempt_out(db, user_id, db.get(Attempt, attempt_id))


class AttemptSummary(BaseModel):
    id: uuid.UUID
    quiz_id: uuid.UUID
    quiz_title: str
    score: int
    total: int
    submitted_at: int | None


@router.get("/attempts", response_model=list[AttemptSummary])
def list_attempts(user_id: CurrentUser, db: DbSession, quiz_id: uuid.UUID | None = None,
                  limit: Annotated[int, Query(ge=1, le=100)] = 50) -> list[AttemptSummary]:
    stmt = (select(Attempt, Quiz.title).join(Quiz, Quiz.id == Attempt.quiz_id)
            .where(Attempt.user_id == user_id).order_by(Attempt.submitted_at.desc()).limit(limit))
    if quiz_id is not None:
        stmt = stmt.where(Attempt.quiz_id == quiz_id)
    return [AttemptSummary(id=a.id, quiz_id=a.quiz_id, quiz_title=title, score=a.score, total=a.total,
                           submitted_at=_ms(a.submitted_at)) for a, title in db.execute(stmt).all()]


@router.get("/attempts/{attempt_id}", response_model=AttemptOut)
def get_attempt(attempt_id: uuid.UUID, user_id: CurrentUser, db: DbSession) -> AttemptOut:
    attempt = db.get(Attempt, attempt_id)
    if attempt is None or attempt.user_id != user_id:
        raise ApiError(404, "not_found", "Hasil kuis tidak ditemukan")
    return _attempt_out(db, user_id, attempt)


# ---------- Hasil AI per catatan & laporan soal ----------


class NoteResult(BaseModel):
    type: str
    id: uuid.UUID
    title: str | None
    source_version: int
    is_demo: bool
    created_at: int | None
    best_score: int | None = None
    total: int | None = None


class NoteResults(BaseModel):
    results: list[NoteResult]
    pending_jobs: list[JobOut]


@router.get("/notes/{note_id}/ai-results", response_model=NoteResults)
def note_results(note_id: uuid.UUID, user_id: CurrentUser, db: DbSession) -> NoteResults:
    summaries = db.scalars(select(Summary).where(Summary.user_id == user_id, Summary.source_id == note_id)).all()
    best = (select(Attempt.quiz_id, func.max(Attempt.score).label("best"))
            .where(Attempt.user_id == user_id).group_by(Attempt.quiz_id).subquery())
    quizzes = db.execute(
        select(Quiz, best.c.best, func.count(Question.id))
        .outerjoin(best, best.c.quiz_id == Quiz.id)
        .join(Question, Question.quiz_id == Quiz.id)
        .where(Quiz.user_id == user_id, Quiz.source_id == note_id)
        .group_by(Quiz.id, best.c.best)
    ).all()
    results = [NoteResult(type="summary", id=s.id, title=None, source_version=s.source_version, is_demo=s.is_demo,
                          created_at=_ms(s.created_at)) for s in summaries]
    results += [NoteResult(type="quiz", id=q.id, title=q.title, source_version=q.source_version, is_demo=q.is_demo,
                           created_at=_ms(q.created_at), best_score=b, total=n) for q, b, n in quizzes]
    results.sort(key=lambda r: r.created_at or 0, reverse=True)
    jobs = db.scalars(
        select(AIJob).where(AIJob.user_id == user_id, AIJob.source_id == note_id,
                            AIJob.status.in_(("queued", "running", "failed")))
        .order_by(AIJob.created_at.desc()).limit(10)
    ).all()
    return NoteResults(results=results, pending_jobs=[_job_out(j) for j in jobs])


class ReportRequest(BaseModel):
    reason: str | None = Field(default=None, max_length=500)


@router.post("/questions/{question_id}/report", status_code=204)
def report_question(question_id: uuid.UUID, body: ReportRequest, user_id: CurrentUser, db: DbSession) -> Response:
    question = db.get(Question, question_id)
    if question is None or question.user_id != user_id:
        raise ApiError(404, "not_found", "Soal tidak ditemukan")
    db.execute(pg_insert(QuestionReport).values(question_id=question_id, user_id=user_id, reason=body.reason)
               .on_conflict_do_nothing())
    db.commit()
    return Response(status_code=204)
