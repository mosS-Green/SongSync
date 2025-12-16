package pl.lambada.songsync.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.ui.aod.AODActivity
import pl.lambada.songsync.util.dataStore

class NotificationListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null
    
    private lateinit var userSettingsController: UserSettingsController
    
    // BroadcastReceiver for Screen OFF
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                checkAODTrigger()
            }
        }
    }

    private val mMediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }

        override fun onSessionDestroyed() {
            currentController?.unregisterCallback(this)
            currentController = null
            MusicState.updateSong(null, null, null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        userSettingsController = UserSettingsController(applicationContext.dataStore)
        
        startForeground()
        
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
        
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    
    override fun onDestroy() {
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        super.onDestroy()
    }

    private fun startForeground() {
        val channelId = "songsync_live_lyrics"
        val channelName = "Live Lyrics Service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("SongSync is active")
            .setContentText("Listening for music...")
            .setSmallIcon(pl.lambada.songsync.R.drawable.ic_notification)
            .build()

        startForeground(101, notification)
    }
    
    private fun triggerAODActivity() {
        // Use fullScreenIntent to launch activity over lockscreen/when screen is off
        val intent = Intent(this, AODActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            202,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val channelId = "songsync_aod_trigger"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
             val channel = NotificationChannel(
                 channelId,
                 "SongSync AOD",
                 NotificationManager.IMPORTANCE_HIGH // Must be HIGH for fullScreenIntent
             )
             channel.setSound(null, null) // Silent
             getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(pl.lambada.songsync.R.drawable.ic_notification)
            .setContentTitle("SongSync AOD")
            .setContentText("Showing music info...")
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()
            
        // Post notification (ID 202)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(202, notification)
        
        // Cancel it immediately after a short delay? 
        // Actually, if we cancel it too fast, it might not launch.
        // But we want to avoid showing a notification in the tray.
        // Usually fullScreenIntent DOES show a notification in tray if user unlocks.
        // We can listen for Activity start and cancel it.
        // Or just let AODActivity cancel it in onResume.
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenToActiveSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        currentController?.unregisterCallback(mMediaControllerCallback)
    }

    private fun listenToActiveSessions() {
        try {
            val componentName = ComponentName(this, NotificationListener::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            val activeController = controllers?.firstOrNull()
            
            if (activeController != null) {
                registerCallback(activeController)
            }
            
            mediaSessionManager?.addOnActiveSessionsChangedListener({ newControllers ->
                val newController = newControllers?.firstOrNull()
                if (newController != null) {
                    registerCallback(newController)
                } else {
                    currentController?.unregisterCallback(mMediaControllerCallback)
                    currentController = null
                    MusicState.updateSong(null, null, null)
                }
            }, componentName)
            
        } catch (e: SecurityException) {
            // Permission hasn't been granted yet
        }
    }

    private fun registerCallback(controller: MediaController) {
        if (currentController?.sessionToken == controller.sessionToken) return
        
        currentController?.unregisterCallback(mMediaControllerCallback)
        currentController = controller
        controller.registerCallback(mMediaControllerCallback)
        
        updateMetadata(controller.metadata)
        updatePlaybackState(controller.playbackState)
    }
    
    private fun checkAODTrigger() {
        if (!this::userSettingsController.isInitialized) return
        if (!userSettingsController.enableAOD) return
        
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else pm.isScreenOn
        
        // We only trigger if screen is OFF and music is PLAYING
        val isPlaying = MusicState.playbackInfo.value?.isPlaying == true
        
        if (!isScreenOn && isPlaying) {
             triggerAODActivity()
        }
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        metadata?.let {
            val title = it.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST)
            
            // Try to get URI first, then fall back to Bitmap
            val artUri = it.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) 
                         ?: it.getString(MediaMetadata.METADATA_KEY_ART_URI)
            
            val art: Any? = artUri ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) 
                         ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
            
            Log.d("SongSync", "Detected Song: $title by $artist")
            MusicState.updateSong(title, artist, art)
            
            // Check AOD on new song
            checkAODTrigger()
        }
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        state?.let {
            val isPlaying = it.state == PlaybackState.STATE_PLAYING
            val position = it.position
            MusicState.updateState(isPlaying, position, System.currentTimeMillis(), it.playbackSpeed)
            
            // Check AOD on state change
            if (isPlaying) checkAODTrigger()
        }
    }
}

// --- THE BRIDGE ---
object MusicState {
    // Triple<Title, Artist, Art (String or Bitmap)>
    private val _currentSong = MutableStateFlow<Triple<String, String, Any?>?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _playbackInfo = MutableStateFlow<PlaybackInfo?>(null)
    val playbackInfo = _playbackInfo.asStateFlow()

    fun updateSong(title: String?, artist: String?, art: Any?) {
        _currentSong.value = null
        if (title != null && artist != null) {
            _currentSong.value = Triple(title, artist, art)
        }
    }

    fun updateState(isPlaying: Boolean, position: Long, updateTime: Long, speed: Float) {
        _playbackInfo.value = PlaybackInfo(isPlaying, position, updateTime, speed)
    }
}

data class PlaybackInfo(
    val isPlaying: Boolean,
    val position: Long,
    val timestamp: Long,
    val speed: Float
)