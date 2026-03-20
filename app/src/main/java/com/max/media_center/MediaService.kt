package com.max.media_center

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.max.media_center.MainActivity.Companion.INTENT_PLAY_MODE
import java.lang.reflect.Field

/**
 * 媒体播放后台服务，继承 MediaBrowserServiceCompat。
 * 负责：从 raw 加载音乐列表、MediaPlayer 播放控制、MediaSession 与通知栏展示、
 * 播放模式（顺序/随机/单曲循环）切换并通过广播通知主界面，以及向 MediaBrowser 客户端提供媒体列表与播放能力。
 */
class MediaService : MediaBrowserServiceCompat() {
    /** 媒体会话，用于与 MediaController 及系统媒体控件交互 */
    private lateinit var mediaSession: MediaSessionCompat
    /** 实际执行音频播放的 MediaPlayer */
    private lateinit var mediaPlayer: MediaPlayer
    /** 当前播放状态：无、播放中、暂停、停止、错误等 */
    private var currentState = PlaybackStateCompat.STATE_NONE
    /** 当前播放项在 musicList 中的索引 */
    private var currentIndex = 0
    /** 音乐列表，从 res/raw 扫描并填充元数据 */
    private val musicList = mutableListOf<MusicItem>()
    /** 唤醒锁（若使用），防止播放时 CPU 休眠 */
    private var wakeLock: PowerManager.WakeLock? = null
    /** 当前播放模式：顺序、随机、单曲循环 */
    private var currentPlayMode = PlayMode.SEQUENTIAL
    /** 主线程 Handler，用于延迟任务与 Toast */
    private val handler = Handler(Looper.getMainLooper())
    /** 周期性更新播放进度的 Runnable */
    private lateinit var progressUpdater: Runnable

    // ---- AudioFocus 相关 ----
    /** 系统音频管理器，用于请求和释放音频焦点 */
    private lateinit var audioManager: AudioManager
    /** Android 8.0+ 的音频焦点请求对象 */
    private lateinit var audioFocusRequest: AudioFocusRequest
    /** 临时失去焦点时记录是否需要在重获焦点后恢复播放 */
    private var playOnAudioFocus = false

    // ---- 持久化相关 ----
    /** 用于保存/恢复播放状态的 SharedPreferences */
    private lateinit var prefs: SharedPreferences

    /**
     * 单曲数据模型。name 为 raw 资源名，resourceId 为 R.raw.xxx 的 id，title/artist/coverArt 从文件元数据解析。
     */
    data class MusicItem(
        val name: String,
        val resourceId: Int,
        var title: String = "",
        var artist: String = "",
        var coverArt: Bitmap? = null
    )

    /** 播放模式枚举：顺序播放、随机播放、单曲循环 */
    enum class PlayMode {
        SEQUENTIAL,  // 顺序播放
        SHUFFLE,     // 随机播放
        REPEAT_ONE   // 单曲循环
    }

    companion object {
        private const val TAG = "MediaService"
        /** MediaBrowser 根节点 ID，客户端通过此 ID 订阅子列表 */
        private const val MEDIA_ID_ROOT = "__ROOT__"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "media_playback_channel"
        /** 进度更新间隔（毫秒） */
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
        /** 专辑封面解码时的压缩比例，避免大图 OOM */
        private const val IMAGE_COMPRESSION_RATIO = 2
        /** Duck 时降低到的音量比例 */
        private const val DUCK_VOLUME = 0.2f
        /** 正常音量 */
        private const val FULL_VOLUME = 1.0f
        // 持久化 Key
        private const val PREFS_NAME = "media_player_prefs"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_CURRENT_POSITION = "current_position"
        private const val KEY_PLAY_MODE = "play_mode"
    }

