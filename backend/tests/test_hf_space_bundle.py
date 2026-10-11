import importlib.util
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("build_hf_space", BACKEND / "scripts" / "build_hf_space.py")
build_hf_space = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(build_hf_space)


def test_bundle_is_a_docker_space_that_runs_start_script(tmp_path):
    out = tmp_path / "space"
    build_hf_space.build(out)

    readme = (out / "README.md").read_text(encoding="utf-8")
    assert readme.startswith("---\n")
    assert "sdk: docker" in readme
    assert "app_port: 8000" in readme
    dockerfile = (out / "Dockerfile").read_text(encoding="utf-8").rstrip().splitlines()
    assert dockerfile[-1] == 'CMD ["/app/start.sh"]'
    for needed in ("app/main.py", "migrations/env.py", "alembic.ini", "pyproject.toml", "uv.lock", "docker/start.sh"):
        assert (out / needed).is_file(), needed
    for text_file in ("README.md", "Dockerfile", "docker/start.sh"):
        assert b"\r" not in (out / text_file).read_bytes(), text_file


def test_bundle_leaves_out_secrets_and_tests(tmp_path):
    out = tmp_path / "space"
    build_hf_space.build(out)

    assert not (out / ".env").exists()
    assert not (out / "tests").exists()
    assert not any(p.name == "__pycache__" for p in out.rglob("*"))
