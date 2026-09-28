package com.example.data

/**
 * Una entrada cruda de la lista M3U, ya con sus atributos (#EXTINF) separados.
 */
data class M3uEntry(
    val url: String,
    val name: String,
    val groupTitle: String,
    val logoUrl: String?,
    val tvgId: String?,
    // ✅ NUEVO: atributo opcional "tmdb-id" en el #EXTINF. Si viene, se usa DIRECTO (sin adivinar
    // por nombre) para traer carátula/sinopsis exactas. Ej: tmdb-id="1396" (Breaking Bad).
    val tmdbId: String? = null
)

/** Episodio ya clasificado dentro de una serie: temporada/episodio vienen de LA LISTA M3U, nunca de TMDB. */
data class M3uEpisode(
    val entry: M3uEntry,
    val season: Int,
    val episode: Int,
    val episodeLabel: String
)

/** Serie agrupada: mismas temporadas y orden de episodios que trae la lista M3U original. */
data class M3uSeriesGroup(
    val title: String,
    val logoUrl: String?,
    val groupTitle: String,
    val tmdbId: String?,
    // clave = número de temporada (según el M3U), valor = episodios ordenados
    val seasons: Map<Int, List<M3uEpisode>>
) {
    val seasonNumbers: List<Int> get() = seasons.keys.sorted()
}

data class M3uMovie(val entry: M3uEntry)

/** Resultado final de procesar una lista completa: separado en películas, series y "otros" (canales en vivo, etc). */
data class ParsedM3uPlaylist(
    val movies: List<M3uMovie>,
    val series: List<M3uSeriesGroup>,
    val others: List<M3uEntry>,
    val categories: List<String>
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty() && others.isEmpty()
}
