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
    private lateinit var titleText: TextView
    
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        playPauseButton = findViewById(R.id.play_pause_button)
        titleText = findViewById(R.id.title_text)
        titleText.text = "JJ - 不为谁而作的歌"
        
        // 初始化MediaBrowser
        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MediaService::class.java),
            connectionCallback,
            null
        )
        
        // 设置按钮点击事件
        playPauseButton.setOnClickListener {
            mediaController?.let { controller ->
                when (controller.playbackState?.state) {
                    PlaybackStateCompat.STATE_PLAYING -> controller.transportControls.pause()
                    else -> controller.transportControls.play()
                }
            }
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            mediaController = MediaControllerCompat(this@MainActivity, mediaBrowser.sessionToken).apply {
                registerCallback(mediaControllerCallback)
            }
            MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
            updatePlayPauseButton(mediaController?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE)
        }

        override fun onConnectionFailed() {
            mediaController = null
        }

        override fun onConnectionSuspended() {
            mediaController?.unregisterCallback(mediaControllerCallback)
            mediaController = null
        }
    }

    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            updatePlayPauseButton(state?.state ?: PlaybackStateCompat.STATE_NONE)
        }
    }

    private fun updatePlayPauseButton(state: Int) {
        playPauseButton.text = when (state) {
            PlaybackStateCompat.STATE_PLAYING -> "暂停"
            else -> "播放"
        }
    }

    override fun onStart() {
        super.onStart()
        mediaBrowser.connect()
    }

    override fun onStop() {
        super.onStop()
        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaBrowser.disconnect()
    }
} 