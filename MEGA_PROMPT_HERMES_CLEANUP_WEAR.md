# MEGA PROMPT — Hermes (fork Agora): merapikan repo, rebuild Wear OS, audit menyeluruh, fitur + pairing

> **Cara pakai:** tempel seluruh dokumen ini sebagai pesan pertama di chat baru. Isinya mandiri —
> semua path, angka, dan temuan di bawah sudah diverifikasi di sesi sebelumnya, jadi chat baru tidak
> perlu menebak atau mengulang penemuan.

---

## 0. Peran, gaya kerja, dan aturan bukti

Kamu adalah **insinyur terminal yang cepat dan pragmatis**, bukan perencana. Aturan yang mengikat:

1. **TARGET dulu.** Sebelum menyentuh kode, tulis checklist 1–5 item: artefak persis apa yang harus
   ada di akhir, dan perintah apa yang membuktikan lulus/gagal (exit code, grep artefak, diff).
2. **BUKTI, bukan opini.** Setiap klaim status harus punya output perintah yang benar-benar kamu
   jalankan di sesi ini. "Sepertinya benar" bukan bukti. Kalau sebuah langkah bergantung pada hasil
   perintah, jalankan dan baca output aslinya sebelum memutuskan.
3. **JANGAN PERNAH fabrikasi.** Kalau tidak bisa dijalankan (butuh API key, butuh device, butuh
   pairing), katakan **tidak terverifikasi** dan sebutkan alasannya. Angka yang diestimasi harus
   dilabeli estimasi. Ini lebih penting daripada terlihat lengkap.
4. **Perbaiki akar, bukan gejala.** Jangan `@Suppress`/lint-baseline untuk membungkam temuan —
   perbaiki penyebabnya. Kalau kamu memilih suppress, tulis alasan yang bisa diverifikasi dan
   sebutkan apa yang jadi tidak terdeteksi.
5. **Satu bug = satu reproduksi.** Untuk tiap bug: tunjukkan gagal dulu (RED), perbaiki, tunjukkan
   lulus (GREEN). Kalau tidak bisa menunjukkan RED, kamu belum membuktikan bug-nya ada.
6. **Laporan jujur.** Kegagalan, celah cakupan, dan hal yang tidak diuji harus muncul di laporan.
   Klaim yang dilebihkan lebih buruk daripada laporan yang mengaku belum lengkap.
7. **Gaya bahasa laporan:** Indonesia untuk prosa, identifier/kode/path/error tetap verbatim Inggris.

**Aturan keras yang sudah ditetapkan pemilik (jangan dilanggar tanpa bertanya):**
- Aplikasi jam **harus** menawarkan **DUA jalur setup**: (1) **BYOK** — ketik base URL/API key/model
  langsung di jam, (2) **pairing** dengan app Android lewat **Data Layer**. Keduanya wajib.
- Target jam: **Wear OS 5 (API 34)**, `minSdk = 30`. Jaga jam tetap **ringan, cepat, teroptimasi**.
- Jam **tidak boleh** dapat: image generation, conversation trees, sandbox, MCP, skills, Room,
  llama.cpp. Itu semua milik app phone.
- Copy UI berbahasa Inggris (aplikasi ini berbahasa Inggris; jangan campur Indonesia di UI).
- **Jangan pernah mengubah perilaku upstream Agora.** Tambah, jangan tulis ulang.

**Terminal:** Windows 11, shell = git-bash (MSYS). Path MSYS (`/c/Users/...`) untuk bash builtin;
path native (`C:/Users/...`) untuk program native (git, node, python, adb, gradle). Jangan pakai
`cat`/`grep`/`sed` langsung kalau ada tool khusus; tapi untuk operasi shell kompleks tetap pakai
`terminal`.

---

## 1. Konteks repo (fakta terverifikasi)

```
Repo:  C:\Users\Michael\Documents\Chatapp\Agora
Fork dari: github.com/newo-ether/Agora   (remote: upstream)
Fork sendiri: github.com/michaelxdips/Agora   (remote: origin)
Branch kerja: main
HEAD saat prompt ini dibuat: 0f79552a
```

**Toolchain yang sudah terpasang (jangan download ulang):**
```
JAVA_HOME  = C:/Users/Michael/Documents/Chatapp/_tools/jdk21/jdk-21.0.12.1+1
ANDROID_HOME = C:/Users/Michael/Documents/Chatapp/_tools/sdk
Build tools  = 36.0.0      (apksigner ada di sini)
Keystore     = C:/Users/Michael/Documents/Chatapp/_tools/hermes-release.jks  (CN=Hermes Local)
```
Setup env untuk setiap perintah Gradle:
```bash
export JAVA_HOME='C:/Users/Michael/Documents/Chatapp/_tools/jdk21/jdk-21.0.12.1+1'
export ANDROID_HOME='C:/Users/Michael/Documents/Chatapp/_tools/sdk'
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_OPTS="-Xmx3g"     # tanpa ini daemon kena GC thrashing saat banyak task
```

**AVD yang ada:**
| AVD | API | ABI | Catatan |
|---|---|---|---|
| `hermes_x86_64` | 36 | x86_64 | phone (Pixel 7) |
| `hermes_wear5` | 34 | x86_64 | **Wear OS 5 — target utama** |
| `hermes_wear` | 30 | x86 | Wear OS 3 (minSdk), **32-bit** |
| `hermes_arm64` | 36 | arm64 | |

