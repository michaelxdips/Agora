# V2_BACKLOG.md — Hermes X v2.1 candidate features

> **Status: DRAFT (planning pass).** Skor difinalkan setelah Track 1+2 selesai, karena aturan
> gauntlet (h) menuntut pain yang **terbukti** dengan ID temuan, bukan opini.
> Aturan default: **TIDAK**. Maksimum 3 fitur dibangun. Backlog pendek yang bagus menang
> atas daftar panjang yang dibangun.

Skala penilaian 1–5: **value** (nilai user), **freq** (frekuensi pakai), **maint** (beban
maintenance — makin tinggi makin berat), **blast** (radius kerusakan kalau salah), **tp**
(biaya touchpoint upstream baru). Verdict: BUILD / LATER / NEVER + alasan satu baris.

## Kandidat (draft — skor final setelah Track 1/2)

| # | Kandidat | value | freq | maint | blast | tp | Verdict draft | Alasan |
|---|---|---|---|---|---|---|---|---|
| 1 | **Watch memory snapshot auto-refresh** (phone push ulang tiap memory berubah, atau watch pull saat online) | 5 | 4 | 2 | 1 | 0 | **BUILD (kandidat kuat)** | A-013: snapshot beku selamanya sejak setup — pain terbukti, perbaikan kecil, murni `wear/` + `autopilot/wearsync/` |
| 2 | **Tile / komplikasi tap-to-talk** di watch face | 5 | 4 | 3 | 1 | 0 | **BUILD (kandidat)** | Hapus biaya buka app; surface resmi Wear OS; `wear/` fork-only. Butuh ProtoLayout dep baru — ukur dampak ke APK 2.7 MB |
| 3 | **Watch queue visibility + retry/discard yang benar** (drain tanpa duplikat, semua jawaban tidak hilang) | 4 | 3 | 1 | 2 | 0 | **BUILD (kandidat)** | A-020/A-021: drain bisa dobel-kirim & menjatuhkan N-1 jawaban — perbaikan kecil, nilai tinggi, bug nyata |
| 4 | **Voice/TTS fallback yang terlihat** (tombol Speak menampilkan "voice unavailable on this watch" + keyboard fokus otomatis) | 4 | 5 | 1 | 1 | 0 | **BUILD (kandidat)** | A-012: di image tanpa TTS/recognizer tombol mati senyap. Aksesibilitas = misi inti |
| 5 | Phone-relay mode saat watch offline | 4 | 3 | 3 | 3 | 1 | LATER | Hanya jika W1/W6 gagal; menambah service phone = touchpoint |
| 6 | Cost dashboard (token/$ per hari) | 3 | 2 | 2 | 1 | 0 | LATER | Butuh `usage` provider nyata (HS2) untuk angka jujur |
| 7 | Morning digest notifikasi ("N memori dipelajari semalam") | 3 | 4 | 1 | 1 | 0 | LATER | Tunggu temuan dogfood; mudah, tapi bisa jadi noise |
| 8 | Memory viewer read-only di watch | 3 | 2 | 2 | 2 | 0 | LATER | Tumpang tindih dengan #1; setelah #1 stabil |
| 9 | Quick-reply suggestions di watch | 2 | 3 | 3 | 2 | 0 | NEVER | Bukan inti misi (auto-adapt / aksesibilitas); biaya model tinggi |
| 10 | Persona quick-toggle di tile | 3 | 3 | 2 | 2 | 0 | NEVER (tunda) | Persona sendiri sedang rusak (A-003/A-010/A-011) — perbaiki dulu |
| 11 | Rotasi API key watch | 2 | 1 | 2 | 2 | 0 | NEVER | Threat model belum butuh; lihat temuan audit "explicitly not recommended" |
| 12 | Streaming respons di watch | 2 | 2 | 3 | 2 | 0 | NEVER | Keputusan lama (WearChatClient KDoc) masih benar; layar kecil, jawaban pendek |

## Aturan gauntlet (dari mandat v2.1 §5.3) — checklist per kandidat BUILD

Sebuah fitur hanya boleh dibangun jika **semua** terpenuhi:

- (a) melayani misi inti (auto-adapt / aksesibilitas wear)
- (b) memperbaiki pain yang **terbukti** — sitasi ID temuan Track 1/2
- (c) nol / mendekati nol touchpoint upstream baru
- (d) bisa diimplementasi + dites dalam <2 hari
- (e) punya kill switch / toggle
- (f) bisa disebutkan user dan momen persisnya
- (g) failure mode membosankan (degradasi anggun, tidak pernah korupsi)
- (h) siap ditaruhkan taruhan tag v2.1

## Status pra-gauntlet kandidat teratas

| Kandidat | (a) | (b) | (c) | (d) | (e) | (f) | (g) | (h) | Catatan |
|---|---|---|---|---|---|---|---|---|---|
| #1 memory refresh | ya | ya — A-013 | ya | ya | ya (toggle di Watch setup) | "owner, saat tanya jam setelah menambah fakta baru di phone" | ya — gagal = pakai snapshot lama, tidak korupsi | ya | Menunggu repro W7 |
| #3 queue benar | ya | ya — A-020/A-021 | ya | ya | ya | "owner, saat reconnect setelah 20 pertanyaan offline" | ya — antrean tetap utuh | ya | Menunggu repro W8 |
| #4 voice fallback | ya | ya — A-012 | ya | ya | ya | "owner, di jam tanpa TTS engine, saat menekan Speak" | ya — hanya teks info | ya | Menunggu repro W2 |

**Belum ada yang dibangun.** Backlog ini akan difinalkan (termasuk #2 Tile) setelah Track 1 dan
Track 2 menghasilkan bukti device, sesuai aturan "pain terbukti".
