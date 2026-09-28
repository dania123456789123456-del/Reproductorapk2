package com.example.data

data class TmdbInfo(
    val tmdbId: Int,
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: String,
    val rating: Double,
    val genres: List<String>
)

// Mapas estáticos de géneros de TMDB (evita una llamada extra a /genre/list por cada búsqueda)
object TmdbGenres {
    val movie = mapOf(
        28 to "Acción", 12 to "Aventura", 16 to "Animación", 35 to "Comedia", 80 to "Crimen",
        99 to "Documental", 18 to "Drama", 10751 to "Familiar", 14 to "Fantasía", 36 to "Historia",
        27 to "Terror", 10402 to "Música", 9648 to "Misterio", 10749 to "Romance",
        878 to "Ciencia ficción", 10770 to "TV Movie", 53 to "Suspenso", 10752 to "Bélica", 37 to "Western"
    )
    val tv = mapOf(
        10759 to "Acción y Aventura", 16 to "Animación", 35 to "Comedia", 80 to "Crimen",
        99 to "Documental", 18 to "Drama", 10751 to "Familiar", 10762 to "Infantil",
        9648 to "Misterio", 10763 to "Noticias", 10764 to "Reality", 10765 to "Ciencia ficción y Fantasía",
        10766 to "Telenovela", 10767 to "Talk Show", 10768 to "Bélica y Política", 37 to "Western"
    )
}
