# Keputusan versi dependency

Diverifikasi 28 September 2026 dari dokumentasi resmi dan metadata Maven Central / Google Maven. Semua versi dipin di `gradle/libs.versions.toml` dan `backend/uv.lock`; tidak ada versi dinamis `+`.

## Aplikasi (Kotlin Multiplatform)

| Komponen | Versi | Sumber / alasan |
|---|---|---|
| Gradle (wrapper) | 9.6.0 | Minimum dan default untuk AGP 9.4 (catatan rilis AGP). Checksum distribusi dipin di `gradle-wrapper.properties`. |
| Kotlin | 2.4.20 | Rilis stabil terbaru (kotlinlang.org/docs/releases, 7 Sep 2026). |
| Android Gradle Plugin | 9.4.1 | Stabil terbaru di Google Maven. AGP 9 memakai Kotlin bawaan, sehingga `androidApp` tidak menerapkan `org.jetbrains.kotlin.android`. |
| Plugin Android KMP library | `com.android.kotlin.multiplatform.library` (AGP 9.4.1) | Wajib untuk modul shared pada AGP 9+ (struktur proyek yang direkomendasikan JetBrains). |
| Compose Multiplatform | 1.12.1 | Stabil terbaru; kompatibel dengan Kotlin terbaru. |
| Compose Material3 (JetBrains) | 1.9.0 | Artefak material3 stabil terbaru (versinya kini terpisah dari CMP). |
| Material Icons Core | 1.7.3 | Versi terakhir yang dipublikasikan; hanya ikon inti yang dipakai. |
| Navigation Compose (JetBrains) | 2.9.2 | Stabil terbaru; mendukung typed route `@Serializable`. |
| Lifecycle ViewModel/Runtime Compose (JetBrains) | 2.11.0 | Stabil terbaru. |
| Room | 2.8.5 | Rilis stabil KMP terbaru. Room 3 (`androidx.room3`) masih alpha sehingga tidak dipakai. |
| SQLite bundled | 2.6.2 | Seri yang dipasangkan dengan Room 2.8. |
| KSP | 2.3.12 | KSP 2.3+ tidak lagi terikat versi Kotlin. |
| Koin (BOM) | 4.2.2 | Stabil terbaru. |
| kotlinx.coroutines | 1.11.0 | Stabil terbaru. |
| kotlinx.serialization | 1.11.0 | Stabil terbaru. |
| kotlinx-datetime | 0.8.0 | Memakai `kotlin.time.Instant`. |
| Ktor Client (core, content-negotiation, OkHttp engine, mock untuk tes) | 3.6.0 | Klien Supabase Auth dan API sinkronisasi. |
| JNA Platform | 5.19.1 | DPAPI (`Crypt32Util`) untuk menyimpan sesi terenkripsi di Windows. |
| activity-compose (androidMain shared) | 1.13.0 | Pemilih berkas `OpenDocument` untuk unggah PDF di Android. |
| activity-compose | 1.13.0 | Entry point Android. |

SDK Android: `compileSdk = 37` (Compose 1.12.1 mensyaratkan compile terhadap API 37; AGP 9.4 mendukung maksimum API 37), `targetSdk = 36`, `minSdk = 26`.

JDK: Gradle berjalan pada JDK 25 yang terpasang; bytecode ditarget JVM 17.

## Backend (Python)

`backend/uv.lock` adalah sumber kebenaran. Versi terkunci saat bootstrap:

| Paket | Versi |
|---|---|
| fastapi | 0.141.1 |
| pydantic-settings | 2.15.0 |
| uvicorn[standard] | 0.54.0 |
| pytest (dev) | 9.1.1 |
| httpx2 (klien provider AI dan TestClient) | 2.13.1 |
| ruff (dev) | 0.16.9 |
| sqlalchemy | 2.1.1 |
| psycopg[binary] | 3.3.6 |
| alembic | 1.20.0 |
| pyjwt[crypto] | 2.15.0 (cryptography 50.0.1) |
| pypdf | 6.19.0 |
| pgvector (Python) | 0.5.0 |
| python-multipart | 0.0.32 |

Provider AI: tanpa SDK. REST API Gemini (`https://generativelanguage.googleapis.com/v1beta`, model `gemini-3.8-flash` dan `gemini-embedding-001`) dan Chat Completions kompatibel OpenAI (alternatif Groq) dipanggil langsung lewat httpx2.

PostgreSQL lokal: image `pgvector/pgvector:pg17` (docker-compose), dipilih agar fase 4 (RAG) tidak perlu mengganti database.

## Cara memeriksa pembaruan

- Kompatibilitas CMP: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- Room KMP: https://developer.android.com/kotlin/multiplatform/room
- AGP: https://developer.android.com/build/releases/gradle-plugin
- Backend: `uv lock --upgrade-package <nama>` lalu jalankan tes.

## Tooling kualitas (11 Oktober 2026)

| Komponen | Versi | Catatan |
|---|---|---|
| `org.jlleitschuh.gradle.ktlint` | 14.2.0 | Mendukung Gradle 9. Gaya `intellij_idea` (lihat `.editorconfig`); `ktlint_official` menghasilkan ~1.600 pelanggaran dan terlalu jauh dari gaya kode yang ada. |
