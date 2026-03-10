package com.max.media_center

import android.content.ComponentName
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log

/**
 * MediaBrowser 连接与 MediaController 的封装类。负责连接 MediaService、获取 MediaController、
 * 订阅媒体列表、转发连接/列表/播放状态/元数据/错误等回调给 [MediaConnectionListener]，便于 Activity 只关心业务逻辑。
 */
class MediaBrowserHelper(
    private val context: Context,
    private val listener: MediaConnectionListener
) {
    /** 用于连接 MediaService 的 MediaBrowser 实例 */
    private lateinit var mediaBrowser: MediaBrowserCompat
    /** 连接成功后得到的 MediaController，用于控制播放与接收状态回调 */
    private var mediaController: MediaControllerCompat? = null

    companion object {
        private const val TAG = "MediaBrowserHelper"
    }

    /** 连接成功时创建 MediaController、注册回调、通知 listener 并自动订阅根节点子列表 */
    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.d(TAG, "MediaBrowser connected")

            mediaController = MediaControllerCompat(context, mediaBrowser.sessionToken).apply {
                registerCallback(mediaControllerCallback)
            }

            mediaController?.let { listener.onConnected(it) }
            subscribe()
        }

        override fun onConnectionFailed() {
            Log.e(TAG, "MediaBrowser connection failed")
            mediaController = null
            listener.onConnectionFailed()
        }

        override fun onConnectionSuspended() {
            Log.d(TAG, "MediaBrowser connection suspended")
            mediaController?.unregisterCallback(mediaControllerCallback)
            mediaController = null
            listener.onConnectionSuspended()
        }
    }

    init {
        mediaBrowser = MediaBrowserCompat(
            context,
            ComponentName(context, MediaService::class.java),
            connectionCallback,
            null
        )
    }

    /** 订阅根节点子列表的回调：加载成功时把列表交给 listener，失败时 onError */
    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(
            parentId: String,
            children: MutableList<MediaBrowserCompat.MediaItem>
        ) {
            Log.d(TAG, "Loaded ${children.size} media items")
            listener.onChildrenLoaded(children)
        }

        override fun onError(parentId: String) {
            Log.e(TAG, "Error loading children for $parentId")
            listener.onError(context.getString(R.string.error_load_media_failed))
        }
    }

    /** MediaController 状态回调：播放状态或元数据变化时转发给 listener */
    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            listener.onPlaybackStateChanged(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            listener.onMetadataChanged(metadata)
        }
    }

    /**
     * 连接 MediaService。应在 Activity 的 onStart() 中调用；若已连接则不会重复连接。
     */
    fun connect() {
        if (!mediaBrowser.isConnected) {
            mediaBrowser.connect()
        }
    }

    /**
     * 断开与 MediaService 的连接：注销 MediaController 回调、取消订阅、断开 MediaBrowser。应在 Activity 的 onStop() 中调用。
     */
    fun disconnect() {
        mediaController?.unregisterCallback(mediaControllerCallback)
        if (mediaBrowser.isConnected) {
            mediaBrowser.unsubscribe(mediaBrowser.root)
            mediaBrowser.disconnect()
        }
    }

    /**
     * 订阅根节点的子列表。连接成功后会在 connectionCallback 中自动调用；也可在需要刷新列表时手动调用。仅在已连接时有效。
     */
    fun subscribe() {
        if (mediaBrowser.isConnected) {
            mediaBrowser.subscribe(mediaBrowser.root, subscriptionCallback)
        }
    }

    /**
     * 取消对根节点子列表的订阅。断开前会由 disconnect() 自动调用。
     */
    fun unsubscribe() {
        if (mediaBrowser.isConnected) {
            mediaBrowser.unsubscribe(mediaBrowser.root)
        }
    }

    /**
     * 获取当前 MediaController。未连接或连接失败时为 null。
     * @return 当前 MediaController 或 null
     */
    fun getMediaController(): MediaControllerCompat? = mediaController

    /**
     * 获取用于控制播放的 TransportControls（播放、暂停、上一首、下一首、按 mediaId 播放等）。未连接时为 null。
     * @return transportControls 或 null
     */
    fun getTransportControls() = mediaController?.transportControls

    /**
     * 媒体连接与数据回调接口。实现此接口以接收连接成功/失败/挂起、媒体列表加载、播放状态与元数据变化、以及错误信息。
     */
    interface MediaConnectionListener {
        /** 已连接到 MediaService 并拿到 MediaController 时调用 */
        fun onConnected(controller: MediaControllerCompat)
        /** 订阅的媒体子列表加载完成时调用 */
        fun onChildrenLoaded(items: List<MediaBrowserCompat.MediaItem>)
        /** 连接失败时调用 */
        fun onConnectionFailed()
        /** 连接被挂起时调用 */
        fun onConnectionSuspended()
        /** 播放状态变化时调用 */
        fun onPlaybackStateChanged(state: PlaybackStateCompat?)
        /** 当前播放媒体元数据变化时调用 */
        fun onMetadataChanged(metadata: MediaMetadataCompat?)
        /** 加载列表等出错时调用，errorMessage 一般为字符串资源内容 */
        fun onError(errorMessage: String)
    }
} 