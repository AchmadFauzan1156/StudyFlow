"""Penyimpanan berkas PDF. Akses ke berkas selalu lewat backend setelah pemeriksaan kepemilikan."""

from pathlib import Path
from typing import Protocol

import httpx2

from app.core.config import Settings


class StorageError(Exception):
    pass


class BlobStorage(Protocol):
    def put(self, key: str, data: bytes, content_type: str) -> None: ...

    def get(self, key: str) -> bytes: ...

    def delete(self, key: str) -> None:
        """Idempotent: menghapus berkas yang tidak ada bukan error."""


class LocalStorage:
    """Folder lokal untuk development dan tes. Key selalu berupa path relatif tanpa '..'."""

    def __init__(self, root: str) -> None:
        self.root = Path(root).resolve()

    def _path(self, key: str) -> Path:
        path = (self.root / key).resolve()
        if self.root not in path.parents:
            raise StorageError("key tidak valid")
        return path

    def put(self, key: str, data: bytes, content_type: str) -> None:
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def get(self, key: str) -> bytes:
        try:
            return self._path(key).read_bytes()
        except FileNotFoundError as exc:
            raise StorageError("berkas tidak ditemukan") from exc

    def delete(self, key: str) -> None:
        self._path(key).unlink(missing_ok=True)


class SupabaseStorage:
    """Supabase Storage (bucket privat) memakai secret/service-role key yang hanya ada di backend."""

    def __init__(self, supabase_url: str, service_key: str, bucket: str,
                 transport: httpx2.BaseTransport | None = None) -> None:
        self.bucket = bucket
        self._client = httpx2.Client(
            base_url=f"{supabase_url.rstrip('/')}/storage/v1/",
            headers={"Authorization": f"Bearer {service_key}", "apikey": service_key},
            timeout=httpx2.Timeout(60.0, connect=10.0),
            transport=transport,
        )

    def put(self, key: str, data: bytes, content_type: str) -> None:
        response = self._client.post(f"object/{self.bucket}/{key}", content=data,
                                     headers={"Content-Type": content_type, "x-upsert": "false"})
        if response.status_code >= 400:
            raise StorageError(f"gagal mengunggah ke storage ({response.status_code})")

    def get(self, key: str) -> bytes:
        response = self._client.get(f"object/{self.bucket}/{key}")
        if response.status_code >= 400:
            raise StorageError(f"gagal mengunduh dari storage ({response.status_code})")
        return response.content

    def delete(self, key: str) -> None:
        response = self._client.request("DELETE", f"object/{self.bucket}", json={"prefixes": [key]})
        if response.status_code >= 400 and response.status_code != 404:
            raise StorageError(f"gagal menghapus dari storage ({response.status_code})")


def get_storage(settings: Settings) -> BlobStorage:
    if settings.storage_backend == "supabase":
        if not settings.supabase_url or settings.supabase_service_key is None:
            raise StorageError("STUDYFLOW_SUPABASE_URL dan STUDYFLOW_SUPABASE_SERVICE_KEY wajib untuk storage supabase")
        return SupabaseStorage(settings.supabase_url, settings.supabase_service_key.get_secret_value(),
                               settings.storage_bucket)
    return LocalStorage(settings.storage_local_dir)
