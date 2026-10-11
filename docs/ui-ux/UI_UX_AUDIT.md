# Audit UI/UX StudyFlow

Tanggal: 11 Oktober 2026 · Branch: `perbaikan-ui-ux` · Fase 1 (audit, tanpa perubahan kode aplikasi)

## 1. Ruang lingkup dan cara audit

| Hal | Isi |
|---|---|
| Stack | Kotlin Multiplatform + Compose Multiplatform 1.12 (Material 3), satu UI bersama untuk Android dan Windows |
| Kode UI | `shared/src/commonMain/kotlin/id/studyflow/ui/` (37 file, ±5.000 baris) |
| Tema | `ui/theme/Theme.kt`: skema warna M3 indigo, font bawaan sistem, bentuk 8–28 dp |
| Komponen bersama | `ui/components/Components.kt`: `EmptyState`, `IconBadge`, `InitialAvatar`, `SectionHeader`, `InfoCard`, `ConfirmDeleteDialog`, `DeadlineBadge`, `TaskRow`, `StatTile` |
| Navigasi | 4 menu utama (Beranda, Mata Kuliah, Progres, Pengaturan); sidebar ≥ 600 dp, bottom bar < 600 dp; Ctrl+1..4 di desktop |
| Screenshot | `docs/ui-ux/screenshots/before/` — 36 gambar, ukuran ponsel (412×892 dp) dan desktop (1280×800 dp), terang dan gelap, data contoh dan keadaan kosong |

Screenshot dibuat oleh `shared/src/jvmTest/kotlin/id/studyflow/ui/ScreenshotCapture.kt`: aplikasi asli dirender dengan database in-memory berisi data contoh dan konfigurasi server seperti build rilis (jaringan dipalsukan). Perintah:

```powershell
$env:STUDYFLOW_SCREENSHOT_DIR = "docs/ui-ux/screenshots/before"
.\gradlew.bat :shared:jvmTest --tests "id.studyflow.ui.ScreenshotCapture"
```

**Belum tercakup:** layar AI (rangkuman, kuis, tanya materi, dokumen PDF, flashcard) dan layar konflik sinkronisasi, karena butuh akun dan data dari server. Temuan untuk layar ini berasal dari membaca kode, belum dari tampilan. Fokus keyboard di desktop dan pembaca layar (TalkBack/Narrator) juga belum diuji langsung.

## 2. Inventaris layar

| Layar | Route | Screenshot |
|---|---|---|
| Beranda (sapaan, deadline terdekat, ringkasan, status akun) | `HomeRoute` | 01, 00-kosong |
| Daftar mata kuliah | `CoursesRoute` | 02, 00-kosong |
| Detail mata kuliah: tab Catatan, Materi, Tugas, Belajar | `CourseDetailRoute` | 03–06 |
| Editor catatan | `NoteEditorRoute` | 07 |
| Editor tugas | `TaskEditorRoute` | 08 |
| Progres (statistik, riwayat kuis, rekomendasi, flashcard) | `ProgressRoute` | 09 |
| Pengaturan (tema, akun, tentang) | `SettingsRoute` | 10 |
| Akun (masuk/daftar) | `AuthRoute` | 11 |
| Catatan untuk belajar, rangkuman, kuis, dokumen, tanya materi, sumber, flashcard, konflik | 8 route lain | belum |

## 3. Yang sudah baik

- Struktur navigasi jelas dan konsisten: 4 menu, sidebar di layar lebar, bottom bar di ponsel, tombol kembali di setiap layar turunan.
- Keadaan kosong punya judul, penjelasan, dan tombol aksi (mis. "Belum ada mata kuliah" → "Tambah mata kuliah").
- Status deadline memakai teks, bukan hanya warna ("Terlambat", "Hari ini", "Segera").
- Ikon dekoratif sudah `contentDescription = null` dan tombol ikon punya label; tombol hapus memakai dialog konfirmasi.
- Mode gelap tersedia dan kontras teks utamanya baik.
- Lebar konten dibatasi di layar lebar, sehingga teks tidak membentang penuh.

