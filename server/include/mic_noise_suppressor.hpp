#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

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
#ifdef ZENITH_HAVE_RNNOISE
        return state_ != nullptr;
#else
        return false;
#endif
    }

    // 0 = bypass, 1 = full RNNoise. The control packet from the phone sets it.
    void set_wet_mix(float mix) {
        wet_mix_.store(std::clamp(mix, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    float wet_mix() const {
        return wet_mix_.load(std::memory_order_relaxed);
    }

    // Processes whole 480-sample frames in place. A short trailing frame (for
    // example after a malformed packet) is left untouched.
    void process(float *samples, size_t count) {
#ifdef ZENITH_HAVE_RNNOISE
        if (!samples) return;

        const float user_wet = wet_mix_.load(std::memory_order_relaxed);
        size_t offset = 0;

        while (offset + kFrameSamples <= count) {
            float input[kFrameSamples];
            float output[kFrameSamples];

            double sum = 0.0;
            for (size_t i = 0; i < kFrameSamples; ++i) {
                input[i] = samples[offset + i] * 32768.0f;
                sum += static_cast<double>(input[i]) * input[i];
            }
            float input_rms = static_cast<float>(
                std::sqrt(sum / kFrameSamples) / 32768.0);

            // Slow level follower so the dry/wet decision does not pump on
            // every syllable. At quiet levels (distant speech) keep more of
            // the dry signal; at normal levels let RNNoise remove the hiss.
            smoothed_rms_ = 0.9f * smoothed_rms_ + 0.1f * input_rms;

            if (user_wet <= 0.001f || !state_) {
                offset += kFrameSamples;
                continue;
            }

            // rnnoise_process_frame returns the voice-activity probability,
            // which is not needed here; the denoised samples are in output.
            rnnoise_process_frame(state_, output, input);

            float level = (smoothed_rms_ - kQuietRms) / (kLoudRms - kQuietRms);
            level = std::clamp(level, 0.0f, 1.0f);
            float effective_wet = user_wet * (kQuietWetFloor + (1.0f - kQuietWetFloor) * level);

            for (size_t i = 0; i < kFrameSamples; ++i) {
                float mixed = output[i] * effective_wet +
                              input[i] * (1.0f - effective_wet);
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
    static constexpr float kQuietRms = 0.006f;     // about -44 dBFS
    static constexpr float kLoudRms = 0.030f;      // about -30 dBFS
    static constexpr float kQuietWetFloor = 0.35f; // preserve distant speech

#ifdef ZENITH_HAVE_RNNOISE
    DenoiseState *state_ = nullptr;
#endif
    std::atomic<float> wet_mix_{0.8f};
    float smoothed_rms_ = 0.0f;
};

} // namespace zenith
