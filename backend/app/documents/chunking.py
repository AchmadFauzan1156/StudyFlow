"""Pemecahan teks per halaman menjadi potongan berukuran kata dengan overlap.

Ukuran default ~450 kata (≈600 token) dengan overlap ~70 kata (≈90 token): parameter percobaan,
bukan angka universal. Nomor halaman = urutan fisik PDF mulai 1, bukan nomor cetak.
"""

import re
from dataclasses import dataclass

_TOKEN = re.compile(r"\S+")


@dataclass(frozen=True)
class Chunk:
    index: int
    text: str
    page_start: int
    page_end: int
    # Offset karakter awal/akhir di teks halaman awal/akhir (untuk menyorot cuplikan).
    char_start: int
    char_end: int


def chunk_pages(pages: list[str], chunk_words: int, overlap_words: int) -> list[Chunk]:
    if chunk_words <= 0 or not 0 <= overlap_words < chunk_words:
        raise ValueError("ukuran chunk/overlap tidak valid")
    words: list[tuple[str, int, int, int]] = []  # (kata, halaman, offset awal, offset akhir)
    for page_no, text in enumerate(pages, start=1):
        for match in _TOKEN.finditer(text):
            words.append((match.group(), page_no, match.start(), match.end()))

    chunks: list[Chunk] = []
    step = chunk_words - overlap_words
    start = 0
    while start < len(words):
        window = words[start:start + chunk_words]
        chunks.append(
            Chunk(
                index=len(chunks),
                text=" ".join(w[0] for w in window),
                page_start=window[0][1],
                page_end=window[-1][1],
                char_start=window[0][2],
                char_end=window[-1][3],
            )
        )
        if start + chunk_words >= len(words):
            break
        start += step
    return chunks
