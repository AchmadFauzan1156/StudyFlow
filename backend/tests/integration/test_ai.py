import threading
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import text
from sqlalchemy.orm import sessionmaker

from app.core.config import AIProviderKind, AppEnv, Settings
from app.domain import ai
from app.domain.schemas import GenerationResult, QuizDraft, SummaryDraft
from app.providers.base import ProviderInvalidOutputError, ProviderTransientError, SourceText
from app.providers.fake import FakeProvider
from app.worker.runner import Worker
from tests.integration.conftest import JWT_SECRET, SUPABASE_URL, TEST_DB_URL

pytestmark = pytest.mark.integration

ALICE = uuid.UUID("00000000-0000-4000-8000-00000000a11c")
BOB = uuid.UUID("00000000-0000-4000-8000-000000000b0b")
NOTE_TEXT = "RSA memakai dua bilangan prima p dan q. Modulus n adalah p kali q. Kunci publik terdiri dari e dan n."


@pytest.fixture
def settings() -> Settings:
    return Settings(
        _env_file=None, app_env=AppEnv.TEST, ai_provider=AIProviderKind.FAKE, database_url=TEST_DB_URL,
        supabase_url=SUPABASE_URL, jwt_hs256_secret=JWT_SECRET,
        ai_user_daily_limit=3, ai_global_daily_limit=5, ai_max_input_chars=500, ai_min_interval_seconds=0,
        ai_max_attempts=2,
    )


@pytest.fixture
def factory(engine):
    return sessionmaker(bind=engine, expire_on_commit=False)


def worker(factory, settings, provider=None, sleeps=None) -> Worker:
    return Worker(factory, settings, provider or FakeProvider(AppEnv.TEST),
                  sleep=(sleeps.append if sleeps is not None else (lambda s: None)))


def create_note(client, auth, user=ALICE, content=NOTE_TEXT) -> tuple[uuid.UUID, uuid.UUID]:
    course_id, note_id = uuid.uuid4(), uuid.uuid4()
    res = client.post("/v1/sync/push", headers=auth(user), json={"mutations": [
        {"mutation_id": str(uuid.uuid4()), "entity_type": "course", "entity_id": str(course_id),
         "operation": "create", "base_version": 0, "payload": {"name": "Kriptografi"}},
        {"mutation_id": str(uuid.uuid4()), "entity_type": "note", "entity_id": str(note_id),
         "operation": "create", "base_version": 0,
         "payload": {"course_id": str(course_id), "title": "RSA", "content": content}},
    ]})
    assert [r["status"] for r in res.json()["results"]] == ["applied", "applied"]
    return course_id, note_id


def request_quiz(client, auth, note_id, key=None, user=ALICE, version=1, count=3):
    return client.post("/v1/quizzes", headers=auth(user) | {"Idempotency-Key": key or uuid.uuid4().hex},
                       json={"note_id": str(note_id), "note_version": version, "question_count": count})


def request_summary(client, auth, note_id, key=None, user=ALICE, version=1):
    return client.post("/v1/summaries", headers=auth(user) | {"Idempotency-Key": key or uuid.uuid4().hex},
                       json={"note_id": str(note_id), "note_version": version})


def job(client, auth, job_id, user=ALICE):
    return client.get(f"/v1/jobs/{job_id}", headers=auth(user)).json()


# ---------- Membuat job ----------


def test_info_reports_demo_mode_and_quota(client, auth):
    body = client.get("/v1/ai/info", headers=auth(ALICE)).json()
    assert body["mode"] == "demo" and body["available"] is True
    assert body["daily_limit"] == 3 and body["used_today"] == 0
    assert "tidak dikirim" in body["data_notice"]


def test_unconfigured_ai_returns_503_without_consuming_quota(client, auth, settings, engine):
    _, note_id = create_note(client, auth)
    settings.ai_provider = AIProviderKind.NONE
    assert request_summary(client, auth, note_id).status_code == 503
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM ai_jobs")).scalar() == 0


def test_idempotency_key_returns_same_job_and_charges_once(client, auth, engine):
    _, note_id = create_note(client, auth)
    first = request_quiz(client, auth, note_id, key="key-12345678")
    second = request_quiz(client, auth, note_id, key="key-12345678")
    assert first.status_code == second.status_code == 202
    assert first.json()["id"] == second.json()["id"]
    assert client.get("/v1/ai/info", headers=auth(ALICE)).json()["used_today"] == 1

    other = request_quiz(client, auth, note_id, key="key-12345678", count=5)
    assert other.status_code == 409 and other.json()["code"] == "idempotency_key_reused"