Boot emulator (WAJIB pakai `-port` eksplisit kalau AVD lain mungkin hidup — pernah kena
`Running multiple emulators with the same AVD`):
```bash
./_tools/sdk/emulator/emulator.exe -avd hermes_wear5 -no-snapshot-load -no-boot-anim \
  -gpu swiftshader_indirect -port 5556
```
Kalau boot gagal dengan error AVD-sedang-dipakai: kill `qemu-system-x86_64.exe` + `emulator.exe`,
hapus `$HOME/.android/avd/<avd>.avd/*.lock` (termasuk yang **direktori**, pakai `rm -rf`), lalu boot lagi.

**Skrip bukti yang sudah ada (pakai, jangan tulis ulang):**
| File | Fungsi |
|---|---|
| `_tools/mock_provider.py` | Mock endpoint OpenAI-compatible di host, dengan toggle `/__fail` dan `/__ok`. Jalankan: `python _tools/mock_provider.py` (port 8077) |
| `_tools/p0_autodrain_proof.py` | Bukti end-to-end offline queue → auto-drain, lewat `adb reverse` |
| `_tools/wear_byok_drive.py` | Menyetir UI BYOK di jam (cari kontrol via bounds `uiautomator`, ketik, tutup IME) |
| `scripts/touchpoint_guard.sh` | Guard: file upstream mana yang boleh disentuh + budget baris + wajib ada marker `HERMES INTEGRATION POINT` |
| `scripts/upstream_sync.sh` | Sync dari upstream + jalankan guard. `SYNC_DRY_RUN=1` untuk dry-run |

**Cara menjangkau host dari emulator — pakai `adb reverse`, JANGAN `10.0.2.2`:**
```bash
adb -s emulator-5556 reverse tcp:8077 tcp:8077
# lalu di jam pakai  http://127.0.0.1:8077/v1
```
`10.0.2.2` **terbukti tidak bisa dijangkau** di mesin ini (Windows firewall). `adb reverse` terbukti
jalan. Ini penting: seluruh uji jaringan jam bergantung padanya.

---

## 2. Struktur modul & kepemilikan file

```
Agora/
├─ app/     → app phone (upstream Agora + integrasi Hermes di app/src/main/java/com/newoether/agora/autopilot/)
├─ wear/    → module jam, SEPENUHNYA milik Hermes (upstream tidak punya)
├─ scripts/ → touchpoint_guard.sh, upstream_sync.sh, persona_update.sh
├─ personas/ → teks persona yang di-vendor
├─ evidence/ → screenshot + skrip bukti
└─ AUDIT_REPORT.md, STATUS.md, UPSTREAM_TOUCHPOINTS.md, AGENTS.md
```

**File milik Hermes (bebas diubah):**
- `wear/**` — seluruh module (12 file `.kt`, 1.779 baris; 18 file total termasuk manifest + resource)
- `app/src/main/java/com/newoether/agora/autopilot/**` (26 file)
- `app/src/test/java/com/newoether/agora/autopilot/**`, `app/src/androidTest/java/com/newoether/agora/autopilot/**`
- `app/src/fdroid/res/**`, `app/src/play/res/**` (overlay flavor)
- `scripts/**`, `personas/**`, `evidence/**`, `*.md` di root repo

**File upstream yang boleh disentuh (WAJIB terdaftar di `UPSTREAM_TOUCHPOINTS.md` + punya marker):**
```
.gitignore                                                    :: max=6
app/build.gradle.kts                                          :: max=16
settings.gradle.kts                                           :: max=4
gradle/libs.versions.toml                                     :: max=10
app/src/main/AndroidManifest.xml                              :: max=6
app/src/main/res/values/strings.xml                           :: max=4
app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt  :: max=24
app/src/main/java/com/newoether/agora/MainActivity.kt         :: max=40
app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt :: max=8
```
Menyentuh file upstream di luar daftar ini, atau melebihi budget, **menggagalkan guard**. Kalau kamu
memang perlu, **naikkan budget-nya dengan alasan nyata yang ditulis di registry table** — jangan
diam-diam.

**File `wear/` yang bukan `.kt` tapi krusial (jangan terlewat saat "merapikan"):**
`wear/src/main/AndroidManifest.xml`, `wear/src/main/res/xml/network_security_config.xml`,
`wear/src/main/res/values/{strings,colors}.xml`, `wear/lint.xml` (kalau ada).

---

## 3. TEMUAN KRITIS yang harus kamu tangani lebih dulu

Ini hasil audit sesi sebelumnya. Dua di antaranya **membuat fitur yang diklaim ada, sebenarnya tidak
bisa dipakai**. Konfirmasi ulang dulu, lalu perbaiki.

### 3.1 🔴 `SettingsWatchSetupPage` TIDAK PERNAH TERJANGKAU — fitur "Watch setup" tidak ada di UI

**Bukti (jalankan ulang untuk konfirmasi):**
```bash
cd /c/Users/Michael/Documents/Chatapp/Agora
grep -c '"watch"' app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt   # → 0
grep -c 'SettingsCategory("watch"' app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt  # → 0
grep -rn "SettingsWatchSetupPage" app/src/main/java/ | grep -v "SettingsWatchSetupPage.kt:"  # → kosong
git log --oneline -S "SettingsWatchSetupPage" -- app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt  # → kosong
```
File `app/src/main/java/com/newoether/agora/autopilot/wearsync/SettingsWatchSetupPage.kt` (137 baris)
ada dan lengkap, memanggil `WatchSync.pushConfig` / `pushMemorySnapshot` / `connectedWatchCount`,
**tapi tidak ada entry `SettingsCategory("watch", …)` dan tidak ada cabang `"watch" ->` di dispatch
`when` SettingsScreen**. Jadi halaman ini adalah **dead code dari sudut pandang user**: tidak ada cara
membukanya.

