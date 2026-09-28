import pytest
from pydantic import ValidationError

from app.core.config import AIProviderKind, AppEnv, Settings
from app.domain.quiz import validate_quiz
from app.providers.base import ProviderUnavailableError, SourceText
from app.providers.factory import get_provider
from app.providers.fake import FakeProvider

SOURCE = SourceText(
    source_id="note-1",
    title="RSA",
    text="RSA memakai dua bilangan prima p dan q. Modulus n = p * q. Kunci publik terdiri dari e dan n.",
)


def test_fake_provider_is_deterministic_and_labelled_demo():
    provider = FakeProvider(AppEnv.DEVELOPMENT)
    first = provider.generate_quiz(SOURCE, 4)
    second = provider.generate_quiz(SOURCE, 4)
    assert first == second
    assert first.is_demo
    assert first.output.title.startswith("[Demo]")
    assert provider.summarize(SOURCE).is_demo


def test_fake_quiz_passes_structural_validation():
    draft = FakeProvider(AppEnv.TEST).generate_quiz(SOURCE, 5).output
    validate_quiz(draft, expected_count=5, allowed_source_ids={"note-1"})
    # Jawaban benar tidak selalu berada di posisi yang sama.
    assert len({q.correct_option_id for q in draft.questions}) > 1


def test_fake_provider_refused_in_production():
    with pytest.raises(RuntimeError):
        FakeProvider(AppEnv.PRODUCTION)
    with pytest.raises(ValidationError):
        Settings(_env_file=None, app_env=AppEnv.PRODUCTION, ai_provider=AIProviderKind.FAKE)


def test_unconfigured_provider_is_unavailable():
    settings = Settings(_env_file=None, app_env=AppEnv.DEVELOPMENT, ai_provider=AIProviderKind.NONE)
    with pytest.raises(ProviderUnavailableError):
        get_provider(settings)
