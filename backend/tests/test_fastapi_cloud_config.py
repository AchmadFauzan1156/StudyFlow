import importlib
import tomllib
from pathlib import Path

from fastapi import FastAPI

PYPROJECT = Path(__file__).resolve().parents[1] / "pyproject.toml"


def test_fastapi_cloud_entrypoint_points_to_the_api_app():
    config = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))
    module_name, attr = config["tool"]["fastapi"]["entrypoint"].split(":")
    assert isinstance(getattr(importlib.import_module(module_name), attr), FastAPI)


def test_fastapi_cloud_runtime_has_the_fastapi_cli():
    config = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))
    assert any(dep.startswith("fastapi[standard]") for dep in config["project"]["dependencies"])
