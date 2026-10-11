# Deployment dan backup

Panduan ini tidak menjalankan deployment berbayar apa pun. Semua langkah dikerjakan pemilik proyek dengan akunnya sendiri; jangan tempel key ke chat, issue, atau commit.

## 1. Supabase

1. Buat proyek Supabase.
2. **Auth**: aktifkan provider Email/Password. Konfirmasi email boleh aktif; aplikasi menampilkan "cek email" bila perlu.
3. **API Keys**:
   - **Publishable key** → aplikasi (`local.properties`: `studyflow.supabase.publishableKey`).
   - **Secret/service-role key** → hanya backend (`STUDYFLOW_SUPABASE_SERVICE_KEY`), dipakai untuk Storage.
4. **JWT**: gunakan signing key asimetris (ES256/RS256, rekomendasi Supabase). Backend memverifikasi lewat `https://<ref>.supabase.co/auth/v1/.well-known/jwks.json`; `STUDYFLOW_JWT_HS256_SECRET` dikosongkan.
5. **Storage**: buat bucket `documents` dengan akses **privat** (bukan public).
6. **Database**: ambil connection string (role `postgres`) → `STUDYFLOW_DATABASE_URL=postgresql+psycopg://...`. Jalankan migrasi dari komputer yang punya akses:
   ```powershell
   cd backend
   uv run alembic upgrade head
   ```
   Migrasi membuat ekstensi `vector`, mengaktifkan RLS tanpa policy di semua tabel bisnis, dan mencabut hak `anon`/`authenticated`. Aplikasi hanya mengakses data lewat backend.

## 2. Backend (API + worker)

Satu image, dua proses:

```powershell
cd backend
docker build -t studyflow-backend .
docker run --env-file .env studyflow-backend alembic upgrade head      # sekali per rilis
docker run -d --env-file .env -p 8000:8000 studyflow-backend           # API
docker run -d --env-file .env studyflow-backend python -m app.worker   # worker
```

`.env` produksi minimal:

```properties
STUDYFLOW_APP_ENV=production
STUDYFLOW_DATABASE_URL=postgresql+psycopg://...
STUDYFLOW_SUPABASE_URL=https://<ref>.supabase.co
STUDYFLOW_SUPABASE_SERVICE_KEY=<secret key>
STUDYFLOW_STORAGE_BACKEND=supabase
STUDYFLOW_AI_PROVIDER=gemini
STUDYFLOW_EMBEDDING_PROVIDER=gemini
STUDYFLOW_AI_API_KEY=<Gemini API key>
```

- `fake` ditolak saat `APP_ENV=production`.
- API wajib di belakang HTTPS (reverse proxy atau platform hosting). Build release Android menolak HTTP.
- Worker boleh lebih dari satu; klaim job memakai `SKIP LOCKED` dan lease.
- Health check: `GET /health` (tidak memuat rahasia).

Biaya hosting dan kuota Gemini bergantung pada platform dan pemakaian; perkiraan belum dihitung.

### Deploy gratis: FastAPI Cloud Hobby + Supabase Free + Gemini free tier

Tanpa kartu kredit. API dan worker berjalan dalam satu proses (`STUDYFLOW_EMBEDDED_WORKER=true`).

1. **Migrasi** (dari komputer sendiri, sekali per rilis yang menambah migrasi). Buat `backend/.env.production` (di-ignore Git) berisi satu baris `STUDYFLOW_DATABASE_URL=...` (Session pooler Supabase, port 5432), lalu:
   ```powershell
   cd backend
   uv run --env-file .env.production alembic upgrade head
   ```
2. **Deploy** dari folder `backend/` (entrypoint diambil dari `[tool.fastapi]` di `pyproject.toml`; `.fastapicloudignore` mencegah `.env`, tes, dan file Docker ikut terunggah):
   ```powershell
   cd backend
   $env:PYTHONIOENCODING = "utf-8"   # CLI memakai emoji; konsol Windows lama gagal tanpa ini
   uv run fastapi deploy
   ```
   Saat pertama kali, CLI membuka browser untuk login dan meminta membuat app baru.
