import { describe, expect, test } from "bun:test";
import {
  encodeFrame,
  encodeJson,
  FrameParser,
  FrameType,
  MAX_FRAME_BYTES,
} from "../src/frames.ts";

const decoder = (payload: Buffer): unknown =>
  JSON.parse(payload.toString("utf8"));

describe("frame codec", () => {
  test("round-trips a JSON frame", () => {
    const frame = encodeJson(FrameType.START, { version: 1, live: true });
    const parsed = new FrameParser().push(frame);
    expect(parsed).toHaveLength(1);
    expect(parsed[0]!.type).toBe(FrameType.START);
    expect(decoder(parsed[0]!.payload)).toEqual({ version: 1, live: true });
  });

  test("parses a frame split across reads", () => {
    const frame = encodeFrame(FrameType.AUDIO, Buffer.from([1, 2, 3, 4]));
    const parser = new FrameParser();
    expect(parser.push(frame.subarray(0, 3))).toHaveLength(0);
    expect(parser.push(frame.subarray(3, 7))).toHaveLength(0);
    const parsed = parser.push(frame.subarray(7));
    expect(parsed).toHaveLength(1);
    expect(parsed[0]!.payload).toEqual(Buffer.from([1, 2, 3, 4]));
  });

  test("parses several frames in one read", () => {
    const stream = Buffer.concat([
      encodeFrame(FrameType.AUDIO, Buffer.from([1, 2])),
      encodeFrame(FrameType.END),
    ]);
    const parsed = new FrameParser().push(stream);
    expect(parsed.map((frame) => frame.type)).toEqual([
      FrameType.AUDIO,
      FrameType.END,
    ]);
  });

  test("rejects a declared length over the cap", () => {
    const header = Buffer.alloc(5);
    header.writeUInt8(FrameType.AUDIO, 0);
    header.writeUInt32BE(MAX_FRAME_BYTES + 1, 1);
    expect(() => new FrameParser().push(header)).toThrow("frame too large");
  });

  test("rejects encoding a frame over the cap", () => {
    expect(() =>
      encodeFrame(FrameType.AUDIO, Buffer.alloc(MAX_FRAME_BYTES + 1)),
    ).toThrow("frame too large");
  });
});
