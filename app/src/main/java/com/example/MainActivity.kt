@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.effect.RgbMatrix
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.material3.LocalContentColor
import com.example.data.HistoryEntry
import com.example.data.PlayerDatabase
import com.example.data.PlayerRepository
import com.example.data.SavedPlaylist
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class AspectScale(val displayName: String, val modeValue: Int) {
    ORIGINAL("Original (Fit)", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    STRETCH("Estirar (Fill)", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom (Crop)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    CROP_FILL("Sin Franjas + Llenar", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    CUSTOM_XY("Escala Personalizada X/Y", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    RATIO_16_9("16:9 Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    RATIO_4_3("4:3 Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    RATIO_21_9("21:9 Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL)
}

// ✅ NUEVO: Filtros de imagen en tiempo real (aplicados vía media3-effect, sin ayuda de hardware)
// contrast/brightness/saturation en rango aproximado -1f..1f relativos al valor neutro
enum class VideoFilter(
    val displayName: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val saturation: Float,   // 1f = normal, >1 más vívido, <1 más desaturado
    val contrast: Float,     // 0f = normal
    val warmth: Float        // -1f frío/azulado .. 1f cálido/dorado (luz de día)
) {
    NONE("Original", Icons.Default.Movie, 1f, 0f, 0f),
    VIVID("Vívido", Icons.Default.Palette, 1.35f, 0.12f, 0f),
    DAYLIGHT("Luz de día", Icons.Default.WbSunny, 1.1f, 0.05f, 0.28f),
    COOL("Frío / Cine", Icons.Default.AcUnit, 0.95f, 0.08f, -0.22f),
    WARM("Cálido", Icons.Default.WbIncandescent, 1.05f, 0f, 0.35f),
    BW("Blanco y negro", Icons.Default.FilterBAndW, 0f, 0.1f, 0f)
}

data class MediaTrackOption(
    val group: Tracks.Group,
    val trackIndex: Int,
    val displayName: String,
    val id: String,
    val isSelected: Boolean
)

class MainActivity : ComponentActivity() {

    private lateinit var repository: PlayerRepository
    private var exoPlayer: ExoPlayer? = null
    private var currentVideoUrl by mutableStateOf("")
    private var currentVideoTitle by mutableStateOf("")
    private var isPlayingVideo by mutableStateOf(false)
    private var activePlaylistUrls = mutableStateListOf<String>()
    private var activePlaylistTitles = mutableStateListOf<String>()
    private var activePlaylistIndex by mutableStateOf(0)
    private var isParsingM3u by mutableStateOf(false)
    private var m3uParsingStatus by mutableStateOf("")
    // ✅ NUEVO: modo de la app — null = aún no se eligió, se pregunta al abrir por primera vez
    private var appMode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = PlayerDatabase.getDatabase(this)
        repository = PlayerRepository(db.playerDao())
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        appMode = prefs.getString("app_mode", null)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                val gradientBrush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F111A), Color(0xFF08090D))
                )
                Surface(
                    modifier = Modifier.fillMaxSize().background(gradientBrush),
                    color = Color.Transparent
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isPlayingVideo) {
                            VideoPlayerScreen(
                                videoUrl = currentVideoUrl,
                                videoTitle = currentVideoTitle,
                                onExit = { stopAndSaveHistory() },
                                repository = repository,
                                playListUrls = activePlaylistUrls,
                                playListTitles = activePlaylistTitles,
                                currentIndex = activePlaylistIndex,
                                onIndexChanged = { idx ->
                                    if (idx in 0 until activePlaylistUrls.size) {
                                        activePlaylistIndex = idx
                                        currentVideoUrl = activePlaylistUrls[idx]
                                        currentVideoTitle = activePlaylistTitles[idx]
                                    }
                                }
                            )
                        } else {
                            when (appMode) {
                                null -> ModeSelectionScreen(
                                    onSelectLinks = { appMode = "links" },
                                    onSelectIptv = { appMode = "iptv" }
                                )
                                "iptv" -> IptvRoot(
                                    repository = repository,
                                    onPlayList = { urls, titles, index -> playParsedList(urls, titles, index) },
                                    onChangeMode = { appMode = null },
                                )
                                else -> DashboardScreen(
                                    repository = repository,
                                    onChangeMode = { appMode = null },
                                    onPlayVideo = { url, title, playlist, index ->
                                        playUrlCheckingM3u(url, title, playlist, index)
                                    }
                                )
                            }
                        }

                        if (isParsingM3u) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.8f))
                                    .clickable(enabled = false) {},
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1025)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(color = Color(0xFF7C3AED), strokeWidth = 4.dp, modifier = Modifier.size(50.dp))
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(text = m3uParsingStatus, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(text = "Extrayendo canales, transmisiones y títulos...", color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri = intent.dataString ?: intent.getStringExtra("video_url")
        if (!uri.isNullOrEmpty()) {
            val title = intent.getStringExtra("title") ?: "Enlace Externo"
            val streamList = intent.getStringArrayListExtra("playlist_urls")
            val titleList = intent.getStringArrayListExtra("playlist_titles")
            val index = intent.getIntExtra("playlist_index", 0)
            activePlaylistUrls.clear()
            activePlaylistTitles.clear()
            if (streamList != null && streamList.isNotEmpty()) {
                activePlaylistUrls.addAll(streamList)
                if (titleList != null && titleList.size == streamList.size) {
                    activePlaylistTitles.addAll(titleList)
                } else {
                    activePlaylistTitles.addAll(streamList.mapIndexed { idx, _ -> "Episodio ${idx + 1}" })
                }
                activePlaylistIndex = if (index in 0 until streamList.size) index else 0
                currentVideoUrl = activePlaylistUrls[activePlaylistIndex]
                currentVideoTitle = activePlaylistTitles[activePlaylistIndex]
                isPlayingVideo = true
                Toast.makeText(this, "Reproduciendo stream: $title", Toast.LENGTH_SHORT).show()
            } else {
                playUrlCheckingM3u(uri, title)
            }
        }
    }

    private fun playUrlCheckingM3u(url: String, title: String, playlist: SavedPlaylist? = null, index: Int = 0) {
        val trimmedUrl = url.trim()
        if (isM3uUrl(trimmedUrl)) {
            isParsingM3u = true
            m3uParsingStatus = "Procesando enlace/lista..."
            lifecycleScope.launch {
                try {
                    val items = parseM3uFromLocation(this@MainActivity, trimmedUrl)
                    if (items.isNotEmpty()) {
                        activePlaylistUrls.clear()
                        activePlaylistTitles.clear()
                        items.forEach { (vUrl, vTitle) ->
                            activePlaylistUrls.add(vUrl)
                            activePlaylistTitles.add(vTitle)
                        }
                        activePlaylistIndex = 0
                        currentVideoUrl = activePlaylistUrls[0]
                        currentVideoTitle = activePlaylistTitles[0]
                        isPlayingVideo = true
                        Toast.makeText(this@MainActivity, "Lista M3U procesada: ${items.size} transmisiones cargadas", Toast.LENGTH_LONG).show()
                    } else {
                        playDirectUrl(trimmedUrl, title, playlist, index)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    playDirectUrl(trimmedUrl, title, playlist, index)
                } finally {
                    isParsingM3u = false
                }
            }
        } else {
            playDirectUrl(trimmedUrl, title, playlist, index)
        }
    }

    private fun playDirectUrl(url: String, title: String, playlist: SavedPlaylist? = null, index: Int = 0) {
        activePlaylistUrls.clear()
        activePlaylistTitles.clear()
        if (playlist != null) {
            val listUrls = playlist.urlsJson.split("||")
            val listTitles = playlist.titlesJson.split("||")
            activePlaylistUrls.addAll(listUrls)
            activePlaylistTitles.addAll(listTitles)
            activePlaylistIndex = if (index in 0 until listUrls.size) index else 0
        } else {
            activePlaylistUrls.add(url)
            activePlaylistTitles.add(if (title.isEmpty()) "Video Stream" else title)
            activePlaylistIndex = 0
        }
        currentVideoUrl = activePlaylistUrls[activePlaylistIndex]
        currentVideoTitle = activePlaylistTitles[activePlaylistIndex]
        isPlayingVideo = true
    }

    private fun setAppModeFix(mode: String?) {
    appMode = mode
    getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().apply {
        if (mode == null) remove("app_mode") else putString("app_mode", mode)
    }.apply()
}

    // ✅ NUEVO: reproduce una lista ya armada (episodios de una serie IPTV o una sola película),
    // sin re-analizar el texto como URL — usa el MISMO reproductor y el mismo mecanismo de
    // "siguiente/anterior capítulo" que el modo Links.
    private fun playParsedList(urls: List<String>, titles: List<String>, index: Int) {
        if (urls.isEmpty()) return
        activePlaylistUrls.clear(); activePlaylistTitles.clear()
        activePlaylistUrls.addAll(urls); activePlaylistTitles.addAll(titles)
        activePlaylistIndex = index.coerceIn(0, urls.size - 1)
        currentVideoUrl = activePlaylistUrls[activePlaylistIndex]
        currentVideoTitle = activePlaylistTitles[activePlaylistIndex]
        isPlayingVideo = true
    }

    private fun isM3uUrl(url: String): Boolean {
        val cleanUrl = url.substringBefore("?").substringBefore("#")
        return cleanUrl.endsWith(".m3u", ignoreCase = true)
    }

    private suspend fun parseM3uFromLocation(context: Context, location: String): List<Pair<String, String>> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val result = mutableListOf<Pair<String, String>>()
            try {
                val inputStream = if (location.startsWith("content://") || location.startsWith("file://")) {
                    context.contentResolver.openInputStream(Uri.parse(location))
                } else {
                    val connection = URL(location).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) connection.inputStream else null
                }
                inputStream?.use { stream ->
                    stream.bufferedReader().useLines { lines ->
                        var currentTitle = ""
                        var isHlsVideo = false
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("#EXT-X-STREAM-INF") ||
                                trimmed.startsWith("#EXT-X-TARGETDURATION") ||
                                trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                                isHlsVideo = true
                                break
                            }
                            if (trimmed.startsWith("#EXTINF:")) {
                                val lastComma = trimmed.lastIndexOf(',')
                                currentTitle = if (lastComma != -1 && lastComma < trimmed.length - 1)
                                    trimmed.substring(lastComma + 1).trim() else ""
                            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                val displayTitle = if (currentTitle.isEmpty()) "Reproducción ${result.size + 1}" else currentTitle
                                result.add(Pair(trimmed, displayTitle))
                                currentTitle = ""
                            }
                        }
                        if (isHlsVideo) result.clear()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            result
        }
    }

    private fun stopAndSaveHistory() {
        isPlayingVideo = false
        currentVideoUrl = ""
        currentVideoTitle = ""
        activePlaylistUrls.clear()
        activePlaylistTitles.clear()
        activePlaylistIndex = 0
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

// ==========================================
// 1. DASHBOARD COMPOSABLES
// ==========================================

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    repository: PlayerRepository,
    onChangeMode: () -> Unit,
    onPlayVideo: (url: String, title: String, playlist: SavedPlaylist?, index: Int) -> Unit
) {
    val context = LocalContext.current
    var inputUrl by remember { mutableStateOf("") }
    var inputTitle by remember { mutableStateOf("") }
    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var playlistEditorName by remember { mutableStateOf("") }
    var playlistEditorUrlsStr by remember { mutableStateOf("") }
    var playlistEditorTitlesStr by remember { mutableStateOf("") }
    val historyState = repository.historyList.collectAsState(initial = emptyList())
    val playlistState = repository.playlistList.collectAsState(initial = emptyList())
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Logo", tint = Color(0xFF7C3AED), modifier = Modifier.size(32.dp).padding(end = 6.dp))
                        Text(text = "XSTREAM PLAYER", fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, fontSize = 20.sp, letterSpacing = 1.5.sp, color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color(0xFF120E1F).copy(alpha = 0.9f), titleContentColor = Color.White),
                actions = { IconButton(onClick = onChangeMode) { Icon(Icons.Default.SwapHoriz, contentDescription = "Cambiar modo", tint = Color.Gray) } }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF120E1F), tonalElevation = 8.dp) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir") }, label = { Text("Reproductor", fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF7C3AED), selectedTextColor = Color(0xFF7C3AED), unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray, indicatorColor = Color(0xFF2D1B69)))
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.List, contentDescription = "Mis Listas") }, label = { Text("Playlists Series", fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF7C3AED), selectedTextColor = Color(0xFF7C3AED), unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray, indicatorColor = Color(0xFF2D1B69)))
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Info, contentDescription = "Ayuda") }, label = { Text("Kodular Help", fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF7C3AED), selectedTextColor = Color(0xFF7C3AED), unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray, indicatorColor = Color(0xFF2D1B69)))
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(12.dp))
            when (selectedTab) {
                0 -> {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1025)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("launcher_card")) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(text = "REPRODUCIR URL EN VIVO", fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp, color = Color(0xFF7C3AED), modifier = Modifier.padding(bottom = 12.dp))
                                    OutlinedTextField(value = inputUrl, onValueChange = { inputUrl = it }, label = { Text("Enlace streaming (HLS m3u8, MKV, MP4, etc.)", color = Color.Gray) }, placeholder = { Text("https://url.com/video.m3u8", color = Color.DarkGray) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("url_input"), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF7C3AED), unfocusedBorderColor = Color(0xFF2E3146), focusedTextColor = Color.White, unfocusedTextColor = Color.White), leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Gray) })
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(value = inputTitle, onValueChange = { inputTitle = it }, label = { Text("Título / Nombre del video (opcional)", color = Color.Gray) }, placeholder = { Text("Ej: Capitulo 1", color = Color.DarkGray) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF7C3AED), unfocusedBorderColor = Color(0xFF2E3146), focusedTextColor = Color.White, unfocusedTextColor = Color.White), leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color.Gray) })
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(onClick = { if (inputUrl.trim().isEmpty()) Toast.makeText(context, "Ingresa un enlace válido m3u8 o mkv", Toast.LENGTH_SHORT).show() else onPlayVideo(inputUrl.trim(), inputTitle.trim(), null, 0) }, modifier = Modifier.fillMaxWidth().height(50.dp).testTag("submit_button"), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED), contentColor = Color.White), shape = RoundedCornerShape(12.dp)) { Text(text = "REPRODUCIR AHORA", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                                }
                            }
                        }
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "HISTORIAL Y RESUMEN", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.LightGray)
                                }
                                if (historyState.value.isNotEmpty()) {
                                    Text(text = "Limpiar Todo", color = Color(0xFFFF5555), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { val scope = (context as MainActivity).lifecycleScope; scope.launch { repository.clearHistory() }; Toast.makeText(context, "Historial vaciado", Toast.LENGTH_SHORT).show() })
                                }
                            }
                        }
                        if (historyState.value.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(48.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(text = "No hay historial de reproducción.\nLos enlaces que reproduzcas se guardarán automáticamente aquí con su progreso.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
                                    }
                                }
                            }
                        } else {
                            items(historyState.value) { item ->
                                HistoryRowItem(entry = item, onPlay = { onPlayVideo(item.url, item.title, null, 0) }, onDelete = { val scope = (context as MainActivity).lifecycleScope; scope.launch { repository.deleteHistory(item.url) } })
                            }
                        }
                    }
                }
                1 -> {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1025)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = "GESTIÓN DE PLAYLISTS", fontWeight = FontWeight.Bold, color = Color(0xFF9333EA), fontSize = 14.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(text = "Crea listas de capítulos para tus series. El reproductor tendrá botones Siguiente / Anterior y avanzará automáticamente.", color = Color.LightGray, fontSize = 12.sp, lineHeight = 16.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = { showAddPlaylistDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED), contentColor = Color.White), shape = RoundedCornerShape(8.dp)) { Icon(Icons.Default.Add, contentDescription = "Crear") }
                                }
                            }
                        }
                        item { Text(text = "MIS PLAYLISTS", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.LightGray) }
                        if (playlistState.value.isEmpty()) {
                            item { Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { Text(text = "Aún no tienes listas guardadas. \nToca el botón '+' para agregar una nueva serie.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center) } }
                        } else {
                            items(playlistState.value) { playlist ->
                                PlaylistCardItem(playlist = playlist, onPlayItem = { index -> val urls = playlist.urlsJson.split("||"); val titles = playlist.titlesJson.split("||"); if (index in urls.indices) onPlayVideo(urls[index], titles[index], playlist, index) }, onDelete = { val scope = (context as MainActivity).lifecycleScope; scope.launch { repository.deletePlaylist(playlist.id) } })
                            }
                        }
                    }
                }
                2 -> {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1025)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = "CONEXIÓN DESDE KODULAR / APP INVENTOR", fontWeight = FontWeight.ExtraBold, color = Color(0xFF7C3AED), fontSize = 15.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
                                Text(text = "Para reemplazar VLC externo en tu App webview de Kodular, puedes iniciar nuestro reproductor mediante la acción de 'Activity-Starter'.", fontSize = 12.sp, color = Color.LightGray, lineHeight = 18.sp)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(text = "Configuración del Bloque ActivityStarter:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Spacer(modifier = Modifier.height(4.dp))
                                KodularParamRow(name = "Action", value = "android.intent.action.VIEW")
                                KodularParamRow(name = "ActivityPackage", value = "com.aistudio.hlsplayer.yvtrqz")
                                KodularParamRow(name = "ActivityClass", value = "com.example.MainActivity")
                                KodularParamRow(name = "DataUri", value = "https://tu-servidor.com/canal.m3u8")
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = "Parámetros Extra Opcionales:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Spacer(modifier = Modifier.height(6.dp))
                                KodularParamRow(name = "title", value = "Texto (Ej: 'Capítulo 1')")
                                KodularParamRow(name = "playlist_urls", value = "Lista de enlaces (ArrayList)")
                                KodularParamRow(name = "playlist_titles", value = "Lista de nombres (ArrayList)")
                                KodularParamRow(name = "playlist_index", value = "Número índice de inicio")
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (showAddPlaylistDialog) {
        AlertDialog(onDismissRequest = { showAddPlaylistDialog = false }, title = { Text("Nueva Serie / Lista de Capítulos", color = Color.White) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Guarda capítulos secuenciales para verlos de corrido.", fontSize = 11.sp, color = Color.Gray)
                    OutlinedTextField(value = playlistEditorName, onValueChange = { playlistEditorName = it }, label = { Text("Nombre de la Serie / Anime") }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF7C3AED), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    OutlinedTextField(value = playlistEditorUrlsStr, onValueChange = { playlistEditorUrlsStr = it }, label = { Text("URLs de Capítulos (Separar por comas ,)") }, placeholder = { Text("https://url.com/1.mp4, https://url.com/2.mp4") }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF7C3AED), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    OutlinedTextField(value = playlistEditorTitlesStr, onValueChange = { playlistEditorTitlesStr = it }, label = { Text("Nombres de Capítulos (Separar por comas ,)") }, placeholder = { Text("Capítulo 1, Capítulo 2") }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF7C3AED), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = playlistEditorName.trim()
                    val cleanedUrls = playlistEditorUrlsStr.trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    var cleanedTitles = playlistEditorTitlesStr.trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (name.isEmpty() || cleanedUrls.isEmpty()) { Toast.makeText(context, "Llena al menos el Nombre y una URL", Toast.LENGTH_SHORT).show() }
                    else {
                        if (cleanedTitles.size != cleanedUrls.size) { cleanedTitles = cleanedUrls.mapIndexed { index, _ -> if (index in cleanedTitles.indices) cleanedTitles[index] else "Capítulo ${index + 1}" } }
                        val scope = (context as MainActivity).lifecycleScope
                        scope.launch { repository.savePlaylist(name, cleanedUrls, cleanedTitles); Toast.makeText(context, "Playlist guardada", Toast.LENGTH_SHORT).show(); showAddPlaylistDialog = false; playlistEditorName = ""; playlistEditorUrlsStr = ""; playlistEditorTitlesStr = "" }
                    }
                }) { Text("Guardar", color = Color(0xFF7C3AED)) }
            },
            dismissButton = { TextButton(onClick = { showAddPlaylistDialog = false }) { Text("Cancelar", color = Color.Gray) } },
            containerColor = Color(0xFF120E1F))
    }
}

