package com.linan.barezen_drive.storage

import java.util.concurrent.ConcurrentHashMap

/**
 * Where a stored object lives: the backend that holds it and the key inside
 * that backend.
 *
 * Serialized form is either a bare key (`blobs/aa/bb/<sha256>`, the shape
 * every row uses today because a single node serves all data) or an explicit
 * `backend:key` pair. Bare keys are read as "whatever backend this process was
 * started with", so switching a deployment to a named backend is additive: old
 * rows keep resolving, new rows can carry a backend prefix.
 *
 * The `files.storage_key` and `file_versions.storage_key` columns are free
 * text up to 512 chars and generated keys never contain a colon, so a
 * multi-node deployment needs no schema change to start writing prefixed refs.
 */
data class StorageRef(val backend: String?, val key: String) {

    /** Form stored in the database: bare while the backend is the default one. */
    fun serialize(): String = if (backend == null) key else "$backend$SEPARATOR$key"

    companion object {
        const val SEPARATOR: Char = ':'

        fun parse(stored: String): StorageRef {
            val index = stored.indexOf(SEPARATOR)
            return if (index < 0) StorageRef(null, stored)
            else StorageRef(stored.substring(0, index).takeIf { index > 0 }, stored.substring(index + 1))
        }
    }
}

/**
 * Registry of the storage backends this process may read from. It is the seam a
 * clustered deployment plugs into; with one backend (always the case today) it
 * simply hands back the provider that was registered first.
 *
 * Nothing in the request path looks a backend name up yet: routes and services
 * keep using the injected [StorageProvider] directly. Adopting a second node is
 * then a two-step change - register both providers at startup, and start
 * resolving per row through [resolve] with `backend:key` refs in the
 * storage_key columns. [copyObject] is the primitive a rebalancing pass uses to
 * move bytes to another backend before the rows are rewritten.
 */
object StorageRegistry {

    private val providers = ConcurrentHashMap<String, StorageProvider>()

    /** First registered backend; also the target of every bare (unprefixed) key. */
    @Volatile
    private var defaultBackend: String? = null

    /** Registers [provider] under [backend]; the first registration becomes the default. */
    fun register(backend: String, provider: StorageProvider) {
        require(backend.isNotBlank() && !backend.contains(StorageRef.SEPARATOR)) {
            "backend name must be non-blank and must not contain a colon"
        }
        providers[backend] = provider
        if (defaultBackend == null) defaultBackend = backend
    }

    fun provider(backend: String): StorageProvider? = providers[backend]

    /** The provider serving bare keys, or null when nothing is registered. */
    fun defaultProvider(): StorageProvider? = defaultBackend?.let { providers[it] }

    fun backends(): Set<String> = providers.keys.toSet()

    /**
     * Resolves a stored key to the provider that holds it and the key to ask
     * that provider for. An unknown backend is a configuration error (a node
     * removed from the cluster, or a typo in the registration), never a silent
     * fallback: reading from the wrong store would answer with a 404 that looks
     * like lost data.
     */
    fun resolve(stored: String): Pair<StorageProvider, String> {
        val ref = StorageRef.parse(stored)
        val backend = ref.backend
        if (backend == null) {
            return (defaultProvider() ?: error("no storage backend registered")) to ref.key
        }
        val provider = providers[backend] ?: error("unknown storage backend '$backend'")
        return provider to ref.key
    }

    /**
     * Streams one object from [from] to [to] without buffering it in memory.
     * Both sides use the same key, which is what makes a migration or a copy to
     * a second node a pure storage-level operation.
     */
    suspend fun copyObject(from: StorageProvider, to: StorageProvider, key: String) {
        to.put(key, from.get(key))
    }

    /** Drops every registration; used by tests that boot several applications. */
    fun reset() {
        providers.clear()
        defaultBackend = null
    }
}