## 4. Temuan

Tingkat: **Tinggi** = menghambat tugas atau menyesatkan pengguna; **Sedang** = menurunkan kejelasan atau kenyamanan; **Rendah** = poles. Prioritas: P1 dikerjakan lebih dulu.

### Alur dan kegunaan

| # | Temuan | Bukti | Tingkat | Prioritas |
|---|---|---|---|---|
| U1 | **Jalan buntu di fitur yang butuh akun.** Tab Materi, Belajar, dan kartu Riwayat kuis hanya berisi kartu info "Masuk dari menu Pengaturan" tanpa tombol. Pengguna harus keluar dari layar, mencari Pengaturan, lalu kembali. | 05, 06, 09 | Tinggi | P1 |
| U2 | **Tidak ada umpan balik setelah menyimpan atau menghapus.** Tidak ada Snackbar di seluruh aplikasi; setelah Simpan layar langsung tertutup, setelah Hapus data hilang tanpa opsi batalkan. | kode: 0 pemakaian `Snackbar` | Tinggi | P1 |
| U3 | **Daftar mata kuliah di sidebar hanya terbuka saat kursor di atasnya** dan menutup sendiri setelah kursor pergi. Tidak bisa dibuka dengan keyboard atau layar sentuh laptop, dan posisinya berubah-ubah antar layar. | 02/03 vs 04; `Sidebar.kt:97-105` | Tinggi | P1 |
| U4 | **Versi aplikasi salah.** Pengaturan menampilkan "0.2.0" (teks tetap di kode), padahal rilis v0.1.0. Membingungkan saat pengguna melaporkan masalah. | 10; `Settings.kt:86` | Tinggi | P1 |
| U5 | **Judul ganda di Beranda**: bilah atas "Beranda" lalu judul besar "Selamat siang". Di ponsel dua judul besar memakan ±15% layar pertama. | 01 ponsel | Sedang | P2 |
| U6 | **Tombol hapus bersebelahan dengan Simpan** di editor catatan dan tugas; aksi merusak dan aksi utama mudah tertukar. | 07, 08 | Sedang | P2 |
| U7 | **Gestur geser untuk menyelesaikan tugas tidak terlihat.** Tidak ada petunjuk; untungnya checkbox tetap ada. | `TaskRow` | Rendah | P3 |
| U8 | Kartu "Mode lokal" di Beranda bisa diketuk (membuka halaman akun), tetapi tampilannya seperti info statis tanpa tanda bisa diketuk. | 01 | Sedang | P2 |

### Hierarki visual dan tata letak

| # | Temuan | Bukti | Tingkat | Prioritas |
|---|---|---|---|---|
| V1 | **Identitas visual generik.** Warna indigo bawaan M3 dan font sistem membuat aplikasi tampak seperti template; tidak ada ciri yang terkait dengan belajar atau mahasiswa. | semua | Sedang | P2 |
| V2 | **Mata kuliah tidak bisa dibedakan secara visual.** Avatar inisial hampir selalu berwarna sama (B, J, K semuanya ungu muda); tidak ada warna per mata kuliah di daftar tugas, sidebar, atau detail. Padahal mata kuliah adalah pengelompokan utama aplikasi. | 02, 01 | Sedang | P2 |
| V3 | **Semua item berupa kartu abu-abu di atas latar abu-abu.** Kartu `#F4F4F7` di latar `#FAFAFB` hampir tak berbeda (rasio ±1,05:1); batas item hanya terbaca dari jarak. Daftar terasa berat dan seragam. | 01–04 | Sedang | P2 |
| V4 | **Lebar konten tidak konsisten**: 480, 640, 840, dan 1160 dp. Tab detail mata kuliah membentang penuh (jarak antar tab ±400 dp di desktop) sementara kontennya di tengah; editor tugas memakai 1160 dp sehingga kolom form sangat panjang. | 03–08 | Sedang | P2 |
| V5 | **Ringkasan angka kurang bermakna.** Kotak statistik besar ("3", "4", "1") tanpa konteks waktu; di desktop kotak "Terlambat" sendirian selebar dua kolom. Halaman Progres berisi 4 angka serupa. | 01, 09 | Sedang | P2 |
| V6 | **Pratinjau catatan menampilkan teks mentah** termasuk baris kosong, sehingga tinggi kartu tidak rata dan isi penting terpotong. | 03 | Rendah | P3 |
| V7 | Hierarki tipografi datar: judul bagian, judul item, dan judul kartu sama-sama `titleMedium/titleSmall` tebal. Angka dan tanggal tidak memakai angka tabular sehingga kolom waktu tidak sejajar. | 01, 04 | Sedang | P2 |
| V8 | Ikon keadaan kosong generik (pensil untuk "Belum ada mata kuliah", ikon info di banyak tempat). | 00 | Rendah | P3 |

