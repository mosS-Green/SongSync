package pl.lambada.songsync.ui.aod

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.services.MusicState
import pl.lambada.songsync.ui.theme.SongSyncTheme
import pl.lambada.songsync.util.dataStore

class AODActivity : ComponentActivity() {

    private lateinit var lyricsProviderService: LyricsProviderService
    private lateinit var userSettingsController: UserSettingsController
    
    // Timer specific logic
    private var lastInteractionTime = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup Window Flags for AOD/Lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Initialize dependencies
        lyricsProviderService = LyricsProviderService()
        userSettingsController = UserSettingsController(dataStore)

        enableEdgeToEdge()
        
        setContent {
            SongSyncTheme(pureBlack = true) { // Force Pure Black
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    val song by MusicState.currentSong.collectAsState()
                    val playback by MusicState.playbackInfo.collectAsState()
                    
                    // Logic to finish if paused for 10s
                    // or dim after 7s (handled in AmbientAura or screen brightness)
                    
                    AODScreen(
                        songTitle = song?.first ?: "Unknown Title",
                        artist = song?.second ?: "Unknown Artist",
                        art = song?.third,
                        playbackInfo = playback,
                        lyricsProviderService = lyricsProviderService,
                        userSettingsController = userSettingsController,
                        onClose = { finish() },
                        onUserInteraction = { lastInteractionTime = System.currentTimeMillis() }
                    )
                }
            }
        }
        
        // Background loop to check for idle/paused state
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                val isPlaying = MusicState.playbackInfo.value?.isPlaying == true
                val timeSinceInteraction = System.currentTimeMillis() - lastInteractionTime
                
                // Dim Logic
                val lp = window.attributes
                if (timeSinceInteraction > 7000) {
                    lp.screenBrightness = 0.05f // very dim
                } else {
                    lp.screenBrightness = -1f // default
                }
                window.attributes = lp
                
                // Turn off if paused for 10s
                if (!isPlaying && timeSinceInteraction > 10000) {
                    finish() // Close AOD, which lets screen turn off normally
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Cancel the trigger notification
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm?.cancel(202)
    }
    
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionTime = System.currentTimeMillis()
    }
}
