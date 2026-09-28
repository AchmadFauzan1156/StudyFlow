"""Fase 4: dokumen PDF, halaman, potongan ber-embedding (pgvector), dan tanya materi.

Revision ID: 0003
Revises: 0002
Create Date: 2026-09-28
"""

import sqlalchemy as sa
from alembic import op
from pgvector.sqlalchemy import Vector
from sqlalchemy.dialects import postgresql

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None

UUID = postgresql.UUID(as_uuid=True)
TS = sa.DateTime(timezone=True)
EMBEDDING_DIM = 768

TABLES = ("documents", "document_versions", "document_pages", "document_chunks", "chat_threads", "chat_messages")


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    op.create_table(
        "documents",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("course_id", UUID, nullable=False, index=True),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("idempotency_key", sa.String(128), nullable=False),
        sa.Column("active_version_id", UUID),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("deleted_at", TS),
        sa.ForeignKeyConstraint(["course_id", "user_id"], ["courses.id", "courses.user_id"],
                                name="fk_documents_course_owner"),
        sa.UniqueConstraint("user_id", "idempotency_key", name="uq_documents_idempotency"),
    )
    op.create_table(
        "document_versions",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("document_id", UUID, sa.ForeignKey("documents.id"), nullable=False, index=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("version_number", sa.Integer, nullable=False),
        sa.Column("storage_key", sa.String(300), nullable=False),
        sa.Column("content_hash", sa.String(64), nullable=False),
        sa.Column("size_bytes", sa.Integer, nullable=False),
        sa.Column("page_count", sa.Integer, nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("error_code", sa.String(64)),
        sa.Column("warnings", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("embedding_model", sa.String(120)),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("ready_at", TS),
        sa.CheckConstraint("status IN ('queued', 'processing', 'ready', 'failed', 'unsupported')",
                           name="ck_document_versions_status"),
    )
    op.create_table(
        "document_pages",
        sa.Column("version_id", UUID, sa.ForeignKey("document_versions.id"), primary_key=True),
        sa.Column("page_number", sa.Integer, primary_key=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("text", sa.Text, nullable=False),
    )
    # Pencarian awal memakai exact search (tanpa indeks ANN) sesuai spesifikasi; indeks HNSW ditambahkan
    # setelah volume data terukur membutuhkannya.
    op.create_table(
        "document_chunks",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("document_version_id", UUID, sa.ForeignKey("document_versions.id"), nullable=False, index=True),
        sa.Column("chunk_index", sa.Integer, nullable=False),
        sa.Column("text", sa.Text, nullable=False),
        sa.Column("page_start", sa.Integer, nullable=False),
        sa.Column("page_end", sa.Integer, nullable=False),
        sa.Column("char_start", sa.Integer, nullable=False),
        sa.Column("char_end", sa.Integer, nullable=False),
        sa.Column("embedding_model", sa.String(120), nullable=False),
        sa.Column("embedding", Vector(EMBEDDING_DIM), nullable=False),
    )
    op.create_table(
        "chat_threads",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("course_id", UUID, nullable=False, index=True),
        sa.Column("document_id", UUID),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.ForeignKeyConstraint(["course_id", "user_id"], ["courses.id", "courses.user_id"],
                                name="fk_chat_threads_course_owner"),
    )
    op.create_table(
        "chat_messages",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("thread_id", UUID, sa.ForeignKey("chat_threads.id"), nullable=False, index=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("role", sa.String(16), nullable=False),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column("source_refs", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("answerable", sa.Boolean),
        sa.Column("is_demo", sa.Boolean, nullable=False, server_default="false"),
        sa.Column("model_id", sa.String(120)),
        sa.Column("reply_to", UUID),
        sa.Column("job_id", UUID),
        sa.Column("idempotency_key", sa.String(128)),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("user_id", "idempotency_key", name="uq_chat_messages_idempotency"),
        sa.CheckConstraint("role IN ('user', 'assistant')", name="ck_chat_messages_role"),
    )

    # Tipe job baru untuk indexing, jawaban tanya-materi, dan pembersihan dokumen.
    op.drop_constraint("ck_ai_jobs_type", "ai_jobs", type_="check")
    op.create_check_constraint(
        "ck_ai_jobs_type", "ai_jobs",
        "type IN ('summary', 'quiz', 'index_document', 'chat_answer', 'cleanup_document')",
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
    op.drop_constraint("ck_ai_jobs_type", "ai_jobs", type_="check")
    op.create_check_constraint("ck_ai_jobs_type", "ai_jobs", "type IN ('summary', 'quiz')")
    for table in ("chat_messages", "chat_threads", "document_chunks", "document_pages", "document_versions",
                  "documents"):
        op.drop_table(table)
