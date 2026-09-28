"""Provider AI palsu yang deterministik untuk development dan tes.

Output selalu ditandai is_demo=True dan tidak boleh disajikan sebagai hasil AI nyata.
"""

import hashlib
import math
import re

from app.core.config import AppEnv
from app.domain.schemas import (
    AnswerDraft,
    GenerationResult,
    QuizDraft,
    QuizOption,
    QuizQuestion,
    SourceRef,
    SummaryDraft,
)
from app.providers.base import ContextChunk, SourceText

MODEL_ID = "fake-deterministic-v1"
PROMPT_VERSION = "fake-v1"

_SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+|\n+")
_DISTRACTORS = (
    "[Demo] Pernyataan ini tidak berasal dari materi.",
    "[Demo] Pilihan pengecoh kedua.",
    "[Demo] Pilihan pengecoh ketiga.",
)


def _sentences(text: str) -> list[str]:
    return [s.strip() for s in _SENTENCE_SPLIT.split(text) if len(s.strip()) >= 3]


def _stable_index(seed: str, modulo: int) -> int:
    return int(hashlib.sha256(seed.encode()).hexdigest(), 16) % modulo


class FakeProvider:
    def __init__(self, app_env: AppEnv) -> None:
        if app_env is AppEnv.PRODUCTION:
            raise RuntimeError("FakeProvider hanya untuk development/test")

    def summarize(self, source: SourceText) -> GenerationResult[SummaryDraft]:
        points = _sentences(source.text)[:3] or [source.text.strip() or "(materi kosong)"]
        content = "[Demo — bukan hasil AI]\n" + "\n".join(f"• {p}" for p in points)
        return GenerationResult[SummaryDraft](
            output=SummaryDraft(content=content, source_refs=[SourceRef(source_id=source.source_id)]),
            model_id=MODEL_ID,
            prompt_version=PROMPT_VERSION,
            is_demo=True,
        )

    def generate_quiz(self, source: SourceText, question_count: int) -> GenerationResult[QuizDraft]:
        sentences = _sentences(source.text) or [source.title]
        questions: list[QuizQuestion] = []
        for i in range(question_count):
            fact = sentences[i % len(sentences)]
            options_text = [fact, *_DISTRACTORS]
            # Posisi jawaban benar diacak secara deterministik agar tidak selalu "a".
            shift = _stable_index(f"{source.source_id}:{i}", len(options_text))
            options_text = options_text[shift:] + options_text[:shift]
            options = [QuizOption(id=chr(ord("a") + j), text=t) for j, t in enumerate(options_text)]
            correct = next(o.id for o in options if o.text == fact)
            questions.append(
                QuizQuestion(
                    id=f"q{i + 1}",
                    topic_id=f"{source.source_id}:t{i % len(sentences) + 1}",
                    question=f"[Demo] Pernyataan mana yang terdapat dalam materi \"{source.title}\"?",
                    options=options,
                    correct_option_id=correct,
                    explanation="[Demo] Jawaban benar adalah kalimat yang dikutip langsung dari materi.",
                    source_refs=[SourceRef(source_id=source.source_id)],
                )
            )
        return GenerationResult[QuizDraft](
            output=QuizDraft(title=f"[Demo] Kuis {source.title}", questions=questions),
            model_id=MODEL_ID,
            prompt_version=PROMPT_VERSION,
            is_demo=True,
        )

    def answer(self, question: str, contexts: list[ContextChunk]) -> GenerationResult[AnswerDraft]:
        """Demo: mengutip kalimat dari potongan teratas; tidak menalar."""
        if not contexts:
            draft = AnswerDraft(answerable=False, answer="[Demo] Materi belum memuat jawabannya.", source_ids=[])
        else:
            top = contexts[0]
            sentence = (_sentences(top.text) or [top.text.strip()])[0]
            draft = AnswerDraft(answerable=True, answer=f"[Demo] {sentence}", source_ids=[top.chunk_id])
        return GenerationResult[AnswerDraft](output=draft, model_id=MODEL_ID, prompt_version=PROMPT_VERSION,
                                             is_demo=True)


_WORD = re.compile(r"[0-9a-zA-ZÀ-ɏ]+")


class FakeEmbedder:
    """Embedding demo: hashing kata (bag-of-words) ke vektor ternormalisasi.

    Deterministik dan gratis; kemiripan = tumpang tindih kata. Hanya untuk development/tes.
    """

    def __init__(self, app_env: AppEnv, dim: int) -> None:
        if app_env is AppEnv.PRODUCTION:
            raise RuntimeError("FakeEmbedder hanya untuk development/test")
        self.model = "fake-hash-embedding-v1"
        self.dim = dim

    def embed_documents(self, texts: list[str], title: str | None = None) -> list[list[float]]:
        return [self._embed(t) for t in texts]

    def embed_query(self, text: str) -> list[float]:
        return self._embed(text)

    def _embed(self, text: str) -> list[float]:
        vector = [0.0] * self.dim
        for word in _WORD.findall(text.casefold()):
            if len(word) < 3:
                continue
            digest = hashlib.sha256(word.encode()).digest()
            index = int.from_bytes(digest[:4], "big") % self.dim
            vector[index] += 1.0 if digest[4] % 2 == 0 else -1.0
        norm = math.sqrt(sum(v * v for v in vector)) or 1.0
        return [v / norm for v in vector]
