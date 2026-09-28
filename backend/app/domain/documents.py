"""Dokumen PDF dan tanya materi (RAG).

Setiap query dibatasi user_id pemilik, termasuk pencarian vektor. Hanya versi dokumen yang aktif dan
dokumen yang belum dihapus yang dapat diambil.
"""

import hashlib
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.errors import ApiError
from app.core.config import Settings
from app.db.models import (
    AIJob,
    ChatMessage,
    ChatThread,
    Course,
    Document,
    DocumentChunk,
    DocumentPage,
    DocumentVersion,
)
from app.documents.chunking import Chunk
from app.documents.pdf import ExtractedPdf, PdfRejected, inspect_pdf
from app.documents.storage import BlobStorage
from app.domain import ai

NO_ANSWER_TEXT = (
    "Materi di mata kuliah ini belum memuat jawaban untuk pertanyaan tersebut. "
    "Coba perjelas pertanyaan atau unggah materi yang relevan."
)


def _owned_course(session: Session, user_id: uuid.UUID, course_id: uuid.UUID) -> Course:
    course = session.get(Course, course_id)
    if course is None or course.user_id != user_id or course.deleted_at is not None:
        raise ApiError(404, "not_found", "Mata kuliah tidak ditemukan. Sinkronkan dulu jika baru dibuat.")
    return course


def owned_document(session: Session, user_id: uuid.UUID, document_id: uuid.UUID) -> Document:
    doc = session.get(Document, document_id)
    if doc is None or doc.user_id != user_id or doc.deleted_at is not None:
        raise ApiError(404, "not_found", "Dokumen tidak ditemukan")
    return doc


# ---------- Unggah ----------


def upload(session: Session, storage: BlobStorage, settings: Settings, user_id: uuid.UUID, course_id: uuid.UUID,
           title: str, data: bytes, idempotency_key: str) -> tuple[Document, DocumentVersion, AIJob]:
    existing = session.scalars(
        select(Document).where(Document.user_id == user_id, Document.idempotency_key == idempotency_key)
    ).one_or_none()
    if existing is not None:
        return _existing_upload(session, existing)

    _owned_course(session, user_id, course_id)
    try:
        info = inspect_pdf(data, settings.document_max_bytes, settings.document_max_pages)
    except PdfRejected as exc:
        raise ApiError(exc.status, exc.code, exc.message) from None

    today = ai.utc_today()
    ai.reserve_indexing(session, settings, user_id, today)

    document_id, version_id = uuid.uuid4(), uuid.uuid4()
    # Key acak dengan prefix pemilik; tidak memuat nama berkas asli.
    storage_key = f"{user_id}/{document_id}/{version_id}.pdf"
    doc = Document(id=document_id, user_id=user_id, course_id=course_id, title=title.strip()[:200] or "Dokumen",
                   idempotency_key=idempotency_key)
    version = DocumentVersion(
        id=version_id, document_id=document_id, user_id=user_id, version_number=1, storage_key=storage_key,
        content_hash=hashlib.sha256(data).hexdigest(), size_bytes=len(data), page_count=info.page_count,
        status="queued", warnings=[],
    )
    job = AIJob(
        id=uuid.uuid4(), user_id=user_id, type="index_document", source_type="document", source_id=version_id,
        source_version=1, params={"quota_scopes": ai.index_scopes(user_id)},
        idempotency_key=f"index:{version_id}", status="queued", attempts=0, quota_day=today,
    )
    session.add(doc)
    session.flush()
    session.add(version)
    session.flush()
    session.add(job)
    try:
        session.flush()
    except IntegrityError:
        session.rollback()
        existing = session.scalars(
            select(Document).where(Document.user_id == user_id, Document.idempotency_key == idempotency_key)
        ).one()
        return _existing_upload(session, existing)
    # Berkas disimpan sebelum commit; jika commit gagal, berkas yatim dibersihkan oleh job cleanup berikutnya.
    storage.put(storage_key, data, "application/pdf")
    session.commit()
    return doc, version, job


def _existing_upload(session: Session, doc: Document) -> tuple[Document, DocumentVersion, AIJob]:
    version = session.scalars(
        select(DocumentVersion).where(DocumentVersion.document_id == doc.id).order_by(DocumentVersion.version_number)
    ).first()
    job = session.scalars(select(AIJob).where(AIJob.user_id == doc.user_id, AIJob.source_id == version.id)).first()
    return doc, version, job


