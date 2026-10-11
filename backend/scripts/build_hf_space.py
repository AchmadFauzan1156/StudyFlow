"""Menyusun isi repo Hugging Face Space (Docker) dari backend: API + worker tertanam, gratis tanpa kartu.

Pemakaian: python scripts/build_hf_space.py <folder-tujuan>
Hanya file yang dilacak git yang disalin, jadi .env dan rahasia lain tidak ikut. Panduan: docs/DEPLOYMENT.md.
"""

import shutil
import subprocess
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
EXCLUDED_TOP = {"tests", "scripts", "README.md", "docker-compose.yml", "docker-compose.demo.yml"}

SPACE_README = """---
title: StudyFlow API
emoji: 📚
colorFrom: blue
colorTo: green
sdk: docker
app_port: 8000
pinned: false
---

Backend StudyFlow (FastAPI + worker AI tertanam). Kode sumber: https://github.com/AchmadFauzan1156/StudyFlow
"""


def _tracked_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z", "."], cwd=BACKEND, check=True, capture_output=True, text=True
    )
    return [p for p in result.stdout.split("\0") if p]


def build(out: Path) -> None:
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    for rel in _tracked_files():
        if rel.split("/", 1)[0] in EXCLUDED_TOP:
            continue
        target = out / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(BACKEND / rel, target)
    (out / "README.md").write_text(SPACE_README, encoding="utf-8", newline="\n")
    # Space tidak punya opsi start command: jalankan migrasi lalu API dengan worker tertanam.
    with (out / "Dockerfile").open("a", encoding="utf-8", newline="\n") as dockerfile:
        dockerfile.write('\nCMD ["/app/start.sh"]\n')


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Pemakaian: python scripts/build_hf_space.py <folder-tujuan>")
    build(Path(sys.argv[1]))
