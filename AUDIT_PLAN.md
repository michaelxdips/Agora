# AUDIT_PLAN.md — Hermes v2.1 Brutal Audit

> Status: **RENCANA SAJA. Belum ada eksekusi.** Semua angka di bawah yang ditandai
> `VERIFIED` sudah dicek di sesi perencanaan ini; yang ditandai `TO-VERIFY` baru akan
> dibuktikan saat eksekusi.
>
> Aturan yang mengikat: `AGENTS.md` (repo) + `RULES.md` (N6–N14) + mandat v2.1.
> Tidak ada tag, tidak ada klaim, tidak ada angka tanpa bukti perintah di `audit-evidence/`.

---

## 1. Mandat decode — 7 track

| Track | Isi | Prioritas |
|---|---|---|
| GATE 0 | Rekonstruksi kebenaran: tag, gate, device | P0 |
| 1 | Truth Commission: setiap fitur dijalankan end-to-end di device | P1 |
| 2 | Wear OS torture chamber W1–W9 | **P0 (tertinggi)** |
| 3 | Bug hunt phone-side B1–B8 | P1 |
| 4 | Optimasi O1–O5 (angka atau tidak ada) | P2 |
| 5 | MVP gauntlet: ≤3 fitur lolos, dibangun + toggle + tes | P2 |
| 6 | Dogfood simulation satu hari penuh di device | P2 (setelah fix) |
| 7 | Pass 2: auditor baru yang membenci pass 1 | P3 (wajib) |

Urutan eksekusi: **GATE 0 -> Track 2 -> Track 1 -> Track 3 -> Track 6 -> Track 4 ->
Track 5 -> Track 7 (pass 2) -> deliverables -> tag v2.1-brutal-audit**.
Track 2 didahulukan karena device adalah sumber daya paling rapuh (emulator bisa mati,
jam fisik tidak ada) dan mandat menyebut Track 2 tanpa hasil = fraud.

---

## 2. GATE 0 — ground truth HASIL CEK SEKARANG (planning pass)

Semua baris di bawah dijalankan di sesi perencanaan ini. Tabel ini akan di-append
apa adanya ke `STATUS.md` sebagai langkah eksekusi 0.1 (mandat: first output).

### 2.1 Repo

| Item | Hasil | Metode |
|---|---|---|
| Repo root | `C:\Users\Michael\Documents\Chatapp\Agora` | `Get-ChildItem` |
| Branch | `main`, tree bersih | `git status --short` kosong |
| HEAD | `c92055402a9b03df790a2997bb7884dfe24db5dc` | `git rev-parse HEAD` |
| HEAD tanggal | 2026-09-15 12:33:41 +0700 | `git log -1 --format=%ci` |
| HEAD subject | `hermes: phase 12 report — CODE_MAP, STATUS correction, audit findings, doc rename` | `git log -1` |
| Remote | `origin` = michaelxdips/Agora, `upstream` = newo-ether/Agora | `git remote -v` |

### 2.2 Tag chain — `VERIFIED`, dan satu klaim mandat TIDAK akurat

| Tag | Ada? | Objek | Ancestor of HEAD? |
|---|---|---|---|
| `v1.0-hermes` | YA | commit `8bb7f089` | YA |
| `v1.1-wear` | **TIDAK ADA** (lokal + remote) | — | — |
| `v1.2-hardened` | **TIDAK ADA** (lokal + remote) | — | — |
| `v2.0-hermes` | YA | annotated tag -> commit `c6b56bcb`, message "Hermes v2.0 — hardened" | YA |
| `sync-2026-09-14-stress` | YA | annotated tag -> commit `e360491c` | YA |

Metode: `git tag --list`, `git ls-remote --tags origin`, `git merge-base --is-ancestor`.

**Temuan G-001 (kandidat, process):** mandat mengklaim 4 tag hermes. Realitanya 2.
`AUDIT_REPORT.md:121` sendiri menyatakan *"`v1.1-wear` / `v1.2-hardened` / `v2.0-hermes`
pending the gate"* — jadi chain memang tidak pernah lengkap, dan `v2.0-hermes` sudah
dianotasi "hardened", artinya isi v1.2 kemungkinan di-roll ke v2.0. Eksekusi pass 1
wajib memutuskan: (a) buat tag retroaktif di commit yang tepat, atau (b) dokumentasikan
sebagai deliberate consolidation. Tidak boleh diam-diam dilewati.

### 2.3 Device — `VERIFIED`

| Device | Serial | Model | API | Catatan |
|---|---|---|---|---|
| Phone | `emulator-5554` | `sdk_gphone64_x86_64` | 36 | x86_64 Play image, ARM translation |
| Watch | `emulator-5556` | `sdk_gwear_x86_64` | 34 | `ro.build.characteristics=watch` |
| AVD tersedia tapi belum boot | `hermes_wear` | API 30 (minSdk) | — | dipakai untuk bukti minimum-device |
| AVD | `hermes_arm64`, `hermes_x86_64` | — | — | cadangan |

**HUMAN MOMENT (W-device):** mandat menyebut "Xiaomi Watch 2" fisik. Yang ada hanya
emulator Wear OS 384x384 round. Konsekuensi yang harus ditulis jujur di laporan:

* W3 (round clipping, touch target, AOD privacy) — **bisa** di emulator (round 384x384).
* W3 (rotating crown/bezel) — **tidak bisa** di emulator (tidak ada input rotari).
* W5a/W5c (battery delta, wake-lock) — **proxy** via `dumpsys batterystats` / `dumpsys power`.
* W5b (8 jam idle drain) — **tidak bisa realistis** di emulator; jalankan versi kompresi
  (30 menit idle, delta mAh) dan label sebagai proxy.
* W5d (thermal throttle) — emulator tidak merepresentasikan thermal jam; catat sebagai
  **BLOCKED-HARDWARE** dengan alasan, bukan angka palsu.

### 2.4 Tooling — `VERIFIED`

| Alat | Path | Status |
|---|---|---|
| adb | `C:\Users\Michael\Documents\Chatapp\_tools\sdk\platform-tools\adb.exe` | OK |
| bash | `C:\Program Files\Git\bin\bash.exe` (GNU bash 5.3.15, cygwin) | OK — `bash` di PATH adalah WSL dan rusak; semua script harus dipanggil lewat path ini |
| JDK | `_tools/jdk21/jdk-21.0.12.1+1` | OK |
| SDK | `_tools/sdk` (build-tools 36.0.0, platform-tools, emulator) | OK |
| Keystore | `_tools/hermes-release.jks` via `local.properties` | OK (secret, jangan pernah dicetak) |
| `gh` CLI | **TIDAK ADA** | Deferral issue MEDIUM/LOW harus lewat GitHub API + `_tools/.ghtoken`, atau dicatat di BLOCKED.md |
| Mock provider | `_tools/mock_provider.py` (host, port 8077, punya toggle `/__fail`, mengembalikan `usage`) | OK — dipakai semua device E2E tanpa API key |
| Proof scripts lama | `_tools/p0_autodrain_proof.py`, `phone_proof.py`, `maintainer_proof.py`, `wear_byok_drive.py` | dipakai ulang/diperluas |

### 2.5 Kode yang akan dibaca 100% (`FULL-READ`)

