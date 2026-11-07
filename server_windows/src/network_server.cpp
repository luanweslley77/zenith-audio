#include "network_server.hpp"
#include <iostream>
#include <chrono>

namespace zenith {

static uint64_t get_now_us() {
    auto now = std::chrono::steady_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count();
}

NetworkServer::NetworkServer() = default;

NetworkServer::~NetworkServer() {
    stop();
}

bool NetworkServer::start(uint16_t port) {
    if (is_running_.load()) return false;
    port_ = port;

    WSADATA wsaData;
    int wsaRes = WSAStartup(MAKEWORD(2, 2), &wsaData);
    if (wsaRes != 0) {
        std::cerr << "[Network] WSAStartup failed: " << wsaRes << "\n";
        return false;
    }

    sockfd_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sockfd_ == INVALID_SOCKET) {
        std::cerr << "[Network] Failed to create UDP socket: " << WSAGetLastError() << "\n";
        WSACleanup();
        return false;
    }

    BOOL opt = TRUE;
    setsockopt(sockfd_, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&opt), sizeof(opt));

    int sndbuf = 256 * 1024;
    setsockopt(sockfd_, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&sndbuf), sizeof(sndbuf));
    int rcvbuf = 256 * 1024;
    setsockopt(sockfd_, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&rcvbuf), sizeof(rcvbuf));

    BOOL bcast = TRUE;
    setsockopt(sockfd_, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&bcast), sizeof(bcast));

    DWORD timeout_ms = 1000;
    setsockopt(sockfd_, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout_ms), sizeof(timeout_ms));

    struct sockaddr_in serv_addr = {};
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_addr.s_addr = INADDR_ANY;
    serv_addr.sin_port = htons(port_);

    if (bind(sockfd_, reinterpret_cast<struct sockaddr*>(&serv_addr), sizeof(serv_addr)) == SOCKET_ERROR) {
        std::cerr << "[Network] Socket bind failed: " << WSAGetLastError() << "\n";
        closesocket(sockfd_);
        sockfd_ = INVALID_SOCKET;
        WSACleanup();
        return false;
    }

    is_running_.store(true);
    rx_thread_ = std::thread(&NetworkServer::rx_thread_main, this);

    std::cout << "[Network] Zenith Windows 11 UDP server listening on port " << port_ << "\n";
    return true;
}

void NetworkServer::stop() {
    if (!is_running_.load()) return;
    is_running_.store(false);

    if (sockfd_ != INVALID_SOCKET) {
        closesocket(sockfd_);
        sockfd_ = INVALID_SOCKET;
    }

    if (rx_thread_.joinable()) {
        rx_thread_.join();
    }

    WSACleanup();
    std::cout << "[Network] Zenith Windows 11 server stopped.\n";
}

void NetworkServer::broadcast_audio(const uint8_t *payload, size_t size, uint64_t timestamp_us, bool fec) {
    if (!is_running_.load() || sockfd_ == INVALID_SOCKET) return;

    std::vector<struct sockaddr_in> target_addrs;
    {
        std::lock_guard<std::mutex> lock(clients_mutex_);
        if (clients_.empty()) return;
        target_addrs.reserve(clients_.size());
        for (const auto &c : clients_) {
            target_addrs.push_back(c.addr);
        }
    }

    uint32_t seq = current_seq_.fetch_add(1, std::memory_order_relaxed);

    uint8_t packet_buffer[2048];
    if (sizeof(zap::Header) + size > sizeof(packet_buffer)) {
        return;
    }

    zap::Header hdr;
    hdr.magic = zap::MAGIC;
    hdr.version = zap::VERSION;
    hdr.type = zap::PKT_AUDIO_FRAME;
    hdr.seq_num = seq;
    hdr.timestamp_us = timestamp_us;
    hdr.payload_size = static_cast<uint16_t>(size);
    hdr.flags = fec ? zap::FLAG_FEC_PRESENT : zap::FLAG_NONE;

    zap::Header net_hdr = hdr;
    net_hdr.to_network();

    std::memcpy(packet_buffer, &net_hdr, sizeof(net_hdr));
    std::memcpy(packet_buffer + sizeof(net_hdr), payload, size);
    size_t total_packet_size = sizeof(net_hdr) + size;

    for (const auto &addr : target_addrs) {
        int sent = sendto(sockfd_, reinterpret_cast<const char*>(packet_buffer),
                          static_cast<int>(total_packet_size), 0,
                          reinterpret_cast<const struct sockaddr*>(&addr), sizeof(addr));
        if (sent > 0) {
            packets_sent_.fetch_add(1, std::memory_order_relaxed);
            bytes_sent_.fetch_add(sent, std::memory_order_relaxed);
        }
    }
}

