# Changelog UI/UX

Branch `perbaikan-ui-ux`, 11 Oktober 2026. Dasar: `UI_UX_AUDIT.md`, `DESIGN_SYSTEM.md`, `REFINEMENT_PLAN.md`. Screenshot sebelum/sesudah: `screenshots/before/` dan `screenshots/after/` (nama file sama; `after/` menambah ukuran tablet 700 dp).

Route, ViewModel, repository, model data, kontrak API, dan sinkronisasi tidak berubah. Tidak ada fitur yang dihapus.

## Perubahan

### Perbaikan fungsional (P1)
- **Versi aplikasi** diambil dari satu sumber (`studyflow.version` di `gradle.properties`) untuk Android, paket desktop, dan Pengaturan. Sebelumnya Pengaturan menampilkan "0.2.0" padahal rilis v0.1.0. (U4)
- **Fitur yang butuh akun** (tab Materi, Belajar, Riwayat kuis) kini punya tombol **"Masuk atau daftar"** langsung di tempat; versi tanpa server menjelaskan bahwa fitur belum tersedia tanpa tombol. (U1)
- **Snackbar** setelah menyimpan/menghapus mata kuliah, catatan, dan tugas. Di layar lebar muncul di kiri bawah dengan lebar terbatas; di ponsel di atas area FAB, sehingga tombol tambah tetap bisa ditekan. (U2)
- **Daftar mata kuliah di sidebar** dibuka/ditutup lewat tombol panah (mouse, sentuh, keyboard), terbuka sendiri saat sebuah mata kuliah dibuka; tidak lagi bergantung pada hover. (U3)

### Fondasi
- Tema **teal tua** dengan netral hangat, warna terang dan gelap terukur kontrasnya (DESIGN_SYSTEM §2).
- Font **Plus Jakarta Sans** (OFL) dibundel lewat Compose Resources; angka tabular untuk waktu dan statistik.
- **Warna penanda mata kuliah** (8 warna) diturunkan dari ID mata kuliah: garis di baris tugas/catatan dan avatar.
- Token spasi (`Spacing`) dan lebar konten (`ContentWidth`: form 640, baca 720, daftar 840, lebar 1160 dp).
- Komponen baru/diubah: `TaskRow` berupa baris (garis warna, checkbox berlabel untuk pembaca layar, waktu relatif "Hari ini · 16.30"), `CourseAvatar`, `CourseStripe`, `GroupLabel`, `CountRow`, `NeedsAccountPanel`, `DeadlineStatusLabel`, `Modifier.heading()`.

### Layar
- **Beranda**: satu judul (sapaan) dengan tanggal dan ringkasan satu kalimat ("4 tugas terbuka · 1 terlambat"); deadline dikelompokkan **Terlambat / Hari ini / 7 hari ke depan / Nanti**; penanda akun ringkas yang bisa diketuk di ponsel, panel akun dengan tombol dan ringkasan angka di layar lebar. (U5, U8, V5)
- **Mata Kuliah**: baris bergaris tipis dengan avatar berwarna; metadata tanpa angka nol ("Belum ada catatan atau tugas"); dua kolom di layar lebar. (V2, V3)
- **Detail mata kuliah**: tab berada di kolom konten (tidak membentang selebar jendela); catatan berupa baris dengan pratinjau baris pertama yang tidak kosong; hapus mata kuliah dipindah ke menu ⋮. (V4, V6, U6)
- **Editor catatan/tugas**: Simpan menjadi tombol terisi, Hapus di menu ⋮; lebar 720 dp (catatan) dan 640 dp (tugas). (U6, V4)
- **Progres**: persentase tugas selesai dengan konteks ("1 dari 5 tugas selesai") dan jumlah terlambat, ringkasan isi dalam baris. (V5)
- **Pengaturan, Akun, layar AI**: label bagian seragam, judul bertanda heading, lebar konten konsisten.
- **Navigasi responsif**: bottom bar < 600 dp, **navigation rail 600–839 dp**, sidebar 256 dp mulai 840 dp. (R1)

### Aksesibilitas
- Judul layar dan label kelompok bertanda `heading()` untuk pembaca layar. (A1)
- Checkbox tugas punya deskripsi ("Tandai Kuis RSA selesai").
- Batas baris mata kuliah memakai garis `outlineVariant`; daftar tugas/catatan dipisah jarak dan garis warna, bukan kartu abu-abu berkontras rendah. (A2, V3)
- Status deadline tetap berupa teks; label status di agenda disembunyikan karena kelompoknya sudah menyatakan status.

## Status temuan audit

| Temuan | Status |
|---|---|
| U1, U2, U3, U4 | Selesai |
| U5, U6, U8 | Selesai |
| U7 gestur geser tidak terlihat | Tetap (checkbox tersedia sebagai jalan utama) |
| V1–V7 | Selesai |
| V8 ikon keadaan kosong | Sebagian (ikon "Belum ada mata kuliah" kini sama dengan menu Mata Kuliah) |
| S1 efek tekan kotak | Selesai (baris dipotong mengikuti bentuk; latar geser hanya saat digeser) |
| S2 kerangka memuat | Ditunda |
| S3 pola error seragam di layar AI | Ditunda (butuh pengujian dengan akun dan server) |
| S4 bayangan ganda FAB | Bukan masalah: hanya di renderer tes, tidak muncul di emulator |
| A1, A2 | Selesai |
| A3 fokus keyboard desktop | Belum diverifikasi langsung |
| R1 | Selesai |
| R2 ruang kosong desktop | Sebagian (Beranda dua panel, Mata Kuliah dua kolom) |

## Verifikasi yang dijalankan

| Perintah / cara | Hasil |
|---|---|
| `gradlew ktlintCheck :shared:jvmTest :androidApp:assembleDebug :desktopApp:jar` | Berhasil; 85 tes Kotlin lulus, 0 gagal (termasuk tes baru: versi di Pengaturan, tombol masuk di fitur akun, Snackbar simpan, sidebar dibuka lewat klik, navigasi 599/600/839/840 dp, pengelompokan agenda, label waktu, metadata mata kuliah, pratinjau catatan, ViewModel catatan dan progres) |
| `ScreenshotCapture` → `screenshots/after/` | 41 gambar: ponsel, tablet, desktop; terang dan gelap; data contoh dan kosong |
| Emulator Android (build debug) | Font dan tema tampil; tambah mata kuliah → Snackbar "Mata kuliah disimpan" di atas FAB |

Belum dijalankan: TalkBack/Narrator penuh, navigasi Tab di aplikasi desktop, dan layar AI dengan akun sungguhan.
