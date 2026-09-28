"""Progres per topik, rekomendasi latihan (rule-based), dan flashcard Leitner.

Soal yang dilaporkan bermasalah tidak dihitung. Jawaban kosong tidak dihitung sebagai percobaan.
Ambang rekomendasi adalah keputusan produk yang perlu dievaluasi, bukan ukuran ilmiah penguasaan.
"""

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, exists, func, select
from sqlalchemy.orm import Session

from app.api.errors import ApiError
from app.db.models import (
    Answer,
    Attempt,
    FlashcardReview,
    Note,
    Question,
    QuestionKey,
    QuestionReport,
    Quiz,
)

# Interval hari per kotak Leitner (1..5).
LEITNER_DAYS = {1: 1, 2: 2, 3: 4, 4: 8, 5: 16}


@dataclass(frozen=True)
class TopicStat:
    topic_id: str
    label: str
    note_id: uuid.UUID
    note_title: str | None
    course_id: uuid.UUID | None
    answered: int
    correct: int
    last_answered_at: datetime | None
    needs_practice: bool

    @property
    def accuracy(self) -> float:
        return self.correct / self.answered if self.answered else 0.0


def topic_label(topic_id: str) -> str:
    slug = topic_id.split(":", 1)[-1]
    return slug.replace("-", " ").strip().capitalize() or "Umum"


def _not_reported(user_id: uuid.UUID):
    return ~exists().where(and_(QuestionReport.question_id == Question.id, QuestionReport.user_id == user_id))


def topic_stats(session: Session, user_id: uuid.UUID, course_id: uuid.UUID | None, min_answers: int,
                threshold: float) -> list[TopicStat]:
    stmt = (
        select(Question.topic_id, Quiz.source_id, Note.title, Note.course_id, func.count().label("answered"),
               func.count().filter(Answer.correct.is_(True)).label("correct"), func.max(Attempt.submitted_at))
        .select_from(Answer)
        .join(Attempt, Attempt.id == Answer.attempt_id)
        .join(Question, Question.id == Answer.question_id)
        .join(Quiz, Quiz.id == Question.quiz_id)
        .outerjoin(Note, and_(Note.id == Quiz.source_id, Note.user_id == user_id))
        .where(Attempt.user_id == user_id, Question.user_id == user_id, Quiz.user_id == user_id,
               Answer.selected_option_id.is_not(None), _not_reported(user_id))
        .group_by(Question.topic_id, Quiz.source_id, Note.title, Note.course_id)
    )
    if course_id is not None:
        stmt = stmt.where(Note.course_id == course_id)
    stats = []
    for topic, note_id, title, course, answered, right, last in session.execute(stmt).all():
        needs = answered >= min_answers and right / answered < threshold
        stats.append(TopicStat(topic, topic_label(topic), note_id, title, course, answered, right, last, needs))
    # Topik yang perlu latihan dulu, lalu akurasi terendah.
    return sorted(stats, key=lambda s: (not s.needs_practice, s.accuracy, s.label))


# ---------- Flashcard ----------


@dataclass(frozen=True)
class Flashcard:
    question_id: uuid.UUID
    question: str
    answer: str
    explanation: str
    topic_label: str
    note_title: str | None
    box: int
    due_at: datetime | None


def _wrongly_answered(user_id: uuid.UUID):
    return exists().where(and_(
        Answer.question_id == Question.id, Answer.correct.is_(False), Answer.selected_option_id.is_not(None),
        Attempt.id == Answer.attempt_id, Attempt.user_id == user_id,
    ))


def due_flashcards(session: Session, user_id: uuid.UUID, limit: int, now: datetime | None = None) -> tuple[list[Flashcard], int]:
    """Kartu dari soal yang pernah dijawab salah dan belum dilaporkan; jatuh tempo atau belum pernah diulang."""
    now = now or datetime.now(UTC)
    due = FlashcardReview.due_at.is_(None) | (FlashcardReview.due_at <= now)
    base = (
        select(Question, QuestionKey, FlashcardReview.box, FlashcardReview.due_at, Note.title)
        .join(QuestionKey, QuestionKey.question_id == Question.id)
        .join(Quiz, Quiz.id == Question.quiz_id)
        .outerjoin(Note, and_(Note.id == Quiz.source_id, Note.user_id == user_id))
        .outerjoin(FlashcardReview, and_(FlashcardReview.question_id == Question.id,
                                         FlashcardReview.user_id == user_id))
        .where(Question.user_id == user_id, Quiz.user_id == user_id, _wrongly_answered(user_id),
               _not_reported(user_id), due)
    )
    total = session.execute(select(func.count()).select_from(base.subquery())).scalar_one()
    rows = session.execute(base.order_by(FlashcardReview.due_at.nulls_first(), Question.id).limit(limit)).all()
    cards = []
    for question, key, box, due_at, title in rows:
        answer = next((o["text"] for o in question.options if o["id"] == key.correct_option_id), "")
        cards.append(Flashcard(question.id, question.question, answer, key.explanation, topic_label(question.topic_id),
                               title, box or 1, due_at))
    return cards, total


def review(session: Session, user_id: uuid.UUID, question_id: uuid.UUID, remembered: bool,
           now: datetime | None = None) -> FlashcardReview:
    now = now or datetime.now(UTC)
    eligible = session.execute(
        select(Question.id).where(Question.id == question_id, Question.user_id == user_id,
                                  _wrongly_answered(user_id))
    ).first()
    if eligible is None:
        raise ApiError(404, "not_found", "Kartu tidak ditemukan")
    card = session.get(FlashcardReview, (user_id, question_id), with_for_update=True)
    box = 1 if card is None else card.box
    box = min(box + 1, 5) if remembered else 1
    due = now + timedelta(days=LEITNER_DAYS[box])
    if card is None:
        card = FlashcardReview(user_id=user_id, question_id=question_id, box=box, due_at=due, reviewed_at=now)
        session.add(card)
    else:
        card.box, card.due_at, card.reviewed_at = box, due, now
    session.commit()
    return card
