from app.core.config import AIProviderKind, AppEnv


def test_health_ok_without_secrets(client):
    res = client.get("/health")
    assert res.status_code == 200
    body = res.json()
    assert body == {"status": "ok", "version": "0.1.0", "environment": "test", "ai_mode": "unconfigured"}
    assert res.headers["X-Request-ID"]


def test_health_reports_demo_mode(make_client):
    res = make_client(app_env=AppEnv.DEVELOPMENT, ai_provider=AIProviderKind.FAKE).get("/health")
    assert res.json()["ai_mode"] == "demo"


def test_unknown_route_uses_error_format(client):
    res = client.get("/v1/does-not-exist")
    assert res.status_code == 404
    body = res.json()
    assert set(body) == {"code", "message", "request_id", "details"}
    assert body["code"] == "not_found"
    assert body["request_id"] == res.headers["X-Request-ID"]


def test_request_id_is_not_taken_from_client(client):
    res = client.get("/health", headers={"X-Request-ID": "attacker-controlled"})
    assert res.headers["X-Request-ID"] != "attacker-controlled"
