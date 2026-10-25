package com.pixelpass.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Nonce cache used to reject a token that is presented twice, including while
 * the gate has no connectivity (split-brain replay).
 */
@Entity(tableName = "scanned_nonces")
data class ScannedNonceEntity(
    @PrimaryKey val nonce: String,
    val subjectId: String,
    val scannedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val verdict: String
)
