# MEGA PROMPT v2 — HERMES X: refactor bebas, rebuild Wear OS, pairing, rename, maintainer

> **Mode: YOLO — TANPA BATAS.**
> Kamu punya izin penuh dan eksplisit dari pemilik untuk mengubah **apa pun** di repo ini selama itu
> membuat aplikasi lebih benar, lebih cepat, lebih bersih, atau lebih lengkap: refactor besar,
> menghapus kode, mengganti pendekatan, menambah dependensi, mengubah struktur modul, menulis ulang
> file yang jelek. Kamu **tidak perlu minta izin** untuk perbaikan yang jelas bermanfaat.
>
> Satu-satunya hal yang tetap mengikat: **kejujuran laporan**. Jangan klaim sesuatu terverifikasi
> kalau kamu tidak menjalankan perintahnya. Jangan sembunyikan kegagalan. Jangan fabrikasi output.
> Kebebasan mengubah kode **bukan** kebebasan mengarang hasil.

> **Cara pakai:** tempel seluruh dokumen ini sebagai pesan pertama di chat baru. Isinya mandiri.

---

## 0. Mandat pemilik (baca ini dulu, ini yang paling penting)

Pemilik menulis, hampir verbatim:

> *"Kali ini saya membebaskan semuanya, agar aplikasinya semua sempurna… nama utamanya semua sementara
> ini rename ke **Hermes X**. Lalu maintainernya ganti ke **Michael**, sisipkan di beberapa code, atau
> code yang menyertakan itu. Kita akan teliti code satu persatu."*

Jadi tiga perintah eksplisit:

1. **Rename nama tampilan utama → `Hermes X`** (lihat §5 untuk cakupan tepatnya — ada jebakan besar
   di sana yang bisa merusak Data Layer kalau salah).
2. **Maintainer → `Michael`**, disisipkan di beberapa tempat di kode + metadata build + dokumentasi
   (lihat §6).
3. **Teliti kode satu per satu.** Pemilik akan memeriksa bareng. Artinya: setiap perubahan harus
   **bisa dijelaskan dan dipertanggungjawabkan**, bukan sekadar "sudah jalan". Kalau kamu mengubah
   sesuatu, siapkan alasan satu paragraf: apa yang salah, kenapa diperbaiki begitu, apa buktinya.

Dan mandat kerja yang lebih luas: **bebas melakukan apa pun yang bermanfaat** — refactor kalau tidak
efisien, rebuild Wear OS, pengecekan menyeluruh, optimasi, tambah fitur yang sudah disarankan,
basmi bug, buang sampah, cek dead code. **Semua fitur wajib dites dan dicoba.**

---

## 1. Aturan bukti (yang membedakan YOLO dari ngawur)

Kebebasan penuh **tidak** mengurangi standar bukti. Justru sebaliknya: makin bebas, makin harus bisa
dibuktikan.

1. **TARGET dulu, lalu kerjakan.** Sebelum menyentuh kode: tulis checklist 1–5 item — artefak persis
   apa yang harus ada, dan perintah apa yang membuktikan lulus/gagal.
2. **Bukti = output perintah yang kamu jalankan di sesi ini.** Bukan ingatan, bukan bacaan, bukan
   "sepertinya". Kalau sebuah langkah bergantung pada hasil, jalankan dan **baca output aslinya**.
3. **RED sebelum GREEN.** Untuk tiap bug: tunjukkan **gagal dulu**, perbaiki, tunjukkan **lulus**.
   Kalau tidak bisa menunjukkan RED, kamu belum membuktikan bug-nya ada — tulis "belum bisa
   direproduksi".
4. **Jangan fabrikasi, jangan mengarang angka.** Kalau butuh API key / device / pairing dan tidak ada,
   tulis **TIDAK TERVERIFIKASI** + apa yang dibutuhkan. Estimasi harus dilabeli estimasi.
5. **Perbaiki akar, jangan bungkam.** Dilarang `@Suppress`/lint-baseline untuk menyembunyikan temuan.
   Kalau terpaksa suppress, tulis alasan yang bisa diverifikasi + apa yang jadi tidak terdeteksi.
6. **Satu commit per fase**, pesan commit menjelaskan **apa yang salah dan kenapa diperbaiki begitu**.
7. **Bahasa laporan: Indonesia** untuk prosa; identifier/kode/path/error tetap **verbatim Inggris**.

**Peringatan khusus YOLO:** refactor besar itu sah, tapi **refactor tanpa test = merusak tanpa
jejak**. Sebelum membongkar sesuatu, pastikan ada test yang menangkap kerusakannya; kalau belum ada,
**tulis test-nya dulu**, baru bongkar. Ini bukan birokrasi — ini satu-satunya cara kamu tahu kamu
tidak merusak.

---

## 2. Konteks repo (fakta terverifikasi)

```
Repo:         C:\Users\Michael\Documents\Chatapp\Agora
Fork dari:    github.com/newo-ether/Agora      (remote: upstream)
Fork sendiri: github.com/michaelxdips/Agora    (remote: origin)
Branch:       main
HEAD saat prompt ini ditulis: 87dcfff9
```

**Toolchain (sudah terpasang — jangan unduh ulang):**
```bash
export JAVA_HOME='C:/Users/Michael/Documents/Chatapp/_tools/jdk21/jdk-21.0.12.1+1'
export ANDROID_HOME='C:/Users/Michael/Documents/Chatapp/_tools/sdk'
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_OPTS="-Xmx3g"        # WAJIB, tanpa ini daemon kena GC thrashing
```
```
build-tools 36.0.0   → apksigner
keystore: C:/Users/Michael/Documents/Chatapp/_tools/hermes-release.jks   (CN=Hermes Local)
```

**AVD:**
| AVD | API | ABI | Catatan |
|---|---|---|---|
| `hermes_wear5` | 34 | x86_64 | **Wear OS 5 — target utama, uji semua di sini** |
| `hermes_wear` | 30 | x86 | Wear OS 3 = minSdk. **32-bit** → connected test tidak bisa jalan (app module `abiFilters=arm64-v8a`) |
| `hermes_x86_64` | 36 | x86_64 | phone (Pixel 7) |
| `hermes_arm64` | 36 | arm64 | cadangan |

Boot (selalu pakai `-port` eksplisit; pernah kena `Running multiple emulators with the same AVD`):
```bash
./_tools/sdk/emulator/emulator.exe -avd hermes_wear5 -no-snapshot-load -no-boot-anim \
  -gpu swiftshader_indirect -port 5556
```
Kalau gagal karena AVD terpakai: kill `qemu-system-x86_64.exe` + `emulator.exe`, `rm -rf`
`$HOME/.android/avd/<avd>.avd/*.lock` (ada yang berbentuk **direktori**), lalu boot lagi.

