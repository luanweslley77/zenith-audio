import { timingSafeEqual } from "node:crypto";
import net from "node:net";
import { encodeJson, FrameParser, FrameType } from "./frames.ts";
import type { HexClient } from "./hex-client.ts";
import {
  errorMessage,
  LiveSession,
  SessionError,
  type SessionConfig,
} from "./live-session.ts";

export interface BridgeOptions {
  readonly token: string;
  readonly maxSeconds: number;
  readonly liveWindowMs: number;
  readonly liveStrideMs: number;
  readonly minLiveMs: number;
  readonly idleTimeoutMs: number;
  readonly logTranscripts: boolean;
  readonly client: HexClient;
}

class ProtocolError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

interface StartRequest {
  readonly sampleRate: number;
  readonly live: boolean;
  readonly liveWindowMs: number;
  readonly liveStrideMs: number;
  readonly minLiveMs: number;
}

interface LiveDefaults {
  readonly liveWindowMs: number;
  readonly liveStrideMs: number;
  readonly minLiveMs: number;
}

function parseStart(
  payload: Buffer,
  token: string,
  client: HexClient,
  defaults: LiveDefaults,
): StartRequest {
  let value: unknown;
  try {
    value = JSON.parse(payload.toString("utf8"));
  } catch {
    throw new ProtocolError("bad-request", "START payload is not JSON");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ProtocolError("bad-request", "START payload must be an object");
  }
  const message = value as Record<string, unknown>;
  if (message.version !== 1) {
    throw new ProtocolError("bad-request", "unsupported protocol version");
  }
  if (message.sampleRate !== 48000 || message.channels !== 1) {
    throw new ProtocolError(
      "unsupported-format",
      "the bridge accepts 48000 Hz mono only",
    );
  }
  if (message.format !== "s16le") {
    throw new ProtocolError("unsupported-format", "the bridge accepts s16le only");
  }
  if (token !== "" && !tokenMatches(token, message.token)) {
    throw new ProtocolError("bad-request", "invalid token");
  }
  if (typeof message.model === "string" && message.model !== client.model) {
    throw new ProtocolError("bad-request", "the bridge runs a different model");
  }
  if (
    typeof message.language === "string" &&
    message.language !== client.language
  ) {
    throw new ProtocolError("bad-request", "the bridge runs a different language");
  }
  return {
    sampleRate: 48000,
    live: message.live === true,
    liveWindowMs: clampNumber(
      message.liveWindowMs,
      defaults.liveWindowMs,
      500,
      30000,
    ),
    liveStrideMs: clampNumber(
      message.liveStrideMs,
      defaults.liveStrideMs,
      100,
      5000,
    ),
    minLiveMs: clampNumber(message.minLiveMs, defaults.minLiveMs, 200, 5000),
  };
}

function clampNumber(
  value: unknown,
  fallback: number,
  min: number,
  max: number,
): number {
  if (typeof value !== "number" || !Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, Math.round(value)));
}

function tokenMatches(expected: string, actual: unknown): boolean {
  if (typeof actual !== "string") return false;
  const a = Buffer.from(expected, "utf8");
  const b = Buffer.from(actual, "utf8");
  return a.length === b.length && timingSafeEqual(a, b);
}

export function createBridge(options: BridgeOptions): net.Server {
  // The socket that owns the single dictation slot. A stale close must never
  // clear the slot for a newer session.
  let activeSocket: net.Socket | null = null;

  return net.createServer((socket) => {
    let session: LiveSession | null = null;
    let started = false;
    let ended = false;
    let failed = false;
    let idleTimer: ReturnType<typeof setTimeout> | null = null;
    const parser = new FrameParser();

    const clearIdle = (): void => {
      if (idleTimer !== null) {
        clearTimeout(idleTimer);
        idleTimer = null;
      }
    };

    const sink = {
      send(frame: Buffer): void {
        if (socket.destroyed) return;
        // A client that stops reading must not grow the write buffer without
        // bound; live partials would keep filling it.
        if (socket.writableLength > 4 * 1024 * 1024) {
          socket.destroy();
          return;
        }
        socket.write(frame);
      },
      close(): void {
        if (!socket.destroyed) socket.end();
      },
    };

    const fail = (code: string, message: string): void => {
      clearIdle();
      if (failed || socket.destroyed) return;
      failed = true;
      socket.write(encodeJson(FrameType.ERROR, { version: 1, code, message }));
      socket.end();
    };

    const touchIdle = (): void => {
      clearIdle();
      idleTimer = setTimeout(() => {
        if (activeSocket === socket) activeSocket = null;
        session?.cancel();
        fail("timeout", "no audio received");
      }, options.idleTimeoutMs);
      idleTimer.unref?.();
    };

    const handle = (frame: { type: number; payload: Buffer }): void => {
      switch (frame.type) {
        case FrameType.START: {
          if (started) {
            throw new ProtocolError("bad-request", "START already received");
          }
          if (activeSocket !== null) {
            throw new ProtocolError("model-busy", "another dictation is active");
          }
          const request = parseStart(frame.payload, options.token, options.client, {
            liveWindowMs: options.liveWindowMs,
            liveStrideMs: options.liveStrideMs,
            minLiveMs: options.minLiveMs,
          });
          const config: SessionConfig = {
            sampleRate: request.sampleRate,
            channels: 1,
            live: request.live,
            liveWindowMs: request.liveWindowMs,
            liveStrideMs: request.liveStrideMs,
            minLiveMs: request.minLiveMs,
            maxSeconds: options.maxSeconds,
            model: options.client.model,
            language: options.client.language,
            logTranscripts: options.logTranscripts,
          };
          session = new LiveSession(sink, options.client, config);
          started = true;
          activeSocket = socket;
          touchIdle();
          return;
        }
        case FrameType.AUDIO: {
          if (session === null) {
            throw new ProtocolError("bad-request", "AUDIO before START");
          }
          if (ended) {
            throw new ProtocolError("bad-request", "AUDIO after END");
          }
          try {
            session.append(frame.payload);
          } catch (error) {
            if (error instanceof SessionError) {
              throw new ProtocolError(error.code, error.message);
            }
            throw error;
          }
          touchIdle();
          return;
        }
        case FrameType.END: {
          if (session === null) {
            throw new ProtocolError("bad-request", "END before START");
          }
          if (ended) {
            throw new ProtocolError("bad-request", "END already received");
          }
          ended = true;
          clearIdle();
          session
            .finish()
            .catch((error: unknown) => {
              fail("internal", errorMessage(error));
            })
            .finally(() => {
              if (activeSocket === socket) activeSocket = null;
            });
          return;
        }
        default:
          throw new ProtocolError(
            "bad-frame",
            `unknown frame type 0x${frame.type.toString(16)}`,
          );
      }
    };

    socket.on("data", (chunk: Buffer) => {
      let frames;
      try {
        frames = parser.push(chunk);
      } catch (error) {
        fail("bad-frame", errorMessage(error));
        return;
      }
      for (const frame of frames) {
        try {
          handle(frame);
        } catch (error) {
          if (error instanceof ProtocolError) {
            fail(error.code, error.message);
          } else {
            fail("internal", errorMessage(error));
          }
          return;
        }
      }
    });

    socket.on("close", () => {
      clearIdle();
      session?.cancel();
      if (activeSocket === socket) activeSocket = null;
    });
    socket.on("error", () => {
      socket.destroy();
    });
  });
}
