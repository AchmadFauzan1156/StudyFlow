import pytest

from app.domain.quiz import QuizValidationError, validate_quiz
from app.domain.schemas import QuizDraft, QuizOption, QuizQuestion, SourceRef


def question(**overrides) -> QuizQuestion:
    data = {
        "id": "q1",
        "topic_id": "t1",
        "question": "Berapa 2 + 2?",
        "options": [QuizOption(id="a", text="3"), QuizOption(id="b", text="4")],
        "correct_option_id": "b",
        "explanation": "2 + 2 = 4",
        "source_refs": [SourceRef(source_id="note-1")],
    }
    data.update(overrides)
    return QuizQuestion(**data)


def problems_of(draft: QuizDraft, count: int = 1) -> list[str]:
    with pytest.raises(QuizValidationError) as exc:
        validate_quiz(draft, expected_count=count, allowed_source_ids={"note-1"})
    return exc.value.problems


def test_valid_quiz_passes():
    validate_quiz(QuizDraft(title="Kuis", questions=[question()]), 1, {"note-1"})


def test_wrong_question_count():
    assert any("jumlah soal" in p for p in problems_of(QuizDraft(title="K", questions=[question()]), 2))


def test_correct_answer_must_exist():
    draft = QuizDraft(title="K", questions=[question(correct_option_id="z")])
    assert any("correct_option_id" in p for p in problems_of(draft))


def test_duplicate_option_text_rejected():
    opts = [QuizOption(id="a", text="4"), QuizOption(id="b", text=" 4 ")]
    draft = QuizDraft(title="K", questions=[question(options=opts, correct_option_id="a")])
    assert any("teks pilihan duplikat" in p for p in problems_of(draft))


def test_duplicate_option_ids_rejected():
    opts = [QuizOption(id="a", text="3"), QuizOption(id="a", text="4")]
    draft = QuizDraft(title="K", questions=[question(options=opts, correct_option_id="a")])
    assert any("id pilihan duplikat" in p for p in problems_of(draft))


def test_unknown_source_rejected():
    draft = QuizDraft(title="K", questions=[question(source_refs=[SourceRef(source_id="other-user-note")])])
    assert any("bukan bagian" in p for p in problems_of(draft))
