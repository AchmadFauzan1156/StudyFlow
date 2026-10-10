import logging
import sys

from app.core.config import get_settings
from app.worker.bootstrap import build_worker

logger = logging.getLogger("studyflow.worker")


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    worker = build_worker(get_settings())
    if worker is None:
        return 1
    try:
        worker.run_forever()
    except KeyboardInterrupt:
        logger.info("Worker dihentikan")
    return 0


if __name__ == "__main__":
    sys.exit(main())
