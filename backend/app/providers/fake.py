"""Provider AI palsu yang deterministik untuk development dan tes.

Output selalu ditandai is_demo=True dan tidak boleh disajikan sebagai hasil AI nyata.
"""

import hashlib
import re

from app.core.config import AppEnv
from app.domain.schemas import (
    GenerationResult,
    QuizDraft,
    QuizOption,
    QuizQuestion,
    SourceRef,
    SummaryDraft,
)
from app.providers.base import SourceText

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