### Keadaan (state) dan interaksi

| # | Temuan | Bukti | Tingkat | Prioritas |
|---|---|---|---|---|
| S1 | Efek tekan pada kartu catatan berbentuk kotak, tidak mengikuti sudut membulat kartu. Sorotan hover pada tab tetap tertinggal setelah berpindah. | 03 ponsel, 04–06 | Rendah | P3 |
| S2 | Loading hanya berupa spinner (11 tempat); tidak ada kerangka (skeleton) untuk daftar, sehingga layar berkedip dari kosong ke berisi. | kode | Rendah | P3 |
| S3 | Belum ada pola error yang seragam: pesan error ditulis per layar. Perlu diperiksa di layar AI setelah login. | kode | Sedang | P2 (verifikasi) |
| S4 | Di renderer tes, FAB memperlihatkan bayangan ganda di bawahnya. Perlu dicek di perangkat asli sebelum dianggap masalah. | 02, 03 | Rendah | verifikasi |

### Aksesibilitas

| # | Temuan | Bukti | Tingkat | Prioritas |
|---|---|---|---|---|
| A1 | Tidak ada semantik heading (`semantics { heading() }` = 0); pengguna pembaca layar tidak bisa melompat antar bagian. | kode | Sedang | P2 |
| A2 | Batas kartu yang bisa diklik hampir tak terlihat (lihat V3); WCAG 1.4.11 menyarankan kontras 3:1 untuk batas komponen yang dibutuhkan untuk mengenalinya. | 01–04 | Sedang | P2 |
| A3 | Fokus keyboard di desktop (kartu, tab, item sidebar) belum diverifikasi; bawaan M3 untuk kartu cukup samar. | belum diuji | Sedang | P2 (verifikasi) |
| A4 | Ukuran target sentuh sudah ≥ 48 dp (checkbox, menu ⋮, tab, item navigasi). | — | Baik | — |

### Responsif

| # | Temuan | Tingkat | Prioritas |
|---|---|---|---|
| R1 | Hanya ada satu titik ubah (600 dp). Rentang 600–840 dp (tablet kecil, jendela desktop sempit) memakai tata letak desktop penuh dengan sidebar 272 dp yang memakan ruang. | Sedang | P2 |
| R2 | Desktop 1280 dp: banyak ruang kosong di kanan daftar mata kuliah dan di bawah konten; ruang ini bisa dipakai untuk panel detail atau agenda. | Rendah | P3 |

## 5. Ringkasan prioritas

- **P1 (alur rusak/menyesatkan):** U1 jalan buntu fitur akun, U2 tanpa umpan balik simpan/hapus, U3 sidebar hanya-hover, U4 versi salah.
- **P2 (kejelasan dan identitas):** U5, U6, U8, V1–V5, V7, S3, A1–A3, R1.
- **P3 (poles):** U7, V6, V8, S1, S2, R2.

## 6. Usulan arah desain (Fase 2, menunggu persetujuan)

Lihat ringkasan di percakapan; dokumen `DESIGN_SYSTEM.md` dan `REFINEMENT_PLAN.md` dibuat setelah arah dipilih.
