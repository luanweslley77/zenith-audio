#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>

#include "ring_buffer.hpp"

namespace zenith {

struct MicJitterStats {
    uint64_t packets_received = 0;
    uint64_t packets_lost = 0;
    uint64_t packets_late = 0;
    uint64_t plc_frames = 0;
    uint64_t underruns = 0;
    uint64_t overruns = 0;
    uint64_t skipped_samples = 0;
    uint32_t buffer_fill_ms = 0;
};

// Packet-level timeline repair for the phone-to-PC microphone.
//
// The phone sends 10 ms PCM frames over UDP. UDP does not preserve order and
// does not tell us when a packet is lost, so appending frames in arrival order
// makes speech shorter every time a packet disappears (the "talking too fast"
// effect). This buffer turns the sequence number into a continuous sample
// timeline: missing frames are replaced with a short concealment tail, and the
// playout side holds a small target delay so jitter and late packets do not
// become clicks.
//
// Producer: the UDP receive thread (push_pcm/push_float).
// Consumer: the PipeWire real-time thread (pull).
class MicJitterBuffer {
public:
    static constexpr uint32_t kSampleRate = 48000;
    static constexpr size_t kTargetSamples = 1920;    // 40 ms playout reserve
    static constexpr size_t kMaxExtraSamples = 1920;  // cap at ~80 ms
    static constexpr size_t kFrameCapacity = 2048;    // max samples in one packet
    static constexpr uint32_t kMaxConcealMs = 150;    // never invent more than a short stall
    static constexpr uint64_t kIdleResetUs = 500'000; // stop PLC after the phone goes quiet

    void push_pcm(const uint8_t *data, size_t bytes, uint32_t seq, bool discontinuity) {
        if (!data || bytes < sizeof(int16_t)) return;
        size_t samples = bytes / sizeof(int16_t);
        if (samples > kFrameCapacity) samples = kFrameCapacity;

        float converted[kFrameCapacity];
        for (size_t i = 0; i < samples; ++i) {
            int16_t sample = 0;
            std::memcpy(&sample, data + i * sizeof(int16_t), sizeof(sample));
            converted[i] = static_cast<float>(sample) / 32768.0f;
        }
        push_float(converted, samples, seq, discontinuity);
    }

    void push_float(const float *data, size_t samples, uint32_t seq, bool discontinuity) {
        if (!data || samples == 0) return;
        if (samples > kFrameCapacity) samples = kFrameCapacity;
        packets_received_.fetch_add(1, std::memory_order_relaxed);

        // Old clients do not set FLAG_DISCONTINUITY; their sequence restarts at
        // zero every time the microphone is toggled. Treat that as a new
        // session instead of a very late packet, or the source would stay
        // silent until the sequence caught up again.
        const bool likely_restart = (seq == 0 && last_seq_ != 0);

        if (discontinuity || !have_last_seq_ || likely_restart) {
            // A new capture session starts its own sequence. Do not invent
            // audio for the time the previous session was gone.
            have_last_seq_ = true;
            last_seq_ = seq;
            last_frame_samples_ = 0;
            gap_gain_ = 1.0f;
        } else if (seq == last_seq_) {
            // Duplicate datagram.
            packets_late_.fetch_add(1, std::memory_order_relaxed);
            return;
        } else if (seq < last_seq_) {
            // Late packet. The timeline has already moved past it.
            packets_late_.fetch_add(1, std::memory_order_relaxed);
            return;
        } else if (seq > last_seq_ + 1) {
            uint32_t missing = seq - last_seq_ - 1;
            packets_lost_.fetch_add(missing, std::memory_order_relaxed);

            // Fill ordinary Wi-Fi drops, but never invent hundreds of
            // milliseconds of fake audio after a real disconnect. The limit is
            // expressed in time so it works for both 10 ms and 20 ms frames.
            uint32_t frame_ms = last_frame_samples_ > 0
                ? std::max<uint32_t>(1, static_cast<uint32_t>((last_frame_samples_ * 1000) / kSampleRate))
                : 10;
            uint32_t max_missing = std::max<uint32_t>(1, kMaxConcealMs / frame_ms);

            if (missing > max_missing) {
                last_frame_samples_ = 0;
                gap_gain_ = 1.0f;
            } else {
                for (uint32_t i = 0; i < missing; ++i) {
                    append_concealment_frame();
                }
            }
            last_seq_ = seq;
        } else {
            // Exactly the next expected packet.
            last_seq_ = seq;
        }

        last_frame_samples_ = samples;
        std::memcpy(last_frame_.data(), data, samples * sizeof(float));
        gap_gain_ = 1.0f;

        size_t written = ring_.write(data, samples);
        if (written < samples) {
            overruns_.fetch_add(1, std::memory_order_relaxed);
        }
        last_push_time_us_.store(now_us(), std::memory_order_release);
    }