@Composable
fun KodularParamRow(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = name, fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(110.dp))
        Text(text = value, color = Color(0xFF9333EA), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HistoryRowItem(entry: HistoryEntry, onPlay: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onPlay() }, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF150D2A))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val isHls = entry.url.contains(".m3u8", ignoreCase = true)
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isHls) Color(0xFF7C3AED) else Color(0xFFFFB300)))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = entry.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = entry.url, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(imageVector = Icons.Default.Delete, contentDescription = "Borrar", tint = Color.Gray, modifier = Modifier.size(16.dp)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (entry.durationMs > 0) {
                val progress = entry.positionMs.toFloat() / entry.durationMs.toFloat()
                val percentage = (progress * 100).toInt().coerceIn(0, 100)
                Text(text = "Visto al $percentage% - ${formatTime(entry.positionMs)} / ${formatTime(entry.durationMs)}", fontSize = 10.sp, color = Color.LightGray)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFF7C3AED), trackColor = Color(0xFF252A3C))
            } else {
                Text(text = "Transmisión en vivo", fontSize = 10.sp, color = Color(0xFF7C3AED))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistCardItem(playlist: SavedPlaylist, onPlayItem: (index: Int) -> Unit, onDelete: () -> Unit) {
    val urls = playlist.urlsJson.split("||")
    val titles = playlist.titlesJson.split("||")
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF150D2A))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF7C3AED), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = playlist.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(imageVector = Icons.Default.Delete, contentDescription = "Borrar", tint = Color.Gray, modifier = Modifier.size(16.dp)) }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "${urls.size} Capítulos configurados. Selecciona uno para iniciar:", fontSize = 11.sp, color = Color.LightGray)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                urls.indices.forEach { index ->
                    val title = if (index in titles.indices) titles[index] else "Cap ${index + 1}"
                    SuggestionChip(onClick = { onPlayItem(index) }, label = { Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold) }, colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0xFF2D1B69), labelColor = Color(0xFF9333EA)), border = null)
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}

