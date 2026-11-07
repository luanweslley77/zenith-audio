#include "pipewire_mic.hpp"
#include <algorithm>
#include <iostream>
#include <cstring>

namespace zenith {

static const struct pw_stream_events mic_stream_events = {
    .version = PW_VERSION_STREAM_EVENTS,
    .destroy = nullptr,
    .state_changed = [](void *userdata, enum pw_stream_state old_state,
                        enum pw_stream_state state, const char *error) {
        (void)userdata;
        (void)old_state;
        if (state == PW_STREAM_STATE_STREAMING) {
            std::cout << "[PipeWireMic] Virtual microphone is online and ready for applications.\n";
        } else if (state == PW_STREAM_STATE_ERROR) {
            std::cerr << "[PipeWireMic] Stream error: " << (error ? error : "unknown") << "\n";
        }
    },
    .control_info = nullptr,
    .io_changed = nullptr,
    .param_changed = nullptr,
    .add_buffer = nullptr,
    .remove_buffer = nullptr,
    .process = [](void *userdata) {
        auto *self = static_cast<PipeWireMicSink*>(userdata);
        self->process_audio();
    },
    .drained = nullptr,
    .command = nullptr,
    .trigger_done = nullptr,
};

PipeWireMicSink::PipeWireMicSink() {
    int error = 0;
    opus_decoder_ = opus_decoder_create(48000, 1, &error);
    if (error != OPUS_OK || !opus_decoder_) {
        std::cerr << "[PipeWireMic] Failed to create Opus decoder: " << opus_strerror(error) << "\n";
    }
}

PipeWireMicSink::~PipeWireMicSink() {
    stop();
    if (opus_decoder_) {
        opus_decoder_destroy(opus_decoder_);
        opus_decoder_ = nullptr;
    }
}

bool PipeWireMicSink::start() {
    if (is_running_.load()) return false;
    is_running_.store(true);
    loop_thread_ = std::thread(&PipeWireMicSink::thread_main, this);
    return true;
}

void PipeWireMicSink::stop() {
    if (!is_running_.load()) return;
    is_running_.store(false);

    if (loop_) {
        pw_main_loop_quit(loop_);
    }

    if (loop_thread_.joinable()) {
        loop_thread_.join();
    }
}

void PipeWireMicSink::thread_main() {
    pw_init(nullptr, nullptr);

    loop_ = pw_main_loop_new(nullptr);
    if (!loop_) {
        std::cerr << "[PipeWireMic] Failed to create main loop\n";
        is_running_.store(false);
        return;
    }

    struct pw_properties *props = pw_properties_new(
        PW_KEY_MEDIA_TYPE, "Audio",
        PW_KEY_MEDIA_CATEGORY, "Playback",
        PW_KEY_MEDIA_CLASS, "Audio/Source",
        PW_KEY_MEDIA_ROLE, "Communication",
        PW_KEY_NODE_NAME, "zenith-mic",
        PW_KEY_NODE_DESCRIPTION, "Zenith Wireless Microphone",
        PW_KEY_APP_NAME, "ZenithAudioServer",
        PW_KEY_NODE_LATENCY, "480/48000",
        PW_KEY_NODE_RATE, "1/48000",
        nullptr
    );

    stream_ = pw_stream_new_simple(
        pw_main_loop_get_loop(loop_),
        "zenith-mic-source",
        props,
        &mic_stream_events,
        this
    );

    if (!stream_) {
        std::cerr << "[PipeWireMic] Failed to create stream\n";
        pw_main_loop_destroy(loop_);
        loop_ = nullptr;
        is_running_.store(false);
        return;
    }

    uint8_t buffer[1024];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));

    struct spa_audio_info_raw info;
    std::memset(&info, 0, sizeof(info));
    info.format = SPA_AUDIO_FORMAT_F32;
    info.channels = 1;
    info.rate = 48000;
    info.position[0] = SPA_AUDIO_CHANNEL_MONO;

    const struct spa_pod *params[1];
    params[0] = spa_format_audio_raw_build(&b, SPA_PARAM_EnumFormat, &info);

    int res = pw_stream_connect(
        stream_,
        PW_DIRECTION_OUTPUT,
        PW_ID_ANY,
        static_cast<enum pw_stream_flags>(
            PW_STREAM_FLAG_AUTOCONNECT |
            PW_STREAM_FLAG_MAP_BUFFERS |
            PW_STREAM_FLAG_RT_PROCESS
        ),
        params, 1
    );

    if (res < 0) {
        std::cerr << "[PipeWireMic] Failed to connect stream: " << res << "\n";
        pw_stream_destroy(stream_);
        stream_ = nullptr;
        pw_main_loop_destroy(loop_);
        loop_ = nullptr;
        is_running_.store(false);
        return;
    }

    std::cout << "[PipeWireMic] Registered 'Zenith Wireless Microphone' in system audio devices.\n";
    std::cout << "[PipeWireMic] RNNoise noise suppression: "
              << (noise_suppressor_.available() ? "ON" : "OFF")
              << "\n" << std::flush;
    pw_main_loop_run(loop_);

    if (stream_) {
        pw_stream_destroy(stream_);
        stream_ = nullptr;
    }
    if (loop_) {
        pw_main_loop_destroy(loop_);
        loop_ = nullptr;
    }
}

