package pl.lambada.songsync.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.services.MusicState
import pl.lambada.songsync.services.PlaybackInfo
import pl.lambada.songsync.util.parseLyrics
import pl.lambada.songsync.util.Providers
import java.util.regex.Pattern

data class LiveLyricsUiState(
    val songTitle: String = "Listening for music...",
    val songArtist: String = "",
    val coverArt: Any? = null,
    val parsedLyrics: List<Pair<String, String>> = emptyList(),
    val currentLyricLine: String = "",
    val currentLyricIndex: Int = -1,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTimestamp: Long = 0L,
    val lrcOffset: Int = 0
)

class LiveLyricsViewModel(
    private val lyricsProviderService: LyricsProviderService,
    val userSettingsController: UserSettingsController
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveLyricsUiState())
    val uiState = _uiState.asStateFlow()

    private var lyricsFetchJob: Job? = null
    private var timestampUpdateJob: Job? = null
    
    private var queryOffset = 0

    init {
        // COLLECTOR 1: Handles Song Changes
        viewModelScope.launch {
            MusicState.currentSong.collectLatest { songTriple ->
                if (songTriple == null) {
                    _uiState.value = LiveLyricsUiState()
                    queryOffset = 0
                    return@collectLatest
                }

                val (title, artist, art) = songTriple
                
                // Only refresh if the song actually changed
                if (title != _uiState.value.songTitle || artist != _uiState.value.songArtist) {
                    queryOffset = 0
                    _uiState.value = _uiState.value.copy(
                        lrcOffset = 0,
                        coverArt = art 
                    )
                    fetchLyricsFor(title, artist)
                }
            }
        }

        // COLLECTOR 2: Handles Play/Pause/Time
        viewModelScope.launch {
            MusicState.playbackInfo.collect { playbackInfo ->
                if (playbackInfo == null) {
                    timestampUpdateJob?.cancel()
                    return@collect
                }

                _uiState.value = _uiState.value.copy(isPlaying = playbackInfo.isPlaying)

                if (playbackInfo.isPlaying) {
                    if (timestampUpdateJob == null || timestampUpdateJob?.isActive == false) {
                        timestampUpdateJob = launch {
                            while (true) {
                                val currentInfo = MusicState.playbackInfo.value
                                if (currentInfo == null || !currentInfo.isPlaying) break

                                val (isPlaying, basePosition, baseTime, speed) = currentInfo
                                val timePassed = (System.currentTimeMillis() - baseTime) * speed
                                val currentPosition = basePosition + timePassed.toLong()
                                
                                updateCurrentLyric(currentPosition)
                                delay(200)
                            }
                        }
                    }
                } else {
                    timestampUpdateJob?.cancel()
                    updateCurrentLyric(playbackInfo.position)
                }
            }
        }
    }

    fun updateProvider(provider: Providers) {
        userSettingsController.updateSelectedProviders(provider)
        queryOffset = 0
        forceRefreshLyrics()
    }

    fun forceRefreshLyrics() {
        val currentState = _uiState.value
        if (currentState.songTitle != "Listening for music...") {
            if (currentState.currentLyricLine.contains("Song not found", ignoreCase = true)) {
                queryOffset = 0
            } else {
                queryOffset++
            }
            fetchLyricsFor(currentState.songTitle, currentState.songArtist)
        }
    }

    fun updateSearchQuery(title: String, artist: String) {
        queryOffset = 0
        fetchLyricsFor(title, artist)
    }

    fun updateLrcOffset(offset: Int) {
        _uiState.value = _uiState.value.copy(lrcOffset = offset)
        MusicState.playbackInfo.value?.let {
             val currentPos = if (it.isPlaying) {
                 val timePassed = (System.currentTimeMillis() - it.timestamp) * it.speed
                 it.position + timePassed.toLong()
             } else {
                 it.position
             }
             updateCurrentLyric(currentPos)
        }
    }

    private fun fetchLyricsFor(originalTitle: String, originalArtist: String) {
        lyricsFetchJob?.cancel()

        _uiState.value = _uiState.value.copy(
            songTitle = originalTitle,
            songArtist = originalArtist,
            isLoading = true,
            parsedLyrics = emptyList(),
            currentLyricLine = ""
        )

        lyricsFetchJob = viewModelScope.launch {
            val queriesToTry = mutableListOf<Pair<String, String>>()
            
            // 1. Original
            queriesToTry.add(originalTitle to originalArtist)
            
            // 2. Cleaned (Removes "Official Video", "Remastered", etc.)
            val cleanedTitle = cleanText(originalTitle)
            val cleanedArtist = cleanText(originalArtist)
            if (cleanedTitle != originalTitle || cleanedArtist != originalArtist) {
                queriesToTry.add(cleanedTitle to cleanedArtist)
            }

            // 3. Super Clean (Removes everything in brackets/parentheses)
            val superCleanTitle = superCleanText(originalTitle)
            if (superCleanTitle != cleanedTitle && superCleanTitle.isNotBlank()) {
                queriesToTry.add(superCleanTitle to cleanedArtist)
            }

            var success = false
            for ((title, artist) in queriesToTry) {
                // Avoid jumping queries if the user is trying to page through results
                if (queryOffset > 0 && (title != queriesToTry.first().first)) continue

                if (tryFetchLyrics(title, artist)) {
                    success = true
                    break 
                }
            }

            if (!success) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentLyricLine = "Song not found."
                )
            }
        }
    }

    private suspend fun tryFetchLyrics(title: String, artist: String): Boolean {
        return try {
            val songInfo = lyricsProviderService.getSongInfo(
                query = SongInfo(title, artist),
                offset = queryOffset,
                provider = userSettingsController.selectedProvider
            ) ?: return false

            val lyricsString = lyricsProviderService.getSyncedLyrics(
                songInfo.songName ?: title, 
                songInfo.artistName ?: artist,
                userSettingsController.selectedProvider,
                userSettingsController.includeTranslation,
                userSettingsController.includeRomanization,
                userSettingsController.multiPersonWordByWord,
                userSettingsController.unsyncedFallbackMusixmatch
            ) ?: return false

            val parsedLyrics = parseLyrics(lyricsString)
            if (parsedLyrics.isEmpty()) return false

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                parsedLyrics = parsedLyrics
            )
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun cleanText(input: String): String {
        return try {
            var text = input
            // Extensive list of "junk" keywords to remove
            // Matches (Official Video), [HQ], (Remix), etc.
            val keywords = "official|video|lyrics|lyric|visualizer|audio|music video|mv|topic|hd|hq|4k|1080p|remastered|remaster|live|session|performance|concert|cover|remix|mix|edit|extended|radio|instrumental|karaoke|version|clean|explicit"
            val junkRegex = Pattern.compile("(?i)[(\\[](?:$keywords).*?[)\\]]")
            text = junkRegex.matcher(text).replaceAll("").trim()
            
            // Remove "feat." or "ft." 
            val featRegex = Pattern.compile("(?i)\\s(feat\\.?|ft\\.?|featuring)\\s.*")
            text = featRegex.matcher(text).replaceAll("").trim()
            
            text
        } catch (e: Exception) {
            input // Fallback to original if regex crashes
        }
    }

    private fun superCleanText(input: String): String {
        return try {
            // Remove ANYTHING in brackets or parentheses
            val bracketRegex = Pattern.compile("[(\\[].*?[)\\]]")
            bracketRegex.matcher(input).replaceAll("").trim()
        } catch (e: Exception) {
            input
        }
    }

    private fun updateCurrentLyric(currentPosition: Long) {
        val state = _uiState.value
        val lyrics = state.parsedLyrics
        if (lyrics.isEmpty()) return

        val effectivePosition = currentPosition - state.lrcOffset

        val currentIndex = lyrics.indexOfLast { (time, _) ->
            try {
                val parts = time.split(":", ".")
                val minutes = parts[0].toLong()
                val seconds = parts[1].toLong()
                val millis = parts[2].toLong()
                val lyricTime = (minutes * 60 * 1000) + (seconds * 1000) + millis
                
                lyricTime <= effectivePosition
            } catch (e: Exception) {
                false
            }
        }

        if (currentIndex != -1 && currentIndex != state.currentLyricIndex) {
            _uiState.value = state.copy(
                currentLyricLine = lyrics[currentIndex].second,
                currentLyricIndex = currentIndex
            )
        }
        
        _uiState.value = _uiState.value.copy(currentTimestamp = currentPosition)
    }

    companion object {
        fun Factory(
            lyricsProviderService: LyricsProviderService,
            userSettingsController: UserSettingsController
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(LiveLyricsViewModel::class.java)) {
                    return LiveLyricsViewModel(lyricsProviderService, userSettingsController) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}