from app.core.config import AIProviderKind, Settings
from app.providers.base import AIProvider, ProviderUnavailableError
from app.providers.fake import FakeProvider


def provider_mode(settings: Settings) -> str:
    match settings.ai_provider:
        case AIProviderKind.FAKE:
            return "demo"
        case AIProviderKind.NONE:
            return "unconfigured"


def get_provider(settings: Settings) -> AIProvider:
    match settings.ai_provider:
        case AIProviderKind.FAKE:
            return FakeProvider(settings.app_env)
        case AIProviderKind.NONE:
            raise ProviderUnavailableError("Fitur AI belum dikonfigurasi di server ini")
