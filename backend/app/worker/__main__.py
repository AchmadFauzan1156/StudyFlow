import logging
import sys

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.core.config import get_settings
from app.documents.storage import StorageError, get_storage
from app.providers.base import ProviderUnavailableError
from app.providers.factory import get_embedder, get_provider
from app.worker.runner import Worker

logger = logging.getLogger("studyflow.worker")


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    settings = get_settings()
    if not settings.database_url:
        logger.error("STUDYFLOW_DATABASE_URL belum diset")
        return 1
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
        return 1
    factory = sessionmaker(bind=create_engine(settings.database_url, pool_pre_ping=True), expire_on_commit=False)
    try:
        Worker(factory, settings, provider, embedder, storage).run_forever()
    except KeyboardInterrupt:
        logger.info("Worker dihentikan")
    return 0


if __name__ == "__main__":
    sys.exit(main())