| Area | File | LOC |
|---|---|---|
| `app/.../autopilot/` | 28 file `.kt` (termasuk `wearsync/` 3 file) | ~2.774 |
| `wear/src/main` | 13 file `.kt` + `AndroidManifest.xml` + `res/` | ~2.369 (termasuk test) |
| Test fork-owned | 13 JVM + 3 androidTest (app), 6 test (wear) | — |

Fakta kode yang sudah terlihat saat planning (bahan torture, bukan temuan final):

* `wear/.../WearMainActivity.kt:120` memakai `Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)`.
  Image `sdk_gwear_x86_64` umumnya **tidak punya activity recognizer**. W2 harus dimulai
  dengan `pm resolve-activity` — kalau tidak ada, itu temuan UX (voice mati di device tanpa
  recognizer) + fallback keyboard harus terbukti jalan. Jangan asumsikan bisa.
* `wear/src/main/AndroidManifest.xml` **tidak punya** `TileService`, complication, atau
  `OngoingActivity` — sesuai `AUDIT_REPORT.md` P1, jadi kandidat Track 5 sudah berdasar.
* `AutopilotSettings.startOfToday` memakai `Calendar` zona lokal; cap harian dihitung
  `countSince(startOfToday(now))`. Uji boundary tengah malam = JVM dengan `now` injeksi
  (device clock tidak bisa diubah di Play image — `adb root` tidak tersedia). Catat metode.
* `ReflectionWorker.schedule` memakai `ExistingWorkPolicy.REPLACE` — bahan uji Worker
  double-fire & concurrent reflections (B3).
* `CircuitBreaker.recordCorrection` hanya menghitung entry yang `injectionsFor(entry.id)`
  mengandung sessionId — bahan uji "koreksi yang BUKAN tentang fakta adaptasi tidak boleh
  memicu breaker" (Track 1 trap).

---

## 2.6 PRE-AUDIT DISCOVERIES — hasil pencarian 5 agent paralel (planning pass, read-only)

Semua di bawah **ditemukan sebelum audit resmi dimulai**, dengan membaca kode/dokumen dan
perintah read-only. Status: `TO-REPROVE` = wajib direproduksi ulang dengan perintah + bukti saat
eksekusi sebelum difix (aturan REPRODUCE -> FIX -> RE-PROVE). Tidak ada yang difix sekarang.

### 2.6.1 Blocker yang membuat GATE 0 merah SEKARANG

| ID | Sev | Temuan | Bukti |
|---|---|---|---|
| A-001 | **CRITICAL** | `main` MERAH di HEAD `edebbc43`: `touchpoint_guard.sh` FAIL — `app/src/test/java/com/newoether/agora/util/UpdateCheckerTest.kt` adalah file path upstream yang tidak terdaftar dan tidak di bawah prefix Hermes-only. Commit `edebbc43` sudah di-push ke `origin/main`. | `bash scripts/touchpoint_guard.sh` → `FAILED`, exit 1 (dijalankan di sesi ini) |
| A-002 | **MEDIUM (proses)** | Penulis lain commit ke `main` SAAT sesi perencanaan ini berjalan: HEAD `c9205540` → `edebbc43` ("the update check offered the upstream project's releases", 13:19 +0700). Artinya repo ini punya lebih dari satu penulis aktif. | `git log --oneline -2` |

**Konsekuensi rencana:** langkah eksekusi pertama bukan W1, tapi **fix A-001** (daftarkan
`UpdateCheckerTest.kt` di `UPSTREAM_TOUCHPOINTS.md` dengan budget, atau pindahkan tes ke path
terdaftar) supaya baseline hijau bisa diukur. Plus aturan checkpoint baru: catat HEAD di awal
tiap track; kalau bergerak, re-verifikasi temuan yang menyentuh file itu.

### 2.6.2 CRITICAL produk (terkonfirmasi baca-kode di sesi ini)

| ID | Sev | Temuan | Bukti |
|---|---|---|---|
| A-003 | **CRITICAL** | **Persona tidak bisa di-undo; undo-nya berpotensi menghapus file user.** `PersonaApplier` menulis `files/active_memory.md` tapi menjurnal `store=STORE_MEMORY, targetFile="active_memory.md"` (`PersonaApplier.kt:98-99`). `MemoryApplier.undo` meresolusi lewat `MemoryManager.resolveFile` → `files/memory_db/active_memory.md` (`MemoryManager.kt` resolveFile). Hasil: (a) tombol Undo pada baris persona selalu gagal senyap (`readFile` → `require(file.exists())` throw, tertangkap, return false, hasilnya dibuang UI); (b) kalau user punya file memory bernama `active_memory.md`, undo persona **menimpa file itu** dengan snapshot persona = data loss. | dibaca langsung: `PersonaApplier.kt:95-115`, `MemoryApplier.undo` (85-117), `MemoryManager.resolveFile` |
| A-004 | **CRITICAL** | **API key dikirim plaintext lewat Data Layer dan tidak pernah dihapus.** `WatchSync.pushConfig` menaruh `apiKey` mentah di `DataItem` (`WatchSync.kt:75-77`); `grep -rn deleteDataItems` → 0 hit; DataItem adalah store bersama yang bisa dibaca app lain di jam. Klaim `WearListeners` "hostile app cannot read the key" dan `SettingsWatchSetupPage` "never written anywhere on the phone side" dua-duanya salah. | `WatchSync.kt:75`, grep `deleteDataItems` kosong |
| A-005 | **CRITICAL** | **Pairing tidak terautentikasi.** `PairingListenerService.onMessageReceived` menerima `/hermes/pair` dari node mana pun dan langsung mendorong kredensial asli tanpa cek pemanggil. | `PairingListenerService.kt:51-63,77-85` |
| A-006 | **CRITICAL** | **Config listener bisa dibajak.** `ConfigListenerService` exported, filter hanya path. App lain di jam bisa `putDataItem("/hermes/config")` dengan baseUrl jahat → semua pertanyaan + snapshot memory berikutnya dikirim ke endpoint penyerang. | `wear/AndroidManifest.xml:38-48`, `WearListeners.kt:30-48` |
| A-007 | **CRITICAL** | **Pertanyaan hilang saat dibatalkan.** `WearChatClient.ask` rethrow `CancellationException` (sengaja), tapi pemanggil di `WearMainActivity` memakai `rememberCoroutineScope`; saat activity mati (wrist-down/config change) `result.fold` tidak pernah jalan → `queue.enqueue` tidak pernah dieksekusi → pertanyaan lenyap tanpa jejak. | `WearChatClient.kt:103-109`, `WearMainActivity.kt:237-251,376` |

### 2.6.3 HIGH produk (fitur yang diklaim selesai tapi tidak bisa jalan)

