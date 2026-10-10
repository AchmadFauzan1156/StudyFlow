"""Membangun Worker dari Settings; dipakai proses worker terpisah dan mode tertanam di API."""

import logging

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.core.config import Settings
from app.documents.storage import StorageError, get_storage
from app.providers.base import ProviderUnavailableError
from app.providers.factory import get_embedder, get_provider
from app.worker.runner import Worker

logger = logging.getLogger("studyflow.worker")


def build_worker(settings: Settings) -> Worker | None:
    """Mengembalikan None (dan mencatat penyebabnya) jika worker tidak bisa berjalan."""
    if not settings.database_url:
        logger.error("STUDYFLOW_DATABASE_URL belum diset")
        return None
    try:
        provider = get_provider(settings)
    except ProviderUnavailableError:
        provider = None
        logger.warning("Provider AI generatif tidak dikonfigurasi: job rangkuman/kuis/tanya materi ditunda.")
    try:
        embedder = get_embedder(settings)
    except ProviderUnavailableError:
        embedder = None
        logger.warning("Embedding tidak dikonfigurasi: job indexing dokumen dan tanya materi ditunda.")
    try:
        storage = get_storage(settings)
    except StorageError as exc:
        logger.error("%s", exc)
        return None
    factory = sessionmaker(bind=create_engine(settings.database_url, pool_pre_ping=True), expire_on_commit=False)
    return Worker(factory, settings, provider, embedder, storage)
