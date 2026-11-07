#pragma once

#include <cstdint>
#include <cstring>

#if defined(_WIN32)
#include <winsock2.h>
#include <stdlib.h>
#define htobe64_compat(x) _byteswap_uint64(x)
#define be64toh_compat(x) _byteswap_uint64(x)
#else
#include <arpa/inet.h>
#if defined(__linux__)
#include <endian.h>
#define htobe64_compat(x) htobe64(x)
#define be64toh_compat(x) be64toh(x)
#else
#define htobe64_compat(x) (x)
#define be64toh_compat(x) (x)
#endif
#endif

namespace zap {

constexpr uint16_t MAGIC = 0x5A41; // 'Z', 'A'
constexpr uint8_t  VERSION = 1;
constexpr uint16_t DEFAULT_PORT = 59100;

enum PacketType : uint8_t {
    PKT_AUDIO_FRAME      = 0x01,
    PKT_PING             = 0x02,
    PKT_PONG             = 0x03,
    PKT_CLIENT_FEEDBACK  = 0x04,
    PKT_SERVER_ANNOUNCE  = 0x05,
    PKT_CONTROL_REQ      = 0x06,
    PKT_DISCOVERY_BEACON = 0x07,
    PKT_DISCOVERY_PROBE  = 0x08,
    PKT_MIC_AUDIO_FRAME  = 0x09
};

enum CodecType : uint8_t {
    CODEC_PCM16 = 0x00,
    CODEC_OPUS  = 0x01
};

enum PacketFlags : uint16_t {
    FLAG_NONE          = 0x0000,
    FLAG_FEC_PRESENT   = 0x0001,
    FLAG_DISCONTINUITY = 0x0002,
    FLAG_MIC_PCM       = 0x0004
};

enum ControlCmd : uint8_t {
    CMD_SET_BITRATE = 0x01,
    CMD_RESYNC      = 0x02,
    CMD_PAUSE       = 0x03,
    CMD_RESUME      = 0x04,
    CMD_SET_VOLUME  = 0x05,
    CMD_MIC_STATE   = 0x06,
    CMD_SET_PC_MUTE = 0x07  // param16: 1 = mute PC speakers, 0 = unmute
};

#pragma pack(push, 1)

struct Header {
    uint16_t magic;         // 0x5A41
    uint8_t  version;       // 1
    uint8_t  type;          // PacketType
    uint32_t seq_num;       // Monotonic sequence number
    uint64_t timestamp_us;  // Monotonic time in microseconds
    uint16_t payload_size;  // Byte size of payload following header
    uint16_t flags;         // PacketFlags

    void to_network() {
        magic = htons(magic);
        seq_num = htonl(seq_num);
        timestamp_us = htobe64_compat(timestamp_us);
        payload_size = htons(payload_size);
        flags = htons(flags);
    }

    void to_host() {
        magic = ntohs(magic);
        seq_num = ntohl(seq_num);
        timestamp_us = be64toh_compat(timestamp_us);
        payload_size = ntohs(payload_size);
        flags = ntohs(flags);
    }
};

struct PingPayload {
    uint64_t client_timestamp_us;
    uint64_t server_timestamp_us;

    void to_network() {
        client_timestamp_us = htobe64_compat(client_timestamp_us);
        server_timestamp_us = htobe64_compat(server_timestamp_us);
    }
    void to_host() {
        client_timestamp_us = be64toh_compat(client_timestamp_us);
        server_timestamp_us = be64toh_compat(server_timestamp_us);
    }
};

struct FeedbackPayload {
    uint32_t last_seq_received;
    uint32_t total_packets_received;
    uint32_t total_packets_lost;
    uint16_t loss_fraction_percent; // 250 = 2.50%
    uint16_t jitter_us;             // RFC 3550 jitter in microseconds
    uint16_t buffer_delay_ms;       // Jitter buffer queue delay in ms
    uint16_t rtt_ms;                // Measured round-trip time in ms

    void to_network() {
        last_seq_received = htonl(last_seq_received);
        total_packets_received = htonl(total_packets_received);
        total_packets_lost = htonl(total_packets_lost);
        loss_fraction_percent = htons(loss_fraction_percent);
        jitter_us = htons(jitter_us);
        buffer_delay_ms = htons(buffer_delay_ms);
        rtt_ms = htons(rtt_ms);
    }

    void to_host() {
        last_seq_received = ntohl(last_seq_received);
        total_packets_received = ntohl(total_packets_received);
        total_packets_lost = ntohl(total_packets_lost);
        loss_fraction_percent = ntohs(loss_fraction_percent);
        jitter_us = ntohs(jitter_us);
        buffer_delay_ms = ntohs(buffer_delay_ms);
        rtt_ms = ntohs(rtt_ms);
    }
};

struct AnnouncePayload {
    uint32_t sample_rate;    // 48000
    uint16_t channels;       // 2
    uint16_t frame_samples;  // 480 (10ms)
    uint32_t bitrate_bps;    // 320000
    uint8_t  codec_type;     // CODEC_OPUS = 1
    uint8_t  reserved[7];

    void to_network() {
        sample_rate = htonl(sample_rate);
        channels = htons(channels);
        frame_samples = htons(frame_samples);
        bitrate_bps = htonl(bitrate_bps);
    }

    void to_host() {
        sample_rate = ntohl(sample_rate);
        channels = ntohs(channels);
        frame_samples = ntohs(frame_samples);
        bitrate_bps = ntohl(bitrate_bps);
    }
};

struct ControlPayload {
    uint8_t  command;        // ControlCmd
    uint8_t  reserved;
    uint16_t param16;
    uint32_t target_bitrate; // e.g. 320000

    void to_network() {
        param16 = htons(param16);
        target_bitrate = htonl(target_bitrate);
    }

    void to_host() {
        param16 = ntohs(param16);
        target_bitrate = ntohl(target_bitrate);
    }
};

struct DiscoveryPayload {
    char     server_name[32]; // Null-terminated hostname
    uint16_t port;            // 59100
    uint16_t version;         // 1
    uint32_t sample_rate;     // 48000
    uint16_t channels;        // 2
    uint16_t current_bitrate_kbps; // 320

    void to_network() {
        port = htons(port);
        version = htons(version);
        sample_rate = htonl(sample_rate);
        channels = htons(channels);
        current_bitrate_kbps = htons(current_bitrate_kbps);
    }

    void to_host() {
        port = ntohs(port);
        version = ntohs(version);
        sample_rate = ntohl(sample_rate);
        channels = ntohs(channels);
        current_bitrate_kbps = ntohs(current_bitrate_kbps);
    }
};

#pragma pack(pop)

static_assert(sizeof(Header) == 20, "Header must be exactly 20 bytes");
static_assert(sizeof(PingPayload) == 16, "PingPayload must be 16 bytes");
static_assert(sizeof(FeedbackPayload) == 20, "FeedbackPayload must be 20 bytes");
static_assert(sizeof(AnnouncePayload) == 20, "AnnouncePayload must be 20 bytes");
static_assert(sizeof(ControlPayload) == 8, "ControlPayload must be 8 bytes");
static_assert(sizeof(DiscoveryPayload) == 44, "DiscoveryPayload must be 44 bytes");

} // namespace zap
