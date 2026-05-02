package com.example.vocalapp

/**
 * Thread-safe ring buffer for 16-bit PCM audio samples (Short).
 *
 * Producer thread calls [write]; consumer thread calls [available] / [peek] / [consume].
 * On overflow the oldest samples are silently dropped — this is the right behaviour for
 * a real-time visualizer: better to lose a few ms than to block the audio thread.
 */
class RingBuffer(private val capacity: Int) {

    private val buffer = ShortArray(capacity)
    private var writePos = 0
    private var readPos = 0
    private var count = 0
    private val lock = Any()

    /** Number of unread samples currently in the buffer. */
    fun available(): Int = synchronized(lock) { count }

    /** Append [length] samples from [data] starting at [offset]. */
    fun write(data: ShortArray, offset: Int = 0, length: Int = data.size) {
        if (length <= 0) return
        synchronized(lock) {
            for (i in 0 until length) {
                if (count == capacity) {
                    // Drop oldest sample
                    readPos = (readPos + 1) % capacity
                    count--
                }
                buffer[writePos] = data[offset + i]
                writePos = (writePos + 1) % capacity
                count++
            }
        }
    }

    /** Read up to [n] samples without advancing the read pointer. */
    fun peek(n: Int): ShortArray {
        synchronized(lock) {
            val toRead = minOf(n, count)
            val out = ShortArray(toRead)
            for (i in 0 until toRead) {
                out[i] = buffer[(readPos + i) % capacity]
            }
            return out
        }
    }

    /** Advance the read pointer by [n] samples (clamped to available). */
    fun consume(n: Int) {
        if (n <= 0) return
        synchronized(lock) {
            val toConsume = minOf(n, count)
            readPos = (readPos + toConsume) % capacity
            count -= toConsume
        }
    }

    /** Discard all buffered data. */
    fun clear() {
        synchronized(lock) {
            writePos = 0
            readPos = 0
            count = 0
        }
    }
}

