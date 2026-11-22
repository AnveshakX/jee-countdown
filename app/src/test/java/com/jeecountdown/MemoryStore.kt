package com.jeecountdown

/** In-memory KeyValueStore: lets tests simulate "app restart" by building new objects over the same store. */
class MemoryStore(initial: Map<String, Any?> = emptyMap()) : KeyValueStore {
    private val m = HashMap<String, Any?>(initial)
    override fun all(): Map<String, Any?> = HashMap(m)
    override fun write(values: Map<String, Any?>) {
        for ((k, v) in values) if (v == null) m.remove(k) else m[k] = v
    }
}
