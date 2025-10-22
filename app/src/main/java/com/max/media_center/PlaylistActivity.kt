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

class PlaylistActivity : AppCompatActivity(), MediaBrowserHelper.MediaConnectionListener {
    private lateinit var mediaBrowserHelper: MediaBrowserHelper
    private lateinit var recyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter
    
    companion object {
        private const val TAG = "PlaylistActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        
        title = getString(R.string.playlist_title)
        
        // 1. 初始化 RecyclerView
        recyclerView = findViewById(R.id.playlist_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        
        // 2. 初始化 Adapter，设置点击事件处理
        songAdapter = SongAdapter { mediaItem ->
            // 当用户点击某首歌曲时，通过 mediaId 播放它
            Log.d(TAG, "User clicked on: ${mediaItem.description.title}")
            val mediaId = mediaItem.mediaId
            if (mediaId != null) {
                mediaBrowserHelper.getTransportControls()?.playFromMediaId(mediaId, null)
                Toast.makeText(this, getString(R.string.now_playing_format, mediaItem.description.title), Toast.LENGTH_SHORT).show()
            }
        }
        recyclerView.adapter = songAdapter
        
        // 3. 初始化 MediaBrowserHelper 以获取数据
        mediaBrowserHelper = MediaBrowserHelper(this, this)
    }
    
    // --- MediaBrowserHelper.MediaConnectionListener Callbacks ---

    override fun onConnected(controller: MediaControllerCompat) {
        Log.d(TAG, "Media Service에 연결되었습니다.")
        // 连接成功后，获取当前播放的歌曲信息并设置高亮
        val currentMetadata = controller.metadata
        val currentMediaId = currentMetadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        songAdapter.setCurrentPlayingId(currentMediaId)
    }
    
    override fun onChildrenLoaded(items: List<MediaBrowserCompat.MediaItem>) {
        Log.d(TAG, "${items.size}개의 곡을 불러왔습니다.")
        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.playlist_empty), Toast.LENGTH_SHORT).show()
        }
        // 4. 将获取到的数据更新到 Adapter
        songAdapter.updateList(items)
    }
    
    override fun onConnectionFailed() {
        Log.e(TAG, "Media Service connection failed")
        Toast.makeText(this, getString(R.string.connection_failed), Toast.LENGTH_SHORT).show()
    }

    override fun onConnectionSuspended() { Log.d(TAG, "Connection suspended") }
    override fun onPlaybackStateChanged(state: PlaybackStateCompat?) { /* Do nothing for now */ }
    
    override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
        // 当播放的歌曲信息变化时，更新高亮
        val currentMediaId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        Log.d(TAG, "Metadata changed. New mediaId: $currentMediaId")
        songAdapter.setCurrentPlayingId(currentMediaId)
    }
    
    // --- Activity Lifecycle ---

    override fun onStart() {
        super.onStart()
        mediaBrowserHelper.connect()
    }
    
    override fun onStop() {
        super.onStop()
        mediaBrowserHelper.disconnect()
    }
} 