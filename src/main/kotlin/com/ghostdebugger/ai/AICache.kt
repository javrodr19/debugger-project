package com.ghostdebugger.ai

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class AICache(private val ttlSeconds: Long = 3600) {

    private data class CacheEntry(
        val response: String,
        val createdAt: Instant = Instant.now()
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun get(key: String): String? {
        val entry = cache[key] ?: return null
        val age = Instant.now().epochSecond - entry.createdAt.epochSecond
        if (age > ttlSeconds) {
            cache.remove(key)
            return null
        }
        return entry.response
    }

    fun put(key: String, response: String) {
        cache[key] = CacheEntry(response)
    }

    fun computeKey(code: String, promptType: String): String {
        val input = "$promptType:$code"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback for environments where SHA-256 might be restricted
            input.hashCode().toString()
        }
    }

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size
}
