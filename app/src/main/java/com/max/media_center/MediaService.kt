package com.max.media_center

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
    private var playbackState: PlaybackStateCompat? = null
    
    companion object {
        private const val TAG = "MediaService"
        private const val MEDIA_ID_ROOT = "__ROOT__"
    }

    override fun onCreate() {
        super.onCreate()
        
        // 初始化MediaSession
        mediaSession = MediaSessionCompat(this, "MediaService")
        mediaSession.setCallback(mediaSessionCallback)
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        
        // 设置初始播放状态
        playbackState = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
        
        // 设置SessionToken
        sessionToken = mediaSession.sessionToken
        
        // 初始化MediaPlayer
        mediaPlayer = MediaPlayer()
        mediaPlayer.setOnPreparedListener { mp ->
            mp.start()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        }
        mediaPlayer.setOnCompletionListener {
            updatePlaybackState(PlaybackStateCompat.STATE_NONE)
        }
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (playbackState?.state == PlaybackStateCompat.STATE_PAUSED) {
                mediaPlayer.start()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }
        }

        override fun onPause() {
            if (playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
                mediaPlayer.pause()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            }
        }

        override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
            try {
                mediaPlayer.reset()
                uri?.let { mediaPlayer.setDataSource(this@MediaService, it) }
                mediaPlayer.prepare()
                
                // 更新元数据
                extras?.getString("title")?.let { title ->
                    val metadata = MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                        .build()
                    mediaSession.setMetadata(metadata)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing from URI", e)
            }
        }
    }

    private fun updatePlaybackState(state: Int) {
        playbackState = PlaybackStateCompat.Builder()
            .setState(state, mediaPlayer.currentPosition.toLong(), 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return BrowserRoot(MEDIA_ID_ROOT, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        
        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()
        
        // 添加示例音乐
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "1")
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "示例音乐")
            .build()
            
        mediaItems.add(
            MediaBrowserCompat.MediaItem(
                metadata.description,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        )
        
        result.sendResult(mediaItems)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer.release()
        mediaSession.release()
    }
} 