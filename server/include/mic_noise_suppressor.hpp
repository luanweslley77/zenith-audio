#pragma once

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

    // Processes whole 480-sample frames in place. A short trailing frame (for
    // example after a malformed packet) is left untouched.
    void process(float *samples, size_t count) {
#ifdef ZENITH_HAVE_RNNOISE
        if (!state_ || !samples) return;

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

            for (size_t i = 0; i < kFrameSamples; ++i) {
                samples[offset + i] = output[i] / 32768.0f;
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
};

} // namespace zenith
