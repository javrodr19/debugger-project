package com.ghostdebugger.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AICacheLruEvictionTest {

    @Test
    fun testLruEviction() {
        val cache = AICache(ttlSeconds = 3600, maxEntries = 10)

        // Fill the cache
        for (i in 1..10) {
            cache.put("key$i", "response$i")
        }
        assertEquals(10, cache.size())

        // Access key1 to make it most recent
        cache.get("key1")

        // Add one more entry, should evict key2 (the oldest)
        cache.put("key11", "response11")

        assertEquals(10, cache.size())
        assertNull(cache.get("key2"), "key2 should have been evicted")
        assertEquals("response1", cache.get("key1"))
        assertEquals("response11", cache.get("key11"))
    }

    @Test
    fun `concurrent get and put on same key does not lose fresh entries`() {
        // Pre-V1.4.1, AICache.get did get-then-check-then-remove without
        // synchronization. A fresh put() between the get and the remove could
        // be evicted incorrectly. V1.4.1 synchronizes the read-modify-write.
        val cache = AICache(ttlSeconds = 60, maxEntries = 256)
        val pool = Executors.newFixedThreadPool(4)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(4)
        val firstError = AtomicReference<Throwable?>(null)
        val iterations = 5000

        fun runHammer(op: (Int) -> Unit) {
            pool.submit {
                try {
                    startGate.await()
                    for (i in 0 until iterations) op(i)
                } catch (t: Throwable) {
                    firstError.compareAndSet(null, t)
                } finally {
                    doneGate.countDown()
                }
            }
        }

        try {
            runHammer { cache.put("k", "v$it") }
            runHammer { cache.put("k", "v$it") }
            runHammer { cache.get("k") }
            runHammer { cache.get("k") }

            startGate.countDown()
            assertTrue(doneGate.await(30, TimeUnit.SECONDS), "Hammers did not finish in 30s")
        } finally {
            pool.shutdown()
        }
        firstError.get()?.let { throw AssertionError("Concurrent get/put threw: $it", it) }

        assertNotNull(cache.get("k"), "Fresh entry for 'k' should not have been evicted by races")
    }
}
