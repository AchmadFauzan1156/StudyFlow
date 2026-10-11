# Design system StudyFlow — "Agenda akademik"

Arah yang disetujui pada 11 Oktober 2026 (lihat `UI_UX_AUDIT.md` §6). Berlaku untuk Android dan desktop karena UI dibagi lewat Compose Multiplatform.

## 1. Prinsip

1. **Waktu dan mata kuliah adalah struktur utama.** Pengguna membuka aplikasi untuk tahu "apa yang harus dikerjakan, untuk kuliah apa, kapan". Tata letak mengelompokkan menurut waktu (Terlambat, Hari ini, Minggu ini, Nanti) dan menandai mata kuliah dengan warna.
2. **Daftar, bukan tumpukan kartu.** Item berupa baris di atas permukaan, dipisah garis tipis. Kartu hanya untuk mengelompokkan bagian yang memang satu kesatuan (mis. panel akun, hasil AI).
3. **Satu aksen, banyak netral.** Teal tua untuk aksi dan status aktif; warna lain hanya untuk makna (status deadline, warna mata kuliah).
4. **Setiap keadaan punya jalan keluar.** Kosong → ajakan membuat; butuh akun → tombol masuk di tempat; error → pesan jelas dan tombol coba lagi; simpan/hapus → konfirmasi singkat.
5. **Tenang dan cepat.** Tanpa gradien, tanpa bayangan dekoratif, animasi ≤ 250 ms yang menjelaskan perpindahan.

## 2. Token warna

Disimpan di `ui/theme/Color.kt`. Komponen hanya memakai peran dari `MaterialTheme.colorScheme` atau `StudyFlowColors` (token tambahan), tidak pernah hex langsung.

### Peran Material 3

| Peran | Terang | Gelap | Kontras teks (diukur) |
|---|---|---|---|
| primary | `#0F766E` | `#5EEAD4` | terhadap latar 5,28 / 12,51 |
| onPrimary | `#FFFFFF` | `#04302C` | 5,47 / 9,69 |
| primaryContainer | `#CCFBF1` | `#134E4A` | — |
| onPrimaryContainer | `#042F2E` | `#CCFBF1` | — |
| secondary | `#57534E` | `#C9C6C0` | — |
| tertiary (hari ini) | `#92400E` | `#FCD34D` | 6,84 / 12,83 |
| tertiaryContainer | `#FEF3C7` | `#4A3410` | — |
| error (terlambat) | `#B42318` | `#FDA29B` | 6,35 / 9,53 |
| errorContainer | `#FEE4E2` | `#55160C` | — |
| background, surface | `#FBFBF9` | `#121413` | — |
| onBackground, onSurface | `#1C1B19` | `#E7E5E1` | 16,61 / 14,7 |
| onSurfaceVariant (teks sekunder) | `#57534E` | `#C9C6C0` | 7,36 / 10,85 |
| surfaceContainerLow (panel) | `#F4F3F0` | `#1A1C1B` | — |
| surfaceContainer | `#EEEDE9` | `#1F2120` | — |
| surfaceContainerHigh | `#E8E6E1` | `#2A2C2B` | — |
| outline | `#78736C` | `#928D86` | batas kolom input 4,54 / 5,62 |
| outlineVariant (garis pemisah) | `#E4E2DD` | `#323432` | dekoratif |

### Warna mata kuliah

Ditentukan dari `courseId` (hash stabil, sama di semua perangkat karena ID ikut tersinkron), sehingga **tidak mengubah model data atau kontrak sinkronisasi**. Dipakai sebagai garis kiri baris tugas/catatan, titik di sidebar, dan avatar.

| Nama | Garis/teks terang | Kontras | Garis/teks gelap | Kontras |
|---|---|---|---|---|
| cyan | `#0E7490` | 5,17 | `#2DD4BF` | 9,94 |
| biru | `#2563EB` | 4,99 | `#60A5FA` | 7,28 |
| ungu | `#7C3AED` | 5,50 | `#A78BFA` | 6,80 |
| mawar | `#E11D48` | 4,53 | `#FB7185` | 6,87 |
| amber | `#B45309` | 4,85 | `#FBBF24` | 11,08 |
| zamrud | `#047857` | 5,29 | `#34D399` | 9,62 |
| langit | `#0369A1` | 5,73 | `#38BDF8` | 8,64 |
| fuchsia | `#A21CAF` | 6,10 | `#E879F9` | 7,52 |

