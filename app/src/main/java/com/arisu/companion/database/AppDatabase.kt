package com.arisu.companion.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Conversation bucket. Active conversation lives in "default". */
    val sessionId: String = "default",
    val role: String, // "user" | "assistant" | "error"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Non-null when generation was interrupted / failed; used for retry. */
    val failed: Boolean = false,
    /** Newline separated absolute paths to provider-returned images (Gemini). */
    @androidx.room.ColumnInfo(defaultValue = "")
    val images: String = "",
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class SessionSummary(
    val sessionId: String,
    val lastTimestamp: Long,
    val messageCount: Int,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun messages(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun messagesOnce(sessionId: String): List<ChatMessageEntity>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET content = :content, failed = :failed WHERE id = :id")
    suspend fun update(id: Long, content: String, failed: Boolean)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT sessionId, MAX(timestamp) AS lastTimestamp, COUNT(*) AS messageCount " +
        "FROM chat_messages GROUP BY sessionId ORDER BY lastTimestamp DESC")
    fun sessions(): Flow<List<SessionSummary>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND (content LIKE '%' || :query || '%') " +
        "ORDER BY timestamp DESC")
    suspend fun search(sessionId: String, query: String): List<ChatMessageEntity>
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun all(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    suspend fun allOnce(): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [ChatMessageEntity::class, MemoryEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        /** v2 adds `chat_messages.images` without touching existing history. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN images TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "arisu.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
