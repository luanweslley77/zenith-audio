#pragma once

#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>
#include <opus/opus.h>
#include <thread>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstdint>
#include "mic_jitter_buffer.hpp"
#include "mic_noise_suppressor.hpp"

namespace zenith {

class PipeWireMicSink {
public:
    PipeWireMicSink();
    ~PipeWireMicSink();

    bool start();
    void stop();

    void push_audio_frame(const uint8_t *data, size_t size, bool is_pcm,
                          uint32_t seq, uint64_t timestamp_us, bool discontinuity);
    void set_noise_suppression_percent(int percent);
    void process_audio();

    MicJitterStats get_stats() const;

private:
    void thread_main();

    std::atomic<struct pw_main_loop *> loop_{nullptr};
    std::atomic<struct pw_stream *> stream_{nullptr};
    std::mutex loop_mutex_;
    std::thread loop_thread_;
    std::atomic<bool> is_running_{false};

    OpusDecoder *opus_decoder_ = nullptr;
    MicNoiseSuppressor noise_suppressor_;
    MicJitterBuffer jitter_buffer_;
};

} // namespace zenith
