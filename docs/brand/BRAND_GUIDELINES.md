# Panduan identitas StudyFlow

Logo: **monogram SF dari baris agenda** (disetujui 11 Oktober 2026). Konsep yang dipertimbangkan ada di `concepts/`.

![Logo horizontal](png/horizontal/studyflow-horizontal-800.png)

## 1. Makna

- **S** tersusun dari tiga baris agenda yang disambung belokan bulat: tugas dan catatan yang mengalir dari satu ke berikutnya. Baris atas sedikit ke kanan dan baris bawah sedikit ke kiri agar lekuk S terbaca.
- **F** memakai **batang berwarna** yang sama dengan garis penanda mata kuliah di aplikasi; dua lengannya sejajar dengan baris S.
- Bentuk dasar: garis tebal dengan ujung membulat, dari bahasa visual yang sama dengan baris agenda di aplikasi. Tanpa gradien, bayangan, atau efek 3D.

## 2. Warna

Selaras dengan design system aplikasi (`docs/ui-ux/DESIGN_SYSTEM.md`).

| Pemakaian | Badan S dan lengan F | Batang F | Latar |
|---|---|---|---|
| Latar terang | Teal `#0F766E` | Teal medium `#0D9488` | Putih / `#FBFBF9` |
| Ikon aplikasi | Putih `#FFFFFF` | Teal muda `#5EEAD4` | Teal `#0F766E` |
| Latar gelap | Putih `#FFFFFF` | Teal muda `#5EEAD4` | `#121413` |
| Monokrom | Satu warna (hitam `#1C1B19` atau putih) | sama | bebas, kontras ≥ 3:1 |

Kontras yang diukur: batang `#0D9488` terhadap putih 3,74:1; `#5EEAD4` terhadap teal 3,7:1 dan terhadap latar gelap 12,51:1; putih terhadap teal 5,47:1.

## 3. Varian dan berkas

| Varian | SVG (`logo/`) | PNG transparan (`png/`) |
|---|---|---|
| Tanda (master) | `studyflow-mark.svg` | `mark/studyflow-mark-{256,512,1024}.png` |
| Tanda di latar gelap | `studyflow-mark-on-dark.svg` | `mark/studyflow-mark-on-dark-*.png` |
| Tanda monokrom | `studyflow-mark-mono-black.svg`, `-mono-white.svg` | `mark/studyflow-mark-mono-*-*.png` |
| Horizontal (tanda + kata) | `studyflow-horizontal.svg` | `horizontal/studyflow-horizontal-{800,1600}.png` |
| Horizontal gelap / monokrom | `studyflow-horizontal-on-dark.svg`, `-mono-black.svg`, `-mono-white.svg` | `horizontal/…` |
| Ikon aplikasi | `studyflow-app-icon.svg`, `-dark.svg` | `app-icon/studyflow-app-icon-{128..1024}.png` |

Platform:

| Platform | Berkas | Dipasang di aplikasi |
|---|---|---|
| Android (adaptive icon, Android 8+) | `android/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` (ikon bertema Android 13+), `values/ic_launcher_background.xml`, `mipmap-anydpi-v26/ic_launcher*.xml` | `androidApp/src/main/res/` |
| iOS | `ios/AppIcon.appiconset/` (1024 px, tanpa transparansi; Xcode 14+ membuat ukuran lain) | belum ada target iOS |
| Windows | `desktop/studyflow.ico` (16–256 px) | `desktopApp/icons/studyflow.ico` (installer) |
| Di dalam aplikasi | `compose/studyflow_mark*.xml`, `studyflow_app_icon.xml` | `shared/src/commonMain/composeResources/drawable/` (sidebar, ikon jendela desktop) |

Semua berkas dibuat ulang dengan:

```powershell
python -m uv run --no-project --with fonttools --with uharfbuzz python docs/brand/build_brand.py .
```

PNG dirender dari SVG lewat browser (lihat `exports.json` untuk daftar ukuran).

## 4. Tipografi

Kata **StudyFlow** memakai **Plus Jakarta Sans Bold** (OFL, font yang sama dengan aplikasi), sudah diubah menjadi kurva di SVG sehingga tidak bergantung font terpasang. Tinggi huruf kapital ≈ 74% tinggi tanda; jarak tanda ke kata = lebar batang F × 1,6.

## 5. Ruang aman dan ukuran minimum

- Ruang kosong di sekitar logo minimal **setinggi satu baris S** (±1/4 tinggi tanda) di semua sisi.
- Ukuran minimum: tanda **16 px** (ikon), logo horizontal **lebar 96 px** di layar atau **20 mm** di cetak.
- Ikon Android: tanda berada jauh di dalam lingkaran aman 66 dp dari kanvas 108 dp (skala 0,6; ±67% bagian ikon yang terlihat), sehingga utuh dan proporsional di masker bulat, kotak membulat, maupun squircle.

## 6. Yang tidak boleh

- Mengubah warna batang F menjadi warna yang kontras dengan teal (mis. merah, oranye), atau memberi gradien.
- Memutar, memiringkan, meregangkan, atau menambah bayangan/kontur.
- Memisahkan batang F dari lengannya atau menggabungkannya tanpa celah.
- Menaruh tanda berwarna di atas latar yang kontrasnya di bawah 3:1; gunakan varian monokrom.
- Mengganti kata dengan font lain.
