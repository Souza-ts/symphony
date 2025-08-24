package io.github.zyrouge.symphony.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.zyrouge.symphony.services.radio.RadioPlayer
import io.github.zyrouge.symphony.ui.helpers.FadeTransition
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.TimedContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Timer
import kotlin.concurrent.timer

@Composable
fun LyricsText(
    context: ViewContext,
    padding: PaddingValues,
    style: TimedContentTextStyle,
) {
    val coroutineScope = rememberCoroutineScope()
    var playbackPosition by remember {
        mutableStateOf(
            context.symphony.radio.currentPlaybackPosition ?: RadioPlayer.PlaybackPosition.zero
        )
    }
    var playbackPositionTimer: Timer? = remember { null }
    val queue by context.symphony.radio.observatory.queue.collectAsState()
    val queueIndex by context.symphony.radio.observatory.queueIndex.collectAsState()
    val song by remember(queue, queueIndex) {
        derivedStateOf {
            queue.getOrNull(queueIndex)?.let { context.symphony.groove.song.get(it) }
        }
    }
    var lyricsState by remember { mutableIntStateOf(0) }
    var lyricsSongId by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<TimedContent?>(null) }

    // Função para buscar letras da LrcLib
    suspend fun fetchLyricsFromLrcLib(artist: String, title: String): String? {
        return try {
            val client = HttpClient()
            // Primeiro busca os resultados da pesquisa
            val searchResponse: String = client.get(
                "https://lrclib.net/api/search" +
                "?artist_name=${URLEncoder.encode(artist, "UTF-8")}" +
                "&track_name=${URLEncoder.encode(title, "UTF-8")}"
            ).body()
            
            // Parse da resposta JSON
            val jsonArray = Json.parseToJsonElement(searchResponse).jsonArray
            if (jsonArray.isNotEmpty()) {
                // Pega o primeiro resultado (mais relevante)
                val firstResult = jsonArray.first().jsonObject
                val lyricsId = firstResult["id"]?.jsonPrimitive?.int
                
                lyricsId?.let { id ->
                    // Busca as letras completas pelo ID
                    val lyricsResponse: String = client.get("https://lrclib.net/api/get/$id").body()
                    val lyricsJson = Json.parseToJsonElement(lyricsResponse).jsonObject
                    lyricsJson["syncedLyrics"]?.jsonPrimitive?.content ?: 
                    lyricsJson["plainLyrics"]?.jsonPrimitive?.content
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    LaunchedEffect(LocalContext.current) {
        awaitAll(
            async {
                playbackPositionTimer = timer(period = 50L) {
                    playbackPosition = context.symphony.radio.currentPlaybackPosition
                        ?: RadioPlayer.PlaybackPosition.zero
                }
            },
            async {
                snapshotFlow { song }
                    .distinctUntilChanged()
                    .collect { song ->
                        lyricsState = 1
                        lyricsSongId = song?.id
                        coroutineScope.launch {
                            // Primeiro tenta buscar da LrcLib
                            val fetchedLyrics = song?.let {
                                fetchLyricsFromLrcLib(it.artist, it.title)
                            }
                            
                            if (fetchedLyrics != null) {
                                // Usa as letras da LrcLib
                                lyrics = TimedContent.fromLyrics(fetchedLyrics)
                                // Opcional: salva localmente para uso futuro
                                song?.id?.let { songId ->
                                    context.symphony.groove.song.saveLyrics(songId, fetchedLyrics)
                                }
                            } else {
                                // Fallback: busca letras locais
                                lyrics = song?.let { s ->
                                    context.symphony.groove.song.getLyrics(s)?.let {
                                        TimedContent.fromLyrics(it)
                                    }
                                }
                            }
                            lyricsState = 2
                        }
                    }
            }
        )
    }

    DisposableEffect(LocalContext.current) {
        onDispose {
            playbackPositionTimer?.cancel()
        }
    }

    // Botão para forçar busca de letras
    Column {
        if (lyricsState == 2 && lyrics == null && song != null) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        lyricsState = 1
                        val fetchedLyrics = fetchLyricsFromLrcLib(song.artist, song.title)
                        if (fetchedLyrics != null) {
                            lyrics = TimedContent.fromLyrics(fetchedLyrics)
                            context.symphony.groove.song.saveLyrics(song.id, fetchedLyrics)
                        }
                        lyricsState = 2
                    }
                },
                modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally)
            ) {
                Text("Buscar Letras Online")
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }

        AnimatedContent(
            label = "lyrics-text",
            targetState = lyricsState to lyrics,
            transitionSpec = {
                FadeTransition.enterTransition()
                    .togetherWith(FadeTransition.exitTransition())
            },
        ) { targetState ->
            val targetLyricsState = targetState.first
            val targetLyrics = targetState.second

            when {
                targetLyricsState == 2 && targetLyrics != null -> TimedContentText(
                    content = targetLyrics,
                    duration = playbackPosition.played,
                    padding = padding,
                    style = style,
                    onSeek = {
                        targetLyrics.pairs.getOrNull(it)?.first?.let { to ->
                            context.symphony.radio.seek(to)
                        }
                    }
                )

                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (targetLyricsState == 1) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(8.dp))
                        Text(context.symphony.t.Loading)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(context.symphony.t.NoLyrics)
                            Text(
                                "Tente buscar letras online",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Classe de dados para a resposta da LrcLib (opcional, mas recomendado)
@Serializable
data class LrcLibSearchResult(
    val id: Int,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val duration: Int,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val copyright: String?
)

// Função de extensão para parsing seguro
suspend fun HttpClient.getJson(url: String): JsonElement? {
    return try {
        val response = this.get(url).body<String>()
        Json.parseToJsonElement(response)
    } catch (e: Exception) {
        null
    }
}