    /**
     * 服务创建时调用。加载音乐列表、初始化 MediaPlayer 与进度更新器、创建 MediaSession 与通知渠道，并暴露 sessionToken。
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MediaService onCreate")

        // 初始化 AudioManager 与 AudioFocusRequest
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        }

        // 初始化 SharedPreferences
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 从 res/raw 扫描所有音频并解析元数据
        loadMusicList()
        Log.d(TAG, "MediaService loaded ${musicList.size} music items")

        // 创建MediaPlayer并配置
        mediaPlayer = MediaPlayer().apply {
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setOnCompletionListener {
                playNext()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: $what, $extra")
                currentState = PlaybackStateCompat.STATE_ERROR
                updatePlaybackState()
                showToast(getString(R.string.error_playback_failed))
                true // 返回true表示已处理错误
            }
        }

        // 初始化进度更新器
        progressUpdater = Runnable {
            if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                updatePlaybackState()
                handler.postDelayed(progressUpdater, PROGRESS_UPDATE_INTERVAL)
            }
        }
        
        // 创建MediaSession
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        mediaSession = MediaSessionCompat(this, getString(R.string.media_service_tag)).apply {
            setSessionActivity(sessionActivityPendingIntent)
            setCallback(MediaSessionCallback())
            isActive = true
        }
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 设置初始播放状态
        updatePlaybackState()
        
        // 创建初始通知，确保前台服务启动
        updateNotification()
        
        sessionToken = mediaSession.sessionToken

        // 恢复上次保存的播放状态（索引、进度、播放模式）
        restorePlaybackState()
    }

    /**
     * 每次通过 startService 启动或媒体按钮触发时调用。先交给 MediaButtonReceiver 处理播放/暂停等，再处理切换播放模式的 action 并发送广播。
     * @param intent 启动意图，action 可能为切换播放模式等
     * @param flags 启动标志
     * @param startId 本次启动 ID
     * @return 由父类决定的返回值
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        // 耳机/通知栏等媒体按钮事件统一由此处理
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            getString(R.string.action_switch_play_mode) -> {
                Log.d(TAG, "Switching play mode from: $currentPlayMode")
                switchPlayMode()
                Log.d(TAG, "Switched play mode to: $currentPlayMode")
                // 广播播放模式变化，供 MainActivity 更新播放模式按钮
                val broadcastIntent = Intent(getString(R.string.action_play_mode_changed))
                broadcastIntent.putExtra(INTENT_PLAY_MODE, currentPlayMode.name)
                broadcastIntent.setPackage(packageName)
                sendBroadcast(broadcastIntent)
                Log.d(TAG, "Broadcast sent: ${currentPlayMode.name}")
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 在主线程中显示 Toast。可在子线程中安全调用。
     * @param message 要显示的提示文案
     */
    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 创建媒体播放通知渠道。Android 8.0 及以上必须创建渠道后通知才会显示。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.media_playback_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.media_playback_channel_description)
            }
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 更新前台通知：标题/艺术家、播放状态副标题、上一首/播放暂停/下一首按钮，并保持前台服务。
     */
    private fun updateNotification() {
        val musicItem = musicList.getOrNull(currentIndex)
        val title = musicItem?.title?.takeIf { it.isNotEmpty() } ?: musicItem?.name
        ?: getString(R.string.unknown_song)
        val artist =
            musicItem?.artist?.takeIf { it.isNotEmpty() } ?: getString(R.string.unknown_artist)
        
        // 创建播放/暂停动作
        val playPauseAction = if (currentState == PlaybackStateCompat.STATE_PLAYING) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                getString(R.string.pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PAUSE
                )
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                getString(R.string.play), 
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PLAY
                )
            ).build()
        }
        
        // 创建上一首动作
        val prevAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous,
            getString(R.string.previous),
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
        ).build()
        
        // 创建下一首动作
        val nextAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next,
            getString(R.string.next),
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            )
        ).build()
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(
                if (currentState == PlaybackStateCompat.STATE_PLAYING) getString(R.string.now_playing) else getString(
                    R.string.paused
                )
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_STOP
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(prevAction)
            .addAction(playPauseAction) 
            .addAction(nextAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2) // 显示所有3个按钮
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this,
                            PlaybackStateCompat.ACTION_STOP
                        )
                    )
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * 通过反射遍历 R.raw 下所有资源，构建 MusicItem 列表并解析每首的标题、艺术家、封面（若有），失败时 Toast 提示。
     */
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
                    
                    musicItem.title =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                    musicItem.artist =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                    
                    // 提取专辑封面并压缩
                    val artBytes = retriever.embeddedPicture
                    if (artBytes != null) {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = IMAGE_COMPRESSION_RATIO // 压缩图片，避免内存溢出
                        }
                        musicItem.coverArt =
                            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
                    }
                    
                    retriever.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting metadata for $resourceName", e)
                }
                
                musicList.add(musicItem)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading music list", e)
            showToast(getString(R.string.error_load_music_list_failed))
        }
    }

    /**
     * 播放 musicList 中指定索引的歌曲。重置 MediaPlayer、设置数据源、准备并开始播放，更新 MediaSession 元数据与通知，并启动进度更新。
     * @param index 歌曲在 musicList 中的下标，越界则直接返回
     */
    private fun playMusic(index: Int) {
        if (index < 0 || index >= musicList.size) return

        currentIndex = index
        val musicItem = musicList[index]

        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(
                applicationContext,
                Uri.parse("android.resource://${packageName}/raw/${musicItem.name}")
            )
            mediaPlayer.prepare()
            mediaPlayer.start()
            currentState = PlaybackStateCompat.STATE_PLAYING

            updateMetadata(musicItem)
            updatePlaybackState()
            updateNotification()

            handler.post(progressUpdater)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing music", e)
            showToast(getString(R.string.error_play_music_failed))
        }
    }

    /**
     * 播放下一首。顺序模式为列表下一项（循环）；随机模式随机下标；单曲循环不改变索引。列表为空时 Toast 提示。
     */
    private fun playNext() {
        if (musicList.isEmpty()) {
            showToast(getString(R.string.error_playlist_empty_operation))
            return
        }
        when (currentPlayMode) {
            PlayMode.SEQUENTIAL -> {
                currentIndex = (currentIndex + 1) % musicList.size
            }
            PlayMode.SHUFFLE -> {
                currentIndex = (0 until musicList.size).random()
            }
            PlayMode.REPEAT_ONE -> {
                // 单曲循环：索引不变，相当于重播当前首
            }
        }
        playMusic(currentIndex)
    }

    /**
     * 播放上一首。顺序模式为列表上一项（循环）；随机模式随机下标；单曲循环不改变索引。列表为空时 Toast 提示。
     */
    private fun playPrevious() {
        if (musicList.isEmpty()) {
            showToast(getString(R.string.error_playlist_empty_operation))
            return
        }
        when (currentPlayMode) {
            PlayMode.SEQUENTIAL -> {
                currentIndex = (currentIndex - 1 + musicList.size) % musicList.size
            }

            PlayMode.SHUFFLE -> {
                currentIndex = (0 until musicList.size).random()
            }

            PlayMode.REPEAT_ONE -> {
                // 单曲循环：索引不变
            }
        }
        playMusic(currentIndex)
    }

    /**
     * 切换播放模式：顺序 -> 随机 -> 单曲循环 -> 顺序。仅修改 currentPlayMode，不自动播放；主界面通过广播获取新模式并更新图标。
     */
    fun switchPlayMode() {
        currentPlayMode = when (currentPlayMode) {
            PlayMode.SEQUENTIAL -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SEQUENTIAL
        }
    }

    /**
     * 获取当前播放模式。
     * @return 当前 PlayMode 枚举值
     */
    fun getCurrentPlayMode(): PlayMode = currentPlayMode

    /**
     * MediaBrowser 请求根节点时调用。允许连接则返回以 MEDIA_ID_ROOT 为 id 的 BrowserRoot。
     * @param clientPackageName 客户端包名
     * @param clientUid 客户端 UID
     * @param rootHints 可选的根节点参数
     * @return 根节点，null 表示拒绝连接
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(MEDIA_ID_ROOT, null)
    }

    /**
     * 客户端订阅某父节点子列表时调用。仅当 parentId 为 MEDIA_ID_ROOT 时返回 musicList 对应的 MediaItem 列表，否则返回 null。
     * @param parentId 父节点 ID
     * @param result 用于发送结果的回调
     */
    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        Log.d(TAG, "onLoadChildren called with parentId: $parentId")
        
        if (parentId == MEDIA_ID_ROOT) {
            val mediaItems = musicList.map { musicItem ->
                val description = MediaDescriptionCompat.Builder()
                    .setMediaId(musicItem.resourceId.toString())
                    .setTitle(musicItem.title.takeIf { it.isNotEmpty() } ?: musicItem.name)
                    .setSubtitle(musicItem.artist.takeIf { it.isNotEmpty() }
                        ?: getString(R.string.unknown_artist))
                    .setIconBitmap(musicItem.coverArt)
                    .build()
                MediaBrowserCompat.MediaItem(
                    description,
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            }.toMutableList()
            
            Log.d(TAG, "Returning ${mediaItems.size} items to client")
            result.sendResult(mediaItems)
        } else {
            Log.d(TAG, "Unknown parentId: $parentId, returning null")
            result.sendResult(null)
        }
    }

    // ==================== AudioFocus ====================

    /**
     * 音频焦点变化监听器。处理四种场景：
     * GAIN（重获焦点，恢复播放/音量）、LOSS（永久失去，暂停）、
     * LOSS_TRANSIENT（临时失去，暂停并记录恢复标记）、LOSS_TRANSIENT_CAN_DUCK（降低音量继续播放）。
     */
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AudioFocus: GAIN")
                if (playOnAudioFocus) {
                    mediaPlayer.start()
                    currentState = PlaybackStateCompat.STATE_PLAYING
                    updatePlaybackState()
                    handler.post(progressUpdater)
                    playOnAudioFocus = false
                }
                mediaPlayer.setVolume(FULL_VOLUME, FULL_VOLUME)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "AudioFocus: LOSS")
                playOnAudioFocus = false
                if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                    mediaPlayer.pause()
                    currentState = PlaybackStateCompat.STATE_PAUSED
                    updatePlaybackState()
                    handler.removeCallbacks(progressUpdater)
                    savePlaybackState()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "AudioFocus: LOSS_TRANSIENT")
                if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                    playOnAudioFocus = true
                    mediaPlayer.pause()
                    currentState = PlaybackStateCompat.STATE_PAUSED
                    updatePlaybackState()
                    handler.removeCallbacks(progressUpdater)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "AudioFocus: LOSS_TRANSIENT_CAN_DUCK")
                if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                    mediaPlayer.setVolume(DUCK_VOLUME, DUCK_VOLUME)
                }
            }
        }
    }

    /**
     * 请求音频焦点。播放前必须调用，未获得焦点时不应开始播放。
     * @return 是否成功获取焦点
     */
    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(audioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * 释放音频焦点。在停止播放或服务销毁时调用。
     */
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    // ==================== 持久化 ====================

    /**
     * 保存当前播放状态（索引、进度、播放模式）到 SharedPreferences。
     */
    private fun savePlaybackState() {
        val position = if (mediaPlayer.isPlaying || currentState == PlaybackStateCompat.STATE_PAUSED) {
            mediaPlayer.currentPosition.toLong()
        } else {
            0L
        }
        prefs.edit()
            .putInt(KEY_CURRENT_INDEX, currentIndex)
            .putLong(KEY_CURRENT_POSITION, position)
            .putString(KEY_PLAY_MODE, currentPlayMode.name)
            .apply()
        Log.d(TAG, "Saved state: index=$currentIndex, position=$position, mode=$currentPlayMode")
    }

    /**
     * 从 SharedPreferences 恢复上次的播放状态。恢复索引、播放模式，并准备歌曲（不自动播放）。
     */
    private fun restorePlaybackState() {
        currentIndex = prefs.getInt(KEY_CURRENT_INDEX, 0)
        val savedPosition = prefs.getLong(KEY_CURRENT_POSITION, 0)
        val savedMode = prefs.getString(KEY_PLAY_MODE, PlayMode.SEQUENTIAL.name)

        currentPlayMode = try {
            PlayMode.valueOf(savedMode ?: PlayMode.SEQUENTIAL.name)
        } catch (e: Exception) {
            PlayMode.SEQUENTIAL
        }

        if (currentIndex in musicList.indices) {
            prepareMusic(currentIndex, savedPosition)
        }
        Log.d(TAG, "Restored state: index=$currentIndex, position=$savedPosition, mode=$currentPlayMode")
    }

    /**
     * 仅准备歌曲（不自动开始播放）。用于服务启动时恢复上次状态，设置数据源、seek 到上次位置、更新元数据，状态置为 PAUSED。
     * @param index 歌曲索引
     * @param seekPosition 需要 seek 到的位置（毫秒），0 表示从头
     */
    private fun prepareMusic(index: Int, seekPosition: Long = 0) {
        if (index < 0 || index >= musicList.size) return

        currentIndex = index
        val musicItem = musicList[index]

        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(
                applicationContext,
                Uri.parse("android.resource://${packageName}/raw/${musicItem.name}")
            )
            mediaPlayer.prepare()

            if (seekPosition > 0 && seekPosition < mediaPlayer.duration) {
                mediaPlayer.seekTo(seekPosition.toInt())
            }

            currentState = PlaybackStateCompat.STATE_PAUSED
            updateMetadata(musicItem)
            updatePlaybackState()
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing music", e)
        }
    }

    /**
     * 将 MusicItem 的元数据设置到 MediaSession（标题、艺术家、时长、封面），供 MediaController 回调使用。
     * @param musicItem 要更新的歌曲数据
     */
    private fun updateMetadata(musicItem: MusicItem) {
        val duration = mediaPlayer.duration.toLong()
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, musicItem.resourceId.toString())
            .putString(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                musicItem.title.takeIf { it.isNotEmpty() } ?: musicItem.name
            )
            .putString(
                MediaMetadataCompat.METADATA_KEY_ARTIST,
                musicItem.artist.takeIf { it.isNotEmpty() } ?: getString(R.string.unknown_artist)
            )
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, musicItem.coverArt)
            .build()
        mediaSession.setMetadata(metadata)
    }

    /**
     * 根据 currentState 和 mediaPlayer.currentPosition 构建 PlaybackStateCompat 并设置到 MediaSession，同时刷新通知栏。
     */
    private fun updatePlaybackState() {
        val currentPosition =
            if (currentState == PlaybackStateCompat.STATE_PLAYING || currentState == PlaybackStateCompat.STATE_PAUSED) {
                mediaPlayer.currentPosition.toLong()
            } else {
                0L
            }
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(currentState, currentPosition, 1.0f)
        mediaSession.setPlaybackState(stateBuilder.build())
        updateNotification()
    }

    /**
     * MediaSession 回调：处理播放、暂停、上一首、下一首、按 mediaId 播放、seek、停止等。与 MediaController/通知栏/耳机按键一一对应。
     */
    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        /**
         * 用户请求播放时调用。若列表为空则 Toast 并返回；若当前为暂停则恢复播放；否则从 currentIndex 开始播放。
         */
        override fun onPlay() {
            if (currentState != PlaybackStateCompat.STATE_PLAYING) {
                // 先请求音频焦点，未获得则不播放
                if (!requestAudioFocus()) {
                    Log.w(TAG, "Cannot acquire audio focus")
                    showToast(getString(R.string.error_audio_focus_failed))
                    return
                }

                if (musicList.isEmpty()) {
                    Log.w(TAG, "Cannot play: music list is empty")
                    Toast.makeText(this@MediaService, getString(R.string.playlist_empty), Toast.LENGTH_SHORT).show()
                    return
                }

                if (currentState == PlaybackStateCompat.STATE_PAUSED) {
                    mediaPlayer.start()
                    currentState = PlaybackStateCompat.STATE_PLAYING
                    updatePlaybackState()
                    handler.post(progressUpdater)
                } else {
                    playMusic(currentIndex)
                }
            }
        }

        /** 用户请求暂停时，暂停 MediaPlayer、停止进度更新并保存播放状态 */
        override fun onPause() {
            if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                mediaPlayer.pause()
                currentState = PlaybackStateCompat.STATE_PAUSED
                updatePlaybackState()
                handler.removeCallbacks(progressUpdater)
                savePlaybackState()
            }
        }

        /** 用户请求下一首时，调用 playNext() */
        override fun onSkipToNext() {
            playNext()
        }

        /** 用户请求上一首时，调用 playPrevious() */
        override fun onSkipToPrevious() {
            playPrevious()
        }

        /**
         * 按 mediaId 播放（如从播放列表点击某首）。mediaId 对应 MusicItem.resourceId，找到索引后调用 playMusic。
         * @param mediaId 媒体 ID，可为 null
         * @param extras 可选附加参数
         */
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.d(TAG, "onPlayFromMediaId called with mediaId: $mediaId")
            mediaId?.toIntOrNull()?.let { resourceId ->
                val index = musicList.indexOfFirst { it.resourceId == resourceId }
                if (index != -1) {
                    playMusic(index)
                } else {
                    Log.e(TAG, "Song with resourceId $resourceId not found in playlist")
                    showToast(getString(R.string.error_song_not_found))
                }
            }
        }

        /** 用户拖动进度到 pos 时调用，将 MediaPlayer seek 到指定位置并更新状态 */
        override fun onSeekTo(pos: Long) {
            mediaPlayer.seekTo(pos.toInt())
            updatePlaybackState()
        }

        /** 用户点击通知栏停止时调用：保存状态、停止播放、释放焦点、移除前台通知并 stopSelf */
        override fun onStop() {
            savePlaybackState()

            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
            }
            handler.removeCallbacks(progressUpdater)
            currentState = PlaybackStateCompat.STATE_STOPPED
            updatePlaybackState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            abandonAudioFocus()
            stopSelf()
        }
    }

    /** 任务被移除（用户划掉应用）时保存播放状态并停止服务 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        savePlaybackState()
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    /** 服务销毁时保存状态、释放焦点、移除进度更新、释放 MediaSession 与 MediaPlayer、释放 WakeLock */
    override fun onDestroy() {
        savePlaybackState()
        abandonAudioFocus()
        handler.removeCallbacks(progressUpdater)
        mediaSession.release()
        mediaPlayer.release()
        wakeLock?.release()
        super.onDestroy()
    }
} 