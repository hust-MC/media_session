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
        
        title = "播放列表"
        
        // 1. 初始化 RecyclerView
        recyclerView = findViewById(R.id.playlist_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        
        // 2. 初始化 Adapter
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter
        
        // 3. 初始化 MediaBrowserHelper 以获取数据
        mediaBrowserHelper = MediaBrowserHelper(this, this)
    }
    
    // --- MediaBrowserHelper.MediaConnectionListener Callbacks ---

    override fun onConnected(controller: MediaControllerCompat) {
        Log.d(TAG, "Media Service에 연결되었습니다.")
        // 连接成功，可以进行其他操作
    }
    
    override fun onChildrenLoaded(items: List<MediaBrowserCompat.MediaItem>) {
        Log.d(TAG, "${items.size}개의 곡을 불러왔습니다.")
        if (items.isEmpty()) {
            Toast.makeText(this, "플레이리스트에 곡이 없습니다.", Toast.LENGTH_SHORT).show()
        }
        // 4. 将获取到的数据更新到 Adapter
        songAdapter.updateList(items)
    }
    
    override fun onConnectionFailed() {
        Log.e(TAG, "Media Service 연결에 실패했습니다.")
        Toast.makeText(this, "서비스 연결에 실패했습니다.", Toast.LENGTH_SHORT).show()
    }

    // 以下回调暂时不需要处理
    override fun onConnectionSuspended() { Log.d(TAG, "Connection suspended") }
    override fun onPlaybackStateChanged(state: PlaybackStateCompat?) { /* Do nothing for now */ }
    override fun onMetadataChanged(metadata: MediaMetadataCompat?) { /* Do nothing for now */ }
    
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