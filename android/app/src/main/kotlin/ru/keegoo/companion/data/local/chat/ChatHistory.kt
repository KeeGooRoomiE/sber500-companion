package ru.keegoo.companion.data.local.chat

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["owner", "messageKey"], unique = true), Index(value = ["owner", "seq"])],
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val owner: String,
    val messageKey: String,
    val requestId: String,
    val role: String,
    val text: String = "",
    val source: String = "",
    val date: String,
    val dailyId: String? = null,
    val state: String = "done",
    val pillsJson: String = "[]",
    val previousCount: Long = 0,
    val profileSynced: Boolean = true,
    val searchText: String = text.lowercase(),
) {
    fun pills(): List<String> = Gson().fromJson(pillsJson, Array<String>::class.java)?.toList().orEmpty()
}

@Dao
abstract class ChatHistory {
    @Query("SELECT * FROM chat_messages WHERE owner = :owner AND seq < :before ORDER BY seq DESC LIMIT :limit")
    abstract suspend fun older(owner: String, before: Long, limit: Int): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE owner = :owner AND seq > :after ORDER BY seq LIMIT :limit")
    abstract suspend fun newer(owner: String, after: Long, limit: Int): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE owner = :owner AND messageKey = :key LIMIT 1")
    abstract suspend fun byKey(owner: String, key: String): ChatMessage?

    @Query("""SELECT EXISTS(SELECT 1 FROM chat_messages WHERE owner = :owner AND dailyId = :id
        AND role = 'assistant' AND (state = 'pending' OR source = 'answer'))""")
    abstract suspend fun hasPendingOrAcceptedDailyAnswer(owner: String, id: String): Boolean

    @Query("SELECT count(*) FROM chat_messages WHERE owner = :owner AND state = 'done'")
    abstract suspend fun countMessages(owner: String): Long

    @Query("SELECT * FROM chat_messages WHERE owner = :owner AND seq < :before AND state = 'done' ORDER BY seq DESC LIMIT :limit")
    abstract suspend fun contextBefore(owner: String, before: Long, limit: Int): List<ChatMessage>

    @Query("""
        SELECT * FROM chat_messages WHERE owner = :owner AND seq < :before AND state = 'done'
        AND instr(searchText, :keyword) > 0 ORDER BY seq DESC LIMIT 2
    """)
    abstract suspend fun searchBefore(owner: String, before: Long, keyword: String): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(message: ChatMessage): Long

    // The user message and the answer's permanent place in the list are created together.
    // Starter entries are unique per day. Each submitted turn keeps its request ID across retries.
    @Transaction
    open suspend fun enqueue(
        owner: String, key: String, text: String, source: String, date: String, dailyId: String?,
    ): ChatMessage? {
        if (byKey(owner, key) != null || (dailyId != null && hasPendingOrAcceptedDailyAnswer(owner, dailyId))) return null
        val request = UUID.randomUUID().toString()
        val msg = ChatMessage(
            owner = owner, messageKey = key, requestId = request, role = "user", text = text,
            source = source, date = date, dailyId = dailyId, previousCount = countMessages(owner),
        )
        val seq = insert(msg)
        if (seq < 0) return null
        insert(ChatMessage(
            owner = owner, messageKey = "reply:$request", requestId = request, role = "assistant",
            source = source, date = date, dailyId = dailyId, state = "pending",
        ))
        return msg.copy(seq = seq)
    }

    @Query("SELECT * FROM chat_messages WHERE owner = :owner AND requestId = :request AND role = 'user' LIMIT 1")
    abstract suspend fun sent(owner: String, request: String): ChatMessage?

    @Query("UPDATE chat_messages SET state = 'done', source = :kind, text = :text, searchText = :search, pillsJson = :pills, profileSynced = :synced WHERE owner = :owner AND messageKey = :key")
    abstract suspend fun finish(owner: String, key: String, kind: String, text: String, search: String, pills: String, synced: Boolean)

    @Query("SELECT * FROM chat_messages WHERE owner = :owner AND profileSynced = 0 ORDER BY seq")
    abstract suspend fun awaitingProfile(owner: String): List<ChatMessage>

    @Query("UPDATE chat_messages SET profileSynced = 1 WHERE owner = :owner AND messageKey = :key")
    abstract suspend fun markSynced(owner: String, key: String)
}

@Database(entities = [ChatMessage::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun history(): ChatHistory
}

@Module
@InstallIn(SingletonComponent::class)
object ChatStorageModule {
    @Provides
    @Singleton
    fun history(@ApplicationContext context: Context): ChatHistory =
        Room.databaseBuilder(context, ChatDatabase::class.java, "companion-chat.db").build().history()
}
