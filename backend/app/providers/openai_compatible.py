"""Adapter untuk API Chat Completions kompatibel OpenAI (default: Groq free plan).

Model hanya menerima teks materi dan tidak diberi tool atau akses data lain.
"""

import json
import re
from typing import Any

import httpx2
from pydantic import BaseModel, ValidationError

from app.domain.schemas import (
    GenerationResult,
    QuizDraft,
    QuizOption,
    QuizQuestion,
    SourceRef,
    SummaryDraft,
)
from app.providers import prompts
from app.providers.base import (
    ProviderInvalidOutputError,
    ProviderRejectedError,
    ProviderTransientError,
    SourceText,
)

_OPTION_IDS = "abcdefgh"


class _SummaryOut(BaseModel):
    summary: str
    key_points: list[str]


class _QuestionOut(BaseModel):
    topic: str
    question: str
    options: list[str]
    correct_index: int
    explanation: str


class _QuizOut(BaseModel):
    title: str
    questions: list[_QuestionOut]


def topic_id(source_id: str, topic: str) -> str:
    """ID topik stabil per materi: slug label topik (dipakai untuk progres per topik)."""
    slug = re.sub(r"[^a-z0-9]+", "-", topic.casefold()).strip("-")[:40] or "umum"
    return f"{source_id}:{slug}"


class OpenAICompatibleProvider:
    def __init__(self, base_url: str, api_key: str, model: str, timeout_seconds: float,
                 transport: httpx2.BaseTransport | None = None) -> None:
        self.model = model
        self._client = httpx2.Client(
            base_url=base_url.rstrip("/") + "/",
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=httpx2.Timeout(timeout_seconds, connect=10.0),
            transport=transport,
        )

    def close(self) -> None:
        self._client.close()

    def summarize(self, source: SourceText) -> GenerationResult[SummaryDraft]:
        data, usage = self._complete(prompts.SUMMARY_SYSTEM, prompts.summary_user_prompt(source),
                                     "summary", prompts.SUMMARY_SCHEMA)
        parsed = _parse(_SummaryOut, data)
        points = "\n".join(f"• {p.strip()}" for p in parsed.key_points if p.strip())
        content = parsed.summary.strip() + (f"\n\n{points}" if points else "")
        return GenerationResult[SummaryDraft](
            output=SummaryDraft(content=content, source_refs=[SourceRef(source_id=source.source_id)]),
            model_id=self.model, prompt_version=prompts.PROMPT_VERSION, is_demo=False, **usage,
        )

    def generate_quiz(self, source: SourceText, question_count: int) -> GenerationResult[QuizDraft]:
        data, usage = self._complete(prompts.QUIZ_SYSTEM, prompts.quiz_user_prompt(source, question_count),
                                     "quiz", prompts.QUIZ_SCHEMA)
        parsed = _parse(_QuizOut, data)
        questions = []
        for i, q in enumerate(parsed.questions):
            options = [QuizOption(id=_OPTION_IDS[j], text=t.strip()) for j, t in enumerate(q.options[: len(_OPTION_IDS)])]
            if not 0 <= q.correct_index < len(options):
                raise ProviderInvalidOutputError(f"correct_index di luar jangkauan pada soal {i + 1}")
            questions.append(
                QuizQuestion(
                    id=f"q{i + 1}",
                    topic_id=topic_id(source.source_id, q.topic),
                    question=q.question.strip(),
                    options=options,
                    correct_option_id=options[q.correct_index].id,
                    explanation=q.explanation.strip(),
                    source_refs=[SourceRef(source_id=source.source_id)],
                )
            )
        try:
            draft = QuizDraft(title=parsed.title.strip() or source.title, questions=questions)
        except ValidationError as exc:
            raise ProviderInvalidOutputError("struktur kuis tidak valid") from exc
        return GenerationResult[QuizDraft](
            output=draft, model_id=self.model, prompt_version=prompts.PROMPT_VERSION, is_demo=False, **usage,
        )

    def _complete(self, system: str, user: str, schema_name: str, schema: dict) -> tuple[Any, dict]:
        body = {
            "model": self.model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0.3,
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": schema_name, "strict": True, "schema": schema},
            },
        }
        try:
            response = self._client.post("chat/completions", json=body)
        except httpx2.TimeoutException as exc:
            raise ProviderTransientError("provider timeout") from exc
        except httpx2.RequestError as exc:
            raise ProviderTransientError("provider tidak dapat dihubungi") from exc

        if response.status_code == 429 or response.status_code >= 500:
            raise ProviderTransientError(
                f"provider membalas {response.status_code}", _retry_after(response.headers.get("retry-after"))
            )
        if response.status_code >= 400:
            # Jangan meneruskan isi error mentah ke pengguna; cukup kode status.
            raise ProviderRejectedError(f"provider menolak permintaan ({response.status_code})")

        try:
            payload = response.json()
            choice = payload["choices"][0]
            if choice.get("finish_reason") == "length":
                raise ProviderInvalidOutputError("output terpotong")
            content = choice["message"]["content"]
            usage = payload.get("usage") or {}
            return json.loads(content), {
                "input_units": usage.get("prompt_tokens"),
                "output_units": usage.get("completion_tokens"),
            }
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise ProviderInvalidOutputError("respons provider tidak dapat dibaca") from exc


def _parse[T: BaseModel](model: type[T], data: Any) -> T:
    try:
        return model.model_validate(data)
    except ValidationError as exc:
        raise ProviderInvalidOutputError("output tidak sesuai schema") from exc


def _retry_after(value: str | None) -> float | None:
    try:
        return max(0.0, float(value)) if value else None
    except ValueError:
        return None
