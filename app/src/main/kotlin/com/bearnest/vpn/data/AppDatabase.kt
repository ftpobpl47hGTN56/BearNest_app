package com.bearnest.vpn.data

import androidx.room.*
import com.bearnest.vpn.model.ServerConfig
import kotlinx.coroutines.flow.Flow

// ── Entity ──────────────────────────────────────────────────────────────────

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
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
    val sortOrder: Int = 0  // для ручной сортировки
) {
    fun toServerConfig() = ServerConfig(
        name = name,
        protocol = protocol,
        address = address,
        port = port,
        id = uuid,
        security = security,
        network = network,
        publicKey = publicKey,
        shortId = shortId,
        serverName = serverName,
        path = path,
        flow = flow,
        fingerprint = fingerprint,
        pingMs = pingMs
    )

    companion object {
        fun fromServerConfig(cfg: ServerConfig, sortOrder: Int = 0) = ServerEntity(
            name = cfg.name,
            protocol = cfg.protocol,
            address = cfg.address,
            port = cfg.port,
            uuid = cfg.id,
            security = cfg.security,
            network = cfg.network,
            publicKey = cfg.publicKey,
            shortId = cfg.shortId,
            serverName = cfg.serverName,
            path = cfg.path,
            flow = cfg.flow,
            fingerprint = cfg.fingerprint,
            pingMs = cfg.pingMs,
            sortOrder = sortOrder
        )
    }
}

// ── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<ServerEntity>

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

    @Query("UPDATE servers SET pingMs = :ping WHERE id = :id")
    suspend fun updatePing(id: Int, ping: Long)
}

// ── Database ─────────────────────────────────────────────────────────────────

@Database(entities = [ServerEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bearnest.db"
                ).build().also { INSTANCE = it }
            }
    }
}