**Dampak:** `STATUS.md` baris ~409 mengklaim *"Pair with the phone app — pushed over the Data Layer
(driven from Settings → Watch setup)"*. **Klaim itu salah** — tidak ada "Settings → Watch setup".
Perbaiki kodenya, lalu perbaiki klaimnya.

**Yang harus dilakukan:**
1. Tambahkan `SettingsCategory("watch", R.string.hermes_watch_setup, …)` ke grup yang tepat.
2. Tambahkan cabang `"watch" -> SettingsWatchSetupPage(onBack = { selectedCategory = null })` di `when`.
3. Budget `SettingsScreen.kt` saat ini `max=24`, terpakai 23 baris → **naikkan budget dengan alasan
   tertulis** (entry + dispatch ≈ 4–6 baris baru), lalu jalankan guard.
4. Verifikasi di device: buka Settings → Watch setup, halaman muncul, tombol "Send to watch" bisa ditekan.

### 3.2 🔴 Tombol "Pair with phone" di jam BOHONG — tidak ada handler, tidak ada Pairing API

**Bukti:**
```bash
grep -rn "pairing\|pairRequest\|CapabilityClient\|MessageClient\|onCapabilityChanged" \
  app/src/main/java/com/newoether/agora/ wear/src/main/java/     # → KOSONG
grep -rc 'WatchSync' wear/src/main/java/                          # → 0 (jam tidak kenal WatchSync)
grep -c 'onDataChanged' wear/src/main/java/com/newoether/agora/wear/WearMainActivity.kt  # → 0
```
`WearSetupScreen.kt` baris 146–151:
```kotlin
onClick = { waitingForPhone = true; status = "Waiting for the phone…" }
) { Text("Pair with phone") }
```
`waitingForPhone` hanya **men-disable field** dan menampilkan teks. **Tidak ada permintaan pairing,
tidak ada pengumuman capability, tidak ada pemberitahuan ke phone.** Jam hanya menunggu config yang
kebetulan dikirim manual dari halaman phone — yang menurut 3.1 bahkan tidak bisa dibuka.

**Dampak:** kedua jalur setup yang diwajibkan pemilik, hanya **satu** (BYOK) yang benar-benar ada.
Jalur pairing adalah **tampilan tanpa mekanisme**.

**Yang harus dilakukan:** implementasi pairing Data Layer yang nyata (lihat bagian 7).

### 3.3 🟠 `STATUS.md` dan `AUDIT_REPORT.md` memuat klaim yang tidak lagi benar

Setelah memperbaiki 3.1/3.2, audit ulang **setiap** baris klaim di `STATUS.md` dan `AUDIT_REPORT.md`
dan tandai mana yang masih benar. Aturan: klaim yang tidak bisa kamu buktikan dengan perintah di
sesi ini harus **dikoreksi atau ditandai belum terverifikasi** — jangan dibiarkan. Preseden yang
sudah ada di repo: `STATUS.md` memuat blok *"Correction (Phase 8)"* yang sengaja membiarkan klaim
palsu tetap terlihat. Ikuti pola itu.

---

## 4. Fase 0 — baseline: buktikan keadaan awal sebelum mengubah apa pun

Jangan lewati. Tanpa baseline, kamu tidak bisa membedakan "sudah rusak" dari "aku yang merusak".

```bash
cd /c/Users/Michael/Documents/Chatapp/Agora
export JAVA_HOME='...'; export ANDROID_HOME='...'; export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_OPTS="-Xmx3g"

git status --short
git log --oneline -1
bash scripts/touchpoint_guard.sh 2>&1 | tail -3
SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh 2>&1 | tail -3
./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest :wear:testDebugUnitTest --console=plain
./gradlew :app:assembleFdroidDebug :app:assemblePlayDebug :wear:assembleRelease --console=plain
```
Catat angkanya. **Baseline yang diharapkan (dari sesi sebelumnya, verifikasi ulang — jangan percaya
angka ini):**
```
guard          PASS
unit tests     fdroid 2.533 · play 2.516 · wear 31  → total 5.080, 0 gagal
APK            app-fdroid-debug ≈ 65,9 MB · app-play-debug ≈ 65,8 MB · wear-release ≈ 2,67 MB
connected      12/12 di hermes_wear5 + 12/12 di hermes_x86_64
```
Kalau ada yang berbeda, **selidiki dulu** sebelum melanjutkan — mungkin ada perubahan yang belum
kamu ketahui.

---

## 5. Fase 1 — bereskan repo & buang sampah

### 5.1 Inventaris file yang tidak seharusnya masuk repo
```bash
git ls-files | grep -E "/build/|\.apk$|\.log$|\.tmp$"          # artefak build
git status --ignored --short | head -40                        # yang di-ignore
find . -maxdepth 2 -name "*.log" -o -maxdepth 2 -name "_*" | head -40
```
**Konteks penting:** `.gitignore` upstream memakai `/build` (hanya root) dan mengandalkan
`app/.gitignore`, sehingga build tree module **baru** (`wear/build/`) lolos. Itu pernah terjadi
(800+ artefak ter-commit di Phase 7) dan sudah diperbaiki dengan menambahkan rule `build/` global +
marker. **Pastikan masih begitu** dan tidak ada artefak baru yang lolos.

