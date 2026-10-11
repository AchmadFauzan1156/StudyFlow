# Memasang StudyFlow

StudyFlow tersedia untuk **Windows** dan **Android**. Unduh file terbaru dari halaman Releases:

**https://github.com/AchmadFauzan1156/StudyFlow/releases/latest**

| Perangkat | File | Ukuran kira-kira |
|---|---|---|
| Windows 10/11 (64-bit), dengan installer | `StudyFlow-<versi>.exe` | 80 MB |
| Windows tanpa instalasi (portabel) | `StudyFlow-<versi>-windows.zip` | 80 MB |
| Android 8.0 ke atas | `StudyFlow-<versi>.apk` | 17 MB |

## Windows

1. Unduh `StudyFlow-<versi>.exe`, lalu klik dua kali.
2. Windows mungkin menampilkan **"Windows protected your PC"** (SmartScreen) karena installer belum ditandatangani sertifikat berbayar. Klik **More info → Run anyway**.
3. Ikuti wizard. Instalasi berlaku untuk akun Windows yang sedang dipakai dan **tidak butuh hak administrator**.
4. Buka **StudyFlow** dari Start Menu atau shortcut di desktop.

Versi baru dipasang dengan cara yang sama; installer menggantikan versi lama dan data tetap ada.

**Versi portabel:** ekstrak `StudyFlow-<versi>-windows.zip` ke folder mana saja, lalu jalankan `StudyFlow\StudyFlow.exe`. Java sudah termasuk di dalamnya.

**Menghapus:** Settings → Apps → Installed apps → StudyFlow → Uninstall.

## Android

1. Buka link Releases di HP, unduh `StudyFlow-<versi>.apk`.
2. Saat membuka file, Android meminta izin **"Install unknown apps"** untuk browser atau aplikasi file. Izinkan, lalu kembali dan ketuk **Install**.
3. Jika Play Protect memperingatkan aplikasi tidak dikenal, pilih **More details → Install anyway**.

Versi baru bisa dipasang langsung di atas versi lama tanpa menghapusnya.

## Memakai aplikasi

- **Tanpa akun (mode lokal):** semua catatan, mata kuliah, dan tugas tersimpan di perangkat dan bisa dipakai tanpa internet.
- **Dengan akun:** ketuk kartu *Mode lokal* di Beranda (atau buka Pengaturan) → **Daftar** dengan email dan kata sandi (minimal 8 karakter). Data lalu tersinkron antar perangkat yang memakai akun yang sama, dan fitur AI (rangkuman, kuis, tanya materi dari dokumen) bisa dipakai.

### Yang perlu diketahui

- **Server bisa "tidur".** Server gratis berhenti sementara saat tidak ada yang memakai. Sinkronisasi atau permintaan AI pertama setelahnya bisa butuh beberapa detik lebih lama; perubahan tetap tersimpan di perangkat dan dikirim otomatis.
- **Batas AI harian.** Fitur AI memakai kuota gratis, jadi jumlah permintaan per pengguna per hari dibatasi. Jika kuota habis, coba lagi besok.
- **Materi yang dikirim ke AI.** Saat memakai fitur AI, isi catatan atau dokumen yang dipilih dikirim ke penyedia AI (Google Gemini). Aplikasi menampilkan pemberitahuannya sebelum materi dikirim. Jangan mengunggah dokumen yang berisi data pribadi sensitif.

## Jika ada masalah

| Gejala | Coba |
|---|---|
| Sinkronisasi lama atau gagal | Pastikan internet aktif, tunggu sebentar (server mungkin sedang bangun), lalu buka ulang aplikasi. |
| Tidak bisa masuk | Periksa email dan kata sandi; daftar dulu jika belum punya akun. |
| Fitur AI tidak tersedia | Fitur AI hanya untuk akun yang sudah masuk. Jika muncul pesan batas harian, coba lagi besok. |
| Installer Windows diblokir | Lihat langkah SmartScreen di atas. |

Laporkan masalah lain lewat halaman **Issues** di repositori GitHub.
