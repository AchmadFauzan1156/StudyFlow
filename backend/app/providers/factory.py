from typing import Protocol

from app.core.config import GEMINI_BASE_URL, AIProviderKind, EmbeddingProviderKind, Settings
from app.providers.base import AIProvider, ProviderUnavailableError
from app.providers.fake import FakeEmbedder, FakeProvider
from app.providers.gemini import GeminiEmbedder, GeminiProvider
from app.providers.openai_compatible import OpenAICompatibleProvider


class Embedder(Protocol):
    model: str
    dim: int

    def embed_documents(self, texts: list[str], title: str | None = None) -> list[list[float]]: ...

    def embed_query(self, text: str) -> list[float]: ...


def provider_mode(settings: Settings) -> str:
    match settings.ai_provider:
        case AIProviderKind.FAKE:
            return "demo"
        case AIProviderKind.GEMINI | AIProviderKind.OPENAI_COMPATIBLE:
            return "live"
        case AIProviderKind.NONE:
            return "unconfigured"


def get_provider(settings: Settings) -> AIProvider:
    match settings.ai_provider:
        case AIProviderKind.FAKE:
            return FakeProvider(settings.app_env)
        case AIProviderKind.GEMINI:
            return GeminiProvider(
                api_key=settings.ai_api_key.get_secret_value(),
                model=settings.effective_ai_model,
                base_url=settings.effective_ai_base_url,
                timeout_seconds=settings.ai_request_timeout_seconds,
            )
        case AIProviderKind.OPENAI_COMPATIBLE:
            return OpenAICompatibleProvider(
                base_url=settings.effective_ai_base_url,
                api_key=settings.ai_api_key.get_secret_value(),
                model=settings.effective_ai_model,
                timeout_seconds=settings.ai_request_timeout_seconds,
            )
        case AIProviderKind.NONE:
            raise ProviderUnavailableError("Fitur AI belum dikonfigurasi di server ini")


def get_embedder(settings: Settings) -> Embedder:
    match settings.embedding_provider:
        case EmbeddingProviderKind.FAKE:
            return FakeEmbedder(settings.app_env, settings.embedding_dim)
        case EmbeddingProviderKind.GEMINI:
            return GeminiEmbedder(
                api_key=settings.effective_embedding_key.get_secret_value(),
                model=settings.embedding_model,
                dim=settings.embedding_dim,
                batch_size=settings.embedding_batch_size,
                base_url=GEMINI_BASE_URL,
                timeout_seconds=settings.ai_request_timeout_seconds,
            )
        case EmbeddingProviderKind.NONE:
            raise ProviderUnavailableError("Embedding belum dikonfigurasi di server ini")
