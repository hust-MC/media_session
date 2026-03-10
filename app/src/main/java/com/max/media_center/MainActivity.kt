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

/**
 * 主界面 Activity，负责车载音乐播放器的主屏展示与播放控制。
 * 通过 MediaBrowser 连接 MediaService，使用 MediaController 控制播放、切换歌曲、调节进度，
 * 并监听播放模式变更广播以更新播放模式按钮图标。
 */
class MainActivity : AppCompatActivity() {
    /** 用于连接 MediaBrowserService 的浏览器实例 */
    private lateinit var mediaBrowser: MediaBrowserCompat
    /** 媒体控制器，UI 与 MediaSession 之间的桥梁，用于发送播放指令并接收状态回调 */
    private var mediaController: MediaControllerCompat? = null

    /** 播放/暂停按钮 */
    private lateinit var playPauseButton: ImageButton
    /** 上一首按钮 */
    private lateinit var prevButton: ImageButton
    /** 下一首按钮 */
    private lateinit var nextButton: ImageButton
    /** 播放模式切换按钮（顺序/随机/单曲循环） */
    private lateinit var playModeButton: ImageButton
    /** 播放列表入口按钮 */
    private lateinit var playlistButton: ImageButton
    /** 歌曲标题文本 */
    private lateinit var titleText: TextView
    /** 艺术家文本 */
    private lateinit var artistText: TextView
    /** 专辑封面图片 */
    private lateinit var albumArt: ImageView
    /** 播放进度条 */
    private lateinit var seekBar: SeekBar
    /** 当前播放时间显示 */
    private lateinit var currentTimeText: TextView
    /** 总时长显示 */
    private lateinit var totalTimeText: TextView

    /** 默认播放模式（顺序播放），用于连接成功后初始化播放模式按钮图标 */
    private val DEFAULT_PLAY_MODE: String by lazy {
        SEQUENTIAL.name
    }

    /** 接收播放模式变更广播，用于在服务端切换模式后更新主界面播放模式按钮 */
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
        /** 播放模式广播 Intent 的 extra 键名，值为当前 PlayMode 的 name */
        const val INTENT_PLAY_MODE = "playMode"
    }

    /**
     * Activity 创建时调用。启动前台服务、绑定视图、注册广播、初始化 MediaBrowser 并设置各按钮点击与进度条拖动监听。
     * @param savedInstanceState 若从重建恢复则非 null
     */
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
     * Activity 变为可见时调用。
     * 若尚未连接 MediaBrowserService 则发起连接；若已连接（如从播放列表返回）则重新注册 MediaController 回调并立即同步一次播放状态与元数据到 UI。
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
     * Activity 不可见时调用。
     * 仅注销 MediaController 回调，不断开 MediaBrowser 连接，以便保持后台播放并在返回时快速恢复 UI。
     */
    override fun onStop() {
        super.onStop()
        // 当Activity不可见时，注销回调以避免内存泄漏和不必要的工作
        mediaController?.unregisterCallback(mediaControllerCallback)
    }
    
    /**
     * Activity 销毁时调用。断开 MediaBrowser 连接并注销播放模式广播接收器。
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

    /** MediaBrowser 连接状态回调：处理连接成功、失败、挂起 */
    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        /**
         * 成功连接到 MediaBrowserService 时调用。创建并绑定 MediaController，注册状态回调并同步当前播放状态与元数据，更新播放模式按钮。
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

        /** 连接失败时调用，清空 controller 并提示用户 */
        override fun onConnectionFailed() {
            mediaController = null
            Toast.makeText(this@MainActivity, getString(R.string.connection_failed), Toast.LENGTH_SHORT).show()
        }

        /** 连接被挂起时调用，注销回调并提示重新连接 */
        override fun onConnectionSuspended() {
            mediaController?.unregisterCallback(mediaControllerCallback)
            mediaController = null
            Toast.makeText(this@MainActivity, getString(R.string.error_connection_suspended), Toast.LENGTH_SHORT).show()
        }
    }

    /** MediaController 回调：在播放状态或元数据变化时更新主界面 UI */
    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        /**
         * 播放状态变化时调用。更新播放/暂停按钮图标和进度条、当前时间显示。
         * @param state 当前播放状态，可为 null
         */
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            updatePlayPauseButton(state?.state ?: PlaybackStateCompat.STATE_NONE)
            updateProgress(state)
        }

        /**
         * 当前媒体元数据变化时调用。更新标题、艺术家、专辑封面、总时长及进度条最大值。
         * @param metadata 当前媒体元数据，可为 null
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

    /**
     * 根据播放状态更新播放/暂停按钮图标。
     * @param state 播放状态，如 STATE_PLAYING、STATE_PAUSED 等
     */
    private fun updatePlayPauseButton(state: Int) {
        playPauseButton.setImageResource(when (state) {
            PlaybackStateCompat.STATE_PLAYING -> R.drawable.ic_pause
            else -> R.drawable.ic_play
        })
    }

    /**
     * 根据当前播放状态更新进度条和当前时间文本。
     * @param state 当前播放状态，从中取 position
     */
    private fun updateProgress(state: PlaybackStateCompat?) {
        val currentPosition = state?.position ?: 0
        seekBar.progress = currentPosition.toInt()
        currentTimeText.text = formatTime(currentPosition)
    }

    /**
     * 根据播放模式名称更新播放模式按钮图标。
     * @param playModeName 播放模式名称，如 SEQUENTIAL、SHUFFLE、REPEAT_ONE，为 null 时按顺序播放图标显示
     */
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
     * 将毫秒数格式化为 "分:秒" 字符串（如 03:45）。
     * @param millis 毫秒数
     * @return 格式化的时间字符串，格式由 R.string.time_format 定义（如 %02d:%02d）
     */
    private fun formatTime(millis: Long): String {
        return String.format(getString(R.string.time_format),
            TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) -
                    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        )
    }
} 