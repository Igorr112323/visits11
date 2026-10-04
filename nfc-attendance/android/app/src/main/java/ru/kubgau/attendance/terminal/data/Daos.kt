package ru.kubgau.attendance.terminal.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE status = 'active' ORDER BY createdAt DESC LIMIT 1")
    suspend fun activeSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status = 'active' ORDER BY createdAt DESC LIMIT 1")
    fun observeActiveSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 50): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE synced = 0")
    suspend fun unsynced(): List<SessionEntity>

    @Query("UPDATE sessions SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE sessions SET status = 'closed', endTime = :endTime WHERE id = :id")
    suspend fun close(id: String, endTime: String)
}

@Dao
interface TapDao {

    @Insert
    suspend fun insert(tap: TapEntity): Long

    @Query("SELECT * FROM taps WHERE synced = 0 ORDER BY createdAt ASC")
    suspend fun unsynced(): List<TapEntity>

    @Query("UPDATE taps SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("SELECT COUNT(*) FROM taps WHERE sessionId = :sessionId")
    fun observeCount(sessionId: String): Flow<Int>

    @Query("SELECT * FROM taps WHERE sessionId = :sessionId ORDER BY tapTime DESC")
    suspend fun forSession(sessionId: String): List<TapEntity>

    @Query("DELETE FROM taps WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}
