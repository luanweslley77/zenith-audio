import {
  create,
  type ModelId,
  type ModelProgress,
  type Transcriber,
} from "@kitlangton/hex";

export interface HexClientOptions {
  readonly command: readonly [string, ...string[]];
  /** Catalog id or an imported `custom-<hash>` id. */
  readonly model: string;
  readonly language: string;
  readonly onProgress?: (progress: ModelProgress) => void;
  /** Reject a caller that waits this long for one inference. Default 120 s. */
  readonly requestTimeoutMs?: number;
  /** Maximum wait for queued work during shutdown. Default 5 s. */
  readonly closeTimeoutMs?: number;
}

export interface Transcription {
  readonly transcript: string;
  readonly durationMs: number;
}

/**
 * A resident Hex engine. The model stays loaded across passes, which is what
 * makes repeated live passes affordable. Requests are serialized because the
 * engine runs one inference at a time.
 */
export class HexClient {
  private transcriber: Transcriber | null = null;
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private readonly options: HexClientOptions) {}

  async start(): Promise<void> {
    this.transcriber = await create({
      command: this.options.command,
      // The published types list the catalog ids, but the runtime also accepts
      // an imported `custom-<hash>` id (our pt-BR model is one).
      model: this.options.model as ModelId,
      language: this.options.language,
      onProgress: this.options.onProgress,
    });
  }

  get model(): string {
    return this.options.model;
  }

  get language(): string {
    return this.options.language;
  }

  transcribe(wav: Uint8Array): Promise<Transcription> {
    const transcriber = this.transcriber;
    if (transcriber === null) {
      return Promise.reject(new Error("model-unavailable"));
    }
    const run = this.queue.then(() => transcriber.transcribe(wav));
    this.queue = run.then(
      () => undefined,
      () => undefined,
    );
    // The timeout rejects the caller but leaves the engine finishing the
    // request; the queue still tracks it, so the next pass cannot overlap.
    return withTimeout(
      run,
      this.options.requestTimeoutMs ?? 120_000,
      "timeout",
    );
  }

  async close(): Promise<void> {
    const transcriber = this.transcriber;
    this.transcriber = null;
    // Give queued/in-flight passes a bounded chance to drain before the
    // native engine is torn down. A stuck engine must not hang shutdown.
    const drained = await withTimeoutValue(
      this.queue.then(() => true),
      this.options.closeTimeoutMs ?? 5_000,
      false,
    );
    if (!drained) {
      // The engine is still inside a native inference. Let process exit reap
      // the child instead of tearing the helper down mid-call.
      console.error("[hex] close timed out; leaving the engine to process exit");
      return;
    }
    try {
      await transcriber?.close();
    } catch {
      // Shutdown is best effort; a closed or crashed helper is already gone.
    }
  }
}

function withTimeout<T>(
  promise: Promise<T>,
  timeoutMs: number,
  message: string,
): Promise<T> {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) return promise;
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), timeoutMs);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

function withTimeoutValue<T>(
  promise: Promise<T>,
  timeoutMs: number,
  fallback: T,
): Promise<T> {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) return promise;
  return new Promise<T>((resolve) => {
    const timer = setTimeout(() => resolve(fallback), timeoutMs);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      () => {
        clearTimeout(timer);
        resolve(fallback);
      },
    );
  });
}
