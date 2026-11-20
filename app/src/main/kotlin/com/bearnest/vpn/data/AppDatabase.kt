package com.bearnest.vpn.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.bearnest.vpn.model.ServerConfig
import kotlinx.coroutines.flow.Flow

// ── Server entity ───────────────────────────────────────────────────────────

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** id подписки-владельца (мультиподписка). "" = legacy до миграции. */
    val subscriptionId: String = "",
    /** Избранный сервер — поднимается наверх внутри своей подписки. */
    val isFavorite: Boolean = false,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val uuid: String,       // id в ServerConfig
    val security: String,
    val network: String,
    val publicKey: String,
    val shortId: String,
    val serverName: String,
    val path: String,
    val flow: String,
    val fingerprint: String,
    val pingMs: Long,
    val sortOrder: Int = 0  // исходный порядок внутри подписки (как _ord в Tauri)
) {
    fun toServerConfig() = ServerConfig(
        dbId           = id,
        subscriptionId = subscriptionId,
        isFavorite     = isFavorite,
        name           = name,
        protocol       = protocol,
        address        = address,
        port           = port,
        id             = uuid,
        security       = security,
        network        = network,
        publicKey      = publicKey,
        shortId        = shortId,
        serverName     = serverName,
        path           = path,
        flow           = flow,
        fingerprint    = fingerprint,
        pingMs         = pingMs
    )

    companion object {
        fun fromServerConfig(
            cfg: ServerConfig,
            sortOrder: Int = 0,
            subscriptionId: String = "",
            isFavorite: Boolean = false
        ) = ServerEntity(
            subscriptionId = subscriptionId,
            isFavorite     = isFavorite,
            name           = cfg.name,
            protocol       = cfg.protocol,
            address        = cfg.address,
            port           = cfg.port,
            uuid           = cfg.id,
            security       = cfg.security,
            network        = cfg.network,
            publicKey      = cfg.publicKey,
            shortId        = cfg.shortId,
            serverName     = cfg.serverName,
            path           = cfg.path,
            flow           = cfg.flow,
            fingerprint    = cfg.fingerprint,
            pingMs         = cfg.pingMs,
            sortOrder      = sortOrder
        )
    }
}

// ── Subscription entity (мультиподписка — порт subs[] из Tauri main.js) ──────

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    /** "sub_<ts36>_<rand>" — как newSubId() в Tauri-версии. */
    @PrimaryKey val id: String,
    val name: String = "",
    val url: String = "",
    /** Свёрнута ли группа в списке серверов. */
    val collapsed: Boolean = false,
    // ── Кэш SubscriptionInfo (заголовки подписки) ──
    val infoTitle: String = "",
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expireUnix: Long = 0L,
    val sortOrder: Int = 0,
    val lastUpdateMs: Long = 0L
)

// ── Server DAO ──────────────────────────────────────────────────────────────

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId ORDER BY sortOrder ASC, id ASC")
    suspend fun getBySubscription(subId: String): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(servers: List<ServerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity)

    @Delete
    suspend fun delete(server: ServerEntity)

    @Query("DELETE FROM servers")
    suspend fun deleteAll()

    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteBySubscription(subId: String)

    @Query("UPDATE servers SET pingMs = :ping WHERE id = :id")
    suspend fun updatePing(id: Int, ping: Long)

    @Query("UPDATE servers SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Int, fav: Boolean)

    /** Перепривязать серверы к другой подписке (миграция legacy → первая подписка). */
    @Query("UPDATE servers SET subscriptionId = :toId WHERE subscriptionId = :fromId")
    suspend fun reassignServers(fromId: String, toId: String)

    /** Сколько серверов ещё без подписки (после миграции v1→v2). */
    @Query("SELECT COUNT(*) FROM servers WHERE subscriptionId = '' OR subscriptionId = 'legacy'")
    suspend fun countUnassigned(): Int
}

// ── Subscription DAO ────────────────────────────────────────────────────────

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY sortOrder ASC, rowid ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY sortOrder ASC, rowid ASC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sub: SubscriptionEntity)

    @Update
    suspend fun update(sub: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ── Migration v1 → v2: таблица подписок + поля servers ──────────────────────

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `subscriptions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL DEFAULT '',
                `url` TEXT NOT NULL DEFAULT '',
                `collapsed` INTEGER NOT NULL DEFAULT 0,
                `infoTitle` TEXT NOT NULL DEFAULT '',
                `upload` INTEGER NOT NULL DEFAULT 0,
                `download` INTEGER NOT NULL DEFAULT 0,
                `total` INTEGER NOT NULL DEFAULT 0,
                `expireUnix` INTEGER NOT NULL DEFAULT 0,
                `sortOrder` INTEGER NOT NULL DEFAULT 0,
                `lastUpdateMs` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("ALTER TABLE `servers` ADD COLUMN `subscriptionId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `servers` ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0")
    }
}

// ── Database ────────────────────────────────────────────────────────────────

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bearnest.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
