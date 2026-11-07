#pragma once

#include <cstdint>
#include <cstddef>
#include <functional>
#include <string>

namespace zenith {

struct AudioFormat {
    uint32_t sample_rate = 48000;
    uint16_t channels = 2;
    uint16_t frame_samples = 480; // 10ms at 48kHz
};

struct TransportStats {
    uint64_t packets_sent = 0;
    uint64_t bytes_sent = 0;
    uint32_t active_clients = 0;
    uint32_t client_reported_loss_pct = 0; // scaled by 100 (e.g. 150 = 1.50%)
    uint32_t client_reported_jitter_us = 0;
    uint32_t client_reported_buffer_ms = 0;
    uint32_t client_reported_rtt_ms = 0;
};

using AudioSampleCallback = std::function<void(const float *samples, size_t frame_count, uint64_t capture_timestamp_us)>;
using FeedbackCallback = std::function<void(uint32_t loss_pct_scaled, uint32_t jitter_us, uint32_t rtt_ms)>;
using ControlCallback = std::function<void(uint8_t command, uint32_t param)>;
using MicFrameCallback = std::function<void(const uint8_t *data, size_t size, bool is_pcm,
                                             uint32_t seq, uint64_t timestamp_us,
                                             bool discontinuity)>;

class IAudioSource {
public:
    virtual ~IAudioSource() = default;
    virtual bool start(AudioSampleCallback callback) = 0;
    virtual void stop() = 0;
    virtual AudioFormat get_format() const = 0;
    virtual bool is_active() const = 0;
};

class IAudioEncoder {
public:
    virtual ~IAudioEncoder() = default;
    virtual bool init(const AudioFormat &format, int target_bitrate_bps) = 0;
    virtual int encode(const float *pcm_interleaved, int frame_samples, uint8_t *out_buffer, int max_bytes) = 0;
    virtual void set_bitrate(int bitrate_bps) = 0;
    virtual int get_bitrate() const = 0;
    virtual void set_packet_loss_percent(int loss_perc) = 0;
    virtual int get_frame_samples() const = 0;
    virtual const char* get_name() const = 0;
};

class IAudioTransport {
public:
    virtual ~IAudioTransport() = default;
    virtual bool start(uint16_t port) = 0;
    virtual void stop() = 0;
    virtual void broadcast_audio(const uint8_t *payload, size_t size, uint64_t timestamp_us, bool fec) = 0;
    virtual void send_announce(const AudioFormat &format, int bitrate) = 0;
    virtual void set_feedback_callback(FeedbackCallback cb) = 0;
    virtual void set_control_callback(ControlCallback cb) = 0;
    virtual void set_mic_frame_callback(MicFrameCallback cb) { (void)cb; }
    virtual TransportStats get_stats() const = 0;
    virtual bool is_running() const = 0;
};

} // namespace zenith
