import uuid
from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.api.auth import CurrentUser
from app.core.config import Settings, get_settings
from app.db.session import DbSession
from app.domain import progress

router = APIRouter(prefix="/v1")

SettingsDep = Annotated[Settings, Depends(get_settings)]


def _ms(dt: datetime | None) -> int | None:
    return None if dt is None else int(dt.timestamp() * 1000)


class TopicOut(BaseModel):
    topic_id: str
    label: str
    note_id: uuid.UUID
    note_title: str | None
    course_id: uuid.UUID | None
    answered: int
    correct: int
    accuracy: float
    needs_practice: bool
    last_answered_at: int | None


class ProgressOut(BaseModel):
    topics: list[TopicOut]
    min_answers: int
    accuracy_threshold: float


@router.get("/progress/topics", response_model=ProgressOut)
def topics(user_id: CurrentUser, db: DbSession, settings: SettingsDep, course_id: uuid.UUID | None = None) -> ProgressOut:
    stats = progress.topic_stats(db, user_id, course_id, settings.practice_min_answers,
                                 settings.practice_accuracy_threshold)
    return ProgressOut(
        topics=[TopicOut(topic_id=s.topic_id, label=s.label, note_id=s.note_id, note_title=s.note_title,
                         course_id=s.course_id, answered=s.answered, correct=s.correct,
                         accuracy=round(s.accuracy, 4), needs_practice=s.needs_practice,
                         last_answered_at=_ms(s.last_answered_at)) for s in stats],
        min_answers=settings.practice_min_answers,
        accuracy_threshold=settings.practice_accuracy_threshold,
    )


class FlashcardOut(BaseModel):
    question_id: uuid.UUID
    question: str
    answer: str
    explanation: str
    topic_label: str
    note_title: str | None
    box: int


class FlashcardsOut(BaseModel):
    cards: list[FlashcardOut]
    due_total: int


@router.get("/flashcards/due", response_model=FlashcardsOut)
def due(user_id: CurrentUser, db: DbSession, settings: SettingsDep) -> FlashcardsOut:
    cards, total = progress.due_flashcards(db, user_id, settings.flashcard_batch)
    return FlashcardsOut(
        cards=[FlashcardOut(question_id=c.question_id, question=c.question, answer=c.answer,
                            explanation=c.explanation, topic_label=c.topic_label, note_title=c.note_title,
                            box=c.box) for c in cards],
        due_total=total,
    )


class ReviewRequest(BaseModel):
    remembered: bool


class ReviewOut(BaseModel):
    question_id: uuid.UUID
    box: int
    due_at: int | None


@router.post("/flashcards/{question_id}/review", response_model=ReviewOut)
def review(question_id: uuid.UUID, body: ReviewRequest, user_id: CurrentUser, db: DbSession) -> ReviewOut:
    card = progress.review(db, user_id, question_id, body.remembered)
    return ReviewOut(question_id=card.question_id, box=card.box, due_at=_ms(card.due_at))
