package com.ghostdebugger.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
}
