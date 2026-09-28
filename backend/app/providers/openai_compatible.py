"""Adapter untuk API Chat Completions kompatibel OpenAI (alternatif, mis. Groq free plan).

Model hanya menerima teks materi dan tidak diberi tool atau akses data lain.
"""

import json
from typing import Any

import httpx2

from app.domain.schemas import AnswerDraft, GenerationResult, QuizDraft, SummaryDraft
from app.providers import prompts, structured
from app.providers.base import (
    ContextChunk,
    ProviderInvalidOutputError,
    ProviderRejectedError,
    ProviderTransientError,
    SourceText,
)
from app.providers.http_util import retry_after

# Diekspor ulang untuk kompatibilitas impor lama.
topic_id = structured.topic_id


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
        return structured.summary_result(data, source, self.model, usage)

    def generate_quiz(self, source: SourceText, question_count: int) -> GenerationResult[QuizDraft]:
        data, usage = self._complete(prompts.QUIZ_SYSTEM, prompts.quiz_user_prompt(source, question_count),
                                     "quiz", prompts.QUIZ_SCHEMA)
        return structured.quiz_result(data, source, self.model, usage)

    def answer(self, question: str, contexts: list[ContextChunk]) -> GenerationResult[AnswerDraft]:
        user = prompts.answer_user_prompt(question, [(c.chunk_id, c.label, c.text) for c in contexts])
        data, usage = self._complete(prompts.ANSWER_SYSTEM, user, "answer", prompts.ANSWER_SCHEMA)
        return structured.answer_result(data, self.model, usage)

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
                f"provider membalas {response.status_code}", retry_after(response.headers.get("retry-after"))
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
