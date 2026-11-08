import { describe, expect, test } from "bun:test";
import { FrameParser, FrameType } from "../src/frames.ts";
import {
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

const CHUNK = Buffer.alloc(9600); // 100 ms of 48 kHz mono PCM16

describe("live session", () => {
  test("emits partials and a final whole-clip result", async () => {
    const sink = new CollectSink();
    const session = new LiveSession(sink, fakeTranscriber, config());

    for (let i = 0; i < 12; i += 1) {
      session.append(CHUNK);
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
    expect(result.text).toBe(`len:${44 + CHUNK.length * 12}`);
    expect(result.livePasses).toBeGreaterThan(0);
    expect(sink.closed).toBe(true);
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
