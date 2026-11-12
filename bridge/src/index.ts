import { parseArgs } from "node:util";
import { HexClient } from "./hex-client.ts";
import { createBridge } from "./server.ts";

const USAGE = `Usage: bun run src/index.ts --model <id> [options]

Required:
  --model <id>            Hex model id (catalog or imported custom-<hash>)

Options:
  --host <address>        Listen address (default 127.0.0.1)
  --port <number>         Listen port (default 59102)
  --hex <path>            Hex executable (default "hex", i.e. $PATH)
  --language <code>       Language hint (default pt)
  --token <secret>        Require this token in START (required for a LAN bind)
  --max-seconds <n>       Per-dictation audio cap (default 120)
  --live-window-ms <n>    Live tail window (default 4000)
  --live-stride-ms <n>    Live pass stride (default 500)
  --min-live-ms <n>       Minimum audio before previews (default 800)
  --idle-timeout-ms <n>   Cancel a session that stops sending audio (default 30000)
  --request-timeout-ms <n>  Per-inference caller timeout (default 120000)
  --log-transcripts       Log transcript text (off by default)
  --help                  Show this help
`;

function fail(message: string): never {
  console.error(message);
  console.error(USAGE);
  process.exit(2);
}

function numberInRange(
  name: string,
  raw: string,
  min: number,
  max: number,
): number {
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    fail(`invalid --${name}: ${raw}`);
  }
  return value;
}

function parseCli() {
  try {
    return parseArgs({
      args: process.argv.slice(2),
      options: {
        host: { type: "string", default: "127.0.0.1" },
        port: { type: "string", default: "59102" },
        hex: { type: "string", default: "hex" },
        model: { type: "string" },
        language: { type: "string", default: "pt" },
        token: { type: "string", default: "" },
        "max-seconds": { type: "string", default: "120" },
        "live-window-ms": { type: "string", default: "4000" },
        "live-stride-ms": { type: "string", default: "500" },
        "min-live-ms": { type: "string", default: "800" },
        "idle-timeout-ms": { type: "string", default: "30000" },
        "request-timeout-ms": { type: "string", default: "120000" },
        "log-transcripts": { type: "boolean", default: false },
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
if (values.help) {
  console.log(USAGE);
  process.exit(0);
}
if (!values.model) fail("--model is required");

// Prefer the environment for the shared secret so it does not appear in the
// process table (ps) on a shared machine. The CLI flag remains supported.
const token = values.token || process.env.DICTATION_TOKEN || "";

const port = numberInRange("port", values.port, 1, 65535);
const maxSeconds = numberInRange("max-seconds", values["max-seconds"], 1, 3600);
const liveWindowMs = numberInRange(
  "live-window-ms",
  values["live-window-ms"],
  500,
  30000,
);
const liveStrideMs = numberInRange(
  "live-stride-ms",
  values["live-stride-ms"],
  100,
  5000,
);
const minLiveMs = numberInRange("min-live-ms", values["min-live-ms"], 200, 5000);
const idleTimeoutMs = numberInRange(
  "idle-timeout-ms",
  values["idle-timeout-ms"],
  1000,
  300000,
);
const requestTimeoutMs = numberInRange(
  "request-timeout-ms",
  values["request-timeout-ms"],
  1000,
  600000,
);

const loopback =
  values.host === "127.0.0.1" ||
  values.host === "::1" ||
  values.host === "localhost";
if (!loopback && token === "") {
  fail(
    "binding to a non-loopback address requires --token or DICTATION_TOKEN; the phone and the PC must share it",
  );
}

const client = new HexClient({
  command: [values.hex, "service", "--embedded"],
  model: values.model,
  language: values.language,
  requestTimeoutMs,
  onProgress: (progress) => {
    if (progress.type === "downloading") {
      console.error(
        `[hex] downloading model: ${progress.downloadedBytes}/${progress.totalBytes} bytes`,
      );
    } else {
      console.error(`[hex] ${progress.type}`);
    }
  },
});

console.error(`[bridge] starting Hex with model ${values.model}...`);
try {
  await client.start();
} catch (error) {
  console.error(
    `[bridge] could not start Hex: ${error instanceof Error ? error.message : String(error)}`,
  );
  process.exit(1);
}
console.error(`[bridge] Hex ready: ${client.model} (${client.language})`);

const server = createBridge({
  token,
  maxSeconds,
  liveWindowMs,
  liveStrideMs,
  minLiveMs,
  idleTimeoutMs,
  logTranscripts: values["log-transcripts"],
  client,
});

server.on("error", (error) => {
  console.error(`[bridge] server error: ${error.message}`);
  void client.close().finally(() => process.exit(1));
});
server.listen(port, values.host, () => {
  console.log(
    `[bridge] listening on ${values.host}:${port} — model ${client.model}, language ${client.language}`,
  );
});

let shuttingDown = false;
const shutdown = async (exitCode = 0): Promise<void> => {
  if (shuttingDown) return;
  shuttingDown = true;
  console.error("[bridge] shutting down");
  await new Promise<void>((resolve) => {
    const timer = setTimeout(() => {
      (server as unknown as { closeAllConnections?: () => void })
        .closeAllConnections?.();
      resolve();
    }, 2_000);
    server.close(() => {
      clearTimeout(timer);
      resolve();
    });
  });
  await client.close();
  process.exit(exitCode);
};

process.on("SIGINT", () => {
  void shutdown();
});
process.on("SIGTERM", () => {
  void shutdown();
});