**Skrip bukti yang sudah ada — PAKAI, jangan tulis ulang:**
| File | Fungsi |
|---|---|
| `_tools/mock_provider.py` | Mock endpoint OpenAI-compatible di host, toggle `/__fail` `/__ok`, port 8077 |
| `_tools/p0_autodrain_proof.py` | Bukti end-to-end offline queue → auto-drain |
| `_tools/wear_byok_drive.py` | Menyetir UI BYOK di jam (cari kontrol via bounds, ketik, tutup IME) |
| `scripts/touchpoint_guard.sh` | Guard file upstream + budget baris + marker |
| `scripts/upstream_sync.sh` | Sync upstream + guard (`SYNC_DRY_RUN=1` untuk dry-run) |

**Menjangkau host dari emulator: WAJIB `adb reverse`.**
```bash
adb -s emulator-5556 reverse tcp:8077 tcp:8077      # lalu pakai http://127.0.0.1:8077/v1
```
`10.0.2.2` **terbukti tidak bisa dijangkau** di mesin ini (Windows firewall). Seluruh uji jaringan
jam bergantung pada ini.

---

## 3. Baseline — WAJIB, sebelum mengubah apa pun

```bash
cd /c/Users/Michael/Documents/Chatapp/Agora
# env dulu (lihat §2)
git status --short && git log --oneline -1
bash scripts/touchpoint_guard.sh 2>&1 | tail -3
SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh 2>&1 | tail -3
./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest :wear:testDebugUnitTest --console=plain
./gradlew :app:assembleFdroidDebug :app:assemblePlayDebug :wear:assembleRelease --console=plain
```
**Baseline yang diharapkan (VERIFIKASI ULANG — jangan percaya angka ini):**
```
guard        PASS
unit         fdroid 2.533 · play 2.516 · wear 31   → 5.080, 0 gagal
APK          65.860.308 / 65.799.760 / 2.669.736 byte
connected    12/12 di hermes_wear5 + 12/12 di hermes_x86_64
```
Kalau berbeda → **selidiki dulu** sebelum lanjut.

---

## 4. TEMUAN KRITIS yang sudah dikonfirmasi (tangani lebih dulu)

Dua di antaranya membuat fitur yang **diklaim ada** sebenarnya **tidak bisa dipakai**. Konfirmasi
ulang dengan perintah di bawah, lalu perbaiki.

### 4.1 🔴 `SettingsWatchSetupPage` tidak pernah bisa dibuka
```bash
grep -c '"watch"' app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt          # → 0
grep -c 'SettingsCategory("watch"' app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt  # → 0
grep -rn "SettingsWatchSetupPage" app/src/main/java/ | grep -v "SettingsWatchSetupPage.kt:"     # → kosong
git log --oneline -S "SettingsWatchSetupPage" -- app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt  # → kosong
```
File `app/.../autopilot/wearsync/SettingsWatchSetupPage.kt` (137 baris) lengkap dan memanggil
`WatchSync.pushConfig` / `pushMemorySnapshot` / `connectedWatchCount` — **tapi tidak ada entry
`SettingsCategory("watch", …)` dan tidak ada cabang `"watch" ->`** di `SettingsScreen`. **Dead code
dari sudut pandang user.**

**Dampak:** `STATUS.md` ~baris 409 mengklaim *"pushed over the Data Layer (driven from Settings →
Watch setup)"*. **Klaim itu salah.** Perbaiki kode, lalu koreksi klaimnya (pola koreksi sudah ada di
repo — lihat blok *"Correction (Phase 8)"*).

### 4.2 🔴 Tombol "Pair with phone" di jam tidak punya handler
```bash
grep -rn "pairing\|pairRequest\|CapabilityClient\|MessageClient\|onCapabilityChanged" \
  app/src/main/java/com/newoether/agora/ wear/src/main/java/       # → KOSONG
grep -rc 'WatchSync' wear/src/main/java/                            # → 0
grep -c 'onDataChanged' wear/src/main/java/com/newoether/agora/wear/WearMainActivity.kt  # → 0
```
`WearSetupScreen.kt:146` → `onClick = { waitingForPhone = true; status = "Waiting for the phone…" }`.
`waitingForPhone` hanya **men-disable field** + teks statis. **Tidak ada permintaan pairing, tidak ada
capability, tidak ada apa pun ke phone.**

**Dampak:** dari **dua jalur setup yang diwajibkan pemilik**, hanya **BYOK** yang nyata. Jalur
pairing adalah tampilan tanpa mekanisme — dan halaman phone-nya bahkan tidak bisa dibuka.

### 4.3 🟠 UI jam tidak pernah diberi tahu saat config datang
`ConfigListenerService.onDataChanged` menulis config ke store, tapi **tidak ada notifikasi ke UI**
(`onDataChanged` di `WearMainActivity` = 0). Jadi walau config dikirim, jam **tidak otomatis pindah
ke layar chat** — user harus menutup & membuka app. Perbaiki dengan `StateFlow`/`SharedFlow`, **bukan
polling**.

### 4.4 🟠 Dokumentasi memuat klaim basi
Setelah 4.1–4.3 diperbaiki, **audit ulang setiap klaim** di `STATUS.md` + `AUDIT_REPORT.md`. Klaim
yang tidak bisa kamu buktikan di sesi ini → **koreksi atau tandai belum terverifikasi**. Jangan
dibiarkan.

---

## 5. RENAME → `Hermes X` (hati-hati: ada jebakan yang bisa merusak pairing)

### 5.1 Cakupan yang HARUS diubah

**Nama tampilan (label launcher + judul UI):**
```
app/src/fdroid/res/values/strings.xml              app_name = "Hermes"      → "Hermes X"
app/src/fdroid/res/values-*/strings.xml            (11 locale) app_name     → "Hermes X"
app/src/play/res/values/strings.xml                app_name                 → "Hermes X"
wear/src/main/res/values/strings.xml               app_name                 → "Hermes X"
wear/src/main/res/values/strings.xml               wear_speak_prompt        → "Ask Hermes X"
```

### 🚨 BUG YANG SUDAH ADA: flavor `play` menampilkan "Agora" di 12 locale

