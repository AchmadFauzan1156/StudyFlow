from app.core.config import AIProviderKind, Settings
from app.providers.base import AIProvider, ProviderUnavailableError
from app.providers.fake import FakeProvider
from app.providers.openai_compatible import OpenAICompatibleProvider


def provider_mode(settings: Settings) -> str:
    match settings.ai_provider:
        case AIProviderKind.FAKE:
            return "demo"
        case AIProviderKind.OPENAI_COMPATIBLE:
            return "live"
        case AIProviderKind.NONE:
            return "unconfigured"


def get_provider(settings: Settings) -> AIProvider:
    match settings.ai_provider:
        case AIProviderKind.FAKE:
            return FakeProvider(settings.app_env)
        case AIProviderKind.OPENAI_COMPATIBLE:
            return OpenAICompatibleProvider(
                base_url=settings.ai_base_url,
                api_key=settings.ai_api_key.get_secret_value(),
                model=settings.ai_model,
                timeout_seconds=settings.ai_request_timeout_seconds,
            )
        case AIProviderKind.NONE:
            raise ProviderUnavailableError("Fitur AI belum dikonfigurasi di server ini")
