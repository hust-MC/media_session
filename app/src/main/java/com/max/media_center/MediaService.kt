package com.max.media_center

import android.app.PendingIntent
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import java.lang.reflect.Field

class MediaService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaPlayer: MediaPlayer
    private var currentState = PlaybackStateCompat.STATE_NONE
    private var currentIndex = 0
    private val musicList = mutableListOf<MusicItem>()

    data class MusicItem(
        val name: String,
        val resourceId: Int
    )

    companion object {
        private const val TAG = "MediaService"
        private const val MEDIA_ID_ROOT = "__ROOT__"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MCLOG", "MediaService onCreate")
        
        // 获取raw目录下的所有音频文件
        loadMusicList()
        
        // 创建MediaPlayer
        mediaPlayer = MediaPlayer()
        mediaPlayer.setOnCompletionListener {
            playNext()
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
        
        // 设置初始播放状态
        updatePlaybackState()
        
        sessionToken = mediaSession.sessionToken
    }

    private fun loadMusicList() {
        try {
            val fields: Array<Field> = R.raw::class.java.fields
            for (field in fields) {
                val resourceId = field.getInt(null)
                val resourceName = field.name
                musicList.add(MusicItem(resourceName, resourceId))
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            updatePlaybackState()
        } catch (e: Exception) {
            e.printStackTrace()
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
        super.onDestroy()
    }
} 