// ==========================================
// 2. VIDEO PLAYER VIEW COMPOSABLES
// ==========================================

// ✅ NUEVO: construye una matriz de color lineal (saturación + temperatura) para el filtro elegido.
// Se aplica en tiempo real sobre cada frame vía media3-effect (sin depender de hardware TV).
@UnstableApi
private fun buildFilterMatrix(filter: VideoFilter): RgbMatrix {
    val s = filter.saturation
    val warmth = filter.warmth
    // Pesos de luminancia Rec.709 para no alterar el brillo al mover la saturación.
    val lumR = 0.2126f; val lumG = 0.7152f; val lumB = 0.0722f
    val rr = lumR * (1 - s) + s; val rg = lumG * (1 - s); val rb = lumB * (1 - s)
    val gr = lumR * (1 - s); val gg = lumG * (1 - s) + s; val gb = lumB * (1 - s)
    val br = lumR * (1 - s); val bg = lumG * (1 - s); val bb = lumB * (1 - s) + s
    val redScale = 1f + warmth * 0.22f
    val blueScale = 1f - warmth * 0.22f
    val matrix = floatArrayOf(
        rr * redScale, gr, br, 0f,
        rg, gg, bg, 0f,
        rb, gb, bb * blueScale, 0f,
        0f, 0f, 0f, 1f
    )
    return RgbMatrix { _, _ -> matrix }
}

