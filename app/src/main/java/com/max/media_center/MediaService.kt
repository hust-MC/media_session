package com.max.media_center

import android.app.PendingIntent
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat

class MediaService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaPlayer: MediaPlayer
    private var currentState = PlaybackStateCompat.STATE_NONE
    
    companion object {
        private const val TAG = "MediaService"
        private const val MEDIA_ID_ROOT = "__ROOT__"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MCLOG", "MediaService onCreate")
        
        // 创建MediaPlayer
        mediaPlayer = MediaPlayer()
        mediaPlayer.setDataSource(applicationContext, android.net.Uri.parse("android.resource://${packageName}/raw/jj"))
        mediaPlayer.prepare()
        
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
                PlaybackStateCompat.ACTION_PAUSE
            )
            .setState(currentState, 0, 1.0f)
        mediaSession.setPlaybackState(stateBuilder.build())
    }

    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (currentState != PlaybackStateCompat.STATE_PLAYING) {
                mediaPlayer.start()
                currentState = PlaybackStateCompat.STATE_PLAYING
                updatePlaybackState()
            }
        }

        override fun onPause() {
            if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                mediaPlayer.pause()
                currentState = PlaybackStateCompat.STATE_PAUSED
                updatePlaybackState()
            }
        }
    }

    override fun onDestroy() {
        mediaSession.release()
        mediaPlayer.release()
        super.onDestroy()
    }
} 