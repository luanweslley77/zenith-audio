import { encodeJson, FrameType } from "./frames.ts";
import { pcm16ToWav } from "./wav.ts";

export interface SessionConfig {
  readonly sampleRate: number;
  readonly channels: number;
  readonly live: boolean;
  readonly liveWindowMs: number;
  readonly liveStrideMs: number;
  readonly minLiveMs: number;
  readonly maxSeconds: number;
  readonly model: string;
  readonly language: string;
  readonly logTranscripts: boolean;
}

export interface SessionSink {
  send(frame: Buffer): void;
  close(): void;
}

export interface TranscriberLike {
  transcribe(wav: Uint8Array): Promise<{
    transcript: string;
    durationMs: number;
  }>;
}

/** A protocol/session error with a wire code, not a message to be parsed. */
export class SessionError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/**
 * One dictation. Audio is appended as it arrives; when `live` is on, the tail
 * window is re-transcribed at a fixed stride and sent as PARTIAL frames. The
 * final pass always transcribes the whole utterance, so partials can never
 * lose or duplicate committed words: the client replaces the tentative text.
 */
export class LiveSession {
  private data: Buffer = Buffer.alloc(0);
  private totalBytes = 0;
  private ended = false;
  private closed = false;
  private inFlight: Promise<void> | null = null;
  private lastPassEnd = 0;
  private pass = 0;
  private livePasses = 0;

  constructor(
    private readonly sink: SessionSink,
    private readonly transcriber: TranscriberLike,
    private readonly config: SessionConfig,
  ) {
    if (config.channels !== 1) {
      throw new Error("the bridge supports mono only");
    }
  }

  append(chunk: Buffer): void {
    if (this.ended) return;
    if (chunk.length === 0) return;
    if (chunk.length % 2 !== 0) {
      throw new SessionError("bad-frame", "audio frame has an odd byte length");
    }
    const maxBytes = this.config.maxSeconds * this.config.sampleRate * 2;
    if (this.totalBytes + chunk.length > maxBytes) {
      throw new SessionError(
        "audio-too-large",
        "audio exceeds the maximum utterance length",
      );
    }
    if (this.totalBytes + chunk.length > this.data.length) {
      this.grow(this.totalBytes + chunk.length);
    }
    chunk.copy(this.data, this.totalBytes);
    this.totalBytes += chunk.length;
    this.maybeLive();
  }

  async finish(): Promise<void> {
    if (this.ended) return;
    this.ended = true;
    try {
      // A live pass in flight still owns the engine; let it finish so the
      // final request is not rejected by the serialized queue.
      await this.inFlight;
      if (this.closed) return;

      const pcm = this.fullPcm();
      if (
        pcm.length === 0 ||
        !hasSpeech(
          pcm,
          0,
          pcm.length / 2,
          this.config.sampleRate,
          MIN_SPEECH_MS,
          this.livePasses > 0 ? FINAL_RMS_THRESHOLD : LIVE_RMS_THRESHOLD,
        )
      ) {
        // Nothing above the speech threshold was ever captured. Sending this
        // to the model risks a hallucinated word; a silent session must yield
        // an empty result instead.
        const durationMs = Math.round(
          (pcm.length / 2 / this.config.sampleRate) * 1000,
        );
        console.log(`[final] no speech in ${durationMs} ms; returning empty`);
        this.sendResult("", durationMs, 0);
        this.close();
        return;
      }

      const wav = pcm16ToWav(
        pcm,
        this.config.sampleRate,
        this.config.channels,
      );
      const started = Date.now();
      try {
        const result = await this.transcriber.transcribe(wav);
        this.sendResult(
          result.transcript,
          result.durationMs,
          Date.now() - started,
        );
      } catch (error) {
        this.sendError(errorCode(error), errorMessage(error));
        return;
      }
      this.close();
    } catch (error) {
      // A malformed WAV or a failed send must still surface as a wire error,
      // not as an unhandled rejection.
      this.sendError("internal", errorMessage(error));
    }
  }

  cancel(): void {
    this.ended = true;
    this.close();
  }

  private maybeLive(): void {
    if (!this.config.live || this.ended || this.inFlight !== null) return;

    const samples = this.totalBytes / 2;
    const minSamples = Math.floor(
      (this.config.minLiveMs / 1000) * this.config.sampleRate,
    );
    const stride = Math.floor(
      (this.config.liveStrideMs / 1000) * this.config.sampleRate,
    );
    const windowSamples = Math.floor(
      (this.config.liveWindowMs / 1000) * this.config.sampleRate,
    );
    if (samples < minSamples) return;
    if (samples - this.lastPassEnd < stride) return;

    const end = samples;
    const start = Math.max(0, end - windowSamples);
    // One full-buffer copy per pass, shared by the pause search and the slice.
    const full = this.fullPcm();
    const speechStart = findSpeechStart(
      full,
      start,
      end,
      this.config.sampleRate,
    );
    this.lastPassEnd = end;
    // Hum, fan noise and the phone's noise floor are not speech, but an ASR
    // model asked to transcribe them can emit the same made-up word every
    // time ("ah", "obrigado"). Only run a preview when the slice actually
    // carries speech.
    if (!hasSpeech(full, speechStart, end, this.config.sampleRate)) return;
    const pass = ++this.pass;
    const pcm = full.subarray(speechStart * 2, end * 2);
    const wav = pcm16ToWav(pcm, this.config.sampleRate, this.config.channels);
    const started = Date.now();

    const task = this.transcriber
      .transcribe(wav)
      .then((result) => {
        this.livePasses += 1;
        if (this.config.logTranscripts) {
          console.log(`[live ${pass}] ${result.transcript}`);
        }
        if (this.ended || this.closed) return;
        this.sendJson(FrameType.PARTIAL, {
          version: 1,
          pass,
          text: result.transcript,
          startMs: Math.round((speechStart / this.config.sampleRate) * 1000),
          endMs: Math.round((end / this.config.sampleRate) * 1000),
          elapsedMs: Date.now() - started,
        });
      })
      .catch((error) => {
        // A failed preview must not kill the final transcription.
        if (this.config.logTranscripts) {
          console.error(`[live] pass ${pass} failed:`, errorMessage(error));
        }
      })
      .finally(() => {
        this.inFlight = null;
        if (!this.ended) this.maybeLive();
      });

    this.inFlight = task;
  }

