# Rencana penyempurnaan UI/UX

Dasar: `UI_UX_AUDIT.md` (temuan) dan `DESIGN_SYSTEM.md` (arah "Agenda akademik"). Urutan disetujui: perbaikan P1 dulu, lalu token dan komponen bersama, lalu layar satu per satu.

## Aturan kerja

- Route, ViewModel, repository, model data, kontrak API, dan sinkronisasi **tidak diubah** kecuali disebut di langkah. Warna mata kuliah diturunkan dari `courseId`, bukan disimpan.
- Satu langkah = satu commit (tanpa atribusi Claude) yang lulus `ktlintCheck`, `:shared:jvmTest`, dan build Android/desktop.
- Perubahan perilaku diuji dulu (tes gagal → implementasi → lulus). Perubahan murni visual diverifikasi dengan screenshot sesudah (`ScreenshotCapture`).
- Teks UI tetap bahasa Indonesia; tidak menyebut nama orang.

## Langkah

### Bagian A — Perbaikan P1 (fungsional)

| # | Langkah | Temuan | Verifikasi |
|---|---|---|---|
| A1 | Versi aplikasi dari satu sumber: properti Gradle `studyflow.version` dipakai Android `versionName`, paket desktop, dan `AppConfig.appVersion`; Pengaturan menampilkannya | U4 | Tes UI: Pengaturan menampilkan `AppConfig.appVersion`; build paket memakai versi yang sama |
| A2 | `NeedsAccountPanel` dengan tombol "Masuk atau daftar" yang membuka halaman akun, dipakai di Materi, Belajar, dan Riwayat kuis (lewat `LocalAppActions`, tanpa mengubah route) | U1 | Tes UI: dari tab Belajar, tombol membuka halaman akun |
| A3 | Snackbar global (`LocalMessenger`) dan pesan setelah simpan/hapus mata kuliah, catatan, dan tugas | U2 | Tes UI: simpan catatan → "Catatan disimpan" tampil |
| A4 | Daftar mata kuliah di sidebar dibuka/ditutup lewat klik atau keyboard (tombol panah dengan label), terbuka otomatis saat berada di layar mata kuliah; hover tidak lagi menutupnya | U3 | Tes UI: klik "Tampilkan daftar mata kuliah" menampilkan nama mata kuliah dan tetap terbuka |

### Bagian B — Fondasi design system

| # | Langkah | Temuan |
|---|---|---|
| B1 | `Color.kt` (skema teal terang/gelap, warna mata kuliah, status), `Type.kt` (Plus Jakarta Sans lewat Compose Resources, angka tabular), `Dimens.kt` (spasi, lebar konten); `Theme.kt` memakainya. Lisensi OFL disertakan. | V1, V7 |
| B2 | Komponen bersama: `courseColor(id)`, `CourseAvatar`, `ScreenHeader` (heading semantics), `GroupLabel`, `ListDivider`, `NeedsAccountPanel`, `AccountChip`, `SummaryLine`; `TaskRow` dan `EmptyState` disesuaikan; efek tekan dipotong mengikuti bentuk | V2, V3, A1, S1 |

### Bagian C — Layar

| # | Layar | Isi | Temuan |
|---|---|---|---|
| C1 | Beranda | Satu judul, `AccountChip`, `SummaryLine`, agenda berkelompok (Terlambat / Hari ini / Minggu ini / Nanti); panel samping di ≥ 840 dp | U5, U8, V5 |
| C2 | Mata Kuliah | `CourseRow` dengan warna mata kuliah, metadata ringkas, dua kolom di layar lebar | V2, V3 |
| C3 | Detail mata kuliah | Tab rata kiri di dalam kolom konten, `NoteRow` (baris pertama isi), daftar tugas dengan `TaskRow` baru | V4, V6 |
| C4 | Editor catatan dan tugas | Lebar form 640 dp, Simpan sebagai tombol terisi, Hapus di menu ⋮ | U6, V4 |
| C5 | Progres dan Pengaturan | Statistik dengan konteks, panel akun, lebar konsisten | V5 |
| C6 | Layar AI dan akun | Lebar baca 720 dp, pola error dan memuat seragam | S2, S3 |
| C7 | Navigasi responsif | NavigationRail untuk 600–839 dp; sidebar 256 dp untuk ≥ 840 dp | R1 |

### Bagian D — QA dan dokumentasi

| # | Langkah |
|---|---|
| D1 | Screenshot sesudah (`docs/ui-ux/screenshots/after/`) untuk ukuran dan tema yang sama dengan sebelum, ditambah 600–839 dp |
| D2 | Seluruh tes, build Android/desktop, cek manual di emulator (TalkBack sekilas) dan desktop (navigasi keyboard) |
| D3 | `CHANGELOG_UI_UX.md`: perubahan per layar, temuan yang ditutup, yang ditunda |
