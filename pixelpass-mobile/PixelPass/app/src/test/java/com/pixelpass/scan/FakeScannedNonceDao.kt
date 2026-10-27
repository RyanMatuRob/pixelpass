package com.pixelpass.scan

import com.pixelpass.data.local.ScannedNonceDao
import com.pixelpass.data.local.ScannedNonceEntity

/**
 * In-memory stand-in for the Room DAO so the replay rules can run on the JVM
 * without an Android runtime. `prune` mirrors the SQL it replaces: an entry is
 * dropped only once its expiry has strictly passed.
 */
class FakeScannedNonceDao : ScannedNonceDao {

    val rows = linkedMapOf<String, ScannedNonceEntity>()

    override suspend fun find(nonce: String): ScannedNonceEntity? = rows[nonce]

    override suspend fun upsert(entry: ScannedNonceEntity) {
        rows[entry.nonce] = entry
    }

    override suspend fun prune(now: Long) {
        rows.entries.removeAll { it.value.expiresAtEpochMillis < now }
    }

    override suspend fun count(): Int = rows.size
}
