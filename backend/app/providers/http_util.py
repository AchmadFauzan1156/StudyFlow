def retry_after(value: str | None) -> float | None:
    """Membaca header Retry-After (detik). Nilai tidak valid diabaikan."""
    try:
        return max(0.0, float(value)) if value else None
    except ValueError:
        return None
