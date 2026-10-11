"""Adapter Google Gemini API (native REST, v1beta).

- Generasi: `models/{model}:generateContent` dengan `responseMimeType=application/json` dan
  `responseJsonSchema`.
- Embedding: `models/{model}:batchEmbedContents` dengan `taskType` dan `outputDimensionality`.
- API key dikirim lewat header `x-goog-api-key`, tidak pernah di URL (agar tidak masuk log).
- Model hanya menerima teks materi dan tidak diberi tool.
"""

import json
import math
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


def _client(base_url: str, api_key: str, timeout_seconds: float, transport: httpx2.BaseTransport | None) -> httpx2.Client:
    return httpx2.Client(
        base_url=base_url.rstrip("/") + "/",
        headers={"x-goog-api-key": api_key},
        timeout=httpx2.Timeout(timeout_seconds, connect=10.0),
        transport=transport,
    )


def _post(client: httpx2.Client, path: str, body: dict) -> dict:
    try:
        response = client.post(path, json=body)
    except httpx2.TimeoutException as exc:
        raise ProviderTransientError("provider timeout") from exc
    except httpx2.RequestError as exc:
        raise ProviderTransientError("provider tidak dapat dihubungi") from exc
    if response.status_code == 429 or response.status_code >= 500:
        raise ProviderTransientError(
            f"provider membalas {response.status_code}", retry_after(response.headers.get("retry-after"))
        )
    if response.status_code >= 400:
        # Isi error mentah tidak diteruskan (bisa memuat detail konfigurasi).
        raise ProviderRejectedError(f"provider menolak permintaan ({response.status_code})")
    try:
        return response.json()
    except ValueError as exc:
        raise ProviderInvalidOutputError("respons provider tidak dapat dibaca") from exc


class GeminiProvider:
    def __init__(self, api_key: str, model: str, base_url: str, timeout_seconds: float,
                 transport: httpx2.BaseTransport | None = None) -> None:
        self.model = model
        self._client = _client(base_url, api_key, timeout_seconds, transport)

    def close(self) -> None:
        self._client.close()

    def summarize(self, source: SourceText) -> GenerationResult[SummaryDraft]:
        data, usage = self._generate(prompts.SUMMARY_SYSTEM, prompts.summary_user_prompt(source), prompts.SUMMARY_SCHEMA)
        return structured.summary_result(data, source, self.model, usage)

    def generate_quiz(self, source: SourceText, question_count: int) -> GenerationResult[QuizDraft]:
        data, usage = self._generate(prompts.QUIZ_SYSTEM, prompts.quiz_user_prompt(source, question_count),
                                     prompts.QUIZ_SCHEMA)
        return structured.quiz_result(data, source, self.model, usage)

    def answer(self, question: str, contexts: list[ContextChunk]) -> GenerationResult[AnswerDraft]:
        user = prompts.answer_user_prompt(question, [(c.chunk_id, c.label, c.text) for c in contexts])
        data, usage = self._generate(prompts.ANSWER_SYSTEM, user, prompts.ANSWER_SCHEMA)
        return structured.answer_result(data, self.model, usage)

    def _generate(self, system: str, user: str, schema: dict) -> tuple[Any, dict]:
        body = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": {
                "temperature": 0.3,
                "responseMimeType": "application/json",
                "responseJsonSchema": schema,
            },
        }
        payload = _post(self._client, f"models/{self.model}:generateContent", body)
        if (payload.get("promptFeedback") or {}).get("blockReason"):
            raise ProviderRejectedError("permintaan diblokir filter keamanan provider")
        try:
            candidate = payload["candidates"][0]
            finish = candidate.get("finishReason", "STOP")
            if finish == "MAX_TOKENS":
                raise ProviderInvalidOutputError("output terpotong")
            if finish not in ("STOP", "FINISH_REASON_UNSPECIFIED"):
                raise ProviderRejectedError(f"output dihentikan provider ({finish})")
            # Bagian "thought" (ringkasan penalaran) bukan bagian jawaban.
            text = "".join(p.get("text", "") for p in candidate["content"]["parts"] if not p.get("thought"))
            usage = payload.get("usageMetadata") or {}
            return json.loads(text), {
                "input_units": usage.get("promptTokenCount"),
                "output_units": usage.get("candidatesTokenCount"),
            }
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise ProviderInvalidOutputError("respons provider tidak dapat dibaca") from exc


def l2_normalize(vector: list[float]) -> list[float]:
    norm = math.sqrt(sum(v * v for v in vector))
    if norm == 0:
        raise ProviderInvalidOutputError("embedding nol")
    return [v / norm for v in vector]


class GeminiEmbedder:
    """Embedding teks. gemini-embedding-001 perlu dinormalisasi manual untuk dimensi selain 3072."""

    def __init__(self, api_key: str, model: str, dim: int, batch_size: int, base_url: str,
                 timeout_seconds: float, transport: httpx2.BaseTransport | None = None) -> None:
        self.model = model
        self.dim = dim
        self.batch_size = batch_size
        self._client = _client(base_url, api_key, timeout_seconds, transport)

    def close(self) -> None:
        self._client.close()

    def embed_documents(self, texts: list[str], title: str | None = None) -> list[list[float]]:
        vectors: list[list[float]] = []
        for start in range(0, len(texts), self.batch_size):
            vectors += self._batch(texts[start:start + self.batch_size], "RETRIEVAL_DOCUMENT", title)
        return vectors

    def embed_query(self, text: str) -> list[float]:
        return self._batch([text], "RETRIEVAL_QUERY", None)[0]

    def _batch(self, texts: list[str], task_type: str, title: str | None) -> list[list[float]]:
        config: dict[str, Any] = {"taskType": task_type, "outputDimensionality": self.dim}
        if title and task_type == "RETRIEVAL_DOCUMENT":
            config["title"] = title
        # Konfigurasi dikirim dalam format baru (embedContentConfig) dan lama (tingkat atas, masih diterima):
        # batchEmbedContents terbukti bisa mengabaikan salah satunya sehingga membalas 3072 dimensi.
        body = {
            "requests": [
                {"model": f"models/{self.model}", "content": {"parts": [{"text": t}]}, "embedContentConfig": config,
                 **config}
                for t in texts
            ]
        }
        payload = _post(self._client, f"models/{self.model}:batchEmbedContents", body)
        try:
            embeddings = [e["values"] for e in payload["embeddings"]]
        except (KeyError, TypeError) as exc:
            raise ProviderInvalidOutputError("respons embedding tidak dapat dibaca") from exc
        if len(embeddings) != len(texts) or any(len(v) < self.dim for v in embeddings):
            raise ProviderInvalidOutputError("jumlah atau dimensi embedding tidak sesuai")
        # Model Matryoshka: awalan sepanjang dim tetap valid setelah dinormalisasi ulang.
        return [l2_normalize([float(x) for x in v[:self.dim]]) for v in embeddings]
