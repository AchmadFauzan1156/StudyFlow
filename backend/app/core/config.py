from enum import StrEnum
from functools import lru_cache

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AppEnv(StrEnum):
    DEVELOPMENT = "development"
    TEST = "test"
    PRODUCTION = "production"


class AIProviderKind(StrEnum):
    # Belum ada provider: fitur AI mengembalikan 503, fitur lain tetap berjalan.
    NONE = "none"
    # Provider palsu deterministik; hanya boleh di development/test dan selalu berlabel demo.
    FAKE = "fake"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="STUDYFLOW_", extra="ignore")

    app_env: AppEnv = AppEnv.DEVELOPMENT
    ai_provider: AIProviderKind = AIProviderKind.NONE
    cors_origins: list[str] = []

    @model_validator(mode="after")
    def _fake_provider_only_outside_production(self) -> "Settings":
        if self.ai_provider is AIProviderKind.FAKE and self.app_env is AppEnv.PRODUCTION:
            raise ValueError("STUDYFLOW_AI_PROVIDER=fake tidak diizinkan saat STUDYFLOW_APP_ENV=production")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