    // Always writes exactly `samples` floats. Before enough audio has arrived,
    // it writes silence; after that it writes PCM or a concealment tail.
    size_t pull(float *dst, size_t samples) {
        if (!dst || samples == 0) return 0;

        // The phone can stop sending at any time (mic toggled off, app killed,
        // Wi-Fi lost). Without this timeout the source would keep generating
        // concealment forever and the underrun counter would grow without
        // bound. After a short silence, return to the initial prefill state.
        uint64_t last_push = last_push_time_us_.load(std::memory_order_acquire);
        if (started_ && last_push != 0 && now_us() - last_push > kIdleResetUs) {
            ring_.skip(ring_.available_to_read());
            started_ = false;
            plc_active_ = false;
            last_output_samples_ = 0;
            buffer_fill_ms_.store(0, std::memory_order_relaxed);
            std::memset(dst, 0, samples * sizeof(float));
            return samples;
        }

        if (!started_) {
            if (ring_.available_to_read() >= kTargetSamples) {
                started_ = true;
                fade_in_ = kFadeSamples;
            } else {
                std::memset(dst, 0, samples * sizeof(float));
                return samples;
            }
        }

        size_t available = ring_.available_to_read();
        if (available > kTargetSamples + kMaxExtraSamples) {
            // A Wi-Fi burst or a short hiccup must not become permanent extra
            // latency. Drop the oldest audio and fade back in.
            size_t to_skip = available - kTargetSamples;
            size_t skipped = ring_.skip(to_skip);
            skipped_samples_.fetch_add(skipped, std::memory_order_relaxed);
            overruns_.fetch_add(1, std::memory_order_relaxed);
            fade_in_ = kFadeSamples;
            plc_active_ = false;
        }

        size_t read = ring_.read(dst, samples);
        if (read < samples) {
            size_t missing = samples - read;
            fill_concealment(dst + read, missing);
            underruns_.fetch_add(1, std::memory_order_relaxed);
            plc_frames_.fetch_add(1, std::memory_order_relaxed);
            plc_active_ = true;
        } else if (plc_active_) {
            // Real audio is back after a gap: ramp it in instead of starting
            // with a step that would click.
            fade_in_ = kFadeSamples;
            plc_active_ = false;
        }

        if (fade_in_ > 0) {
            size_t n = std::min<size_t>(fade_in_, samples);
            for (size_t i = 0; i < n; ++i) {
                dst[i] *= static_cast<float>(i + 1) / static_cast<float>(n + 1);
            }
            fade_in_ -= n;
        }

        size_t copy = std::min<size_t>(samples, kFrameCapacity);
        std::memcpy(last_output_.data(), dst + (samples - copy), copy * sizeof(float));
        last_output_samples_ = copy;

        buffer_fill_ms_.store(
            static_cast<uint32_t>((ring_.available_to_read() * 1000) / kSampleRate),
            std::memory_order_relaxed);

        return samples;
    }

    MicJitterStats get_stats() const {
        MicJitterStats stats;
        stats.packets_received = packets_received_.load(std::memory_order_relaxed);
        stats.packets_lost = packets_lost_.load(std::memory_order_relaxed);
        stats.packets_late = packets_late_.load(std::memory_order_relaxed);
        stats.plc_frames = plc_frames_.load(std::memory_order_relaxed);
        stats.underruns = underruns_.load(std::memory_order_relaxed);
        stats.overruns = overruns_.load(std::memory_order_relaxed);
        stats.skipped_samples = skipped_samples_.load(std::memory_order_relaxed);
        stats.buffer_fill_ms = buffer_fill_ms_.load(std::memory_order_relaxed);
        return stats;
    }

private:
    static constexpr size_t kFadeSamples = 64; // ~1.3 ms at 48 kHz

    static uint64_t now_us() {
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count());
    }

    void append_concealment_frame() {
        float frame[kFrameCapacity];
        size_t count = last_frame_samples_;

        if (count == 0) {
            count = 480; // 10 ms of silence
            std::memset(frame, 0, count * sizeof(float));
        } else {
            for (size_t i = 0; i < count; ++i) {
                frame[i] = last_frame_[i] * gap_gain_;
            }
            gap_gain_ *= 0.55f;
            if (gap_gain_ < 0.02f) {
                gap_gain_ = 0.0f;
            }
        }

        size_t written = ring_.write(frame, count);
        if (written < count) {
            overruns_.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void fill_concealment(float *dst, size_t samples) {
        size_t done = 0;
        float gain = 0.8f;

        while (done < samples) {
            size_t available = last_output_samples_;
            if (available == 0) {
                size_t n = std::min<size_t>(samples - done, 480);
                std::memset(dst + done, 0, n * sizeof(float));
                done += n;
                gain *= 0.6f;
                continue;
            }

            size_t n = std::min<size_t>(samples - done, available);
            const float *src = last_output_.data() + (available - n);
            for (size_t i = 0; i < n; ++i) {
                dst[done + i] = src[i] * gain;
            }
            done += n;
            gain *= 0.6f;
        }
    }

    SpscRingBuffer<float, 65536> ring_;

    // Producer state (UDP thread only).
    std::array<float, kFrameCapacity> last_frame_{};
    size_t last_frame_samples_ = 0;
    uint32_t last_seq_ = 0;
    bool have_last_seq_ = false;
    float gap_gain_ = 1.0f;

    // Consumer state (PipeWire real-time thread only).
    bool started_ = false;
    bool plc_active_ = false;
    size_t fade_in_ = 0;
    std::array<float, kFrameCapacity> last_output_{};
    size_t last_output_samples_ = 0;

    // Shared counters.
    std::atomic<uint64_t> packets_received_{0};
    std::atomic<uint64_t> packets_lost_{0};
    std::atomic<uint64_t> packets_late_{0};
    std::atomic<uint64_t> plc_frames_{0};
    std::atomic<uint64_t> underruns_{0};
    std::atomic<uint64_t> overruns_{0};
    std::atomic<uint64_t> skipped_samples_{0};
    std::atomic<uint32_t> buffer_fill_ms_{0};
    std::atomic<uint64_t> last_push_time_us_{0};
};

} // namespace zenith
