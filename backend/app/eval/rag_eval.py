"""Evaluasi retrieval pada fixture yang diketahui jawabannya.

Jalankan dengan embedder yang sedang dikonfigurasi (mis. Gemini) untuk mengkalibrasi
STUDYFLOW_RAG_MAX_DISTANCE:

    uv run python -m app.eval.rag_eval tests/fixtures/rag_eval.json

Laporan: hit@k (halaman rujukan ada di top-k), jarak terdekat pertanyaan relevan vs di luar materi.
Skor kemiripan bukan probabilitas kebenaran; evaluasi groundedness jawaban tetap perlu dilakukan manual.
"""

import json
import sys
from dataclasses import dataclass
from pathlib import Path

from app.core.config import get_settings
from app.documents.chunking import chunk_pages
from app.providers.factory import Embedder, get_embedder


@dataclass
class EvalReport:
    hits: int
    total: int
    relevant_distances: list[float]
    out_of_scope_distances: list[float]

    @property
    def hit_rate(self) -> float:
        return self.hits / self.total if self.total else 0.0


def _distance(a: list[float], b: list[float]) -> float:
    return 1.0 - sum(x * y for x, y in zip(a, b, strict=True))


def evaluate(fixture: dict, embedder: Embedder, top_k: int, chunk_words: int = 60, overlap: int = 10) -> EvalReport:
    corpus: list[tuple[str, int, list[float]]] = []  # (material, halaman awal, vektor)
    for material in fixture["materials"]:
        chunks = chunk_pages(material["pages"], chunk_words, overlap)
        vectors = embedder.embed_documents([c.text for c in chunks], material["title"])
        corpus += [(material["id"], c.page_start, v) for c, v in zip(chunks, vectors, strict=True)]

    hits, relevant = 0, []
    for q in fixture["questions"]:
        qv = embedder.embed_query(q["question"])
        ranked = sorted(corpus, key=lambda item: _distance(qv, item[2]))[:top_k]
        relevant.append(_distance(qv, ranked[0][2]))
        if any(m == q["material"] and page == q["page"] for m, page, _ in ranked):
            hits += 1
    out = []
    for question in fixture["out_of_scope"]:
        qv = embedder.embed_query(question)
        out.append(min(_distance(qv, item[2]) for item in corpus))
    return EvalReport(hits, len(fixture["questions"]), relevant, out)


def main(path: str) -> int:
    settings = get_settings()
    report = evaluate(json.loads(Path(path).read_text(encoding="utf-8")), get_embedder(settings), settings.rag_top_k)
    print(f"hit@{settings.rag_top_k}: {report.hits}/{report.total} ({report.hit_rate:.0%})")
    print("jarak terdekat pertanyaan relevan :", ", ".join(f"{d:.3f}" for d in report.relevant_distances))
    print("jarak terdekat di luar materi     :", ", ".join(f"{d:.3f}" for d in report.out_of_scope_distances))
    print(f"STUDYFLOW_RAG_MAX_DISTANCE saat ini: {settings.rag_max_distance}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "tests/fixtures/rag_eval.json"))