  private fullPcm(): Buffer {
    return this.data.subarray(0, this.totalBytes);
  }

  private grow(needed: number): void {
    let next = Math.max(16 * 1024, this.data.length * 2);
    while (next < needed) next *= 2;
    const grown = Buffer.alloc(next);
    this.data.copy(grown, 0, 0, this.totalBytes);
    this.data = grown;
  }

  private sendResult(
    text: string,
    durationMs: number,
    inferenceMs: number,
  ): void {
    if (this.closed) return;
    if (this.config.logTranscripts) {
      console.log(`[final] ${text} (${text.length} chars, ${durationMs} ms)`);
    }
    this.sendJson(FrameType.RESULT, {
      version: 1,
      text,
      model: this.config.model,
      language: this.config.language,
      durationMs,
      inferenceMs,
      livePasses: this.livePasses,
    });
  }

  private sendError(code: string, message: string): void {
    if (this.closed) return;
    this.sendJson(FrameType.ERROR, { version: 1, code, message });
    this.close();
  }

  private sendJson(type: number, value: unknown): void {
    if (this.closed) return;
    this.sink.send(encodeJson(type, value));
  }

  private close(): void {
    if (this.closed) return;
    this.closed = true;
    this.sink.close();
  }
}

const LIVE_FRAME_MS = 10;
const LIVE_PAUSE_MS = 100;
const LIVE_SEARCH_MS = 800;
const LIVE_RMS_THRESHOLD = 0.01;
/** The final pass only rejects near-silence; quiet speech must survive. */
const FINAL_RMS_THRESHOLD = 0.005;
/** Speech above the threshold needed before anything is sent to the model. */
const MIN_SPEECH_MS = 100;
/** The speech run must fit inside this window, so scattered clicks cannot add up. */
const SPEECH_WINDOW_MS = 300;

function frameSamples(sampleRate: number): number {
  return Math.max(1, Math.floor(sampleRate * (LIVE_FRAME_MS / 1000)));
}

function frameRms(pcm: Buffer, sample: number, frameSamples: number): number {
  let sum = 0;
  for (let i = 0; i < frameSamples; i += 1) {
    const value = pcm.readInt16LE((sample + i) * 2) / 32768;
    sum += value * value;
  }
  return Math.sqrt(sum / frameSamples);
}

/**
 * Reports whether [startSample, endSample) contains at least [minSpeechMs] of
 * audio above the speech threshold inside a [SPEECH_WINDOW_MS] window. Short
 * intra-word gaps are allowed, but isolated clicks and sporadic noise spread
 * over a long clip cannot accumulate into a false positive. A hum that crosses
 * the threshold continuously still qualifies, because RMS alone cannot tell a
 * voiced vowel from a strong tone.
 */
export function hasSpeech(
  pcm: Buffer,
  startSample: number,
  endSample: number,
  sampleRate: number,
  minSpeechMs: number = MIN_SPEECH_MS,
  rmsThreshold: number = LIVE_RMS_THRESHOLD,
): boolean {
  const samplesPerFrame = frameSamples(sampleRate);
  const requiredFrames = Math.max(1, Math.ceil(minSpeechMs / LIVE_FRAME_MS));
  const windowFrames = Math.max(
    requiredFrames,
    Math.ceil(SPEECH_WINDOW_MS / LIVE_FRAME_MS),
  );
  const recent: number[] = [];
  let speechFrames = 0;
  for (
    let sample = startSample;
    sample + samplesPerFrame <= endSample;
    sample += samplesPerFrame
  ) {
    const speech =
      frameRms(pcm, sample, samplesPerFrame) >= rmsThreshold ? 1 : 0;
    recent.push(speech);
    speechFrames += speech;
    if (recent.length > windowFrames) {
      speechFrames -= recent.shift() ?? 0;
    }
    if (speechFrames >= requiredFrames) return true;
  }
  return false;
}

/**
 * Starts the live window after a short pause when one exists, so a partial
 * does not begin mid-word. Falls back to the requested start.
 */
export function findSpeechStart(
  pcm: Buffer,
  startSample: number,
  endSample: number,
  sampleRate: number,
): number {
  const samplesPerFrame = frameSamples(sampleRate);
  const pauseFrames = Math.max(1, Math.round(LIVE_PAUSE_MS / LIVE_FRAME_MS));
  const searchSamples = Math.min(
    endSample - startSample,
    Math.floor(sampleRate * (LIVE_SEARCH_MS / 1000)),
  );
  const searchEnd = startSample + searchSamples;

  let quietRun = 0;
  for (
    let sample = startSample;
    sample + samplesPerFrame <= searchEnd;
    sample += samplesPerFrame
  ) {
    if (frameRms(pcm, sample, samplesPerFrame) < LIVE_RMS_THRESHOLD) {
      quietRun += 1;
      if (quietRun >= pauseFrames) {
        return sample + samplesPerFrame;
      }
    } else {
      quietRun = 0;
    }
  }
  return startSample;
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function errorCode(error: unknown): string {
  const message = errorMessage(error);
  if (message === "timeout") return "timeout";
  if (message === "model-unavailable") return "model-unavailable";
  return "transcription-failed";
}