Terverifikasi:
```bash
ls -d app/src/play/res/values-*/    # → 0 direktori
ls -d app/src/main/res/values-*/    # → 12 direktori (upstream, semuanya app_name="Agora")
grep -n app_name app/src/main/res/values/strings.xml   # → <string name="app_name">Agora</string>
```
**Flavor `fdroid` punya 11 overlay locale** (`values-ar` … `values-zh-rTW`), **flavor `play` hanya
punya `values/`**. Artinya di 12 locale upstream, build **play** jatuh ke `app_name="Agora"` — jadi
user berbahasa Arab/Jerman/Spanyol/dst melihat app bernama **"Agora"**, bukan "Hermes"/"Hermes X".

**Perbaiki ini** dengan menambahkan overlay locale untuk flavor `play` yang mencerminkan yang sudah
dibuat `fdroid` (atau lebih baik: pindahkan `app_name` ke satu tempat kalau kamu bisa melakukannya
tanpa melanggar `SettingsResourceContractTest` — baca dulu test itu di
`app/src/test/.../SettingsResourceContractTest.kt`, ia menegakkan setiap locale di `main/res`
mencerminkan setiap key di `main/res/values`).

**Bukti wajib:** `aapt2 dump badging` pada APK **play** untuk beberapa locale, atau install di
emulator dengan locale diubah:
```bash
adb -s emulator-5554 shell "setprop persist.sys.locale de-DE; stop; start"   # atau cara yang benar
# lalu baca label lewat launcher / cmd package
```

**Teks UI yang menyebut nama:**
```
wear/.../WearMainActivity.kt:293    Text("Hermes")          → Text("Hermes X")
wear/.../WearSetupScreen.kt:88      Text("Hermes setup")    → Text("Hermes X setup")
app/.../AutopilotNotifier.kt:64     "Hermes adapted…"       → "Hermes X adapted…"
app/src/fdroid/res/values/strings.xml  (semua hermes_*_desc yang menyebut "Hermes")
```

**Metadata build (versionName saja — JANGAN versionCode):**
```
app/build.gradle.kts    versionName = "2.1.0"  →  "3.0.0-hermesx"   (atau pola yang kamu jelaskan)
wear/build.gradle.kts   versionName = "2.1.0"  →  "3.0.0-hermesx"
```
`versionCode` biarkan atau naikkan konsisten — **jelaskan pilihanmu**. Kalau naik, naikkan **kedua
module sama**, karena phone & watch berbagi `applicationId`.

**Dokumentasi:** `STATUS.md`, `AUDIT_REPORT.md`, `ROADMAP.md`, `NOTICE.md`, `README*` — sebut nama
produk sebagai **Hermes X**; sebut fork-nya sebagai *fork of Agora*.

### 5.2 🚨 YANG TIDAK BOLEH DIUBAH — ini bisa merusak Data Layer

```
applicationId = "com.hermes.app"     ← phone DAN wear. JANGAN DIUBAH.
namespace     = "com.newoether.agora" (+ ".wear")   ← package Kotlin. JANGAN DIUBAH.
```
**Alasan, dan ini keras:** Data Layer (phone↔watch) **mensyaratkan kedua app punya `applicationId`
yang sama**, dan pairing hanya jalan kalau keduanya juga ditandatangani dengan **sertifikat yang
sama**. Mengubah `applicationId` **mematikan pairing sepenuhnya** — fitur yang justru sedang kamu
bangun di §7. Mengubah `namespace` berarti memindahkan ratusan file Kotlin ke package baru; itu
**refactor raksasa tanpa manfaat produk** dan menghancurkan budget guard upstream. Nama tampilan
adalah yang diminta pemilik — **bukan** identifier teknis.

Kalau kamu yakin salah satunya harus berubah, **tanyakan dulu** dengan alasan teknis. Jangan
putuskan sendiri.

### 5.3 Yang juga TIDAK boleh diganti
- `hermes_release` / nama keystore & alias — kalau berubah, **APK update tidak bisa dipasang**
  di atas instalasi lama (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), dan pairing putus.
- Nama channel notifikasi (`CHANNEL_ID = "hermes_autopilot"`) — sudah dibuat di device user; ganti
  nama = channel duplikat.
- Path Data Layer (`/hermes/config`, `/hermes/memory`) — **kedua sisi harus cocok**; ganti satu sisi
  saja = pairing diam-diam rusak. Kalau kamu ganti, ganti **kedua** sisi + naikkan versi payload.
- Kunci string `hermes_*` di `strings.xml` — itu identifier resource, bukan nama tampilan.
- Nama kelas/file `Wear*`, `Persona*`, dsb — identifier kode.

### 5.4 Bukti rename
```bash
# 1. label launcher benar-benar berubah di APK
"C:/Users/Michael/Documents/Chatapp/_tools/sdk/build-tools/36.0.0/aapt2.exe" dump badging \
  app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk | grep -E "application-label|package:"
# 2. applicationId TETAP sama di kedua APK
unzip -p wear/build/outputs/apk/release/wear-release.apk AndroidManifest.xml >/dev/null 2>&1 || true
"C:/Users/Michael/Documents/Chatapp/_tools/sdk/build-tools/36.0.0/aapt2.exe" dump badging \
  wear/build/outputs/apk/release/wear-release.apk | grep "package:"
# 3. sisa "Hermes" tanpa "X" yang masih nama tampilan
grep -rn "Hermes" app/src/*/res/values*/strings.xml wear/src/main/res/values/strings.xml | grep -v "Hermes X"
# 4. install & lihat label di launcher (device)
adb -s emulator-5556 install -r wear/build/outputs/apk/release/wear-release.apk
adb -s emulator-5556 shell cmd package resolve-activity --brief com.hermes.app
```

---

## 6. MAINTAINER → `Michael`

Sisipkan di **beberapa** tempat, tidak hanya satu. Minimal:

**1. Konstanta kode yang bisa dibaca runtime** — buat satu sumber kebenaran, jangan sebar literal.
Contoh yang disarankan (sesuaikan dengan gaya repo):
```kotlin
// wear/.../WearBuildInfo.kt   (dan padanan di app/.../autopilot/ untuk phone)
object WearBuildInfo {
    /** Display name. Kept in one place so the rename cannot drift between screens. */
    const val PRODUCT_NAME = "Hermes X"

    /** Maintainer of this fork (upstream Agora is by the Agora authors; see NOTICE.md). */
    const val MAINTAINER = "Michael"

    /** Fork repository. */
    const val FORK_URL = "https://github.com/michaelxdips/Agora"
}
```
Lalu **pakai** konstanta itu di layar About / Debug panel / layar setup jam — jangan cuma didefinisikan
lalu menganggur (itu dead code baru).

