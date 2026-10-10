"""Worker AI: proses terpisah dari API, satu codebase.

Jalankan: `uv run python -m app.worker`. Beberapa worker boleh berjalan bersamaan; klaim job memakai
FOR UPDATE SKIP LOCKED dan lease sehingga job tidak diproses ganda, dan job yang workernya mati
dapat diambil ulang setelah lease habis. Hasil hanya ditulis oleh pemegang lease.
"""

import logging
import threading
import time
import uuid
from collections.abc import Callable
from typing import TypeVar

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.db.models import AIJob, ChatMessage, ChatThread, Document, DocumentVersion, Note
from app.documents.chunking import chunk_pages
from app.documents.pdf import PdfRejected, extract_pages
from app.documents.storage import BlobStorage, StorageError
from app.domain import ai, documents
from app.domain.quiz import QuizValidationError, validate_quiz
from app.providers.base import (
    AIProvider,
    ContextChunk,
    ProviderInvalidOutputError,
    ProviderRejectedError,
    ProviderTransientError,
    ProviderUnavailableError,
    SourceText,
)
from app.providers.factory import Embedder

logger = logging.getLogger("studyflow.worker")

INVALID_OUTPUT_TRIES = 2  # percobaan awal + satu kali retry

T = TypeVar("T")


def backoff_seconds(attempt: int) -> float:
    return min(600.0, 15.0 * (2 ** max(0, attempt - 1)))


class _Stop(Exception):
    """Job sudah dijadwalkan ulang/digagalkan; hentikan pemrosesan."""


