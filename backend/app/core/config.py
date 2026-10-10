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
    # Google Gemini API (native generateContent). Default yang direkomendasikan.
    GEMINI = "gemini"
    # API Chat Completions kompatibel OpenAI (mis. Groq) sebagai alternatif.
    OPENAI_COMPATIBLE = "openai_compatible"


class EmbeddingProviderKind(StrEnum):
    NONE = "none"
    FAKE = "fake"
    GEMINI = "gemini"


GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

GEMINI_DATA_NOTICE = (
    "Materi yang kamu pilih dikirim ke Google Gemini API (layanan gratis). Pada layanan gratis, Google dapat "
    "memakai isi yang dikirim beserta hasilnya untuk meningkatkan produknya, dan peninjau manusia dapat "
    "membacanya. Jangan kirim informasi sensitif, rahasia, atau data pribadi. Layanan ini untuk pengguna "
    "berusia 18 tahun ke atas."
)
GROQ_DATA_NOTICE = (
    "Materi yang kamu pilih dikirim ke Groq untuk diproses. Menurut kebijakan Groq, data API tidak dipakai "
    "untuk melatih model dan tidak disimpan secara default, kecuali sementara (maks. 30 hari) untuk "
    "investigasi gangguan atau penyalahgunaan."
)

_PROVIDER_DEFAULTS = {
    AIProviderKind.GEMINI: (GEMINI_BASE_URL, "gemini-3.8-flash", "Google Gemini", GEMINI_DATA_NOTICE),
    AIProviderKind.OPENAI_COMPATIBLE: (
        "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "Groq", GROQ_DATA_NOTICE,
    ),
}


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="STUDYFLOW_", extra="ignore")

    app_env: AppEnv = AppEnv.DEVELOPMENT
    cors_origins: list[str] = []

    database_url: str | None = None

    # URL proyek Supabase, mis. https://abcd.supabase.co. Dipakai untuk issuer, JWKS, dan Storage.
    supabase_url: str | None = None
    jwt_audience: str = "authenticated"
    # Hanya untuk tes lokal atau proyek yang masih memakai legacy HS256 secret.
    jwt_hs256_secret: SecretStr | None = None

    sync_push_max_mutations: int = 100
    sync_pull_max_limit: int = 500

    # ---------- AI generatif ----------
    ai_provider: AIProviderKind = AIProviderKind.NONE
    ai_api_key: SecretStr | None = None
    # Kosong = default per provider (lihat _PROVIDER_DEFAULTS).
    ai_base_url: str | None = None
    ai_model: str | None = None
    ai_provider_label: str | None = None
    # Ditampilkan ke pengguna sebelum materi dikirim; wajib jujur sesuai kebijakan provider yang dipakai.
    ai_data_notice: str | None = None
    ai_request_timeout_seconds: float = 60.0
    # Batas agar tetap dalam free tier. Batas Gemini free tier per project terlihat di Google AI Studio.
    ai_user_daily_limit: int = 10
    ai_global_daily_limit: int = 200
    ai_max_input_chars: int = 12_000
    ai_min_interval_seconds: float = 6.0
    ai_max_attempts: int = 3
    ai_job_lease_seconds: int = 180
    # Jalankan worker sebagai thread di dalam proses API (hosting gratis dengan satu service).
    embedded_worker: bool = False
    quiz_min_questions: int = 3
    quiz_max_questions: int = 10

    # ---------- Embedding & RAG (fase 4) ----------
    embedding_provider: EmbeddingProviderKind = EmbeddingProviderKind.NONE
    # Kosong = memakai ai_api_key.
    embedding_api_key: SecretStr | None = None
    embedding_model: str = "gemini-embedding-001"
    # Harus sama dengan dimensi kolom vector di migrasi 0003; mengganti model/dimensi berarti reindex.
    embedding_dim: int = 768
    embedding_batch_size: int = 50
    rag_top_k: int = 6
    # Jarak kosinus maksimum agar potongan dianggap relevan (kalibrasi dengan fixture evaluasi).
    rag_max_distance: float = 0.55

    # ---------- Dokumen ----------
    # local = folder di disk (development); supabase = Supabase Storage bucket privat.
    storage_backend: str = "local"
    storage_local_dir: str = "./.storage"
    storage_bucket: str = "documents"
    # Secret/service-role key Supabase: hanya di backend, dipakai untuk Storage.
    supabase_service_key: SecretStr | None = None
    document_max_bytes: int = 10 * 1024 * 1024
    document_max_pages: int = 100
    document_extract_timeout_seconds: float = 60.0
    chunk_words: int = 450
    chunk_overlap_words: int = 70
    index_user_daily_limit: int = 20
    index_global_daily_limit: int = 200

    # ---------- Progres & latihan (fase 5) ----------
    # Ambang keputusan produk (bukan ukuran ilmiah): minimal N jawaban pada topik dan akurasi di bawah ambang.
    practice_min_answers: int = 5
    practice_accuracy_threshold: float = 0.6
    flashcard_batch: int = 20

    @model_validator(mode="after")
    def _validate(self) -> "Settings":
        if AIProviderKind.FAKE is self.ai_provider and self.app_env is AppEnv.PRODUCTION:
            raise ValueError("STUDYFLOW_AI_PROVIDER=fake tidak diizinkan saat STUDYFLOW_APP_ENV=production")
        if EmbeddingProviderKind.FAKE is self.embedding_provider and self.app_env is AppEnv.PRODUCTION:
            raise ValueError("STUDYFLOW_EMBEDDING_PROVIDER=fake tidak diizinkan saat production")
        if self.ai_provider in (AIProviderKind.GEMINI, AIProviderKind.OPENAI_COMPATIBLE) and not _has(self.ai_api_key):
            # Tanpa key, fitur AI dimatikan (503) alih-alih gagal saat dipakai.
            self.ai_provider = AIProviderKind.NONE
        if self.embedding_provider is EmbeddingProviderKind.GEMINI and not _has(self.effective_embedding_key):
            self.embedding_provider = EmbeddingProviderKind.NONE
        if self.supabase_url:
            self.supabase_url = self.supabase_url.rstrip("/")
        return self

    # ---------- Nilai efektif per provider ----------

    def _default(self, index: int) -> str | None:
        defaults = _PROVIDER_DEFAULTS.get(self.ai_provider)
        return defaults[index] if defaults else None

    @property
    def effective_ai_base_url(self) -> str | None:
        return self.ai_base_url or self._default(0)

    @property
    def effective_ai_model(self) -> str | None:
        return self.ai_model or self._default(1)

    @property
    def effective_ai_label(self) -> str | None:
        return self.ai_provider_label or self._default(2)

    @property
    def effective_data_notice(self) -> str | None:
        return self.ai_data_notice or self._default(3)

    @property
    def effective_embedding_key(self) -> SecretStr | None:
        return self.embedding_api_key if _has(self.embedding_api_key) else self.ai_api_key

    @property
    def jwt_issuer(self) -> str | None:
        return f"{self.supabase_url}/auth/v1" if self.supabase_url else None

    @property
    def jwks_url(self) -> str | None:
        return f"{self.supabase_url}/auth/v1/.well-known/jwks.json" if self.supabase_url else None


def _has(secret: SecretStr | None) -> bool:
    return secret is not None and bool(secret.get_secret_value().strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
