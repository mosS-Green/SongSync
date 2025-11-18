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

data class LiveLyricsUiState(
    val songTitle: String = "Listening for music...",
    val songArtist: String = "",
    val parsedLyrics: List<Pair<String, String>> = emptyList(),
    val currentLyricLine: String = "",
    val currentLyricIndex: Int = -1,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTimestamp: Long = 0L
)

class LiveLyricsViewModel(
    private val lyricsProviderService: LyricsProviderService,
    val userSettingsController: UserSettingsController // Changed to public/accessible
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveLyricsUiState())
    val uiState = _uiState.asStateFlow()

    private var lyricsFetchJob: Job? = null
    private var timestampUpdateJob: Job? = null

    init {
        // COLLECTOR 1: Handles Song Changes
        viewModelScope.launch {
            MusicState.currentSong.collectLatest { songPair ->
                timestampUpdateJob?.cancel()

                if (songPair == null) {
                    _uiState.value = LiveLyricsUiState()
                    return@collectLatest
                }

                fetchLyricsFor(songPair.first, songPair.second)
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
                timestampUpdateJob?.cancel()

                if (playbackInfo.isPlaying) {
                    timestampUpdateJob = launch {
                        while (true) {
                            val (isPlaying, basePosition, baseTime, speed) = MusicState.playbackInfo.value ?: break
                            if (!isPlaying) break

                            val timePassed = (System.currentTimeMillis() - baseTime) * speed
                            val currentPosition = basePosition + timePassed.toLong()
                            updateCurrentLyric(currentPosition)
                            
                            delay(200)
                        }
                    }
                } else {
                    updateCurrentLyric(playbackInfo.position)
                }
            }
        }
    }

    // Function to switch provider and refresh
    fun updateProvider(provider: Providers) {
        userSettingsController.updateSelectedProviders(provider)
        forceRefreshLyrics()
    }

    // Function to manually refresh lyrics
    fun forceRefreshLyrics() {
        val currentState = _uiState.value
        if (currentState.songTitle != "Listening for music...") {
            fetchLyricsFor(currentState.songTitle, currentState.songArtist)
        }
    }

    private fun fetchLyricsFor(title: String, artist: String) {
        lyricsFetchJob?.cancel()

        _uiState.value = LiveLyricsUiState(
            songTitle = title,
            songArtist = artist,
            isLoading = true,
            isPlaying = _uiState.value.isPlaying
        )

        lyricsFetchJob = viewModelScope.launch {
            val songInfo = try {
                lyricsProviderService.getSongInfo(
                    query = SongInfo(title, artist),
                    offset = 0,
                    provider = userSettingsController.selectedProvider
                )
            } catch (e: Exception) {
                null
            }

            if (songInfo == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentLyricLine = "Song not found by provider."
                )
                return@launch
            }

            val lyricsString = try {
                lyricsProviderService.getSyncedLyrics(
                    songInfo.songName ?: title, 
                    songInfo.artistName ?: artist,
                    userSettingsController.selectedProvider,
                    userSettingsController.includeTranslation,
                    userSettingsController.includeRomanization,
                    userSettingsController.multiPersonWordByWord,
                    userSettingsController.unsyncedFallbackMusixmatch
                )
            } catch (e: Exception) {
                null
            }

            if (lyricsString == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentLyricLine = "No lyrics found for this song."
                )
                return@launch
            }

            val parsedLyrics = parseLyrics(lyricsString)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                parsedLyrics = parsedLyrics
            )
        }
    }

    private fun updateCurrentLyric(currentPosition: Long) {
        val lyrics = _uiState.value.parsedLyrics
        if (lyrics.isEmpty()) return

        val currentIndex = lyrics.indexOfLast { (time, _) ->
            try {
                val parts = time.split(":", ".")
                val minutes = parts[0].toLong()
                val seconds = parts[1].toLong()
                val millis = parts[2].toLong()
                val lyricTime = (minutes * 60 * 1000) + (seconds * 1000) + millis
                lyricTime <= currentPosition
            } catch (e: Exception) {
                false
            }
        }

        if (currentIndex != -1 && currentIndex != _uiState.value.currentLyricIndex) {
            _uiState.value = _uiState.value.copy(
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