def latest_version(session: Session, doc: Document) -> DocumentVersion:
    return session.scalars(
        select(DocumentVersion).where(DocumentVersion.document_id == doc.id, DocumentVersion.user_id == doc.user_id)
        .order_by(DocumentVersion.version_number.desc())
    ).first()


# ---------- Hapus ----------


def delete_document(session: Session, user_id: uuid.UUID, document_id: uuid.UUID) -> None:
    doc = owned_document(session, user_id, document_id)
    now = datetime.now(UTC)
    doc.deleted_at = now
    doc.active_version_id = None
    version_ids = session.scalars(select(DocumentVersion.id).where(DocumentVersion.document_id == doc.id)).all()
    # Batalkan job indexing yang belum berjalan; job yang sedang berjalan tidak akan mempublikasikan hasil.
    session.execute(
        update(AIJob).where(AIJob.user_id == user_id, AIJob.source_id.in_(version_ids), AIJob.status == "queued")
        .values(status="failed", error_code="source_deleted", updated_at=now)
    )
    session.add(AIJob(
        id=uuid.uuid4(), user_id=user_id, type="cleanup_document", source_type="document", source_id=doc.id,
        source_version=0, params={"quota_scopes": []}, idempotency_key=f"cleanup:{doc.id}", status="queued",
        attempts=0, quota_day=ai.utc_today(),
    ))
    session.commit()


def cleanup_document(session: Session, storage: BlobStorage, document_id: uuid.UUID, user_id: uuid.UUID) -> None:
    """Idempotent: aman diulang jika sebelumnya gagal di tengah jalan."""
    versions = session.scalars(
        select(DocumentVersion).where(DocumentVersion.document_id == document_id, DocumentVersion.user_id == user_id)
    ).all()
    for version in versions:
        storage.delete(version.storage_key)
        session.execute(delete(DocumentChunk).where(DocumentChunk.document_version_id == version.id))
        session.execute(delete(DocumentPage).where(DocumentPage.version_id == version.id))
        if version.status in ("queued", "processing"):
            version.status = "failed"
            version.error_code = "source_deleted"


# ---------- Publikasi hasil indexing ----------


def publish_index(session: Session, version: DocumentVersion, extracted: ExtractedPdf, chunks: list[Chunk],
                  vectors: list[list[float]], embedding_model: str) -> bool:
    """Menulis halaman dan potongan lalu mengaktifkan versi secara atomik. False jika dokumen sudah dihapus."""
    doc = session.get(Document, version.document_id, with_for_update=True)
    if doc is None or doc.deleted_at is not None:
        return False
    session.execute(delete(DocumentChunk).where(DocumentChunk.document_version_id == version.id))
    session.execute(delete(DocumentPage).where(DocumentPage.version_id == version.id))
    for number, text in enumerate(extracted.pages, start=1):
        session.add(DocumentPage(version_id=version.id, page_number=number, user_id=version.user_id, text=text))
    for chunk, vector in zip(chunks, vectors, strict=True):
        session.add(DocumentChunk(
            id=uuid.uuid4(), user_id=version.user_id, document_version_id=version.id, chunk_index=chunk.index,
            text=chunk.text, page_start=chunk.page_start, page_end=chunk.page_end, char_start=chunk.char_start,
            char_end=chunk.char_end, embedding_model=embedding_model, embedding=vector,
        ))
    version.status = "ready"
    version.error_code = None
    version.warnings = extracted.warnings
    version.embedding_model = embedding_model
    version.ready_at = datetime.now(UTC)
    doc.active_version_id = version.id
    return True


# ---------- Retrieval ----------


@dataclass(frozen=True)
class RetrievedChunk:
    chunk_id: uuid.UUID
    document_id: uuid.UUID
    document_title: str
    page_start: int
    page_end: int
    text: str
    distance: float


