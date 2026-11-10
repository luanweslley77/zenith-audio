# Zenith Dictation Bridge

Local TCP bridge between the phone's dictation client and Hex. It accepts
48 kHz mono PCM16 audio, keeps Hex's model loaded, and returns:

- **PARTIAL** frames while the person speaks, when the client asks for live
  text; the bridge re-transcribes the uncertain tail at a fixed stride.
- one **RESULT** with the whole-clip transcription when the client sends END.

The final pass always transcribes the **entire** utterance, so partials are
tentative: the client replaces them and commits only the final text. This
avoids the word-loss risk of cutting the audio into fixed chunks.

## Prerequisites

- Hex built from source (or installed) and a model available to it. The bridge
  starts `hex service --embedded`, which keeps the model resident.
- A GGUF speech model imported into Hex. For the pt-BR Parakeet fine-tune:
  `hex model import parakeet-tdt-v3-ptBR-30s-Q8_0.gguf` prints a
  `custom-<hash>` id; pass that id to `--model`.

## Run

Loopback only (the local test client):

```bash
bun install
bun run start -- \
  --hex "$HOME/hex/target/release/hex" \
  --model custom-7d13a4fb474b2100 \
  --language pt \
  --port 59102
```

For the phone on the LAN, bind to a non-loopback address. The bridge **refuses
to start** without a token in that case:

```bash
TOKEN=$(openssl rand -hex 16)
bun run start -- \
  --host 0.0.0.0 --token "$TOKEN" \
  --hex "$HOME/hex/target/release/hex" \
  --model custom-7d13a4fb474b2100 \
  --language pt
```

The model id is machine-local (a SHA-256 prefix of the imported file). Find it
in Hex's model picker or in the `hex model import` output.

Useful flags:

| Flag | Default | Meaning |
|---|---|---|
| `--host` | `127.0.0.1` | Listen address (non-loopback requires `--token`) |
| `--port` | `59102` | TCP port |
| `--hex` | `hex` | Hex executable |
| `--token` | empty | Shared secret required in START; mandatory for a LAN bind |
| `--max-seconds` | `120` | Per-dictation audio cap |
| `--live-window-ms` | `4000` | Tail window for each preview |
| `--live-stride-ms` | `500` | Audio between preview passes |
| `--min-live-ms` | `800` | Minimum audio before previews |
| `--request-timeout-ms` | `120000` | Caller timeout for one inference |
| `--log-transcripts` | off | Log preview/final text (off by default) |

## Run as a user service (recommended)

`zenith-dictation.service` keeps the bridge and Hex running across sessions and
restarts them if they crash. It starts at login; with `linger` enabled it also
runs without a graphical session.

1. Store the LAN token in a private file (use the same token the app has):

```bash
mkdir -p ~/.config/zenith
printf 'DICTATION_TOKEN=%s\n' "$(openssl rand -hex 16)" > ~/.config/zenith/dictation.env
chmod 600 ~/.config/zenith/dictation.env
```

2. Install the unit. Adjust `WorkingDirectory`, `--hex` and `--model` in the
   file if the checkout or the imported model differs; the model id is
   machine-local.

```bash
mkdir -p ~/.config/systemd/user
cp bridge/zenith-dictation.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now zenith-dictation.service
```

3. Check it:

```bash
systemctl --user status zenith-dictation.service
journalctl --user -u zenith-dictation.service -f
```

The log must show `[bridge] Hex ready` and
`listening on 0.0.0.0:59102`. If the user service must start before login:
`sudo loginctl enable-linger "$USER"`.

If the token changes, update both `dictation.env`, the installed unit (if it
contains the model/paths) and the app's Voice keyboard card.

## Protocol

One TCP connection per dictation. Each frame is:

```text
+--------+------------------+----------------------+
| type   | length (u32 BE)  | payload              |
| 1 byte | 4 bytes          | `length` bytes       |
+--------+------------------+----------------------+
```

| Type | Direction | Payload |
|---|---|---|
| `0x01` | client → bridge | START, UTF-8 JSON |
| `0x02` | client → bridge | AUDIO, PCM16LE 48 kHz mono |
| `0x03` | client → bridge | END |
| `0x04` | bridge → client | PARTIAL, UTF-8 JSON |
| `0x81` | bridge → client | RESULT, UTF-8 JSON |
| `0x82` | bridge → client | ERROR, UTF-8 JSON |

START:

```json
{
  "version": 1,
  "sampleRate": 48000,
  "channels": 1,
  "format": "s16le",
  "token": "",
  "live": true,
  "liveWindowMs": 4000,
  "liveStrideMs": 500,
  "minLiveMs": 800
}
```

PARTIAL:

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

RESULT:

```json
{
  "version": 1,
  "text": "…",
  "model": "custom-7d13a4fb474b2100",
  "language": "pt",
  "durationMs": 8200,
  "inferenceMs": 410,
  "livePasses": 7
}
```

ERROR codes: `bad-frame`, `bad-request`, `unsupported-format`,
`audio-too-large`, `model-busy`, `model-unavailable`,
`transcription-failed`, `timeout`, `internal`.

After END the client may half-close its write side (`shutdownOutput`). The
bridge keeps its own write side open until it has sent RESULT/ERROR, and a
half-close without END cancels the dictation.

## End-to-end test

Create a 48 kHz mono WAV and send it in real time (or 10× faster):

```bash
ffmpeg -i input.wav -ar 48000 -ac 1 -c:a pcm_s16le /tmp/sample-48k.wav

bun run live-test -- \
  --wav /tmp/sample-48k.wav \
  --host 127.0.0.1 --port 59102 \
  --fast
```

The client prints every PARTIAL and the final RESULT. Without `--fast`, the
audio is paced in real time, which is the realistic preview cadence.

## Tests

```bash
bun test
bun run typecheck
```

The tests cover the frame codec, the WAV container, the live scheduler with a
fake transcriber, cancellation, the audio cap, and the socket lifecycle
(half-close after END and abandonment without END). They do not need Hex, a
model or a microphone.

## Notes

- Why the live layer is here and not in Hex:
  [`../docs/adr/0001-live-text-in-the-bridge.md`](../docs/adr/0001-live-text-in-the-bridge.md).

- The bridge serializes inference; a live pass in flight is allowed to finish
  before the final pass starts.
- A failed preview is ignored; the final transcription still runs.
- The model is loaded once at bridge startup (or the process exits with a clear
  error if Hex cannot start).
- This is the v1 protocol. The Android IME (`android/.../ime/`) consumes the
  same frames: it sends START, 100 ms AUDIO frames and END, shows PARTIAL
  frames as composing text and commits the final RESULT.
