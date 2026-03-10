package com.max.media_center

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 播放列表界面。通过 MediaBrowserHelper 连接 MediaService，订阅媒体列表并展示在 RecyclerView 中；
 * 点击某首歌曲时通过 mediaId 请求播放，并根据当前播放项高亮对应条目。
 */
class PlaylistActivity : AppCompatActivity(), MediaBrowserHelper.MediaConnectionListener {
    /** 封装 MediaBrowser 连接与订阅的辅助类，在 onStart 连接、onStop 断开 */
    private lateinit var mediaBrowserHelper: MediaBrowserHelper
    /** 歌曲列表 RecyclerView */
    private lateinit var recyclerView: RecyclerView
    /** 歌曲列表适配器，负责展示条目并高亮当前播放项 */
    private lateinit var songAdapter: SongAdapter

    companion object {
        private const val TAG = "PlaylistActivity"
    }

    /**
     * Activity 创建时调用。设置标题、初始化 RecyclerView 与 SongAdapter（含点击播放逻辑），并创建 MediaBrowserHelper。
     * @param savedInstanceState 若从重建恢复则非 null
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        title = getString(R.string.playlist_title)

        // 初始化列表：线性布局 + 分割线
        recyclerView = findViewById(R.id.playlist_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        // 点击某条时通过 mediaId 请求服务端播放该曲并 Toast 提示
        songAdapter = SongAdapter { mediaItem ->
            Log.d(TAG, "User clicked on: ${mediaItem.description.title}")
            val mediaId = mediaItem.mediaId
            if (mediaId != null) {
                mediaBrowserHelper.getTransportControls()?.playFromMediaId(mediaId, null)
                Toast.makeText(this, getString(R.string.now_playing_format, mediaItem.description.title), Toast.LENGTH_SHORT).show()
            }
        }
        recyclerView.adapter = songAdapter

        mediaBrowserHelper = MediaBrowserHelper(this, this)
    }

    // ---------- MediaBrowserHelper.MediaConnectionListener 回调 ----------

    /**
     * 已连接到 MediaService 并拿到 MediaController 时调用。用当前正在播放的 mediaId 设置列表高亮。
     * @param controller 当前 MediaController
     */
    override fun onConnected(controller: MediaControllerCompat) {
        Log.d(TAG, "Media Service Connected")
        val currentMetadata = controller.metadata
        val currentMediaId = currentMetadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        songAdapter.setCurrentPlayingId(currentMediaId)
    }

    /**
     * 订阅的媒体子列表加载完成时调用。若列表为空则 Toast，否则将列表交给 Adapter 刷新展示。
     * @param items 媒体项列表（即歌曲列表）
     */
    override fun onChildrenLoaded(items: List<MediaBrowserCompat.MediaItem>) {
        Log.d(TAG, "onChildrenLoaded, item size = ${items.size}")
        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.playlist_empty), Toast.LENGTH_SHORT).show()
        }
        songAdapter.updateList(items)
    }

    /** 连接 MediaService 失败时调用，Toast 提示用户 */
    override fun onConnectionFailed() {
        Log.e(TAG, "Media Service connection failed")
        Toast.makeText(this, getString(R.string.connection_failed), Toast.LENGTH_SHORT).show()
    }

    /** 连接被挂起时调用，当前实现仅打日志 */
    override fun onConnectionSuspended() {
        Log.d(TAG, "Connection suspended")
    }

    /** 播放状态变化时调用，当前未做 UI 处理 */
    override fun onPlaybackStateChanged(state: PlaybackStateCompat?) { /* 暂不处理 */ }

    /**
     * 当前播放媒体元数据变化时调用（如切歌）。根据新的 mediaId 更新列表中的高亮项。
     * @param metadata 当前媒体元数据，可为 null
     */
    override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
        val currentMediaId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        Log.d(TAG, "Metadata changed. New mediaId: $currentMediaId")
        songAdapter.setCurrentPlayingId(currentMediaId)
    }

    /**
     * 加载媒体列表等发生错误时调用。
     * @param errorMessage 错误提示文案，直接 Toast 显示
     */
    override fun onError(errorMessage: String) {
        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
    }

    // ---------- Activity 生命周期 ----------

    /** 界面可见时连接 MediaBrowser，从而触发订阅并收到 onChildrenLoaded */
    override fun onStart() {
        super.onStart()
        mediaBrowserHelper.connect()
    }

    /** 界面不可见时断开连接并取消订阅 */
    override fun onStop() {
        super.onStop()
        mediaBrowserHelper.disconnect()
    }
} 