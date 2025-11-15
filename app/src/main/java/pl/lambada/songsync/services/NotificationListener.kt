package pl.lambada.songsync.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null

    override fun onCreate() {
        super.onCreate()
        // This creates the "notification" that stays in your status bar
        // so Android doesn't kill the app while it's listening.
        startForeground()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private fun startForeground() {
        val channelId = "songsync_live_lyrics"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Live Lyrics Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("SongSync is active")
            .setContentText("Listening for music...")
            .setSmallIcon(pl.lambada.songsync.R.drawable.ic_notification) 
            .build()

        // ID 101 is just a random ID for this notification
        startForeground(101, notification)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenToActiveSessions()
    }

    private fun listenToActiveSessions() {
        try {
            val componentName = ComponentName(this, NotificationListener::class.java)
            // Find active media sessions (like Spotify, YouTube Music, etc.)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            
            // Pick the first one that is playing or ready
            val activeController = controllers?.firstOrNull()
            
            if (activeController != null) {
                registerCallback(activeController)
            }
            
            // Listen for changes (e.g. if you switch from Spotify to YouTube Music)
            mediaSessionManager?.addOnActiveSessionsChangedListener({ newControllers ->
                val newController = newControllers?.firstOrNull()
                if (newController != null) {
                    registerCallback(newController)
                }
            }, componentName)
            
        } catch (e: SecurityException) {
            // Permission hasn't been granted yet
        }
    }

    private fun registerCallback(controller: MediaController) {
        // If we are already listening to this app, don't do it again
        if (currentController?.packageName == controller.packageName) return
        
        currentController = controller
        
        // Immediately update with current info
        updateMetadata(controller.metadata)
        updatePlaybackState(controller.playbackState)

        // Register a callback to get updates automatically
        controller.registerCallback(object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                updatePlaybackState(state)
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                updateMetadata(metadata)
            }
        })
    }
    
    private fun updateMetadata(metadata: MediaMetadata?) {
        metadata?.let {
            val title = it.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST)
            // We will use this data in Step 2
            Log.d("SongSync", "Detected Song: $title by $artist")
            MusicState.updateSong(title, artist)
        }
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        state?.let {
            val isPlaying = it.state == PlaybackState.STATE_PLAYING
            val position = it.position
            // We will use this data in Step 2
            MusicState.updateState(isPlaying, position, System.currentTimeMillis(), it.playbackSpeed)
        }
    }
}

// --- THE BRIDGE ---
// This little object acts as a bridge. The Service (above) puts data IN.
// The UI (which we build later) will take data OUT.
object MusicState {
    private val _currentSong = MutableStateFlow<Pair<String, String>?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _playbackInfo = MutableStateFlow<PlaybackInfo?>(null)
    val playbackInfo = _playbackInfo.asStateFlow()

    fun updateSong(title: String?, artist: String?) {
        if (title != null && artist != null) {
            _currentSong.value = title to artist
        }
    }

    fun updateState(isPlaying: Boolean, position: Long, updateTime: Long, speed: Float) {
        _playbackInfo.value = PlaybackInfo(isPlaying, position, updateTime, speed)
    }
}

data class PlaybackInfo(
    val isPlaying: Boolean,
    val position: Long,
    val timestamp: Long, // System time when the position was read
    val speed: Float
)