#include "audio_engine.hpp"
#include "pipewire_mic.hpp"
#include "../../protocol/protocol.hpp"
#include <iostream>
#include <chrono>
#include <iomanip>

namespace zenith {

AudioEngine::AudioEngine(std::unique_ptr<IAudioSource> source,
                         std::unique_ptr<IAudioEncoder> encoder,
                         std::unique_ptr<IAudioTransport> transport)
    : source_(std::move(source)),
      encoder_(std::move(encoder)),
      transport_(std::move(transport)) {
}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start(uint16_t port, int initial_bitrate_bps) {
    if (is_running_.load()) return false;

    format_ = source_->get_format();

    if (!encoder_->init(format_, initial_bitrate_bps)) {
        std::cerr << "[Engine] Failed to initialize audio encoder\n";
        return false;
    }

    if (!transport_->start(port)) {
        std::cerr << "[Engine] Failed to start network transport\n";
        return false;
    }

    // Connect feedback and control callbacks
    transport_->set_feedback_callback([this](uint32_t loss_pct_scaled, uint32_t jitter_us, uint32_t rtt_ms) {
        (void)jitter_us;
        (void)rtt_ms;
        int loss_pct = static_cast<int>(loss_pct_scaled / 100);
        encoder_->set_packet_loss_percent(loss_pct);
    });

    transport_->set_control_callback([this](uint8_t command, uint32_t param) {
        if (command == zap::CMD_SET_BITRATE) {
            std::cout << "[Engine] Client requested bitrate change to: " << (param / 1000) << " kbps\n";
            set_bitrate(static_cast<int>(param));
        }
    });

    is_running_.store(true);

    // Start PipeWire capture
    bool src_ok = source_->start([this](const float *samples, size_t frame_count, uint64_t timestamp_us) {
        last_capture_time_us_.store(timestamp_us, std::memory_order_relaxed);
        size_t float_count = frame_count * format_.channels;
        ring_buffer_.write(samples, float_count);
    });

    if (!src_ok) {
        std::cerr << "[Engine] Failed to start audio source capture\n";
        transport_->stop();
        is_running_.store(false);
        return false;
    }

    // Initialize the PipeWire virtual microphone before the stats thread starts
    // so the thread always sees a stable sink pointer.
    mic_sink_ = std::make_unique<PipeWireMicSink>();
    if (mic_sink_->start()) {
        transport_->set_mic_frame_callback(
            [this](const uint8_t *data, size_t size, bool is_pcm,
                   uint32_t seq, uint64_t timestamp_us, bool discontinuity) {
                if (mic_sink_) {
                    mic_sink_->push_audio_frame(data, size, is_pcm, seq,
                                               timestamp_us, discontinuity);
                }
            });
    }

    worker_thread_ = std::thread(&AudioEngine::audio_worker_thread, this);
    stats_thread_ = std::thread(&AudioEngine::stats_worker_thread, this);

    std::cout << "[Engine] Zenith Audio Engine started successfully at "
              << (initial_bitrate_bps / 1000) << " kbps\n";
    return true;
}

void AudioEngine::stop() {
    if (!is_running_.load()) return;

    is_running_.store(false);

    // Stop the producers first: once the transport receive thread is joined,
    // no new microphone frames can reach the sink. Join the stats thread
    // before destroying the sink it reads.
    if (source_) {
        source_->stop();
    }
    if (transport_) {
        transport_->stop();
    }

    if (worker_thread_.joinable()) {
        worker_thread_.join();
    }
    if (stats_thread_.joinable()) {
        stats_thread_.join();
    }

    if (mic_sink_) {
        mic_sink_->stop();
        mic_sink_.reset();
    }

    std::cout << "[Engine] Zenith Audio Engine stopped.\n";
}

void AudioEngine::set_bitrate(int bitrate_bps) {
    if (encoder_) {
        encoder_->set_bitrate(bitrate_bps);
        if (transport_) {
            transport_->send_announce(format_, bitrate_bps);
        }
    }
}

int AudioEngine::get_bitrate() const {
    return encoder_ ? encoder_->get_bitrate() : 320000;
}

TransportStats AudioEngine::get_transport_stats() const {
    return transport_ ? transport_->get_stats() : TransportStats{};
}

void AudioEngine::audio_worker_thread() {
    const size_t frame_samples = format_.frame_samples;
    const size_t total_floats = frame_samples * format_.channels;
    std::vector<float> pcm_buf(total_floats);
    std::vector<uint8_t> encoded_buf(2048);

    uint64_t audio_time_us = 0;
    const uint64_t frame_duration_us = (frame_samples * 1000000ULL) / format_.sample_rate;

    while (is_running_.load()) {
        size_t available = ring_buffer_.available_to_read();

        // If ring buffer has accumulated excessive backlog (> 500ms),
        // drain down to prevent latency run-away after suspend/resume.
        constexpr size_t MAX_BACKLOG = 480 * 2 * 50; // 500ms
        if (available > MAX_BACKLOG) {
            size_t to_discard = available - (total_floats * 2);
            std::vector<float> discard_buf(to_discard);
            ring_buffer_.read(discard_buf.data(), to_discard);
            audio_time_us = 0;
        }

        bool encoded_any = false;
        while (ring_buffer_.available_to_read() >= total_floats) {
            size_t n_read = ring_buffer_.read(pcm_buf.data(), total_floats);
            if (n_read == total_floats) {
                auto now = std::chrono::steady_clock::now();
                uint64_t now_us = std::chrono::duration_cast<std::chrono::microseconds>(
                    now.time_since_epoch()
                ).count();

                if (audio_time_us == 0 ||
                    now_us > audio_time_us + 100000ULL ||
                    audio_time_us > now_us + 100000ULL) {
                    audio_time_us = now_us;
                }

                uint64_t ts_us = audio_time_us;
                audio_time_us += frame_duration_us;

                int encoded_bytes = encoder_->encode(
                    pcm_buf.data(),
                    static_cast<int>(frame_samples),
                    encoded_buf.data(),
                    static_cast<int>(encoded_buf.size())
                );

                if (encoded_bytes > 0) {
                    transport_->broadcast_audio(encoded_buf.data(), encoded_bytes, ts_us, false);
                    frames_encoded_.fetch_add(1, std::memory_order_relaxed);
                }
                encoded_any = true;
            }
        }

        if (!encoded_any) {
            std::this_thread::sleep_for(std::chrono::microseconds(500));
        }
    }
}

void AudioEngine::stats_worker_thread() {
    uint64_t last_bytes = 0;
    auto last_time = std::chrono::steady_clock::now();
    MicJitterStats prev_mic_stats{};

    while (is_running_.load()) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        if (!is_running_.load()) break;

        auto now = std::chrono::steady_clock::now();
        double elapsed_sec = std::chrono::duration<double>(now - last_time).count();
        last_time = now;

        TransportStats stats = transport_->get_stats();
        uint64_t delta_bytes = stats.bytes_sent - last_bytes;
        last_bytes = stats.bytes_sent;
        double throughput_kbps = (delta_bytes * 8.0 / 1000.0) / (elapsed_sec > 0 ? elapsed_sec : 1.0);

        if (stats.active_clients > 0) {
            std::cout << "[STATS] Bitrate: " << (get_bitrate() / 1000) << " kbps | "
                      << "Bandwidth: " << std::fixed << std::setprecision(1) << throughput_kbps << " kbps | "
                      << "Clients: " << stats.active_clients << " | "
                      << "Loss: " << std::setprecision(2) << (stats.client_reported_loss_pct / 100.0) << "% | "
                      << "Jitter: " << std::setprecision(1) << (stats.client_reported_jitter_us / 1000.0) << "ms | "
                      << "Buffer: " << stats.client_reported_buffer_ms << "ms | "
                      << "RTT: " << stats.client_reported_rtt_ms << "ms\n"
                      << std::flush;
        }

        if (mic_sink_) {
            MicJitterStats mic = mic_sink_->get_stats();
            uint64_t recv_delta = mic.packets_received - prev_mic_stats.packets_received;
            uint64_t lost_delta = mic.packets_lost - prev_mic_stats.packets_lost;
            uint64_t late_delta = mic.packets_late - prev_mic_stats.packets_late;
            uint64_t plc_delta = mic.plc_frames - prev_mic_stats.plc_frames;
            uint64_t underrun_delta = mic.underruns - prev_mic_stats.underruns;
            uint64_t overrun_delta = mic.overruns - prev_mic_stats.overruns;
            uint64_t skip_delta = mic.skipped_samples - prev_mic_stats.skipped_samples;
            prev_mic_stats = mic;

            if (recv_delta > 0) {
                std::cout << "[MIC] Recv: +" << recv_delta
                          << " | Lost: +" << lost_delta
                          << " | Late: +" << late_delta
                          << " | PLC: +" << plc_delta
                          << " | Underrun: +" << underrun_delta
                          << " | Overrun: +" << overrun_delta
                          << " | Skip: +" << (skip_delta / 48) << "ms"
                          << " | Buffer: " << mic.buffer_fill_ms << "ms\n"
                          << std::flush;
            }
        }
    }
}

} // namespace zenith
