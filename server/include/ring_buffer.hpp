#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <algorithm>

namespace zenith {

template <typename T, size_t Capacity>
class SpscRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

public:
    SpscRingBuffer() : write_idx_(0), read_idx_(0) {}

    // Called only by Producer (PipeWire RT thread)
    size_t write(const T *data, size_t count) {
        const size_t current_read = read_idx_.load(std::memory_order_acquire);
        const size_t current_write = write_idx_.load(std::memory_order_relaxed);
        const size_t available = Capacity - (current_write - current_read);

        const size_t to_write = std::min(count, available);
        if (to_write == 0) {
            return 0;
        }

        const size_t mask = Capacity - 1;
        const size_t idx = current_write & mask;
        const size_t first_chunk = std::min(to_write, Capacity - idx);

        std::memcpy(&buffer_[idx], data, first_chunk * sizeof(T));
        if (to_write > first_chunk) {
            std::memcpy(&buffer_[0], data + first_chunk, (to_write - first_chunk) * sizeof(T));
        }

        write_idx_.store(current_write + to_write, std::memory_order_release);
        return to_write;
    }

    // Called only by Consumer (Encoder thread)
    size_t read(T *data, size_t count) {
        const size_t current_write = write_idx_.load(std::memory_order_acquire);
        const size_t current_read = read_idx_.load(std::memory_order_relaxed);
        const size_t available = current_write - current_read;

        const size_t to_read = std::min(count, available);
        if (to_read == 0) {
            return 0;
        }

        const size_t mask = Capacity - 1;
        const size_t idx = current_read & mask;
        const size_t first_chunk = std::min(to_read, Capacity - idx);

        std::memcpy(data, &buffer_[idx], first_chunk * sizeof(T));
        if (to_read > first_chunk) {
            std::memcpy(data + first_chunk, &buffer_[0], (to_read - first_chunk) * sizeof(T));
        }

        read_idx_.store(current_read + to_read, std::memory_order_release);
        return to_read;
    }

    // Called only by Consumer. Advances the read pointer without copying,
    // used by the microphone jitter buffer to discard stale audio after a
    // network burst and keep the end-to-end delay bounded.
    size_t skip(size_t count) {
        const size_t current_write = write_idx_.load(std::memory_order_acquire);
        const size_t current_read = read_idx_.load(std::memory_order_relaxed);
        const size_t available = current_write - current_read;
        const size_t to_skip = std::min(count, available);
        if (to_skip == 0) {
            return 0;
        }
        read_idx_.store(current_read + to_skip, std::memory_order_release);
        return to_skip;
    }

    size_t available_to_read() const {
        const size_t current_write = write_idx_.load(std::memory_order_acquire);
        const size_t current_read = read_idx_.load(std::memory_order_relaxed);
        return current_write - current_read;
    }

    void reset() {
        write_idx_.store(0, std::memory_order_relaxed);
        read_idx_.store(0, std::memory_order_relaxed);
    }

private:
    alignas(64) T buffer_[Capacity];
    alignas(64) std::atomic<size_t> write_idx_;
    alignas(64) std::atomic<size_t> read_idx_;
};

} // namespace zenith
