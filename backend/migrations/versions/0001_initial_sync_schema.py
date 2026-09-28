"""Skema awal: profil, mata kuliah, catatan, tugas, dan tabel sinkronisasi dengan RLS default-deny.

Revision ID: 0001
Revises:
Create Date: 2026-09-28
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None

UUID = postgresql.UUID(as_uuid=True)
TS = sa.DateTime(timezone=True)

TABLES = ("profiles", "courses", "notes", "tasks", "sync_counters", "sync_changes", "sync_mutations")


def _synced_columns() -> list[sa.Column]:
    return [
        sa.Column("id", UUID, primary_key=True),
        sa.Column("user_id", UUID, nullable=False, index=True),
        sa.Column("version", sa.Integer, nullable=False),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("deleted_at", TS),
    ]


def upgrade() -> None:
    op.create_table(
        "profiles",
        sa.Column("user_id", UUID, primary_key=True),
        sa.Column("display_name", sa.String(120)),
        sa.Column("timezone", sa.String(64), nullable=False, server_default="Asia/Jakarta"),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
    )
    op.create_table(
        "courses",
        *_synced_columns(),
        sa.Column("name", sa.String(80), nullable=False),
        sa.Column("semester", sa.String(40)),
        sa.UniqueConstraint("id", "user_id", name="uq_courses_id_user"),
    )
    op.create_table(
        "notes",
        *_synced_columns(),
        sa.Column("course_id", UUID, nullable=False, index=True),
        sa.Column("title", sa.String(120), nullable=False),
        sa.Column("content", sa.Text, nullable=False),
        sa.ForeignKeyConstraint(
            ["course_id", "user_id"], ["courses.id", "courses.user_id"], name="fk_notes_course_owner"
        ),
    )
    op.create_table(
        "tasks",
        *_synced_columns(),
        sa.Column("course_id", UUID, nullable=False, index=True),
        sa.Column("title", sa.String(120), nullable=False),
        sa.Column("description", sa.Text, nullable=False),
        sa.Column("due_at", TS, nullable=False),
        sa.Column("time_zone_id", sa.String(64), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.CheckConstraint("status IN ('TODO', 'DONE')", name="ck_tasks_status"),
        sa.ForeignKeyConstraint(
            ["course_id", "user_id"], ["courses.id", "courses.user_id"], name="fk_tasks_course_owner"
        ),
    )
    op.create_table(
        "sync_counters",
        sa.Column("user_id", UUID, primary_key=True),
        sa.Column("last_seq", sa.BigInteger, nullable=False, server_default="0"),
    )
    op.create_table(
        "sync_changes",
        sa.Column("user_id", UUID, primary_key=True),
        sa.Column("entity_type", sa.String(16), primary_key=True),
        sa.Column("entity_id", UUID, primary_key=True),
        sa.Column("seq", sa.BigInteger, nullable=False),
        sa.UniqueConstraint("user_id", "seq", name="uq_sync_changes_user_seq"),
    )
    op.create_table(
        "sync_mutations",
        sa.Column("user_id", UUID, primary_key=True),
        sa.Column("mutation_id", UUID, primary_key=True),
        sa.Column("payload_hash", sa.String(64), nullable=False),
        sa.Column("result", postgresql.JSONB, nullable=False),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
    )

    # Default-deny: aplikasi mengakses data bisnis lewat backend, bukan langsung via PostgREST.
    # RLS aktif tanpa policy berarti role anon/authenticated tidak melihat baris apa pun; hak tabel
    # juga dicabut. Backend tetap wajib memfilter setiap query dengan user_id.
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
    for table in reversed(TABLES):
        op.drop_table(table)
