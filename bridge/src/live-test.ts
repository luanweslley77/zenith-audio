import fs from "node:fs";
import net from "node:net";
import { parseArgs } from "node:util";
import { encodeFrame, encodeJson, FrameParser, FrameType } from "./frames.ts";

interface Wav {
  readonly pcm: Buffer;
  readonly sampleRate: number;
  readonly channels: number;
  readonly bits: number;
}

const USAGE = `Usage: bun run src/live-test.ts --wav <48k-mono-s16le.wav> [options]

Options:
  --host <address>    Bridge address (default 127.0.0.1)
  --port <number>     Bridge port (default 59102)
  --token <secret>    Token, when the bridge requires one
  --fast              Send audio 10x faster than real time
  --no-live           Do not request partials
  --chunk-ms <n>      Audio chunk size (default 100)

Create a suitable WAV with:
  ffmpeg -i input.wav -ar 48000 -ac 1 -c:a pcm_s16le output.wav
`;

function fail(message: string): never {
  console.error(message);
  console.error(USAGE);
  process.exit(2);
}

function readWav(path: string): Wav {
  const buf = fs.readFileSync(path);
  if (
    buf.toString("ascii", 0, 4) !== "RIFF" ||
    buf.toString("ascii", 8, 12) !== "WAVE"
  ) {
    throw new Error("not a RIFF/WAVE file");
  }
  let offset = 12;
  let fmt: { sampleRate: number; channels: number; bits: number } | null = null;
  let data: Buffer | null = null;
  while (offset + 8 <= buf.length) {
    const id = buf.toString("ascii", offset, offset + 4);
    const size = buf.readUInt32LE(offset + 4);
    const body = offset + 8;
    if (id === "fmt ") {
      if (size < 16 || body + 16 > buf.length) {
        throw new Error("WAV has a truncated fmt chunk");
      }
      const channels = buf.readUInt16LE(body + 2);
      const sampleRate = buf.readUInt32LE(body + 4);
      const bits = buf.readUInt16LE(body + 14);
      fmt = { sampleRate, channels, bits };
    } else if (id === "data") {
      const end = Math.min(buf.length, body + size);
      data = buf.subarray(body, end);
      break;
    }
    offset = body + size + (size % 2);
  }
  if (fmt === null) throw new Error("WAV has no fmt chunk");
  if (data === null) throw new Error("WAV has no data chunk");
  return { pcm: data, ...fmt };
}

function parseCli() {
  try {
    return parseArgs({
      args: process.argv.slice(2),
      options: {
        wav: { type: "string" },
        host: { type: "string", default: "127.0.0.1" },
        port: { type: "string", default: "59102" },
        token: { type: "string", default: "" },
        fast: { type: "boolean", default: false },
        "no-live": { type: "boolean", default: false },
        "chunk-ms": { type: "string", default: "100" },
        help: { type: "boolean", default: false },
      } as const,
      strict: true,
      allowPositionals: false,
    });
  } catch (error) {
    fail(error instanceof Error ? error.message : String(error));
  }
}

const values = parseCli().values;
if (values.help || !values.wav) {
  console.log(USAGE);
  process.exit(values.help ? 0 : 2);
}

const wav = readWav(values.wav);
if (wav.sampleRate !== 48000 || wav.channels !== 1 || wav.bits !== 16) {
  fail(
    `unsupported WAV: ${wav.sampleRate} Hz, ${wav.channels} channels, ${wav.bits} bits (need 48000/1/16)`,
  );
}

const port = Number(values.port);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  fail(`invalid --port: ${values.port}`);
}
const chunkMs = Number(values["chunk-ms"]);
if (!Number.isFinite(chunkMs) || chunkMs <= 0) {
  fail(`invalid --chunk-ms: ${values["chunk-ms"]}`);
}
const chunkBytes = Math.max(2, Math.floor((48000 * chunkMs) / 1000) * 2);
const delayMs = values.fast ? Math.max(1, chunkMs / 10) : chunkMs;

let done = false;

const socket = net.connect({ host: values.host, port }, () => {
  socket.write(
    encodeJson(FrameType.START, {
      version: 1,
      sampleRate: 48000,
      channels: 1,
      format: "s16le",
      token: values.token,
      live: !values["no-live"],
      liveWindowMs: 4000,
      liveStrideMs: 500,
      minLiveMs: 800,
    }),
  );
  void sendAudio();
});

const parser = new FrameParser();

async function sendAudio(): Promise<void> {
  for (
    let offset = 0;
    offset < wav.pcm.length && !done;
    offset += chunkBytes
  ) {
    if (socket.destroyed) return;
    const end = Math.min(wav.pcm.length, offset + chunkBytes);
    socket.write(encodeFrame(FrameType.AUDIO, wav.pcm.subarray(offset, end)));
    await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
  if (!done && !socket.destroyed) {
    socket.write(encodeFrame(FrameType.END));
  }
}

function parseJson(payload: Buffer): Record<string, unknown> {
  const value: unknown = JSON.parse(payload.toString("utf8"));
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("JSON payload is not an object");
  }
  return value as Record<string, unknown>;
}

socket.on("data", (chunk: Buffer) => {
  let frames;
  try {
    frames = parser.push(chunk);
  } catch (error) {
    console.error(
      `frame error: ${error instanceof Error ? error.message : String(error)}`,
    );
    done = true;
    process.exitCode = 1;
    socket.end();
    return;
  }
  for (const frame of frames) {
    try {
      if (frame.type === FrameType.PARTIAL) {
        const partial = parseJson(frame.payload);
        console.log(
          `PARTIAL [${partial.startMs}-${partial.endMs} ms] ${partial.text}`,
        );
      } else if (frame.type === FrameType.RESULT) {
        const result = parseJson(frame.payload);
        console.log(
          `RESULT (${result.livePasses} live passes) ${result.text}`,
        );
        done = true;
        socket.end();
      } else if (frame.type === FrameType.ERROR) {
        const error = parseJson(frame.payload);
        console.error(`ERROR ${error.code}: ${error.message}`);
        done = true;
        process.exitCode = 1;
        socket.end();
      }
    } catch (error) {
      console.error(
        `payload error: ${error instanceof Error ? error.message : String(error)}`,
      );
      done = true;
      process.exitCode = 1;
      socket.end();
      return;
    }
  }
});

socket.on("close", () => {
  if (!done) {
    console.error("connection closed before a result");
    process.exitCode = 1;
  }
});

socket.on("error", (error) => {
  console.error(`socket error: ${error.message}`);
  done = true;
  process.exitCode = 1;
});
