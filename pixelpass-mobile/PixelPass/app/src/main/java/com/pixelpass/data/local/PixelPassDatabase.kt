package com.pixelpass.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local store for the mobile client. It is deliberately small: the offline scan
 * queue, the replay nonce cache, and (from the observability issue) nothing else.
 *
 * App Inspection -> Database Inspector can open `pixelpass.db` live while the
 * debug app is running because the database is a plain Room/SQLite file with no
 * encryption layer.
 */
@Database(
    entities = [PendingSyncScanEntity::class, ScannedNonceEntity::class],
    version = 1,
    exportSchema = true
)
abstract class PixelPassDatabase : RoomDatabase() {

    abstract fun pendingSyncScanDao(): PendingSyncScanDao

    abstract fun scannedNonceDao(): ScannedNonceDao

    companion object {
        const val NAME = "pixelpass.db"

        fun build(context: Context): PixelPassDatabase =
            Room.databaseBuilder(context.applicationContext, PixelPassDatabase::class.java, NAME)
                .build()
    }
}