Latar avatar memakai warna yang sama dengan alfa 14% (terang) atau 24% (gelap); huruf memakai warna penuh.

### Status deadline

| Status | Penanda | Teks |
|---|---|---|
| Terlambat | `error` | "Terlambat · kemarin 16.51" |
| Hari ini | `tertiary` | "Hari ini · 15.51" |
| Segera (≤ 3 hari) | `primary` | "Sel 13 Okt · 04.51" |
| Nanti | `onSurfaceVariant` | tanggal saja |
| Selesai | `onSurfaceVariant` + coret | — |

Status selalu memakai teks, warna hanya penguat.

## 3. Tipografi

Font: **Plus Jakarta Sans** (Tokotype, OFL 1.1), dibundel 4 ketebalan (Regular 400, Medium 500, SemiBold 600, Bold 700) lewat Compose Resources. Angka pada jam, tanggal, dan statistik memakai `fontFeatureSettings = "tnum"` agar sejajar.

| Token M3 | Ukuran / tinggi baris | Ketebalan | Pemakaian |
|---|---|---|---|
| headlineSmall | 24 / 32 sp | Bold | Judul halaman utama |
| titleLarge | 20 / 28 sp | SemiBold | Judul bilah atas layar turunan |
| titleMedium | 16 / 24 sp | SemiBold | Judul item daftar, judul panel |
| titleSmall | 14 / 20 sp | SemiBold | Judul baris padat |
| bodyLarge | 16 / 24 sp | Regular | Isi catatan, kolom input |
| bodyMedium | 14 / 20 sp | Regular | Teks pendukung |
| bodySmall | 12 / 16 sp | Regular | Metadata (waktu, jumlah) |
| labelLarge | 14 / 20 sp | SemiBold | Tombol |
| labelMedium | 12 / 16 sp | SemiBold, huruf kapital, spasi 0,6 sp | Label kelompok ("TERLAMBAT", "HARI INI") |

Aturan: maksimal 3 tingkat dalam satu layar (judul, item, metadata); teks isi tidak di bawah 12 sp.

## 4. Spasi, grid, dan bentuk

- Skala spasi (dp): **4, 8, 12, 16, 24, 32, 48** (`Spacing.xs … xxl` di `ui/theme/Dimens.kt`).
- Margin layar: 16 dp di ponsel, 24 dp di ≥ 600 dp, 32 dp di ≥ 1200 dp.
- Lebar maksimum konten (`Dimens.kt`):
  - `ContentWidth.form` = **640 dp**: editor dan form.
  - `ContentWidth.reading` = **720 dp**: teks panjang (catatan, rangkuman, jawaban AI).
  - `ContentWidth.list` = **840 dp**: daftar dan detail.
  - `ContentWidth.wide` = **1160 dp**: kisi (daftar mata kuliah dua kolom, Beranda dua panel).
- Baris daftar: tinggi minimum 56 dp (satu baris) / 72 dp (dua baris); padding horizontal 16 dp; pemisah `outlineVariant` 1 dp yang menjorok sesuai awal teks.
- Bentuk: kecil 8 dp (chip, badge), sedang 12 dp (kolom input, panel), besar 16 dp (dialog, FAB). Efek tekan selalu dipotong mengikuti bentuk.

## 5. Komponen