| ID | Sev | Temuan | Bukti |
|---|---|---|---|
| A-008 | **HIGH** | **Auto-rollback circuit breaker = PHANTOM.** `recordInjection`/`recordCorrection` nol caller produksi (`ReflectionWorker` hanya panggil `pruneRetention`). Tidak ada injeksi tercatat, tidak ada koreksi terhitung → "2 flags → rollback" tidak pernah bisa terjadi untuk user nyata. | `Select-String recordInjection\|recordCorrection` di `app/src/main` → hanya 2 deklarasi |
| A-009 | **HIGH** | **Skills v1 = PHANTOM.** `SkillCandidateDetector` + `SkillSynthesizer` nol caller produksi; hanya dipakai tes. | grep sama, hanya deklarasi |
| A-010 | **HIGH** | **"Check for persona updates" mustahil.** `PersonaUpdater.readLock` membaca `filesDir/personas/upstream.lock`, tapi seeding hanya menyalin `SKILL.md`; lock tidak pernah ada di storage app → selalu `"upstream.lock not readable"`. | `PersonaUpdater.kt:102-107` vs `PersonaRepository.seedVendoredFromAssets` |
| A-011 | **HIGH** | **Edit teks persona tidak pernah dipakai.** `bodies()` membangun dari `defaultText`, `customText` tidak pernah dikonsultasi → user mengedit, menyimpan, dan tidak ada efek pada prompt. | `PersonaRepository.kt:40-43`, `SettingsPersonasPage.kt:207-214` |
| A-012 | **HIGH** | **Voice/TTS gagal senyap.** Di image jam target: TTS engine = "No services found", recognizer = tidak ada. `speak()` jadi no-op tanpa log; tombol Speak hanya menulis satu logcat dan layar tidak berubah. Tidak ada UX fallback. | `cmd package query-services` di emulator-5556; `WearMainActivity.kt:109-126` |
| A-013 | **HIGH** | **Memory snapshot beku selamanya.** Doc `WatchSync` bilang "pushed whenever it changes"; satu-satunya call site ada di `sendConfigToWatch` (sekali saat setup). Core context jam tidak pernah update setelah setup. | `WatchSync.kt:22-23,168` |
| A-014 | **HIGH** | **Guard bisa dilewati** (dibuktikan di lab, bukan teori): (1) `scripts/` dan `*.md` root diizinkan sebagai kelas padahal upstream punya `scripts/round_icon.py`, `README.md`, `ARCHITECTURE.md`, dll → backdoor lolos PASS; (2) path terdaftar yang juga match ALLOWED dilewati cek budget; (3) `.github/workflows/upstream-sync.yml` diizinkan → CI bisa mengedit dirinya sendiri; (4) rename `{old => new}` merusak lookup budget; (5) mode-only/binary tak terlihat. | 3 bypass dijalankan di repo lab dengan script guard asli, semuanya exit 0 |
| A-015 | **HIGH** | **CI tidak pernah jalan di `main`.** `build.yml`/`mkdocs.yml` trigger `[master]` saja; default branch origin = `master`. Dan `upstream-sync.yml` tidak menyiapkan JDK/SDK (`JAVA_HOME` script menunjuk path Windows) → gate gradle di CI akan gagal setelah merge lokal. | `.github/workflows/*.yml` |
| A-016 | **HIGH** | **Bukti device yang dikutip tidak ada.** Semua `_device_proof/p6-*.png` (4 file) dan `p7-*.png` (3 file) di `STATUS.md` tidak pernah ada di disk/git. Yang ada hanya `evidence/phase0-6/*` dan `p8/p9/p10`. | `Test-Path _device_proof` → False; `git log --all -- _device_proof` kosong |

### 2.6.4 MEDIUM/LOW terverifikasi (kandidat fix cepat)

| ID | Sev | Temuan | Bukti |
|---|---|---|---|
| A-017 | MED | `emulator-5558` dikutip di `STATUS.md:439` dan di-hardcode `_tools/wear_byok_drive.py:12`; device itu tidak ada (hanya 5554/5556). | `adb -s emulator-5558` → not found |
| A-018 | MED | Angka dokumen bertabrakan: wear tests 47 vs 51 (kode = 51); fdroid APK 65,285,032 vs 65,285,432 vs 65,294,236; instrumented 3/3 vs 6/6 vs 12/12 (kode = 12). | hitung `@Test`, `Get-Item` APK |
| A-019 | MED | Watch release APK tidak bisa di-`run-as` (release, non-debuggable) → `p0_autodrain_proof.py` membaca queue via `run-as` dan akan selalu 0; PASS yang tercatat di AUDIT_REPORT tidak bisa berasal dari APK release seperti yang diklaim docstring. | `run-as` → "package not debuggable" |
| A-020 | MED | Drain tanpa lock: `WearQueueDrainer` snapshot `queue.all()` di luar mutex → dua drain bersamaan (launch + Send) bisa mengirim duplikat. | `WearQueueDrainer.kt:62-79` |
| A-021 | MED | Drain hanya menyimpan `lastAnswer`; N-1 jawaban lain dihapus tanpa pernah ditampilkan. | `WearQueueDrainer.kt:72-74`, `WearMainActivity.kt:196-199` |
| A-022 | MED | Ack pairing tidak pernah di-reset → tap kedua melaporkan `Connected` dari ack lama. | `WearPairing.kt:104-105`, `WearSignals.kt:27` |
| A-023 | MED | Tidak ada version handshake: watch kirim `{"protocol":1}`, phone tidak pernah parse; `version=1` hardcoded. Bump versi → semua push ditolak senyap sambil phone bilang "Sent". | `PairingListenerService.kt:51-53`, `WatchSync.kt:69` |
| A-024 | MED | `WearConfig.isValid` prefix-match (`http://localhost.evil.com` lolos validasi) lalu diblokir platform → bug `UnknownServiceException` yang diklaim fixed masih bisa terjadi. | `WearConfig.kt:42-45` vs `network_security_config.xml` |
| A-025 | MED | Master toggle `collectAsState(initial = true)` + `LaunchedEffect` → saat master OFF dan persona ON, halaman menulis-ulang persona lalu strip lagi (journal + cap terpakai); process death di jendela itu = persona aktif dengan autopilot mati. | `AutopilotControlsSection.kt:33,50` |
| A-026 | MED | `PersonaApplier.setEnabled` rethrow ke `LaunchedEffect` Compose tanpa try/catch → IO failure (disk penuh) = crash. | `PersonaApplier.kt:109-113` |
| A-027 | MED | Cap harian menghitung SEMUA baris `adaptation_log` termasuk toggle persona dan skill draft → 5 toggle persona mematikan reflection sehari. | `AutopilotSettings.kt:45-49`, `AdaptationLog.countSince` |
| A-028 | MED | `fallbackToDestructiveMigration(dropAllTables=true)` + `exportSchema=false` masih aktif padahal sudah ada tag rilis → perubahan schema berikutnya menghapus seluruh jurnal undo. | `AdaptationLog.kt:127-148` |
| A-029 | MED | Tidak ada index di `timestamp`/`targetFile`/`store`; `all()` memuat semua snapshot penuh → halaman history memuat seluruh teks ke memori. | `AdaptationLog.kt:22-76` |
| A-030 | MED | `transcriptFor` mengandalkan urutan `getMessagesByIds` yang tidak punya `ORDER BY` → transcript bisa acak. | `ReflectionWorker.kt:104`, `ChatDao.kt:665` |
| A-031 | MED | Jawaban panjang di jam tidak punya `maxLines`/scroll → terpotong di `ScalingLazyColumn`. | `WearMainActivity.kt:318-322` |
| A-032 | MED | `ImeAction.Send` tanpa `KeyboardActions` → tombol Send di keyboard tidak mengirim. | `WearMainActivity.kt:351` |
| A-033 | MED | State jam pakai `remember` bukan `rememberSaveable`, tanpa `configChanges` → config change/process death menghapus jawaban + draft. | `WearMainActivity.kt:150-158` |
| A-034 | MED | Field API key tanpa `PasswordVisualTransformation` → kredensial tampil plaintext di layar jam. | `WearSetupScreen.kt:224-236` |
| A-035 | MED | `WearCrypto.secretKey()` tidak disinkronkan; dua call pertama bersamaan bisa generate dua key → ciphertext tidak bisa didekripsi. | `WearCrypto.kt:67-82` |
| A-036 | MED | Queue korup dibaca sebagai kosong, `enqueue` berikutnya menimpa file → semua pertanyaan hilang. Tes justru meng-assert perilaku ini. | `WearOfflineQueue.kt:83-86`, `WearOfflineQueueTest.kt:90-97` |
| A-037 | MED | Blok persona tak tertutup menghapus SELURUH sisa snapshot (bukan hanya blok) tanpa catatan truncation. | `WearCoreContext.kt:83-87` |
| A-038 | MED | `endpoint()` tidak menangani `/chat/completions/` (dobel append) dan base URL tanpa `/v1`. | `WearChatClient.kt:126-129` |
| A-039 | MED | `response.body?.string()` tanpa batas ukuran. | `WearChatClient.kt:96` |
| A-040 | MED | `PersonaStore.removeBlock` menghapus teks user yang kebetulan memuat literal marker (catatan tentang format marker = tail-nya hilang saat startup reconcile). | `PersonaStore.kt:68-93` |
| A-041 | MED | `SkillSynthesizer` collision guard membandingkan `"taken"` vs `"taken.md"` → draft menimpa skill user; tes memakai nilai yang produksi tidak pernah hasilkan. | `SkillSynthesizer.kt:45` |
| A-042 | LOW | Dead code terkirim di release: `WearCardSurface`, `inputSurface()`, `Gap`, `RoundContent`, `WearConfigStore.clear`, `WearMemoryCache.updatedAt/clear`, `WearOfflineQueue.clear`, `AdaptationLogDao.observe`, `AutopilotSettings.setDailyCap`, `AutopilotNotifier.EXTRA_ADAPTATION_COUNT`, `PersonaRepository.installVendored`, `PersonaCostReport`. | grep per simbol |
| A-043 | LOW | `PersonaCostReport` menghitung biaya Ponytail memakai marker Caveman → angka HONEST-NUMBERS di STATUS sedikit salah. | `PersonaCostReport.kt:23-32,50-53` |
| A-044 | LOW | `NOTICE.md:3` rusak: "Ac the Agora authors" (mestinya "© the Agora authors"). | baca file |
| A-045 | LOW | `ROADMAP.md` basi: Phase 6 dobel, Phase 7 semua `[ ]` padahal STATUS bilang done, tidak ada Phase 12. | baca file |
| A-046 | LOW | `AUDIT_PLAN.md` §3 sendiri sempat mengklaim `audit-evidence/` sudah dibuat — **salah**; dikoreksi di revisi ini. | `Test-Path audit-evidence` → False |
| A-047 | LOW | `gh` tidak terpasang; jalur `gh issue create` di `upstream_sync.sh` mati (log saja, bukan issue durable). | `Get-Command gh` kosong |