def retrieve(session: Session, user_id: uuid.UUID, course_id: uuid.UUID, document_id: uuid.UUID | None,
             query_vector: list[float], embedding_model: str, top_k: int, max_distance: float) -> list[RetrievedChunk]:
    distance = DocumentChunk.embedding.cosine_distance(query_vector).label("distance")
    stmt = (
        select(DocumentChunk, Document.id, Document.title, distance)
        .join(DocumentVersion, DocumentVersion.id == DocumentChunk.document_version_id)
        .join(Document, Document.id == DocumentVersion.document_id)
        .where(
            DocumentChunk.user_id == user_id,
            DocumentVersion.user_id == user_id,
            Document.user_id == user_id,
            Document.course_id == course_id,
            Document.deleted_at.is_(None),
            Document.active_version_id == DocumentVersion.id,
            DocumentChunk.embedding_model == embedding_model,
        )
        .order_by(distance)
        .limit(top_k)
    )
    if document_id is not None:
        stmt = stmt.where(Document.id == document_id)
    return [
        RetrievedChunk(chunk.id, doc_id, title, chunk.page_start, chunk.page_end, chunk.text, float(dist))
        for chunk, doc_id, title, dist in session.execute(stmt).all()
        if dist <= max_distance
    ]


def source_ref(chunk: RetrievedChunk) -> dict:
    """Metadata sumber dipetakan server dari database, bukan dari teks model."""
    return {
        "chunk_id": str(chunk.chunk_id),
        "document_id": str(chunk.document_id),
        "document_title": chunk.document_title,
        "page_start": chunk.page_start,
        "page_end": chunk.page_end,
        "excerpt": chunk.text[:300],
    }


# ---------- Tanya materi ----------


def get_or_create_thread(session: Session, user_id: uuid.UUID, course_id: uuid.UUID,
                         document_id: uuid.UUID | None) -> ChatThread:
    _owned_course(session, user_id, course_id)
    if document_id is not None:
        doc = owned_document(session, user_id, document_id)
        if doc.course_id != course_id:
            raise ApiError(422, "invalid_input", "Dokumen bukan bagian dari mata kuliah ini")
    thread = session.scalars(
        select(ChatThread).where(ChatThread.user_id == user_id, ChatThread.course_id == course_id,
                                 ChatThread.document_id.is_(None) if document_id is None
                                 else ChatThread.document_id == document_id)
    ).first()
    if thread is None:
        thread = ChatThread(id=uuid.uuid4(), user_id=user_id, course_id=course_id, document_id=document_id)
        session.add(thread)
        session.commit()
    return thread


def owned_thread(session: Session, user_id: uuid.UUID, thread_id: uuid.UUID) -> ChatThread:
    thread = session.get(ChatThread, thread_id)
    if thread is None or thread.user_id != user_id:
        raise ApiError(404, "not_found", "Percakapan tidak ditemukan")
    return thread


def ask(session: Session, settings: Settings, user_id: uuid.UUID, thread_id: uuid.UUID, question: str,
        idempotency_key: str) -> tuple[ChatMessage, AIJob]:
    thread = owned_thread(session, user_id, thread_id)
    existing = session.scalars(
        select(ChatMessage).where(ChatMessage.user_id == user_id, ChatMessage.idempotency_key == idempotency_key)
    ).one_or_none()
    if existing is not None:
        if existing.thread_id != thread.id or existing.content != question.strip():
            raise ApiError(409, "idempotency_key_reused", "Idempotency-Key sudah dipakai untuk permintaan lain")
        return existing, session.get(AIJob, existing.job_id)

    today = ai.utc_today()
    ai.reserve_generative(session, settings, user_id, today)
    message = ChatMessage(id=uuid.uuid4(), thread_id=thread.id, user_id=user_id, role="user",
                          content=question.strip(), idempotency_key=idempotency_key)
    job = AIJob(
        id=uuid.uuid4(), user_id=user_id, type="chat_answer", source_type="thread", source_id=thread.id,
        source_version=0, params={"message_id": str(message.id)}, idempotency_key=f"chat:{message.id}",
        status="queued", attempts=0, quota_day=today,
    )
    message.job_id = job.id
    session.add(job)
    session.flush()
    session.add(message)
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        existing = session.scalars(
            select(ChatMessage).where(ChatMessage.user_id == user_id, ChatMessage.idempotency_key == idempotency_key)
        ).one()
        return existing, session.get(AIJob, existing.job_id)
    return message, job
