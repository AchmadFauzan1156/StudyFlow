"""Membuat PDF kecil untuk tes tanpa dependency tambahan (teks ASCII, font Helvetica)."""

import io
import textwrap

from pypdf import PdfReader, PdfWriter


def _escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def make_pdf(pages: list[str]) -> bytes:
    objects: list[bytes] = []

    def add(body: bytes) -> int:
        objects.append(body)
        return len(objects)

    catalog_id = add(b"")  # diisi setelah pages
    pages_id = add(b"")
    font_id = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    page_ids = []
    for text in pages:
        lines = []
        for paragraph in text.split("\n"):
            lines += textwrap.wrap(paragraph, 90) or [""]
        ops = "BT /F1 11 Tf 14 TL 40 800 Td " + " ".join(f"({_escape(line)}) Tj T*" for line in lines) + " ET"
        stream = ops.encode("latin-1")
        content_id = add(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
        page_ids.append(add(
            b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 %d 0 R >> >> "
            b"/Contents %d 0 R >>" % (pages_id, font_id, content_id)
        ))
    kids = b" ".join(b"%d 0 R" % pid for pid in page_ids)
    objects[pages_id - 1] = b"<< /Type /Pages /Kids [%s] /Count %d >>" % (kids, len(page_ids))
    objects[catalog_id - 1] = b"<< /Type /Catalog /Pages %d 0 R >>" % pages_id

    out = io.BytesIO()
    out.write(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(b"%d 0 obj\n" % number + body + b"\nendobj\n")
    xref = out.tell()
    out.write(b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1))
    for offset in offsets:
        out.write(b"%010d 00000 n \n" % offset)
    out.write(b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, catalog_id, xref))
    return out.getvalue()


def encrypt_pdf(data: bytes, password: str = "rahasia") -> bytes:
    writer = PdfWriter()
    writer.append(PdfReader(io.BytesIO(data)))
    writer.encrypt(user_password=password, owner_password=password + "-owner")
    out = io.BytesIO()
    writer.write(out)
    return out.getvalue()


def blank_pdf(page_count: int) -> bytes:
    """Menyerupai PDF hasil scan: halaman tanpa lapisan teks."""
    writer = PdfWriter()
    for _ in range(page_count):
        writer.add_blank_page(width=595, height=842)
    out = io.BytesIO()
    writer.write(out)
    return out.getvalue()