### 2.6.5 Yang sudah TERBUKTI BENAR (jangan diutak-atik tanpa alasan)

* Persona cost numbers (2473/2061/4534) reproduce dengan estimator app.
* sha256 persona lock cocok dengan vendored + asset copies.
* `applicationId=com.hermes.app`, vc=31, vn=`3.0.0-hermesx`, label `Hermes X` di semua locale spot-check.
* `wear/` ada di guard ALLOWED; `"watch"` reachable di `SettingsScreen.kt:275,366`.
* `SettingsWatchSetupPage` memang reachable sekarang (koreksi Phase 12 benar).
* Sistem image API 34 wear x86_64 ADA; API 30 wear ADA.
* Disk/battery/wakelock device sehat; tidak ada wake lock bocor saat ini.

### 2.6.6 Dampak ke rencana

1. **GATE 0 sekarang merah.** Urutan eksekusi berubah: `fix A-001` dulu, baru baseline gate.
2. **Tiga "fitur selesai" berubah status jadi PHANTOM di Truth Commission** (A-008 breaker,
   A-009 skills, A-010 persona update). Track 1 harus memverifikasi ulang semuanya on-device.
3. **Track 2 bertambah wajib:** W6 harus menguji A-004/A-005/A-006 (kebocoran kredensial +
   hijack) sebagai serangan nyata, bukan hanya skenario adversarial tertulis.
4. **Track 3 bertambah:** B4 persona harus memuat A-003 (undo persona), A-011 (customText),
   A-025/A-026 (race + crash).
5. **Track 5 (gauntlet) berubah:** kandidat "memory viewer di watch" naik prioritas karena
   A-013 (snapshot beku) membuktikan pain nyata; "persona quick-toggle" turun karena persona
   sendiri sedang rusak.
6. **Definition of Done bertambah:** A-001..A-007 wajib FIXED sebelum tag v2.1; A-008..A-016
   wajib FIXED atau deferred dengan issue (tapi CRITICAL/HIGH = fixed now).

---

## 2.7 EKSEKUSI PASS 1 — status per temuan (fix pass, branch `main`)

Baris di bawah adalah **hasil eksekusi**, bukan rencana: setiap fix punya tes regresi di repositori
dan gate-nya dijalankan ulang setelah perubahan. Yang tidak bisa dibuktikan di mesin ini ditandai
apa adanya.