| Komponen | Spesifikasi | Menggantikan |
|---|---|---|
| `AgendaSection` | Label kelompok (`labelMedium` kapital + jumlah), lalu baris-baris tugas | daftar kartu "Deadline terdekat" |
| `TaskRow` | Garis warna mata kuliah 3 dp · checkbox 48 dp · judul (`titleSmall`) · baris kedua "Mata kuliah · waktu" · status di kanan sebagai teks berwarna. Tekan → editor; geser tetap ada sebagai jalan pintas. | kartu dengan badge berlatar |
| `NoteRow` | Garis warna mata kuliah · judul · baris pertama isi yang tidak kosong (1 baris, elipsis) · "Diubah …" | kartu dengan teks mentah |
| `CourseRow` | Avatar berwarna mata kuliah · nama · "Ganjil 2026 · 2 catatan · 3 tugas" (angka nol disembunyikan) · menu ⋮ | kartu abu-abu |
| `SummaryLine` | Satu kalimat ringkas: "4 tugas terbuka · 1 terlambat" dengan angka tabular; bisa diketuk ke Progres | 3 `StatTile` besar di Beranda |
| `StatTile` | Tetap dipakai di Progres, dengan konteks ("dari 5 tugas") | — |
| `NeedsAccountPanel` | Ikon, judul, satu kalimat manfaat, **tombol "Masuk atau daftar"** langsung ke halaman akun | `InfoCard` tanpa aksi |
| `AccountChip` | Penanda kecil di kepala Beranda: "● Mode lokal ›" atau "● Tersinkron"; jelas bisa diketuk | kartu info besar |
| Snackbar global | `LocalSnackbar` di `App`; pesan ≤ 1 baris setelah simpan/hapus/sinkron gagal | tidak ada |
| `ScreenHeader` | Judul halaman + `semantics { heading() }`; satu judul per layar | judul ganda Beranda |
| Bilah atas editor | Kembali · judul · **Simpan** (tombol terisi). Hapus dipindah ke menu ⋮ dengan dialog konfirmasi. | ikon hapus di sebelah Simpan |
| Tab detail mata kuliah | Rata kiri, lebar mengikuti teks, berada di dalam kolom konten yang sama dengan daftarnya | tab rata penuh selebar jendela |

## 6. Keadaan dan interaksi

| Keadaan | Pola |
|---|---|
| Memuat daftar | 3 baris kerangka berwarna `surfaceContainer` (tanpa animasi kilau) selama > 300 ms; spinner hanya untuk aksi tombol |
| Kosong | `EmptyState` dengan ikon yang sesuai konteks dan satu tombol aksi |
| Butuh akun | `NeedsAccountPanel` dengan tombol "Masuk atau daftar" |
| Error | Pesan di dekat sumbernya + tombol "Coba lagi"; error tanpa tempat (mis. sinkron) lewat Snackbar |
| Berhasil | Snackbar: "Catatan disimpan", "Tugas dihapus" |
| Hover (desktop) | `stateLayer` 8% primary; kursor tangan pada elemen yang bisa diklik |
| Fokus (keyboard) | Garis 2 dp `primary` di luar elemen, terlihat di semua baris dan tombol |
| Ditekan | Riak dipotong mengikuti bentuk |
| Nonaktif | Opasitas 38% untuk konten, tanpa mengubah tata letak |

## 7. Responsif

| Lebar | Navigasi | Tata letak |
|---|---|---|
| < 600 dp | Bottom bar 4 menu | Satu kolom, margin 16 dp |
| 600–839 dp | **NavigationRail** (ikon + label, 80 dp) | Satu kolom, margin 24 dp |
| ≥ 840 dp | Sidebar 256 dp dengan daftar mata kuliah yang **dibuka/ditutup lewat klik atau keyboard** (bukan hover) | Beranda dua panel (agenda + panel samping), daftar mata kuliah dua kolom |

## 8. Gerak

- Durasi: 150 ms (umpan balik kecil), 200 ms (muncul/hilang), 250 ms (pindah layar). Easing standar M3 (`FastOutSlowIn`); keluar lebih cepat dari masuk.
- Animasi hanya untuk: perpindahan layar, membuka/menutup daftar sidebar, centang tugas (coret + pudar), Snackbar.
- Tidak ada animasi berulang atau dekoratif.

## 9. Aksesibilitas

- Kontras teks ≥ 4,5:1, komponen non-teks penting ≥ 3:1 (angka terukur di §2).
- Target sentuh ≥ 48 dp; jarak antar target ≥ 8 dp.
- Satu `heading()` per judul halaman dan per label kelompok.
- Ikon dekoratif tanpa deskripsi; tombol ikon wajib punya deskripsi.
- Status tidak hanya lewat warna (teks "Terlambat", "Hari ini").
- Semua aksi di desktop bisa dicapai dengan Tab/Enter; pintasan Ctrl+1..4 tetap.
- Teks mengikuti skala font sistem; tata letak tidak memotong teks pada skala 130%.