void NetworkServer::rx_thread_main() {
    uint8_t rx_buf[2048];
    struct sockaddr_in src_addr = {};
    int addr_len = sizeof(src_addr);
    uint64_t last_bcast_us = 0;

    while (is_running_.load()) {
        uint64_t now_us = get_now_us();
        if (now_us - last_bcast_us > 2'000'000ULL) {
            broadcast_discovery_beacon();
            last_bcast_us = now_us;
        }

        addr_len = sizeof(src_addr);
        int n = recvfrom(sockfd_, reinterpret_cast<char*>(rx_buf), sizeof(rx_buf), 0,
                         reinterpret_cast<struct sockaddr*>(&src_addr), &addr_len);
        if (n == SOCKET_ERROR) {
            if (!is_running_.load()) break;
            continue;
        }

        if (n >= static_cast<int>(sizeof(zap::Header))) {
            handle_incoming_packet(rx_buf, static_cast<size_t>(n), src_addr);
        }
    }
}

void NetworkServer::update_or_add_client(const struct sockaddr_in &addr) {
    uint64_t now_us = get_now_us();
    std::lock_guard<std::mutex> lock(clients_mutex_);

    for (auto &c : clients_) {
        if (c.addr.sin_addr.s_addr == addr.sin_addr.s_addr &&
            c.addr.sin_port == addr.sin_port) {
            c.last_seen_us = now_us;
            return;
        }
    }

    ClientEndpoint new_client;
    new_client.addr = addr;
    new_client.last_seen_us = now_us;
    clients_.push_back(new_client);

    char ip_str[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, const_cast<IN_ADDR*>(&addr.sin_addr), ip_str, sizeof(ip_str));
    std::cout << "[Network] Registered Android client: " << ip_str << ":" << ntohs(addr.sin_port)
              << " (Active clients: " << clients_.size() << ")\n";
}

void NetworkServer::cleanup_stale_clients() {
    uint64_t now_us = get_now_us();
    constexpr uint64_t TIMEOUT_US = 8'000'000;

    std::lock_guard<std::mutex> lock(clients_mutex_);
    for (auto it = clients_.begin(); it != clients_.end();) {
        if (now_us - it->last_seen_us > TIMEOUT_US) {
            char ip_str[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, const_cast<IN_ADDR*>(&it->addr.sin_addr), ip_str, sizeof(ip_str));
            std::cout << "[Network] Client disconnected: " << ip_str << ":" << ntohs(it->addr.sin_port) << "\n";
            it = clients_.erase(it);
        } else {
            ++it;
        }
    }
}

