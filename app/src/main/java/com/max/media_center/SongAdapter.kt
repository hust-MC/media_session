package com.max.media_center

import android.support.v4.media.MediaBrowserCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * A simple adapter to display a list of song titles.
 * This adapter has no click handling or playback state logic.
 */
class SongAdapter : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var songList = emptyList<MediaBrowserCompat.MediaItem>()

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleTextView: TextView = itemView.findViewById(R.id.song_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun getItemCount(): Int {
        return songList.size
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val songItem = songList[position]
        holder.titleTextView.text = songItem.description.title ?: "未知歌曲"
    }

    /**
     * Updates the list of songs and notifies the adapter to refresh the UI.
     */
    fun updateList(newSongList: List<MediaBrowserCompat.MediaItem>) {
        songList = newSongList
        notifyDataSetChanged()
    }
} 