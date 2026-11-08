/**
 * Wraps raw PCM16LE samples in a RIFF/WAVE container. The bridge is mono at
 * 48 kHz; the utility also accepts stereo and 8-192 kHz so the test client and
 * future capture paths can share it.
 */
export function pcm16ToWav(
  pcm: Buffer,
  sampleRate = 48000,
  channels = 1,
): Buffer {
  if (!Number.isInteger(channels) || channels < 1 || channels > 2) {
    throw new Error(`unsupported channel count: ${channels}`);
  }
  if (
    !Number.isInteger(sampleRate) ||
    sampleRate < 8000 ||
    sampleRate > 192000
  ) {
    throw new Error(`unsupported sample rate: ${sampleRate}`);
  }
  if (pcm.length > 0xffff_ffff - 36) {
    throw new Error("PCM payload is too large for a RIFF file");
  }
  const blockAlign = channels * 2;
  if (pcm.length % blockAlign !== 0) {
    throw new Error(
      `PCM16 payload length ${pcm.length} is not a multiple of blockAlign ${blockAlign}`,
    );
  }
  const header = Buffer.alloc(44);
  header.write("RIFF", 0, "ascii");
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8, "ascii");
  header.write("fmt ", 12, "ascii");
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * blockAlign, 28);
  header.writeUInt16LE(blockAlign, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36, "ascii");
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}
