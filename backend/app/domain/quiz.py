"""Validasi struktural kuis. Lolos validasi != benar secara semantik; tetap perlu evaluasi manual."""

from app.domain.schemas import QuizDraft

MIN_OPTIONS = 2
MAX_OPTIONS = 6


class QuizValidationError(ValueError):
    def __init__(self, problems: list[str]) -> None:
        super().__init__("; ".join(problems))
        self.problems = problems


def validate_quiz(draft: QuizDraft, expected_count: int, allowed_source_ids: set[str]) -> None:
    problems: list[str] = []
    if len(draft.questions) != expected_count:
        problems.append(f"jumlah soal {len(draft.questions)}, diharapkan {expected_count}")

    seen_question_ids: set[str] = set()
    for q in draft.questions:
        label = f"soal {q.id}"
        if q.id in seen_question_ids:
            problems.append(f"{label}: id soal duplikat")
        seen_question_ids.add(q.id)

        if not MIN_OPTIONS <= len(q.options) <= MAX_OPTIONS:
            problems.append(f"{label}: jumlah pilihan harus {MIN_OPTIONS}-{MAX_OPTIONS}")

        option_ids = [o.id for o in q.options]
        if len(set(option_ids)) != len(option_ids):
            problems.append(f"{label}: id pilihan duplikat")

        normalized = [o.text.strip().casefold() for o in q.options]
        if len(set(normalized)) != len(normalized):
            problems.append(f"{label}: teks pilihan duplikat")

        # Tepat satu jawaban benar: id jawaban harus ada tepat satu kali di pilihan.
        if option_ids.count(q.correct_option_id) != 1:
            problems.append(f"{label}: correct_option_id tidak cocok dengan tepat satu pilihan")

        for ref in q.source_refs:
            if ref.source_id not in allowed_source_ids:
                problems.append(f"{label}: sumber {ref.source_id} bukan bagian dari materi yang dipilih")

    if problems:
        raise QuizValidationError(problems)
