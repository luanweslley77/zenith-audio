#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>

#ifdef ZENITH_HAVE_RNNOISE
#include <rnnoise.h>
#endif

namespace zenith {

// Optional RNNoise stage for the phone microphone.
//
// RNNoise works on 10 ms (480 sample) frames at 48 kHz and expects float
// samples in the same scale as 16-bit PCM. The phone already sends 10 ms
// frames, so this adds no extra buffering. When the server is built without
// RNNoise, process() is a no-op and the rest of the microphone path still
// works.
class MicNoiseSuppressor {
public:
    static constexpr size_t kFrameSamples = 480;

    MicNoiseSuppressor() {
#ifdef ZENITH_HAVE_RNNOISE
        state_ = rnnoise_create(nullptr);
        linked_ = state_ != nullptr;
#endif
    }

    ~MicNoiseSuppressor() {
#ifdef ZENITH_HAVE_RNNOISE
        if (state_) {
            rnnoise_destroy(state_);
            state_ = nullptr;
        }
#endif
    }

    MicNoiseSuppressor(const MicNoiseSuppressor &) = delete;
    MicNoiseSuppressor &operator=(const MicNoiseSuppressor &) = delete;

    bool available() const {
        return linked_;
    }

    // 0 = bypass, 1 = full RNNoise. The control packet from the phone sets it.
    void set_wet_mix(float mix) {
        float clamped = std::clamp(mix, 0.0f, 1.0f);
        float previous = wet_mix_.exchange(clamped, std::memory_order_relaxed);
        if (previous <= 0.001f && clamped > 0.001f &&
            processed_frames_.load(std::memory_order_relaxed) > 0) {
            // The model was not advanced during bypass; restart it instead of
            // resuming from a stale voice/noise estimate.
            reset_requested_.store(true, std::memory_order_relaxed);
        }
    }

    float wet_mix() const {
        return wet_mix_.load(std::memory_order_relaxed);
    }

    // Reset the model between capture sessions. Called from the same thread
    // that calls process(), so the DenoiseState is never touched concurrently.
    void reset() {
#ifdef ZENITH_HAVE_RNNOISE
        if (state_) {
            rnnoise_destroy(state_);
            state_ = nullptr;
        }
        state_ = rnnoise_create(nullptr);
#endif
    }

    uint64_t processed_frames() const {
        return processed_frames_.load(std::memory_order_relaxed);
    }

    // Processes whole 480-sample frames in place. A short trailing frame (for
    // example after a malformed packet) is left untouched.
    void process(float *samples, size_t count) {
#ifdef ZENITH_HAVE_RNNOISE
        if (!samples || !state_) return;
        if (reset_requested_.exchange(false, std::memory_order_relaxed)) {
            reset();
            if (!state_) return;
        }

        const float wet = wet_mix_.load(std::memory_order_relaxed);
        if (wet <= 0.001f) return;

        size_t offset = 0;
        while (offset + kFrameSamples <= count) {
            float input[kFrameSamples];
            float output[kFrameSamples];

            for (size_t i = 0; i < kFrameSamples; ++i) {
                input[i] = samples[offset + i] * 32768.0f;
            }

            // rnnoise_process_frame returns the voice-activity probability,
            // which is not needed here; the denoised samples are in output.
            rnnoise_process_frame(state_, output, input);
            processed_frames_.fetch_add(1, std::memory_order_relaxed);

            for (size_t i = 0; i < kFrameSamples; ++i) {
                float mixed = output[i] * wet + input[i] * (1.0f - wet);
                samples[offset + i] = mixed / 32768.0f;
            }

            offset += kFrameSamples;
        }
#else
        (void)samples;
        (void)count;
#endif
    }

private:
#ifdef ZENITH_HAVE_RNNOISE
    DenoiseState *state_ = nullptr;
#endif
    bool linked_ = false;
    std::atomic<float> wet_mix_{0.0f};
    std::atomic<uint64_t> processed_frames_{0};
    std::atomic<bool> reset_requested_{false};
};

} // namespace zenith