void PipeWireMicSink::push_audio_frame(const uint8_t *data, size_t size, bool is_pcm,
                                       uint32_t seq, uint64_t timestamp_us, bool discontinuity) {
    if (!data || size == 0) return;
    (void)timestamp_us;

    if (jitter_buffer_.will_restart(seq, discontinuity)) {
        noise_suppressor_.reset();
    }

    if (is_pcm) {
        // Late/duplicate packets are discarded by the jitter buffer. Let it
        // account for them without feeding out-of-order audio to RNNoise.
        if (!jitter_buffer_.accepts(seq, discontinuity)) {
            jitter_buffer_.push_pcm(data, size, seq, discontinuity);
            return;
        }

        size_t samples = size / sizeof(int16_t);
        if (samples > 2048) samples = 2048;
        float converted[2048];
        for (size_t i = 0; i < samples; ++i) {
            int16_t sample = 0;
            std::memcpy(&sample, data + i * sizeof(int16_t), sizeof(sample));
            converted[i] = static_cast<float>(sample) / 32768.0f;
        }
        noise_suppressor_.process(converted, samples);
        jitter_buffer_.push_float(converted, samples, seq, discontinuity);
    } else {
        if (!opus_decoder_) return;
        float decoded[960 * 2]; // up to 40 ms at 48 kHz
        int samples = opus_decode_float(opus_decoder_, data, size, decoded, 960 * 2, 0);
        if (samples > 0) {
            if (jitter_buffer_.accepts(seq, discontinuity)) {
                noise_suppressor_.process(decoded, static_cast<size_t>(samples));
            }
            jitter_buffer_.push_float(decoded, static_cast<size_t>(samples), seq, discontinuity);
        }
    }
}

MicJitterStats PipeWireMicSink::get_stats() const {
    MicJitterStats stats = jitter_buffer_.get_stats();
    stats.denoised_frames = noise_suppressor_.processed_frames();
    return stats;
}

void PipeWireMicSink::set_noise_suppression_percent(int percent) {
    int clamped = std::clamp(percent, 0, 100);
    noise_suppressor_.set_wet_mix(static_cast<float>(clamped) / 100.0f);
    std::cout << "[PipeWireMic] Denoiser mix set to " << clamped << "%\n" << std::flush;
}

void PipeWireMicSink::process_audio() {
    if (!stream_) return;

    struct pw_buffer *b = pw_stream_dequeue_buffer(stream_);
    if (!b) return;

    struct spa_buffer *buf = b->buffer;
    float *dst = static_cast<float*>(buf->datas[0].data);
    if (!dst) {
        pw_stream_queue_buffer(stream_, b);
        return;
    }

    // The graph quantum is decided by the consumer and can change at runtime
    // (256, 480, 1024 samples...). Trust pw_buffer::requested instead of
    // assuming a 10 ms block.
    uint32_t req_samples = 480;
    if (b->requested > 0) {
        req_samples = static_cast<uint32_t>(b->requested);
    }
    if (buf->datas[0].maxsize >= sizeof(float)) {
        req_samples = std::min<uint32_t>(req_samples, buf->datas[0].maxsize / sizeof(float));
    }
    if (req_samples == 0) {
        buf->datas[0].chunk->offset = 0;
        buf->datas[0].chunk->stride = sizeof(float);
        buf->datas[0].chunk->size = 0;
        pw_stream_queue_buffer(stream_, b);
        return;
    }

    // pull() always fills the complete request with PCM, concealment, or
    // silence, so the virtual source never exposes a hole in the timeline.
    jitter_buffer_.pull(dst, req_samples);

    buf->datas[0].chunk->offset = 0;
    buf->datas[0].chunk->stride = sizeof(float);
    buf->datas[0].chunk->size = req_samples * sizeof(float);

    pw_stream_queue_buffer(stream_, b);
}

} // namespace zenith
