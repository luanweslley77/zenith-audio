import { describe, expect, test } from "bun:test";
import { pcm16ToWav } from "../src/wav.ts";

describe("WAV container", () => {
  test("writes a 48 kHz mono PCM16 header", () => {
    const pcm = Buffer.alloc(960); // 10 ms at 48 kHz
    const wav = pcm16ToWav(pcm, 48000, 1);
    expect(wav.toString("ascii", 0, 4)).toBe("RIFF");
    expect(wav.toString("ascii", 8, 12)).toBe("WAVE");
    expect(wav.toString("ascii", 12, 16)).toBe("fmt ");
    expect(wav.readUInt16LE(20)).toBe(1);
    expect(wav.readUInt16LE(22)).toBe(1);
    expect(wav.readUInt32LE(24)).toBe(48000);
    expect(wav.readUInt32LE(28)).toBe(96000);
    expect(wav.readUInt16LE(32)).toBe(2);
    expect(wav.readUInt16LE(34)).toBe(16);
    expect(wav.toString("ascii", 36, 40)).toBe("data");
    expect(wav.readUInt32LE(40)).toBe(pcm.length);
    expect(wav.readUInt32LE(4)).toBe(36 + pcm.length);
    expect(wav.length).toBe(44 + pcm.length);
  });

  test("rejects an odd PCM byte length", () => {
    expect(() => pcm16ToWav(Buffer.alloc(3))).toThrow("blockAlign");
  });
});
