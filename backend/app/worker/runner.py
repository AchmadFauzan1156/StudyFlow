"""Worker AI: proses terpisah dari API, satu codebase.

Jalankan: `uv run python -m app.worker`. Beberapa worker boleh berjalan bersamaan; klaim job memakai
FOR UPDATE SKIP LOCKED dan lease sehingga job tidak diproses ganda, dan job yang workernya mati
dapat diambil ulang setelah lease habis.
"""

import logging
import time
import uuid
from collections.abc import Callable

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.db.models import AIJob, Note
from app.domain import ai
from app.domain.quiz import QuizValidationError, validate_quiz
from app.providers.base import (
    AIProvider,
    ProviderInvalidOutputError,
    ProviderRejectedError,
    ProviderTransientError,
    SourceText,
)

logger = logging.getLogger("studyflow.worker")

INVALID_OUTPUT_TRIES = 2  # percobaan awal + satu kali retry


def backoff_seconds(attempt: int) -> float:
    return min(600.0, 15.0 * (2 ** max(0, attempt - 1)))


class Worker:
    def __init__(self, session_factory: Callable[[], Session], settings: Settings, provider: AIProvider,
                 sleep: Callable[[float], None] = time.sleep, clock: Callable[[], float] = time.monotonic) -> None:
        self.session_factory = session_factory
        self.settings = settings
        self.provider = provider
        self.sleep = sleep
        self.clock = clock
        self._last_call: float | None = None

    def run_forever(self, poll_seconds: float = 2.0) -> None:
        logger.info("Worker AI berjalan")
        while True:
            if not self.run_once():
                self.sleep(poll_seconds)

    def run_once(self) -> bool:
        """Memproses satu job jika ada. Mengembalikan False jika antrean kosong."""
        with self.session_factory() as session:
            claimed = ai.claim_next_job(session, self.settings.ai_job_lease_seconds)
        if claimed is None:
            return False
        job_id, attempt = claimed
        try:
            self._process(job_id, attempt)
        except Exception:
            logger.exception("Job %s gagal tak terduga", job_id)
            with self.session_factory() as session:
                ai.reschedule(session, job_id, attempt, backoff_seconds(attempt), "internal_error")
        return True

    def _process(self, job_id: uuid.UUID, attempt: int) -> None:
        with self.session_factory() as session:
            job = session.get(AIJob, job_id)
            note = session.scalars(
                select(Note).where(Note.id == job.source_id, Note.user_id == job.user_id)
            ).one_or_none()
            job_type, question_count = job.type, int(job.params.get("question_count", 0))
            source_version = job.source_version
            if note is None or note.deleted_at is not None:
                ai.fail(session, job_id, attempt, "source_deleted")
                return
            if note.version != source_version:
                # Hasil untuk versi lama tidak boleh dibuat setelah materi berubah.
                ai.fail(session, job_id, attempt, "source_changed")
                return
            source = SourceText(source_id=str(note.id), title=note.title, text=note.content)

        model_id = getattr(self.provider, "model", None)
        for try_index in range(INVALID_OUTPUT_TRIES):
            self._throttle()
            try:
                if job_type == "summary":
                    result = self.provider.summarize(source)
                else:
                    result = self.provider.generate_quiz(source, question_count)
                    validate_quiz(result.output, question_count, {source.source_id})
            except (ProviderInvalidOutputError, QuizValidationError) as exc:
                logger.warning("Output tidak valid untuk job %s (percobaan %d): %s", job_id, try_index + 1, exc)
                continue
            except ProviderTransientError as exc:
                with self.session_factory() as session:
                    if attempt >= self.settings.ai_max_attempts:
                        ai.fail(session, job_id, attempt, "provider_unavailable", model_id)
                    else:
                        delay = max(exc.retry_after_seconds or 0.0, backoff_seconds(attempt))
                        ai.reschedule(session, job_id, attempt, delay, "provider_busy")
                return
            except ProviderRejectedError:
                with self.session_factory() as session:
                    ai.fail(session, job_id, attempt, "provider_rejected", model_id)
                return
            with self.session_factory() as session:
                ai.complete(session, job_id, attempt, result)
            return

        with self.session_factory() as session:
            ai.fail(session, job_id, attempt, "invalid_output", model_id)

    def _throttle(self) -> None:
        """Jeda minimum antar panggilan provider agar tetap di bawah batas per menit free tier."""
        if self._last_call is not None:
            wait = self.settings.ai_min_interval_seconds - (self.clock() - self._last_call)
            if wait > 0:
                self.sleep(wait)
        self._last_call = self.clock()
