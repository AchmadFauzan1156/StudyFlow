import threading
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text

from tests.integration.conftest import make_token

pytestmark = pytest.mark.integration

ALICE = uuid.UUID("00000000-0000-4000-8000-00000000a11c")
BOB = uuid.UUID("00000000-0000-4000-8000-000000000b0b")


def mutation(entity_type: str, entity_id: uuid.UUID, operation: str, base_version: int = 0,
             payload: dict | None = None, mutation_id: uuid.UUID | None = None) -> dict:
    return {
        "mutation_id": str(mutation_id or uuid.uuid4()),
        "entity_type": entity_type,
        "entity_id": str(entity_id),
        "operation": operation,
        "base_version": base_version,
        "payload": payload or {},
    }


def push(client: TestClient, auth, user: uuid.UUID, *mutations: dict) -> list[dict]:
    res = client.post("/v1/sync/push", json={"mutations": list(mutations)}, headers=auth(user))
    assert res.status_code == 200, res.text
    return res.json()["results"]


def pull_all(client: TestClient, auth, user: uuid.UUID, cursor: str | None = None, limit: int = 200):
    changes = []
    while True:
        params = {"limit": limit} | ({"cursor": cursor} if cursor else {})
        res = client.get("/v1/sync/pull", params=params, headers=auth(user))
        assert res.status_code == 200, res.text
        body = res.json()
        changes += body["changes"]
        cursor = body["next_cursor"]
        if not body["has_more"]:
            return changes, cursor


def create_course(client, auth, user, name="Kriptografi") -> uuid.UUID:
    course_id = uuid.uuid4()
    [r] = push(client, auth, user, mutation("course", course_id, "create", payload={"name": name}))
    assert r["status"] == "applied" and r["version"] == 1
    return course_id


# ---------- Autentikasi ----------


def test_requires_bearer_token(client):
    res = client.get("/v1/me")
    assert res.status_code == 401
    assert res.json()["code"] == "unauthorized"


@pytest.mark.parametrize(
    "token_kwargs",
    [
        {"secret": "wrong-secret-with-at-least-32-bytes-0"},
        {"aud": "anon"},
        {"iss": "https://evil.example/auth/v1"},
        {"exp_delta": -120},
    ],
)
def test_rejects_invalid_tokens(client, token_kwargs):
    token = make_token(ALICE, **token_kwargs)
    res = client.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert res.status_code == 401


def test_me_creates_profile_from_sub(client, auth):
    res = client.get("/v1/me", headers=auth(ALICE))
    assert res.status_code == 200
    assert res.json() == {"user_id": str(ALICE), "display_name": None, "timezone": "Asia/Jakarta"}


# ---------- Push ----------


def test_retry_with_same_mutation_is_idempotent(client, auth, engine):
    course_id = uuid.uuid4()
    m = mutation("course", course_id, "create", payload={"name": "Basis Data"})
    first = push(client, auth, ALICE, m)
    second = push(client, auth, ALICE, m)
    assert first == second
    with engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM courses")).scalar() == 1
        assert conn.execute(text("SELECT last_seq FROM sync_counters")).scalar() == 1


def test_reused_mutation_id_with_different_payload_is_rejected(client, auth):
    course_id = uuid.uuid4()
    m = mutation("course", course_id, "create", payload={"name": "A"})
    push(client, auth, ALICE, m)
    m["payload"] = {"name": "B"}
    [r] = push(client, auth, ALICE, m)
    assert r["status"] == "rejected" and r["error_code"] == "mutation_id_reused"


def test_stale_update_returns_conflict_with_server_copy(client, auth):
    course_id = create_course(client, auth, ALICE)
    [ok] = push(client, auth, ALICE,
                mutation("course", course_id, "update", 1, {"name": "Perangkat A"}))
    assert ok["status"] == "applied" and ok["version"] == 2
    [conflict] = push(client, auth, ALICE,
                      mutation("course", course_id, "update", 1, {"name": "Perangkat B"}))
    assert conflict["status"] == "conflict"
    assert conflict["version"] == 2
    assert conflict["server_entity"]["name"] == "Perangkat A"


def test_invalid_payload_is_rejected_per_mutation(client, auth):
    course_id = uuid.uuid4()
    results = push(
        client, auth, ALICE,
        mutation("course", course_id, "create", payload={"name": "   "}),
        mutation("course", uuid.uuid4(), "create", payload={"name": "Valid"}),
    )
    assert [r["status"] for r in results] == ["rejected", "applied"]
    assert results[0]["error_code"] == "invalid_payload"


