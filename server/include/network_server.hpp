#pragma once

#include "audio_interfaces.hpp"
#include "../../protocol/protocol.hpp"
#include <netinet/in.h>
#include <atomic>
#include <thread>
#include <vector>
#include <mutex>
#include <unordered_map>

namespace zenith {

struct ClientEndpoint {
    struct sockaddr_in addr;
    uint64_t last_seen_us = 0;
    uint32_t last_seq_received = 0;
    uint32_t packets_lost = 0;
    uint16_t loss_pct_scaled = 0;
    uint16_t jitter_us = 0;
    uint16_t buffer_delay_ms = 0;
    uint16_t rtt_ms = 0;
    bool wants_audio = true;      // false when the client is in mic-only mode
    bool pc_muted_by_client = false; // true while this client muted the PC sink
};

class NetworkServer : public IAudioTransport {
public:
    NetworkServer();
    ~NetworkServer() override;

    bool start(uint16_t port) override;
    void stop() override;

    void broadcast_audio(const uint8_t *payload, size_t size, uint64_t timestamp_us, bool fec) override;
    void send_announce(const AudioFormat &format, int bitrate) override;

    void set_feedback_callback(FeedbackCallback cb) override { feedback_cb_ = std::move(cb); }
    void set_control_callback(ControlCallback cb) override { control_cb_ = std::move(cb); }
    void set_mic_frame_callback(MicFrameCallback cb) override { mic_cb_ = std::move(cb); }

    TransportStats get_stats() const override;
    bool is_running() const override { return is_running_.load(); }

private:
    void rx_thread_main();
    void handle_incoming_packet(const uint8_t *buffer, size_t size, const struct sockaddr_in &src_addr);
    void update_or_add_client(const struct sockaddr_in &addr);
    void cleanup_stale_clients();
    void send_discovery_beacon(const struct sockaddr_in &dest_addr);
    void broadcast_discovery_beacon();

    int sockfd_ = -1;
    uint16_t port_ = zap::DEFAULT_PORT;
    std::atomic<bool> is_running_{false};
    std::thread rx_thread_;

    std::atomic<uint32_t> current_seq_{0};
    std::atomic<uint64_t> packets_sent_{0};
    std::atomic<uint64_t> bytes_sent_{0};

    FeedbackCallback feedback_cb_;
    ControlCallback control_cb_;
    MicFrameCallback mic_cb_;

    std::mutex clients_mutex_;
    std::vector<ClientEndpoint> clients_;

    AudioFormat current_format_;
    int current_bitrate_ = 320000;
};

} // namespace zenith