void NetworkServer::handle_incoming_packet(const uint8_t *buffer, size_t size,
                                           const struct sockaddr_in &src_addr) {
    zap::Header hdr;
    std::memcpy(&hdr, buffer, sizeof(hdr));
    hdr.to_host();

    if (hdr.magic != zap::MAGIC || hdr.version != zap::VERSION) {
        return;
    }

    update_or_add_client(src_addr);
    cleanup_stale_clients();

    const uint8_t *payload = buffer + sizeof(zap::Header);
    size_t payload_len = size - sizeof(zap::Header);

    switch (hdr.type) {
        case zap::PKT_PING: {
            if (payload_len >= sizeof(zap::PingPayload)) {
                zap::PingPayload ping;
                std::memcpy(&ping, payload, sizeof(ping));
                ping.to_host();

                zap::Header pong_hdr;
                pong_hdr.magic = zap::MAGIC;
                pong_hdr.version = zap::VERSION;
                pong_hdr.type = zap::PKT_PONG;
                pong_hdr.seq_num = hdr.seq_num;
                pong_hdr.timestamp_us = get_now_us();
                pong_hdr.payload_size = sizeof(zap::PingPayload);
                pong_hdr.flags = zap::FLAG_NONE;

                zap::PingPayload pong_payload;
                pong_payload.client_timestamp_us = ping.client_timestamp_us;
                pong_payload.server_timestamp_us = pong_hdr.timestamp_us;

                uint8_t pong_buf[sizeof(zap::Header) + sizeof(zap::PingPayload)];
                pong_hdr.to_network();
                pong_payload.to_network();

                std::memcpy(pong_buf, &pong_hdr, sizeof(pong_hdr));
                std::memcpy(pong_buf + sizeof(pong_hdr), &pong_payload, sizeof(pong_payload));

                sendto(sockfd_, reinterpret_cast<const char*>(pong_buf), sizeof(pong_buf), 0,
                       reinterpret_cast<const struct sockaddr*>(&src_addr), sizeof(src_addr));
            }
            break;
        }

        case zap::PKT_CLIENT_FEEDBACK: {
            if (payload_len >= sizeof(zap::FeedbackPayload)) {
                zap::FeedbackPayload fb;
                std::memcpy(&fb, payload, sizeof(fb));
                fb.to_host();

                {
                    std::lock_guard<std::mutex> lock(clients_mutex_);
                    for (auto &c : clients_) {
                        if (c.addr.sin_addr.s_addr == src_addr.sin_addr.s_addr &&
                            c.addr.sin_port == src_addr.sin_port) {
                            c.last_seq_received = fb.last_seq_received;
                            c.packets_lost = fb.total_packets_lost;
                            c.loss_pct_scaled = fb.loss_fraction_percent;
                            c.jitter_us = fb.jitter_us;
                            c.buffer_delay_ms = fb.buffer_delay_ms;
                            c.rtt_ms = fb.rtt_ms;
                            break;
                        }
                    }
                }

                if (feedback_cb_) {
                    feedback_cb_(fb.loss_fraction_percent, fb.jitter_us, fb.rtt_ms);
                }
            }
            break;
        }

        case zap::PKT_DISCOVERY_PROBE: {
            send_discovery_beacon(src_addr);
            break;
        }

        case zap::PKT_CONTROL_REQ: {
            if (payload_len >= sizeof(zap::ControlPayload)) {
                zap::ControlPayload ctrl;
                std::memcpy(&ctrl, payload, sizeof(ctrl));
                ctrl.to_host();

                if (control_cb_) {
                    control_cb_(ctrl.command, ctrl.target_bitrate);
                }
            }
            break;
        }

        case zap::PKT_MIC_AUDIO_FRAME: {
            bool is_pcm = (hdr.flags & zap::FLAG_MIC_PCM) != 0;
            bool discontinuity = (hdr.flags & zap::FLAG_DISCONTINUITY) != 0;
            if (mic_cb_ && payload_len > 0) {
                mic_cb_(payload, payload_len, is_pcm, hdr.seq_num,
                        hdr.timestamp_us, discontinuity);
            }
            break;
        }

        default:
            break;
    }
}

void NetworkServer::send_discovery_beacon(const struct sockaddr_in &dest_addr) {
    if (sockfd_ == INVALID_SOCKET) return;

    zap::Header beacon_hdr;
    beacon_hdr.magic = zap::MAGIC;
    beacon_hdr.version = zap::VERSION;
    beacon_hdr.type = zap::PKT_DISCOVERY_BEACON;
    beacon_hdr.seq_num = 0;
    beacon_hdr.timestamp_us = get_now_us();
    beacon_hdr.payload_size = sizeof(zap::DiscoveryPayload);
    beacon_hdr.flags = zap::FLAG_NONE;

    zap::DiscoveryPayload beacon_payload;
    std::memset(&beacon_payload, 0, sizeof(beacon_payload));

    char host[32] = {0};
    DWORD host_len = sizeof(host);
    GetComputerNameA(host, &host_len);
    snprintf(beacon_payload.server_name, sizeof(beacon_payload.server_name), "WIN11 (%s)", host);

    beacon_payload.port = port_;
    beacon_payload.version = zap::VERSION;
    beacon_payload.sample_rate = current_format_.sample_rate > 0 ? current_format_.sample_rate : 48000;
    beacon_payload.channels = current_format_.channels > 0 ? current_format_.channels : 2;
    beacon_payload.current_bitrate_kbps = static_cast<uint16_t>(current_bitrate_ > 0 ? current_bitrate_ / 1000 : 320);

    uint8_t pkt_buf[sizeof(zap::Header) + sizeof(zap::DiscoveryPayload)];
    beacon_hdr.to_network();
    beacon_payload.to_network();
    std::memcpy(pkt_buf, &beacon_hdr, sizeof(beacon_hdr));
    std::memcpy(pkt_buf + sizeof(beacon_hdr), &beacon_payload, sizeof(beacon_payload));

    sendto(sockfd_, reinterpret_cast<const char*>(pkt_buf), sizeof(pkt_buf), 0,
           reinterpret_cast<const struct sockaddr*>(&dest_addr), sizeof(dest_addr));
}

