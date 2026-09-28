"""Fase 5: jadwal ulang flashcard (Leitner).

Revision ID: 0004
Revises: 0003
Create Date: 2026-09-29
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "flashcard_reviews",
        sa.Column("user_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("question_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("questions.id"), primary_key=True),
        sa.Column("box", sa.Integer, nullable=False),
        sa.Column("due_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("reviewed_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("box BETWEEN 1 AND 5", name="ck_flashcard_reviews_box"),
    )
    op.execute("ALTER TABLE flashcard_reviews ENABLE ROW LEVEL SECURITY")
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
            EXECUTE 'REVOKE ALL ON TABLE flashcard_reviews FROM anon';
          END IF;
          IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
            EXECUTE 'REVOKE ALL ON TABLE flashcard_reviews FROM authenticated';
          END IF;
        END $$;
        """
    )


def downgrade() -> None:
    op.drop_table("flashcard_reviews")
