package com.max.media_center

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import java.lang.reflect.Field

class MediaService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaPlayer: MediaPlayer
    private var currentState = PlaybackStateCompat.STATE_NONE
    private var currentIndex = 0
    private val musicList = mutableListOf<MusicItem>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentPlayMode = PlayMode.SEQUENTIAL
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var progressUpdater: Runnable

    data class MusicItem(
        val name: String,
        val resourceId: Int,
        var title: String = "",
        var artist: String = ""
    )

    enum class PlayMode {
        SEQUENTIAL,  // 顺序播放
        SHUFFLE,     // 随机播放
        REPEAT_ONE   // 单曲循环
    }

    companion object {
        private const val TAG = "MediaService"
        private const val MEDIA_ID_ROOT = "__ROOT__"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "media_playback_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MCLOG", "MediaService onCreate")
        
        // 获取raw目录下的所有音频文件
        loadMusicList()
        Log.d(TAG, "MediaService loaded ${musicList.size} music items")
        
        // 创建MediaPlayer
        mediaPlayer = MediaPlayer().apply {
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setOnCompletionListener {
                playNext()
            }
        }
        
        // 初始化进度更新器
        progressUpdater = Runnable {
            if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                updatePlaybackState()
                handler.postDelayed(progressUpdater, 1000) // 每秒更新一次
            }
        }
        
        // 创建MediaSession
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        mediaSession = MediaSessionCompat(this, "MediaService").apply {
            setSessionActivity(sessionActivityPendingIntent)
            setCallback(MediaSessionCallback())
            isActive = true
        }
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 设置初始播放状态
        updatePlaybackState()
        
        // 创建初始通知，确保前台服务启动
        updateNotification()
        
        sessionToken = mediaSession.sessionToken
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        // 处理媒体按钮事件
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        
        when (intent?.action) {
            "SWITCH_PLAY_MODE" -> {
                Log.d(TAG, "Switching play mode from: $currentPlayMode")
                switchPlayMode()
                Log.d(TAG, "Switched play mode to: $currentPlayMode")
                // 广播播放模式变化，让MainActivity更新UI
                val broadcastIntent = Intent("PLAY_MODE_CHANGED")
                broadcastIntent.putExtra("playMode", currentPlayMode.name)
                broadcastIntent.setPackage(packageName) // 确保广播发送到本应用
                sendBroadcast(broadcastIntent)
                Log.d(TAG, "Broadcast sent: ${currentPlayMode.name}")
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val musicItem = musicList.getOrNull(currentIndex)
        val title = musicItem?.title?.takeIf { it.isNotEmpty() } ?: musicItem?.name ?: "未知歌曲"
        val artist = musicItem?.artist?.takeIf { it.isNotEmpty() } ?: "未知艺术家"
        
        // 创建播放/暂停动作
        val playPauseAction = if (currentState == PlaybackStateCompat.STATE_PLAYING) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                "暂停",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "播放", 
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
            ).build()
        }
        
        // 创建上一首动作
        val prevAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous,
            "上一首",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        ).build()
        
        // 创建下一首动作
        val nextAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next,
            "下一首",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        ).build()
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(if (currentState == PlaybackStateCompat.STATE_PLAYING) "正在播放" else "已暂停")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(prevAction)
            .addAction(playPauseAction) 
            .addAction(nextAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2) // 显示所有3个按钮
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun loadMusicList() {
        try {
            val fields: Array<Field> = R.raw::class.java.fields
            for (field in fields) {
                val resourceId = field.getInt(null)
                val resourceName = field.name
                val musicItem = MusicItem(resourceName, resourceId)
                
                // 获取音频文件的元数据
                try {
                    val retriever = MediaMetadataRetriever()
                    val uri = Uri.parse("android.resource://${packageName}/raw/${resourceName}")
                    retriever.setDataSource(applicationContext, uri)
                    
                    musicItem.title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                    musicItem.artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                    
                    retriever.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting metadata for $resourceName", e)
                }
                
                musicList.add(musicItem)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading music list", e)
        }
    }

    private fun playMusic(index: Int) {
        if (index < 0 || index >= musicList.size) return
        
        currentIndex = index
        val musicItem = musicList[index]
        
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(applicationContext, Uri.parse("android.resource://${packageName}/raw/${musicItem.name}"))
            mediaPlayer.prepare()
            val duration = mediaPlayer.duration.toLong()
            mediaPlayer.start()
            currentState = PlaybackStateCompat.STATE_PLAYING
            
            // 更新元数据
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, musicItem.resourceId.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, musicItem.title.takeIf { it.isNotEmpty() } ?: musicItem.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, musicItem.artist.takeIf { it.isNotEmpty() } ?: "未知艺术家")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
            mediaSession.setMetadata(metadata)
            
            updatePlaybackState()
            updateNotification()
            
            // 启动进度更新
            handler.post(progressUpdater)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing music", e)
        }
    }

    private fun playNext() {
        if (musicList.isEmpty()) return
        when (currentPlayMode) {
            PlayMode.SEQUENTIAL -> {
                currentIndex = (currentIndex + 1) % musicList.size
            }
            PlayMode.SHUFFLE -> {
                currentIndex = (0 until musicList.size).random()
            }
            PlayMode.REPEAT_ONE -> {
                // 保持当前索引不变
            }
        }
        playMusic(currentIndex)
    }

    private fun playPrevious() {
        if (musicList.isEmpty()) return
        when (currentPlayMode) {
            PlayMode.SEQUENTIAL -> {
                currentIndex = (currentIndex - 1 + musicList.size) % musicList.size
            }
            PlayMode.SHUFFLE -> {
                currentIndex = (0 until musicList.size).random()
            }
            PlayMode.REPEAT_ONE -> {
                // 保持当前索引不变
            }
        }
        playMusic(currentIndex)
    }

    fun switchPlayMode() {
        currentPlayMode = when (currentPlayMode) {
            PlayMode.SEQUENTIAL -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SEQUENTIAL
        }
    }

    fun getCurrentPlayMode(): PlayMode = currentPlayMode

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(MEDIA_ID_ROOT, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        Log.d(TAG, "onLoadChildren called with parentId: $parentId")
        
        if (parentId == MEDIA_ID_ROOT) {
            val mediaItems = musicList.map { musicItem ->
                val description = MediaDescriptionCompat.Builder()
                    .setMediaId(musicItem.resourceId.toString())
                    .setTitle(musicItem.title.takeIf { it.isNotEmpty() } ?: musicItem.name)
                    .setSubtitle(musicItem.artist.takeIf { it.isNotEmpty() } ?: "未知艺术家")
                    .build()
                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
            }.toMutableList()
            
            Log.d(TAG, "Returning ${mediaItems.size} items to client")
            result.sendResult(mediaItems)
        } else {
            Log.d(TAG, "Unknown parentId: $parentId, returning null")
            result.sendResult(null)
        }
    }

    private fun updatePlaybackState() {
        val currentPosition = if (currentState == PlaybackStateCompat.STATE_PLAYING || currentState == PlaybackStateCompat.STATE_PAUSED) {
            mediaPlayer.currentPosition.toLong()
        } else {
            0L
        }
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(currentState, currentPosition, 1.0f)
        mediaSession.setPlaybackState(stateBuilder.build())
        updateNotification()
    }

    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (currentState != PlaybackStateCompat.STATE_PLAYING) {
                if (musicList.isEmpty()) {
                    playMusic(0)
                } else {
                    mediaPlayer.start()
                    currentState = PlaybackStateCompat.STATE_PLAYING
                    updatePlaybackState()
                    handler.post(progressUpdater)
                }
            }
        }

        override fun onPause() {
            if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                mediaPlayer.pause()
                currentState = PlaybackStateCompat.STATE_PAUSED
                updatePlaybackState()
                handler.removeCallbacks(progressUpdater)
            }
        }

        override fun onSkipToNext() {
            playNext()
        }

        override fun onSkipToPrevious() {
            playPrevious()
        }
        
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.d(TAG, "onPlayFromMediaId called with mediaId: $mediaId")
            mediaId?.toIntOrNull()?.let { resourceId ->
                val index = musicList.indexOfFirst { it.resourceId == resourceId }
                if (index != -1) {
                    playMusic(index)
                } else {
                    Log.e(TAG, "Song with resourceId $resourceId not found in playlist")
                }
            }
        }

        override fun onSeekTo(pos: Long) {
            mediaPlayer.seekTo(pos.toInt())
            updatePlaybackState()
        }

        override fun onStop() {
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        mediaSession.release()
        mediaPlayer.release()
        wakeLock?.release()
        super.onDestroy()
    }
} 