package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// 1. History Persistence Model
@Entity(tableName = "playback_history")
data class HistoryEntry(
    @PrimaryKey val url: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

// 2. Playlists Persistence Model (for serial chapters, etc.)
@Entity(tableName = "saved_playlists")
data class SavedPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val urlsJson: String,  // Comma-separated or JSON list of video stream URLs
    val titlesJson: String, // Comma-separated or JSON list of video stream titles
    val timestamp: Long = System.currentTimeMillis()
)

// 3. Fuentes IPTV guardadas (listas M3U por URL raw de GitHub, otro host, o archivo local elegido)
@Entity(tableName = "iptv_sources")
data class IptvSource(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val location: String, // URL http(s) o content:// de un archivo .m3u local
    val timestamp: Long = System.currentTimeMillis()
)

// 3. Room DAO
@Dao
interface PlayerDao {
    @Query("SELECT * FROM playback_history ORDER BY timestamp DESC")
    fun getHistory(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM playback_history WHERE url = :videoUrl LIMIT 1")
    suspend fun getHistoryForUrl(videoUrl: String): HistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHistory(entry: HistoryEntry)

    @Query("DELETE FROM playback_history WHERE url = :videoUrl")
    suspend fun deleteHistoryForUrl(videoUrl: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()

    // Playlists queries
    @Query("SELECT * FROM saved_playlists ORDER BY timestamp DESC")
    fun getPlaylists(): Flow<List<SavedPlaylist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaylist(playlist: SavedPlaylist)

    @Query("DELETE FROM saved_playlists WHERE id = :id")
    suspend fun deletePlaylistById(id: Int)

    // Fuentes IPTV (listas M3U guardadas)
    @Query("SELECT * FROM iptv_sources ORDER BY timestamp DESC")
    fun getIptvSources(): Flow<List<IptvSource>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveIptvSource(source: IptvSource)

    @Query("DELETE FROM iptv_sources WHERE id = :id")
    suspend fun deleteIptvSourceById(id: Int)
}

// 4. Abstract Database
@Database(entities = [HistoryEntry::class, SavedPlaylist::class, IptvSource::class], version = 2, exportSchema = false)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao

    companion object {
        @Volatile
        private var INSTANCE: PlayerDatabase? = null

        fun getDatabase(context: Context): PlayerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PlayerDatabase::class.java,
                    "player_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// 5. Repository Pattern
class PlayerRepository(private val dao: PlayerDao) {
    val historyList: Flow<List<HistoryEntry>> = dao.getHistory()
    val playlistList: Flow<List<SavedPlaylist>> = dao.getPlaylists()

    suspend fun getHistoryForUrl(url: String): HistoryEntry? = dao.getHistoryForUrl(url)

    suspend fun saveHistory(url: String, title: String, positionMs: Long, durationMs: Long) {
        val entry = HistoryEntry(url, title, positionMs, durationMs)
        dao.saveHistory(entry)
    }

    suspend fun deleteHistory(url: String) = dao.deleteHistoryForUrl(url)

    suspend fun clearHistory() = dao.clearHistory()

    suspend fun savePlaylist(name: String, urls: List<String>, titles: List<String>) {
        val urlsStr = urls.joinToString("||")
        val titlesStr = titles.joinToString("||")
        val playlist = SavedPlaylist(name = name, urlsJson = urlsStr, titlesJson = titlesStr)
        dao.savePlaylist(playlist)
    }

    suspend fun deletePlaylist(id: Int) = dao.deletePlaylistById(id)

    val iptvSources: Flow<List<IptvSource>> = dao.getIptvSources()

    suspend fun saveIptvSource(name: String, location: String) {
        dao.saveIptvSource(IptvSource(name = name, location = location))
    }

    suspend fun deleteIptvSource(id: Int) = dao.deleteIptvSourceById(id)
}