def test_rejects_missing_foreign_stale_empty_and_large_sources(client, auth):
    _, note_id = create_note(client, auth)
    assert request_summary(client, auth, uuid.uuid4()).status_code == 404
    assert request_summary(client, auth, note_id, user=BOB).status_code == 404
    stale = request_summary(client, auth, note_id, version=2)
    assert stale.status_code == 409 and stale.json()["code"] == "source_version_mismatch"
    _, empty_id = create_note(client, auth, content="   ")
    assert request_summary(client, auth, empty_id).status_code == 422
    _, big_id = create_note(client, auth, content="x" * 600)
    big = request_summary(client, auth, big_id)
    assert big.status_code == 413 and big.json()["details"]["max"] == 500
    assert request_quiz(client, auth, note_id, count=50).status_code == 422
    assert client.get("/v1/ai/info", headers=auth(ALICE)).json()["used_today"] == 0


def test_user_and_global_daily_quota(client, auth):
    _, note_id = create_note(client, auth)
    for _ in range(3):
        assert request_summary(client, auth, note_id).status_code == 202
    over = request_summary(client, auth, note_id)
    assert over.status_code == 429 and over.json()["code"] == "quota_exceeded"

    _, bob_note = create_note(client, auth, user=BOB)
    assert request_summary(client, auth, bob_note, user=BOB).status_code == 202
    assert request_summary(client, auth, bob_note, user=BOB).status_code == 202
    service = request_summary(client, auth, bob_note, user=BOB)
    assert service.status_code == 429 and service.json()["code"] == "service_quota_exceeded"