def test_delete_course_cascades_to_children(client, auth):
    course_id = create_course(client, auth, ALICE)
    note_id, task_id = uuid.uuid4(), uuid.uuid4()
    push(
        client, auth, ALICE,
        mutation("note", note_id, "create", payload={"course_id": str(course_id), "title": "RSA", "content": "p, q"}),
        mutation("task", task_id, "create", payload={
            "course_id": str(course_id), "title": "Laporan", "description": "", "due_at": 1790000000000,
            "time_zone_id": "Asia/Jakarta", "status": "TODO"}),
    )
    _, cursor = pull_all(client, auth, ALICE)

    [r] = push(client, auth, ALICE, mutation("course", course_id, "delete", 1))
    assert r["status"] == "applied" and r["version"] == 2

    changes, _ = pull_all(client, auth, ALICE, cursor)
    by_id = {c["entity"]["id"]: c["entity"] for c in changes}
    assert set(by_id) == {str(course_id), str(note_id), str(task_id)}
    assert all(e["deleted_at"] is not None for e in by_id.values())
    assert by_id[str(note_id)]["version"] == 2

    # Mutasi anak berbasis versi lama kini konflik, bukan menimpa diam-diam.
    [late] = push(client, auth, ALICE, mutation(
        "note", note_id, "update", 1, {"course_id": str(course_id), "title": "RSA 2", "content": ""}))
    assert late["status"] == "conflict"


def test_child_requires_live_parent(client, auth):
    [r] = push(client, auth, ALICE, mutation(
        "note", uuid.uuid4(), "create", payload={"course_id": str(uuid.uuid4()), "title": "X", "content": ""}))
    assert r["status"] == "rejected" and r["error_code"] == "parent_not_found"


def test_push_batch_limit(client, auth):
    many = [mutation("course", uuid.uuid4(), "create", payload={"name": f"C{i}"}) for i in range(101)]
    res = client.post("/v1/sync/push", json={"mutations": many}, headers=auth(ALICE))
    assert res.status_code == 413


# ---------- Isolasi antar akun ----------


def test_other_user_cannot_touch_or_see_data(client, auth):
    course_id = create_course(client, auth, ALICE, "Rahasia Alice")

    [update] = push(client, auth, BOB, mutation("course", course_id, "update", 1, {"name": "Diretas"}))
    assert update["status"] == "rejected" and update["error_code"] == "not_found"
    [delete] = push(client, auth, BOB, mutation("course", course_id, "delete", 1))
    assert delete["status"] == "rejected"
    [child] = push(client, auth, BOB, mutation(
        "note", uuid.uuid4(), "create", payload={"course_id": str(course_id), "title": "x", "content": ""}))
    assert child["error_code"] == "parent_not_found"
    [same_id] = push(client, auth, BOB, mutation("course", course_id, "create", payload={"name": "Bob"}))
    assert same_id["status"] == "rejected" and same_id["server_entity"] is None

    bob_changes, _ = pull_all(client, auth, BOB)
    assert bob_changes == []
    alice_changes, _ = pull_all(client, auth, ALICE)
    assert [c["entity"]["name"] for c in alice_changes] == ["Rahasia Alice"]


def test_database_roles_cannot_read_business_tables(engine):
    with engine.connect() as conn:
        for role in ("anon", "authenticated"):
            conn.execute(text(f"SET ROLE {role}"))
            with pytest.raises(Exception, match="permission denied"):
                conn.execute(text("SELECT * FROM courses"))
            conn.rollback()
            conn.execute(text("RESET ROLE"))


# ---------- Pull ----------


def test_pull_pages_and_cursor(client, auth):
    ids = [create_course(client, auth, ALICE, f"MK {i}") for i in range(5)]
    res = client.get("/v1/sync/pull", params={"limit": 2}, headers=auth(ALICE))
    body = res.json()
    assert len(body["changes"]) == 2 and body["has_more"] is True
    changes, cursor = pull_all(client, auth, ALICE, limit=2)
    assert [c["entity"]["id"] for c in changes] == [str(i) for i in ids]

    again, same_cursor = pull_all(client, auth, ALICE, cursor)
    assert again == [] and same_cursor == cursor


def test_invalid_or_future_cursor_requires_resync(client, auth):
    create_course(client, auth, ALICE)
    for cursor in ("bukan-cursor", "djE6OTk5OQ"):  # "v1:9999"
        res = client.get("/v1/sync/pull", params={"cursor": cursor}, headers=auth(ALICE))
        assert res.status_code == 400
        assert res.json()["code"] == "cursor_invalid"


def test_entity_appears_once_with_latest_state(client, auth):
    course_id = create_course(client, auth, ALICE, "v1")
    push(client, auth, ALICE, mutation("course", course_id, "update", 1, {"name": "v2"}))
    changes, _ = pull_all(client, auth, ALICE)
    assert len(changes) == 1
    assert changes[0]["entity"]["name"] == "v2" and changes[0]["entity"]["version"] == 2


def test_concurrent_pushes_keep_unique_ordered_sequence(client, engine):
    errors: list[Exception] = []

    def worker(n: int) -> None:
        try:
            headers = {"Authorization": f"Bearer {make_token(ALICE)}"}
            for i in range(5):
                m = mutation("course", uuid.uuid4(), "create", payload={"name": f"T{n}-{i}"})
                res = client.post("/v1/sync/push", json={"mutations": [m]}, headers=headers)
                assert res.status_code == 200, res.text
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    threads = [threading.Thread(target=worker, args=(n,)) for n in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors
    with engine.connect() as conn:
        seqs = conn.execute(text("SELECT seq FROM sync_changes ORDER BY seq")).scalars().all()
    assert seqs == list(range(1, 21))
