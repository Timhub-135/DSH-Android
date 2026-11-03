package io.github.jackwener.dsh.core.data

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ruleId: Long? = null,
    val running: Boolean = false,
    /** Subagent sessions point at the session that spawned them; hidden from the sidebar. */
    val parentId: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** A turn finished while the user was not looking at this session. */
    val unread: Boolean = false,
    /** PermissionPreset id; null = the default from settings. */
    val preset: String? = null,
    /** SessionMode id (issue #3); null = 默认 (rows from before modes, and conversations never given one). */
    val modeId: String? = null,
    /** 免打扰 (#43): finished turns post no notification; a run blocked on the user still does. */
    @ColumnInfo(defaultValue = "0") val muted: Boolean = false,
    /**
     * The conversation's own model (#53, core/session/SessionModel.kt): route ("" = DeepSeek, else a
     * provider's route id), model id, effort (DeepSeek Effort id / third-party level, null = the model's
     * default). [modelProvider] null = follows the settings default (rows from before #53, automations).
     */
    val modelProvider: String? = null,
    val modelId: String? = null,
    val modelEffort: String? = null,
)

/**
 * Raw dsh `session.event` payloads plus app-local records (type `app/...`, negative seq), in
 * arrival order. Both the conversation and the trajectory view are projections of this log.
 */
@Entity(tableName = "events", primaryKeys = ["sessionId", "seq"])
data class EventEntity(val sessionId: String, val seq: Long, val type: String, val json: String, val time: Long = 0)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val specJson: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastFiredAt: Long? = null,
)

@Entity(tableName = "rule_runs", indices = [Index("ruleId")])
data class RuleRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val firedAt: Long,
    val sessionId: String?,
    val outcome: String,
)

@Entity(tableName = "usage")
data class UsageEntity(@PrimaryKey val day: String, val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long)

@Dao
interface SessionDao {
    /** Every row, newest first; the session list filters it with SessionVisibility.listed (one rule for list and notifications). */
    @Query("SELECT * FROM sessions WHERE parentId IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeTopLevel(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>
    @Query("SELECT * FROM sessions WHERE parentId = :id")
    suspend fun children(id: String): List<SessionEntity>
    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)
    @Query("UPDATE sessions SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)
    @Query("UPDATE sessions SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: String, muted: Boolean)
    @Query("UPDATE sessions SET unread = :unread WHERE id = :id")
    suspend fun setUnread(id: String, unread: Boolean)
    @Query("UPDATE sessions SET preset = :preset WHERE id = :id")
    suspend fun setPreset(id: String, preset: String?)
    @Query("UPDATE sessions SET modeId = :modeId WHERE id = :id")
    suspend fun setMode(id: String, modeId: String?)
    @Query("UPDATE sessions SET modelProvider = :provider, modelId = :model, modelEffort = :effort WHERE id = :id")
    suspend fun setModel(id: String, provider: String?, model: String?, effort: String?)
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE running = 1")
    suspend fun running(): List<SessionEntity>
    @Upsert suspend fun upsert(s: SessionEntity)
    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)
    @Query("UPDATE sessions SET running = :running, updatedAt = :now WHERE id = :id")
    suspend fun setRunning(id: String, running: Boolean, now: Long = System.currentTimeMillis())
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(e: EventEntity)
    @Query("SELECT * FROM events WHERE sessionId = :id ORDER BY time, seq")
    suspend fun events(id: String): List<EventEntity>
    @Query("DELETE FROM events WHERE sessionId = :id")
    suspend fun deleteEvents(id: String)
    /** How the newest runs of top-level conversations ended (诊断信息). */
    @Query("SELECT e.* FROM events e JOIN sessions s ON s.id = e.sessionId WHERE s.parentId IS NULL AND e.type IN ('turn/end', 'app/error', 'app/stopped') ORDER BY e.time DESC LIMIT :limit")
    suspend fun recentRunEnds(limit: Int): List<EventEntity>
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun observe(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM rules")
    suspend fun all(): List<RuleEntity>
    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: Long): RuleEntity?
    @Query("SELECT * FROM rules WHERE id = :id")
    fun observeOne(id: Long): Flow<RuleEntity?>
    @Insert suspend fun insert(r: RuleEntity): Long
    @Upsert suspend fun upsert(r: RuleEntity)
    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)
    @Insert suspend fun insertRun(r: RuleRunEntity)
    @Query("SELECT * FROM rule_runs WHERE ruleId = :ruleId ORDER BY firedAt DESC LIMIT 20")
    fun runs(ruleId: Long): Flow<List<RuleRunEntity>>
    @Query("SELECT COUNT(*) FROM rule_runs WHERE ruleId = :ruleId AND firedAt > :since")
    suspend fun runsSince(ruleId: Long, since: Long): Int
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage ORDER BY day DESC LIMIT 30")
    fun observe(): Flow<List<UsageEntity>>
    @Query("SELECT * FROM usage WHERE day = :day")
    suspend fun get(day: String): UsageEntity?
    @Upsert suspend fun upsert(u: UsageEntity)
}

@Database(entities = [SessionEntity::class, EventEntity::class, RuleEntity::class, RuleRunEntity::class, UsageEntity::class], version = DB_VERSION)
abstract class DshDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun rules(): RuleDao
    abstract fun usage(): UsageDao
}

const val DB_VERSION = 5

/**
 * Schema migrations. Every released version from 2 on is migrated in place (conversations are user
 * data); only version 1 (pre-release builds) is still recreated. A new schema version must add
 * its Migration here: Room then fails loudly instead of silently dropping every conversation.
 */
object Migrations {
    /** v3: `sessions.modeId` (session modes). Existing rows read null = 默认, i.e. unchanged behaviour. */
    val SQL_2_3 = listOf("ALTER TABLE `sessions` ADD COLUMN `modeId` TEXT")

    val M_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) { SQL_2_3.forEach(db::execSQL) }
    }

    /** v4: `sessions.muted` (免打扰, #43). Existing rows read false = notifications as before. */
    val SQL_3_4 = listOf("ALTER TABLE `sessions` ADD COLUMN `muted` INTEGER NOT NULL DEFAULT 0")

    val M_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) { SQL_3_4.forEach(db::execSQL) }
    }

    /** v5: the conversation's own model (#53). Existing rows read null = the settings default, as before. */
    val SQL_4_5 = listOf(
        "ALTER TABLE `sessions` ADD COLUMN `modelProvider` TEXT",
        "ALTER TABLE `sessions` ADD COLUMN `modelId` TEXT",
        "ALTER TABLE `sessions` ADD COLUMN `modelEffort` TEXT",
    )

    val M_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) { SQL_4_5.forEach(db::execSQL) }
    }

    val ALL: Array<Migration> = arrayOf(M_2_3, M_3_4, M_4_5)
    /** Versions that are still recreated instead of migrated. */
    val DESTRUCTIVE_FROM = intArrayOf(1)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext c: Context): DshDatabase = Room.databaseBuilder(c, DshDatabase::class.java, "dsh.db")
        .addMigrations(*Migrations.ALL)
        .fallbackToDestructiveMigrationFrom(true, *Migrations.DESTRUCTIVE_FROM)
        // Installing an older build over a newer one (sideloaded test builds) cannot read the newer schema.
        .fallbackToDestructiveMigrationOnDowngrade(true)
        .build()
    @Provides fun sessions(db: DshDatabase) = db.sessions()
    @Provides fun rules(db: DshDatabase) = db.rules()
    @Provides fun usage(db: DshDatabase) = db.usage()
}