3. **Environment variables** (dashboard → app → Environment Variables → Edit):
   - Biasa: `STUDYFLOW_APP_ENV=production`, `STUDYFLOW_EMBEDDED_WORKER=true`, `STUDYFLOW_STORAGE_BACKEND=supabase`, `STUDYFLOW_AI_PROVIDER=gemini`, `STUDYFLOW_EMBEDDING_PROVIDER=gemini`, `STUDYFLOW_SUPABASE_URL`.
   - Nyalakan **Secret**: `STUDYFLOW_DATABASE_URL`, `STUDYFLOW_SUPABASE_SERVICE_KEY`, `STUDYFLOW_AI_API_KEY`.
   - Simpan dengan **Save and Redeploy**. Lewat CLI: `uv run fastapi cloud env set --secret NAMA` (NAMA wajib ditulis; tanpa NAMA, CLI menanyakan nama lebih dulu dan nilai rahasia bisa tersimpan sebagai nama).
4. **Cek**: `GET https://<app>.fastapicloud.dev/health` → `environment: production`, `ai_mode: live`.

Batasan paket gratis:
- App di-*scale to zero* saat sepi; permintaan pertama sesudahnya lebih lambat. Job AI yang tertunda diproses saat app aktif lagi.
- Proyek Supabase Free di-*pause* setelah sekitar seminggu tanpa aktivitas; aktifkan lagi dari dashboard (Resume).
- Kuota Gemini free tier per proyek terlihat di Google AI Studio; backend juga membatasi pemakaian per pengguna per hari.

`render.yaml` tetap tersedia sebagai alternatif (Render Free, satu web service Docker dengan `/app/start.sh`), tetapi Render meminta verifikasi kartu kredit.

## 3. Aplikasi

- **Konfigurasi build** (`local.properties` atau environment): `studyflow.supabase.url`, `studyflow.supabase.publishableKey`, `studyflow.api.baseUrl` (HTTPS).
- **Android release**: buat keystore di luar repo, lalu buat `keystore.properties` (di-ignore Git):
  ```properties
  storeFile=C:/path/ke/studyflow.jks
  storePassword=...
  keyAlias=studyflow
  keyPassword=...
  ```
  Padanan untuk CI: `STUDYFLOW_KEYSTORE_FILE`, `STUDYFLOW_KEYSTORE_PASSWORD`, `STUDYFLOW_KEY_ALIAS`, `STUDYFLOW_KEY_PASSWORD`.
  ```powershell
  .\gradlew.bat :androidApp:assembleRelease   # androidApp\build\outputs\apk\release\androidApp-release.apk
  ```
  Tanpa keystore, hasilnya `androidApp-release-unsigned.apk` (tidak bisa dipasang).
- **Desktop**:
  ```powershell
  .\gradlew.bat :desktopApp:packageZip        # desktopApp\build\dist\StudyFlow-0.1.0-windows.zip (portabel, runtime bawaan)
  .\gradlew.bat :desktopApp:packageMsi        # opsional, butuh WiX Toolset terpasang
  ```

## 4. Backup dan pemulihan

Database (jadwalkan harian; simpan terenkripsi di luar server):

```powershell
pg_dump "$env:STUDYFLOW_DATABASE_URL_PG" --format=custom --file=studyflow-$(Get-Date -Format yyyyMMdd).dump
pg_restore --clean --if-exists --dbname="$env:STUDYFLOW_DATABASE_URL_PG" studyflow-YYYYMMDD.dump
```

`STUDYFLOW_DATABASE_URL_PG` = URL yang sama dengan format `postgresql://` (tanpa `+psycopg`).

- **Storage**: salin bucket `documents` secara berkala (mis. dengan Supabase CLI atau S3-compatible tools). Setelah pemulihan database, berkas yang tidak lagi direferensikan aman dihapus. Referensi berkas yang hilang membuat unduhan gagal dengan 503, dan dokumen dapat diunggah ulang.
- **Uji pemulihan** ke database terpisah minimal sekali sebelum dipakai sungguhan.

## 5. CI

`.github/workflows/ci.yml` berjalan pada push ke `main` dan pull request:

1. gitleaks memindai seluruh riwayat dengan `.gitleaks.toml`.
2. Tes backend dengan PostgreSQL + pgvector.
3. Tes shared dan build Android/desktop.

Repo privat memakai kuota menit GitHub Actions akun; perubahan yang hanya menyentuh dokumentasi tidak menjalankan CI.
