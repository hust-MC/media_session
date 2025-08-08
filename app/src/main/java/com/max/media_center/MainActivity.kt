package com.max.media_center

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null
    
    private lateinit var playPauseButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var playModeButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    
    private val playModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "PLAY_MODE_CHANGED" -> {
                    val playModeName = intent.getStringExtra("playMode")
                    updatePlayModeButton(playModeName)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        playPauseButton = findViewById(R.id.play_pause_button)
        prevButton = findViewById(R.id.prev_button)
        nextButton = findViewById(R.id.next_button)
        playModeButton = findViewById(R.id.play_mode_button)
        titleText = findViewById(R.id.title_text)
        artistText = findViewById(R.id.artist_text)
        titleText.text = "车载音乐播放器"
        artistText.text = "请选择歌曲"
        
        // 注册广播接收器
        val filter = IntentFilter("PLAY_MODE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playModeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(playModeReceiver, filter)
        }
        
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
        
        prevButton.setOnClickListener {
            mediaController?.transportControls?.skipToPrevious()
        }
        
        nextButton.setOnClickListener {
            mediaController?.transportControls?.skipToNext()
        }
        
        playModeButton.setOnClickListener {
            // 通过MediaBrowser获取MediaService实例并切换播放模式
            val intent = Intent(this, MediaService::class.java)
            intent.action = "SWITCH_PLAY_MODE"
            startService(intent)
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            mediaController = MediaControllerCompat(this@MainActivity, mediaBrowser.sessionToken).apply {
                registerCallback(mediaControllerCallback)
            }
            MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
            updatePlayPauseButton(mediaController?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE)
            // 初始化播放模式按钮
            updatePlayModeButton("SEQUENTIAL")
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

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            metadata?.let {
                titleText.text = it.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "未知歌曲"
                artistText.text = it.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "未知艺术家"
            }
        }
    }

    private fun updatePlayPauseButton(state: Int) {
        playPauseButton.setImageResource(when (state) {
            PlaybackStateCompat.STATE_PLAYING -> R.drawable.ic_pause
            else -> R.drawable.ic_play
        })
    }

    private fun updatePlayModeButton(playModeName: String?) {
        val iconRes = when (playModeName) {
            "SEQUENTIAL" -> R.drawable.ic_repeat_all
            "SHUFFLE" -> R.drawable.ic_shuffle
            "REPEAT_ONE" -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat_all
        }
        playModeButton.setImageResource(iconRes)
    }

    override fun onStart() {
        super.onStart()
        mediaBrowser.connect()
    }

    override fun onStop() {
        super.onStop()
        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaBrowser.disconnect()
        try {
            unregisterReceiver(playModeReceiver)
        } catch (e: Exception) {
            // 忽略已经注销的异常
        }
    }
} 