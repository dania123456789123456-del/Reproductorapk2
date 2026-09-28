package com.example

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.*
import kotlinx.coroutines.launch

private val AccentPurple = Color(0xFF7C3AED)
private val SurfaceDark = Color(0xFF120E1F)
private val CardDark = Color(0xFF1A1025)

private enum class IptvScreenState { SOURCES, BROWSE, SERIES }

/** Pantalla inicial: elegir entre "Solo Links" (comportamiento actual) o "Modo IPTV" (listas M3U). */
@Composable
fun ModeSelectionScreen(onSelectLinks: () -> Unit, onSelectIptv: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PlayCircle, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("¿Cómo quieres usar el reproductor?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Puedes cambiar de modo cuando quieras desde el ícono de arriba.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            ModeOptionCard(icon = Icons.Default.Link, title = "Solo Links", subtitle = "Pega un enlace directo (m3u8, mp4, mkv...) y reproduce al instante.", onClick = onSelectLinks)
            Spacer(Modifier.height(14.dp))
            ModeOptionCard(icon = Icons.Default.LiveTv, title = "Modo IPTV", subtitle = "Carga listas M3U completas (URL raw de GitHub o archivo local) separadas en Películas y Series por temporada.", onClick = onSelectIptv)
        }
    }
}

@Composable
private fun ModeOptionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.9f).clickable { onClick() }) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(46.dp).clip(CircleShape).background(AccentPurple.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = AccentPurple)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Color.Gray, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.DarkGray)
        }
    }
}

/**
 * Contenedor con estado del modo IPTV completo: administrar listas -> catálogo -> detalle de serie.
 * onPlayList reutiliza EXACTAMENTE el mismo reproductor de video que el modo "Solo Links".
 */
