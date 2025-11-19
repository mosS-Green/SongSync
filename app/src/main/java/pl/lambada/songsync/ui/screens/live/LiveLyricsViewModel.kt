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
                // *** BUG FIX: REMOVED timestampUpdateJob?.cancel() ***
                // We strictly let the PlaybackInfo collector manage the timer.
                
                if (songTriple == null) {
                    _uiState.value = LiveLyricsUiState()
                    queryOffset = 0
                    return@collectLatest
                }

                val (title, artist, art) = songTriple
                
                // Only update if the song actually changed
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
                    // If playing, ensure the timer is running.
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
            // If we previously failed (Song not found), keep offset 0 and try again.
            // If we succeeded but user wants another version, increment offset.
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
            // *** SMART SEARCH STRATEGY ***
            // We create a list of "queries" to try in order.
            val queriesToTry = mutableListOf<Pair<String, String>>()
            
            // 1. Try the Exact Original
            queriesToTry.add(originalTitle to originalArtist)
            
            // 2. Try "Cleaned" version (removes "Official Video", "(Lyrics)", etc.)
            val cleanedTitle = cleanText(originalTitle)
            val cleanedArtist = cleanText(originalArtist) // Removes "feat."
            if (cleanedTitle != originalTitle || cleanedArtist != originalArtist) {
                queriesToTry.add(cleanedTitle to cleanedArtist)
            }

            // 3. Try "Super Clean" (removes everything in brackets/parentheses)
            val superCleanTitle = superCleanText(originalTitle)
            if (superCleanTitle != cleanedTitle && superCleanTitle.isNotBlank()) {
                queriesToTry.add(superCleanTitle to cleanedArtist)
            }

            var success = false
            for ((title, artist) in queriesToTry) {
                // If we are paging (offset > 0), only use the first (best) query strategy
                // to avoid weird behavior where page 2 implies a different search term.
                if (queryOffset > 0 && (title != queriesToTry.first().first)) continue

                if (tryFetchLyrics(title, artist)) {
                    success = true
                    break // Stop as soon as we find something!
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

    // Helper to try a single query
    private suspend fun tryFetchLyrics(title: String, artist: String): Boolean {
        try {
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
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    // *** TEXT CLEANING UTILITIES ***
    
    // Removes specific "junk" phrases common in music videos
    private fun cleanText(input: String): String {
        var text = input
        // Remove "Official Video", "Lyrics", "Audio", "Visualizer", "HQ", "HD" in brackets/parens
        val junkRegex = Pattern.compile("(?i)[(\\[](?:official|video|lyrics|visualizer|audio|hd|hq|remastered|live).*?[)\\]]")
        text = junkRegex.matcher(text).replaceAll("").trim()
        
        // Remove "feat." or "ft." and the artist names after it (often ruins title search)
        val featRegex = Pattern.compile("(?i)\\s(feat\\.?|ft\\.?|featuring)\\s.*")
        text = featRegex.matcher(text).replaceAll("").trim()
        
        return text
    }

    // Aggressively removes EVERYTHING in brackets or parentheses
    private fun superCleanText(input: String): String {
        // Remove anything between () or []
        val bracketRegex = Pattern.compile("[(\\[].*?[)\\]]")
        return bracketRegex.matcher(input).replaceAll("").trim()
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