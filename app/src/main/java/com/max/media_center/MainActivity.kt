package com.max.media_center

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null
    
    private lateinit var playPauseButton: Button
    private lateinit var titleTextView: TextView
    
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        playPauseButton = findViewById(R.id.play_pause_button)
        titleTextView = findViewById(R.id.title_text)
        
        // 初始化MediaBrowser
        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MediaService::class.java),
            connectionCallback,
            null
        )
        
        // 设置按钮点击事件
        playPauseButton.setOnClickListener {
            handlePlayPause()
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            try {
                // 获取MediaController
                mediaController = MediaControllerCompat(this@MainActivity, mediaBrowser.sessionToken)
                mediaController?.registerCallback(controllerCallback)
                
                // 订阅媒体数据
                mediaBrowser.subscribe(mediaBrowser.root, subscriptionCallback)
                
                // 更新UI
                updatePlaybackState(mediaController?.playbackState)
                updateMetadata(mediaController?.metadata)
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to MediaBrowser", e)
            }
        }

        override fun onConnectionFailed() {
            Log.e(TAG, "Connection failed")
        }

        override fun onConnectionSuspended() {
            Log.e(TAG, "Connection suspended")
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
        }
    }

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(
            parentId: String,
            children: MutableList<MediaBrowserCompat.MediaItem>
        ) {
            // 处理媒体列表数据
            for (item in children) {
                Log.d(TAG, "Media item: ${item.description.title}")
            }
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            updatePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            updateMetadata(metadata)
        }
    }

    private fun updatePlaybackState(state: PlaybackStateCompat?) {
        when (state?.state) {
            PlaybackStateCompat.STATE_PLAYING -> {
                playPauseButton.text = "暂停"
            }
            PlaybackStateCompat.STATE_PAUSED -> {
                playPauseButton.text = "播放"
            }
            else -> {
                playPauseButton.text = "播放"
            }
        }
    }

    private fun updateMetadata(metadata: MediaMetadataCompat?) {
        titleTextView.text = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "无标题"
    }

    private fun handlePlayPause() {
        val state = mediaController?.playbackState?.state
        when (state) {
            PlaybackStateCompat.STATE_PLAYING -> {
                mediaController?.transportControls?.pause()
            }
            else -> {
                mediaController?.transportControls?.play()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mediaBrowser.connect()
    }

    override fun onStop() {
        super.onStop()
        mediaController?.unregisterCallback(controllerCallback)
        mediaBrowser.disconnect()
    }
} 