def test_parallel_requests_cannot_exceed_quota(client, auth, engine):
    _, note_id = create_note(client, auth)
    codes: list[int] = []

    def send():
        codes.append(request_summary(client, auth, note_id).status_code)

    threads = [threading.Thread(target=send) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert codes.count(202) == 3 and codes.count(429) == 5
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM ai_jobs")).scalar() == 3


# ---------- Worker dan hasil ----------


def test_worker_produces_summary_and_quiz_without_leaking_answer_key(client, auth, factory, settings):
    _, note_id = create_note(client, auth)
    summary_job = request_summary(client, auth, note_id).json()
    quiz_job = request_quiz(client, auth, note_id, count=3).json()
    w = worker(factory, settings)
    assert w.run_once() and w.run_once() and not w.run_once()

    s = job(client, auth, summary_job["id"])
    assert s["status"] == "succeeded"
    summary = client.get(f"/v1/summaries/{s['result_id']}", headers=auth(ALICE)).json()
    assert summary["is_demo"] is True and summary["content"].startswith("[Demo")
    assert summary["source"] == {"note_id": str(note_id), "title": "RSA", "version": 1, "current_version": 1,
                                 "outdated": False, "deleted": False}

    q = job(client, auth, quiz_job["id"])
    quiz = client.get(f"/v1/quizzes/{q['result_id']}", headers=auth(ALICE)).json()
    assert len(quiz["questions"]) == 3
    raw = str(quiz)
    assert "correct_option_id" not in raw and "explanation" not in raw

    # Akun lain tidak bisa membaca hasil.
    assert client.get(f"/v1/quizzes/{q['result_id']}", headers=auth(BOB)).status_code == 404
    assert client.get(f"/v1/summaries/{s['result_id']}", headers=auth(BOB)).status_code == 404
    assert client.get(f"/v1/jobs/{q['id']}", headers=auth(BOB)).status_code == 404


def _quiz_with_answers(client, auth, factory, settings):
    _, note_id = create_note(client, auth)
    job_out = request_quiz(client, auth, note_id, count=3).json()
    worker(factory, settings).run_once()
    quiz_id = job(client, auth, job_out["id"])["result_id"]
    quiz = client.get(f"/v1/quizzes/{quiz_id}", headers=auth(ALICE)).json()
    with factory() as session:
        keys = dict(session.execute(text(
            "SELECT q.id::text, k.correct_option_id FROM questions q JOIN question_keys k ON k.question_id = q.id "
            "WHERE q.quiz_id = :id"), {"id": quiz_id}).all())
    return note_id, quiz_id, quiz, keys


def test_attempt_is_graded_deterministically_and_idempotent(client, auth, factory, settings, engine):
    note_id, quiz_id, quiz, keys = _quiz_with_answers(client, auth, factory, settings)
    first_q, second_q, third_q = (q["id"] for q in quiz["questions"])
    wrong = next(o["id"] for o in quiz["questions"][1]["options"] if o["id"] != keys[second_q])
    answers = {first_q: keys[first_q], second_q: wrong}  # soal ketiga tidak dijawab

    headers = auth(ALICE) | {"Idempotency-Key": "attempt-0001"}
    res = client.post(f"/v1/quizzes/{quiz_id}/attempts", headers=headers, json={"answers": answers})
    again = client.post(f"/v1/quizzes/{quiz_id}/attempts", headers=headers, json={"answers": answers})
    assert res.status_code == 200 and res.json() == again.json()
    body = res.json()
    assert (body["score"], body["total"]) == (1, 3)
    by_q = {r["question_id"]: r for r in body["results"]}
    assert by_q[first_q]["correct"] is True
    assert by_q[second_q]["correct"] is False and by_q[second_q]["correct_option_id"] == keys[second_q]
    assert by_q[third_q]["selected_option_id"] is None
    # Judul sumber dipetakan dari metadata server.
    assert by_q[first_q]["sources"] == [{"source_id": str(note_id), "title": "RSA"}]
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM attempts")).scalar() == 1

    history = client.get("/v1/attempts", headers=auth(ALICE)).json()
    assert [(h["score"], h["total"]) for h in history] == [(1, 3)]
    results = client.get(f"/v1/notes/{note_id}/ai-results", headers=auth(ALICE)).json()
    assert results["results"][0]["best_score"] == 1

    foreign = client.post(f"/v1/quizzes/{quiz_id}/attempts", headers=auth(BOB) | {"Idempotency-Key": "attempt-bob1"},
                          json={"answers": {}})
    assert foreign.status_code == 404
    unknown = client.post(f"/v1/quizzes/{quiz_id}/attempts",
                          headers=auth(ALICE) | {"Idempotency-Key": "attempt-0002"},
                          json={"answers": {str(uuid.uuid4()): "a"}})
    assert unknown.status_code == 422


def test_question_can_be_reported(client, auth, factory, settings, engine):
    _, _, quiz, _ = _quiz_with_answers(client, auth, factory, settings)
    qid = quiz["questions"][0]["id"]
    assert client.post(f"/v1/questions/{qid}/report", headers=auth(ALICE), json={"reason": "salah"}).status_code == 204
    assert client.post(f"/v1/questions/{qid}/report", headers=auth(BOB), json={}).status_code == 404
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM question_reports")).scalar() == 1


def test_source_edited_after_enqueue_fails_and_refunds(client, auth, factory, settings):
    _, note_id = create_note(client, auth)
    queued = request_summary(client, auth, note_id).json()
    client.post("/v1/sync/push", headers=auth(ALICE), json={"mutations": [
        {"mutation_id": str(uuid.uuid4()), "entity_type": "note", "entity_id": str(note_id), "operation": "update",
         "base_version": 1, "payload": {"course_id": _course_of(factory, note_id), "title": "RSA",
                                        "content": "isi baru"}}]})
    worker(factory, settings).run_once()
    result = job(client, auth, queued["id"])
    assert (result["status"], result["error_code"]) == ("failed", "source_changed")
    assert client.get("/v1/ai/info", headers=auth(ALICE)).json()["used_today"] == 0


def _course_of(factory, note_id) -> str:
    with factory() as session:
        return str(session.execute(text("SELECT course_id FROM notes WHERE id = :id"), {"id": note_id}).scalar())


def test_summary_marked_outdated_after_note_changes(client, auth, factory, settings):
    _, note_id = create_note(client, auth)
    queued = request_summary(client, auth, note_id).json()
    worker(factory, settings).run_once()
    summary_id = job(client, auth, queued["id"])["result_id"]
    client.post("/v1/sync/push", headers=auth(ALICE), json={"mutations": [
        {"mutation_id": str(uuid.uuid4()), "entity_type": "note", "entity_id": str(note_id), "operation": "update",
         "base_version": 1, "payload": {"course_id": _course_of(factory, note_id), "title": "RSA", "content": "v2"}}]})
    source = client.get(f"/v1/summaries/{summary_id}", headers=auth(ALICE)).json()["source"]
    assert (source["version"], source["current_version"], source["outdated"]) == (1, 2, True)


class ScriptedProvider:
    """Provider uji: mengeluarkan hasil/exception sesuai urutan skrip."""

    model = "scripted-model"

    def __init__(self, *script):
        self.script = list(script)
        self.calls = 0

    def _next(self):
        self.calls += 1
        item = self.script.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    def summarize(self, source: SourceText) -> GenerationResult[SummaryDraft]:
        item = self._next()
        return item if item != "ok" else GenerationResult[SummaryDraft](
            output=SummaryDraft(content="Rangkuman"), model_id=self.model, prompt_version="t", is_demo=False)

    def generate_quiz(self, source: SourceText, question_count: int) -> GenerationResult[QuizDraft]:
        item = self._next()
        if item == "ok":
            return FakeProvider(AppEnv.TEST).generate_quiz(source, question_count)
        if item == "duplicate-options":
            result = FakeProvider(AppEnv.TEST).generate_quiz(source, question_count)
            q = result.output.questions[0]
            q.options[1].text = q.options[0].text
            return result
        return item


def test_invalid_output_is_retried_exactly_once(client, auth, factory, settings):
    _, note_id = create_note(client, auth)
    queued = request_quiz(client, auth, note_id).json()
    provider = ScriptedProvider("duplicate-options", "ok")
    worker(factory, settings, provider).run_once()
    assert provider.calls == 2
    assert job(client, auth, queued["id"])["status"] == "succeeded"

    queued = request_summary(client, auth, note_id).json()
    provider = ScriptedProvider(ProviderInvalidOutputError("x"), ProviderInvalidOutputError("x"), "ok")
    worker(factory, settings, provider).run_once()
    assert provider.calls == 2
    failed = job(client, auth, queued["id"])
    assert (failed["status"], failed["error_code"]) == ("failed", "invalid_output")
    # Gagal karena output AI tidak dihitung ke kuota pengguna.
    assert client.get("/v1/ai/info", headers=auth(ALICE)).json()["used_today"] == 1


def test_rate_limited_provider_reschedules_then_gives_up(client, auth, factory, settings, engine):
    _, note_id = create_note(client, auth)
    queued = request_summary(client, auth, note_id).json()
    provider = ScriptedProvider(ProviderTransientError("429", retry_after_seconds=120),
                                ProviderTransientError("429"))
    w = worker(factory, settings, provider)
    w.run_once()
    with engine.connect() as conn:
        status, run_after, attempts = conn.execute(
            text("SELECT status, run_after, attempts FROM ai_jobs WHERE id = :id"), {"id": queued["id"]}).one()
    assert (status, attempts) == ("queued", 1)
    assert run_after > datetime.now(UTC) + timedelta(seconds=100)
    assert not w.run_once(), "job belum boleh diambil sebelum run_after"

    with engine.begin() as conn:
        conn.execute(text("UPDATE ai_jobs SET run_after = now()"))
    w.run_once()
    final = job(client, auth, queued["id"])
    assert (final["status"], final["error_code"]) == ("failed", "provider_unavailable")
    assert client.get("/v1/ai/info", headers=auth(ALICE)).json()["used_today"] == 0


def test_expired_lease_is_recovered_and_stale_worker_cannot_write(client, auth, factory, settings, engine):
    _, note_id = create_note(client, auth)
    queued = request_summary(client, auth, note_id).json()
    with factory() as session:
        job_id, first_attempt = ai.claim_next_job(session, lease_seconds=60)
    # Worker pertama "crash": lease dibiarkan habis.
    with engine.begin() as conn:
        conn.execute(text("UPDATE ai_jobs SET lease_until = now() - interval '1 second'"))
    with factory() as session:
        again = ai.claim_next_job(session, lease_seconds=60)
    assert again == (job_id, first_attempt + 1)

    stale = GenerationResult[SummaryDraft](output=SummaryDraft(content="basi"), model_id="m", prompt_version="t",
                                           is_demo=False)
    with factory() as session:
        assert ai.complete(session, job_id, first_attempt, stale) is False
    with factory() as session:
        assert ai.complete(session, job_id, first_attempt + 1, stale) is True
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM summaries")).scalar() == 1
    assert job(client, auth, queued["id"])["status"] == "succeeded"


def test_concurrent_claims_never_take_the_same_job(client, auth, factory, settings):
    _, note_id = create_note(client, auth)
    for _ in range(3):
        request_summary(client, auth, note_id)
    claimed: list = []

    def claim():
        with factory() as session:
            claimed.append(ai.claim_next_job(session, 60))

    threads = [threading.Thread(target=claim) for _ in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    ids = [c[0] for c in claimed if c]
    assert len(ids) == 3 and len(set(ids)) == 3


def test_worker_throttles_provider_calls(client, auth, factory, settings):
    _, note_id = create_note(client, auth)
    request_summary(client, auth, note_id)
    request_summary(client, auth, note_id)
    settings.ai_min_interval_seconds = 8
    sleeps: list[float] = []
    w = worker(factory, settings, sleeps=sleeps)
    w.run_once()
    w.run_once()
    assert len(sleeps) == 1 and 7 < sleeps[0] <= 8
