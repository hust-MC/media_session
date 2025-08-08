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
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import java.lang.reflect.Field

class MediaService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaPlayer: MediaPlayer
    private var currentState = PlaybackStateCompat.STATE_NONE
    private var currentIndex = 0
    private val musicList = mutableListOf<MusicItem>()
    private var wakeLock: PowerManager.WakeLock? = null

    data class MusicItem(
        val name: String,
        val resourceId: Int,
        var title: String = "",
        var artist: String = ""
    )

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
        
        // 创建MediaPlayer
        mediaPlayer = MediaPlayer().apply {
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setOnCompletionListener {
                playNext()
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
        
        sessionToken = mediaSession.sessionToken
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
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(if (currentState == PlaybackStateCompat.STATE_PLAYING) "正在播放" else "已暂停")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
            mediaPlayer.start()
            currentState = PlaybackStateCompat.STATE_PLAYING
            
            // 更新元数据
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, musicItem.title.takeIf { it.isNotEmpty() } ?: musicItem.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, musicItem.artist.takeIf { it.isNotEmpty() } ?: "未知艺术家")
                .build()
            mediaSession.setMetadata(metadata)
            
            updatePlaybackState()
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing music", e)
        }
    }

    private fun playNext() {
        if (musicList.isEmpty()) return
        playMusic((currentIndex + 1) % musicList.size)
    }

    private fun playPrevious() {
        if (musicList.isEmpty()) return
        playMusic((currentIndex - 1 + musicList.size) % musicList.size)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(null)
    }

    private fun updatePlaybackState() {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(currentState, 0, 1.0f)
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
                }
            }
        }

        override fun onPause() {
            if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                mediaPlayer.pause()
                currentState = PlaybackStateCompat.STATE_PAUSED
                updatePlaybackState()
            }
        }

        override fun onSkipToNext() {
            playNext()
        }

        override fun onSkipToPrevious() {
            playPrevious()
        }
    }

    override fun onDestroy() {
        mediaSession.release()
        mediaPlayer.release()
        wakeLock?.release()
        super.onDestroy()
    }
} 