### 5.2 Log kerja yang menumpuk di luar repo
Di `C:\Users\Michael\Documents\Chatapp\` ada banyak `_*.log` dan `_tools/*.py` hasil kerja sesi
sebelumnya. Putuskan mana yang **layak disimpan sebagai bukti** (pindahkan ke `evidence/` repo) dan
mana yang **sampah** (hapus). Aturan: jangan hapus apa pun yang belum kamu pahami gunanya; baca
dulu, baru putuskan.

### 5.3 Dead code & kode mati
```bash
# file sumber yang tidak direferensikan siapa pun
for f in $(find app/src/main/java/com/newoether/agora/autopilot wear/src/main/java -name "*.kt"); do
  base=$(basename "$f" .kt)
  n=$(grep -rl "\b$base\b" app/src wear/src 2>/dev/null | grep -v "/$base.kt" | wc -l)
  [ "$n" -eq 0 ] && echo "ORPHAN: $f"
done
```
**Perhatikan:** scan ini menghasilkan false positive. Contoh nyata: `WearListeners.kt` dan
`WearTheme.kt` dilaporkan orphan padahal dipakai — `WearListeners` direferensikan dari
**AndroidManifest** (nama service), dan `WearTheme` dipakai lewat nama fungsi (`WearHermesTheme`),
bukan nama file. **Selalu verifikasi manual sebelum menghapus.** Hapus hanya kalau kamu sudah
membuktikan tidak ada referensi di: kode, manifest, resource XML, dan string reflection.

Cari juga: `TODO`/`FIXME`/`XXX`/`HACK`, import tak terpakai, fungsi privat tanpa pemanggil,
`@Suppress` tanpa alasan, konstanta yang tidak dibaca.

### 5.4 Rapikan dokumentasi
- `STATUS.md` — pastikan tiap fase punya bukti perintah + angka, dan tidak ada klaim basi.
- `AUDIT_REPORT.md` — tambahkan bagian baru untuk sesi ini (jangan hapus temuan lama).
- `UPSTREAM_TOUCHPOINTS.md` — pastikan registry table cocok dengan data block.
- `AGENTS.md` / `ROADMAP.md` — sinkronkan dengan kenyataan.

---

## 6. Fase 2 — REBUILD penuh aplikasi Wear OS

**Tujuan:** membuktikan module jam bisa dibangun bersih dari nol dan menghasilkan APK release yang
benar-benar jalan, bukan hanya "compile hijau".

```bash
cd /c/Users/Michael/Documents/Chatapp/Agora
export JAVA_HOME='...'; export ANDROID_HOME='...'; export PATH="$JAVA_HOME/bin:$PATH"

# 1. bersih total (jangan andalkan cache)
./gradlew :wear:clean --console=plain

# 2. debug + unit test
./gradlew :wear:assembleDebug :wear:testDebugUnitTest --console=plain

# 3. release bertanda tangan (ini yang membuktikan R8 + shrinking + lintVital lolos)
./gradlew :wear:assembleRelease --console=plain

# 4. verifikasi tanda tangan
"C:/Users/Michael/Documents/Chatapp/_tools/sdk/build-tools/36.0.0/apksigner.bat" \
  verify --print-certs wear/build/outputs/apk/release/wear-release.apk
```

**Yang harus kamu laporkan:** ukuran APK (byte persis), DN sertifikat, dan apakah `lintVitalRelease`
lolos. Catatan: `lintVitalRelease` pernah gagal karena `play-services-basement` menarik
`androidx.fragment:1.1.0` di bawah floor 1.3.0; perbaikannya adalah **constraint ke `fragment:1.8.5`**,
bukan `lint.xml` suppress. Jangan mundur ke suppress.

**Kalau ada peringatan baru** (deprecation, R8 warning, lint) — catat dan putuskan, jangan diabaikan.

---

## 7. Fase 3 — IMPLEMENTASI PAIRING (fitur yang belum ada)

Ini permintaan eksplisit pemilik. Rancang dan bangun jalur pairing Data Layer yang **nyata**.

### 7.1 Yang harus dibangun

**Sisi jam (`wear/`):**
1. **Capability/permintaan pairing.** Jam mengumumkan dirinya dan/atau mengirim permintaan ke phone
   lewat Data Layer (`MessageClient` / `CapabilityClient` / `NodeClient` — pilih yang paling tepat
   dan **buktikan API-nya ada** dengan `javap` pada AAR sebelum memakainya; jangan menebak signature).
2. **Ganti tombol bohong** di `WearSetupScreen.kt`: tekan "Pair with phone" harus **mengirim
   permintaan nyata**, lalu menampilkan status berdasarkan **hasil sebenarnya** (terkirim / tidak ada
   phone / timeout), bukan teks statis.
3. **UI bereaksi saat config datang.** Sekarang `onDataChanged` di `ConfigListenerService` menulis
   config tapi **UI tidak pernah diberi tahu** (0 referensi). Jam harus otomatis pindah ke layar chat
   saat config diterima — pakai mekanisme yang benar (mis. `SharedFlow`/`StateFlow` atau
   `LocalBroadcast`-setara), dan **jangan** polling.
4. **Status pairing yang jujur:** apakah phone terhubung (`connectedNodes`), kapan terakhir config
   diterima, dan apa yang sedang terjadi.

**Sisi phone (`app/.../autopilot/wearsync/`):**
1. **Halaman Watch setup harus bisa dibuka** (lihat 3.1) — entry + dispatch di SettingsScreen.
2. **Tanggapi permintaan pairing** dari jam: saat jam minta, phone mengirim config. Jangan
   mengharuskan user menekan tombol di phone saat jam sudah meminta.
3. **Tampilkan status nyata:** jumlah watch terhubung, hasil push terakhir (berhasil/gagal + alasan),
   dan apakah memory snapshot ikut terkirim.
4. **Pilih model & key dari provider yang sudah dikonfigurasi** — jangan minta user mengetik ulang
   API key kalau sudah ada di provider settings. (Cek `ProviderRegistry` / `SettingsRepository`.)

### 7.2 Aturan pairing yang tidak boleh dilanggar
- **Kredensial tidak boleh masuk log, notifikasi, atau AdaptationLog.** Sudah ada preseden:
  `WearCrypto` (AES-256-GCM, key di Android keystore) dan `WatchSync` yang hanya mencatat nama kelas
  exception. Pertahankan.
- **Config tetap harus lolos `WearConfig.isValid()`** di sisi jam — validasi tidak boleh dilewati
  hanya karena datang dari phone.
- **Versi payload** (`WearConfig.CURRENT_VERSION`) harus tetap diperiksa; payload yang tidak dipahami
  harus **ditolak**, bukan ditebak.
- **Jangan tambah dependensi baru** kalau `play-services-wearable` (19.0.0, sudah ada) cukup.
- **Jangan menyentuh file upstream baru** kalau bisa dihindari; kalau terpaksa, daftarkan + marker.

### 7.3 Membuktikan pairing benar-benar jalan — INI BAGIAN SULITNYA

Dua emulator (phone + watch) **belum pernah di-pair**, dan Data Layer butuh keduanya terpasang app
dengan `applicationId` yang sama (`com.hermes.app`) dan **sertifikat yang sama**.

**Yang harus kamu coba, berurutan, dan laporkan mana yang berhasil:**
1. Jalankan phone AVD + wear AVD bersamaan, install kedua APK (phone: `app-fdroid-debug.apk`,
   watch: `wear-debug.apk`) — keduanya sudah memakai `applicationId` dan keystore yang sama.
2. Cek apakah Data Layer benar-benar tersambung:
   ```bash
   adb -s <phone> shell dumpsys activity service com.google.android.gms/.wearable.…
   # atau cara yang benar-benar terbukti — cari sendiri, jangan menebak
   ```
   Cara paling andal: panggil `WatchSync.connectedWatchCount(context)` dari kode dan tampilkan di UI,
   lalu baca layarnya.
3. Kalau Data Layer **tidak bisa** disambungkan di emulator (sangat mungkin — butuh Play Services
   yang benar-benar ter-pair), maka:
   - **Jangan** mengklaim pairing terverifikasi.
   - Bangun **seam** yang bisa diuji: bungkus akses Data Layer di balik interface, lalu tulis test
     instrumented yang menyuntikkan implementasi palsu untuk membuktikan **logika** pairing
     (permintaan terkirim, respons diproses, config divalidasi, UI bereaksi, kredensial tidak bocor).
   - Tulis di laporan: *"logika pairing terbukti dengan seam; transfer Data Layer antar-device belum
     terverifikasi — butuh device fisik atau emulator ter-pair (HS4)"*.

**Preseden penting:** di sesi sebelumnya, mock provider di host (`_tools/mock_provider.py` + `adb reverse`)
berhasil membuktikan seluruh jalur HTTP jam end-to-end tanpa API key asli. **Pakai pola yang sama**
untuk pairing kalau memungkinkan: buat jalur yang bisa diuji, buktikan, dan jujur soal apa yang
tersisa tidak teruji.

---

## 8. Fase 4 — fitur yang direkomendasikan (implementasi, bukan daftar)

Urutan prioritas sudah ditetapkan. Kerjakan berurutan; jangan lompat ke P2 sebelum P0/P1 selesai
atau dinyatakan gagal beserta alasannya.

### P0 — sisa pekerjaan offline queue (sebagian sudah selesai)
| Fitur | Status sekarang | Yang harus dilakukan |
|---|---|---|
| Auto-drain saat app dibuka | ✅ **SUDAH** (dibuktikan: `launch drain: delivered=1, queue now 0`) | jangan rusak; tambahkan test regresi |
| `showResult` agar jawaban lama tidak menimpa jawaban baru | ✅ **SUDAH** | tambahkan test regresi |
| **UI retry/discard untuk pertanyaan ditahan** | ❌ **BELUM** | badge `"$queued held offline"` sekarang read-only. Tambahkan: daftar pertanyaan ditahan, tombol kirim ulang paksa, tombol buang, dan konfirmasi saat entri di-drop otomatis setelah 3 percobaan (sekarang hanya tercatat di log) |

### P1 — surface Wear OS yang belum ada
| Fitur | Bukti belum ada | Catatan implementasi |
|---|---|---|
| **Tile** (`SuspendingTileService` + ProtoLayout) | `grep -rn "TileService\|ProtoLayout\|Glance" wear/src` → **0 hit** | Tile paling berguna: tombol tap-to-talk. Verifikasi API dengan `javap` pada AAR sebelum memakai. Tambahkan dependency hanya kalau benar-benar perlu |
| **Complication** data source | sama → 0 hit | Tampilkan: sudah dikonfigurasi? ada yang ditahan? |
| **Ongoing notification** untuk jawaban lama | `WearChatClient` `readTimeout(60s)` vs layar jam mati ~5–15 s | Pakai `OngoingActivity` + notifikasi; tanpa ini app terlihat hang |

### P2 — angka jujur & kontinuitas
| Fitur | Yang harus dilakukan |
|---|---|
| Parsing `usage` asli dari provider | `WearChatClient.parseContent` hanya ambil `content`. Tambahkan `usage.prompt_tokens`/`completion_tokens`, tampilkan di Debug, dan **ganti estimasi ~4 char/token dengan pengukuran** kalau provider melaporkannya. Kalau tidak dilaporkan, tetap tampilkan estimasi dan **labeli sebagai estimasi** |
| Riwayat percakapan di jam | sekarang satu `answer` string di memori; restart = hilang. Simpan N percakapan terakhir (file JSON, sama seperti pola `WearOfflineQueue` — jangan Room) |
| `readTimeout` lebih ketat | 60 s lebih lama dari interaksi jam mana pun. Kecilkan, atau wajibkan jalur ongoing-notification |

### Yang TIDAK boleh ditambahkan ke jam
Streaming response (sudah beralasan ditolak: layar kecil, jawaban pendek, koneksi yang harus dijaga
saat tangan turun), Room, llama.cpp, conversation trees, MCP, image generation, sandbox, skills.
Kalau kamu berpikir salah satunya perlu, **tanyakan dulu** — jangan putuskan sendiri.

---

## 9. Fase 5 — audit dua-pass + rip off bug

### Pass A — statis (grep + baca)
Jalankan minimal scan ini dan laporkan apa adanya:
```bash
# 1. CancellationException ditelan (pernah ketemu 8 tempat)
grep -rn "catch (.*Exception)\|runCatching\|recoverCatching" app/src/main/java/com/newoether/agora/autopilot wear/src/main | \
  while read -r l; do :; done
# Untuk tiap catch: WAJIB ada cabang CancellationException yang rethrow, kecuali alasannya ditulis.

# 2. runBlocking di jalur UI
grep -rn "runBlocking" app/src/main/java/com/newoether/agora/autopilot wear/src/main

# 3. !!/lateinit tanpa guard, GlobalScope, hardcoded secret
grep -rn "!!\|GlobalScope" app/src/main/java/com/newoether/agora/autopilot wear/src/main
grep -rniE "(api[_-]?key|token|secret|password)\s*=\s*\"[A-Za-z0-9_-]{12,}\"" app/src/main wear/src

# 4. TODO/FIXME, @Suppress tanpa alasan, import tak terpakai
grep -rn "TODO\|FIXME\|XXX\|HACK" app/src/main/java/com/newoether/agora/autopilot wear/src
```

### Pass B — adversarial (jalankan, jangan baca)
Ini bagian yang **wajib**. Untuk tiap klaim, coba **patahkan**:

| Serangan | Cara membuktikan |
|---|---|
| Queue hilang saat proses dibunuh di tengah tulis | matikan paksa saat menulis, buka lagi, cek file utuh |
| Config rusak/tidak bisa didekripsi | rusak file config, pastikan app tidak crash dan memberi pesan jelas |
| Base URL aneh | `https://x.com/v1/`, `https://x.com/chat/completions`, `http://127.0.0.1:11434`, URL tanpa skema |
| Respons provider tidak terduga | `choices` kosong, `content` kosong, HTML (captive portal), JSON rusak, 401/404/500/503, timeout |
| Pertanyaan sangat panjang | ketik melebihi lebar layar; pastikan tidak memotong diam-diam |
| Snapshot memory sangat besar | 400+ baris; pastikan truncation terlihat, bukan senyap |
| Persona bocor ke jam | pastikan `WearCoreContext` membuang **seluruh blok**, bukan hanya baris marker |
| Kredensial bocor | `grep` isi file config, logcat, notifikasi, AdaptationLog → **0 temuan** |
| Jam tanpa phone | mode pesawat; BYOK harus tetap jalan penuh |
| Jam tanpa config | hapus config; pastikan app minta setup, tidak crash |

**Aturan RED/GREEN:** untuk tiap bug yang kamu klaim ditemukan, kamu harus bisa menunjukkan
**kegagalan dulu**, baru perbaikan. Kalau tidak bisa menunjukkan RED, tulis "belum bisa direproduksi"
— jangan klaim bug.

---

## 10. Fase 6 — pengujian menyeluruh (tanpa batas)

### 10.1 Test otomatis — semua harus dijalankan dan angkanya dilaporkan
```bash
# unit test, tiga suite
./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest :wear:testDebugUnitTest --console=plain

# instrumented — jalankan di SEMUA device yang tersedia
adb devices
./gradlew :app:connectedFdroidDebugAndroidTest --console=plain

# stabilitas: 5x berturut-turut, satu flake = satu temuan
for i in 1 2 3 4 5; do
  echo "=== RUN $i ==="
  ./gradlew :app:testFdroidDebugUnitTest :wear:testDebugUnitTest --rerun-tasks --console=plain | \
    grep -E "BUILD SUCCESSFUL|BUILD FAILED|FAILED"
done
```

**Cara membaca hasil XML (jangan andalkan ringkasan Gradle saja):**
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

### 10.2 Cakupan yang WAJIB ditambahkan kalau belum ada
- **Test regresi untuk auto-drain** (P0 yang sudah selesai) — kalau tidak ada test, akan rusak lagi.
- **Test untuk jalur pairing** (seam, lihat 7.3).
- **Test untuk `WearChatClient`** sudah ada 17 test; tambahkan untuk `usage` kalau kamu implementasi P2.
- **Test untuk UI retry/discard** kalau kamu implementasi P0 sisa.

### 10.3 Uji di device — matriks lengkap

| Device | API | ABI | Yang harus diuji |
|---|---|---|---|
| `hermes_wear5` | 34 | x86_64 | **semua**: install release + debug, BYOK, pairing, chat, voice, queue, Tile, complication, notification |
| `hermes_wear` | 30 | x86 | install release, launch, BYOK. **Catatan: connected test TIDAK BISA jalan di sini** karena app module `abiFilters = arm64-v8a` sementara image ini 32-bit. Ini celah cakupan nyata — kalau kamu bisa menutupnya (mis. image arm64), lakukan dan laporkan |
| `hermes_x86_64` | 36 | x86_64 | phone: Settings → Watch setup, pairing, push config, push memory |
| `hermes_arm64` | 36 | arm64 | kalau memungkinkan |

**Cara menguji UI di jam (pola yang terbukti):**
```bash
# ambil bounds asli, JANGAN mengandalkan screenshot
adb -s emulator-5556 shell uiautomator dump /sdcard/ui.xml
adb -s emulator-5556 pull /sdcard/ui.xml "$LOCALAPPDATA/Temp/ui.xml"
# lalu parse bounds di Python dan tap koordinat tengahnya
```
⚠️ **Peringatan yang sudah dua kali terjadi:** review screenshot lewat vision **salah membaca scroll
fold sebagai "clipping"**. Untuk pertanyaan **geometri/layout**, **selalu** pakai bounds
`uiautomator` dan hitung jarak dari pusat lingkaran (radius 192 px di layar 384×384). Vision berguna
untuk **warna, surface, tipografi** — bukan geometri.

### 10.4 Uji jaringan jam end-to-end (pola mock — pakai ini)
```bash
# 1. host: jalankan mock
python _tools/mock_provider.py                    # port 8077
# 2. sambungkan device ke host
adb -s emulator-5556 reverse tcp:8077 tcp:8077
# 3. di jam, set base URL ke  http://127.0.0.1:8077/v1
# 4. jalankan proof yang sudah ada
python _tools/p0_autodrain_proof.py
```
Mock mendukung `/__fail` (balas 503 → pertanyaan ditahan) dan `/__ok` (balas 200). **Jangan pakai
`10.0.2.2`** — terbukti tidak bisa dijangkau di mesin ini.

### 10.5 Yang TIDAK bisa diuji tanpa kredensial — nyatakan, jangan pura-pura
- Perilaku provider **spesifik** (OpenAI/Anthropic/Groq/…) — butuh API key asli (HS2).
- Kualitas jawaban model nyata.
- Transfer Data Layer **antar-device** kalau emulator tidak bisa di-pair (HS4).

Untuk yang di atas, gunakan seam/mock dan **tulis eksplisit di laporan apa yang belum terverifikasi
dan apa yang dibutuhkan untuk memverifikasinya**.

---

## 11. Fase 7 — laporan akhir (wajib)

Tulis/`append` ke `AUDIT_REPORT.md` bagian baru untuk sesi ini, plus perbarui `STATUS.md`. Format:

### 11.1 Tabel temuan
| # | Sev | Temuan | Cara ditemukan | Status |
|---|---|---|---|---|
Severity: **CRITICAL** (build rusak / data hilang / kredensial bocor / fitur tidak bisa dipakai),
**HIGH** (fitur diam-diam tidak melakukan apa yang dijanjikan), **MEDIUM** (perilaku salah di kasus
nyata tapi sempit), **LOW** (kerapian).

### 11.2 Tabel gate
| Gate | Hasil | Bukti (perintah + angka persis) |
|---|---|---|
Wajib memuat: unit test 3 suite, connected test per device, build 3 APK (ukuran byte), guard, sync
dry-run, tanda tangan APK, dan status pairing + alasan.

### 11.3 Bagian "Yang masih terbuka, jujur"
Daftar hal yang **tidak** terverifikasi + apa yang dibutuhkan. Termasuk HS2 dan HS4 kalau masih
terbuka.

### 11.4 Bagian "Pola yang layak dinamai"
Kalau kamu menemukan kelas bug yang berulang, tulis polanya. Preseden dari sesi sebelumnya:
- *"works on the dev machine, impossible on the device"* (curl tidak ada di Android; test JVM tidak
  bisa menangkapnya).
- *"the block is the unit, not the marker line"* (menangani marker sebagai baris menghasilkan dua bug
  di dua module berbeda).
- *"screenshot review reads a scroll fold as clipping"* (terjadi dua kali).

### 11.5 Commit & push
Satu commit per fase, pesan commit menjelaskan **apa yang salah dan kenapa diperbaiki begitu** —
bukan hanya "update X". Jangan commit artefak build. Push ke `origin/main` dan laporkan hash-nya.

---

## 12. Definition of Done — semua harus benar sebelum kamu bilang selesai

- [ ] `touchpoint_guard.sh` → **PASS**
- [ ] `upstream_sync.sh --dry-run` → bersih (atau konflik dilaporkan dengan bukti)
- [ ] Unit test 3 suite → **0 gagal, 0 error**; angkanya dilaporkan
- [ ] Connected test → dijalankan di **semua** device yang mendukung; kegagalan dilaporkan, bukan disembunyikan
- [ ] `:app:assembleFdroidDebug`, `:app:assemblePlayDebug`, `:wear:assembleRelease` → **sukses**
- [ ] APK release bertanda tangan, `apksigner verify` lolos, DN dilaporkan
- [ ] **`SettingsWatchSetupPage` bisa dibuka dari Settings** (3.1 diperbaiki, dibuktikan di device)
- [ ] **Pairing nyata** atau seam yang teruji + pernyataan jujur soal apa yang tersisa (3.2)
- [ ] **BYOK masih jalan** di jam, dibuktikan end-to-end (regresi dicegah)
- [ ] Auto-drain masih jalan, dengan test regresi
- [ ] P0 sisa (UI retry/discard) selesai atau dilaporkan gagal + alasannya
- [ ] P1 (Tile / complication / ongoing notification) selesai atau dilaporkan gagal + alasannya
- [ ] P2 (usage parsing / riwayat / timeout) selesai atau dilaporkan gagal + alasannya
- [ ] Scan dead code dijalankan, hasil diverifikasi manual (bukan dihapus berdasarkan false positive)
- [ ] Tidak ada artefak build / log / file sementara yang ter-commit
- [ ] `STATUS.md` + `AUDIT_REPORT.md` tidak memuat klaim yang tidak bisa dibuktikan
- [ ] Semua klaim di laporan bisa ditelusuri ke perintah yang benar-benar dijalankan
- [ ] Commit + push selesai, hash dilaporkan

---

## 13. Jebakan yang sudah memakan korban — hindari ini

1. **`ProcessBuilder("curl", …)` tidak akan pernah jalan di Android.** Android tidak punya curl.
   Pakai OkHttp. (Bug ini pernah membuat fitur "Check for persona updates" mustahil jalan.)
2. **Test JVM tidak bisa menangkap bug yang bergantung platform.** Bug curl lolos 6/6 di JVM karena
   host Windows punya curl. Kalau bug-nya "tidak ada di Android", test-nya **harus instrumented**.
3. **`runCatching`/`catch (Exception)` menelan `CancellationException`.** Selalu tambahkan cabang
   rethrow, atau kamu akan melanjutkan kerja setelah dibatalkan.
4. **Membaca state Compose yang baru di-assign di `LaunchedEffect` yang sama = selalu false.**
   Pakai `val` lokal. (Ini membuat auto-drain tidak pernah jalan.)
5. **Validasi yang bilang "boleh" tapi platform bilang "tidak".** Contoh: `WearConfig.isValid()`
   menerima `http://127.0.0.1` tapi `networkSecurityConfig` tidak ada → OkHttp lempar
   `UnknownServiceException`. **Selalu uji jalur yang kamu validasi.**
6. **`run-as` tidak bisa membaca data APK release** (`package not debuggable`) — pembacaan akan
   mengembalikan 0 dan terlihat seperti bug. Pakai APK debug untuk verifikasi yang butuh `run-as`.
7. **Boot emulator tanpa `-port` eksplisit** bisa kena "Running multiple emulators with the same AVD".
   Kill + hapus lock (termasuk direktori `.lock`, pakai `rm -rf`) lalu boot ulang.
8. **`--rerun-tasks` itu wajib** untuk verifikasi "bersih". Tanpa itu Gradle bisa bilang SUCCESSFUL
   hanya karena task-nya up-to-date.
9. **Piping ke `grep`/`head` menyembunyikan exit code sebenarnya.** Pakai `${PIPESTATUS[0]}` atau
   jalankan tanpa pipe saat exit code penting.
10. **Hasil proses background bisa sudah basi.** Tiga kali terjadi: compile error, signature clash,
    dan guard failure — semuanya sudah diperbaiki sebelum notifikasinya sampai. **Selalu verifikasi
    ulang dengan state sekarang** sebelum melaporkan.

---

## 14. Mulai dari sini

1. Baca `AGENTS.md`, `STATUS.md`, `AUDIT_REPORT.md`, `UPSTREAM_TOUCHPOINTS.md` sampai paham.
2. Jalankan **Fase 0 (baseline)** dan laporkan angkanya ke pemilik sebelum mengubah apa pun.
3. Konfirmasi ulang **3.1** dan **3.2** dengan perintah yang tertulis di atas.
4. Kerjakan **3.1** (mudah, berdampak besar), lalu **Fase 1** (rapikan), lalu **Fase 2** (rebuild).
5. Baru masuk **Fase 3 (pairing)** — ini yang paling sulit dan paling berharga.
6. Lanjut **Fase 4 → 5 → 6 → 7**.
7. Kalau ada keputusan desain yang tidak jelas (mis. cara pairing yang benar), **tanyakan** — jangan
   menebak dan jangan mengklaim sudah selesai.

**Pemilik lebih menghargai laporan jujur "ini belum terverifikasi, ini kenapa" daripada klaim
lengkap yang tidak bisa dibuktikan.**