**2. KDoc header di file inti milik fork.** Tambahkan baris maintainer di header KDoc file yang
memang milik Hermes (mis. `WearChatClient`, `WearConfig`, `WearOfflineQueue`, `WearCrypto`,
`WatchSync`, `PersonaStore`, `PersonaApplier`, `ReflectionEngine`, `MemoryApplier`, `AdaptationLog`).
**JANGAN** tambahkan ke file upstream (merusak budget guard).

**3. Metadata build:**
```kotlin
// app/build.gradle.kts + wear/build.gradle.kts
android {
    defaultConfig {
        // ...
        // Maintainer metadata, surfaced in About/Debug. Not a licence claim: upstream Agora stays
        // the upstream authors' work (see NOTICE.md).
    }
}
```
Kalau kamu ingin menaruhnya di `AndroidManifest.xml` lewat `manifestPlaceholders` — **ingat
manifest adalah file upstream terdaftar (budget 6 baris)**; naikkan budget dengan alasan tertulis
kalau perlu.

**4. `NOTICE.md`** — sudah menyebut Michael; perbarui supaya konsisten dengan nama produk baru dan
peran maintainer yang eksplisit.

**5. Layar About (phone) & Debug panel (jam)** — tampilkan:
```
Hermes X
Maintainer: Michael
Fork of Agora — github.com/newo-ether/Agora
github.com/michaelxdips/Agora
```
Ini yang membuat penyisipan **terlihat user**, bukan cuma komentar yang tak terbaca.

**Bukti:** jalankan app, buka About (phone) & Debug (jam), baca layarnya lewat `uiautomator dump`,
tempel teksnya di laporan.

---

## 7. Fase — urutan kerja

### Fase 0 — Baseline (§3). Lapor angkanya ke pemilik **sebelum** mengubah apa pun.

### Fase 1 — Rename + maintainer (§5, §6)
Kerjakan lebih dulu karena menyentuh resource & string; lebih mudah dilakukan sebelum perubahan lain
menumpuk. Verifikasi label di device.

### Fase 2 — Perbaiki 3 temuan kritis (§4)
1. `SettingsWatchSetupPage` bisa dibuka (entry + dispatch + naikkan budget `SettingsScreen.kt` dari
   `max=24` (terpakai 23) dengan alasan tertulis).
2. Pairing nyata (§8).
3. UI jam bereaksi saat config datang.

### Fase 3 — Rapikan repo & buang sampah
```bash
git ls-files | grep -E "/build/|\.apk$|\.log$|\.tmp$"       # artefak build
find . -maxdepth 2 -name "*.log" | head -40                  # log kerja
git status --ignored --short | head -40
```
Konteks: `.gitignore` upstream pakai `/build` (hanya root) + `app/.gitignore`, sehingga build tree
module **baru** (`wear/build/`) lolos — pernah 800+ artefak ter-commit. Sudah diperbaiki; **pastikan
masih begitu**.

