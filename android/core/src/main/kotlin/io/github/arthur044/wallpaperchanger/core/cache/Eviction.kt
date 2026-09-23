package io.github.arthur044.wallpaperchanger.core.cache

data class CacheEntry(val name: String, val sizeBytes: Long, val lastUsedMillis: Long)

/**
 * Names to delete, least recently used first, until what remains fits in
 * [maxBytes]. (The desktop cache grew without limit.)
 */
fun evictionOrder(entries: List<CacheEntry>, maxBytes: Long): List<String> {
    var total = entries.sumOf { it.sizeBytes }
    val evicted = mutableListOf<String>()
    for (entry in entries.sortedBy { it.lastUsedMillis }) {
        if (total <= maxBytes) break
        evicted += entry.name
        total -= entry.sizeBytes
    }
    return evicted
}
