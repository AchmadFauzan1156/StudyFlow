import json
import uuid
from pathlib import Path

import pytest
from sqlalchemy import text
from sqlalchemy.orm import sessionmaker

from app.core.config import AIProviderKind, AppEnv, EmbeddingProviderKind, Settings
from app.documents.storage import LocalStorage, StorageError
from app.domain.schemas import AnswerDraft, GenerationResult
from app.eval.rag_eval import evaluate
from app.providers.fake import FakeEmbedder, FakeProvider
from app.worker.runner import Worker
from tests.integration.conftest import JWT_SECRET, SUPABASE_URL, TEST_DB_URL
from tests.pdf_factory import blank_pdf, encrypt_pdf, make_pdf

pytestmark = pytest.mark.integration

ALICE = uuid.UUID("00000000-0000-4000-8000-00000000a11c")
BOB = uuid.UUID("00000000-0000-4000-8000-000000000b0b")
FIXTURE = json.loads((Path(__file__).parent.parent / "fixtures" / "rag_eval.json").read_text(encoding="utf-8"))
KRIPTO = FIXTURE["materials"][0]


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        _env_file=None, app_env=AppEnv.TEST, database_url=TEST_DB_URL, supabase_url=SUPABASE_URL,
        jwt_hs256_secret=JWT_SECRET, ai_provider=AIProviderKind.FAKE, embedding_provider=EmbeddingProviderKind.FAKE,
        storage_backend="local", storage_local_dir=str(tmp_path / "blobs"), ai_min_interval_seconds=0,
        chunk_words=60, chunk_overlap_words=10, rag_max_distance=0.9, document_max_pages=10,
        document_max_bytes=200_000, index_user_daily_limit=5, ai_max_attempts=2,
    )


@pytest.fixture
def storage(settings):
    return LocalStorage(settings.storage_local_dir)


@pytest.fixture
def worker(engine, settings, storage):
    def make(provider=None):
        return Worker(sessionmaker(bind=engine, expire_on_commit=False), settings,
                      provider or FakeProvider(AppEnv.TEST), FakeEmbedder(AppEnv.TEST, settings.embedding_dim),
                      storage, sleep=lambda s: None)
    return make


def create_course(client, auth, user=ALICE) -> uuid.UUID:
    course_id = uuid.uuid4()
    res = client.post("/v1/sync/push", headers=auth(user), json={"mutations": [
        {"mutation_id": str(uuid.uuid4()), "entity_type": "course", "entity_id": str(course_id),
         "operation": "create", "base_version": 0, "payload": {"name": "Kriptografi"}}]})
    assert res.json()["results"][0]["status"] == "applied"
    return course_id


def upload(client, auth, course_id, data, user=ALICE, key=None, title="Materi RSA"):
    return client.post(
        "/v1/documents", headers=auth(user) | {"Idempotency-Key": key or uuid.uuid4().hex},
        data={"course_id": str(course_id), "title": title}, files={"file": ("materi.pdf", data, "application/pdf")},
    )


def process_all(w) -> None:
    while w.run_once():
        pass


def ready_document(client, auth, worker, user=ALICE, pages=None, title="Materi RSA"):
    course_id = create_course(client, auth, user)
    res = upload(client, auth, course_id, make_pdf(pages or KRIPTO["pages"]), user=user, title=title)
    assert res.status_code == 202, res.text
    process_all(worker())
    doc = client.get(f"/v1/documents/{res.json()['id']}", headers=auth(user)).json()
    assert doc["status"] == "ready", doc
    return course_id, doc


def ask(client, auth, course_id, question, user=ALICE, key=None):
    thread = client.post("/v1/chat/threads", headers=auth(user), json={"course_id": str(course_id)}).json()
    res = client.post(f"/v1/chat/threads/{thread['id']}/messages",
                      headers=auth(user) | {"Idempotency-Key": key or uuid.uuid4().hex}, json={"content": question})
    return thread["id"], res


# ---------- Unggah & indexing ----------


def test_upload_index_preview_and_download(client, auth, worker, engine):
    course_id, doc = ready_document(client, auth, worker)
    assert doc["page_count"] == 3 and any("Rumus" in w for w in doc["warnings"])

    pages = client.get(f"/v1/documents/{doc['id']}/pages", headers=auth(ALICE), params={"start": 2, "count": 1}).json()
    assert pages[0]["page_number"] == 2 and "modulus n" in pages[0]["text"].lower()

    listed = client.get("/v1/documents", headers=auth(ALICE), params={"course_id": str(course_id)}).json()
    assert [d["id"] for d in listed] == [doc["id"]]

    download = client.get(f"/v1/documents/{doc['id']}/download", headers=auth(ALICE))
    assert download.status_code == 200 and download.content.startswith(b"%PDF-")
    assert download.headers["cache-control"] == "private, no-store"

    with engine.connect() as conn:
        rows = conn.execute(text("SELECT page_start, page_end, embedding_model FROM document_chunks")).all()
    assert rows and {r.embedding_model for r in rows} == {"fake-hash-embedding-v1"}
    assert min(r.page_start for r in rows) == 1 and max(r.page_end for r in rows) == 3


