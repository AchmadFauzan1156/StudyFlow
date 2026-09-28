from dataclasses import dataclass
from typing import Protocol

from app.domain.schemas import AnswerDraft, GenerationResult, QuizDraft, SummaryDraft


@dataclass(frozen=True)
class SourceText:
    """Teks materi yang sudah diambil backend dari database berdasarkan user_id dan versi."""

    source_id: str
    title: str
    text: str


class ProviderUnavailableError(Exception):
    """Provider tidak dikonfigurasi (dipetakan ke HTTP 503 sebelum job dibuat)."""


class ProviderTransientError(Exception):
    """Gangguan sementara (429, timeout, 5xx). Job dijadwalkan ulang, bukan langsung gagal."""

    def __init__(self, message: str, retry_after_seconds: float | None = None) -> None:
        super().__init__(message)
        self.retry_after_seconds = retry_after_seconds


class ProviderInvalidOutputError(Exception):
    """Respons tidak bisa diparse atau tidak sesuai schema."""


class ProviderRejectedError(Exception):
    """Permintaan ditolak permanen oleh provider (mis. API key salah, input ditolak)."""


class AIProvider(Protocol):
    def summarize(self, source: SourceText) -> GenerationResult[SummaryDraft]: ...

    def generate_quiz(self, source: SourceText, question_count: int) -> GenerationResult[QuizDraft]: ...

    def answer(self, question: str, contexts: list["ContextChunk"]) -> GenerationResult[AnswerDraft]: ...


@dataclass(frozen=True)
class ContextChunk:
    """Potongan materi hasil retrieval yang dikirim ke model (sudah terfilter milik pengguna)."""

    chunk_id: str
    label: str
    text: str
