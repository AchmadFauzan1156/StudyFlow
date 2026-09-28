from dataclasses import dataclass
from typing import Protocol

from app.domain.schemas import GenerationResult, QuizDraft, SummaryDraft


@dataclass(frozen=True)
class SourceText:
    """Teks materi yang sudah diambil backend dari database berdasarkan user_id dan versi."""

    source_id: str
    title: str
    text: str


class ProviderUnavailableError(Exception):
    """Provider tidak dikonfigurasi atau sedang tidak tersedia (dipetakan ke HTTP 503)."""


class AIProvider(Protocol):
    def summarize(self, source: SourceText) -> GenerationResult[SummaryDraft]: ...

    def generate_quiz(self, source: SourceText, question_count: int) -> GenerationResult[QuizDraft]: ...
