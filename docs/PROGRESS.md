# Progres

## Checkpoint 1 — Fase 0 dan 1 (28 September 2026)

### Environment yang diaudit

| Komponen | Temuan |
|---|---|
| OS | Windows 11 Pro 10.0.22621 |
| JDK | Oracle JDK 25.0.2 (PATH); Android Studio JBR 25.0.2. `JAVA_HOME` tidak diset — wrapper memakai `java` dari PATH. |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`: platform 34–37, build-tools 34–36.1, emulator, AVD `Medium_Phone_API_36.1` |
| Gradle | Tidak terpasang global; wrapper 9.6.0 dibuat (checksum dipin) |
| Python | 3.14.3; `uv` 0.12.19 dipasang via `pip install --user uv` |
| Lainnya | Git 2.45, GitHub CLI 2.92, Docker 29.1 |

`AGENTS.md` tidak ada di repository.

### Selesai

**Fase 0 — Bootstrap**
- Matriks versi diverifikasi dan dicatat di [DEPENDENCIES.md](DEPENDENCIES.md).
- Proyek KMP: `shared` (plugin Android-KMP library AGP 9), `androidApp`, `desktopApp`.
- Backend FastAPI: `GET /health`, format error seragam dengan `request_id` buatan server, konfigurasi `STUDYFLOW_*`, interface `AIProvider`, `FakeProvider` deterministik (hanya non-produksi, output berlabel `[Demo]`), schema kuis/rangkuman, validator struktur kuis.
- Dokumentasi: README, PLAN, DECISIONS, DEPENDENCIES, API, `.env.example`.

**Fase 1 — Lokal**
- Room 2.8.5 + SQLite bundled: tabel `courses`, `notes`, `tasks`, `local_outbox`, `app_settings` (skema diekspor ke `shared/schemas/`).
- CRUD mata kuliah, catatan, dan tugas; hapus = tombstone; hapus mata kuliah ikut men-tombstone catatan/tugasnya.
- Setiap mutasi menulis entri outbox (`mutation_id` unik, `base_version`, payload JSON) dalam transaksi yang sama.
- Deadline disimpan sebagai instant UTC + zona waktu; status Terlambat / Hari ini / Segera / Selesai; default tugas baru besok 23.59.
- UI Compose adaptif: bottom navigation (< 600 dp) dan sidebar (≥ 600 dp); Beranda (deadline terdekat), Mata Kuliah, detail dengan tab Catatan/Materi/Tugas/Belajar, editor catatan dan tugas (date/time picker), Progres, Pengaturan (tema Sistem/Terang/Gelap tersimpan, jumlah perubahan menunggu sinkronisasi).
- Tab Materi dan Belajar menampilkan penjelasan bahwa fitur itu memerlukan akun (tidak ada data demo yang dicampur).

### Perintah yang dijalankan dan hasilnya

| Perintah | Hasil |
|---|---|
| `gradlew :shared:jvmTest` | 20 tes lulus (8 repository Room nyata termasuk buka-tutup file DB, 8 deadline/zona waktu, 4 validasi) |
| `gradlew :androidApp:assembleDebug` | Berhasil, `androidApp-debug.apk` ± 19 MB |
| `gradlew :desktopApp:run` | Jendela terbuka; diuji manual dengan input otomatis: tambah mata kuliah, catatan, tugas (date picker), tutup, jalankan ulang → data tetap ada; tema terang; layout sempit menampilkan bottom navigation |
| APK di emulator API 36 (`Medium_Phone_API_36.1`) | Berjalan tanpa crash; tambah mata kuliah → `force-stop` → buka ulang → data tetap ada |
| `uv run pytest` (backend) | 14 tes lulus |
| `uv run ruff check .` | Bersih |
| `uv run uvicorn app.main:app` + `curl /health` | `200 {"status":"ok",…,"ai_mode":"unconfigured"}`; route tak dikenal → 404 dengan format error |

### Keterbatasan dan hambatan

- **iOS tidak dibangun/diuji** — butuh macOS/Xcode (di luar target awal).
- **Belum ada akun, sinkronisasi, dan AI nyata** — sesuai rencana fase 2–3. Outbox terisi tetapi belum dikirim.
- **Build di folder yang sedang diawasi sesi Claude desktop**: Gradle kadang gagal menghapus direktori `build/` (`IOException: Unable to delete directory`) karena watcher memegang handle direktori. Build yang sama dari salinan di luar folder tersebut berhasil konsisten. Tidak terkait kode proyek; jika muncul, tutup watcher/IDE yang mengawasi folder atau jalankan `.\gradlew.bat --stop` lalu hapus folder `build`.
- Uji UI dilakukan manual dengan input otomatis, belum ada tes UI Compose otomatis.
- Label bawaan date picker di Android mengikuti bahasa perangkat.
- Installer MSI/EXE belum dibuat (fase 6).

### Langkah berikutnya (fase 2)

Lihat [PLAN.md](PLAN.md#langkah-fase-2-rinci): SQLAlchemy + Alembic + RLS default-deny, validasi JWT Supabase, `/v1/sync/push` idempotent dan `/v1/sync/pull` berbasis cursor, `SecureSessionStore`, database per akun, UI konflik, serta tes isolasi dua akun. Untuk melanjutkan, tulis **"lanjut"**.
