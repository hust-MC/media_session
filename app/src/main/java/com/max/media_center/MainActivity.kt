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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.max.media_center.MediaService.PlayMode.SEQUENTIAL
import com.max.media_center.MediaService.PlayMode.SHUFFLE
import com.max.media_center.MediaService.PlayMode.REPEAT_ONE
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
    private lateinit var albumArt: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    /** 默认播放模式 */
    private val DEFAULT_PLAY_MODE : String by lazy {
        SEQUENTIAL.name
    }

    private val playModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received: ${intent?.action}")
            when (intent?.action) {
                getString(R.string.action_play_mode_changed) -> {
                    val playModeName = intent.getStringExtra(INTENT_PLAY_MODE)
                    Log.d(TAG, "Play mode changed to: $playModeName")
                    updatePlayModeButton(playModeName)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        const val INTENT_PLAY_MODE = "playMode"
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
        albumArt = findViewById(R.id.album_art_placeholder)
        seekBar = findViewById(R.id.progress_bar)
        currentTimeText = findViewById(R.id.current_time)
        totalTimeText = findViewById(R.id.total_time)
        titleText.text = getString(R.string.car_media_player)
        artistText.text = getString(R.string.please_select_song)
        
        // 注册广播接收器
        val filter = IntentFilter(getString(R.string.action_play_mode_changed))
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
            intent.action = getString(R.string.action_switch_play_mode)
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

    /**
     * Activity生命周期方法：当Activity变为可见时调用。
     * 我们的策略是：
     * 1. 如果尚未连接到MediaBrowserService，则发起连接。
     * 2. 如果已经连接（例如从播放列表返回），则重新注册回调并立即同步UI状态。
     */
    override fun onStart() {
        super.onStart()
        if (!mediaBrowser.isConnected) {
            mediaBrowser.connect()
        } else if (mediaController != null) {
            // 如果已连接，重新注册回调并同步UI
            mediaController?.registerCallback(mediaControllerCallback)
            mediaControllerCallback.onPlaybackStateChanged(mediaController?.playbackState)
            mediaControllerCallback.onMetadataChanged(mediaController?.metadata)
        }
    }

    /**
     * Activity生命周期方法：当Activity不再可见时调用。
     * 我们在这里注销回调，以防止在Activity不可见时更新UI，从而避免内存泄漏和不必要的资源消耗。
     * 我们不在此处断开连接，以保持后台播放并能快速恢复。
     */
    override fun onStop() {
        super.onStop()
        // 当Activity不可见时，注销回调以避免内存泄漏和不必要的工作
        mediaController?.unregisterCallback(mediaControllerCallback)
    }
    
    /**
     * Activity生命周期方法：当Activity被销毁时调用。
     * 这是断开与MediaBrowserService连接的正确时机。
     */
    override fun onDestroy() {
        super.onDestroy()
        // 在Activity销毁时才断开连接
        if (mediaBrowser.isConnected) {
            mediaBrowser.disconnect()
        }
        try {
            unregisterReceiver(playModeReceiver)
        } catch (e: Exception) {
            // 忽略已经注销的异常
        }
    }

    /**
     * MediaBrowser连接状态的回调接口。
     */
    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        /**
         * 当成功连接到MediaBrowserService时调用。
         * 这是初始化MediaController并首次同步UI状态的关键点。
         */
        override fun onConnected() {
            if (mediaController == null) {
                // MediaController是UI和MediaSession之间的桥梁
                mediaController = MediaControllerCompat(this@MainActivity, mediaBrowser.sessionToken)
                MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
            }
            // 注册回调以接收来自MediaSession的状态变化
            mediaController?.registerCallback(mediaControllerCallback)
            
            // 手动调用一次回调方法，以确保UI立即反映当前的播放状态
            mediaControllerCallback.onPlaybackStateChanged(mediaController?.playbackState)
            mediaControllerCallback.onMetadataChanged(mediaController?.metadata)
            updatePlayModeButton(DEFAULT_PLAY_MODE)
        }

        override fun onConnectionFailed() {
            mediaController = null
            Toast.makeText(this@MainActivity, getString(R.string.connection_failed), Toast.LENGTH_SHORT).show()
        }

        override fun onConnectionSuspended() {
            mediaController?.unregisterCallback(mediaControllerCallback)
            mediaController = null
            Toast.makeText(this@MainActivity, getString(R.string.error_connection_suspended), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * MediaController的回调接口，用于接收来自MediaSession的状态更新。
     * 无论Activity是否在前台，只要回调被注册，这些方法就会在播放状态或元数据变化时被调用。
     */
    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        /**
         * 当播放状态（播放、暂停、缓冲等）改变时调用。
         */
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            updatePlayPauseButton(state?.state ?: PlaybackStateCompat.STATE_NONE)
            updateProgress(state)
        }

        /**
         * 当正在播放的媒体项目元数据（歌曲标题、艺术家、时长等）改变时调用。
         */
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            metadata?.let {
                titleText.text = it.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: getString(R.string.unknown_song)
                artistText.text = it.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: getString(R.string.unknown_artist)

                // 显示专辑封面
                val art = it.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
                if (art != null) {
                    albumArt.setImageBitmap(art)
                } else {
                    // 如果没有封面，显示一个默认的占位图或颜色
                    albumArt.setImageResource(android.R.drawable.ic_menu_gallery)
                }

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
            SEQUENTIAL.name -> R.drawable.ic_repeat_all
            SHUFFLE.name -> R.drawable.ic_shuffle
            REPEAT_ONE.name -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat_all
        }
        playModeButton.setImageResource(iconRes)
    }

    /**
     * 格式化时间显示
     * @param millis 毫秒数
     * @return 格式化的时间字符串 (MM:SS)
     */
    private fun formatTime(millis: Long): String {
        return String.format(getString(R.string.time_format),
            TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) -
                    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        )
    }
} 