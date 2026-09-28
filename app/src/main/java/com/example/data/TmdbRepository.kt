package com.example.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente mínimo de TMDB: solo se usa para CARÁTULAS, SINOPSIS y GÉNERO.
 * IMPORTANTE: nunca decide temporadas/episodios — eso siempre sale de la lista M3U
 * (ver M3uParser). Aquí solo se busca "por nombre" porque las listas M3U no traen tmdb_id.
 */
object TmdbRepository {
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
    private const val PREFS = "tmdb_prefs"

    // Caché en memoria: evita repetir la misma búsqueda al recomponer/scrollear (por sesión de app)
    private val cache = HashMap<String, TmdbInfo?>()

    fun getApiKey(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("api_key", "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("api_key", key.trim()).apply()
    }

    suspend fun searchMovie(context: Context, name: String): TmdbInfo? = search(context, name, isTv = false)
    suspend fun searchTv(context: Context, name: String): TmdbInfo? = search(context, name, isTv = true)

    /**
     * Punto de entrada único que usa la UI: si la entrada M3U trae `tmdb-id="..."`, lo usa DIRECTO
     * (exacto, sin adivinar). Si no, busca por el nombre (heurística de texto, puede fallar con
     * títulos genéricos).
     */
    suspend fun lookup(context: Context, title: String, tmdbId: String?, isTv: Boolean): TmdbInfo? {
        if (!tmdbId.isNullOrBlank()) {
            val byId = getById(context, tmdbId, isTv)
            if (byId != null) return byId
        }
        return search(context, title, isTv)
    }

    suspend fun getById(context: Context, id: String, isTv: Boolean): TmdbInfo? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) return@withContext null
        val cacheKey = "id:${if (isTv) "tv" else "movie"}:$id"
        if (cache.containsKey(cacheKey)) return@withContext cache[cacheKey]
        try {
            val endpoint = if (isTv) "tv" else "movie"
            val url = URL("$BASE_URL/$endpoint/$id?api_key=$apiKey&language=es-ES")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) { cache[cacheKey] = null; return@withContext null }
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val genresArray = json.optJSONArray("genres")
            val genres = mutableListOf<String>()
            if (genresArray != null) for (i in 0 until genresArray.length()) genres.add(genresArray.getJSONObject(i).optString("name"))
            val posterPath = json.optString("poster_path", "")
            val backdropPath = json.optString("backdrop_path", "")
            val dateField = if (isTv) "first_air_date" else "release_date"
            val info = TmdbInfo(
                tmdbId = json.optInt("id"),
                title = json.optString(if (isTv) "name" else "title", ""),
                overview = json.optString("overview", ""),
                posterUrl = if (posterPath.isNotBlank()) IMG_BASE + posterPath else null,
                backdropUrl = if (backdropPath.isNotBlank()) BACKDROP_BASE + backdropPath else null,
                year = json.optString(dateField, "").take(4),
                rating = json.optDouble("vote_average", 0.0),
                genres = genres
            )
            cache[cacheKey] = info
            info
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun search(context: Context, rawName: String, isTv: Boolean): TmdbInfo? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) return@withContext null
        val cleanName = cleanTitleForSearch(rawName)
        val cacheKey = "${if (isTv) "tv" else "movie"}:$cleanName"
        if (cache.containsKey(cacheKey)) return@withContext cache[cacheKey]

        try {
            val endpoint = if (isTv) "tv" else "movie"
            val query = URLEncoder.encode(cleanName, "UTF-8")
            val url = URL("$BASE_URL/search/$endpoint?api_key=$apiKey&language=es-ES&query=$query")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val text = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) {
                cache[cacheKey] = null
                return@withContext null
            }
            val first = results.getJSONObject(0)
            val genreIds = first.optJSONArray("genre_ids")
            val genreMap = if (isTv) TmdbGenres.tv else TmdbGenres.movie
            val genres = mutableListOf<String>()
            if (genreIds != null) for (i in 0 until genreIds.length()) genreMap[genreIds.optInt(i)]?.let { genres.add(it) }

            val posterPath = first.optString("poster_path", "")
            val backdropPath = first.optString("backdrop_path", "")
            val dateField = if (isTv) "first_air_date" else "release_date"
            val info = TmdbInfo(
                tmdbId = first.optInt("id"),
                title = first.optString(if (isTv) "name" else "title", cleanName),
                overview = first.optString("overview", ""),
                posterUrl = if (posterPath.isNotBlank()) IMG_BASE + posterPath else null,
                backdropUrl = if (backdropPath.isNotBlank()) BACKDROP_BASE + backdropPath else null,
                year = first.optString(dateField, "").take(4),
                rating = first.optDouble("vote_average", 0.0),
                genres = genres
            )
            cache[cacheKey] = info
            info
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Limpia sufijos típicos de listas M3U (año entre paréntesis, calidad, etc.) para mejorar el match. */
    private fun cleanTitleForSearch(name: String): String {
        return name
            .replace(Regex("""\(\d{4}\)"""), "")
            .replace(Regex("""(?i)\b(4k|1080p|720p|hd|fhd|latino|castellano|subtitulado|vose)\b"""), "")
            .replace(Regex("""[|_]+"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }
}
