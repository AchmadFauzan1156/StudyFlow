import uuid
from datetime import datetime
from typing import Annotated, Any

from fastapi import APIRouter, Depends, File, Form, Header, Query, Response, UploadFile
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.api.auth import CurrentUser
from app.api.errors import ApiError
from app.core.config import AIProviderKind, EmbeddingProviderKind, Settings, get_settings
from app.db.models import AIJob, ChatMessage, Document, DocumentChunk, DocumentPage, DocumentVersion
from app.db.session import DbSession
from app.documents.storage import BlobStorage, StorageError, get_storage
from app.domain import documents

router = APIRouter(prefix="/v1")

SettingsDep = Annotated[Settings, Depends(get_settings)]
IdempotencyKey = Annotated[str, Header(alias="Idempotency-Key", min_length=8, max_length=128)]


def storage_dep(settings: SettingsDep) -> BlobStorage:
    try:
        return get_storage(settings)
    except StorageError as exc:
        raise ApiError(503, "service_unavailable", "Penyimpanan dokumen belum dikonfigurasi") from exc


StorageDep = Annotated[BlobStorage, Depends(storage_dep)]


def _ms(dt: datetime | None) -> int | None:
    return None if dt is None else int(dt.timestamp() * 1000)


def _require_rag(settings: Settings) -> None:
    if settings.embedding_provider is EmbeddingProviderKind.NONE:
        raise ApiError(503, "service_unavailable", "Pemrosesan dokumen belum dikonfigurasi di server ini")


# ---------- Dokumen ----------


class DocumentOut(BaseModel):
    id: uuid.UUID
    course_id: uuid.UUID
    title: str
    status: str
    error_code: str | None
    page_count: int
    size_bytes: int
    warnings: list[str]
    version_id: uuid.UUID
    job_id: uuid.UUID | None = None
    created_at: int | None


def _document_out(doc: Document, version: DocumentVersion, job_id: uuid.UUID | None = None) -> DocumentOut:
    return DocumentOut(id=doc.id, course_id=doc.course_id, title=doc.title, status=version.status,
                       error_code=version.error_code, page_count=version.page_count, size_bytes=version.size_bytes,
                       warnings=version.warnings or [], version_id=version.id, job_id=job_id,
                       created_at=_ms(doc.created_at))


@router.post("/documents", response_model=DocumentOut, status_code=202)
async def upload_document(
    user_id: CurrentUser, db: DbSession, settings: SettingsDep, storage: StorageDep, key: IdempotencyKey,
    file: Annotated[UploadFile, File()], course_id: Annotated[uuid.UUID, Form()],
    title: Annotated[str | None, Form(max_length=200)] = None,
) -> DocumentOut:
    _require_rag(settings)
    # Baca maksimal batas + 1 byte agar berkas besar ditolak tanpa memuat semuanya ke memori.
    data = await file.read(settings.document_max_bytes + 1)
    name = title or (file.filename or "Dokumen").removesuffix(".pdf").removesuffix(".PDF")
    doc, version, job = documents.upload(db, storage, settings, user_id, course_id, name, data, key)
    return _document_out(doc, version, job.id if job else None)


@router.get("/documents", response_model=list[DocumentOut])
def list_documents(user_id: CurrentUser, db: DbSession, course_id: uuid.UUID) -> list[DocumentOut]:
    docs = db.scalars(
        select(Document).where(Document.user_id == user_id, Document.course_id == course_id,
                               Document.deleted_at.is_(None)).order_by(Document.created_at.desc())
    ).all()
    return [_document_out(d, documents.latest_version(db, d)) for d in docs]


@router.get("/documents/{document_id}", response_model=DocumentOut)
def get_document(document_id: uuid.UUID, user_id: CurrentUser, db: DbSession) -> DocumentOut:
    doc = documents.owned_document(db, user_id, document_id)
    return _document_out(doc, documents.latest_version(db, doc))


class PageOut(BaseModel):
    page_number: int
    text: str


@router.get("/documents/{document_id}/pages", response_model=list[PageOut])
def document_pages(document_id: uuid.UUID, user_id: CurrentUser, db: DbSession,
                   start: Annotated[int, Query(ge=1)] = 1,
                   count: Annotated[int, Query(ge=1, le=20)] = 5) -> list[PageOut]:
    """Pratinjau teks hasil ekstraksi (untuk memeriksa rumus/tabel yang rusak)."""
    doc = documents.owned_document(db, user_id, document_id)
    version = documents.latest_version(db, doc)
    pages = db.scalars(
        select(DocumentPage).where(DocumentPage.version_id == version.id, DocumentPage.user_id == user_id,
                                   DocumentPage.page_number >= start, DocumentPage.page_number < start + count)
        .order_by(DocumentPage.page_number)
    ).all()
    return [PageOut(page_number=p.page_number, text=p.text) for p in pages]