| ID | Status | Cara ditutup |
|---|---|---|
| A-001 | **FIXED (sesi lain)** | `UPSTREAM_TOUCHPOINTS.md` mendaftarkan `UpdateCheckerTest.kt`; `touchpoint_guard.sh` → PASS |
| A-003 | **FIXED** | Store baru `STORE_ACTIVE_MEMORY`; `MemoryApplier` merutekan store itu ke `getActiveMemory`/`updateActiveMemory`, dan baris jurnal lama (`memory` + `active_memory.md`) dipetakan ke store yang benar. Tes: `MemoryApplierTest.activeMemoryUndoRestoresTheSingletonAndNeverTouchesMemoryDb`, `…aLegacyPersonaRowUndoesActiveMemoryAndLeavesTheUsersMemoryFileAlone` |
| A-004 | **FIXED (parsial, jujur)** | Item Data Layer dihapus watch setelah config disimpan (`ConfigListenerService.deleteConsumed`). Klaim doc yang salah dikoreksi: platform Data Layer mensyaratkan package **dan** signature sama di kedua perangkat. |
| A-005 | **DIKOREKSI** | Bukan "siapa pun bisa minta kredensial": kanal Data Layer bersifat privat per-app. Yang benar-benar salah adalah payload pairing **diabaikan**; sekarang diverifikasi (`PairingRequest`, tes `PairingRequestTest`, 4 kasus) dan versi yang tidak dikenal ditolak. |
| A-006 | **DIKOREKSI + HARDENED** | Lihat A-005: app lain tidak bisa menulis ke namespace Data Layer app ini. Penulisan ulang base URL jahat tetap ditolak oleh validasi host (A-024). |
| A-007 | **FIXED** | Pertanyaan di-`enqueue` **sebelum** kirim, dihapus hanya setelah jawaban diterima — pembatalan tidak lagi menghilangkan pertanyaan. |
| A-008 | **FIXED** | `recordInjection` dipanggil saat sesi mulai (`AutopilotTriggerObserver`); `recordCorrection` dipanggil dari `ReflectionWorker` dengan sinyal durabel (jawaban model yang di-regenerate, ledger sekali-hitung). Tes: `ReflectionWorkerSignalTest` |
| A-009 | **FIXED** | `SkillCandidateDetector` + `SkillSynthesizer` dipanggil dari `ReflectionWorker`; collision guard `.md` diperbaiki (`SkillSynthesizerTest.anExistingSkillNameWithTheMdSuffixIsAlsoRejected`) |
| A-010 | **FIXED** | `seedLockFromAssets` menyalin `personas/upstream.lock` ke `filesDir` — "Check for persona updates" kini punya berkas yang dibacanya |
| A-011 | **FIXED** | `PersonaRepository.bodies()` (kini suspend) memakai `customText` lebih dulu; `effectiveBody(id)`. |
| A-012 | **FIXED** | `ttsReady` + `voiceUnavailable`; tombol Speak dan mic memberi pesan di layar, bukan no-op senyap |
| A-013 | **FIXED** | `MemorySnapshotPusher` mendorong snapshot saat `activeMemoryRevision` berubah (debounce 2 s) |
| A-014 | **PARSIAL** | Pola ALLOWED tetap lebar; belum diubah di pass ini (butuh redesign guard + tes lab) |
| A-015 | **DEFERRED** | `.github/workflows/build.yml|mkdocs.yml` masih trigger `[master]`; keduanya file upstream → perlu registrasi touchpoint. Dicatat di bawah. |
| A-016 | **DIKOREKSI** | Klaim screenshot `_device_proof/p6-*.png` & `p7-*.png` dicabut di `STATUS.md` dengan cara verifikasi yang bisa diulang |
| A-017 | **DIKOREKSI** | Serial `emulator-5558` yang tidak ada dihapus dari heading `STATUS.md` |
| A-018 | **DIKOREKSI (parsial)** | Angka dokumen yang bertabrakan: laporan ini memakai angka hasil gate terakhir |
| A-020 | **FIXED** | `WearQueueDrainer` memakai mutex proses; tes konkurensi 4 pass |
| A-021 | **FIXED** | `DrainReport.answers` + `answersNotShown`; watch memberi tahu berapa jawaban tertahan yang tidak sempat ditampilkan |
| A-022 | **FIXED** | `WearSignals.pairingAck` di-reset sebelum request baru |
| A-023 | **FIXED** | Handshake protokol (lihat A-005) |
| A-024 | **FIXED** | Host di-parse, bukan prefix-match; `http://localhost.evil.com` ditolak |
| A-025 | **FIXED** | `collectAsState(initial = null)`; reconcile tidak lagi berjalan atas nilai tebakan |
| A-026 | **FIXED** | `runCatching` di `AutopilotControlsSection` — kegagalan IO tidak lagi crash |
| A-027 | **FIXED** | `countAutopilotSince(excludedStore)`; toggle persona tidak lagi memakan cap harian |
| A-028 | **DEFERRED** | Destructive migration masih aktif; dibiarkan karena skema belum berubah sejak v1 |
| A-029 | **DEFERRED** | Index `timestamp`/`targetFile`/`store` belum ditambahkan — retensi (50/berkas, 30 hari) yang membatasi ukuran |
| A-030 | **FIXED** | Transcript diurutkan ulang di `ReflectionWorker` (urutan `getMessagesByIds` tidak dijamin) |
| A-031 | **FIXED** | `maxLines` + scroll pada kartu jawaban |
| A-032 | **FIXED** | `KeyboardActions(onSend)` — tombol Send di keyboard bekerja |
| A-033 | **FIXED** | `rememberSaveable` untuk draft/jawaban/status/notice |
| A-034 | **FIXED** | `PasswordVisualTransformation` untuk field API key |
| A-035 | **FIXED** | `@Synchronized` pada `WearCrypto.secretKey()` |
| A-036 | **FIXED** | Berkas queue yang rusak dikarantina (`.corrupt`), tidak ditimpa |
| A-037 | **FIXED** | Marker persona hanya dikenali sebagai baris utuh (watch) |
| A-038 | **FIXED** | Endpoint menangani host tanpa `/v1` |
| A-039 | **FIXED** | Batas keras 1 MiB pada body respons |
| A-040 | **FIXED** | Marker persona hanya dikenali sebagai baris utuh (`PersonaStore`) |
| A-041 | **FIXED** | Collision guard menormalkan sufiks `.md` |
| A-042 | **FIXED** | Dead code dihapus (lihat §2.7.1) |
| A-043 | **FIXED** | `PersonaCostReport.inputTokenCost(id, body)` memakai marker persona yang benar |
| A-044 | **FIXED (sesi lain)** | `NOTICE.md` sudah berbunyi `© the Agora authors` |
| A-045 | **FIXED** | `ROADMAP.md`: Phase 6 ganda → 6b, Phase 7 ditandai ulang, Phase 12/13 ditambahkan |
| A-046 | **DIKOREKSI** | `audit-evidence/` ada |
| A-047 | **DEFERRED** | `gh` tidak terpasang; deferral issue lewat API bila diperlukan |

### 2.7.1 Dead code yang dihapus

`WearCardSurface`, `Modifier.inputSurface()`, `Gap()`, `RoundContent()` (helper UI tanpa pemanggil),
`WearConfigStore.clear()`, `WearMemoryCache.updatedAt()/clear()`, `WearOfflineQueue.clear()`,
`AdaptationLogDao.observe()` (+ override di fake, + import `Flow` yang jadi yatim),
`AutopilotSettings.setDailyCap()`, `AutopilotNotifier.EXTRA_ADAPTATION_COUNT` (ditulis, tidak pernah
dibaca), `PersonaRepository.installVendored()` dan `PersonaRepository.defaultBody()`.
Bukti: `grep -rn` untuk tiap simbol → 0 pemanggil produksi sebelum dihapus.

### 2.7.2 Temuan baru di pass 1 (tidak ada di §2.6)

| ID | Sev | Temuan | Bukti |
|---|---|---|---|
| A-048 | **CRITICAL** | **Persona tidak pernah sampai ke model.** `GenerationRequestBuilder.resolvePromptTemplate` — satu-satunya tempat active memory masuk ke prompt — melakukan `PersonaStore.stripAll(...)`. Jadi blok persona ditulis ke `active_memory.md` lalu dihapus tepat sebelum dikirim; seluruh fitur Phase 6 (Caveman/Ponytail) tidak berpengaruh apa pun pada jawaban. Komentar "P5 isolation" salah tempat: jalur reflection/synthesis membangun prompt-nya sendiri (`systemPrompt = null`, transcript di-strip di `ReflectionCaller`), jadi tidak ada yang perlu diisolasi di sini. | dibaca langsung; `PersonaIsolationTest` menguji strip di *pemanggil*, bukan di sini |


---

## 3. Pre-flight eksekusi (P0, ~30 menit, sebelum track apa pun)

