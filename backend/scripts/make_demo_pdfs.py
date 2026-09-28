"""Membuat PDF demo berlabel dari fixture evaluasi RAG ke folder demo-pdfs/ (tidak di-commit)."""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from tests.pdf_factory import blank_pdf, make_pdf


def main() -> None:
    out = ROOT / "demo-pdfs"
    out.mkdir(exist_ok=True)
    fixture = json.loads((ROOT / "tests" / "fixtures" / "rag_eval.json").read_text(encoding="utf-8"))
    for material in fixture["materials"]:
        pages = [f"[DEMO] {material['title']} - halaman {i + 1}. {text}" for i, text in enumerate(material["pages"])]
        (out / f"{material['id']}.pdf").write_bytes(make_pdf(pages))
    (out / "scan-kosong.pdf").write_bytes(blank_pdf(2))
    print(f"PDF demo dibuat di {out}")


if __name__ == "__main__":
    main()
