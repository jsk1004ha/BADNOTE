package com.inkforge.notesstudio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BitmapLeaseTest {
    @Test fun repeatedCompletionReleasesExactlyOnce() {
        val released=AtomicInteger()
        val lease=BitmapLease { released.incrementAndGet();Unit }
        val workers=(0 until 8).map { Thread { repeat(100) { lease.close() } }.apply { start() } }
        workers.forEach { it.join() }
        assertEquals(1,released.get())
    }
}
