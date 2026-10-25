package com.pixelpass.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A scan the guard device accepted while offline (or while the gateway was down).
 * The worker replays it against `/api/scan` as soon as connectivity returns.
 */
@Entity(tableName = "pending_sync_scans")
data class PendingSyncScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val token: String,
    val nonce: String?,
    val scannedAtEpochMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val deviceFingerprint: String,
    val faceImageBase64: String?,
    val decision: String,
    val syncAttempts: Int = 0,
    val lastAttemptAtEpochMillis: Long? = null,
    val syncedAtEpochMillis: Long? = null,
    val lastError: String? = null
)
