# Zenith Audio — Phone Dictation (design)

> **Last updated:** 2026-11-09
> **Status:** bridge and Android IME implemented. The IME still needs validation on the phone (Phase 2 acceptance).
> **Branch:** `feat/phone-dictation`, stacked on `feat/mic-quality` (which is stacked on PR #2).
> **Scope:** phone microphone → PC speech recognition → text inserted on the phone or on the PC.
> **Purpose:** record the decisions before the code exists, so the implementation does not drift into a worse architecture.

---

## 0. TL;DR

- The phone already sends **48 kHz mono PCM16, 10 ms frames** to the PC over ZAP/UDP 59100.
- Speech recognition runs on the **PC**, using **Hex** (`hex service --embedded`) as a library with the pt-BR Parakeet `-30s` fine-tune (`custom-7d13a4fb474b2100`). Stock Parakeet v3 is not used: its Portuguese training data is European Portuguese and it cannot be told which language to output (§2.3).
- A small **bridge** process on the PC accepts the dictation audio over **TCP**, keeps Hex's model resident, and returns text on the same connection. It also sends **live partials** by re-transcribing the uncertain tail while the person speaks.
- The final text always transcribes the **whole utterance**, so partials are tentative and the client commits only the final result.
- The phone gets the text through a **custom Android IME** (a voice keyboard) and inserts it with `InputConnection.commitText()`; partials are shown as composing text.
- **The trigger decides the destination.** Press the phone keyboard's mic → text goes to the phone. Press the PC hotkey → text goes to the PC. No router and no synchronized target selector.
- The bridge live mode is implemented now, without waiting for Hex #1136/#1470 (§2.5, §6.7).

---

## 1. Goal and non-goals

### Goal

Use the phone as the only microphone, run the heavy speech model on the PC, and insert the recognized text **where the user is typing** — which may be the phone or the PC.

### Non-goals (v1)

- Replacing ZAP or changing the real-time microphone path used by Discord/Zoom.
- Voice commands or LLM post-processing (punctuation, rewriting, "translate this").
- Multiple simultaneous dictation users/devices.
- Cloud speech recognition. Everything stays on the LAN and on the PC.

Live partial text is **in scope** (it is implemented in the bridge); it is shown as provisional composing text and replaced by the final transcript.

---

## 2. Current state and constraints

### 2.1 Zenith today

| Item | Value |
|---|---|
| Phone → PC audio | UDP 59100, ZAP, 48 kHz mono PCM16, 480-sample (10 ms) frames |
| Jitter buffer | 40 ms target, 80 ms cap, PLC up to 150 ms |
| PC virtual source | PipeWire `Audio/Source` "Zenith Wireless Microphone", F32 mono 48 kHz |
| Android capture | `MicAudioRecorder` (622 lines) with source/device selection, HPF, gain, limiter |
| Settings | `zenith_prefs` (`SharedPreferences`), already shared by activity and service |
| Discovery | `ServerDiscovery` (UDP probe/beacon), result stored as `server_ip` |
| PC → phone audio | Independent Opus path; not relevant here |

The virtual microphone already makes the phone usable by any PC app. **The missing piece is not audio; it is text coming back.**

### 2.2 Hex (verified 2026-11-08)

Hex is a local dictation app written in Rust, MIT licensed. It does not accept pull requests, so it is consumed as a black box.

Two relevant surfaces:

| Surface | What it does | Fit for this project |
|---|---|---|
| `hex service --embedded` + TypeScript SDK (`@kitlangton/hex`) | Loads a model, accepts **WAV 8–192 kHz, 1–8 channels**, returns text. No microphone access. Keeps the model loaded between calls. | **Primary.** No resampling, no lease, no SSE. |
| `POST /streams` (live transcription) | Requires a running Hex with a GGUF model; `16 kHz` mono only; one stream at a time; 10 s lease; SSE events with partial/segment/final. | Later, only if live text is wanted. |

Facts that shaped the decision:

- `hex service [--embedded]` serves the same local API as the running app, including `/streams`, and is designed for host-owned audio.
- The SDK is published (`@kitlangton/hex`, ESM, Node >= 20.3, no runtime dependencies) and works with Bun.
- Upload limits: 64 MiB per clip, 8–192 kHz, 1–8 channels. At 48 kHz mono PCM16, 64 MiB ≈ 11 minutes; our own cap is much lower.
- `transcribe()` never downloads a model; `create({ model })` downloads and loads one. The first call can therefore be slow.
- **Streaming models** in the catalog (at this commit): `parakeet_unified_en` (English only) and `nemotron_3_5_asr` (multilingual, includes `pt`). **`parakeet_v3`, `parakeet_ultra`, `whisper_large_v3_turbo` do not stream.**
- `parakeet_ultra` is Labs-gated. `parakeet_v3` is the stable multilingual default, but for Brazilian Portuguese it is the **wrong** choice: NVIDIA's card says its Portuguese training data is **European Portuguese**, and the model takes no language prompt, so it auto-detects and can drift (see §2.3).
- Models are Q8_0 GGUF, roughly **740–890 MB** each, so two copies fit in the RX 6500 XT's 4 GB, but running two engines is still wasteful.
- On Linux, inference uses Vulkan when available and falls back to the CPU. The window still needs a Vulkan driver even for CPU inference. `hex service --embedded` is the path that avoids the window, but it must be verified headless (see §12).
- On KDE Wayland, Hex pastes through the **RemoteDesktop portal** (not `wtype`), and Live Typing (input-method preedit) is **not** available on KWin.

### 2.3 Choosing the Portuguese model

The model choice matters more than any code in this document. Parakeet v3 is not a safe default for pt-BR:

- NVIDIA's model card, evaluation note 2: *"our training data uses European Portuguese while most benchmarks use Brazilian Portuguese."* Vocabulary and accent are pt-PT, not pt-BR.
- The model **has no language input or output**. NVIDIA closed the request for one with *"unfortunately parakeet-v3 model doesn't receive or output language id"* (NVIDIA-NeMo/Speech#14799). It guesses from the audio.
- Because it guesses, short spans are unstable: a reported 90 s pt-BR recording came out in Portuguese, while a 25 s slice of the same audio came out in fluent English. For short tap-to-talk dictation (a few seconds), that is the worst possible failure mode.

Options with external evidence (Portuguese WER from a public pt-BR comparison; prepared = read speech, spontaneous = natural speech):

| Model | Prepared | Spontaneous | pt-BR fit | Hex |
|---|---|---|---|---|
| pt-BR Parakeet fine-tune (`-plus`, published) | **7.5%** | **14.3%** | Portuguese by construction, no language detection to fail | Not public; the published numbers are this checkpoint |
| `whisper_large_v3_turbo` | 8.4%* | 23.4%* | Good; the `pt` hint prevents the Parakeet-style silent language switch | Native, GPU, 886 MB |
| `parakeet_v3` (stock) | 9.0% | 21.8% | Risky: pt-PT training, no language control, English drift | Native, GPU, 740 MB |
| `nemotron_3_5_asr` | — | 20.7%** | Worse; streaming is its advantage | Native, GPU, 751 MB |
| `qwen3_asr06_b`, `cohere_transcribe`, `granite_speech_5` | — | — | Unknown for pt-BR; no benchmark found | Native |

\* Whisper **large-v3** numbers; turbo is ~0.4 WER points behind on English benchmarks, so expect a similar small gap.
\*\* A single 90 s pt-BR recording, not a benchmark; treat as indicative.

Measured on this machine on 2026-11-08 (same audio for every model; see §16 for the corpus). The fine-tune row is the **`-30s`** checkpoint, not the published `-plus`:

| Model | FLEURS (read, 50 clips) | TAGARELA (spontaneous, 10 clips) | CLI median, per clip |
|---|---|---|---|
| `-30s` fine-tune (Q8_0) | 7.60% raw · **4.05%** without digit sentences | **18.44%** | **0.73 s** |
| `whisper_large_v3_turbo` (Q8_0) | **5.00%** raw · 4.50% without digit sentences | 22.95% | 4.51 s |
| `parakeet_v3` stock (Q8_0) | 5.45% raw · 4.73% without digit sentences | 21.72% | 0.75 s |

The fine-tune's raw FLEURS number is inflated by **number formatting**, not recognition: it writes "mil e quatrocentos" where the reference has "1.400". On the 42 clips without digits it leads. On spontaneous speech — the closest to dictation — it leads clearly, and it is ~6× faster than Whisper on CPU.

Decision (2026-11-08):

- **Chosen:** the `-30s` pt-BR Parakeet fine-tune (`alexandreacff/parakeet-tdt-v3-ptBR-30s`), imported as `custom-7d13a4fb474b2100`. It is v3-based (8192-token multilingual tokenizer), runs on Vulkan through Hex, and measured 4.05% on FLEURS without digit sentences and 18.44% on TAGARELA spontaneous.
- **`-plus` is not available.** Third-party cards (`alefiury`'s ONNX, `luizhrocha`'s GGUF) point to `alexandreacff/parakeet-tdt-0.6b-v3-ptBR-plus`, but the repo answers 401 and the author's public profile lists only `-30s`, `-20s`, `tagarela-v2` and `distil-whisper-tagarela-v2`. Do not build the plan on it; revisit only if the author publishes it or shares the `.nemo`.
- **`tagarela-v2` is a different lineage,** not a newer `-30s`: it is Parakeet **v2** (English) with a **1024-token English** tokenizer (`en_mm_ytc/tokenizer_spe_bpe_v1024`), while `-30s` is Parakeet **v3** with an 8192-token multilingual tokenizer. Its vocab has 2 tokens containing `ç`/`ã`/`ção` against 14 in the `-30s` vocab, so it is likely worse for pt-BR; not pursued.
- **Whisper remains the fallback** if the `-30s` model fails on the user's voice.
- **Do not** ship stock `parakeet_v3` for pt-BR.
- **Number formatting:** the fine-tune spells numbers out. If digits are wanted, add an inverse text normalization step later; do not change the acoustic model for it.

Sibling checkpoints, for the record:

| Repo | Base | Tokenizer | Status |
|---|---|---|---|
| `parakeet-tdt-v3-ptBR-30s` | Parakeet v3 (multilingual) | 8192 BPE | **chosen**, imported |
| `parakeet-tdt-v3-ptBR-20s` | Parakeet v3 (multilingual) | 8192 BPE | public sibling, untested |
| `parakeet-tdt-0.6b-v3-ptBR-plus` | Parakeet v3 (per the ONNX vocab) | 8192 BPE | private/removed; not reachable |
| `parakeet-tdt-tagarela-v2` | Parakeet v2 (English) | 1024 English BPE | likely worse for pt-BR; not pursued |

Beware of the already-published pt-BR GGUF (`luizhrocha/parakeet-tdt-0.6b-v3-ptBR-gguf`): it was converted with **parakeet.cpp**, so its metadata uses `parakeet.encoder.*` instead of transcribe.cpp's `stt.parakeet.encoder.*` and `stt.variant`. Hex cannot load it as-is; it is useful only if the bridge runs `parakeet-cli` instead of Hex.

### 2.4 What this rules out

- **Not** integrating ASR into `zenith-server`. The server is the audio transport; adding HTTP/JSON/SSE/WAV to it would couple every dictation change to a restart of the microphone path.
- **Not** using `/streams` for v1. It needs the desktop app running, only accepts 16 kHz, and its partials depend on the model. The batch WAV path is simpler and model-agnostic.
- **Not** sending the transcript over UDP without an acknowledgement layer. For a short dictated sentence, TCP is the correct tool.
- **Not** resampling 48 → 16 kHz in the batch path. The embedded service accepts 48 kHz directly.

### 2.5 Existing work: batch, chunked, and streaming (searched 2026-11-08)

A search of the Hex and transcribe.cpp GitHub issues and PRs found **no merged implementation** of chunked/live transcription for non-streaming models:

| Where | Item | State | What it means |
|---|---|---|---|
| Hex | [#1136](https://github.com/anomalyco/hex/issues/1136) Transcribe chunks during dictation for non-streaming models (Parakeet Ultra, v3) | open | The exact feature. The maintainer agrees with the goal; splitting a dictation at pauses needs a product decision (join accuracy vs wait). No implementation. |
| Hex | [#1470](https://github.com/anomalyco/hex/issues/1470) Live text for Parakeet v3 and Ultra | open | Re-transcribe the uncertain tail every second instead of fixed cuts. Opened by a Portuguese Parakeet v3 user. No implementation. |
| Hex | [#878](https://github.com/anomalyco/hex/issues/878) Live text for Whisper | open | Technical handoff with a working implementation in a personal fork; not merged. |
| Hex | [#449](https://github.com/anomalyco/hex/issues/449) Live transcript preview | open | UI preview; related. |
| Hex | [#957](https://github.com/anomalyco/hex/issues/957) Client-only / server-only modes over LAN or Tailscale | open | Remote transcription. Aligns with the phone→PC direction, but it is a desktop-client proposal and has no implementation. |
| Hex | [PR #1474](https://github.com/anomalyco/hex/pull/1474) `/dictations` partials | merged | Live text over SSE, but only when the engine's model can stream. |
| transcribe.cpp | [#252](https://github.com/handy-computer/transcribe.cpp/issues/252) Parakeet v3 loses text on clips of some lengths | open | Batch blocker; reproduced below. |
| transcribe.cpp | [#215](https://github.com/handy-computer/transcribe.cpp/issues/215) Parakeet streaming commit freezes after a lone-space token | open | Streaming blocker; a fix is offered but not merged. |

Consequence: the **final** transcription is batch — the only path that works today with the best pt-BR model. Live text for this model is implemented in the bridge (v1, §6.7), not enabled by a Hex flag.

### 2.6 The batch blocker: transcribe.cpp #252

The **stock Parakeet v3** and the **`-30s` fine-tune** both inherit a length-dependent failure: when the mel frame count is `8k + 1`, the encoder sees one frame past the end of the speech. Roughly one length in eight is affected. Reproduced with Hex 3.3.0 and the stock v3 Q8_0:

```text
clip 5.04 s              → (empty)
clip 5.04 s, −1 sample   → "By no possible supposition could this stranger, Fitzroy Simpson, have caused"
clip 10.56 s             → "His bridle is missing, so that Simpson must have put this on."
clip 10.56 s, −1 sample  → "... Then, having left the door open behind him, he was leading the horse away over the moor,"
```

Cause: the encoder graph is built with **every** mel frame, including the frontend's final padding frame. NeMo counts all but the last (`pcm.len() / HOP`). The extra frame joins the attention and can blank or truncate the transcript.

The obvious mask-length change is not enough: `pre_encode_stage_lengths` is only consumed when `kestrel_length_masking` is on, which the v3 family does not set. The extra frame still enters the encoder. The fix must keep the last mel frame out of the graph:

1. derive `graph_mel_frames = mel_frames - 1`;
2. compact the row-major mel buffer from `[n_mels, mel_frames]` to `[n_mels, graph_mel_frames]`;
3. build the encoder graph with `graph_mel_frames`, upload the compacted buffer, and pass `graph_mel_frames` to the length masks.

The local patch is [`docs/patches/transcribe-cpp-252-compact-last-mel-frame.patch`](patches/transcribe-cpp-252-compact-last-mel-frame.patch); it touches `run_one_shot_inner` in `src/arch/parakeet/model.cpp`. It drops the last mel frame from the encoder graph, which is what NeMo does. Validated with the patched `transcribe-cli` and with Hex 3.3.0 built against the patched checkout:

```text
clip 5.04 s              → "By no possible supposition could this stranger, Fitzroy Simpson, have caused"
clip 10.56 s             → "... Then, having left the door open behind him, he was leading the horse away over the moor,"
FLEURS pt-BR reference   → unchanged (exact match)
```

Hex 3.3.0, rebuilt against the patched checkout, produces the same outputs; the trim workaround is no longer needed. The patch changes the stock v3 numbers slightly (FLEURS 5.36 → 5.45%, TAGARELA 21.31 → 21.72%) while removing the empty/truncated failures; the `-30s` and Whisper results are unchanged. The purpose is reliability, not a WER gain.

Hex pins `anomalyco/transcribe.cpp` at rev `2778ae4`, which carries the same bug. A batch pipeline **must** include this fix (or a padding workaround that avoids `mel_frames % 8 == 1`); without it, dictation randomly returns empty or truncated text. The patch is local to the Hex build; upstreaming it to transcribe.cpp #252 is the durable fix.

---

## 3. Architecture

```mermaid
flowchart LR
    subgraph Phone
        A[Zenith Voice IME] -->|tap to talk| B[DictationClient<br/>TCP + framing]
        B --> C[InputConnection.commitText]
    end

    subgraph PC
        D[Bridge<br/>Bun + TypeScript] --> E[hex service --embedded<br/>Parakeet -30s pt-BR]
        E --> D
    end

    B -->|TCP 59102<br/>PCM16 48k mono| D
    D -->|text| B
```

### 3.1 Three components, three responsibilities

| Component | Owns | Does not own |
|---|---|---|
| **Zenith Voice IME** (Android) | Microphone capture during dictation, TCP client, text insertion | Speech recognition, language models |
| **Bridge** (PC, separate process) | Framing, WAV assembly, Hex lifecycle, error mapping | Audio capture, text insertion |
| **Hex** (PC) | Model, inference | Where the text goes |

### 3.2 Why TCP instead of reusing ZAP/UDP

ZAP is optimized for a **continuous real-time stream**: low latency, loss tolerance, jitter buffer, PLC. Dictation has the opposite requirements:

| Requirement | Real-time call | Dictated sentence |
|---|---|---|
| Latency while capturing | Critical | Irrelevant (a few hundred ms are invisible) |
| Loss tolerance | Conceal and continue | **Zero** — a lost packet loses a word |
| Ordering | Buffer and reorder | Must be exact |
| Duration | Minutes | Seconds |
| Transport | UDP | **TCP** |

Analogies: UDP is a postcard — fast, but if it is lost, it is gone. TCP is a registered letter — it is re-sent until it arrives, in order. A phone call needs the postcard; a sentence needs the letter.

Reusing ZAP would also mean duplicating the jitter buffer and PLC in the bridge, and binding a second listener to the server's port. A separate TCP port keeps the dictation path independent: it works while Discord is muted, while the phone is in mic-only mode, or while the server is stopped.

Consequence to accept: **RNNoise and the jitter buffer do not run on this path.** That is intentional. RNNoise is a speech enhancer tuned for calls; ASR models are trained on noisy speech and do not need it. The capture chain still applies the 80 Hz high-pass, gain and soft limiter, because those are cheap and shared with the existing recorder.

A second, unrelated hazard is the Parakeet v3 length bug (§2.6): the batch path must run with the frame-count fix in transcribe.cpp, or with a padding workaround. Without it, roughly one clip length in eight returns empty or loses its last words.

### 3.3 Why the trigger decides the destination

The original plan proposed a target selector ("PC" / "Phone") synchronized between devices. It is unnecessary:

- To insert text on the phone, the user is looking at a phone field, so the **phone keyboard is on screen**. Its mic button starts a phone-target session.
- To insert text on the PC, the user is at the PC and presses the **Hex hotkey**. Hex handles it as it always did.

The device that starts the dictation is the device that receives the text. A selector would add shared state, a synchronization channel and a new failure mode (text landing on the wrong device) to solve a problem the UI already solves.

### 3.4 Why the bridge is a separate process

- It can crash, restart or be upgraded without touching the audio server or the running microphone.
- It can be written in TypeScript and use the official Hex SDK without adding a C++ HTTP client, JSON parser or SSE implementation to `zenith-server`.
- It is testable with a WAV file and `nc`; no phone required.
- If Hex changes, only the bridge changes.

Cost: one more process and a systemd unit. Accepted.

---

## 4. Wire protocol (phone ↔ bridge)

One TCP connection per dictation. The connection **is** the session; no session IDs are needed.

Default port: **59102/TCP**.

### 4.1 Frame

```text
+--------+------------------+----------------------+
| type   | length (u32 BE)  | payload              |
| 1 byte | 4 bytes          | `length` bytes       |
+--------+------------------+----------------------+
```

- Maximum frame payload: 1 MiB.
- Audio frames must have an even length (PCM16 samples).
- A frame larger than the limit, or a truncated frame, closes the connection with `bad-frame`.

### 4.2 Types

| Type | Name | Payload |
|---|---|---|
| `0x01` | `DICTATION_START` | UTF-8 JSON (see below) |
| `0x02` | `AUDIO` | Raw PCM16LE, 48 kHz, mono |
| `0x03` | `DICTATION_END` | Empty |
| `0x04` | `PARTIAL` | UTF-8 JSON (bridge → client, live mode only) |
| `0x81` | `RESULT` | UTF-8 JSON |
| `0x82` | `ERROR` | UTF-8 JSON |

`DICTATION_START`:

```json
{
  "version": 1,
  "sampleRate": 48000,
  "channels": 1,
  "format": "s16le",
  "language": "pt",
  "model": "custom-7d13a4fb474b2100",
  "token": "optional-shared-secret",
  "live": true,
  "liveWindowMs": 4000,
  "liveStrideMs": 500,
  "minLiveMs": 800
}
```

- Invalid JSON or a missing required field is `bad-request`. A supported JSON shape with wrong values (for example 16 kHz or stereo) is `unsupported-format`.
- `language` and `model` are optional. When omitted, the bridge uses its configured defaults. The bridge's configuration wins: a client may ask for a different model, but the bridge refuses it with `bad-request` unless it is the configured one. `language` matters for Whisper; Parakeet and GigaAM ignore it.
- `token` is optional in v1 and enforced when the bridge is configured with one (see §8).
- `live` turns on PARTIAL frames. `liveWindowMs` is the tail window re-transcribed on each pass, `liveStrideMs` is how much new audio triggers a pass, and `minLiveMs` is the minimum audio before the first preview. The bridge clamps all three.

`PARTIAL`:

```json
{
  "version": 1,
  "pass": 3,
  "text": "…",
  "startMs": 1200,
  "endMs": 5200,
  "elapsedMs": 140
}
```

`RESULT`:

```json
{
  "version": 1,
  "text": "Criar uma função que soma dois números.",
  "model": "custom-7d13a4fb474b2100",
  "language": "pt",
  "durationMs": 8200,
  "inferenceMs": 410,
  "livePasses": 7
}
```

`ERROR` codes:

| Code | Meaning |
|---|---|
| `bad-frame` | Truncated frame, oversized frame or unknown type |
| `bad-request` | Invalid `DICTATION_START` JSON or missing fields |
| `unsupported-format` | Not 48 kHz mono PCM16 |
| `audio-too-large` | Over the configured duration/byte cap |
| `model-unavailable` | Hex binary missing, model not installed or load failed |
| `model-busy` | Another dictation is being transcribed and the queue is full |
| `transcription-failed` | Hex returned an error |
| `timeout` | Transcription exceeded the deadline |
| `internal` | Anything else |

### 4.3 Order and termination

```text
client → START
client → AUDIO × N
server → PARTIAL × M          (only when START.live is true; each replaces the previous)
client → END, then half-closes its write side
server → RESULT or ERROR, then closes
```

- Partials are **tentative**: the client replaces the previous PARTIAL and commits only the final RESULT. It must never append partials to the field.
- A live window or the final clip is only sent to the model when it contains roughly **100 ms** of speech above the threshold (-40 dBFS RMS) inside a 300 ms window, so short intra-word gaps count but scattered clicks do not accumulate. Silent windows produce no PARTIAL; a fully silent session returns an empty RESULT. This is what keeps hum and the mic noise floor from becoming a repeated hallucinated word.
- The final RESULT always transcribes the **whole utterance**, not just the last chunk, so partials cannot lose or duplicate words.
- The bridge ignores `AUDIO` after `END`.
- The client must not send a second `START` on the same connection.
- Half-close is part of the contract. The client sends `END` and then shuts down its write side; the bridge runs with `allowHalfOpen: true` so it can still write `RESULT`/`ERROR` after the client's FIN. A half-close **without** `END` cancels the session and frees the single dictation slot.
- If the client disconnects before `END`, the bridge discards the audio (cancel semantics).
- The text is UTF-8 and may be empty (silence or unrecognized speech); the IME then inserts nothing.

### 4.4 Chunk size

The recorder produces 10 ms frames. The client accumulates **100 ms** (4 800 samples, 9 600 bytes) before writing. Rationale: no real-time deadline; fewer syscalls; a 5-byte header on 9 600 bytes is 0.05 % overhead. Losing up to 100 ms on a crash is irrelevant.

---

## 5. Android IME

### 5.1 Component

`ZenithImeService : InputMethodService`

Manifest additions:

```xml
<service
    android:name=".ime.ZenithImeService"
    android:permission="android.permission.BIND_INPUT_METHOD"
    android:exported="true">
    <intent-filter>
        <action android:name="android.view.InputMethod" />
    </intent-filter>
    <meta-data
        android:name="android.view.im"
        android:resource="@xml/method" />
</service>
```

`res/xml/method.xml` declares the subtype (`pt-BR`, keyboard mode) and points at the app's settings for configuration.

### 5.2 UI

A compact voice keyboard, **not** a full QWERTY:

- a large tap-to-toggle microphone button (tap to start, tap to stop; 2 s of silence also stops);
- a status line ("Conectando…", "Ouvindo…", "Transcrevendo…");
- a cancel button while a session is active;
- a keyboard-switch button (`switchToNextInputMethod`) so the user can return to Gboard;
- a settings button that opens the main app.

The PC address comes from the existing `server_ip` preference, filled by `ServerDiscovery`; the IME uses the endpoint published by the streaming service (`active_server_ip`, falling back to `server_ip`), so it follows Wi-Fi/VPN switches without probing the network itself (§5.9). The bridge port defaults to `59102` and is stored next to it. The main app has a "Voice keyboard" card that edits `dictation_port` and `dictation_token` and opens the system IME settings. The token is masked with a show/hide toggle and is edited in the app, not inside the IME: an editable field inside an IME window would route input through the IME itself, which has no QWERTY keyboard by design.

Use classic Android Views for v1. Compose inside an IME needs a `ComposeView` plus a lifecycle owner and adds failure modes for no user benefit in a screen with three controls. The main activity already uses Compose; the IME can stay on Views until it grows.

The mic button is a tap toggle: the first tap starts, the next tap stops. It is not a hold gesture, so TalkBack and switch access use the same activation. The status line and the button text are the accessibility labels; there are no icon-only controls.

### 5.3 Capture

Reuse `MicAudioRecorder` with raw-frame mode (`packetize = false`), which is a
small constructor flag, not a second capture implementation:

```kotlin
MicAudioRecorder(
    context = this,
    sendAudioPacket = { frame -> dictationClient.enqueue(frame) },
    sendControlPacket = { /* no-op: ZAP controls do not apply here */ },
    packetize = false
).apply {
    applySettings(
        MicSettingsStore.read(context).copy(
            gainDb = MicSettings.DEFAULT_GAIN_DB,
            inputDeviceId = MicSettings.DEFAULT_INPUT_DEVICE,
            inputDeviceAddress = ""
        ),
        restartIfRecording = false
    )
}
```

With `packetize = false` the recorder keeps its source mode, 80 Hz
high-pass and limiter, but hands over raw PCM16 frames and sends no ZAP
control packets. That is what the TCP protocol expects; stripping a ZAP
header inside the IME would make it depend on a transport it does not use.

Dictation deliberately does **not** inherit the streaming mic's chosen input
device or gain. The streaming service may be set to the back mic with a
negative gain for a particular PC setup; the IME is held in the hand and
needs the phone's default (bottom) mic at 0 dB. It still reads the same
`MicSettingsStore` for the source mode and limiter settings. `start()` returns
`false` when the device or permission is unavailable; the IME surfaces that
and closes the connection instead of recording.

`AudioReceiverService` and the IME share one Android microphone. Before the
IME opens its `AudioRecord`, it calls
`AudioReceiverService.pauseMicForDictation()`, which stops the streaming
capture; after the IME releases the mic it calls
`AudioReceiverService.resumeMicAfterDictation()`. Without this hand-off,
Android 11 on the Poco X3 can give the audio to only one of the two captures,
so dictation receives fragments or silence. The resume is skipped if the user
turned the streaming mic off or disconnected during the dictation.

Opening and releasing `AudioRecord` can take tens to hundreds of milliseconds,
so the IME runs them on a single capture executor, never on the main thread.
When the user taps stop (or the silence detector fires), the recorder stops first
and only then is `END` sent, so the last 10 ms frame is not lost.

Refactor only if the class grows: extract `MicDsp` (high-pass, gain, limiter)
and a `MicFrameSink` interface. Do not rewrite it for the MVP.

### 5.4 Sending

`DictationClient` runs a writer thread with a bounded queue (e.g., 5 s of audio):

- `enqueue(frame)` never blocks the audio thread.
- If the queue fills, the session aborts locally with `network-slow` instead of silently dropping words.
- On stop (second tap or 2 s of silence): `END`, `socket.shutdownOutput()`, then read the reply with a 120 s timeout.

### 5.5 Insertion

```kotlin
val ic = currentInputConnection
if (ic != null) {
    ic.commitText(text, 1)
}
```

- `commitText` inserts and moves the cursor. It is the only general mechanism; a normal app cannot write into another app's field.
- The IME saves the connection that was focused when the user tapped stop as a fallback. If focus moves while the bridge is transcribing, the current field receives the text (and any provisional underline left on the finished field is cleared); if no field is focused, the saved connection is used.
- PARTIAL frames keep drawing into the currently focused field, and the final commit replaces the underlined preview there. Any preview left on another connection is cleared after a successful insert.
- Check the boolean returned by `commitText`: an app can refuse the insert without throwing. A false result is reported, not silently ignored.
- An empty `RESULT` leaves the field unchanged, clears the provisional composing text and shows "Nada foi reconhecido" in the status line, so a dropped transcript is visible.
- For the bridge's `PARTIAL` frames, use `setComposingText` (underlined provisional text) and replace it on each pass; commit only the final `RESULT` with `commitText`. Never commit partials repeatedly.
- Fields that report no text input (`TYPE_NULL`) and apps that block third-party IMEs will refuse insertion. This is a platform limit, not a bug. (Password fields are fine: an IME is allowed to type into them.)

### 5.6 State machine

```text
Idle ──tap──▶ Connecting ──ok──▶ Recording ──tap/2 s silence──▶ Waiting ──text──▶ Inserting ──▶ Idle
  ▲                   │                  │                    │
  └────error/cancel───┴──────────────────┴────────────────────┘
```

- **Tap to toggle.** The mic button is not a hold gesture: tap starts, the next tap stops and transcribes. This is also the only path assistive technologies need.
- **Silence auto-stop.** The IME computes RMS per captured frame (10 ms, threshold 0.01 normalized, -40 dBFS). The silence clock only starts after roughly **100 ms** of speech above the threshold inside a 300 ms window: a single click or an isolated transient is not enough, and short gaps inside a word are tolerated. Once speech has armed the clock, **2 s** below the threshold stops the capture and sends `END`, exactly as if the user had tapped stop. Silence before the first spoken run never stops: a tap-to-talk session that has not started speaking yet stays open. The detector is created per session and fires once.
- **Cancel** (button, hiding the keyboard with Back or an IME switch, screen off): stop capture, close the socket without `END`, return to `Idle`. Hiding the input view calls `onFinishInputView`; a temporarily hidden window calls `onWindowHidden`; a field losing its input connection calls `onFinishInput`; all three cancel **while capturing** (CONNECTING/RECORDING). A session already in WAITING is left to finish, so a focus change or a transient hide does not discard an utterance that is already being transcribed. Relying only on `onFinishInput` would leave the microphone on when the keyboard is merely hidden.
- **Timeout**: 120 s waiting for the result, then show an error and close.
- **Permission missing**: open the main activity to request `RECORD_AUDIO` (same app, so a grant covers the IME too).
- **PC unreachable**: show "PC indisponível" with the configured address and a shortcut to the settings; do not start recording.

### 5.7 Do not

- Do not run the IME's capture while the Zenith mic service is also capturing. Android 11 on the Poco X3 may give the audio to only one client, so the IME pauses the streaming microphone for the duration of a dictation and resumes it afterwards.
- Do not add a wakelock for dictation; the 2 s silence stop after speech and the 120 s session cap bound a forgotten tap-to-talk session.
- Do not commit `""` (it can clear a selection); skip empty results.

### 5.8 Build and enable

```bash
cd android
ANDROID_HOME="$HOME/Android/Sdk" ./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Enable and select the IME (the system dialog is also reachable from the app)
adb shell ime enable com.zenith.audio/.ime.ZenithImeService
adb shell ime set com.zenith.audio/.ime.ZenithImeService
```

On the PC, start the bridge bound to the LAN with a token (bridge README). In
the app's "Voice keyboard" card, set the port and token and press "Ativar
teclado". Then tap the mic in any text field, speak, and tap again (or wait
2 s without speaking).

The `ime set` command is convenient for development; a normal user enables the
keyboard under Settings → System → Languages & input → On-screen keyboard and
switches with the keyboard button or the navigation-bar keyboard picker.

### 5.9 Wi-Fi and VPN: one active endpoint

The bridge binds to `0.0.0.0`, so it listens on both the LAN address
(`192.168.3.103`) and the WireGuard address (`10.181.104.2`). Which one is
reachable depends on the phone's current network.

The IME does **not** discover, probe or try addresses. Selecting the endpoint
is the app's job:

1. `NetworkFailoverManager` tracks the transport and the current PC address
   (`activeDetectedIp`).
2. `AudioReceiverService` publishes that value to `active_server_ip` in
   `zenith_prefs` whenever it changes.
3. The IME connects to `active_server_ip` only, falling back to `server_ip`
   when the app has never published one.

Switching between Wi-Fi and WireGuard changes what the streaming service uses,
and the next dictation follows it. If the app has not run or connected since
the network changed, the last published endpoint is used; open the app once so
the failover manager can update it.

| Phone network | Typical endpoint |
|---|---|
| Wi-Fi at home | `192.168.3.103` |
| WireGuard | `10.181.104.2` |
| USB tethering (AOA) | the USB host address |

The firewall accepts TCP 1025-65535 on both interfaces, and the token is the
same for every address. WireGuard encrypts the dictation traffic in transit;
without it, the same bytes are plaintext on the LAN.

---

## 6. Bridge

### 6.1 Runtime

**Bun + TypeScript** on the PC.

Reasons: Bun is already installed; the official Hex SDK is TypeScript/ESM-only; the bridge is I/O-bound; iteration is faster than C++ or Rust. The SDK targets Node >= 20.3, and Bun aims for Node compatibility, but this must be verified — if the SDK rejects Bun, run the bridge on Node with no code change. If it ever needs to be a single binary, port it to Rust later; the protocol is the contract, not the language.

### 6.2 Responsibilities

1. Accept TCP connections on `0.0.0.0:59102` (configurable); one active dictation at a time.
2. Parse frames, validate `DICTATION_START`.
3. Buffer audio up to `maxSeconds` (default 120 s) and `maxBytes` (default 16 MiB).
4. When the session is live, re-transcribe the tail at `liveStrideMs`/`liveWindowMs` and send `PARTIAL`; the final pass is always the whole utterance. Windows with no speech are skipped.
5. On `END`, build a WAV of the whole utterance and return `RESULT`/`ERROR`; a clip with no speech returns an empty `RESULT` without inference.
6. Keep Hex's model resident across sessions; serialize inference (one pass at a time).
7. Close the connection.

### 6.3 Hex lifecycle

- `create({ command: ["hex", "service", "--embedded"], model, language })` at bridge startup (warm). The model stays loaded for the lifetime of the bridge.
- There is no idle unload in v1: stop the bridge to release VRAM. A future `--idle-unload` can close and recreate the engine.
- Model download happens on the first `create()` and can take minutes; the bridge logs progress and exits with a clear error if Hex cannot start. A session never starts without a model.
- One inference at a time. The engine is single-threaded; the bridge serializes requests, allows one active dictation (`model-busy` for a second), and lets an in-flight preview finish before the final pass.
- Pin the model and language in the bridge configuration; do not depend on the desktop app's current selection.

### 6.4 WAV

Mono, 16-bit PCM, 48 kHz, no resampling:

```text
RIFF <size> WAVE
fmt  (16 bytes): PCM=1, channels=1, rate=48000, byteRate=96000, blockAlign=2, bits=16
data <bytes>
```

44-byte header plus the buffered samples. Reject odd-length audio.

### 6.5 Configuration

| Option | Default | Meaning |
|---|---|---|
| `--host` | `127.0.0.1` | TCP listen address; non-loopback requires `--token` |
| `--port` | `59102` | TCP listen port |
| `--hex` | `hex` | Hex executable (use the built path if not in `$PATH`) |
| `--model` | **required** | Hex model id (catalog or imported `custom-<hash>`) |
| `--language` | `pt` | Hint; ignored by Parakeet/GigaAM |
| `--max-seconds` | `120` | Audio cap per dictation |
| `--live-window-ms` | `4000` | Tail window per preview |
| `--live-stride-ms` | `500` | New audio between previews |
| `--min-live-ms` | `800` | Minimum audio before previews |
| `--request-timeout-ms` | `120000` | Caller timeout for one inference |
| `--token` | empty | Shared secret required in `START`; mandatory for a LAN bind |
| `--log-transcripts` | `false` | Log preview/final text only when explicitly enabled |

### 6.6 Service

The unit is [`bridge/zenith-dictation.service`](../bridge/zenith-dictation.service). It runs Bun + the bridge as a systemd **user** service, starts after `network-online.target`, restarts on failure and logs to the journal. The token is read from `~/.config/zenith/dictation.env` (mode 0600) instead of living in the unit.

```bash
mkdir -p ~/.config/zenith
printf 'DICTATION_TOKEN=%s\n' "$(openssl rand -hex 16)" > ~/.config/zenith/dictation.env
chmod 600 ~/.config/zenith/dictation.env

mkdir -p ~/.config/systemd/user
cp bridge/zenith-dictation.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now zenith-dictation.service
```

`systemctl --user status zenith-dictation.service` and `journalctl --user -u zenith-dictation.service -f` show the bridge; the log must reach `[bridge] Hex ready` and `listening on 0.0.0.0:59102`. With `loginctl enable-linger "$USER"` (already enabled on this machine) the service also runs without a graphical login. The app's token must match the value in `dictation.env`; changing one means updating the other.

### 6.7 Live text (v1, implemented in the bridge)

The bridge does not wait for Hex #1136/#1470. It implements the #1470 idea on top of the resident engine:

- keep the **whole** utterance buffer;
- every `liveStrideMs` of new audio, transcribe the last `liveWindowMs`;
- start the window after a short pause when one exists (`findSpeechStart`), so a preview rarely begins mid-word;
- require at least 100 ms above the -40 dBFS RMS threshold in that window before calling the model, so hum and the noise floor never become a PARTIAL;
- send a `PARTIAL` with the text; the client **replaces** the previous preview;
- at `END`, transcribe the **whole utterance** and send `RESULT`; a fully silent session returns an empty `RESULT` without inference.

Why the tail instead of fixed chunks:

- there are no fixed cuts, so no word is lost or duplicated at a join;
- the final text is always the whole clip, independent of preview quality;
- the cost is repeated inference on a bounded window, serialized by the engine. A preview in flight delays the final by at most one pass; it never changes the final text.

Trade-offs:

- a preview can start mid-sentence when no pause is found in the first 800 ms of the window;
- long dictations do more inference work; the final still waits for the last preview;
- the client must treat partials as provisional. The Android IME will use `setComposingText` for them and `commitText` only for `RESULT`.

The implementation lives in `bridge/` (Bun + `@kitlangton/hex`):

- `src/live-session.ts`: buffer, tail passes, pause heuristic, final pass;
- `src/hex-client.ts`: resident transcriber and serialized queue;
- `src/server.ts`: TCP frames and one active dictation;
- `src/live-test.ts`: end-to-end client that prints every PARTIAL and the final RESULT.

Validated on a 14.2 s FLEURS clip: 17 partials, final text exactly the reference. `bun test` covers the frame codec, WAV container, live scheduler, speech gate, cancellation and the audio cap; `bun run typecheck` is clean.

Decision record: [ADR 0001](adr/0001-live-text-in-the-bridge.md) explains why this live layer is outside Hex, its costs, and the criteria for moving it into Hex or replacing it with a native streaming model.

---

## 7. Lifecycle of one dictation

```text
1. User focuses a text field on the phone and selects the Zenith Voice keyboard.
2. User taps the mic key (first tap starts; it is not a hold gesture).
3. IME opens TCP to the PC, sends START with live: true, starts AudioRecord.
4. Every 10 ms the recorder emits PCM16; the client buffers and writes 100 ms frames.
5. While the person speaks, the bridge re-transcribes the tail and sends PARTIAL;
   the IME replaces the composing text with each preview.
6. User taps stop, or the IME detects 2 s without speech.
7. IME stops the recorder, sends END, half-closes.
8. Bridge waits for an in-flight preview, transcribes the whole utterance, sends RESULT.
9. IME commits the final text with commitText(text, 1) and returns to Idle.
```

PC-target dictation does not use the bridge:

```text
1. User is at the PC and presses Hex's hotkey.
2. Hex captures from "Zenith Wireless Microphone" (the phone's audio).
3. Hex transcribes and pastes into the focused PC app.
```

---

## 8. Security and privacy

- The bridge binds to `127.0.0.1` by default. To reach it from the phone, bind to a LAN address **and** set `--token`; the bridge refuses a non-loopback host without one. The phone sends the same token in `START`.
- Anyone with the token can make the PC transcribe audio (resource abuse), but cannot read files or run code.
- The token is stored in the app's private `SharedPreferences` (plaintext, masked in the UI) and sent without TLS. Treat it as a LAN-scoped bearer secret; changing it means updating both the bridge and the app.
- No TLS. Do not expose the port through the router; if remote use is needed, use a VPN (WireGuard) — the same assumption ZAP already makes. On the WireGuard path the dictation stream is encrypted by the tunnel; on the LAN it is plaintext.
- Transcripts are not logged by default. Hex's own History is a separate feature; the bridge does not write text to disk.
- Audio and text stay on the LAN and on the PC. No cloud provider is involved unless the user deliberately configures one in Hex (not part of this design).

---

## 9. Latency budget

For a 10-second utterance, LAN, GPU inference:

| Step | Typical |
|---|---|
| Last 100 ms chunk flush after stop | 0–100 ms |
| TCP on Wi-Fi | 1–5 ms |
| WAV assembly | < 5 ms |
| Hex inference (Parakeet `-30s`, ~29× realtime on CPU in the CLI; Vulkan faster) | ~350 ms |
| In-flight preview to drain | 0–500 ms |
| Return + `commitText` | < 10 ms |
| **Total after stop** | **~0.4–1.0 s** |

Notes:

- The 29× figure is the CLI benchmark on the Ryzen 7 5700X; the RX 6500 XT is faster for a warm engine. The `-30s` model is a Parakeet v3 fine-tune, so it inherits Parakeet's speed.
- The bridge loads the model once at startup (~1.4 s); there is no per-dictation load and no idle unload in v1.
- With live text on, the final waits for at most one tail pass that was already in flight; the previews themselves do not lengthen the final text.
- The budget excludes the initial model download.

---

## 10. Alternatives considered

| Alternative | Why not |
|---|---|
| **Integrate ASR into `zenith-server`** | Adds HTTP/JSON/WAV/SSE and model management to the C++ audio path; every change restarts the microphone; couples two very different lifecycles. |
| **Use Hex `/streams` live API** | Requires the desktop app (or a service) holding a model; 16 kHz only; one stream; lease management. The bridge implements live text itself (§6.7) with tail passes on the same model. `/streams` would only help with native streaming models, which are English-only (Parakeet Unified) or worse for pt-BR (Nemotron). |
| **Android `SpeechRecognizer`** | On-device model choice is limited, quality is below Parakeet/Whisper, and the user explicitly wants the PC's compute. |
| **Clipboard only** | Works today (and KDE Connect can sync clipboards), but requires a manual paste every time. Kept as a fallback and as a Phase 0 validation tool, not as the product. |
| **Accessibility Service** | Can type into other apps, but is fragile across apps, visible to the user as a security-sensitive service, and restricted by Play policy. The IME is the supported mechanism. |
| **Send text over ZAP/UDP** | Needs acknowledgements, retries, deduplication and fragmentation — i.e. reimplementing TCP. |
| **A separate ZAP listener on the PC** | Cannot share the port cleanly; duplicates jitter/PLC; packet loss becomes lost words. |

---

## 11. Phases and acceptance criteria

| Phase | Work | Acceptance |
|---|---|---|
| **0 — Validate (zero code)** | Install Hex, set "Zenith Wireless Microphone" as Hex's microphone, and dictate in Portuguese. The pt-BR `-30s` model is converted and imported (§16); Whisper and stock v3 are the comparison points. | Text appears in a KDE app; quality is acceptable; latency measured. Stock v3's pt-PT drift is confirmed on short phrases. |
| **0.5 — validate on the user's voice** | Record 10–15 phrases (numbers, names, accents, code terms) once. Transcribe the same WAVs with the chosen `-30s` model, Whisper and stock v3. Score errors by hand. | The `-30s` model stays the default unless it fails badly on this speaker; the run also provides the reference recordings for the bridge tests. |
| **1 — Bridge + live test** | The bridge (TCP, resident Hex engine, live tail passes, final whole-clip pass) and the `live-test` client are implemented in `bridge/`. A "Test transcription" button in the app can follow. | `bun run live-test` prints PARTIAL frames while the WAV streams and a final RESULT that matches the reference; round trip < 2 s for a 10 s utterance without live, and the final is not delayed by more than one tail pass with live; the two #252 clips return full text; the main error paths are reachable. |
| **2 — IME** | Implemented in `android/.../ime/`: `ZenithImeService`, `DictationClient`, `DictationProtocol`, `SilenceDetector`, the voice layout/declaration and the settings card. | **Code complete and unit-tested; on-device validation pending.** Works in WhatsApp, Chrome and Notes; a tap starts and a tap (or 2 s of silence) stops; partials show underlined and are replaced by the final; text lands at the cursor; cancel discards; screen-off stops; no ANR. |
| **3 — Polish** | Model/language picker, token pairing, undo (`deleteSurroundingText`), PC-target shortcut, better pause detection. | Each item independently verified. |

Phase 0 is not optional. It answers the biggest unknown — does the model understand this user's Brazilian Portuguese through this microphone? — before any code exists. Phase 0.5 decides the model with evidence instead of a model card.

The IME code has JVM tests for the frame codec and for the client against a fake
bridge (`DictationProtocolTest`, `DictationClientTest`). What remains is the
part a JVM cannot prove: real `AudioRecord` capture, the composing-text
behaviour of each app, and concurrent capture with the streaming service.

---

## 12. Open questions

1. **Headless `hex service --embedded`:** resolved — the bridge started it without a display and loaded the model on the RX 6500 XT via Vulkan (§6.7).
2. **VRAM:** one 740–890 MB model is fine; two (desktop + bridge) are probably fine on 4 GB, but Vulkan overhead is unknown. Decide whether the bridge unloads after idle.
3. **Quality on the user's voice:** confirm the chosen `-30s` model against Whisper and stock v3 on the same recordings. The published `-plus` numbers are not a target; that checkpoint is not public.
4. **GGUF conversion and Hex import:** resolved — the pt-BR fine-tune was converted (§16), imported as `custom-7d13a4fb474b2100`, checked on the RX 6500 XT via Vulkan, and transcribed the smoke samples correctly. Remaining: run it on the user's voice.
5. **Live preview quality:** the pause heuristic (`findSpeechStart`, RMS < 0.01 for 100 ms) was tuned on clean 48 kHz audio. Test with the phone's noise floor and the chosen mic/Gain settings; lower the threshold or extend the pause search if previews start mid-word.
6. **Concurrent capture:** IME + `AudioReceiverService` at the same time on the Poco X3/MIUI. The IME already pauses the streaming mic; verify the streaming audio resumes after dictation and the IME log shows the bottom mic at 0 dB.
7. **KDE paste (Phase 0 only):** Hex uses the RemoteDesktop portal on KWin. Confirm it works on this Fedora version.
8. **MIUI battery restrictions:** the IME is foreground while visible, so it should be exempt; verify that a forgotten tap-to-talk session stops 2 s after the last spoken word (and at the 120 s cap even if nothing was ever said).
9. **On-device IME validation:** install the APK, enable "Zenith Voice", set the bridge port/token, and dictate into Notes, Chrome and WhatsApp. Items 5–8 are observed there; none of them can be proven by the JVM tests.
10. **Silence threshold:** the auto-stop uses RMS < 0.01 (-40 dBFS), the same order as the bridge's pause heuristic. Test it with the phone's noise floor and the chosen mic/gain; lower it if a noisy room keeps the session alive, raise it if normal pauses trigger too early.

---

## 13. Planned file map

| Path | Responsibility |
|---|---|
| `docs/phone-dictation.md` | This document |
| `docs/patches/transcribe-cpp-252-compact-last-mel-frame.patch` | Local fix for the Parakeet v3 batch bug |
| `bridge/README.md` | Bridge usage, protocol and test instructions |
| `bridge/package.json` | Bun project, `@kitlangton/hex` dependency |
| `bridge/src/index.ts` | Flags, startup, shutdown |
| `bridge/src/server.ts` | TCP server, frame dispatch, one active dictation |
| `bridge/src/frames.ts` | Frame codec |
| `bridge/src/wav.ts` | PCM16 → WAV |
| `bridge/src/hex-client.ts` | Resident Hex engine and serialized queue |
| `bridge/src/live-session.ts` | Buffer, tail passes, pause heuristic, final pass |
| `bridge/src/live-test.ts` | End-to-end client that prints PARTIAL/RESULT |
| `bridge/test/frames.test.ts` | Frame codec |
| `bridge/test/wav.test.ts` | WAV container |
| `bridge/test/live-session.test.ts` | Live scheduler, cancellation, audio cap |
| `bridge/zenith-dictation.service` | systemd user unit (installed) |
| `android/.../ime/ZenithImeService.kt` | InputMethodService, view, state machine, composing text/commit |
| `android/.../ime/DictationClient.kt` | TCP connection, writer queue, 100 ms aggregation, reply parsing |
| `android/.../ime/DictationProtocol.kt` | Frame codec and JSON models shared with the bridge |
| `android/.../ime/DictationPrefs.kt` | Active endpoint, port and token shared with the service |
| `android/.../ime/SilenceDetector.kt` | 2 s silence auto-stop for tap-to-talk |
| `android/.../mic/MicSettingsStore.kt` | Shared `zenith_prefs` microphone keys (service + IME) |
| `android/app/src/main/res/values/colors.xml` | Voice keyboard palette |
| `android/app/src/main/res/xml/method.xml` | IME declaration and subtypes |
| `android/app/src/main/res/layout/ime_voice.xml` | Voice keyboard layout |
| `android/app/src/main/AndroidManifest.xml` | Service + `BIND_INPUT_METHOD` |
| `android/app/src/test/java/com/zenith/audio/ime/DictationProtocolTest.kt` | Frame codec and JSON payloads |
| `android/app/src/test/java/com/zenith/audio/ime/DictationClientTest.kt` | Client against a fake bridge (START, AUDIO, END, PARTIAL, RESULT, ERROR) |
| `android/app/src/test/java/com/zenith/audio/ime/SilenceDetectorTest.kt` | Silence run, reset on speech, partial frames |

---

## 14. Decision log

- **Batch final, live preview.** The final always transcribes the whole utterance; live text is a series of tentative tail passes. This avoids chunk joins while giving text during speech. The final requires the local #252 patch, now validated in Hex; upstreaming is pending.
- **The bridge protocol carries partials now.** `PARTIAL` (0x04) is implemented; the client replaces previews and commits only `RESULT`.
- **TCP for dictation.** Reliability and ordering matter more than milliseconds when the payload is a sentence.
- **Half-close after END is supported.** Node's default `allowHalfOpen: false` closes the bridge's write side as soon as the client's FIN arrives, which lost the RESULT; the bridge now keeps writing until it has replied, and treats a half-close without END as a cancel.
- **Separate bridge process.** Isolates failures and keeps the C++ audio server untouched.
- **Trigger decides destination.** No router, no synchronized selector.
- **Reuse `MicAudioRecorder` in the IME.** Same source mode and limiter; a `packetize = false` flag hands over raw PCM16 and skips ZAP controls instead of adding a second capture implementation. Dictation overrides the streaming device/gain to the default bottom mic at 0 dB, and pauses the streaming capture while the IME owns the microphone.
- **Dictation settings live in the app.** Port and token are edited in the main app's voice-keyboard card; the IME only opens it. Editable fields inside an IME window would need the IME to type into itself, and this IME deliberately has no QWERTY.
- **Partials are composing text.** Every PARTIAL replaces the previous one with `setComposingText`; the RESULT replaces it with `commitText`. Cancel and errors clear the composing region so no provisional text is left behind.
- **No speech, no inference.** The bridge checks every live window and the final clip for roughly 100 ms of speech above -40 dBFS RMS inside a 300 ms window, so short gaps in a word count and scattered clicks cannot accumulate. A quiet 120 Hz hum from a charger/fan reproduced the failure: the old code transcribed it and the model returned the same invented word ("ah") on every pass. Silent windows now yield no PARTIAL, and a silent session yields an empty RESULT.
- **Tap to toggle, with a local silence stop.** The mic is not a hold gesture: the first tap starts and the next tap stops. A `SilenceDetector` on the recorder thread arms after roughly 100 ms of speech inside a 300 ms window (a click is not enough), then ends the session after 2 s of RMS below -40 dBFS; silence before the first word never stops the session. The detector runs on the phone, not the bridge: no protocol change, and it also works if the bridge is slow.
- **The app owns the endpoint.** `NetworkFailoverManager` tracks the active PC address and `AudioReceiverService` publishes it to `active_server_ip`; the IME reads that single value (falling back to `server_ip`). The IME never probes, scans or tries a list of networks — it only opens the connection to the server the app is currently using.
- **The bridge runs as a systemd user service.** `zenith-dictation.service` starts Bun, which starts `hex service --embedded`; it restarts on failure and starts at login (or without login when linger is enabled). The token comes from a private environment file, not the unit.
- **Hiding the IME ends the session.** `onFinishInput` alone is not enough: Back and keyboard switches hide the input view without finishing input. `onFinishInputView`, `onWindowHidden` and `onFinishInput` cancel only while the microphone is live, so it is never left running without a visible keyboard; WAITING is exempt and keeps the connection that was focused at stop, so a focus change while the PC is transcribing does not throw away the result.
- **Result insertion is checked.** `commitText` can refuse an insert without throwing, and the framework may finish the input during inference. The IME commits to the currently focused field, saves the stop connection as a fallback (clearing its provisional underline when the input finishes), and reports a refused or empty result instead of silently dropping it.
- **Audio device work stays off the main thread.** `AudioRecord` open/close runs on a single capture executor; on a tap stop or silence stop the recorder stops before END is sent. A 500 ms join on the UI thread is an ANR-shaped stall, not an acceptable v1 trade-off.
- **Whisper with a language hint for pt-BR.** It is the safe native fallback that handles Brazilian Portuguese and prevents the output language from silently changing.
- **The `-30s` pt-BR fine-tune is the chosen model**, validated on read and spontaneous speech and imported into Hex (§16). The `-plus` checkpoint is not public; `tagarela-v2` is a 1024-token English lineage and was rejected.
- **Do not log transcripts by default.** Voice text is sensitive.
- **Phase 0 before code.** The model's Portuguese quality is the real risk, not the plumbing.
- **Do not trust a model card's language list.** "Portuguese" can mean pt-PT; test on the speaker.
- **Number formatting is not a recognition error.** Do not pick a model by raw WER when one spells numbers and the other emits digits.

### Do not

- Do not add a text packet to ZAP for v1; ZAP is the real-time audio path.
- Do not resample 48 → 16 kHz for the batch path.
- Do not run the bridge inside `zenith-server`.
- Do not send the transcript over UDP without acknowledgements.
- Do not use `/streams` without the desktop app running.
- Do not run two Hex engines if VRAM becomes a problem; unload one.
- Do not commit partial text; use composing text if live text is ever added.
- Do not use stock `parakeet_v3` for pt-BR; it is trained on European Portuguese and has no language control.
- Do not assume a Parakeet model honors a language hint; Parakeet and GigaAM take none.
- Do not treat a missing `InputConnection` as recoverable; discard and tell the user.

---

## 15. References

- Hex repository: <https://github.com/anomalyco/hex>
- Hex live streams contract: `docs/streams.md` in that repository
- Hex TypeScript SDK: <https://www.npmjs.com/package/@kitlangton/hex>
- Hex Linux notes (Vulkan, paste, Live typing): `docs/linux.md` in that repository
- Parakeet v3 model card (see evaluation note 2, European Portuguese): <https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3>
- No language id in Parakeet v3 (NVIDIA): <https://github.com/NVIDIA-NeMo/Speech/issues/14799>
- pt-BR fine-tune and Portuguese WER comparison: <https://huggingface.co/alefiury/parakeet-tdt-0.6b-v3-ptBR-TAGARELA-onnx>
- pt-BR Parakeet checkpoint: <https://huggingface.co/alexandreacff/parakeet-tdt-v3-ptBR-30s>
- transcribe.cpp GGUF converter (Hex's engine): <https://github.com/handy-computer/transcribe.cpp>
- Zenith mic pipeline: [`docs/mic-audio-pipeline.md`](mic-audio-pipeline.md)
- Zenith protocol: [`protocol/PROTOCOL.md`](../protocol/PROTOCOL.md)

---

## 16. Appendix: converting the pt-BR Parakeet fine-tune (validated)

**Status (2026-11-08):** converted, quantized, **imported into Hex** (`custom-7d13a4fb474b2100`) and verified on the RX 6500 XT via Vulkan. `hex model transcribe` matches the reference on the smoke samples.

### 16.1 What was converted

| Item | Value |
|---|---|
| Checkpoint | `alexandreacff/parakeet-tdt-v3-ptBR-30s` (public, CC-BY-4.0) |
| Source | 2.5 GB `.nemo` (POSIX tar: `model_config.yaml`, `model_weights.ckpt`, `*_tokenizer.model`) |
| Engine | `handy-computer/transcribe.cpp` |
| Converter | `scripts/convert-parakeet.py` |
| Variant slug | `parakeet-tdt-v3-ptBR-30s` |
| Output | 706 MB Q8_0 GGUF |

The converter dispatches on the output directory's slug, so the profile key must match it. The entry added:

```python
"parakeet-tdt-v3-ptBR-30s": {
    "variant": "tdt-0.6b-v3-ptbr",
    "display_name": "Parakeet TDT 0.6B v3 pt-BR (TAGARELA)",
    "version": "v3",
    "size_label": "0.6B",
    "head_kind": "tdt",
    "expected_vocab_size": 8192,
    "languages": ["pt"],
    "lang_detect": False,
    "prefer_direct_load": True,
    "author": "alexandreacff",
    "license": "cc-by-4.0",
    "license_name": "Creative Commons Attribution 4.0",
    "license_link": "https://creativecommons.org/licenses/by/4.0/",
},
```

`prefer_direct_load: True` makes the converter read the `.nemo` tar directly (`_load_nemo_archive_directly`) instead of instantiating NeMo. That is what allowed a small CPU-only environment instead of the repo's full `scripts/envs/parakeet` (torch cu129 + NeMo). The fine-tune keeps v3's architecture and 8192-token SPM, so no converter changes beyond the profile were needed.

### 16.2 Commands

```bash
# 0. Where the model artifacts live (outside the repo; the F32 needs ~2.4 GB)
export MODELS="$HOME/.cache/parakeet-spike/models"
mkdir -p "$MODELS"

# 1. Converter checkout
git clone --depth 1 https://github.com/handy-computer/transcribe.cpp
cd transcribe.cpp

# 2. Minimal environment (no NeMo; CPU torch)
#    uv creates a private Python + package folder; nothing is installed system-wide.
uv venv --python 3.12 /tmp/parakeet-convert
uv pip install --python /tmp/parakeet-convert/bin/python --torch-backend cpu \
    torch numpy gguf omegaconf sentencepiece pyyaml

# 3. Add the profile above to scripts/convert-parakeet.py

# 4. Convert (the output's parent directory is the slug)
mkdir -p "$MODELS/parakeet-tdt-v3-ptBR-30s"
/tmp/parakeet-convert/bin/python scripts/convert-parakeet.py \
    "$MODELS/parakeet-tdt-v3-ptBR-30s.nemo" \
    "$MODELS/parakeet-tdt-v3-ptBR-30s/parakeet-tdt-v3-ptBR-30s-F32.gguf"

# 5. Quantize to the type Hex runs
cmake -B build -DCMAKE_BUILD_TYPE=Release -DTRANSCRIBE_BUILD_TOOLS=ON
cmake --build build -j"$(nproc)"
build/bin/transcribe-quantize \
    "$MODELS/parakeet-tdt-v3-ptBR-30s/parakeet-tdt-v3-ptBR-30s-F32.gguf" \
    "$MODELS/parakeet-tdt-v3-ptBR-30s/parakeet-tdt-v3-ptBR-30s-Q8_0.gguf" \
    --quant Q8_0

# 6. Smoke test
build/bin/transcribe-cli -q -m "$MODELS/.../...-Q8_0.gguf" -l pt sample.wav
```

The conversion needs ~2.4 GB for the F32 intermediate; delete it after quantization. Do not run it in a 7 GB tmpfs — the F32 file will fill it.

### 16.3 Resulting metadata and Hex import

```text
general.architecture = parakeet
stt.variant          = tdt-0.6b-v3-ptbr
general.languages    = ['pt']
general.version      = v3
tensor types         = 424 F32, 48 F16, 225 Q8_0
```

Hex accepts architectures `parakeet`, `whisper`, `qwen3_asr`, `cohere_asr`, `sensevoice` and `gigaam`; it accepts tensor types F32, F16 and Q8_0. This file matches. Hex 3.3.0, built from `~/hex` after merging `upstream/dev`:

```text
$ hex model import parakeet-tdt-v3-ptBR-30s-Q8_0.gguf
imported  custom-7d13a4fb474b2100  Parakeet TDT 0.6B v3 pt-BR (TAGARELA)

$ hex model check custom-7d13a4fb474b2100 --language pt
ready  AMD Radeon RX 6500 XT (RADV NAVI24) (vulkan)

$ hex model transcribe <fleurs sample> --model custom-7d13a4fb474b2100 --language pt
Israel exige uma presença militar contínua no vale por dez anos assim que um acordo
seja assinado, ao passo que a AP concorda em deixar essa presença por apenas cinco anos.
```

The last output is character-for-character the FLEURS reference. The model id is a SHA-256 prefix of this file, so it is local to this machine; the same file produces the same id.

### 16.4 Measured results

Corpora:

- **FLEURS pt_br dev**, 50 clips, 1119 reference words: read speech, clean, 16 kHz.
- **TAGARELA test**, 10 clips, 244 reference words: spontaneous podcast speech, `accent=pt-br`.

| Model | FLEURS raw | FLEURS without digit sentences | TAGARELA spontaneous | CLI median per clip |
|---|---|---|---|---|
| `-30s` fine-tune Q8_0 | 7.60% | **4.05%** (42 clips) | **18.44%** | **0.73 s** |
| `whisper_large_v3_turbo` Q8_0 | **5.00%** | 4.50% (42 clips) | 22.95% | 4.51 s |
| `parakeet_v3` stock Q8_0 | 5.45% | 4.73% (42 clips) | 21.72% | 0.75 s |

Interpretation:

- The CLI median includes process start and model load per invocation (≈250 ms for Parakeet, more for Whisper), so it is not the steady-state dictation latency; a resident engine (§6.3) avoids that part.
- **The fine-tune wins on spontaneous speech**, which is the closest to dictation: 18.44% vs 21.72% (stock) and 22.95% (Whisper). The sample is small, so treat the margin as directional, not precise.
- **Its raw FLEURS score is inflated by number formatting**, not recognition errors: it writes "mil e quatrocentos" where the reference has "1.400", "oito por cento" for "8%", and so on. Excluding those 8 clips, it leads (4.05% vs 4.73%).
- **Whisper is ~6× slower on CPU** in this setup (4.51 s vs 0.73 s per clip, including process start and model load). On GPU the gap narrows, but Parakeet is the only one that is comfortably real-time on the CPU alone.
- **Short 3 s slices:** in three samples, neither model drifted to English; the stock model still produced Portuguese. The drift is a real, documented failure mode, but it is not deterministic. Do not treat a small smoke test as proof that it is fixed.

### 16.5 Caveats

- FLEURS is read speech; TAGARELA is spontaneous but only 10 clips. The user's own voice remains the decisive test (Phase 0.5).
- The published 7.5% prepared / 14.3% spontaneous figures refer to the `-plus` checkpoint; **that repo is not public** (401; the author's public list has only `-30s`, `-20s`, `tagarela-v2`, `distil-whisper-tagarela-v2`). This conversion used the public `-30s`, which measured 7.60% raw / 18.44% here.
- `tagarela-v2` is Parakeet **v2** (English) with a 1024-token English tokenizer; it is a different lineage, not a newer `-30s`, and was not pursued.
- The fine-tune spells numbers out. If digits are preferred, add inverse text normalization in the bridge; it is a formatting step, not a reason to change models.
- Hex imported the file as `custom-7d13a4fb474b2100` and loaded it on the RX 6500 XT via Vulkan; the smoke samples match. The decisive test is still the user's own voice.