@Composable
fun IptvRoot(
    repository: PlayerRepository,
    onPlayList: (urls: List<String>, titles: List<String>, index: Int) -> Unit,
    onChangeMode: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = repository.iptvSources.collectAsState(initial = emptyList())

    var screenState by remember { mutableStateOf(IptvScreenState.SOURCES) }
    var activeSourceName by remember { mutableStateOf("") }
    var parsedPlaylist by remember { mutableStateOf<ParsedM3uPlaylist?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedSeries by remember { mutableStateOf<M3uSeriesGroup?>(null) }

    fun loadSource(source: IptvSource) {
        isLoading = true
        loadError = null
        activeSourceName = source.name
        scope.launch {
            try {
                val result = M3uParser.fetchAndParse(context, source.location)
                if (result.isEmpty) loadError = "La lista no trajo canales/películas/series válidas."
                parsedPlaylist = result
                screenState = IptvScreenState.BROWSE
            } catch (e: Exception) {
                loadError = "No se pudo cargar la lista: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    when (screenState) {
        IptvScreenState.SOURCES -> IptvSourceManagerScreen(
            sources = sources.value,
            isLoading = isLoading,
            error = loadError,
            onAddUrl = { name, url -> scope.launch { repository.saveIptvSource(name, url) } },
            onAddLocalFile = { name, uri -> scope.launch { repository.saveIptvSource(name, uri) } },
            onOpen = { loadSource(it) },
            onDelete = { scope.launch { repository.deleteIptvSource(it) } },
            onChangeMode = onChangeMode
        )
        IptvScreenState.BROWSE -> {
            val playlist = parsedPlaylist
            if (playlist != null) {
                IptvBrowseScreen(
                    sourceName = activeSourceName,
                    playlist = playlist,
                    repository = repository,
                    onPlayMovie = { movie -> onPlayList(listOf(movie.entry.url), listOf(movie.entry.name), 0) },
                    onPlayDirect = { url, title -> onPlayList(listOf(url), listOf(title), 0) },
                    onOpenSeries = { selectedSeries = it; screenState = IptvScreenState.SERIES },
                    onBack = { screenState = IptvScreenState.SOURCES; parsedPlaylist = null }
                )
            }
        }
        IptvScreenState.SERIES -> {
            val series = selectedSeries
            if (series != null) {
                SeriesDetailScreen(
                    series = series,
                    onPlayEpisode = { season, indexInSeason ->
                        val episodes = series.seasons[season].orEmpty()
                        onPlayList(episodes.map { it.entry.url }, episodes.map { it.episodeLabel }, indexInSeason)
                    },
                    onBack = { screenState = IptvScreenState.BROWSE; selectedSeries = null }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IptvSourceManagerScreen(
    sources: List<IptvSource>,
    isLoading: Boolean,
    error: String?,
    onAddUrl: (name: String, url: String) -> Unit,
    onAddLocalFile: (name: String, uri: String) -> Unit,
    onOpen: (IptvSource) -> Unit,
    onDelete: (Int) -> Unit,
    onChangeMode: () -> Unit
) {
    val context = LocalContext.current
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                (context as? Activity)?.contentResolver?.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { e.printStackTrace() }
            val finalName = nameInput.ifBlank { "Lista local" }
            onAddLocalFile(finalName, uri.toString())
            nameInput = ""
        }
    }

    var showTmdbKeyDialog by remember { mutableStateOf(false) }
    var tmdbKeyInput by remember { mutableStateOf(TmdbRepository.getApiKey(context)) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Modo IPTV — Mis Listas", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                navigationIcon = { IconButton(onClick = onChangeMode) { Icon(Icons.Default.SwapHoriz, contentDescription = "Cambiar modo", tint = Color.White) } },
                actions = {
                    IconButton(onClick = { tmdbKeyInput = TmdbRepository.getApiKey(context); showTmdbKeyDialog = true }) {
                        Icon(Icons.Default.Key, contentDescription = "API key de TMDB", tint = if (TmdbRepository.getApiKey(context).isNotBlank()) Color(0xFF7C3AED) else Color.Gray)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = SurfaceDark.copy(alpha = 0.9f))
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("AGREGAR LISTA M3U", color = AccentPurple, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = nameInput, onValueChange = { nameInput = it },
                        label = { Text("Nombre de la lista", color = Color.Gray) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlInput, onValueChange = { urlInput = it },
                        label = { Text("URL raw (GitHub, etc.) — termina en .m3u/.m3u8", color = Color.Gray) },
                        placeholder = { Text("https://raw.githubusercontent.com/.../lista.m3u", color = Color.DarkGray) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (urlInput.isNotBlank()) { onAddUrl(nameInput.ifBlank { "Lista IPTV" }, urlInput.trim()); nameInput = ""; urlInput = "" }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPurple), modifier = Modifier.weight(1f)
                        ) { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Por URL", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), modifier = Modifier.weight(1f)
                        ) { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Archivo .m3u", fontSize = 12.sp) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("LISTAS GUARDADAS", color = Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))

            if (error != null) {
                Text(error, color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            if (sources.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    Text("Aún no agregas ninguna lista M3U.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sources) { source ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF150D2A)), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(source) }
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = AccentPurple)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(source.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(source.location, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onDelete(source.id) }) { Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = Color.Gray, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = AccentPurple, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Descargando y clasificando la lista...", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }

    if (showTmdbKeyDialog) {
        AlertDialog(
            onDismissRequest = { showTmdbKeyDialog = false },
            title = { Text("API key de TMDB", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Se usa solo para carátulas, sinopsis y género. Consíguela gratis en themoviedb.org/settings/api (sección API Read Access / v3 auth).", color = Color.Gray, fontSize = 11.sp, lineHeight = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tmdbKeyInput, onValueChange = { tmdbKeyInput = it },
                        label = { Text("API key (v3)", color = Color.Gray) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { TmdbRepository.setApiKey(context, tmdbKeyInput); showTmdbKeyDialog = false }) { Text("Guardar", color = AccentPurple) } },
            dismissButton = { TextButton(onClick = { showTmdbKeyDialog = false }) { Text("Cancelar", color = Color.Gray) } },
            containerColor = SurfaceDark
        )
    }
}

private enum class IptvTab { INICIO, PELICULAS, SERIES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IptvBrowseScreen(
    sourceName: String,
    playlist: ParsedM3uPlaylist,
    repository: PlayerRepository,
    onPlayMovie: (M3uMovie) -> Unit,
    onPlayDirect: (url: String, title: String) -> Unit,
    onOpenSeries: (M3uSeriesGroup) -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(IptvTab.INICIO) }
    // ✅ Cada vez que se vuelve a tocar "Inicio" (aunque ya esté seleccionado), este contador cambia
    // y fuerza a que la fila de "Inicio" se vuelva a mezclar (intercalar películas/series).
    var homeVisitId by remember { mutableStateOf(0) }
    var detailMovie by remember { mutableStateOf<M3uMovie?>(null) }
    var detailSeries by remember { mutableStateOf<M3uSeriesGroup?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(sourceName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Color.White) } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = SurfaceDark.copy(alpha = 0.9f))
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SurfaceDark) {
                NavigationBarItem(
                    selected = selectedTab == IptvTab.INICIO,
                    onClick = { if (selectedTab == IptvTab.INICIO) homeVisitId++; selectedTab = IptvTab.INICIO },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Inicio", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = AccentPurple, selectedTextColor = AccentPurple, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray, indicatorColor = AccentPurple.copy(alpha = 0.15f))
                )
                NavigationBarItem(
                    selected = selectedTab == IptvTab.PELICULAS,
                    onClick = { selectedTab = IptvTab.PELICULAS },
                    icon = { Icon(Icons.Default.Movie, contentDescription = null) },
                    label = { Text("Películas", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = AccentPurple, selectedTextColor = AccentPurple, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray, indicatorColor = AccentPurple.copy(alpha = 0.15f))
                )
                NavigationBarItem(
                    selected = selectedTab == IptvTab.SERIES,
                    onClick = { selectedTab = IptvTab.SERIES },
                    icon = { Icon(Icons.Default.Theaters, contentDescription = null) },
                    label = { Text("Series", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = AccentPurple, selectedTextColor = AccentPurple, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray, indicatorColor = AccentPurple.copy(alpha = 0.15f))
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ✅ FIX scroll: al envolver cada pestaña en key(...), Compose descarta por completo el
            // LazyListState de la pestaña anterior al salir. Al volver, se crea uno nuevo desde cero
            // (arriba del todo), en vez de recordar dónde te quedaste.
            when (selectedTab) {
                IptvTab.INICIO -> key(homeVisitId) {
                    InicioTab(
                        playlist = playlist, repository = repository, homeVisitId = homeVisitId,
                        onOpenMovie = { detailMovie = it }, onOpenSeries = { detailSeries = it }, onPlayDirect = onPlayDirect
                    )
                }
                IptvTab.PELICULAS -> key(selectedTab) {
                    PeliculasTab(movies = playlist.movies, categories = playlist.categories, onOpenMovie = { detailMovie = it })
                }
                IptvTab.SERIES -> key(selectedTab) {
                    SeriesTab(series = playlist.series, categories = playlist.categories, onOpenSeries = { detailSeries = it })
                }
            }
        }
    }

    detailMovie?.let { m ->
        MediaDetailDialog(title = m.entry.name, fallbackImageUrl = m.entry.logoUrl, tmdbId = m.entry.tmdbId, isTv = false,
            onDismiss = { detailMovie = null }, primaryLabel = "Reproducir",
            onPrimary = { onPlayMovie(m); detailMovie = null })
    }
    detailSeries?.let { s ->
        MediaDetailDialog(title = s.title, fallbackImageUrl = s.logoUrl, tmdbId = s.tmdbId, isTv = true, extraInfo = "${s.seasonNumbers.size} temporada(s) — según la lista M3U",
            onDismiss = { detailSeries = null }, primaryLabel = "Ver capítulos",
            onPrimary = { onOpenSeries(s); detailSeries = null })
    }
}

/** Pestaña "Inicio": banner destacado + continuar viendo + carruseles, con orden que se vuelve a mezclar en cada visita. */
@Composable
private fun InicioTab(
    playlist: ParsedM3uPlaylist,
    repository: PlayerRepository,
    homeVisitId: Int,
    onOpenMovie: (M3uMovie) -> Unit,
    onOpenSeries: (M3uSeriesGroup) -> Unit,
    onPlayDirect: (url: String, title: String) -> Unit
) {
    val history by repository.historyList.collectAsState(initial = emptyList())
    val continueWatching = remember(history) {
        history.filter { it.durationMs > 0 && it.positionMs in 5000 until (it.durationMs - 15000) }
            .sortedByDescending { it.timestamp }.take(15)
    }
    // ✅ Destacado + orden de categorías: se vuelven a sortear cada vez que cambia homeVisitId
    // (o sea, cada vez que tocas "Inicio" de nuevo), así el inicio se ve distinto al volver.
    val shuffledCategories = remember(homeVisitId, playlist) { playlist.categories.shuffled() }
    val featured = remember(homeVisitId, playlist) {
        (playlist.series.map { it to true } + playlist.movies.map { it to false }).randomOrNull()
    }
    // ✅ FIX: este cálculo se hace ACÁ (dentro del cuerpo @Composable de InicioTab), nunca dentro del
    // LazyColumn { ... } de más abajo. El bloque de un LazyColumn/LazyRow es un DSL (LazyListScope),
    // no un contexto @Composable normal, así que remember()/otras funciones @Composable no pueden
    // llamarse ahí afuera de un item{} — eso era justo lo que rompía la compilación.
    val categoryRows = remember(homeVisitId, playlist, shuffledCategories) {
        shuffledCategories.mapNotNull { cat ->
            val catSeries = playlist.series.filter { it.groupTitle == cat }
            val catMovies = playlist.movies.filter { it.entry.groupTitle == cat }
            if (catSeries.isEmpty() && catMovies.isEmpty()) return@mapNotNull null
            val a = catSeries.shuffled(); val b = catMovies.shuffled()
            val interleaved = mutableListOf<Pair<Any, Boolean>>()
            val max = maxOf(a.size, b.size)
            for (i in 0 until max) { a.getOrNull(i)?.let { interleaved.add(it to true) }; b.getOrNull(i)?.let { interleaved.add(it to false) } }
            Triple(cat, catSeries.size + catMovies.size, interleaved)
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (featured != null) {
            item {
                FeaturedBanner(
                    title = if (featured.second) (featured.first as M3uSeriesGroup).title else (featured.first as M3uMovie).entry.name,
                    tmdbId = if (featured.second) (featured.first as M3uSeriesGroup).tmdbId else (featured.first as M3uMovie).entry.tmdbId,
                    fallbackImageUrl = if (featured.second) (featured.first as M3uSeriesGroup).logoUrl else (featured.first as M3uMovie).entry.logoUrl,
                    isTv = featured.second,
                    onVerAhora = { if (featured.second) onOpenSeries(featured.first as M3uSeriesGroup) else onOpenMovie(featured.first as M3uMovie) }
                )
            }
        }
        if (continueWatching.isNotEmpty()) {
            item { SectionHeader(icon = Icons.Default.PlayCircle, title = "Continuar viendo") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(continueWatching) { h ->
                        ContinueWatchingCard(title = h.title, progress = (h.positionMs.toFloat() / h.durationMs.toFloat()).coerceIn(0f, 1f), onClick = { onPlayDirect(h.url, h.title) })
                    }
                }
            }
        }
        shuffledCategories.forEach { cat ->
            val row = categoryRows.firstOrNull { it.first == cat } ?: return@forEach
            val (_, totalCount, interleaved) = row
            item { SectionHeader(icon = Icons.Default.LocalMovies, title = "$cat ($totalCount)") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(interleaved) { (item, isTv) ->
                        if (isTv) { val s = item as M3uSeriesGroup; PosterCard(title = s.title, fallbackLogoUrl = s.logoUrl, tmdbId = s.tmdbId, badge = "${s.seasonNumbers.size} temp.", isTv = true, onClick = { onOpenSeries(s) }) }
                        else { val m = item as M3uMovie; PosterCard(title = m.entry.name, fallbackLogoUrl = m.entry.logoUrl, tmdbId = m.entry.tmdbId, badge = null, isTv = false, onClick = { onOpenMovie(m) }) }
                    }
                }
            }
        }
    }
}

/** Pestaña "Películas": todas las películas en grilla, con su propio filtro de categoría. */
@Composable
private fun PeliculasTab(movies: List<M3uMovie>, categories: List<String>, onOpenMovie: (M3uMovie) -> Unit) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val filtered = remember(selectedCategory, movies) { movies.filter { selectedCategory == null || it.entry.groupTitle == selectedCategory } }
    val gridState = rememberLazyGridState() // recreado desde cero cada vez que se vuelve a esta pestaña

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = selectedCategory == null, onClick = { selectedCategory = null }, label = { Text("Todo") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentPurple, selectedLabelColor = Color.White, labelColor = Color.LightGray)) }
            items(categories) { cat -> FilterChip(selected = selectedCategory == cat, onClick = { selectedCategory = cat }, label = { Text(cat, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentPurple, selectedLabelColor = Color.White, labelColor = Color.LightGray)) }
        }
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("Sin películas en esta categoría.", color = Color.Gray, fontSize = 12.sp) }
        } else {
            LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(filtered) { m -> PosterCard(title = m.entry.name, fallbackLogoUrl = m.entry.logoUrl, tmdbId = m.entry.tmdbId, badge = null, isTv = false, onClick = { onOpenMovie(m) }) }
            }
        }
    }
}

/** Pestaña "Series": todas las series en grilla, con su propio filtro de categoría. */
@Composable
private fun SeriesTab(series: List<M3uSeriesGroup>, categories: List<String>, onOpenSeries: (M3uSeriesGroup) -> Unit) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val filtered = remember(selectedCategory, series) { series.filter { selectedCategory == null || it.groupTitle == selectedCategory } }
    val gridState = rememberLazyGridState()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = selectedCategory == null, onClick = { selectedCategory = null }, label = { Text("Todo") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentPurple, selectedLabelColor = Color.White, labelColor = Color.LightGray)) }
            items(categories) { cat -> FilterChip(selected = selectedCategory == cat, onClick = { selectedCategory = cat }, label = { Text(cat, fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentPurple, selectedLabelColor = Color.White, labelColor = Color.LightGray)) }
        }
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("Sin series en esta categoría.", color = Color.Gray, fontSize = 12.sp) }
        } else {
            LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(filtered) { s -> PosterCard(title = s.title, fallbackLogoUrl = s.logoUrl, tmdbId = s.tmdbId, badge = "${s.seasonNumbers.size} temp.", isTv = true, onClick = { onOpenSeries(s) }) }
            }
        }
    }
}

