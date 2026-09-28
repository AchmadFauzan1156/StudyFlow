# Skrip demo end-to-end

Demo lokal tanpa API key dan tanpa proyek Supabase. Semua hasil AI berlabel **[Demo] / "Demo, bukan AI"**, dan data demo tidak tercampur dengan data pengguna: demo memakai database Docker sendiri dan akun uji.

## Persiapan (sekali)

```powershell
cd backend
docker compose -f docker-compose.demo.yml up --build -d        # PostgreSQL + migrasi + API :8000 + worker
uv run python scripts/make_demo_pdfs.py                        # PDF contoh ke backend\demo-pdfs\
```

Aplikasi membutuhkan login Supabase. Untuk demo tanpa Supabase, jalankan tes E2E yang memakai JWT demo (lihat bagian terakhir), atau hubungkan aplikasi ke proyek Supabase dev dengan `STUDYFLOW_SUPABASE_URL` backend yang sama.

## Alur demo (±10 menit)

1. **Mode lokal**: buka aplikasi desktop, buat mata kuliah "Kriptografi", tambah catatan dan tugas dengan deadline besok. Tunjukkan label "Segera" di Beranda.
2. **Akun dan sinkronisasi**:
   1. Masuk. Terima tawaran impor data lokal.
   2. Di perangkat kedua (instance desktop dengan `APPDATA` berbeda, atau emulator), masuk ke akun yang sama; data muncul.
   3. Ubah nama mata kuliah di kedua perangkat untuk menunjukkan konflik dan pilihan "Pakai versi saya".
3. **Rangkuman dan kuis**:
   1. Belajar → pilih catatan → persetujuan pengiriman data → Buat kuis 3 soal.
   2. Kerjakan kuis, lihat skor, pembahasan, dan sumber.
   3. Tandai satu soal bermasalah.
4. **Materi PDF**:
   1. Tab Materi → Unggah `demo-pdfs\kriptografi.pdf` → status "Siap" → pratinjau teks.
   2. Unggah `demo-pdfs\scan-kosong.pdf` untuk menunjukkan penolakan PDF scan.
5. **Tanya materi**:
   1. Belajar → Tanya materi → "Bagaimana modulus n pada RSA dihitung?" → jawaban dengan chip sumber → buka cuplikan.
   2. Tanyakan hal di luar materi untuk menunjukkan jawaban "materi belum memuat jawabannya".
6. **Progres**: tunjukkan penguasaan per topik dan Latihan kartu dari soal yang salah.
7. **Offline**: matikan backend (`docker compose -f docker-compose.demo.yml stop api`). Riwayat kuis, rangkuman, materi, dan jawaban tetap terbaca dengan label "Offline"; edit catatan masuk antrean dan terkirim setelah API hidup lagi.

## Verifikasi otomatis terhadap stack demo

```powershell
$env:STUDYFLOW_E2E_API_URL = "http://127.0.0.1:8000"
$env:STUDYFLOW_E2E_JWT_SECRET = "demo-only-secret-change-me-000000000000"
$env:STUDYFLOW_E2E_ISSUER_BASE = "http://auth.demo.local"
$env:STUDYFLOW_E2E_AI = "1"
.\gradlew.bat :shared:jvmTest --tests "*TwoDeviceE2ETest*" --rerun-tasks
```

Tes ini mencakup: sinkronisasi dua perangkat, konflik, retry tanpa duplikat, isolasi akun, kuis melalui worker, progres dan kartu, serta unggah PDF → tanya materi → sumber → hapus.

Bersihkan: `docker compose -f docker-compose.demo.yml down -v`.
