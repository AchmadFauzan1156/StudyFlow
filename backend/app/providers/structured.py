"""Konversi output terstruktur model menjadi draft domain. Dipakai semua adapter provider."""

import re
from typing import Any

from pydantic import BaseModel, ValidationError

from app.domain.schemas import (
    AnswerDraft,
    GenerationResult,
    QuizDraft,
    QuizOption,
    QuizQuestion,
    SourceRef,
    SummaryDraft,
)
from app.providers import prompts
from app.providers.base import ProviderInvalidOutputError, SourceText

_OPTION_IDS = "abcdefgh"


class _SummaryOut(BaseModel):
    summary: str
    key_points: list[str]


class _QuestionOut(BaseModel):
    topic: str
    question: str
    options: list[str]
    correct_index: int
    explanation: str


class _QuizOut(BaseModel):
    title: str
    questions: list[_QuestionOut]


class _AnswerOut(BaseModel):
    answerable: bool
    answer: str
    source_ids: list[str]


def topic_id(source_id: str, topic: str) -> str:
    """ID topik stabil per materi: slug label topik (dipakai untuk progres per topik)."""
    slug = re.sub(r"[^a-z0-9]+", "-", topic.casefold()).strip("-")[:40] or "umum"
    return f"{source_id}:{slug}"


def parse[T: BaseModel](model: type[T], data: Any) -> T:
    try:
        return model.model_validate(data)
    except ValidationError as exc:
        raise ProviderInvalidOutputError("output tidak sesuai schema") from exc


def summary_result(data: Any, source: SourceText, model_id: str, usage: dict) -> GenerationResult[SummaryDraft]:
    parsed = parse(_SummaryOut, data)
    points = "\n".join(f"• {p.strip()}" for p in parsed.key_points if p.strip())
    content = parsed.summary.strip() + (f"\n\n{points}" if points else "")
    if not content.strip():
        raise ProviderInvalidOutputError("rangkuman kosong")
    return GenerationResult[SummaryDraft](
        output=SummaryDraft(content=content, source_refs=[SourceRef(source_id=source.source_id)]),
        model_id=model_id, prompt_version=prompts.PROMPT_VERSION, is_demo=False, **usage,
    )


def quiz_result(data: Any, source: SourceText, model_id: str, usage: dict) -> GenerationResult[QuizDraft]:
    parsed = parse(_QuizOut, data)
    questions = []
    for i, q in enumerate(parsed.questions):
        options = [QuizOption(id=_OPTION_IDS[j], text=t.strip()) for j, t in enumerate(q.options[: len(_OPTION_IDS)])]
        if not 0 <= q.correct_index < len(options):
            raise ProviderInvalidOutputError(f"correct_index di luar jangkauan pada soal {i + 1}")
        questions.append(
            QuizQuestion(
                id=f"q{i + 1}",
                topic_id=topic_id(source.source_id, q.topic),
                question=q.question.strip(),
                options=options,
                correct_option_id=options[q.correct_index].id,
                explanation=q.explanation.strip(),
                source_refs=[SourceRef(source_id=source.source_id)],
            )
        )
    try:
        draft = QuizDraft(title=parsed.title.strip() or source.title, questions=questions)
    except ValidationError as exc:
        raise ProviderInvalidOutputError("struktur kuis tidak valid") from exc
    return GenerationResult[QuizDraft](
        output=draft, model_id=model_id, prompt_version=prompts.PROMPT_VERSION, is_demo=False, **usage,
    )


def answer_result(data: Any, model_id: str, usage: dict) -> GenerationResult[AnswerDraft]:
    parsed = parse(_AnswerOut, data)
    try:
        draft = AnswerDraft(answerable=parsed.answerable, answer=parsed.answer.strip(),
                            source_ids=[s.strip() for s in parsed.source_ids])
    except ValidationError as exc:
        raise ProviderInvalidOutputError("jawaban tidak valid") from exc
    return GenerationResult[AnswerDraft](
        output=draft, model_id=model_id, prompt_version=prompts.PROMPT_VERSION, is_demo=False, **usage,
    )
