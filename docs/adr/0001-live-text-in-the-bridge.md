# ADR 0001: Live text lives in the bridge, not in Hex

- **Status:** Accepted for v1
- **Date:** 2026-11-08
- **Deciders:** Luan, OpenCode
- **Related:** [`../phone-dictation.md`](../phone-dictation.md),
  [`../../bridge/README.md`](../../bridge/README.md),
  `~/hex/docs/zenith-dictation.md`

## Context

The phone is the microphone and the PC runs Hex. The target experience is
hold-to-talk with **provisional words while speaking**, then a final text
committed to the focused field.

Three facts constrain the design:

1. **Hex's SDK is batch-only.** `@kitlangton/hex` exposes
   `transcribe(wav)` and reports `partialTranscripts: false`. There is no
   partial-text callback.
2. **The chosen model is not a native streaming model.** The pt-BR Parakeet
   `-30s` fine-tune is a Parakeet TDT v3 model; Hex's catalog marks the v3
   family as `streams: false`. It transcribes a complete clip, not a token
   stream. The only native streaming options are Parakeet Unified English
   (English-only) and Nemotron 3.5 (worse for pt-BR in our tests).
3. **Hex does not implement live text for non-streaming models.** That is
   requested in Hex issues
   [#1136](https://github.com/anomalyco/hex/issues/1136) (chunked dictation)
   and [#1470](https://github.com/anomalyco/hex/issues/1470) (live text for
   Parakeet v3/Ultra), both open. Hex does not accept pull requests.

Waiting for upstream, forking Hex, or using a worse/ English-only streaming
model were all possible. The user wanted live text now, with the best pt-BR
model, without maintaining a Hex fork.

## Decision

Implement live preview in the **Zenith bridge**, over Hex's existing batch API:

- keep the whole utterance buffer;
- every `liveStrideMs` of new audio, transcribe the last `liveWindowMs`,
  starting after a short pause when one exists;
- send the result as a `PARTIAL` frame; the client **replaces** the previous
  preview;
- at `END`, transcribe the **whole utterance** and send one `RESULT`.

The bridge uses the published SDK as-is:

```ts
create({
  command: [hexPath, "service", "--embedded"],
  model: "custom-…",
  language: "pt",
});
transcriber.transcribe(wav); // batch, called repeatedly for previews
```

Hex's source is **not** modified. The engine fix for transcribe.cpp
[#252](https://github.com/handy-computer/transcribe.cpp/issues/252) is kept in
the transcribe.cpp dependency, with a durable patch file in this repository
([`../patches/transcribe-cpp-252-compact-last-mel-frame.patch`](../patches/transcribe-cpp-252-compact-last-mel-frame.patch)).
That patch is about text reliability, not streaming.

## Consequences

### Positive

- **Works now** with the model that has the best measured pt-BR quality.
- **The final text is always the whole utterance.** Partials are tentative;
  there are no fixed chunk joins, so no word can be lost or duplicated at a
  boundary.
- **Hex stays a black box.** No fork, no Rust changes, no local API surface
  invented inside Hex.
- **Live is opt-in.** Without `live: true`, the bridge is batch-only.
- **The layer is removable.** If Hex ships #1136/#1470, the bridge can consume
  native partials or disappear; the phone protocol stays the same.

### Negative

- **Repeated inference.** A 14 s utterance produced 17 preview passes of up to
  4 s each. The GPU makes this cheap, but the work is redundant.
- **One more process and a dependency.** The bridge depends on the SDK and on
  `hex service --embedded`; an SDK change can break it.
- **Duplicated feature.** If Hex implements chunked/live, the bridge's tail
  loop becomes migration debt.
- **Local engine patch.** The #252 fix lives in a Cargo git checkout and must
  be reapplied if the pinned revision changes.
- **Machine-local model id.** `custom-<hash>` must be regenerated on another
  machine.

## Alternatives considered

| Alternative | Why it was not chosen |
|---|---|
| Wait for Hex #1136/#1470 | Not implemented; the user wanted live text now. |
| Fork Hex and implement chunked/live in Rust | Architecturally the "right" home, but a large fork; Hex does not accept PRs; more maintenance than the bridge. |
| Use a native streaming model | Parakeet Unified is English-only; Nemotron was worse for pt-BR; `/streams` also requires 16 kHz, a running app, one stream and lease management. |
| Batch only | No preview; the wait after release grows with the clip length. |
| Fixed audio chunks / VAD cuts | Risks losing or duplicating a word at the join; #1136 discusses exactly this trade-off. |

## Revisit criteria

This decision should be revisited when any of these becomes true:

1. **Hex ships #1136, #1470, or an equivalent partial API.** Switch the bridge
   to native partials and delete the tail loop.
2. **Preview quality or latency is insufficient on the user's voice.** Implement
   the #1470 optimization first (re-transcribe only the uncertain tail, drop
   audio of stable words); it stays in the bridge and cuts most of the repeated
   work.
3. **Dictations become long, clients become multiple, or word timings are
   required.** The bridge adapter is then the wrong layer; move to a Hex fork
   or a native streaming model.
4. **Repeated inference becomes material** (battery, GPU load, fan noise).
   Reduce stride/window, or move to native/chunked.
5. **The SDK or the embedded service changes in a breaking way.**
6. **The #252 fix lands upstream.** Drop the local transcribe.cpp patch.

## References

- [`../phone-dictation.md`](../phone-dictation.md) — full architecture and
  decisions.
- [`../../bridge/README.md`](../../bridge/README.md) — bridge usage and protocol.
- `~/hex/docs/zenith-dictation.md` — local Hex fork notes.
- Hex #1136, #1470, #878, #449, #957; PR #1474.
- transcribe.cpp #252 (batch reliability) and #215 (streaming commit freeze).