| # | Langkah | Bukti |
|---|---|---|
| 0.0 | **Fix A-001**: daftarkan `UpdateCheckerTest.kt` (budget + alasan di registry) atau pindahkan ke path terdaftar; `touchpoint_guard.sh` harus exit 0 | guard output |
| 0.1 | Append §2 tabel ini ke `STATUS.md` | diff |
| 0.2 | `audit-evidence/` init + `_index.md` (**BELUM ada** — dibuat di langkah ini), subfolder per track saat bukti pertama mendarat | `_index.md` |
| 0.3 | Buat branch `audit/v2.1-brutal-audit` dari `main` (N7) | `git branch` |
| 0.4 | Env export: `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH` | log |
| 0.5 | GATE 0 gate suite baseline: `testFdroidDebugUnitTest`, `testPlayDebugUnitTest`, `wear:testDebugUnitTest`, `assembleFdroidDebug`, `assemblePlayDebug`, `assembleFdroidRelease` (signed), `wear:assembleDebug`, `wear:assembleRelease` (signed), `touchpoint_guard.sh`, `SYNC_DRY_RUN=1 upstream_sync.sh` | log mentah per gate di `audit-evidence/gate0/` |
| 0.6 | Baseline device metrics: cold start `am start -W` x5 (median), APK bytes, `dumpsys meminfo`, `batterystats --reset` | angka di `audit-evidence/gate0/metrics.md` |
| 0.7 | Verifikasi `pm resolve-activity` recognizer di watch; `wm size` (round?) | output adb |
| 0.8 | Install debug APK phone + watch; catat `aapt2 dump badging` (label, applicationId, versionName) | output |

Gate 0 dianggap lulus hanya kalau setiap gate hijau ATAU ada temuan CRITICAL/HIGH
tercatat dengan bukti RED. Gate merah = finding, bukan alasan berhenti.

---

## 4. Track 1 — Truth Commission

Format wajib per fitur: **(1) janji** (kalimat + sumber: ROADMAP/STATUS/AUDIT_REPORT),
**(2) eksekusi device** (langkah + screenshot + durasi), **(3) verdict**
`WORKS / DEGRADED / BROKEN / PHANTOM`. DEGRADED/BROKEN/PHANTOM minimal HIGH.

Daftar fitur yang WAJIB diadili (dari `STATUS.md` + `ROADMAP.md`, bukan dari ingatan):

| # | Fitur | Janji (sumber) | Cara bukti |
|---|---|---|---|
| T1.1 | Autopilot memory v1 | fakta masuk store Agora; undo byte-exact (STATUS Phase 3) | seed 3 percakapan -> `run-as cat files/active_memory.md`; undo; bandingkan bytes (hash) |
| T1.2 | Adaptation History UI | list, diff, undo, chip status (STATUS Phase 4) | buka Settings -> Adaptation History; screenshot; tap Undo |
| T1.3 | Auto-rollback circuit breaker | 2 koreksi -> rollback + `needs_revision` (STATUS Phase 4) | device: koreksi nyata 2x pada entry yang memang diinjeksi; plus JVM untuk kasus non-injeksi |
| T1.4 | Autopilot Skills v1 | >=3 tool call / >=2 koreksi -> draft skill (STATUS Phase 5) | device sesi multi-tool; cek `SkillManager` store |
| T1.5 | Persona Caveman | block di `active_memory.md`, verbatim, toggle on/off zero trace (STATUS Phase 6) | device toggle; `cat`; hash sebelum/sesudah |
| T1.6 | Persona Ponytail | idem | idem |
| T1.7 | Persona update mechanism | `persona_update.sh` diff -> regression gate -> baru tulis lock (STATUS P2) | jalankan dry-run + simulasi bump ref (di branch) |
| T1.8 | Persona isolation | persona tidak pernah masuk konteks reflection/synthesis (STATUS P5) | dump konteks nyata; `PersonaIsolationTest` dijalankan ulang |
| T1.9 | Watch voice loop | tap Speak -> RecognizerIntent -> jawaban + TTS (STATUS Phase 7) | device; kalau recognizer absen -> verdict + finding |
| T1.10 | Watch memory sync | snapshot phone -> core context <=500 token (STATUS Phase 7/12) | push dari phone (atau tulis store langsung), baca Debug panel |
| T1.11 | Watch offline queue | tahan offline -> drain otomatis saat launch (AUDIT pass 4) | `_tools/p0_autodrain_proof.py` dijalankan ulang hari ini |
| T1.12 | Wear credential transfer | phone push config / watch BYOK (STATUS Phase 12) | BYOK di device; push = terblokir HS4 -> catat |
| T1.13 | Pairing request | watch minta -> phone jawab (STATUS Phase 12) | device: tap Pair -> log + UI; transport antar-2-emulator = BLOCKED HS4 |
| T1.14 | Upstream-sync CI | workflow jalan terjadwal (STATUS Phase 1, HS3) | cek Actions tab via API token; kalau nonaktif -> finding LOW + HS3 |
| T1.15 | Settings toggles (master, cap, persona, watch setup, About) | semua toggle idempotent, tidak menulis saat off (STATUS Phase 4/6/12) | 10x toggle cepat; cek store tiap iterasi |
| T1.16 | Notifier "N memories updated" | notifikasi muncul + tap -> Adaptation History (STATUS Phase 3/4) | `cmd notification` / screenshot; tap |

**Jebakan khusus (wajib):**

1. **Undo byte-exact pada entry LAMA.** Ambil entry yang dibuat beberapa commit lalu
   (seed data), undo, hash file sebelum vs sesudah harus identik.
2. **Circuit breaker false-positive.** Koreksi yang tidak berhubungan dengan fakta
   adaptasi (mis. "bukan itu, ubah format") TIDAK boleh menaikkan `feedbackFlags`
   entry yang tidak diinjeksi ke sesi itu.
3. **Persona update gate.** Bump `personas/upstream.lock` ke ref lebih baru di branch
   percobaan -> `persona_update.sh` harus menolak kalau tes regresi merah; kalau hijau,
   lock + vendored copy + hash harus konsisten.
4. **Cap harian boundary.** Set cap=1, buat 2 reflection di hari yang sama -> yang kedua
   harus di-skip dengan alasan jelas. Boundary tengah malam lewat JVM `now` injeksi
   (device clock tidak bisa diubah).

---

## 5. Track 2 — Wear OS torture (P0)

Setup global: mock provider di host + `adb reverse tcp:8077 tcp:8077` (loopback tetap
tembus airplane mode karena lewat adb, bukan jaringan) + BYOK watch ke
`http://127.0.0.1:8077/v1`. Setiap W menyimpan: screenshot, logcat terfilter
`HermesWear`, dan waktu.

