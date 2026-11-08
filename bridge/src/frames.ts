export const FrameType = {
  START: 0x01,
  AUDIO: 0x02,
  END: 0x03,
  PARTIAL: 0x04,
  RESULT: 0x81,
  ERROR: 0x82,
} as const;

export interface Frame {
  readonly type: number;
  readonly payload: Buffer;
}

const HEADER_BYTES = 5;
const INITIAL_CAPACITY = 16 * 1024;
export const MAX_FRAME_BYTES = 1024 * 1024;

export function encodeFrame(
  type: number,
  payload: Buffer | string = Buffer.alloc(0),
): Buffer {
  const body = typeof payload === "string" ? Buffer.from(payload, "utf8") : payload;
  if (body.length > MAX_FRAME_BYTES) {
    throw new Error(`frame too large: ${body.length} bytes`);
  }
  const header = Buffer.alloc(HEADER_BYTES);
  header.writeUInt8(type, 0);
  header.writeUInt32BE(body.length, 1);
  return Buffer.concat([header, body]);
}

export function encodeJson(type: number, value: unknown): Buffer {
  return encodeFrame(type, JSON.stringify(value));
}

/**
 * Incremental frame parser. Audio arrives in arbitrary TCP chunks; a frame
 * may be split across reads, and one read may carry several frames.
 *
 * The buffer grows geometrically and keeps only the incomplete tail, so a
 * frame split across many reads is O(n), not O(n²).
 */
export class FrameParser {
  private buffer: Buffer = Buffer.alloc(INITIAL_CAPACITY);
  private length = 0;

  push(chunk: Buffer): Frame[] {
    if (chunk.length === 0) return [];
    this.ensure(this.length + chunk.length);
    chunk.copy(this.buffer, this.length);
    this.length += chunk.length;

    const frames: Frame[] = [];
    let offset = 0;
    while (this.length - offset >= HEADER_BYTES) {
      const type = this.buffer.readUInt8(offset);
      const size = this.buffer.readUInt32BE(offset + 1);
      if (size > MAX_FRAME_BYTES) {
        throw new Error(`frame too large: ${size} bytes`);
      }
      if (this.length - offset < HEADER_BYTES + size) break;
      const start = offset + HEADER_BYTES;
      frames.push({
        type,
        payload: Buffer.from(this.buffer.subarray(start, start + size)),
      });
      offset = start + size;
    }

    if (offset > 0) {
      this.buffer.copy(this.buffer, 0, offset, this.length);
      this.length -= offset;
    }
    return frames;
  }

  private ensure(capacity: number): void {
    if (capacity <= this.buffer.length) return;
    let next = Math.max(INITIAL_CAPACITY, this.buffer.length * 2);
    while (next < capacity) next *= 2;
    const grown = Buffer.alloc(next);
    this.buffer.copy(grown, 0, 0, this.length);
    this.buffer = grown;
  }
}