// ✅ NUEVO: pide a la pantalla del celular usar la tasa de refresco elegida (60/90/120Hz...),
// siempre y cuando el hardware la soporte. No hace "TruMotion" (interpolación de frames),
// eso sí depende del chip del televisor y no se puede lograr solo con código.
private fun applyRefreshRate(activity: Activity?, rate: Float) {
    try {
        val display = if (Build.VERSION.SDK_INT >= 30) activity?.display
        else @Suppress("DEPRECATION") activity?.windowManager?.defaultDisplay
        val mode = display?.supportedModes?.minByOrNull { kotlin.math.abs(it.refreshRate - rate) }
        if (mode != null && activity != null) {
            val attrs = activity.window.attributes
            attrs.preferredDisplayModeId = mode.modeId
            activity.window.attributes = attrs
        }
    } catch (e: Exception) { e.printStackTrace() }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun VideoPlayerScreen(
    videoUrl: String,
    videoTitle: String,
    onExit: () -> Unit,
    repository: PlayerRepository,
    playListUrls: List<String>,
    playListTitles: List<String>,
    currentIndex: Int,
    onIndexChanged: (Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlayingState by remember { mutableStateOf(false) }
    var scaleMode by remember { mutableStateOf(AspectScale.ORIGINAL) }
    var cropFraction by remember { mutableStateOf(0.19f) }
    var cropFractionX by remember { mutableStateOf(0.19f) }
    var cropFractionY by remember { mutableStateOf(0f) }
    var showCropDialog by remember { mutableStateOf(false) }
    var showCustomXYDialog by remember { mutableStateOf(false) }
    var playPosition by remember { mutableStateOf(0L) }
    var totalDuration by remember { mutableStateOf(0L) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var externalSubtitleUri by remember { mutableStateOf<Uri?>(null) }
    var externalSubtitleName by remember { mutableStateOf("") }
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitlesDialog by remember { mutableStateOf(false) }
    val audioTracks = remember { mutableStateListOf<MediaTrackOption>() }
    val videoTracks = remember { mutableStateListOf<MediaTrackOption>() }
    val internalSubtitles = remember { mutableStateListOf<MediaTrackOption>() }
    var isHlsStream by remember { mutableStateOf(false) }
    // ✅ NUEVO: resolución/calidad realmente reproduciéndose ahora mismo
    var currentPlayingResLabel by remember { mutableStateOf("") }
    // ✅ NUEVO: filtro de imagen activo y diálogos de filtros/tasa de refresco
    var activeFilter by remember { mutableStateOf(VideoFilter.NONE) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showRefreshRateDialog by remember { mutableStateOf(false) }
    var availableRefreshRates by remember { mutableStateOf(listOf<Float>()) }
    var currentRefreshRate by remember { mutableStateOf(0f) }

    // ✅ FIX 1: Mantener pantalla encendida durante reproducción
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            externalSubtitleUri = uri
            externalSubtitleName = getFileNameFromUri(context, uri) ?: "srt_local.srt"
            Toast.makeText(context, "Subtítulo local cargado: $externalSubtitleName", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.window?.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            activity?.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
        // ✅ NUEVO: detectar tasas de refresco que soporta la pantalla del celular (60/90/120Hz, etc.)
        try {
            val display = if (Build.VERSION.SDK_INT >= 30) activity?.display
            else @Suppress("DEPRECATION") activity?.windowManager?.defaultDisplay
            availableRefreshRates = display?.supportedModes
                ?.map { it.refreshRate }
                ?.map { Math.round(it * 10) / 10f }
                ?.distinct()
                ?.sorted() ?: emptyList()
            currentRefreshRate = display?.refreshRate ?: 0f
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ✅ NUEVO: aplica en tiempo real el filtro de imagen elegido (Vívido, Luz de día, Frío, etc.)
    LaunchedEffect(player, activeFilter) {
        try {
            player?.setVideoEffects(
                if (activeFilter == VideoFilter.NONE) emptyList()
                else listOf(buildFilterMatrix(activeFilter))
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    LaunchedEffect(videoUrl, externalSubtitleUri) {
        errorMessage = null
        isBuffering = true
        player?.release()

        val savedEntry = repository.getHistoryForUrl(videoUrl)
        val resumePos = savedEntry?.positionMs ?: 0L

        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

        // ✅ FIX SUBTÍTULOS: DefaultDataSource.Factory maneja HTTP *y* file:// locales.
        // Con httpDataSourceFactory solo, ExoPlayer no puede leer el .srt/.vtt del caché (file://).
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val newPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
                val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(videoUrl))
                if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                    mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                // Subtítulo externo: copiar a caché y embeber en el MediaItem
                if (externalSubtitleUri != null) {
                    val subtitleMime = if (externalSubtitleName.endsWith(".vtt", ignoreCase = true))
                        MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
                    val ext = if (externalSubtitleName.endsWith(".vtt", ignoreCase = true)) ".vtt" else ".srt"
                    val tempSubFile = File(context.cacheDir, "subtitle_temp$ext")
                    try {
                        context.contentResolver.openInputStream(externalSubtitleUri!!)?.use { input ->
                            FileOutputStream(tempSubFile).use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(tempSubFile))
                        .setMimeType(subtitleMime)
                        .setLanguage("es")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                    mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
                }
                val mediaItem = mediaItemBuilder.build()
                setMediaItem(mediaItem)
                if (resumePos > 0) seekTo(resumePos)
                prepare()
            }

        player = newPlayer
        isPlayingState = true
        isHlsStream = videoUrl.contains(".m3u8", ignoreCase = true)

        newPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = (state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) totalDuration = newPlayer.duration
                if (state == Player.STATE_ENDED) {
                    if (playListUrls.size > 1 && currentIndex + 1 < playListUrls.size) { Toast.makeText(context, "Siguiente capítulo automático...", Toast.LENGTH_SHORT).show(); onIndexChanged(currentIndex + 1) }
                    else onExit()
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                parseVideoAndAudioTracks(tracks, audioTracks, videoTracks, internalSubtitles)
                // ✅ FIX: onVideoSizeChanged no siempre llega a tiempo en algunos streams; la pista de
                // video ya seleccionada es la fuente más confiable para el badge de calidad.
                videoTracks.firstOrNull { it.isSelected }?.let { selected ->
                    val format = selected.group.getTrackFormat(selected.trackIndex)
                    if (format.height > 0) currentPlayingResLabel = "${format.height}p"
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // ✅ NUEVO: calidad real en reproducción (importante con streams adaptativos, cambia solo)
                if (videoSize.height > 0) currentPlayingResLabel = "${videoSize.height}p"
            }
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val causeMsg = if (cause != null) " (${cause.javaClass.simpleName}: ${cause.message})" else ""
                errorMessage = "Error [${error.errorCode}]: " + (error.localizedMessage ?: "Error de origen") + causeMsg
                isBuffering = false
            }
        })
    }

    LaunchedEffect(player) {
        try {
            while (true) {
                delay(1000)
                player?.let { p ->
                    try {
                        if (p.isPlaying) { isPlayingState = true; if (!isDraggingSlider) playPosition = p.currentPosition; if (p.duration > 0) { repository.saveHistory(url = videoUrl, title = videoTitle, positionMs = p.currentPosition, durationMs = p.duration) } }
                        else { isPlayingState = false }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    LaunchedEffect(areControlsVisible) {
        if (areControlsVisible) { delay(5000); areControlsVisible = false }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player?.pause()
            else if (event == Lifecycle.Event.ON_DESTROY) { player?.release(); player = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.release(); player = null }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { areControlsVisible = !areControlsVisible }
    ) {
        if (player != null) {
            // ✅ FIX: antes solo se fijaba resizeMode dentro del `update` de AndroidView. En Compose,
            // si ese lambda no captura ninguna variable "inestable" nueva, a veces NO se vuelve a
            // ejecutar al cambiar el estado (el bug de "Estirar/Zoom no hacen nada"). Guardar la
            // referencia real de la vista y aplicarlo desde un LaunchedEffect elimina la duda:
            // se ejecuta SIEMPRE que cambien scaleMode/cropFraction, sin depender de esa reactividad.
            var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
            LaunchedEffect(scaleMode, cropFraction, cropFractionX, cropFractionY, playerViewRef) {
                playerViewRef?.resizeMode = when (scaleMode) {
                    AspectScale.CROP_FILL, AspectScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectScale.CUSTOM_XY, AspectScale.STRETCH, AspectScale.RATIO_16_9,
                    AspectScale.RATIO_4_3, AspectScale.RATIO_21_9 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                        setShowSubtitleButton(false)
                        subtitleView?.setStyle(
                            androidx.media3.ui.CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                android.graphics.Color.BLACK,
                                android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
                            )
                        )
                    }.also { playerViewRef = it }
                },
                update = { view ->
                    if (view.player != player) view.player = player
                    view.resizeMode = when (scaleMode) {
                        AspectScale.CROP_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectScale.CUSTOM_XY -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        AspectScale.STRETCH, AspectScale.RATIO_16_9,
                        AspectScale.RATIO_4_3, AspectScale.RATIO_21_9 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
                    .then(
                        when (scaleMode) {
                            AspectScale.CROP_FILL -> Modifier.graphicsLayer {
                                scaleX = 1f / (1f - cropFraction)
                                scaleY = 1f
                            }
                            AspectScale.CUSTOM_XY -> Modifier.graphicsLayer {
                                scaleX = 1f / (1f - cropFractionX)
                                scaleY = 1f / (1f - cropFractionY)
                            }
                            AspectScale.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
                            AspectScale.RATIO_4_3 -> Modifier.aspectRatio(4f / 3f)
                            AspectScale.RATIO_21_9 -> Modifier.aspectRatio(21f / 9f)
                            else -> Modifier.fillMaxSize()
                        }
                    )
            )
        }

        if (isBuffering) { CircularProgressIndicator(color = Color(0xFF7C3AED), modifier = Modifier.size(60.dp).align(Alignment.Center)) }

        if (errorMessage != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xCC200808)), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(24.dp).align(Alignment.Center)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, contentDescription = "Error", tint = Color.Red, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage!!, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { onExit() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Regresar al Panel", color = Color.White) }
                }
            }
        }

        AnimatedVisibility(visible = areControlsVisible && errorMessage == null, enter = fadeIn(animationSpec = tween(200)), exit = fadeOut(animationSpec = tween(200)), modifier = Modifier.fillMaxSize()) {
            // ✅ FIX: sin esto, IconButton usaba LocalContentColor por defecto (negro) para el ripple,
            // y ese "flash" negro sobre los círculos translúcidos se veía como una sombra fea al tocar.
            CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {

                // TOP BAR
                Row(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = { onExit() }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = videoTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (playListUrls.size > 1) {
                                    Text(text = "Episodio ${currentIndex + 1} de ${playListUrls.size}", color = Color(0xFF7C3AED), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                // ✅ NUEVO: badge con la calidad real que se está reproduciendo ahora mismo
                                if (currentPlayingResLabel.isNotEmpty()) {
                                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                        Text(text = currentPlayingResLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // ✅ NUEVO: filtros de imagen (Vívido, Luz de día, Frío, Cálido, B/N)
                        IconButton(onClick = { showFilterDialog = true }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)) {
                            Icon(Icons.Default.Palette, contentDescription = "Filtros de imagen", tint = if (activeFilter != VideoFilter.NONE) Color(0xFF7C3AED) else Color.White)
                        }
                        // ✅ NUEVO: tasa de refresco de pantalla (60/90/120Hz...)
                        if (availableRefreshRates.size > 1) {
                            IconButton(onClick = { showRefreshRateDialog = true }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)) {
                                Icon(Icons.Default.Speed, contentDescription = "Tasa de refresco", tint = Color.White)
                            }
                        }
                        // Botón aspecto — cicla modos y abre diálogo crop al llegar a CROP_FILL
                        IconButton(
                            onClick = {
                                val entries = AspectScale.values()
                                scaleMode = entries[(scaleMode.ordinal + 1) % entries.size]
                                if (scaleMode == AspectScale.CROP_FILL) {
                                    showCropDialog = true
                                } else if (scaleMode == AspectScale.CUSTOM_XY) {
                                    showCustomXYDialog = true
                                }
                                Toast.makeText(context, "Escala: ${scaleMode.displayName}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.AspectRatio, contentDescription = "Escala", tint = Color(0xFF7C3AED))
                        }
                        if (isHlsStream) {
                            IconButton(onClick = { showQualityDialog = true }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)) {
                                Icon(Icons.Default.Hd, contentDescription = "Calidad", tint = Color.White)
                            }
                        }
                        IconButton(onClick = { showAudioDialog = true }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)) {
                            Icon(Icons.Default.Headphones, contentDescription = "Audio", tint = Color.White)
                        }
                        IconButton(onClick = { showSubtitlesDialog = true }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)) {
                            Icon(Icons.Default.Subtitles, contentDescription = "Subtítulos", tint = Color.White)
                        }
                    }
                }

                // CENTER BUTTONS
                Row(modifier = Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    IconButton(
                        onClick = { player?.let { p -> val t = (p.currentPosition - 10000).coerceAtLeast(0); p.seekTo(t); playPosition = t } },
                        modifier = Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    ) { Icon(Icons.Default.Replay10, contentDescription = "-10s", tint = Color.White, modifier = Modifier.size(24.dp)) }

                    IconButton(
                        onClick = { player?.let { p -> if (p.isPlaying) { p.pause(); isPlayingState = false } else { p.play(); isPlayingState = true } } },
                        modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    ) { Icon(imageVector = if (isPlayingState) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pausa", tint = Color.White, modifier = Modifier.size(36.dp)) }

                    IconButton(
                        onClick = { player?.let { p -> val t = (p.currentPosition + 10000).coerceAtMost(p.duration); p.seekTo(t); playPosition = t } },
                        modifier = Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    ) { Icon(Icons.Default.Forward10, contentDescription = "+10s", tint = Color.White, modifier = Modifier.size(24.dp)) }
                }

                // BOTTOM BAR
                Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (playListUrls.size > 1) {
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onIndexChanged(currentIndex - 1) }, enabled = currentIndex > 0) { Text("|< ANTERIOR", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (currentIndex > 0) Color(0xFF7C3AED) else Color.DarkGray) }
                            Spacer(modifier = Modifier.width(24.dp))
                            Text(text = "Episodio ${currentIndex + 1} de ${playListUrls.size}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(24.dp))
                            TextButton(onClick = { onIndexChanged(currentIndex + 1) }, enabled = currentIndex + 1 < playListUrls.size) { Text("SIGUIENTE >|", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (currentIndex + 1 < playListUrls.size) Color(0xFF7C3AED) else Color.DarkGray) }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = formatTime(playPosition), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Slider(value = playPosition.toFloat(), onValueChange = { isDraggingSlider = true; playPosition = it.toLong() }, onValueChangeFinished = { isDraggingSlider = false; player?.seekTo(playPosition) }, valueRange = 0f..(totalDuration.toFloat().coerceAtLeast(1f)), modifier = Modifier.weight(1f).padding(horizontal = 8.dp), colors = SliderDefaults.colors(thumbColor = Color(0xFF7C3AED), activeTrackColor = Color(0xFF7C3AED), inactiveTrackColor = Color.DarkGray))
                        Text(text = formatTime(totalDuration), color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            } // fin CompositionLocalProvider
        }
    }

    // ✅ NUEVO: diálogo de filtros de imagen
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Filtros de imagen", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(VideoFilter.values().toList()) { filter ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { activeFilter = filter; showFilterDialog = false }.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(filter.icon, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(filter.displayName, color = Color.White, fontSize = 14.sp)
                            }
                            if (activeFilter == filter) Icon(Icons.Default.Done, contentDescription = "Activo", tint = Color(0xFF7C3AED))
                        }
                        Divider(color = Color.DarkGray)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFilterDialog = false }) { Text("Cerrar", color = Color(0xFF7C3AED)) } },
            containerColor = Color(0xFF120E1F)
        )
    }

    // ✅ NUEVO: diálogo de tasa de refresco de pantalla
    if (showRefreshRateDialog) {
        AlertDialog(
            onDismissRequest = { showRefreshRateDialog = false },
            title = { Text("Tasa de refresco", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Elige la fluidez que soporte tu celular. Esto ajusta la pantalla, no interpola frames (eso depende del hardware del TV).", color = Color.Gray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    availableRefreshRates.forEach { rate ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                applyRefreshRate(context as? Activity, rate)
                                currentRefreshRate = rate
                                showRefreshRateDialog = false
                                Toast.makeText(context, "Tasa de refresco: ${rate.toInt()}Hz", Toast.LENGTH_SHORT).show()
                            }.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${rate.toInt()} Hz", color = Color.White, fontSize = 14.sp)
                            if (kotlin.math.abs(currentRefreshRate - rate) < 0.5f) Icon(Icons.Default.Done, contentDescription = "Activo", tint = Color(0xFF7C3AED))
                        }
                        Divider(color = Color.DarkGray)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRefreshRateDialog = false }) { Text("Cerrar", color = Color(0xFF7C3AED)) } },
            containerColor = Color(0xFF120E1F)
        )
    }

    // DIALOGS
    if (showCropDialog) {
        AlertDialog(
            onDismissRequest = { showCropDialog = false },
            title = { Text("Ajustar Recorte de Franjas", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Franjas recortadas: ${(cropFraction * 100).toInt()}%",
                        color = Color.LightGray, fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Mueve el slider para ajustar cuánto recortar de los lados",
                        color = Color.Gray, fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Slider(
                        value = cropFraction,
                        onValueChange = { cropFraction = it },
                        valueRange = 0f..0.4f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF7C3AED),
                            activeTrackColor = Color(0xFF7C3AED),
                            inactiveTrackColor = Color.DarkGray
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Presets rápidos:", color = Color.Gray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "4:3 clásico" to 0.19f,
                            "Cine" to 0.12f,
                            "Reset" to 0f
                        ).forEach { (label, value) ->
                            OutlinedButton(
                                onClick = { cropFraction = value },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF7C3AED)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7C3AED)),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text(label, fontSize = 10.sp) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCropDialog = false }) {
                    Text("Aplicar", color = Color(0xFF7C3AED))
                }
            },
            containerColor = Color(0xFF120E1F)
        )
    }

    if (showCustomXYDialog) {
        AlertDialog(
            onDismissRequest = { showCustomXYDialog = false },
            title = { Text("Escala Personalizada X/Y", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("↔ Ancho (X): ${(cropFractionX * 100).toInt()}%", color = Color.LightGray, fontSize = 13.sp)
                    Slider(
                        value = cropFractionX,
                        onValueChange = { cropFractionX = it },
                        valueRange = 0f..0.5f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF7C3AED), activeTrackColor = Color(0xFF7C3AED), inactiveTrackColor = Color.DarkGray)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("↕ Alto (Y): ${(cropFractionY * 100).toInt()}%", color = Color.LightGray, fontSize = 13.sp)
                    Slider(
                        value = cropFractionY,
                        onValueChange = { cropFractionY = it },
                        valueRange = 0f..0.5f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF9333EA), activeTrackColor = Color(0xFF9333EA), inactiveTrackColor = Color.DarkGray)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Presets rápidos:", color = Color.Gray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "4:3" to Pair(0.19f, 0f),
                            "Cine" to Pair(0.12f, 0.05f),
                            "Reset" to Pair(0f, 0f)
                        ).forEach { (label, values) ->
                            OutlinedButton(
                                onClick = { cropFractionX = values.first; cropFractionY = values.second },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF7C3AED)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7C3AED)),
                                shape = RoundedCornerShape(8.dp)
                            ) { Text(label, fontSize = 10.sp) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCustomXYDialog = false }) {
                    Text("Aplicar", color = Color(0xFF7C3AED))
                }
            },
            containerColor = Color(0xFF120E1F)
        )
    }

    if (showQualityDialog) {
        AlertDialog(onDismissRequest = { showQualityDialog = false }, title = { Text("Seleccionar Calidad (HLS)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        val currentlyAdaptive = videoTracks.none { it.isSelected }
                        Row(modifier = Modifier.fillMaxWidth().clickable { player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build() }; Toast.makeText(context, "Calidad Adaptive activada", Toast.LENGTH_SHORT).show(); showQualityDialog = false }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Automático (Adaptive)", color = Color.White, fontSize = 14.sp)
                            if (currentlyAdaptive) Icon(Icons.Default.Done, contentDescription = "Activo", tint = Color(0xFF7C3AED))
                        }
                        Divider(color = Color.DarkGray)
                    }
                    items(videoTracks) { track ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex)).build() }; Toast.makeText(context, "Calidad: ${track.displayName}", Toast.LENGTH_SHORT).show(); showQualityDialog = false }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(track.displayName, color = Color.LightGray, fontSize = 14.sp)
                            if (track.isSelected) Icon(Icons.Default.Done, contentDescription = "Activo", tint = Color(0xFF7C3AED))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQualityDialog = false }) { Text("Cerrar", color = Color(0xFF7C3AED)) } },
            containerColor = Color(0xFF120E1F))
    }

    if (showAudioDialog) {
        AlertDialog(onDismissRequest = { showAudioDialog = false }, title = { Text("Pistas de Audio", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                if (audioTracks.isEmpty()) Text("No se detectaron pistas de audio secundarias.", color = Color.Gray, fontSize = 13.sp)
                else LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(audioTracks) { track ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex)).build() }; Toast.makeText(context, "Audio: ${track.displayName}", Toast.LENGTH_SHORT).show(); showAudioDialog = false }.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(track.displayName, color = Color.White, fontSize = 14.sp)
                            if (track.isSelected) Icon(Icons.Default.Done, contentDescription = "Activo", tint = Color(0xFF7C3AED))
                        }
                        Divider(color = Color.DarkGray)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAudioDialog = false }) { Text("Cerrar", color = Color(0xFF7C3AED)) } },
            containerColor = Color(0xFF120E1F))
    }

    if (showSubtitlesDialog) {
        AlertDialog(onDismissRequest = { showSubtitlesDialog = false }, title = { Text("Gestión de Subtítulos", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("SUBTÍTULOS INCORPORADOS:", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 11.sp) }
                    item {
                        val disabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
                        Row(modifier = Modifier.fillMaxWidth().clickable { player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }; showSubtitlesDialog = false; Toast.makeText(context, "Subtítulos Desactivados", Toast.LENGTH_SHORT).show() }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Desactivados (Ocultar)", color = Color.White, fontSize = 14.sp)
                            if (disabled) Icon(Icons.Default.Done, contentDescription = "Activo", tint = Color(0xFF7C3AED))
                        }
                    }
                    if (internalSubtitles.isNotEmpty()) {
                        items(internalSubtitles) { track ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex)).build() }; showSubtitlesDialog = false; Toast.makeText(context, "Subtítulo: ${track.displayName}", Toast.LENGTH_SHORT).show() }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(track.displayName, color = Color.LightGray, fontSize = 14.sp)
                                if (track.isSelected) Icon(Icons.Default.Done, contentDescription = "Activo", tint = Color(0xFF7C3AED))
                            }
                        }
                    } else {
                        item { Text("Este stream no posee subtítulos integrados.", color = Color.DarkGray, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
                    }
                    item { Divider(color = Color.DarkGray); Spacer(modifier = Modifier.height(4.dp)); Text("SUBTÍTULO EXTERNO:", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 11.sp) }
                    item {
                        Button(onClick = { subtitlePickerLauncher.launch("*/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED), contentColor = Color.White), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Cargar archivo .srt / .vtt", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (externalSubtitleUri != null) {
                        item {
                            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF2D1B69)).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Activo: $externalSubtitleName", color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(onClick = { externalSubtitleUri = null; externalSubtitleName = ""; showSubtitlesDialog = false; Toast.makeText(context, "Subtítulo removido", Toast.LENGTH_SHORT).show() }) { Text("Quitar", color = Color(0xFFFF5555), fontSize = 11.sp) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSubtitlesDialog = false }) { Text("Cerrar", color = Color(0xFF7C3AED)) } },
            containerColor = Color(0xFF120E1F))
    }
}

// ==========================================
// 3. SERVICE HELPERS & PARSERS
// ==========================================

private fun parseVideoAndAudioTracks(tracks: Tracks, audioTracks: MutableList<MediaTrackOption>, videoTracks: MutableList<MediaTrackOption>, internalSubtitles: MutableList<MediaTrackOption>) {
    audioTracks.clear(); videoTracks.clear(); internalSubtitles.clear()
    for (group in tracks.groups) {
        when (group.type) {
            C.TRACK_TYPE_VIDEO -> {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.width > 0 && format.height > 0) {
                        val label = "${format.height}p" + if (format.bitrate > 0) " (${format.bitrate / 1000} Kbps)" else ""
                        videoTracks.add(MediaTrackOption(group, i, label, "video_$i", group.isTrackSelected(i)))
                    }
                }
                // ✅ FIX 2: Ordenar calidades de menor a mayor resolución (240p → 480p → 1080p)
                videoTracks.sortBy { it.group.getTrackFormat(it.trackIndex).height }
            }
            C.TRACK_TYPE_AUDIO -> {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val cleanLabel = "${format.label ?: "Pista #${i + 1}"} [${format.language ?: "es"}]"
                    audioTracks.add(MediaTrackOption(group, i, cleanLabel, "audio_$i", group.isTrackSelected(i)))
                }
            }
            C.TRACK_TYPE_TEXT -> {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val cleanLabel = "${format.label ?: "Subtítulo #${i + 1}"} [${format.language ?: "unknown"}]"
                    internalSubtitles.add(MediaTrackOption(group, i, cleanLabel, "sub_$i", group.isTrackSelected(i)))
                }
            }
        }
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        } finally { cursor?.close() }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
