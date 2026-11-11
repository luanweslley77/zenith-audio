import { describe, expect, test } from "bun:test";
import { FrameParser, FrameType } from "../src/frames.ts";
import {
  hasSpeech,
  LiveSession,
  type SessionConfig,
  type SessionSink,
  type TranscriberLike,
} from "../src/live-session.ts";

const tick = (): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, 0));

class CollectSink implements SessionSink {
  readonly frames: Buffer[] = [];
  closed = false;

  send(frame: Buffer): void {
    this.frames.push(frame);
  }

  close(): void {
    this.closed = true;
  }

  parsed(): ReturnType<FrameParser["push"]> {
    return new FrameParser().push(Buffer.concat(this.frames));
  }
}

const fakeTranscriber: TranscriberLike = {
  async transcribe(wav) {
    return { transcript: `len:${wav.length}`, durationMs: wav.length };
  },
};

function config(overrides: Partial<SessionConfig> = {}): SessionConfig {
  return {
    sampleRate: 48000,
    channels: 1,
    live: true,
    liveWindowMs: 1000,
    liveStrideMs: 500,
    minLiveMs: 500,
    maxSeconds: 10,
    model: "custom-test",
    language: "pt",
    logTranscripts: false,
    ...overrides,
  };
}

const CHUNK = Buffer.alloc(9600); // 100 ms of 48 kHz mono PCM16, silent

/** Same size as CHUNK, but at ~-18 dBFS: clearly above the speech gate. */
const SPEECH_CHUNK = (() => {
  const chunk = Buffer.alloc(9600);
  for (let i = 0; i + 1 < chunk.length; i += 2) {
    chunk.writeInt16LE(4_000, i);
  }
  return chunk;
})();

/** A quiet 120 Hz hum: the signal that made the model answer "ah". */
const HUM_CHUNK = (() => {
  const chunk = Buffer.alloc(9600);
  for (let sample = 0; sample < 4_800; sample += 1) {
    const value = Math.round(200 * Math.sin((2 * Math.PI * 120 * sample) / 48_000));
    chunk.writeInt16LE(value, sample * 2);
  }
  return chunk;
})();

class CountingTranscriber implements TranscriberLike {
  calls = 0;

  async transcribe(): Promise<{ transcript: string; durationMs: number }> {
    this.calls += 1;
    return { transcript: "palavra", durationMs: 1 };
  }
}

describe("speech gate", () => {
  test("needs 100 ms above the threshold, not a click", () => {
    const pcm = Buffer.alloc(4_800 * 2);
    const fillFrames = (frames: number): void => {
      pcm.fill(0);
      for (let i = 0; i < frames * 480; i += 1) pcm.writeInt16LE(4_000, i * 2);
    };

    fillFrames(1); // a 10 ms click
    expect(hasSpeech(pcm, 0, 4_800, 48_000)).toBe(false);
    fillFrames(9); // 90 ms
    expect(hasSpeech(pcm, 0, 4_800, 48_000)).toBe(false);
    fillFrames(10); // 100 ms
    expect(hasSpeech(pcm, 0, 4_800, 48_000)).toBe(true);
  });

  test("a short gap inside a word is tolerated", () => {
    const frame = 480;
    const pcm = Buffer.alloc(frame * 2 * 20);
    const writeSpeech = (from: number, frames: number): void => {
      for (let i = from; i < (from + frames) * frame; i += 1) {
        pcm.writeInt16LE(4_000, i * 2);
      }
    };
    writeSpeech(0, 6);
    // one silent 10 ms frame at index 6
    writeSpeech(7, 6);
    expect(hasSpeech(pcm, 0, pcm.length / 2, 48_000)).toBe(true);
  });

  test("fragmented weak speech still arms", () => {
    const frame = 480;
    const pcm = Buffer.alloc(frame * 2 * 40);
    // Alternate 10 ms of speech and 10 ms of quiet for 200 ms: only half the
    // frames are above the threshold, but the 100 ms minimum is met.
    for (let click = 0; click < 10; click += 1) {
      const start = click * frame * 2;
      for (let i = 0; i < frame; i += 1) pcm.writeInt16LE(4_000, (start + i) * 2);
    }
    expect(hasSpeech(pcm, 0, pcm.length / 2, 48_000)).toBe(true);
  });

  test("scattered clicks do not accumulate into speech", () => {
    const frame = 480;
    const pcm = Buffer.alloc(frame * 2 * 200);
    // Ten isolated 10 ms clicks: 100 ms of above-threshold audio in total,
    // but never a continuous word.
    for (let click = 0; click < 10; click += 1) {
      const start = click * frame * 20;
      for (let i = 0; i < frame; i += 1) pcm.writeInt16LE(4_000, (start + i) * 2);
    }
    expect(hasSpeech(pcm, 0, pcm.length / 2, 48_000)).toBe(false);
  });

  test("rejects a quiet hum", () => {
    const samples = 4_800;
    const pcm = Buffer.alloc(samples * 2);
    for (let sample = 0; sample < samples; sample += 1) {
      const value = Math.round(200 * Math.sin((2 * Math.PI * 120 * sample) / 48_000));
      pcm.writeInt16LE(value, sample * 2);
    }
    expect(hasSpeech(pcm, 0, samples, 48_000)).toBe(false);
  });
});

