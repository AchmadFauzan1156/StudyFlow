"""Fase 3: antrean job AI, rangkuman, kuis, attempt, kuota, dan pencatatan pemakaian.

Revision ID: 0002
Revises: 0001
Create Date: 2026-09-28
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None

UUID = postgresql.UUID(as_uuid=True)
TS = sa.DateTime(timezone=True)

TABLES = (
    "ai_jobs", "summaries", "quizzes", "questions", "question_keys", "attempts", "answers",
    "question_reports", "ai_usage_daily", "usage_records",
)


def upgrade() -> None:
    op.create_table(
        "ai_jobs",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("type", sa.String(16), nullable=False),
        sa.Column("source_type", sa.String(16), nullable=False),
        sa.Column("source_id", UUID, nullable=False),
        sa.Column("source_version", sa.Integer, nullable=False),
        sa.Column("params", postgresql.JSONB, nullable=False),
        sa.Column("idempotency_key", sa.String(128), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("run_after", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("lease_until", TS),
        sa.Column("heartbeat_at", TS),
        sa.Column("quota_day", sa.Date, nullable=False),
        sa.Column("error_code", sa.String(64)),
        sa.Column("result_id", UUID),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", TS, nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("user_id", "idempotency_key", name="uq_ai_jobs_idempotency"),
        sa.CheckConstraint("type IN ('summary', 'quiz')", name="ck_ai_jobs_type"),
        sa.CheckConstraint("status IN ('queued', 'running', 'succeeded', 'failed')", name="ck_ai_jobs_status"),
    )
    # Indeks parsial untuk klaim job oleh worker.
    op.create_index(
        "ix_ai_jobs_claimable", "ai_jobs", ["run_after"],
        postgresql_where=sa.text("status IN ('queued', 'running')"),
    )

    for table in ("summaries", "quizzes"):
        extra = (
            [sa.Column("content", sa.Text, nullable=False)] if table == "summaries"
            else [sa.Column("title", sa.String(200), nullable=False)]
        )
        op.create_table(
            table,
            sa.Column("id", UUID, primary_key=True),
            sa.Column("user_id", UUID, nullable=False, index=True),
            sa.Column("job_id", UUID, sa.ForeignKey("ai_jobs.id"), nullable=False, unique=True),
            sa.Column("source_type", sa.String(16), nullable=False),
            sa.Column("source_id", UUID, nullable=False, index=True),
            sa.Column("source_version", sa.Integer, nullable=False),
            *extra,
            sa.Column("model_id", sa.String(120), nullable=False),
            sa.Column("prompt_version", sa.String(40), nullable=False),
            sa.Column("is_demo", sa.Boolean, nullable=False),
            sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        )

    op.create_table(
        "questions",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("quiz_id", UUID, sa.ForeignKey("quizzes.id"), nullable=False, index=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("position", sa.Integer, nullable=False),
        sa.Column("topic_id", sa.String(120), nullable=False),
        sa.Column("question", sa.Text, nullable=False),
        sa.Column("options", postgresql.JSONB, nullable=False),
    )
    op.create_table(
        "question_keys",
        sa.Column("question_id", UUID, sa.ForeignKey("questions.id"), primary_key=True),
        sa.Column("correct_option_id", sa.String(16), nullable=False),
        sa.Column("explanation", sa.Text, nullable=False),
        sa.Column("source_refs", postgresql.JSONB, nullable=False),
    )
    op.create_table(
        "attempts",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("quiz_id", UUID, sa.ForeignKey("quizzes.id"), nullable=False, index=True),
        sa.Column("idempotency_key", sa.String(128), nullable=False),
        sa.Column("score", sa.Integer, nullable=False),
        sa.Column("total", sa.Integer, nullable=False),
        sa.Column("submitted_at", TS, nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("user_id", "idempotency_key", name="uq_attempts_idempotency"),
    )
    op.create_table(
        "answers",
        sa.Column("attempt_id", UUID, sa.ForeignKey("attempts.id"), primary_key=True),
        sa.Column("question_id", UUID, sa.ForeignKey("questions.id"), primary_key=True),
        sa.Column("selected_option_id", sa.String(16)),
        sa.Column("correct", sa.Boolean, nullable=False),
    )
    op.create_table(
        "question_reports",
        sa.Column("question_id", UUID, sa.ForeignKey("questions.id"), primary_key=True),
        sa.Column("user_id", UUID, primary_key=True),
        sa.Column("reason", sa.String(500)),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
    )
    op.create_table(
        "ai_usage_daily",
        sa.Column("scope", sa.String(64), primary_key=True),
        sa.Column("day", sa.Date, primary_key=True),
        sa.Column("count", sa.Integer, nullable=False),
        sa.CheckConstraint("count >= 0", name="ck_ai_usage_daily_count"),
    )
    op.create_table(
        "usage_records",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("job_id", UUID, nullable=False, index=True),
        sa.Column("operation", sa.String(16), nullable=False),
        sa.Column("model_id", sa.String(120), nullable=False),
        sa.Column("input_units", sa.Integer),
        sa.Column("output_units", sa.Integer),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
    )

    for table in TABLES:
        op.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
        op.execute(
            f"""
            DO $$
            BEGIN
              IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
                EXECUTE 'REVOKE ALL ON TABLE {table} FROM anon';
              END IF;
              IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
                EXECUTE 'REVOKE ALL ON TABLE {table} FROM authenticated';
              END IF;
            END $$;
            """
        )


def downgrade() -> None:
    for table in (
        "usage_records", "ai_usage_daily", "question_reports", "answers", "attempts", "question_keys",
        "questions", "quizzes", "summaries", "ai_jobs",
    ):
        op.drop_table(table)