@router.get("/documents/{document_id}/download")
def download_document(document_id: uuid.UUID, user_id: CurrentUser, db: DbSession, storage: StorageDep) -> Response:
    doc = documents.owned_document(db, user_id, document_id)
    version = documents.latest_version(db, doc)
    try:
        data = storage.get(version.storage_key)
    except StorageError as exc:
        raise ApiError(503, "service_unavailable", "Berkas sedang tidak dapat diunduh") from exc
    return Response(content=data, media_type="application/pdf",
                    headers={"Content-Disposition": 'attachment; filename="dokumen.pdf"',
                             "Cache-Control": "private, no-store"})


@router.delete("/documents/{document_id}", status_code=204)
def delete_document(document_id: uuid.UUID, user_id: CurrentUser, db: DbSession) -> Response:
    documents.delete_document(db, user_id, document_id)
    return Response(status_code=204)


class ChunkOut(BaseModel):
    chunk_id: uuid.UUID
    document_id: uuid.UUID
    document_title: str
    page_start: int
    page_end: int
    text: str


@router.get("/chunks/{chunk_id}", response_model=ChunkOut)
def get_chunk(chunk_id: uuid.UUID, user_id: CurrentUser, db: DbSession) -> ChunkOut:
    """Cuplikan sumber untuk jawaban tanya materi. Hanya dari dokumen milik pengguna yang belum dihapus."""
    row = db.execute(
        select(DocumentChunk, Document)
        .join(DocumentVersion, DocumentVersion.id == DocumentChunk.document_version_id)
        .join(Document, Document.id == DocumentVersion.document_id)
        .where(DocumentChunk.id == chunk_id, DocumentChunk.user_id == user_id, Document.user_id == user_id,
               Document.deleted_at.is_(None))
    ).one_or_none()
    if row is None:
        raise ApiError(404, "not_found", "Sumber tidak ditemukan atau dokumennya sudah dihapus")
    chunk, doc = row
    return ChunkOut(chunk_id=chunk.id, document_id=doc.id, document_title=doc.title, page_start=chunk.page_start,
                    page_end=chunk.page_end, text=chunk.text)


# ---------- Tanya materi ----------


class ThreadRequest(BaseModel):
    course_id: uuid.UUID
    document_id: uuid.UUID | None = None


class ThreadOut(BaseModel):
    id: uuid.UUID
    course_id: uuid.UUID
    document_id: uuid.UUID | None


@router.post("/chat/threads", response_model=ThreadOut)
def create_thread(body: ThreadRequest, user_id: CurrentUser, db: DbSession) -> ThreadOut:
    thread = documents.get_or_create_thread(db, user_id, body.course_id, body.document_id)
    return ThreadOut(id=thread.id, course_id=thread.course_id, document_id=thread.document_id)


class AskRequest(BaseModel):
    content: str = Field(min_length=3, max_length=1_000)


class MessageOut(BaseModel):
    id: uuid.UUID
    role: str
    content: str
    sources: list[dict[str, Any]]
    answerable: bool | None
    is_demo: bool
    reply_to: uuid.UUID | None
    job_id: uuid.UUID | None
    job_status: str | None = None
    job_error: str | None = None
    created_at: int | None


def _message_out(m: ChatMessage, job: AIJob | None = None) -> MessageOut:
    return MessageOut(id=m.id, role=m.role, content=m.content, sources=m.source_refs or [], answerable=m.answerable,
                      is_demo=m.is_demo, reply_to=m.reply_to, job_id=m.job_id,
                      job_status=job.status if job else None,
                      job_error=job.error_code if job and job.status == "failed" else None,
                      created_at=_ms(m.created_at))


@router.post("/chat/threads/{thread_id}/messages", response_model=MessageOut, status_code=202)
def ask(thread_id: uuid.UUID, body: AskRequest, key: IdempotencyKey, user_id: CurrentUser, db: DbSession,
        settings: SettingsDep) -> MessageOut:
    _require_rag(settings)
    if settings.ai_provider is AIProviderKind.NONE:
        raise ApiError(503, "service_unavailable", "Fitur AI belum dikonfigurasi di server ini")
    message, job = documents.ask(db, settings, user_id, thread_id, body.content, key)
    return _message_out(message, job)


@router.get("/chat/threads/{thread_id}/messages", response_model=list[MessageOut])
def list_messages(thread_id: uuid.UUID, user_id: CurrentUser, db: DbSession,
                  limit: Annotated[int, Query(ge=1, le=200)] = 100) -> list[MessageOut]:
    thread = documents.owned_thread(db, user_id, thread_id)
    messages = db.scalars(
        select(ChatMessage).where(ChatMessage.thread_id == thread.id, ChatMessage.user_id == user_id)
        .order_by(ChatMessage.created_at, ChatMessage.role.desc()).limit(limit)
    ).all()
    job_ids = [m.job_id for m in messages if m.role == "user" and m.job_id]
    jobs = {j.id: j for j in db.scalars(select(AIJob).where(AIJob.id.in_(job_ids), AIJob.user_id == user_id))}
    return [_message_out(m, jobs.get(m.job_id) if m.role == "user" else None) for m in messages]
