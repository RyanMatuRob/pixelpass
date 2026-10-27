package com.pixelpass.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingSyncScanDao {

    @Insert
    suspend fun insert(scan: PendingSyncScanEntity): Long

    @Query(
        "SELECT * FROM pending_sync_scans WHERE syncedAtEpochMillis IS NULL " +
            "ORDER BY scannedAtEpochMillis ASC LIMIT :limit"
    )
    suspend fun loadUnsynced(limit: Int = 25): List<PendingSyncScanEntity>

    @Query("SELECT COUNT(*) FROM pending_sync_scans WHERE syncedAtEpochMillis IS NULL")
    suspend fun countUnsynced(): Int

    @Query("UPDATE pending_sync_scans SET syncedAtEpochMillis = :syncedAt, lastError = NULL WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long)

    @Query(
        "UPDATE pending_sync_scans SET syncAttempts = syncAttempts + 1, " +
            "lastAttemptAtEpochMillis = :attemptedAt, lastError = :error WHERE id = :id"
    )
    suspend fun markAttemptFailed(id: Long, attemptedAt: Long, error: String?)

    @Query("SELECT * FROM pending_sync_scans ORDER BY scannedAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<PendingSyncScanEntity>>
}