def test_upload_is_idempotent(client, auth, engine):
    course_id = create_course(client, auth)
    data = make_pdf(KRIPTO["pages"])
    first = upload(client, auth, course_id, data, key="upload-0001")
    second = upload(client, auth, course_id, data, key="upload-0001")
    assert first.json()["id"] == second.json()["id"]
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM documents")).scalar() == 1


@pytest.mark.parametrize(
    ("data", "status", "code"),
    [
        (b"PK\x03\x04bukan pdf sama sekali", 422, "not_pdf"),
        (encrypt_pdf(make_pdf(["rahasia " * 50])), 422, "pdf_encrypted"),
        (make_pdf(["halaman"] * 11), 413, "too_many_pages"),
        (b"%PDF-1.4\n" + b"0" * 250_000, 413, "file_too_large"),
    ],
    ids=["not-pdf", "encrypted", "too-many-pages", "too-large"],
)
def test_upload_rejections(client, auth, data, status, code):
    course_id = create_course(client, auth)
    res = upload(client, auth, course_id, data)
    assert (res.status_code, res.json()["code"]) == (status, code)


def test_scanned_pdf_is_marked_unsupported(client, auth, worker):
    course_id = create_course(client, auth)
    res = upload(client, auth, course_id, blank_pdf(2))
    assert res.status_code == 202
    process_all(worker())
    doc = client.get(f"/v1/documents/{res.json()['id']}", headers=auth(ALICE)).json()
    assert (doc["status"], doc["error_code"]) == ("unsupported", "pdf_no_text")
    # Kuota indexing dikembalikan karena bukan kegagalan yang bisa diperbaiki dengan mencoba lagi.


def test_upload_requires_own_course_and_quota(client, auth):
    course_id = create_course(client, auth)
    assert upload(client, auth, course_id, make_pdf(KRIPTO["pages"]), user=BOB).status_code == 404
    for _ in range(5):
        assert upload(client, auth, course_id, make_pdf(KRIPTO["pages"])).status_code == 202
    over = upload(client, auth, course_id, make_pdf(KRIPTO["pages"]))
    assert (over.status_code, over.json()["code"]) == (429, "quota_exceeded")


def test_other_user_cannot_read_documents(client, auth, worker):
    course_id, doc = ready_document(client, auth, worker)
    for path in (f"/v1/documents/{doc['id']}", f"/v1/documents/{doc['id']}/pages",
                 f"/v1/documents/{doc['id']}/download"):
        assert client.get(path, headers=auth(BOB)).status_code == 404
    assert client.get("/v1/documents", headers=auth(BOB), params={"course_id": str(course_id)}).json() == []
    assert client.delete(f"/v1/documents/{doc['id']}", headers=auth(BOB)).status_code == 404


# ---------- Tanya materi ----------


def test_question_is_answered_with_sources_from_own_documents_only(client, auth, worker):
    course_id, doc = ready_document(client, auth, worker)
    # Bob punya dokumen berisi kata-kata serupa; tidak boleh ikut terambil untuk Alice.
    ready_document(client, auth, worker, user=BOB, title="Catatan Bob",
                   pages=["Modulus n RSA menurut catatan Bob adalah rahasia Bob. " * 10])

    thread_id, res = ask(client, auth, course_id, "Bagaimana modulus n pada RSA dihitung?")
    assert res.status_code == 202 and res.json()["job_status"] == "queued"
    process_all(worker())

    messages = client.get(f"/v1/chat/threads/{thread_id}/messages", headers=auth(ALICE)).json()
    assert [m["role"] for m in messages] == ["user", "assistant"]
    answer = messages[1]
    assert answer["answerable"] is True and answer["is_demo"] is True
    assert answer["sources"], answer
    assert {s["document_id"] for s in answer["sources"]} == {doc["id"]}
    assert all(s["document_title"] == "Materi RSA" and 1 <= s["page_start"] <= 3 for s in answer["sources"])
    chunk = client.get(f"/v1/chunks/{answer['sources'][0]['chunk_id']}", headers=auth(ALICE)).json()
    assert chunk["document_title"] == "Materi RSA"
    assert client.get(f"/v1/chunks/{answer['sources'][0]['chunk_id']}", headers=auth(BOB)).status_code == 404
    assert client.get(f"/v1/chat/threads/{thread_id}/messages", headers=auth(BOB)).status_code == 404


