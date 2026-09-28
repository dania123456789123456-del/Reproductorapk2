package com.example.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object M3uParser {

    // #EXTINF:-1 tvg-id="x" tvg-name="x" tvg-logo="http://..." group-title="Series",Nombre a mostrar
    private val attrRegex = Regex("""(\w[\w-]*)="([^"]*)"""")

    // Patrones de temporada/episodio que se buscan EN ESTE ORDEN dentro del nombre del canal/entrada.
    // Todo lo que separan viene de la propia lista M3U: si el proveedor divide en 3 temporadas de 42,
    // así queda, sin importar cómo TMDB agrupe esa misma serie.
    private val sxxExx = Regex("""(?i)s(?:eason)?\s*[.\-_]?(\d{1,2})\s*[ex]\s*[.\-_]?(\d{1,3})""")
    private val nxNn = Regex("""(?i)(\d{1,2})x(\d{1,3})(?!\d)""")
    private val temporadaCap = Regex("""(?i)temporada\s*(\d{1,2}).{0,15}?(?:cap[ií]tulo|episodio|ep\.?)\s*(\d{1,3})""")

    private val genericMovieGroups = listOf("pelicul", "movie", "film", "vod")
    private val genericLiveGroups = listOf("en vivo", "live", "canal", "tv ", " tv", "24/7", "24-7")

    /**
     * Descarga y parsea una lista M3U desde:
     *  - URL http/https (incluye enlaces raw.githubusercontent.com)
     *  - Uri local content:// (archivo .m3u descargado y elegido con el selector de archivos)
     */
    suspend fun fetchAndParse(context: Context, location: String): ParsedM3uPlaylist = withContext(Dispatchers.IO) {
        val rawText = fetchRawText(context, location)
        parse(rawText)
    }

    private fun fetchRawText(context: Context, location: String): String {
        return if (location.startsWith("content://") || location.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(location))?.use { it.bufferedReader().readText() } ?: ""
        } else {
            val connection = URL(location).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            // Necesario para servidores/CDNs que bloquean clientes sin User-Agent (incluye GitHub raw)
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().readText()
            } else ""
        }
    }

    fun parse(rawText: String): ParsedM3uPlaylist {
        val entries = mutableListOf<M3uEntry>()
        var pendingAttrs: Map<String, String>? = null
        var pendingName = ""

        rawText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pendingAttrs = attrRegex.findAll(line).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                    val lastComma = line.lastIndexOf(',')
                    pendingName = if (lastComma != -1 && lastComma < line.length - 1) line.substring(lastComma + 1).trim() else ""
                }
                line.isEmpty() || line.startsWith("#") -> { /* #EXTM3U, #EXTGRP, #EXT-X-*, comentarios: se ignoran */ }
                else -> {
                    val attrs = pendingAttrs
                    val name = pendingName.ifBlank { attrs?.get("tvg-name") ?: "Sin título" }
                    entries.add(
                        M3uEntry(
                            url = line,
                            name = name,
                            groupTitle = attrs?.get("group-title") ?: "General",
                            logoUrl = attrs?.get("tvg-logo"),
                            tvgId = attrs?.get("tvg-id"),
                            tmdbId = attrs?.get("tmdb-id")
                        )
                    )
                    pendingAttrs = null
                    pendingName = ""
                }
            }
        }
        return classify(entries)
    }

    /**
     * Heurística de clasificación:
     * 1) Si el nombre trae un patrón SxxExx / 1x01 / "Temporada X Capítulo Y" -> es episodio de serie.
     *    El título base de la serie es el group-title (caso típico de listas IPTV: group = nombre
     *    de la serie, título de la entrada = "S01E01" o "Capítulo 1"), o si el group-title es
     *    genérico, el texto que quede antes del patrón dentro del propio nombre.
     * 2) Si NO hay patrón pero el group-title se repite muchas veces con nombres tipo "Cap 1/2/3..."
     *    dentro de un grupo no genérico, también se trata como serie (numerando por orden de aparición).
     * 3) Todo lo demás: película, salvo que el group-title indique claramente "canal en vivo".
     */
    private fun classify(entries: List<M3uEntry>): ParsedM3uPlaylist {
        val movies = mutableListOf<M3uMovie>()
        val others = mutableListOf<M3uEntry>()
        // seriesTitle -> lista de episodios (aún sin agrupar por temporada)
        val seriesBuckets = LinkedHashMap<String, MutableList<M3uEpisode>>()
        val seriesLogo = HashMap<String, String?>()
        val seriesGroupTitle = HashMap<String, String>()
        val seriesTmdbId = HashMap<String, String?>()

        // Para el caso 2 (sin patrón numérico pero mismo group repetido): se resuelve en una 2da pasada.
        val genericByGroup = LinkedHashMap<String, MutableList<M3uEntry>>()

        for (entry in entries) {
            val (season, episode) = extractSeasonEpisode(entry.name) ?: (null to null)
            val groupIsGeneric = isGenericGroup(entry.groupTitle)

            if (season != null && episode != null) {
                val seriesTitle = if (!groupIsGeneric) entry.groupTitle.trim()
                else stripSeasonEpisodeToken(entry.name).ifBlank { entry.groupTitle.trim() }
                val label = entry.name.ifBlank { "Episodio $episode" }
                seriesBuckets.getOrPut(seriesTitle) { mutableListOf() }.add(M3uEpisode(entry, season, episode, label))
                seriesLogo.putIfAbsent(seriesTitle, entry.logoUrl)
                seriesGroupTitle.putIfAbsent(seriesTitle, entry.groupTitle)
                if (entry.tmdbId != null) seriesTmdbId.putIfAbsent(seriesTitle, entry.tmdbId)
            } else if (!groupIsGeneric && isLiveGroup(entry.groupTitle).not()) {
                // Podría ser serie sin numeración explícita (se decide tras contar repeticiones del grupo)
                genericByGroup.getOrPut(entry.groupTitle.trim()) { mutableListOf() }.add(entry)
            } else if (isLiveGroup(entry.groupTitle)) {
                others.add(entry)
            } else {
                movies.add(M3uMovie(entry))
            }
        }

        // Resolver grupos "genéricos": si un mismo group-title trae 2+ entradas, se asume serie
        // de una sola temporada (temporada 1) numerada por el orden en que aparecen en la lista.
        genericByGroup.forEach { (groupTitle, items) ->
            if (items.size >= 2) {
                items.forEachIndexed { idx, entry ->
                    seriesBuckets.getOrPut(groupTitle) { mutableListOf() }
                        .add(M3uEpisode(entry, season = 1, episode = idx + 1, episodeLabel = entry.name.ifBlank { "Episodio ${idx + 1}" }))
                }
                seriesLogo.putIfAbsent(groupTitle, items.first().logoUrl)
                seriesGroupTitle.putIfAbsent(groupTitle, groupTitle)
                items.firstOrNull { it.tmdbId != null }?.let { seriesTmdbId.putIfAbsent(groupTitle, it.tmdbId) }
            } else {
                items.forEach { movies.add(M3uMovie(it)) }
            }
        }

        val series = seriesBuckets.map { (title, episodes) ->
            val seasonsMap = episodes.groupBy { it.season }
                .mapValues { (_, eps) -> eps.sortedBy { it.episode } }
                .toSortedMap()
            M3uSeriesGroup(title = title, logoUrl = seriesLogo[title], groupTitle = seriesGroupTitle[title] ?: title, tmdbId = seriesTmdbId[title], seasons = seasonsMap)
        }.sortedBy { it.title.lowercase(Locale.ROOT) }

        val categories = entries.map { it.groupTitle.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

        return ParsedM3uPlaylist(
            movies = movies.sortedBy { it.entry.name.lowercase(Locale.ROOT) },
            series = series,
            others = others,
            categories = categories
        )
    }

    private fun extractSeasonEpisode(name: String): Pair<Int, Int>? {
        sxxExx.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        temporadaCap.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        nxNn.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return null
    }

    private fun stripSeasonEpisodeToken(name: String): String {
        return name.replace(sxxExx, "").replace(temporadaCap, "").replace(nxNn, "")
            .replace(Regex("""[.\-_]+$"""), "").trim(' ', '-', '.', ':', '|')
    }

    private fun isGenericGroup(group: String): Boolean {
        val g = group.lowercase(Locale.ROOT)
        return genericMovieGroups.any { g.contains(it) } || g.isBlank() || g == "general"
    }

    private fun isLiveGroup(group: String): Boolean {
        val g = group.lowercase(Locale.ROOT)
        return genericLiveGroups.any { g.contains(it) }
    }
}