class Worker:
    def __init__(self, session_factory: Callable[[], Session], settings: Settings, provider: AIProvider | None,
                 embedder: Embedder | None = None, storage: BlobStorage | None = None,
                 sleep: Callable[[float], None] = time.sleep, clock: Callable[[], float] = time.monotonic) -> None:
        self.session_factory = session_factory
        self.settings = settings
        self.provider = provider
        self.embedder = embedder
        self.storage = storage
        self.sleep = sleep
        self.clock = clock
        self._last_call: float | None = None

    def run_forever(self, poll_seconds: float = 2.0, stop: threading.Event | None = None) -> None:
        """Memproses job terus-menerus; berhenti setelah [stop] di-set (mode tertanam di proses API)."""
        logger.info("Worker AI berjalan")
        while stop is None or not stop.is_set():
            if not self.run_once():
                if stop is None:
                    self.sleep(poll_seconds)
                else:
                    stop.wait(poll_seconds)
        logger.info("Worker AI berhenti")

    def run_once(self) -> bool:
        """Memproses satu job jika ada. Mengembalikan False jika antrean kosong."""
        with self.session_factory() as session:
            claimed = ai.claim_next_job(session, self.settings.ai_job_lease_seconds)
        if claimed is None:
            return False
        job_id, attempt = claimed
        with self.session_factory() as session:
            job_type = session.get(AIJob, job_id).type
        handler = {
            "summary": self._generate,
            "quiz": self._generate,
            "index_document": self._index_document,
            "chat_answer": self._chat_answer,
            "cleanup_document": self._cleanup_document,
        }[job_type]
        try:
            handler(job_id, attempt)
        except _Stop:
            pass
        except Exception:
            logger.exception("Job %s gagal tak terduga", job_id)
            with self.session_factory() as session:
                ai.reschedule(session, job_id, attempt, backoff_seconds(attempt), "internal_error")
        return True

    # ---------- Pemanggilan provider dengan aturan retry bersama ----------

    def _call(self, job_id: uuid.UUID, attempt: int, fn: Callable[[], T], model_id: str | None,
              validate: Callable[[T], None] | None = None) -> T:
        """Output tidak valid dicoba ulang sekali; gangguan sementara dijadwalkan ulang; penolakan menggagalkan."""
        for try_index in range(INVALID_OUTPUT_TRIES):
            self._throttle()
            try:
                result = fn()
                if validate:
                    validate(result)
                return result
            except (ProviderInvalidOutputError, QuizValidationError) as exc:
                logger.warning("Output tidak valid untuk job %s (percobaan %d): %s", job_id, try_index + 1, exc)
            except ProviderTransientError as exc:
                with self.session_factory() as session:
                    if attempt >= self.settings.ai_max_attempts:
                        ai.fail(session, job_id, attempt, "provider_unavailable", model_id)
                    else:
                        delay = max(exc.retry_after_seconds or 0.0, backoff_seconds(attempt))
                        ai.reschedule(session, job_id, attempt, delay, "provider_busy")
                raise _Stop from exc
            except (ProviderRejectedError, ProviderUnavailableError) as exc:
                with self.session_factory() as session:
                    ai.fail(session, job_id, attempt, "provider_rejected", model_id)
                raise _Stop from exc
        with self.session_factory() as session:
            ai.fail(session, job_id, attempt, "invalid_output", model_id)
        raise _Stop

    def _require(self, dependency: object, job_id: uuid.UUID, attempt: int) -> None:
        if dependency is None:
            # Worker tidak dikonfigurasi untuk jenis job ini; coba lagi nanti (mungkin worker lain bisa).
            with self.session_factory() as session:
                ai.reschedule(session, job_id, attempt, 60, "worker_not_configured")
            raise _Stop

    # ---------- Rangkuman & kuis catatan ----------

    def _generate(self, job_id: uuid.UUID, attempt: int) -> None:
        self._require(self.provider, job_id, attempt)
        with self.session_factory() as session:
            job = session.get(AIJob, job_id)
            note = session.scalars(
                select(Note).where(Note.id == job.source_id, Note.user_id == job.user_id)
            ).one_or_none()
            job_type, question_count = job.type, int(job.params.get("question_count", 0))
            if note is None or note.deleted_at is not None:
                ai.fail(session, job_id, attempt, "source_deleted")
                return
            if note.version != job.source_version:
                # Hasil untuk versi lama tidak boleh dibuat setelah materi berubah.
                ai.fail(session, job_id, attempt, "source_changed")
                return
            source = SourceText(source_id=str(note.id), title=note.title, text=note.content)

        model_id = getattr(self.provider, "model", None)
        if job_type == "summary":
            result = self._call(job_id, attempt, lambda: self.provider.summarize(source), model_id)
        else:
            result = self._call(
                job_id, attempt, lambda: self.provider.generate_quiz(source, question_count), model_id,
                validate=lambda r: validate_quiz(r.output, question_count, {source.source_id}),
            )
        with self.session_factory() as session:
            ai.complete(session, job_id, attempt, result)

    # ---------- Indexing dokumen ----------

    def _index_document(self, job_id: uuid.UUID, attempt: int) -> None:
        self._require(self.embedder, job_id, attempt)
        self._require(self.storage, job_id, attempt)
        with self.session_factory() as session:
            job = session.get(AIJob, job_id)
            version = session.scalars(
                select(DocumentVersion).where(DocumentVersion.id == job.source_id,
                                              DocumentVersion.user_id == job.user_id)
            ).one_or_none()
            doc = None if version is None else session.get(Document, version.document_id)
            if version is None or doc is None or doc.deleted_at is not None:
                ai.fail(session, job_id, attempt, "source_deleted")
                return
            version.status = "processing"
            session.commit()
            version_id, key, title = version.id, version.storage_key, doc.title

        try:
            data = self.storage.get(key)
            extracted = extract_pages(data, self.settings.document_extract_timeout_seconds)
        except StorageError:
            with self.session_factory() as session:
                ai.reschedule(session, job_id, attempt, backoff_seconds(attempt), "storage_unavailable")
            return
        except PdfRejected as exc:
            with self.session_factory() as session:
                version = session.get(DocumentVersion, version_id)
                version.status = "unsupported" if exc.code == "pdf_no_text" else "failed"
                version.error_code = exc.code
                session.commit()
                ai.fail(session, job_id, attempt, exc.code)
            return

        chunks = chunk_pages(extracted.pages, self.settings.chunk_words, self.settings.chunk_overlap_words)
        model_id = self.embedder.model
        vectors = self._call(job_id, attempt, lambda: self.embedder.embed_documents([c.text for c in chunks], title),
                             model_id)

        with self.session_factory() as session:
            job = ai.lock_owned_job(session, job_id, attempt)
            if job is None:
                session.rollback()
                return
            version = session.get(DocumentVersion, version_id)
            if not documents.publish_index(session, version, extracted, chunks, vectors, model_id):
                session.rollback()
                ai.fail(session, job_id, attempt, "source_deleted")
                return
            ai.mark_succeeded(session, job, version_id, model_id, input_units=len(chunks))
            session.commit()

    # ---------- Tanya materi ----------

    def _chat_answer(self, job_id: uuid.UUID, attempt: int) -> None:
        self._require(self.embedder, job_id, attempt)
        self._require(self.provider, job_id, attempt)
        with self.session_factory() as session:
            job = session.get(AIJob, job_id)
            question = session.scalars(
                select(ChatMessage).where(ChatMessage.id == uuid.UUID(job.params["message_id"]),
                                          ChatMessage.user_id == job.user_id)
            ).one()
            thread = session.get(ChatThread, question.thread_id)
            user_id, course_id, document_id = job.user_id, thread.course_id, thread.document_id
            question_text = question.content

        query_vector = self._call(job_id, attempt, lambda: self.embedder.embed_query(question_text),
                                  self.embedder.model)
        with self.session_factory() as session:
            retrieved = documents.retrieve(session, user_id, course_id, document_id, query_vector,
                                           self.embedder.model, self.settings.rag_top_k,
                                           self.settings.rag_max_distance)

        if not retrieved:
            # Tidak ada konteks yang cukup: jawab jujur tanpa memanggil model generatif.
            self._save_answer(job_id, attempt, question, documents.NO_ANSWER_TEXT, False, [], None, False,
                              refund=True)
            return

        # ID pendek untuk model; dipetakan kembali ke chunk sungguhan oleh server.
        by_label = {f"S{i + 1}": chunk for i, chunk in enumerate(retrieved)}
        contexts = [
            ContextChunk(label, f"{c.document_title}, hal. {c.page_start}" +
                         (f"–{c.page_end}" if c.page_end != c.page_start else ""), c.text)
            for label, c in by_label.items()
        ]

        def validate(result) -> None:
            unknown = set(result.output.source_ids) - set(by_label)
            if unknown:
                raise ProviderInvalidOutputError(f"sumber tidak dikenal: {sorted(unknown)}")
            if result.output.answerable and not result.output.source_ids:
                raise ProviderInvalidOutputError("jawaban tanpa sumber")

        model_id = getattr(self.provider, "model", None)
        result = self._call(job_id, attempt, lambda: self.provider.answer(question_text, contexts), model_id,
                            validate=validate)
        answer = result.output
        refs = [documents.source_ref(by_label[s]) for s in dict.fromkeys(answer.source_ids)] if answer.answerable else []
        content = answer.answer if answer.answerable else (answer.answer or documents.NO_ANSWER_TEXT)
        self._save_answer(job_id, attempt, question, content, answer.answerable, refs, result.model_id,
                          result.is_demo, usage=(result.input_units, result.output_units))

    def _save_answer(self, job_id: uuid.UUID, attempt: int, question: ChatMessage, content: str, answerable: bool,
                     refs: list[dict], model_id: str | None, is_demo: bool, refund: bool = False,
                     usage: tuple[int | None, int | None] = (None, None)) -> None:
        with self.session_factory() as session:
            job = ai.lock_owned_job(session, job_id, attempt)
            if job is None:
                session.rollback()
                return
            reply = ChatMessage(
                id=uuid.uuid4(), thread_id=question.thread_id, user_id=question.user_id, role="assistant",
                content=content, source_refs=refs, answerable=answerable, is_demo=is_demo, model_id=model_id,
                reply_to=question.id, job_id=job.id,
            )
            session.add(reply)
            ai.mark_succeeded(session, job, reply.id, model_id, *usage)
            if refund:
                ai.refund_quota(session, job)
            session.commit()

    # ---------- Pembersihan ----------

    def _cleanup_document(self, job_id: uuid.UUID, attempt: int) -> None:
        self._require(self.storage, job_id, attempt)
        with self.session_factory() as session:
            job = session.get(AIJob, job_id)
            try:
                documents.cleanup_document(session, self.storage, job.source_id, job.user_id)
            except StorageError:
                session.rollback()
                ai.reschedule(session, job_id, attempt, backoff_seconds(attempt), "storage_unavailable")
                return
            locked = ai.lock_owned_job(session, job_id, attempt)
            if locked is None:
                session.rollback()
                return
            ai.mark_succeeded(session, locked, None, None)
            session.commit()

    def _throttle(self) -> None:
        """Jeda minimum antar panggilan provider agar tetap di bawah batas per menit free tier."""
        if self._last_call is not None:
            wait = self.settings.ai_min_interval_seconds - (self.clock() - self._last_call)
            if wait > 0:
                self.sleep(wait)
        self._last_call = self.clock()