class ExplodingProvider(FakeProvider):
    def answer(self, question, contexts):
        raise AssertionError("model tidak boleh dipanggil tanpa konteks")


def test_out_of_scope_question_is_declined_without_calling_model(client, auth, worker):
    course_id, _ = ready_document(client, auth, worker)
    thread_id, _ = ask(client, auth, course_id, "Siapa pemenang piala dunia sepak bola tahun 1998?")
    process_all(worker(ExplodingProvider(AppEnv.TEST)))
    answer = client.get(f"/v1/chat/threads/{thread_id}/messages", headers=auth(ALICE)).json()[1]
    assert answer["answerable"] is False and answer["sources"] == []
    assert "belum memuat jawaban" in answer["content"]
    assert client.get("/v1/ai/info", headers=auth(ALICE)).json()["used_today"] == 0


class FabricatingProvider(FakeProvider):
    calls = 0

    def answer(self, question, contexts):
        FabricatingProvider.calls += 1
        return GenerationResult[AnswerDraft](
            output=AnswerDraft(answerable=True, answer="Karangan.", source_ids=["S99"]),
            model_id="m", prompt_version="t", is_demo=False)


def test_fabricated_source_ids_are_rejected(client, auth, worker):
    course_id, _ = ready_document(client, auth, worker)
    thread_id, _ = ask(client, auth, course_id, "Bagaimana modulus n pada RSA dihitung?")
    process_all(worker(FabricatingProvider(AppEnv.TEST)))
    assert FabricatingProvider.calls == 2, "output tidak valid dicoba ulang tepat sekali"
    messages = client.get(f"/v1/chat/threads/{thread_id}/messages", headers=auth(ALICE)).json()
    assert len(messages) == 1
    assert (messages[0]["job_status"], messages[0]["job_error"]) == ("failed", "invalid_output")


def test_ask_is_idempotent(client, auth, worker, engine):
    course_id, _ = ready_document(client, auth, worker)
    _, first = ask(client, auth, course_id, "Apa itu tanda tangan digital?", key="ask-00000001")
    _, second = ask(client, auth, course_id, "Apa itu tanda tangan digital?", key="ask-00000001")
    assert first.json()["id"] == second.json()["id"]
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM ai_jobs WHERE type = 'chat_answer'")).scalar() == 1


# ---------- Hapus ----------


def test_delete_cleans_up_blob_chunks_and_retrieval(client, auth, worker, engine, storage):
    course_id, doc = ready_document(client, auth, worker)
    with engine.connect() as conn:
        key = conn.execute(text("SELECT storage_key FROM document_versions")).scalar()
    assert storage.get(key).startswith(b"%PDF")

    assert client.delete(f"/v1/documents/{doc['id']}", headers=auth(ALICE)).status_code == 204
    assert client.get(f"/v1/documents/{doc['id']}", headers=auth(ALICE)).status_code == 404
    process_all(worker())

    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM document_chunks")).scalar() == 0
        assert conn.execute(text("SELECT count(*) FROM document_pages")).scalar() == 0
    with pytest.raises(StorageError):
        storage.get(key)

    thread_id, _ = ask(client, auth, course_id, "Bagaimana modulus n pada RSA dihitung?")
    process_all(worker())
    answer = client.get(f"/v1/chat/threads/{thread_id}/messages", headers=auth(ALICE)).json()[1]
    assert answer["answerable"] is False


def test_document_deleted_during_indexing_is_not_published(client, auth, worker, engine):
    course_id = create_course(client, auth)
    res = upload(client, auth, course_id, make_pdf(KRIPTO["pages"]))
    w = worker()
    original = w.embedder.embed_documents

    def delete_then_embed(texts, title=None):
        client.delete(f"/v1/documents/{res.json()['id']}", headers=auth(ALICE))
        return original(texts, title)

    w.embedder.embed_documents = delete_then_embed
    process_all(w)
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM document_chunks")).scalar() == 0
        assert conn.execute(text("SELECT active_version_id FROM documents")).scalar() is None


# ---------- Evaluasi retrieval ----------


def test_rag_fixture_retrieval_baseline():
    # Embedder demo hanya mengukur tumpang tindih kata, jadi ketepatan halaman tidak diuji di sini
    # (kalibrasi nyata: `python -m app.eval.rag_eval` dengan Gemini). Yang diuji: evaluator berjalan dan
    # pertanyaan di luar materi selalu lebih jauh daripada pertanyaan yang relevan, sehingga ambang jarak
    # dapat menolaknya.
    report = evaluate(FIXTURE, FakeEmbedder(AppEnv.TEST, 768), top_k=3)
    assert report.total == 6 and len(report.relevant_distances) == 6
    assert min(report.out_of_scope_distances) > max(report.relevant_distances), report
