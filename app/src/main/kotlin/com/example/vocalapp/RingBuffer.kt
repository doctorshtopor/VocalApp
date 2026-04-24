// RingBuffer.kt
package com.example.vocalapp

import java.util.concurrent.atomic.AtomicLong

class RingBuffer(private val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var writePos = 0
    private var available = 0
    private val totalWritten = AtomicLong(0)

    @Synchronized
    fun available(): Int = available

    @Synchronized
    fun clear() {
        writePos = 0
        available = 0
        totalWritten.set(0)
        buffer.fill(0)
    }

    @Synchronized
    fun write(data: ShortArray, offset: Int = 0, size: Int = data.size): Int {
        val actualSize = minOf(size, capacity - available)
        if (actualSize <= 0) return 0

        for (i in 0 until actualSize) {
            buffer[writePos] = data[offset + i]
            writePos = (writePos + 1) % capacity
        }

        available += actualSize
        totalWritten.addAndGet(actualSize.toLong())
        return actualSize
    }

    @Synchronized
    fun peek(size: Int): ShortArray {
        val actualSize = minOf(size, available)
        val result = ShortArray(actualSize)

        val readStart = (writePos - available + capacity) % capacity

        for (i in 0 until actualSize) {
            result[i] = buffer[(readStart + i) % capacity]
        }

        return result
    }

    @Synchronized
    fun consume(size: Int) {
        val actualSize = minOf(size, available)
        available -= actualSize
    }

    // НОВЫЙ МЕТОД: чтение из абсолютной позиции
    @Synchronized
    fun readFromAbsolutePosition(absolutePosition: Long, size: Int): ShortArray? {
        if (absolutePosition < 0) return null

        val totalWritten = totalWritten.get()
        if (absolutePosition >= totalWritten) return null

        val relativePos = (absolutePosition - (totalWritten - available)).toInt()
        if (relativePos < 0 || relativePos + size > available) {
            return null
        }

        val result = ShortArray(size)
        val readStart = (writePos - available + relativePos + capacity) % capacity

        for (i in 0 until size) {
            result[i] = buffer[(readStart + i) % capacity]
        }

        return result
    }

    // НОВЫЙ МЕТОД: получение абсолютной позиции write pointer
    fun getTotalWritten(): Long = totalWritten.get()
}