package com.pixelpass.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScannedNonceDao {

    @Query("SELECT * FROM scanned_nonces WHERE nonce = :nonce LIMIT 1")
    suspend fun find(nonce: String): ScannedNonceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ScannedNonceEntity)

    @Query("DELETE FROM scanned_nonces WHERE expiresAtEpochMillis < :now")
    suspend fun prune(now: Long)

    @Query("SELECT COUNT(*) FROM scanned_nonces")
    suspend fun count(): Int
}