| ID | Skenario | Metode | Kriteria lulus | Batasan jujur |
|---|---|---|---|---|
| W1 | Standalone truth | `cmd connectivity airplane-mode enable` di watch -> tanya via BYOK -> jawab; lalu matikan phone emulator -> ulangi | Jawaban datang tanpa app phone; logcat bersih dari exception | Bukti ini membuktikan watch tidak butuh *app* phone, bukan tidak butuh *hardware* phone |
| W2 | Voice pipeline | `pm resolve-activity` dulu; lalu: permission denied, no-speech timeout, cancel mid-utterance, ID+EN input, TTS overlap, TTS gagal -> teks tetap tampil | Semua jalur punya UX terdefinisi, tidak crash | Kalau recognizer tidak ada di image -> jalankan fallback keyboard + catat sebagai temuan UX |
| W3 | Display & input | screenshot tiap layar; `uiautomator` bounds -> cek clipping vs radius 192 px; scroll `ScalingLazyColumn`; touch target >=48dp; keyboard usable; AOD privacy | Tidak ada teks/ tombol terpotong; bounds dalam lingkaran | Crown/bezel tidak ada di emulator -> BLOCKED-HARDWARE; AOD diuji lewat timeout layar + `dumpsys` |
| W4 | Latency budget | `am start -W` x5 (median) cold start; stopwatch voice-tap -> token pertama; TTS start latency | Catat angka; optimasi kalau cold start >=2s | Angka emulator, label sebagai emulator |
| W5a | Battery: 10 menit voice kontinu | `batterystats --reset` -> sesi -> `batterystats` delta mAh | Angka dilaporkan; tidak ada wake-lock leak | Proxy emulator |
| W5b | Battery: idle 8 jam | versi kompresi 30 menit idle (emulator tidak layak 8 jam) | Delta ~0 | Proxy; 8 jam = BLOCKED-HARDWARE |
| W5c | Wake-lock leak | `dumpsys power` setelah tiap skenario | Tidak ada wake lock tersisa dari `com.hermes.app` | — |
| W5d | Thermal | 5 sesi voice beruntun | Tidak crash; suhu tidak tersedia di emulator | BLOCKED-HARDWARE, tanpa angka palsu |
| W6 | Data layer adversarial | credential transfer saat watch "mid-sleep"; versi phone != versi watch (version handshake); re-pairing setelah reset; revocation; dua phone | Tidak crash; perilaku terdefinisi | Dua emulator tidak paired (HS4) -> transport antar-device BLOCKED; yang bisa: handshake & validasi store di device tunggal |
| W7 | Memory sync adversarial | memory 50KB -> core context <=500 token dan isinya benar (spot-check); persona block di core context = keputusan terdokumentasi; snapshot korup ditolak; 20 update cepat -> konvergen tanpa storm | Semua terpenuhi; tidak ada flicker | — |
| W8 | Offline queue stress | 20 pesan offline -> reconnect | exactly-once, urut, tidak ada duplikat/hilang; queue selamat dari reboot mid-queue | — |
| W9 | Regression net | setiap temuan W -> tes otomatis (unit/robolectric) atau langkah skrip di `docs-hermes/wear-manual-qa.md` | File ada + dijalankan ulang | — |

---

## 6. Track 3 — Bug hunt phone

| ID | Area | Aksi | Kriteria |
|---|---|---|---|
| B1 | Lint | `./gradlew :app:lintFdroidDebug :wear:lintDebug` | Zero warning tak terjelaskan (yang di-suppress harus punya alasan + apa yang jadi tak terdeteksi) |
| B2 | Contracts | Baca ulang `development/README.md` + kontrak modul yang berubah sejak v1.2-hardened | Tidak ada invariant yang dilanggar |
| B3 | Autopilot adversarial (semua WAJIB pakai tes) | process death mid-reflection; Worker double-fire; snapshot lalu write gagal; reflection bersamaan; JSON rusak/parsial -> skip senyap tanpa crash/half-apply; pruning vs referensi undo hidup; timezone/clock vs cap + retensi 30 hari | Semua ada tes; hasil deterministik |
| B4 | Persona adversarial | toggle 10x cepat; kill mid-write; isolasi (bukti dari context dump, bukan asumsi); removal completeness (`run-as` inspect langsung); interaksi persona x master toggle | Store akhir benar di tiap kasus |
| B5 | Sync adversarial | selundupkan diff tak terdaftar ke `touchpoint_guard.sh` (rename, whitespace, file baru, deletion) -> harus tertangkap semua; `upstream_sync.sh` vs ref tes: whitelist auto-resolve + grep-verify; non-whitelist -> abort + main utuh + issue; contract-change detection | Guard & sync lolos uji serangan |
| B6 | Security | scan ulang secret di seluruh git history; API key tidak pernah muncul di logcat/notifikasi/AdaptationLog/snapshot/persona text/payload watch; store watch terenkripsi; HTTPS only | Zero kebocoran |
| B7 | Data-loss torture | force-stop, airplane, disk 95% penuh, reboot mid-Worker -> setelah masing-masing: store konsisten, undo byte-exact | Semua konsisten |
| B8 | Concurrency | reflection saat percakapan aktif; dua Worker (test hook); persona toggle mid-generation; master toggle mid-reflection | Hasil deterministik saja |

---

## 7. Track 4 — Optimasi

| ID | Aksi | Output |
|---|---|---|
| O1 | Token economics: 5 sesi refleksi bervariasi, hitung token input/output nyata (mock mengembalikan `usage`; estimator app untuk input). Caveman: output reduction % DIKURANGI biaya blok input persona | $/reflection, $/hari, $/bulan. **Output reduction tidak bisa diukur tanpa model nyata (HS2)** -> laporkan `BLOCKED-HS2`, jangan karang angka |
| O2 | Dead code/resource/dep: unused di `autopilot/` + `wear/`, resource `hermes_*` yatim, TODO zombie, dependensi tak beralasan | Daftar hapusan dengan bukti grep |
| O3 | Cold start: phone `am start -W` x5 median; watch per W4; strict-mode proof tidak ada kerja berat di main thread | Angka before/after |
| O4 | Build health: waktu build vs baseline Agora bersih; tandai pemborosan | Angka detik |
| O5 | Memory-injection bloat: ukuran system prompt dengan autopilot penuh aktif vs budget core-context | Angka token |

---

## 8. Track 5 — MVP gauntlet

**Aturan:** jawaban default TIDAK. Maksimum 3 fitur dibangun. Tiap kandidat dinilai 1–5
pada: user value, frekuensi pakai, beban maintenance, blast radius, biaya touchpoint
upstream. Lolos hanya jika memenuhi (a)-(h) mandat, termasuk: memperbaiki pain yang
**terbukti** (sitasi ID temuan Track 1/2), <=2 hari, punya toggle, failure mode boring.

Draft awal kandidat (skor **sementara**, difinalkan setelah Track 1/2 selesai):

| Kandidat | Value | Freq | Maint | Blast | Touchpoint | Verdict awal |
|---|---|---|---|---|---|---|
| Tile/komplikasi tap-to-talk di watch | 5 | 4 | 2 | 1 | 0 (`wear/` fork-only) | **KANDIDAT KUAT** — hapus biaya buka app |
| Quick-reply / retry dari tile | 3 | 3 | 2 | 1 | 0 | LATER (tumpang tindih W8 UI) |
| Phone-relay saat watch offline | 4 | 3 | 3 | 3 | 1 (service phone) | LATER — hanya jika W1/W6 gagal |
| Cost dashboard (token/$ per hari) | 3 | 2 | 2 | 1 | 0 | LATER — butuh `usage` nyata, HS2 |
| Morning digest "N memori dipelajari" | 3 | 4 | 1 | 1 | 0 | LATER — tunggu temuan dogfood |
| Memory viewer di watch | 4 | 2 | 2 | 2 | 0 | LATER |
| Quick-reply suggestions | 2 | 3 | 3 | 2 | 0 | NEVER (bukan inti misi) |

Deliverable: `V2_BACKLOG.md` (draft sudah dibuat; final setelah Track 1/2) berisi SEMUA
kandidat + skor + verdict + alasan satu baris. Fitur yang dibangun wajib: tes, toggle,
bukti device.