/** Banner destacado tipo "Película Destacada" (MiCine) con backdrop real de TMDB si hay API key. */
@Composable
private fun FeaturedBanner(title: String, tmdbId: String?, fallbackImageUrl: String?, isTv: Boolean, onVerAhora: () -> Unit) {
    val context = LocalContext.current
    var info by remember(title, tmdbId) { mutableStateOf<TmdbInfo?>(null) }
    LaunchedEffect(title, tmdbId) { info = TmdbRepository.lookup(context, title, tmdbId, isTv) }
    val imageUrl = info?.backdropUrl ?: info?.posterUrl ?: fallbackImageUrl

    Box(modifier = Modifier.fillMaxWidth().height(230.dp)) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(model = imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(modifier = Modifier.fillMaxSize().background(CardDark))
        }
        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, SurfaceDark), startY = 60f)))
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AccentPurple.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(if (isTv) "✦ SERIE DESTACADA" else "✦ PELÍCULA DESTACADA", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(info?.title ?: title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!info?.overview.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(info!!.overview, color = Color.LightGray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.85f))
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onVerAhora, colors = ButtonDefaults.buttonColors(containerColor = AccentPurple), shape = RoundedCornerShape(10.dp), modifier = Modifier.height(38.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("Ver ahora", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(title: String, progress: Float, onClick: () -> Unit) {
    Column(modifier = Modifier.width(150.dp).clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(CardDark), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(30.dp))
            LinearProgressIndicator(progress = { progress }, color = AccentPurple, trackColor = Color.White.copy(alpha = 0.2f), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** Grilla simple de carátulas (versión funcional; el look final tipo carrusel MiCine llega en la fase de rediseño). */
@Composable
private fun PosterCard(title: String, fallbackLogoUrl: String?, tmdbId: String?, badge: String?, isTv: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    // ✅ TMDB solo aporta metadata (carátula/año); nunca decide temporadas/episodios.
    var tmdbInfo by remember(title, tmdbId) { mutableStateOf<TmdbInfo?>(null) }
    LaunchedEffect(title, tmdbId) {
        tmdbInfo = TmdbRepository.lookup(context, title, tmdbId, isTv)
    }
    val posterUrl = tmdbInfo?.posterUrl ?: fallbackLogoUrl

    Column(modifier = Modifier.width(120.dp).clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(CardDark)) {
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(model = posterUrl, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.Movie, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.align(Alignment.Center).size(36.dp))
            }
            if (tmdbInfo != null && tmdbInfo!!.rating > 0) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                    Text("★ ${"%.1f".format(tmdbInfo!!.rating)}", color = Color(0xFFFFC107), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (badge != null) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                    Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(tmdbInfo?.title ?: title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Diálogo de detalle tipo ficha: carátula, año, rating, géneros y sinopsis (todo desde TMDB si hay API key). */
@Composable
private fun MediaDetailDialog(
    title: String,
    fallbackImageUrl: String?,
    tmdbId: String?,
    isTv: Boolean,
    extraInfo: String? = null,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var info by remember(title, tmdbId) { mutableStateOf<TmdbInfo?>(null) }
    var loaded by remember(title, tmdbId) { mutableStateOf(false) }
    LaunchedEffect(title, tmdbId) {
        info = TmdbRepository.lookup(context, title, tmdbId, isTv)
        loaded = true
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp).background(CardDark)) {
                    val imgUrl = info?.backdropUrl ?: info?.posterUrl ?: fallbackImageUrl
                    if (!imgUrl.isNullOrBlank()) {
                        AsyncImage(model = imgUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, SurfaceDark))))
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                    }
                }
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(info?.title ?: title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!info?.year.isNullOrBlank()) { Text(info!!.year, color = Color.Gray, fontSize = 12.sp); Spacer(Modifier.width(10.dp)) }
                        if ((info?.rating ?: 0.0) > 0) { Text("★ ${"%.1f".format(info!!.rating)}", color = Color(0xFFFFC107), fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(10.dp)) }
                        if (!info?.genres.isNullOrEmpty()) { Text(info!!.genres.joinToString(" • "), color = Color(0xFF7C3AED), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (extraInfo != null) { Spacer(Modifier.height(4.dp)); Text(extraInfo, color = Color.Gray, fontSize = 11.sp) }
                    Spacer(Modifier.height(10.dp))
                    if (!loaded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = AccentPurple, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp)); Text("Buscando en TMDB...", color = Color.Gray, fontSize = 11.sp)
                        }
                    } else if (info?.overview.isNullOrBlank()) {
                        Text(
                            if (TmdbRepository.getApiKey(context).isBlank()) "Configura tu API key de TMDB (ícono de llave) para ver sinopsis y carátulas reales."
                            else "Sin sinopsis disponible para este título.",
                            color = Color.Gray, fontSize = 12.sp, lineHeight = 17.sp
                        )
                    } else {
                        Text(info!!.overview, color = Color.LightGray, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentPurple), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(primaryLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesDetailScreen(
    series: M3uSeriesGroup,
    onPlayEpisode: (season: Int, indexInSeason: Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedSeason by remember { mutableStateOf(series.seasonNumbers.firstOrNull() ?: 1) }
    val episodes = series.seasons[selectedSeason].orEmpty()
    var tmdbInfo by remember(series.title, series.tmdbId) { mutableStateOf<TmdbInfo?>(null) }
    LaunchedEffect(series.title, series.tmdbId) { tmdbInfo = TmdbRepository.lookup(context, series.title, series.tmdbId, isTv = true) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(series.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Color.White) } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = SurfaceDark.copy(alpha = 0.9f))
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ✅ Ficha TMDB (solo metadata: sinopsis/año/género) — las temporadas de abajo son las del M3U
            if (tmdbInfo != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (tmdbInfo!!.year.isNotBlank()) { Text(tmdbInfo!!.year, color = Color.Gray, fontSize = 12.sp); Spacer(Modifier.width(8.dp)) }
                        if (tmdbInfo!!.rating > 0) { Text("★ ${"%.1f".format(tmdbInfo!!.rating)}", color = Color(0xFFFFC107), fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)) }
                        if (tmdbInfo!!.genres.isNotEmpty()) Text(tmdbInfo!!.genres.joinToString(" • "), color = AccentPurple, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (tmdbInfo!!.overview.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(tmdbInfo!!.overview, color = Color.LightGray, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // ✅ Las temporadas mostradas son EXACTAMENTE las que trae la lista M3U (aunque un catálogo
            // externo como TMDB agrupe todo en una sola temporada, aquí se respeta la lista original).
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(series.seasonNumbers) { season ->
                    FilterChip(
                        selected = selectedSeason == season, onClick = { selectedSeason = season },
                        label = { Text("Temporada $season", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentPurple, selectedLabelColor = Color.White, labelColor = Color.LightGray)
                    )
                }
            }
            LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(episodes.size) { idx ->
                    val ep = episodes[idx]
                    Column(modifier = Modifier.clickable { onPlayEpisode(selectedSeason, idx) }) {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(CardDark), contentAlignment = Alignment.Center) {
                            if (!ep.entry.logoUrl.isNullOrBlank()) {
                                AsyncImage(model = ep.entry.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Icon(Icons.Default.PlayCircle, contentDescription = "Reproducir", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("E${ep.episode}. ${ep.episodeLabel}", color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
