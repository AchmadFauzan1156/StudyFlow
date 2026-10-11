"""Validasi dan ekstraksi PDF berbasis teks (pypdf). OCR untuk PDF scan berada di luar scope."""

import io
import multiprocessing as mp
import queue
import re
from dataclasses import dataclass, field

from pypdf import PdfReader
from pypdf.errors import PdfReadError

PDF_SIGNATURE = b"%PDF-"
# Halaman dengan teks lebih sedikit dari ini dianggap tidak berteks (kemungkinan hasil scan/gambar).
MIN_PAGE_CHARS = 20
# Dokumen dianggap tidak layak jika total teks di bawah ini.
MIN_DOCUMENT_CHARS = 200


class PdfRejected(Exception):
    def __init__(self, code: str, message: str, status: int = 422) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


@dataclass
class PdfInfo:
    page_count: int


@dataclass
class ExtractedPdf:
    pages: list[str]
    warnings: list[str] = field(default_factory=list)

    @property
    def total_chars(self) -> int:
        return sum(len(p) for p in self.pages)


def inspect_pdf(data: bytes, max_bytes: int, max_pages: int) -> PdfInfo:
    """Pemeriksaan cepat saat unggah: ukuran, signature (bukan ekstensi), enkripsi, jumlah halaman."""
    if len(data) > max_bytes:
        raise PdfRejected("file_too_large", f"Berkas melebihi {max_bytes // (1024 * 1024)} MB", 413)
    if not data.startswith(PDF_SIGNATURE):
        raise PdfRejected("not_pdf", "Berkas bukan PDF")
    try:
        reader = PdfReader(io.BytesIO(data))
        # PDF dengan password pembuka (atau enkripsi yang tidak bisa dibuka) ditolak.
        if reader.is_encrypted and reader.decrypt("") == 0:
            raise PdfRejected("pdf_encrypted", "PDF terkunci password dan tidak bisa dibaca")
        count = len(reader.pages)
    except PdfRejected:
        raise
    except (PdfReadError, ValueError, KeyError, TypeError, OSError) as exc:
        raise PdfRejected("pdf_invalid", "PDF rusak atau tidak dapat dibaca") from exc
    if count == 0:
        raise PdfRejected("pdf_invalid", "PDF tidak memiliki halaman")
    if count > max_pages:
        raise PdfRejected("too_many_pages", f"PDF memiliki {count} halaman (maks. {max_pages})", 413)
    return PdfInfo(page_count=count)


def _normalize(text: str) -> str:
    text = text.replace("\x00", "")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _extract_all(data: bytes) -> list[str]:
    reader = PdfReader(io.BytesIO(data))
    if reader.is_encrypted:
        reader.decrypt("")
    return [_normalize(page.extract_text() or "") for page in reader.pages]


def _limit_memory(max_memory_mb: int | None) -> None:
    """Batasi memori proses ekstraksi (Linux/Unix) agar PDF berat gagal sendiri, bukan mematikan container API."""
    if not max_memory_mb:
        return
    try:
        import resource
    except ImportError:  # Windows (pengembangan lokal): tanpa batas.
        return
    limit = max_memory_mb * 1024 * 1024
    resource.setrlimit(resource.RLIMIT_DATA, (limit, limit))


def _worker(data: bytes, out: "mp.Queue", max_memory_mb: int | None = None) -> None:
    try:
        _limit_memory(max_memory_mb)
        out.put(("ok", _extract_all(data)))
    except BaseException as exc:  # noqa: BLE001 - termasuk MemoryError; dikirim balik ke proses induk
        out.put(("error", type(exc).__name__))


def _result_to_pages(status: str, payload: object) -> list[str]:
    if status == "ok":
        return payload  # type: ignore[return-value]
    if payload == "MemoryError":
        raise PdfRejected("pdf_too_heavy", "PDF terlalu berat untuk diproses. Pecah menjadi beberapa berkas yang lebih kecil.")
    raise PdfRejected("pdf_invalid", "PDF rusak atau tidak dapat dibaca")


def extract_pages(data: bytes, timeout_seconds: float, max_memory_mb: int | None = None) -> ExtractedPdf:
    """Ekstraksi per halaman di proses terpisah dengan batas waktu dan memori (PDF berat tidak menggantung worker)."""
    ctx = mp.get_context("spawn")
    out: mp.Queue = ctx.Queue()
    proc = ctx.Process(target=_worker, args=(data, out, max_memory_mb), daemon=True)
    proc.start()
    try:
        status, payload = out.get(timeout=timeout_seconds)
    except queue.Empty as exc:
        raise PdfRejected("extract_timeout", "Ekstraksi teks melebihi batas waktu") from exc
    finally:
        proc.join(timeout=5)
        if proc.is_alive():
            proc.kill()
    return assess(_result_to_pages(status, payload))


def assess(pages: list[str]) -> ExtractedPdf:
    result = ExtractedPdf(pages=pages)
    if result.total_chars < MIN_DOCUMENT_CHARS:
        raise PdfRejected("pdf_no_text",
                          "PDF tidak memiliki teks yang bisa dibaca (kemungkinan hasil scan). OCR belum didukung.")
    empty = [i + 1 for i, text in enumerate(pages) if len(text) < MIN_PAGE_CHARS]
    if empty:
        shown = ", ".join(map(str, empty[:10])) + (" …" if len(empty) > 10 else "")
        result.warnings.append(f"Halaman tanpa teks terbaca (mungkin gambar/scan): {shown}")
    result.warnings.append("Rumus, tabel, dan tata letak kolom mungkin tidak terbaca sempurna. Cek pratinjau teks.")
    return result
