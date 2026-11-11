import { describe, expect, test } from "bun:test";
import net from "node:net";
import { encodeFrame, encodeJson, FrameParser, FrameType } from "../src/frames.ts";
import type { HexClient } from "../src/hex-client.ts";
import { createBridge } from "../src/server.ts";

const fakeClient = {
  model: "test-model",
  language: "pt",
  async transcribe() {
    return { transcript: "texto final", durationMs: 100 };
  },
} as unknown as HexClient;

async function startBridge(): Promise<{ server: net.Server; port: number }> {
  const server = createBridge({
    token: "",
    maxSeconds: 120,
    liveWindowMs: 4000,
    liveStrideMs: 500,
    minLiveMs: 800,
    idleTimeoutMs: 30000,
    logTranscripts: false,
    client: fakeClient,
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address() as net.AddressInfo;
  return { server, port: address.port };
}

function closeServer(server: net.Server): Promise<void> {
  return new Promise((resolve) => server.close(() => resolve()));
}

function startFrame(): Buffer {
  return encodeJson(FrameType.START, {
    version: 1,
    sampleRate: 48000,
    channels: 1,
    format: "s16le",
    live: false,
  });
}

/** 100 ms of 48 kHz mono PCM16 above the bridge's speech threshold. */
function speechAudio(): Buffer {
  const pcm = Buffer.alloc(9600);
  for (let i = 0; i + 1 < pcm.length; i += 2) {
    pcm.writeInt16LE(4_000, i);
  }
  return pcm;
}

describe("bridge socket lifecycle", () => {
  test("returns RESULT after the client half-closes on END", async () => {
    const { server, port } = await startBridge();
    try {
      const text = await new Promise<string>((resolve, reject) => {
        const socket = net.connect({ host: "127.0.0.1", port }, () => {
          socket.write(startFrame());
          socket.write(encodeFrame(FrameType.AUDIO, speechAudio()));
          socket.write(encodeFrame(FrameType.END));
          // The Android client half-closes after END and still waits for
          // RESULT on the readable side.
          socket.end();
        });

        const parser = new FrameParser();
        socket.on("data", (chunk: Buffer) => {
          for (const frame of parser.push(chunk)) {
            if (frame.type === FrameType.RESULT) {
              resolve(JSON.parse(frame.payload.toString("utf8")).text);
            } else if (frame.type === FrameType.ERROR) {
              reject(new Error(frame.payload.toString("utf8")));
            }
          }
        });
        socket.on("error", reject);
        socket.on("close", () => reject(new Error("closed without a result")));
        setTimeout(() => reject(new Error("timeout")), 5000).unref?.();
      });

      expect(text).toBe("texto final");
    } finally {
      await closeServer(server);
    }
  });

  test("a half-close without END discards the session and frees the slot", async () => {
    const { server, port } = await startBridge();
    try {
      const closed = await new Promise<boolean>((resolve, reject) => {
        const socket = net.connect({ host: "127.0.0.1", port }, () => {
          socket.write(startFrame());
          socket.write(encodeFrame(FrameType.AUDIO, Buffer.alloc(960)));
          socket.end();
        });
        socket.on("close", () => resolve(true));
        socket.on("error", reject);
        setTimeout(() => reject(new Error("timeout")), 5000).unref?.();
      });
      expect(closed).toBe(true);

      // The single dictation slot must be free again.
      const text = await new Promise<string>((resolve, reject) => {
        const socket = net.connect({ host: "127.0.0.1", port }, () => {
          socket.write(startFrame());
          socket.write(encodeFrame(FrameType.AUDIO, speechAudio()));
          socket.write(encodeFrame(FrameType.END));
          socket.end();
        });
        const parser = new FrameParser();
        socket.on("data", (chunk: Buffer) => {
          for (const frame of parser.push(chunk)) {
            if (frame.type === FrameType.RESULT) {
              resolve(JSON.parse(frame.payload.toString("utf8")).text);
            } else if (frame.type === FrameType.ERROR) {
              reject(new Error(frame.payload.toString("utf8")));
            }
          }
        });
        socket.on("error", reject);
        setTimeout(() => reject(new Error("timeout")), 5000).unref?.();
      });
      expect(text).toBe("texto final");
    } finally {
      await closeServer(server);
    }
  });
});