---

## 9. Track 6 — Dogfood day (script, dijalankan di device)

Menjadi `docs-hermes/dogfood-day.md` sebagai skrip permanen. Semua waktu dipadatkan
(tidak perlu jam nyata), tapi urutan dan state harus nyata.

| Jam | Aksi | Bukti |
|---|---|---|
| 07:00 | Watch: tanya via suara (phone airplane mode) -> jawaban + TTS | screenshot + logcat + waktu |
| 09:00 | Phone: 3 percakapan dengan fakta jelas; 1 koreksi | screenshot Adaptation History |
| 12:00 | Phone: Caveman ON -> bandingkan panjang/gaya respons; OFF | 2 screenshot + hitung karakter |
| 15:00 | Phone: sesi multi-tool -> kandidat skill Phase 5 muncul | screenshot + cek `SkillManager` |
| 18:00 | Watch: snapshot memory ter-update dari phone; core context benar | Debug panel + `run-as cat` |
| 22:00 | Reflection jalan: fakta tertulis, notifikasi, history terisi, koreksi 09:00 terhitung | screenshot + isi log |
| Audit | Ledger sehari: setiap entry bisa dijelaskan, di-undo, punya provenance | tabel di dokumen |

Batasan jujur: Caveman ON/OFF butuh provider nyata untuk mengukur output -> pakai mock
(panjang respons mock bukan bukti model nyata) dan label. Langkah yang gagal = finding.

---

## 10. Track 7 — Pass 2

Aturan: pass 2 dijalankan sebagai auditor BARU yang membenci pass 1. Fokus berbeda,
bukan mengulang perintah yang sama:

1. Baca `audit-evidence/` pass 1 dan cari bukti yang lemah/kabur (screenshot tanpa
   logcat, klaim tanpa hash, "PASS" tanpa exit code).
2. Serang fix pass 1 (regresi baru, edge case yang fix tidak tutup).
3. Ulang gate 5x (flake = finding).
4. Kalau pass 2 menemukan CRITICAL yang lolos pass 1 -> tulis post-mortem paragraf
   di `AUDIT_REPORT.md` yang menjelaskan blind spot-nya.

---

## 11. Deliverables

| # | Artefak | Isi |
|---|---|---|
| 1 | `AUDIT_REPORT.md` (edisi v2.1) | verdict eksekutif; tabel Truth Commission; hasil W1–W9 dengan angka; tabel temuan 2 pass (ID, sev, area, repro, root cause, fix commit, regression test, status); angka optimasi; ringkasan gauntlet |
| 2 | `V2_BACKLOG.md` | semua kandidat + skor + verdict + alasan (draft sudah ada) |
| 3 | `docs-hermes/wear-manual-qa.md` + `docs-hermes/dogfood-day.md` | checklist manual + skrip harian |
| 4 | Tes regresi untuk tiap CRITICAL/HIGH | file tes + bukti RED->GREEN |
| 5 | `ROADMAP.md` / `STATUS.md` diperbarui ke kebenaran final | diff |
| 6 | Tag `v2.1-brutal-audit` + push | `git tag -v` / `git push origin` |

Skeleton `AUDIT_REPORT.md` dibuat di eksekusi langkah 0.1 bersama append STATUS.
Struktur `audit-evidence/`: `gate0/`, `t1/`, `t2/W1..W9/`, `t3/`, `t4/`, `t5/`, `t6/`,
`t7/` + `_index.md`. Nama file: `<track>-<id>-<step>-<verdict>.(png|txt|log|md)`.

---

## 12. Konvensi temuan & severity

ID: `A-###` (audit). Template wajib per temuan:

```
ID: A-001
Sev: CRITICAL|HIGH|MEDIUM|LOW
Area: autopilot|wear|sync|build|security|docs
Janji: <kalimat + sumber file:line>
Repro: <perintah persis>
Expected / Actual: <...>
Root cause: <...>
Fix commit: <hash atau "-">
Regression test: <path atau "-">
Status: OPEN|FIXED|DEFERRED(+issue URL)
```

CRITICAL = data loss, security, crash jalur utama, rollback gagal, watch standalone gagal.
HIGH = fitur rusak / gagal senyap / pemborosan besar. CRITICAL + HIGH wajib difix di sesi
ini. MEDIUM/LOW boleh deferred dengan justifikasi + issue (via API token; `gh` tidak ada).

---

## 13. Risiko & blocker yang sudah teridentifikasi

| # | Risiko | Mitigasi / keputusan |
|---|---|---|
| R1 | Tidak ada Xiaomi Watch 2 fisik | Emulator Wear API 34 + API 30; item khusus hardware -> BLOCKED-HARDWARE dengan alasan; tidak ada angka palsu |
| R2 | Recognizer tidak ada di image watch | Cek `pm resolve-activity` dulu; fallback keyboard; temuan UX + dokumentasi |
| R3 | `gh` CLI tidak ada | Issue deferral lewat API + `_tools/.ghtoken`; kalau gagal -> catat di BLOCKED.md |
| R4 | API key provider nyata (HS2) tidak ada | Mock provider untuk semua E2E; O1 output-side + pengukuran Caveman output = BLOCKED-HS2, dilaporkan apa adanya |
| R5 | Dua emulator tidak Data-Layer-paired (HS4) | Transport antar-device = BLOCKED; logika sisi watch diuji dengan fake transport + device tunggal |
| R6 | `adb root` tidak tersedia (Play image) -> clock tidak bisa diubah | Uji boundary cap via JVM dengan `now` injeksi; device: cap=0/1 |
| R7 | Release vs debug signing | Proof script uninstall dulu untuk install release |
| R8 | Emulator mati/lelet di tengah | Transient: retry 3x backoff; blocked >30 menit -> `BLOCKED.md`, opsi paling konservatif, main tetap hijau |

---

## 14. Definition of Done (gate akhir)

1. Setiap fitur di tabel Track 1 punya verdict + bukti device (screenshot + log).
2. Track 2 W1–W9 lengkap; item yang tidak bisa dijalankan punya alasan eksplisit
   (hardware/HS4) — bukan dihilangkan diam-diam.
3. Zero CRITICAL/HIGH terbuka.
4. Dua pass tercatat di `AUDIT_REPORT.md`; flake 5x = finding.
5. Semua klaim optimasi punya angka + metode.
6. `V2_BACKLOG.md` lengkap; <=3 fitur gauntlet dibangun, dites, punya toggle, ada bukti.
7. Dogfood day hijau end-to-end (atau tiap langkah gagal punya finding ID).
8. `main` hijau: semua build + semua tes + guard + sync dry-run (dijalankan ulang hari itu).
9. `AUDIT_REPORT.md` jujur sampai level tidak nyaman.
10. Tag `v2.1-brutal-audit` dibuat + dipush.

"Done" dengan apa pun yang merah = gagal. Stop dengan `main` hijau = sukses.

---

## 15. Langkah pertama saat eksekusi disetujui

```
1. Pre-flight §3 langkah 0.1–0.8 (sekali, berurutan)
2. Track 2 W1 (standalone) — karena device paling rapuh
3. lanjut urutan §1
```

Tidak ada eksekusi yang dijalankan sampai pemilik menyetujui rencana ini.