void NetworkServer::broadcast_discovery_beacon() {
    if (sockfd_ == INVALID_SOCKET) return;

    // Broadcast on all active network interfaces via GetAdaptersAddresses
    ULONG outBufLen = 15000;
    std::vector<BYTE> buf(outBufLen);
    PIP_ADAPTER_ADDRESSES pAddresses = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data());

    ULONG flags = GAA_FLAG_INCLUDE_PREFIX | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST;
    ULONG dwRetVal = GetAdaptersAddresses(AF_INET, flags, nullptr, pAddresses, &outBufLen);
    if (dwRetVal == ERROR_BUFFER_OVERFLOW) {
        buf.resize(outBufLen);
        pAddresses = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data());
        dwRetVal = GetAdaptersAddresses(AF_INET, flags, nullptr, pAddresses, &outBufLen);
    }

    if (dwRetVal == NO_ERROR) {
        for (PIP_ADAPTER_ADDRESSES pCurr = pAddresses; pCurr != nullptr; pCurr = pCurr->Next) {
            if (pCurr->OperStatus != IfOperStatusUp || pCurr->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;

            for (PIP_ADAPTER_UNICAST_ADDRESS pUni = pCurr->FirstUnicastAddress; pUni != nullptr; pUni = pUni->Next) {
                if (pUni->Address.lpSockaddr->sa_family == AF_INET) {
                    struct sockaddr_in *sin = reinterpret_cast<struct sockaddr_in*>(pUni->Address.lpSockaddr);
                    // Compute subnet broadcast address
                    struct sockaddr_in bcast = *sin;
                    bcast.sin_port = htons(port_);
                    // Send to standard class-c broadcast fallback for the interface
                    bcast.sin_addr.s_addr |= htonl(0x000000FF);
                    send_discovery_beacon(bcast);
                }
            }
        }
    }

    // Global broadcast fallback
    struct sockaddr_in global_bcast = {};
    global_bcast.sin_family = AF_INET;
    global_bcast.sin_port = htons(port_);
    inet_pton(AF_INET, "255.255.255.255", &global_bcast.sin_addr);
    send_discovery_beacon(global_bcast);
}

TransportStats NetworkServer::get_stats() const {
    TransportStats stats;
    stats.packets_sent = packets_sent_.load(std::memory_order_relaxed);
    stats.bytes_sent = bytes_sent_.load(std::memory_order_relaxed);

    std::lock_guard<std::mutex> lock(const_cast<std::mutex&>(clients_mutex_));
    stats.active_clients = static_cast<uint32_t>(clients_.size());

    if (!clients_.empty()) {
        const auto &c = clients_.front();
        stats.client_reported_loss_pct = c.loss_pct_scaled;
        stats.client_reported_jitter_us = c.jitter_us;
        stats.client_reported_buffer_ms = c.buffer_delay_ms;
        stats.client_reported_rtt_ms = c.rtt_ms;
    }

    return stats;
}

size_t NetworkServer::get_active_client_count() const {
    std::lock_guard<std::mutex> lock(const_cast<std::mutex&>(clients_mutex_));
    return clients_.size();
}

void NetworkServer::send_announce(const AudioFormat &format, int bitrate) {
    current_format_ = format;
    current_bitrate_ = bitrate;
}

} // namespace zenith
