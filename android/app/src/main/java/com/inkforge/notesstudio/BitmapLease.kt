package com.inkforge.notesstudio

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Ownership ends on actual SDK completion, including timeout, cancellation and start failure. */
internal class BitmapLease(private val release: () -> Unit) : Closeable {
    private val released = AtomicBoolean(false)
    override fun close() { if (released.compareAndSet(false,true)) release() }
}
