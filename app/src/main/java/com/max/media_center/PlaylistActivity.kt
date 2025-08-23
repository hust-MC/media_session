package com.max.media_center

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlaylistActivity : AppCompatActivity(), MediaBrowserHelper.MediaConnectionListener {
    private lateinit var mediaBrowserHelper: MediaBrowserHelper
    private lateinit var recyclerView: RecyclerView
    private val musicList = mutableListOf<MediaBrowserCompat.MediaItem>()
    
    companion object {
        private const val TAG = "PlaylistActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        
        // 初始化RecyclerView
        recyclerView = findViewById(R.id.playlist_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // 初始化MediaBrowserHelper
        mediaBrowserHelper = MediaBrowserHelper(this, this)
    }
    
    // MediaConnectionListener 接口实现
    override fun onConnected(controller: MediaControllerCompat) {
        Log.d(TAG, "Connected to MediaService")
        MediaControllerCompat.setMediaController(this, controller)
    }
    
    override fun onChildrenLoaded(items: List<MediaBrowserCompat.MediaItem>) {
        Log.d(TAG, "Loaded ${items.size} music items")
        musicList.clear()
        musicList.addAll(items)
        
        // 这里暂时只打印日志，后续会更新RecyclerView
        items.forEach { item ->
            Log.d(TAG, "Music item: ${item.description.title} - ${item.description.subtitle}")
        }
    }
    
    override fun onConnectionFailed() {
        Log.e(TAG, "Failed to connect to MediaService")
    }
    
    override fun onConnectionSuspended() {
        Log.d(TAG, "Connection to MediaService suspended")
    }
    
    override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
        // 后续用于更新UI，显示当前播放状态
        Log.d(TAG, "Playback state changed: ${state?.state}")
    }
    
    override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
        // 后续用于高亮当前播放的歌曲
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        val mediaId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        Log.d(TAG, "Metadata changed: $title (ID: $mediaId)")
    }
    
    override fun onStart() {
        super.onStart()
        mediaBrowserHelper.connect()
    }
    
    override fun onStop() {
        super.onStop()
        mediaBrowserHelper.disconnect()
    }
} 