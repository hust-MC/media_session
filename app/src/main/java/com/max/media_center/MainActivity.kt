package com.max.media_center

import android.annotation.SuppressLint
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
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null
    
    private lateinit var playPauseButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var playModeButton: ImageButton
    private lateinit var playlistButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    
    private val playModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received: ${intent?.action}")
            when (intent?.action) {
                "PLAY_MODE_CHANGED" -> {
                    val playModeName = intent.getStringExtra("playMode")
                    Log.d(TAG, "Play mode changed to: $playModeName")
                    updatePlayModeButton(playModeName)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 启动后台服务以支持后台播放
        val serviceIntent = Intent(this, MediaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        playPauseButton = findViewById(R.id.play_pause_button)
        prevButton = findViewById(R.id.prev_button)
        nextButton = findViewById(R.id.next_button)
        playModeButton = findViewById(R.id.play_mode_button)
        playlistButton = findViewById(R.id.playlist_button)
        titleText = findViewById(R.id.title_text)
        artistText = findViewById(R.id.artist_text)
        seekBar = findViewById(R.id.progress_bar)
        currentTimeText = findViewById(R.id.current_time)
        totalTimeText = findViewById(R.id.total_time)
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
        
        playlistButton.setOnClickListener {
            val intent = Intent(this, PlaylistActivity::class.java)
            startActivity(intent)
        }
        
        playModeButton.setOnClickListener {
            Log.d(TAG, "Play mode button clicked")
            // 通过MediaBrowser获取MediaService实例并切换播放模式
            val intent = Intent(this, MediaService::class.java)
            intent.action = "SWITCH_PLAY_MODE"
            startService(intent)
        }

        // 进度条拖动监听
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // 用户拖动时更新当前时间显示
                    currentTimeText.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // 开始拖动时可以暂停进度更新
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 停止拖动时跳转到指定位置
                mediaController?.transportControls?.seekTo(seekBar?.progress?.toLong() ?: 0)
            }
        })
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
            updateProgress(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            metadata?.let {
                titleText.text = it.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "未知歌曲"
                artistText.text = it.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "未知艺术家"
                // 更新总时长
                val duration = it.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
                seekBar.max = duration.toInt()
                totalTimeText.text = formatTime(duration)
            }
        }
    }

    private fun updatePlayPauseButton(state: Int) {
        playPauseButton.setImageResource(when (state) {
            PlaybackStateCompat.STATE_PLAYING -> R.drawable.ic_pause
            else -> R.drawable.ic_play
        })
    }

    private fun updateProgress(state: PlaybackStateCompat?) {
        val currentPosition = state?.position ?: 0
        seekBar.progress = currentPosition.toInt()
        currentTimeText.text = formatTime(currentPosition)
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

    private fun formatTime(millis: Long): String {
        return String.format("%02d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) -
                    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        )
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(playModeReceiver)
        } catch (e: Exception) {
            // 忽略已经注销的异常
        }
    }
} 