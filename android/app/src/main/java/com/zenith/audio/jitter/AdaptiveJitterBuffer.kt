package com.zenith.audio.jitter

import com.zenith.audio.protocol.ZapProtocol
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AdaptiveJitterBuffer(
    private val minDelayMs: Int = 30,
    private val maxDelayMs: Int = 80
) {
    // Packet storage sorted by sequence number
    private val lock = Any()
    private val packetMap = TreeMap<Long, PooledPacket>()

    private var nextExpectedSeq: Long = -1L
    private val packetsReceivedCount = AtomicLong(0)
    private val packetsLostCount = AtomicLong(0)

    // RFC 3550 jitter estimator (in microseconds)
    private var estimatedJitterUs: Double = 0.0
    private var lastTransitTimeUs: Long = 0L
    private var hasPrevPacket: Boolean = false

    private var targetDelayMs: Double = 5.0
    private var isGamingMode: Boolean = true
    private var isPrebuffered = false
    private var missingSeqSinceMs: Long = 0L
    private var emptySinceMs: Long = 0L

    fun setGamingMode(enabled: Boolean) {
        synchronized(lock) {
            isGamingMode = enabled
            targetDelayMs = if (enabled) 5.0 else 35.0
            isPrebuffered = false
        }
    }

    // Packet memory pool to eliminate GC allocations
    private val packetPool = ConcurrentLinkedQueue<PooledPacket>()

    class PooledPacket(
        var header: ZapProtocol.Header = ZapProtocol.Header(0, 0, 0, 0, 0, 0, 0),
        val payload: ByteArray = ByteArray(1500),
        var payloadSize: Int = 0,
        var receiveTimeUs: Long = 0L
    )

    fun obtainPacket(
        header: ZapProtocol.Header,
        data: ByteArray,
        offset: Int,
        size: Int,
        receiveTimeUs: Long
    ): PooledPacket {
        val pkt = packetPool.poll() ?: PooledPacket()
        pkt.header = header
        val copySize = min(size, pkt.payload.size)
        System.arraycopy(data, offset, pkt.payload, 0, copySize)
        pkt.payloadSize = copySize
        pkt.receiveTimeUs = receiveTimeUs
        return pkt
    }

    fun recyclePacket(pkt: PooledPacket) {
        if (packetPool.size < 64) {
            packetPool.offer(pkt)
        }
    }

    fun pushPacket(packet: PooledPacket) {
        val seq = packet.header.seqNum
        packetsReceivedCount.incrementAndGet()

        // Measure interarrival jitter (RFC 3550)
        val transitUs = packet.receiveTimeUs - packet.header.timestampUs
        if (hasPrevPacket) {
            val d = abs(transitUs - lastTransitTimeUs)
            estimatedJitterUs += (d.toDouble() - estimatedJitterUs) / 16.0
        } else {
            hasPrevPacket = true
        }
        lastTransitTimeUs = transitUs

        // Adapt target delay: aggressive 2ms for gaming mode, buffer-safe 30ms+ for media mode
        val jitterMs = estimatedJitterUs / 1000.0
        val computedTarget = if (isGamingMode) {
            max(2.0, min(15.0, jitterMs * 1.2 + 2.0))
        } else {
            max(minDelayMs.toDouble(), min(maxDelayMs.toDouble(), jitterMs * 2.0 + 20.0))
        }
        targetDelayMs = targetDelayMs * 0.98 + computedTarget * 0.02

        synchronized(lock) {
            if (nextExpectedSeq == -1L) {
                nextExpectedSeq = seq
            }

            // Discard packets older than what we already played out
            if (seq < nextExpectedSeq) {
                recyclePacket(packet)
                return
            }

            packetMap[seq] = packet
            emptySinceMs = 0L

            // If jitter buffer accumulated too large of a backlog (> 150ms),
            // drain the oldest packets down to maintain low latency.
            val maxBacklogFrames = if (isGamingMode) 4 else max(15, (targetDelayMs / 10.0 * 2.0).toInt())
            while (packetMap.size > maxBacklogFrames) {
                val oldestKey = packetMap.firstKey()
                val dropped = packetMap.remove(oldestKey)
                if (dropped != null) recyclePacket(dropped)
                nextExpectedSeq = oldestKey + 1
            }
        }
    }

    fun popNextPacket(): PopResult {
        val nowMs = System.currentTimeMillis()

        synchronized(lock) {
            // Target frames for smooth cushion (1 frame for gaming = 0.5ms-2ms, 3+ frames for media)
            val requiredCushion = if (isGamingMode) 1 else max(3, (targetDelayMs / 10.0).toInt())

            if (!isPrebuffered) {
                if (packetMap.size >= requiredCushion) {
                    isPrebuffered = true
                    nextExpectedSeq = packetMap.firstKey()
                } else {
                    return PopResult.Empty
                }
            }

            if (packetMap.isEmpty()) {
                if (emptySinceMs == 0L) {
                    emptySinceMs = nowMs
                } else if (nowMs - emptySinceMs > 200L) {
                    // Buffer has been empty for > 200ms: reset pre-buffering gate
                    isPrebuffered = false
                    nextExpectedSeq = -1L
                }
                return PopResult.Empty
            }

            // Discard stale packets that arrived late
            while (packetMap.isNotEmpty() && packetMap.firstKey() < nextExpectedSeq) {
                val stale = packetMap.remove(packetMap.firstKey())
                if (stale != null) recyclePacket(stale)
            }

            if (packetMap.isEmpty()) {
                return PopResult.Empty
            }

            // 1. Check if next expected packet is available
            val packet = packetMap.remove(nextExpectedSeq)
            if (packet != null) {
                nextExpectedSeq++
                missingSeqSinceMs = 0L
                return PopResult.Packet(packet)
            }

            // 2. Next expected packet is missing.
            val firstAvailable = packetMap.firstKey()
            if (firstAvailable > nextExpectedSeq) {
                if (missingSeqSinceMs == 0L) {
                    missingSeqSinceMs = nowMs
                }

                val waitTimeMs = nowMs - missingSeqSinceMs
                // Gaming mode must not wait the media-mode reorder window;
                // one lost packet would otherwise cost several target frames.
                val reorderWaitMs = if (isGamingMode) 8L else 35L
                val maxReorderFrames = if (isGamingMode) 4 else 8
                if (waitTimeMs < reorderWaitMs && packetMap.size < maxReorderFrames) {
                    return PopResult.WaitingForReorder
                }

                // Packet is genuinely lost after waiting
                val lostSeq = nextExpectedSeq
                packetsLostCount.incrementAndGet()
                nextExpectedSeq++
                missingSeqSinceMs = 0L
                return PopResult.LostPacket(lostSeq)
            }

            return PopResult.Empty
        }
    }

    fun getJitterMs(): Double = estimatedJitterUs / 1000.0

    fun getCurrentBufferDelayMs(): Double {
        synchronized(lock) {
            return (packetMap.size * 10.0)
        }
    }

    fun getTargetBufferDelayMs(): Double {
        synchronized(lock) {
            return targetDelayMs
        }
    }

    fun getPacketsReceived(): Long = packetsReceivedCount.get()
    fun getPacketsLost(): Long = packetsLostCount.get()
    fun getNextExpectedSeq(): Long = nextExpectedSeq

    fun reset() {
        synchronized(lock) {
            for (p in packetMap.values) {
                recyclePacket(p)
            }
            packetMap.clear()
            nextExpectedSeq = -1L
            packetsReceivedCount.set(0)
            packetsLostCount.set(0)
            estimatedJitterUs = 0.0
            hasPrevPacket = false
            targetDelayMs = if (isGamingMode) 5.0 else 40.0
            isPrebuffered = false
            missingSeqSinceMs = 0L
            emptySinceMs = 0L
        }
    }

    sealed class PopResult {
        data class Packet(val audioPacket: PooledPacket) : PopResult()
        data class LostPacket(val lostSeq: Long) : PopResult()
        object WaitingForReorder : PopResult()
        object Empty : PopResult()
    }
}
