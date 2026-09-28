from enum import StrEnum
from functools import lru_cache

from pydantic import SecretStr, model_validator
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

    database_url: str | None = None

    # URL proyek Supabase, mis. https://abcd.supabase.co. Dipakai untuk issuer dan JWKS.
    supabase_url: str | None = None
    jwt_audience: str = "authenticated"
    # Hanya untuk tes lokal atau proyek yang masih memakai legacy HS256 secret.
    jwt_hs256_secret: SecretStr | None = None

    sync_push_max_mutations: int = 100
    sync_pull_max_limit: int = 500

    @model_validator(mode="after")
    def _validate(self) -> "Settings":
        if self.ai_provider is AIProviderKind.FAKE and self.app_env is AppEnv.PRODUCTION:
            raise ValueError("STUDYFLOW_AI_PROVIDER=fake tidak diizinkan saat STUDYFLOW_APP_ENV=production")
        if self.supabase_url:
            self.supabase_url = self.supabase_url.rstrip("/")
        return self

    @property
    def jwt_issuer(self) -> str | None:
        return f"{self.supabase_url}/auth/v1" if self.supabase_url else None

    @property
    def jwks_url(self) -> str | None:
        return f"{self.supabase_url}/auth/v1/.well-known/jwks.json" if self.supabase_url else None


@lru_cache
def get_settings() -> Settings:
    return Settings()
