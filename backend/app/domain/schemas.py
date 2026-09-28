"""Schema structured output AI (lihat spesifikasi bagian 7)."""

from pydantic import BaseModel, Field


class SourceRef(BaseModel):
    """Rujukan ke bagian materi. Judul/halaman dipetakan backend dari metadata, bukan dari model."""

    source_id: str
    chunk_id: str | None = None


class QuizOption(BaseModel):
    id: str = Field(min_length=1, max_length=16)
    text: str = Field(min_length=1, max_length=500)


class QuizQuestion(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    topic_id: str = Field(min_length=1, max_length=64)
    question: str = Field(min_length=1, max_length=1000)
    options: list[QuizOption]
    correct_option_id: str
    explanation: str = Field(max_length=2000)
    source_refs: list[SourceRef] = []


class QuizDraft(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    questions: list[QuizQuestion]


class SummaryDraft(BaseModel):
    content: str = Field(min_length=1, max_length=20_000)
    source_refs: list[SourceRef] = []


class GenerationResult[T](BaseModel):
    """Hasil provider beserta metadata; [is_demo] wajib ditampilkan ke pengguna jika true."""

    output: T
    model_id: str
    prompt_version: str
    is_demo: bool
    input_units: int | None = None
    output_units: int | None = None


class AnswerDraft(BaseModel):
    """Jawaban tanya-materi. `source_ids` wajib berasal dari potongan hasil retrieval (divalidasi server)."""

    answerable: bool
    answer: str = Field(max_length=6_000)
    source_ids: list[str] = []
