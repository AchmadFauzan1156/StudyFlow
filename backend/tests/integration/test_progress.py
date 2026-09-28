import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy.orm import sessionmaker

from app.core.config import AIProviderKind, AppEnv, Settings
from app.domain import progress
from tests.integration.conftest import JWT_SECRET, SUPABASE_URL, TEST_DB_URL
from tests.integration.test_ai import ALICE, BOB, create_note, job, request_quiz, worker

pytestmark = pytest.mark.integration


@pytest.fixture
def settings() -> Settings:
    return Settings(
        _env_file=None, app_env=AppEnv.TEST, ai_provider=AIProviderKind.FAKE, database_url=TEST_DB_URL,
        supabase_url=SUPABASE_URL, jwt_hs256_secret=JWT_SECRET, ai_min_interval_seconds=0,
        practice_min_answers=5, practice_accuracy_threshold=0.6,
    )


@pytest.fixture
def factory(engine):
    return sessionmaker(bind=engine, expire_on_commit=False)


def make_quiz(client, auth, factory, settings, count=10):
    _, note_id = create_note(client, auth)
    queued = request_quiz(client, auth, note_id, count=count).json()
    worker(factory, settings).run_once()
    quiz = client.get(f"/v1/quizzes/{job(client, auth, queued['id'])['result_id']}", headers=auth(ALICE)).json()
    return note_id, quiz


def submit(client, auth, quiz, choose) -> dict:
    answers = {q["id"]: choose(q) for q in quiz["questions"]}
    res = client.post(f"/v1/quizzes/{quiz['id']}/attempts", headers=auth(ALICE) | {"Idempotency-Key": uuid.uuid4().hex},
                      json={"answers": answers})
    assert res.status_code == 200, res.text
    return {r["question_id"]: r for r in res.json()["results"]}


def distractor(q) -> str:
    """Pilihan yang pasti salah: pengecoh provider demo selalu berawalan [Demo]."""
    return next(o["id"] for o in q["options"] if o["text"].startswith("[Demo]"))


def wrong(result_by_q):
    return lambda q: next(o["id"] for o in q["options"] if o["id"] != result_by_q[q["id"]]["correct_option_id"])


def test_topic_accuracy_and_practice_recommendation(client, auth, factory, settings):
    _, quiz = make_quiz(client, auth, factory, settings)
    first = submit(client, auth, quiz, distractor)
    wrong_choice = wrong(first)
    t1 = {q["id"] for q in quiz["questions"] if q["topic_id"].endswith(":t1")}
    # Percobaan kedua: topik t1 benar semua, topik lain salah, satu soal tidak dijawab.
    skipped = next(q["id"] for q in quiz["questions"] if q["topic_id"].endswith(":t3"))
    submit(client, auth, quiz, lambda q: None if q["id"] == skipped
           else (first[q["id"]]["correct_option_id"] if q["id"] in t1 else wrong_choice(q)))

    body = client.get("/v1/progress/topics", headers=auth(ALICE)).json()
    topics = {t["topic_id"].split(":")[-1]: t for t in body["topics"]}
    assert (body["min_answers"], body["accuracy_threshold"]) == (5, 0.6)
    assert topics["t1"]["answered"] == 8 and topics["t1"]["note_title"] == "RSA"
    assert topics["t3"]["answered"] == 5, "jawaban kosong tidak dihitung"
    assert topics["t2"]["needs_practice"] is True and topics["t2"]["accuracy"] < 0.6
    assert body["topics"][0]["needs_practice"] is True, "topik yang perlu latihan diurutkan lebih dulu"
    assert topics["t2"]["label"] == "T2"

    # Soal yang dilaporkan tidak dihitung.
    reported = next(q["id"] for q in quiz["questions"] if q["topic_id"].endswith(":t2"))
    client.post(f"/v1/questions/{reported}/report", headers=auth(ALICE), json={})
    after = {t["topic_id"].split(":")[-1]: t for t in client.get("/v1/progress/topics", headers=auth(ALICE)).json()["topics"]}
    assert after["t2"]["answered"] == topics["t2"]["answered"] - 2

    assert client.get("/v1/progress/topics", headers=auth(BOB)).json()["topics"] == []


def test_flashcards_come_from_wrong_answers_and_follow_leitner(client, auth, factory, settings):
    _, quiz = make_quiz(client, auth, factory, settings, count=3)
    first = submit(client, auth, quiz, distractor)
    wrong_ids = {qid for qid, r in first.items() if not r["correct"]}
    assert len(wrong_ids) == 3

    due = client.get("/v1/flashcards/due", headers=auth(ALICE)).json()
    assert {c["question_id"] for c in due["cards"]} == wrong_ids and due["due_total"] == len(wrong_ids)
    card = due["cards"][0]
    correct_text = next(o["text"] for q in quiz["questions"] if q["id"] == card["question_id"]
                        for o in q["options"] if o["id"] == first[card["question_id"]]["correct_option_id"])
    assert card["answer"] == correct_text and card["box"] == 1

    remembered = client.post(f"/v1/flashcards/{card['question_id']}/review", headers=auth(ALICE),
                             json={"remembered": True}).json()
    assert remembered["box"] == 2
    left = {c["question_id"] for c in client.get("/v1/flashcards/due", headers=auth(ALICE)).json()["cards"]}
    assert card["question_id"] not in left, "kartu yang diingat dijadwalkan ulang"

    forgot = client.post(f"/v1/flashcards/{card['question_id']}/review", headers=auth(ALICE),
                         json={"remembered": False}).json()
    assert forgot["box"] == 1

    assert client.get("/v1/flashcards/due", headers=auth(BOB)).json() == {"cards": [], "due_total": 0}
    assert client.post(f"/v1/flashcards/{card['question_id']}/review", headers=auth(BOB),
                       json={"remembered": True}).status_code == 404


def test_leitner_intervals_and_due_dates(client, auth, factory, settings):
    _, quiz = make_quiz(client, auth, factory, settings, count=3)
    first = submit(client, auth, quiz, distractor)
    qid = uuid.UUID(next(qid for qid, r in first.items() if not r["correct"]))
    now = datetime(2026, 10, 1, tzinfo=UTC)
    with factory() as session:
        boxes = [progress.review(session, ALICE, qid, True, now).box for _ in range(6)]
        assert boxes == [2, 3, 4, 5, 5, 5]
        card = progress.review(session, ALICE, qid, True, now)
        assert card.due_at == now + timedelta(days=16)
        cards, _ = progress.due_flashcards(session, ALICE, 20, now + timedelta(days=17))
        assert qid in {c.question_id for c in cards}
