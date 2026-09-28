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
    # API kompatibel OpenAI (default: Groq free plan). Juga cocok untuk Gemini OpenAI-compat, dsb.
    OPENAI_COMPATIBLE = "openai_compatible"


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

    # ---------- AI (fase 3) ----------
    ai_base_url: str = "https://api.groq.com/openai/v1"
    ai_model: str = "openai/gpt-oss-120b"
    ai_api_key: SecretStr | None = None
    # Ditampilkan ke pengguna sebelum materi dikirim; wajib jujur sesuai kebijakan provider yang dipakai.
    ai_provider_label: str = "Groq"
    ai_data_notice: str = (
        "Materi yang kamu pilih dikirim ke Groq untuk diproses. Menurut kebijakan Groq, data API "
        "tidak dipakai untuk melatih model dan tidak disimpan secara default, kecuali sementara "
        "(maks. 30 hari) untuk investigasi gangguan atau penyalahgunaan."
    )
    ai_request_timeout_seconds: float = 60.0
    # Batas agar tetap dalam free tier (Groq free: 30 RPM, 1.000 RPD, 8K TPM per model).
    ai_user_daily_limit: int = 10
    ai_global_daily_limit: int = 800
    ai_max_input_chars: int = 12_000
    ai_min_interval_seconds: float = 8.0
    ai_max_attempts: int = 3
    ai_job_lease_seconds: int = 180
    quiz_min_questions: int = 3
    quiz_max_questions: int = 10

    @model_validator(mode="after")
    def _validate(self) -> "Settings":
        if self.ai_provider is AIProviderKind.FAKE and self.app_env is AppEnv.PRODUCTION:
            raise ValueError("STUDYFLOW_AI_PROVIDER=fake tidak diizinkan saat STUDYFLOW_APP_ENV=production")
        if self.ai_provider is AIProviderKind.OPENAI_COMPATIBLE and (
            self.ai_api_key is None or not self.ai_api_key.get_secret_value().strip()
        ):
            # Tanpa key, fitur AI dimatikan (503) alih-alih gagal saat dipakai.
            self.ai_provider = AIProviderKind.NONE
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