describe("live session", () => {
  test("emits partials and a final whole-clip result", async () => {
    const sink = new CollectSink();
    const session = new LiveSession(sink, fakeTranscriber, config());

    for (let i = 0; i < 12; i += 1) {
      session.append(SPEECH_CHUNK);
      await tick();
    }
    await session.finish();
    await tick();

    const frames = sink.parsed();
    const partials = frames.filter((frame) => frame.type === FrameType.PARTIAL);
    const results = frames.filter((frame) => frame.type === FrameType.RESULT);

    expect(partials.length).toBeGreaterThan(0);
    expect(results).toHaveLength(1);
    const result = JSON.parse(results[0]!.payload.toString("utf8"));
    expect(result.text).toBe(`len:${44 + SPEECH_CHUNK.length * 12}`);
    expect(result.livePasses).toBeGreaterThan(0);
    expect(sink.closed).toBe(true);
  });

  test("silence never reaches the model", async () => {
    const sink = new CollectSink();
    const transcriber = new CountingTranscriber();
    const session = new LiveSession(sink, transcriber, config());

    for (let i = 0; i < 12; i += 1) {
      session.append(CHUNK);
      await tick();
    }
    await session.finish();
    await tick();

    const frames = sink.parsed();
    expect(frames.filter((frame) => frame.type === FrameType.PARTIAL)).toHaveLength(0);
    const results = frames.filter((frame) => frame.type === FrameType.RESULT);
    expect(results).toHaveLength(1);
    expect(JSON.parse(results[0]!.payload.toString("utf8")).text).toBe("");
    expect(transcriber.calls).toBe(0);
  });

  test("a quiet hum that fools the model is not previewed", async () => {
    const sink = new CollectSink();
    const transcriber = new CountingTranscriber();
    const session = new LiveSession(sink, transcriber, config());

    for (let i = 0; i < 12; i += 1) {
      session.append(HUM_CHUNK);
      await tick();
    }
    await session.finish();
    await tick();

    const frames = sink.parsed();
    expect(frames.filter((frame) => frame.type === FrameType.PARTIAL)).toHaveLength(0);
    expect(transcriber.calls).toBe(0);
  });

  test("does not emit partials when live is off", async () => {
    const sink = new CollectSink();
    const session = new LiveSession(
      sink,
      fakeTranscriber,
      config({ live: false }),
    );
    for (let i = 0; i < 8; i += 1) {
      session.append(CHUNK);
      await tick();
    }
    await session.finish();
    await tick();

    const frames = sink.parsed();
    expect(frames.filter((frame) => frame.type === FrameType.PARTIAL)).toHaveLength(
      0,
    );
    expect(frames.filter((frame) => frame.type === FrameType.RESULT)).toHaveLength(
      1,
    );
  });

  test("cancel closes without a result", async () => {
    const sink = new CollectSink();
    const session = new LiveSession(sink, fakeTranscriber, config());
    for (let i = 0; i < 6; i += 1) {
      session.append(CHUNK);
      await tick();
    }
    session.cancel();
    await tick();
    const frames = sink.parsed();
    expect(frames.filter((frame) => frame.type === FrameType.RESULT)).toHaveLength(
      0,
    );
    expect(sink.closed).toBe(true);
  });

  test("rejects audio over the configured cap", () => {
    const sink = new CollectSink();
    const session = new LiveSession(
      sink,
      fakeTranscriber,
      config({ maxSeconds: 1 }),
    );
    for (let i = 0; i < 10; i += 1) session.append(CHUNK);
    expect(() => session.append(CHUNK)).toThrow(
      "maximum utterance length",
    );
  });
});