Di `C:\Users\Michael\Documents\Chatapp\` ada banyak `_*.log` + `_tools/*.py` sisa kerja. Putuskan:
layak disimpan sebagai bukti → pindahkan ke `evidence/`; sampah → hapus. **Baca dulu, baru hapus.**

### Fase 4 — Dead code & refactor
```bash
for f in $(find app/src/main/java/com/newoether/agora/autopilot wear/src/main/java -name "*.kt"); do
  base=$(basename "$f" .kt)
  n=$(grep -rl "\b$base\b" app/src wear/src 2>/dev/null | grep -v "/$base.kt" | wc -l)
  [ "$n" -eq 0 ] && echo "ORPHAN: $f"
done
```
⚠️ **Scan ini menghasilkan false positive.** Contoh nyata: `WearListeners.kt` & `WearTheme.kt`
dilaporkan orphan padahal dipakai — `WearListeners` direferensikan dari **AndroidManifest** (nama
service), `WearTheme` dipakai lewat **nama fungsi** (`WearHermesTheme`). **Verifikasi manual sebelum
menghapus**, cek: kode, manifest, resource XML, reflection.

Cari juga: `TODO`/`FIXME`/`XXX`/`HACK`, import tak terpakai, fungsi privat tanpa pemanggil,
`@Suppress` tanpa alasan, konstanta tak terbaca, duplikasi yang bisa disatukan.

**Refactor bebas** — selama: (a) ada test yang menangkap kerusakan (kalau belum, tulis dulu), (b)
kamu bisa menjelaskan manfaatnya, (c) guard tetap PASS.

### Fase 5 — REBUILD penuh Wear OS
```bash
./gradlew :wear:clean --console=plain
./gradlew :wear:assembleDebug :wear:testDebugUnitTest --console=plain
./gradlew :wear:assembleRelease --console=plain          # buktikan R8+shrinking+lintVital lolos
"C:/Users/Michael/Documents/Chatapp/_tools/sdk/build-tools/36.0.0/apksigner.bat" \
  verify --print-certs wear/build/outputs/apk/release/wear-release.apk
```
Laporkan: ukuran APK (byte persis), DN sertifikat, apakah `lintVitalRelease` lolos.
Catatan: `lintVitalRelease` pernah gagal karena `play-services-basement` menarik
`androidx.fragment:1.1.0` di bawah floor 1.3.0 → perbaikannya **constraint ke `fragment:1.8.5`**,
**bukan** `lint.xml` suppress. Jangan mundur.

### Fase 6 — IMPLEMENTASI PAIRING (§8)

### Fase 7 — Fitur yang direkomendasikan (§9)

### Fase 8 — Audit dua-pass + rip off bug (§10)

### Fase 9 — Pengujian menyeluruh tanpa batas (§11)

### Fase 10 — Laporan + commit + push (§12)

---

## 8. PAIRING — fitur yang belum ada, dan cara membuktikannya

### 8.1 Sisi jam (`wear/`)
1. **Permintaan pairing nyata.** Kirim permintaan ke phone lewat Data Layer
   (`MessageClient` / `CapabilityClient` / `NodeClient`). **Verifikasi API-nya ada** dengan `javap`
   pada AAR sebelum memakai — jangan menebak signature:
   ```bash
   # contoh pola: ekstrak AAR, javap kelasnya
   unzip -o -q play-services-wearable-*.aar classes.jar && unzip -o -q classes.jar -d cls
   "…/jdk21/bin/javap.exe" -classpath cls com.google.android.gms.wearable.MessageClient | head -30
   ```
2. **Ganti tombol bohong** di `WearSetupScreen.kt` — tekan "Pair with phone" harus **mengirim
   permintaan nyata** dan menampilkan status **berdasarkan hasil sebenarnya** (terkirim / tidak ada
   phone / timeout), bukan teks statis.
3. **UI bereaksi saat config datang** (§4.3) — pakai `StateFlow`/`SharedFlow`, bukan polling.
4. **Status jujur:** phone terhubung? kapan config terakhir diterima? apa yang sedang terjadi?

### 8.2 Sisi phone (`app/.../autopilot/wearsync/`)
1. **Halaman Watch setup bisa dibuka** (§4.1).
2. **Tanggapi permintaan dari jam** — jam minta → phone kirim config. Jangan wajibkan user menekan
   tombol di phone saat jam sudah minta.
3. **Status nyata:** jumlah watch terhubung, hasil push terakhir (berhasil/gagal + alasan), apakah
   memory snapshot ikut terkirim.
4. **Ambil key dari provider yang sudah dikonfigurasi** — jangan minta user mengetik ulang API key
   kalau sudah ada di provider settings (cek `ProviderRegistry` / `SettingsRepository`).

### 8.3 Aturan yang tidak boleh dilanggar
- **Kredensial tidak boleh masuk log / notifikasi / AdaptationLog.** Preseden: `WearCrypto`
  (AES-256-GCM, key di Android keystore), `WatchSync` hanya mencatat nama kelas exception.
- **Config tetap harus lolos `WearConfig.isValid()`** di sisi jam — validasi tidak boleh dilewati
  hanya karena datang dari phone.
- **Versi payload** (`WearConfig.CURRENT_VERSION`) tetap diperiksa; payload tak dipahami →
  **ditolak**, bukan ditebak.
- **Jangan tambah dependensi baru** kalau `play-services-wearable` (19.0.0) cukup.
- **Jangan sentuh file upstream baru** kalau bisa dihindari.

### 8.4 Membuktikan pairing — INI BAGIAN TERSulit

Dua emulator **belum pernah di-pair**, dan Data Layer butuh kedua app terpasang dengan
`applicationId` + **sertifikat** yang sama (keduanya sudah begitu).

**Coba berurutan, laporkan mana yang berhasil:**
1. Jalankan phone AVD + wear AVD bersamaan; install `app-fdroid-debug.apk` (phone) +
   `wear-debug.apk` (watch).
2. Cek Data Layer benar-benar tersambung. Cara paling andal: panggil
   `WatchSync.connectedWatchCount(context)` dari kode, tampilkan di UI, **baca layarnya**.
3. **Kalau tidak bisa tersambung di emulator** (sangat mungkin — butuh Play Services benar-benar
   ter-pair), maka:
   - **JANGAN** klaim pairing terverifikasi.
   - Bangun **seam**: bungkus akses Data Layer di balik interface, lalu tulis test instrumented yang
     menyuntikkan implementasi palsu → buktikan **logika** pairing (permintaan terkirim, respons
     diproses, config divalidasi, UI bereaksi, kredensial tidak bocor).
   - Tulis di laporan: *"logika pairing terbukti dengan seam; transfer Data Layer antar-device belum
     terverifikasi — butuh device fisik / emulator ter-pair (HS4)"*.

**Preseden yang terbukti:** mock provider + `adb reverse` berhasil membuktikan seluruh jalur HTTP jam
end-to-end **tanpa API key asli**. Pakai pola yang sama untuk pairing kalau memungkinkan.

---

## 9. Fitur yang direkomendasikan (implementasi, bukan daftar)

Kerjakan berurutan. Jangan lompat ke P2 sebelum P0/P1 selesai **atau** dinyatakan gagal + alasannya.

### P0 — sisa pekerjaan offline queue
| Fitur | Status | Yang harus dilakukan |
|---|---|---|
| Auto-drain saat app dibuka | ✅ **SUDAH** (`launch drain: delivered=1, queue now 0`) | jangan rusak; **tambah test regresi** |
| `showResult` (jawaban lama tak menimpa baru) | ✅ **SUDAH** | tambah test regresi |
| **UI retry/discard pertanyaan ditahan** | ❌ **BELUM** | badge `"$queued held offline"` read-only. Tambah: daftar pertanyaan ditahan, tombol kirim ulang paksa, tombol buang, konfirmasi saat entri di-drop otomatis (sekarang hanya masuk log) |

### P1 — surface Wear OS yang belum ada
| Fitur | Bukti belum ada | Catatan |
|---|---|---|
| **Tile** (`SuspendingTileService` + ProtoLayout) | `grep -rn "TileService\|ProtoLayout\|Glance" wear/src` → **0 hit** | Paling berguna: tombol tap-to-talk. **Verifikasi API dengan `javap`** sebelum memakai; tambah dependency hanya kalau perlu |
| **Complication** data source | sama → 0 hit | Tampilkan: sudah dikonfigurasi? ada yang ditahan? |
| **Ongoing notification** jawaban lama | `readTimeout(60s)` vs layar mati ~5–15 s | Pakai `OngoingActivity` + notifikasi; tanpa ini app terlihat hang |

### P2 — angka jujur & kontinuitas
| Fitur | Yang harus dilakukan |
|---|---|
| Parsing `usage` dari provider | `WearChatClient.parseContent` hanya ambil `content`. Tambah `usage.prompt_tokens`/`completion_tokens`, tampilkan di Debug, **ganti estimasi ~4 char/token dengan pengukuran** kalau provider melaporkan. Kalau tidak → tetap estimasi & **labeli estimasi** |
| Riwayat percakapan di jam | sekarang satu `answer` string di memori; restart = hilang. Simpan N percakapan terakhir (file JSON, pola `WearOfflineQueue` — **jangan Room**) |
| `readTimeout` lebih ketat | 60 s lebih lama dari interaksi jam mana pun |

### Yang TIDAK boleh ditambahkan ke jam
Streaming response (sudah beralasan ditolak: layar kecil, jawaban pendek, koneksi yang harus dijaga
saat tangan turun), Room, llama.cpp, conversation trees, MCP, image generation, sandbox, skills.
Kalau kamu berpikir salah satunya perlu → **tanyakan dulu**.

---

## 10. Audit dua-pass + rip off bug

### Pass A — statis
```bash
# 1. CancellationException ditelan (pernah ketemu 8 tempat)
grep -rn "catch (.*Exception)\|runCatching\|recoverCatching" \
  app/src/main/java/com/newoether/agora/autopilot wear/src/main
#   → tiap catch WAJIB punya cabang CancellationException yang rethrow, kecuali alasannya ditulis
# 2. runBlocking di jalur UI
grep -rn "runBlocking" app/src/main/java/com/newoether/agora/autopilot wear/src/main
# 3. !!/lateinit tanpa guard, GlobalScope, secret hardcoded
grep -rn "!!\|GlobalScope" app/src/main/java/com/newoether/agora/autopilot wear/src/main
grep -rniE "(api[_-]?key|token|secret|password)\s*=\s*\"[A-Za-z0-9_-]{12,}\"" app/src/main wear/src
# 4. TODO/FIXME/@Suppress tanpa alasan/import tak terpakai
grep -rn "TODO\|FIXME\|XXX\|HACK" app/src/main/java/com/newoether/agora/autopilot wear/src
```

### Pass B — adversarial (JALANKAN, jangan baca)
| Serangan | Cara membuktikan |
|---|---|
| Queue hilang saat proses dibunuh di tengah tulis | matikan paksa saat menulis → buka lagi → file utuh |
| Config rusak / tak bisa didekripsi | rusakkan file config → app tidak crash, pesan jelas |
| Base URL aneh | `https://x.com/v1/`, `https://x.com/chat/completions`, `http://127.0.0.1:11434`, tanpa skema |
| Respons provider tidak terduga | `choices` kosong, `content` kosong, HTML (captive portal), JSON rusak, 401/404/500/503, timeout |
| Pertanyaan sangat panjang | ketik melebihi lebar layar → tidak terpotong diam-diam |
| Snapshot memory sangat besar | 400+ baris → truncation **terlihat**, bukan senyap |
| Persona bocor ke jam | `WearCoreContext` membuang **seluruh blok**, bukan hanya baris marker |
| Kredensial bocor | `grep` file config, logcat, notifikasi, AdaptationLog → **0 temuan** |
| Jam tanpa phone | mode pesawat → BYOK tetap jalan penuh |
| Jam tanpa config | hapus config → app minta setup, tidak crash |
| **Rename tidak merusak Data Layer** | install phone+watch, `connectedWatchCount` masih > 0 |

---

## 11. Pengujian menyeluruh — TANPA BATAS

### 11.1 Test otomatis
```bash
./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest :wear:testDebugUnitTest --console=plain
adb devices
./gradlew :app:connectedFdroidDebugAndroidTest --console=plain
# stabilitas: 5x berturut-turut; satu flake = satu temuan
for i in 1 2 3 4 5; do echo "=== RUN $i ==="; \
  ./gradlew :app:testFdroidDebugUnitTest :wear:testDebugUnitTest --rerun-tasks --console=plain | \
  grep -E "BUILD SUCCESSFUL|BUILD FAILED|FAILED"; done
```

### 11.2 Baca XML, jangan percaya ringkasan Gradle saja
```python
import glob, re
for label, pat in [("fdroid", r"app/build/test-results/testFdroidDebugUnitTest/*.xml"),
                   ("play",   r"app/build/test-results/testPlayDebugUnitTest/*.xml"),
                   ("wear",   r"wear/build/test-results/testDebugUnitTest/*.xml")]:
    t = f = e = 0
    for x in glob.glob(pat):
        s = open(x, encoding="utf-8", errors="replace").read(3000)
        m = re.search(r'tests="(\d+)"\s+skipped="\d+"\s+failures="(\d+)"\s+errors="(\d+)"', s)
        if m: t += int(m.group(1)); f += int(m.group(2)); e += int(m.group(3))
    print(f"{label}: {t} tests, {f} failures, {e} errors")
```

### 11.3 Cakupan test yang WAJIB ditambahkan
- **Regresi auto-drain** (P0 selesai) — kalau tidak ada test, akan rusak lagi.
- **Jalur pairing** (seam, §8.4).
- **`usage` parsing** kalau kamu implementasi P2.
- **UI retry/discard** kalau kamu implementasi P0 sisa.
- **Rename**: test yang memastikan `applicationId` **tidak berubah** (regresi paling mahal).

### 11.4 Matriks device
| Device | API | ABI | Yang diuji |
|---|---|---|---|
| `hermes_wear5` | 34 | x86_64 | **semua**: release+debug, BYOK, pairing, chat, voice, queue, Tile, complication, notification, rename, maintainer |
| `hermes_wear` | 30 | x86 | release install, launch, BYOK. **Connected test TIDAK BISA** (app `abiFilters=arm64-v8a` vs image 32-bit) — celah nyata; tutup kalau bisa, laporkan kalau tidak |
| `hermes_x86_64` | 36 | x86_64 | phone: Settings → Watch setup, pairing, push config/memory, rename, About |
| `hermes_arm64` | 36 | arm64 | kalau memungkinkan |

### 11.5 Uji UI di jam (pola terbukti)
```bash
adb -s emulator-5556 shell uiautomator dump /sdcard/ui.xml
adb -s emulator-5556 pull /sdcard/ui.xml "$LOCALAPPDATA/Temp/ui.xml"
# parse bounds di Python, tap koordinat tengahnya
```
⚠️ **Peringatan yang sudah DUA KALI terjadi:** review screenshot lewat vision **salah membaca scroll
fold sebagai "clipping"**. Untuk **geometri/layout** → **selalu** pakai bounds `uiautomator` dan
hitung jarak dari pusat lingkaran (radius 192 px di layar 384×384). Vision berguna untuk **warna,
surface, tipografi** — bukan geometri.

### 11.6 Uji jaringan jam end-to-end
```bash
python _tools/mock_provider.py                       # host, port 8077
adb -s emulator-5556 reverse tcp:8077 tcp:8077
# di jam: base URL → http://127.0.0.1:8077/v1
python _tools/p0_autodrain_proof.py
```
Mock mendukung `/__fail` (503 → ditahan) & `/__ok`. **Jangan pakai `10.0.2.2`.**

### 11.7 Yang TIDAK bisa diuji tanpa kredensial — nyatakan
- Perilaku provider **spesifik** (OpenAI/Anthropic/Groq/…) → butuh API key asli (**HS2**).
- Kualitas jawaban model nyata.
- Transfer Data Layer **antar-device** kalau emulator tak bisa di-pair (**HS4**).

---

## 12. Laporan akhir (wajib)

`append` ke `AUDIT_REPORT.md` + perbarui `STATUS.md`.

**12.1 Tabel temuan**
| # | Sev | Temuan | Cara ditemukan | Status |
|---|---|---|---|---|
Severity: **CRITICAL** (build rusak / data hilang / kredensial bocor / fitur tak bisa dipakai),
**HIGH** (fitur diam-diam tidak melakukan yang dijanjikan), **MEDIUM**, **LOW**.

**12.2 Tabel gate** — unit 3 suite, connected per device, 3 APK (ukuran byte), guard, sync dry-run,
tanda tangan APK, **status pairing + alasan**, **label launcher setelah rename**.

**12.3 "Yang masih terbuka, jujur"** — daftar yang **tidak** terverifikasi + apa yang dibutuhkan.

**12.4 "Pola yang layak dinamai"** — preseden yang sudah ada:
- *"works on the dev machine, impossible on the device"* (curl tidak ada di Android; test JVM tidak
  bisa menangkapnya).
- *"the block is the unit, not the marker line"* (menangani marker sebagai baris → dua bug di dua
  module berbeda).
- *"screenshot review reads a scroll fold as clipping"* (dua kali).
- *"validasi bilang boleh, platform bilang tidak"* (`WearConfig.isValid()` menerima `http://127.0.0.1`
  tapi `networkSecurityConfig` tidak ada → `UnknownServiceException`).

### 12.5 Commit & push
Satu commit per fase, pesan menjelaskan **apa yang salah & kenapa**.
Jangan commit artefak build. Push ke `origin/main`, laporkan hash.

### 12.6 Daftar perubahan untuk review
Di akhir, tulis bagian **"Perubahan sesi ini"** di `AUDIT_REPORT.md`: satu baris per perubahan, dengan
format `file — apa yang salah — bukti`. Ini yang akan pemilik telusuri saat meneliti kode satu per
satu (§12A).

---

## 12A. "KITA AKAN TELITI CODE SATU PERSATU" — protokol review

Pemilik akan memeriksa bareng, file per file. Siapkan repo supaya itu **mungkin**, dan siapkan dirimu
untuk **mempertanggungjawabkan setiap perubahan**.

### 12A.1 Aturan untuk setiap perubahan
Untuk tiap perubahan yang kamu buat, siapkan **satu paragraf** yang menjawab:
1. **Apa yang salah** sebelum perubahan (dengan bukti: output perintah, stack trace, nomor baris).
2. **Kenapa diperbaiki begitu** — dan apa alternatif yang kamu tolak beserta alasannya.
3. **Apa buktinya** setelah perubahan (perintah + output).

Kalau kamu tidak bisa menjawab ketiganya, **jangan buat perubahannya**. Itu bukan perubahan, itu
tebakan.

### 12A.2 Buat peta kode agar bisa diteliti satu per satu
Tulis `CODE_MAP.md` di root repo: daftar **setiap** file milik Hermes, satu baris per file:

| File | Baris | Peran | Status | Perlu diteliti? |
|---|---|---|---|---|

Kolom **Status**: `baru di sesi ini` / `diubah` / `tidak diubah` / `kandidat hapus`.
Kolom **Perlu diteliti?**: `ya — alasan` kalau kamu sendiri merasa bagian itu lemah atau belum
terverifikasi. **Kejujuran di kolom ini lebih berharga daripada tabel yang terlihat rapi.**

Cakupan minimal: `wear/src/**` (12 file `.kt` + manifest + resource),
`app/.../autopilot/**` (26 file), `scripts/**`, `_tools/**`.

### 12A.3 Tandai perubahan di kode
- Setiap perubahan di **file upstream** wajib punya marker `HERMES INTEGRATION POINT` (guard
  menegakkan ini).
- Untuk file milik fork, kalau kamu **mengubah** sesuatu di sesi ini, tambahkan komentar singkat
  `// HERMES X: <alasan>` di titik perubahan **hanya kalau alasannya tidak jelas dari kode**. Jangan
  mengomentari hal yang sudah jelas — itu noise yang membuat review lebih lambat.
- **Jangan** menambah marker ke file upstream tanpa mendaftarkannya.

### 12A.4 Urutan review yang disarankan
Sajikan ke pemilik dalam urutan ini, karena ini urutan ketergantungan:
1. `wear/src/main/AndroidManifest.xml` + `network_security_config.xml` (fondasi keamanan jaringan)
2. `WearConfig.kt` → `WearCrypto.kt` → `WearChatClient.kt` (rantai kredensial → kripto → jaringan)
3. `WearOfflineQueue.kt` → `WearCoreContext.kt` (state yang bertahan)
4. `WearMainActivity.kt` → `WearSetupScreen.kt` → `WearTheme.kt` (UI)
5. `WearListeners.kt` (penerima Data Layer)
6. `app/.../wearsync/WatchSync.kt` → `SettingsWatchSetupPage.kt` (sisi phone)
7. `app/.../autopilot/**` (autopilot, persona, refleksi)

### 12A.5 Yang membuat review cepat atau lambat
| Mempercepat | Memperlambat |
|---|---|
| Diff kecil & fokus, satu alasan per commit | Refactor raksasa yang mencampur banyak alasan |
| Test yang membuktikan perilaku | "Sudah saya tes manual" tanpa bukti |
| Komentar yang menjelaskan **kenapa** | Komentar yang mengulang **apa** |
| Menghapus kode mati | Menambah abstraksi yang belum dipakai |
| Melaporkan yang belum terverifikasi | Mengklaim lengkap padahal belum |

---

## 12B. Checklist izin YOLO — boleh vs jangan

### ✅ BOLEH tanpa bertanya
- Refactor apa pun **yang ada test-nya** (kalau belum ada, tulis dulu)
- Menghapus kode mati setelah **verifikasi manual**
- Mengganti pendekatan yang jelek, menghapus duplikasi
- Menambah test
- Memperbaiki bug
- Menambah dependensi **yang benar-benar dipakai** (dan jelaskan kenapa stdlib/OkHttp tidak cukup)
- Menulis ulang file yang kacau
- Mengubah struktur internal `wear/` dan `app/.../autopilot/` (keduanya milik fork)
- Menghapus file sampah setelah dibaca
- Memperbaiki dokumentasi yang salah

### ⚠️ BOLEH tapi WAJIB lapor
- Menambah file upstream baru ke `UPSTREAM_TOUCHPOINTS.md`
- Menaikkan budget baris di `UPSTREAM_TOUCHPOINTS.md` (tulis alasan di registry table)
- Mengubah `versionCode` (naikkan **kedua** module sama)
- Mengubah `readTimeout`/timeout lain
- Mengubah format file yang dipersist (queue, config, memory cache) — **sebutkan migrasinya**
- Menambah permission di manifest

### 🛑 JANGAN — tanyakan dulu
- `applicationId` (mematikan pairing)
- `namespace` / package Kotlin (refactor raksasa tanpa manfaat produk)
- Nama/alias keystore (APK update jadi tidak bisa dipasang; pairing putus)
- Path Data Layer (`/hermes/config`, `/hermes/memory`) — kalau diganti, **kedua sisi** + naikkan versi
- Nama channel notifikasi (channel duplikat di device user)
- Menambahkan ke jam: streaming, Room, llama.cpp, conversation trees, MCP, image gen, sandbox, skills
- Menghapus test yang ada (kecuali kamu bisa membuktikan test itu salah)
- Menghapus/mengubah `LICENSE` atau klaim lisensi upstream
- `lint.xml` suppress untuk membungkam temuan nyata

### 🔴 TIDAK PERNAH
- Fabrikasi output perintah
- Mengklaim "terverifikasi" tanpa menjalankan perintahnya
- Menyembunyikan kegagalan dari laporan
- Meng-commit artefak build / log / secret
- Memasukkan API key / token / kredensial ke kode, test, atau dokumentasi

---

## 13. Definition of Done

- [ ] **Rename ke `Hermes X`** terpasang di label launcher (phone + jam), dibuktikan `aapt2 dump badging`
- [ ] **`applicationId` TETAP `com.hermes.app`** di kedua APK — dibuktikan, bukan diasumsikan
- [ ] **Maintainer `Michael`** muncul di: konstanta kode, KDoc file inti fork, NOTICE.md, dan
      **terlihat di layar** (About phone / Debug jam) — dibuktikan dengan `uiautomator dump`
- [ ] `SettingsWatchSetupPage` **bisa dibuka** dari Settings — dibuktikan di device
- [ ] **Pairing nyata** atau seam teruji + pernyataan jujur (§8.4)
- [ ] **UI jam bereaksi saat config datang** (tanpa polling) — dibuktikan
- [ ] **BYOK masih jalan** end-to-end — dibuktikan (regresi dicegah)
- [ ] **Auto-drain masih jalan** + test regresi ada
- [ ] P0 sisa / P1 / P2 → selesai **atau** dilaporkan gagal + alasan
- [ ] `touchpoint_guard.sh` → **PASS**
- [ ] `upstream_sync.sh --dry-run` → bersih (atau konflik dilaporkan dengan bukti)
- [ ] Unit 3 suite → **0 gagal, 0 error**; angka dilaporkan
- [ ] Connected test → dijalankan di **semua** device yang mendukung
- [ ] 3 APK build sukses; release bertanda tangan; `apksigner verify` lolos; DN dilaporkan
- [ ] Dead code dipindai, hasil **diverifikasi manual** (bukan dihapus karena false positive)
- [ ] Tidak ada artefak build/log/temp ter-commit
- [ ] `STATUS.md` + `AUDIT_REPORT.md` tidak memuat klaim yang tak bisa dibuktikan
- [ ] Semua klaim bisa ditelusuri ke perintah yang benar-benar dijalankan
- [ ] Commit + push selesai, hash dilaporkan

---

## 14. Jebakan yang sudah memakan korban — HINDARI

1. **`ProcessBuilder("curl", …)` tidak akan pernah jalan di Android.** Android tidak punya curl. Pakai
   OkHttp. (Bug ini membuat fitur "Check for persona updates" mustahil jalan.)
2. **Test JVM tidak bisa menangkap bug yang bergantung platform.** Bug curl lolos 6/6 di JVM karena
   host Windows punya curl. Kalau bug-nya "tidak ada di Android" → test **harus instrumented**.
3. **`runCatching`/`catch (Exception)` menelan `CancellationException`.** Selalu tambah cabang
   rethrow, atau kamu melanjutkan kerja setelah dibatalkan.
4. **Membaca state Compose yang baru di-assign di `LaunchedEffect` yang sama = selalu false.** Pakai
   `val` lokal. (Ini membuat auto-drain tidak pernah jalan.)
5. **Validasi bilang "boleh" tapi platform bilang "tidak".** `WearConfig.isValid()` menerima
   `http://127.0.0.1` tapi `networkSecurityConfig` tidak ada → `UnknownServiceException`. **Selalu uji
   jalur yang kamu validasi.**
6. **`run-as` tidak bisa membaca data APK release** (`package not debuggable`) → pembacaan
   mengembalikan 0 dan terlihat seperti bug. Pakai APK **debug** untuk verifikasi yang butuh `run-as`.
7. **Boot emulator tanpa `-port` eksplisit** → `Running multiple emulators with the same AVD`. Kill +
   hapus lock (**ada yang direktori**, pakai `rm -rf`) → boot ulang.
8. **`--rerun-tasks` WAJIB** untuk verifikasi "bersih". Tanpa itu Gradle bisa bilang SUCCESSFUL hanya
   karena task-nya up-to-date.
9. **Piping ke `grep`/`head` menyembunyikan exit code sebenarnya.** Pakai `${PIPESTATUS[0]}` atau
   jalankan tanpa pipe saat exit code penting.
10. **Hasil proses background bisa sudah basi.** Tiga kali terjadi (compile error, signature clash,
    guard failure) — semuanya sudah diperbaiki sebelum notifikasinya sampai. **Selalu verifikasi ulang
    dengan state sekarang.**
11. **Mengubah `applicationId` = mematikan pairing.** Ini jebakan paling mahal di prompt ini. Nama
    tampilan yang diminta pemilik, bukan identifier teknis.
12. **Refactor tanpa test = merusak tanpa jejak.** Tulis test-nya dulu, baru bongkar.

---

## 15. Mulai dari sini

1. Baca `AGENTS.md`, `STATUS.md`, `AUDIT_REPORT.md`, `UPSTREAM_TOUCHPOINTS.md`, `NOTICE.md` sampai paham.
2. **Fase 0 (baseline)** — laporkan angkanya ke pemilik **sebelum** mengubah apa pun.
3. **Fase 1 (rename + maintainer)** — kerjakan lebih dulu; verifikasi label di device.
4. **Fase 2 (3 temuan kritis)** — §4.1 paling mudah & berdampak besar.
5. **Fase 3–5** (rapikan → refactor → rebuild wear).
6. **Fase 6 (pairing)** — paling sulit & paling berharga.
7. **Fase 7 → 8 → 9 → 10**.
8. Kalau ada keputusan desain yang tidak jelas (mis. cara pairing yang benar), **tanyakan** — jangan
   menebak dan jangan mengklaim sudah selesai.

**Pemilik lebih menghargai laporan jujur "ini belum terverifikasi, ini kenapa" daripada klaim lengkap
yang tidak bisa dibuktikan.**
