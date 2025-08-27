package com.max.media_center

import android.support.v4.media.MediaBrowserCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter to display a list of songs with click handling.
 */
class SongAdapter(
    private val onItemClick: (MediaBrowserCompat.MediaItem) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var songList = emptyList<MediaBrowserCompat.MediaItem>()
    private var currentPlayingMediaId: String? = null

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleTextView: TextView = itemView.findViewById(R.id.song_title)
        
        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(songList[position])
                }
            }
        }
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

        // 根据是否为当前播放歌曲来设置颜色
        if (songItem.mediaId == currentPlayingMediaId) {
            // 设置为高亮颜色（例如，主题的 accent color）
            holder.titleTextView.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.purple_500))
        } else {
            // 设置为默认颜色
            holder.titleTextView.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.white))
        }
    }

    /**
     * Updates the list of songs and notifies the adapter to refresh the UI.
     */
    fun updateList(newSongList: List<MediaBrowserCompat.MediaItem>) {
        songList = newSongList
        notifyDataSetChanged()
    }

    /**
     * Sets the mediaId of the currently playing song to highlight it in the list.
     */
    fun setCurrentPlayingId(mediaId: String?) {
        val oldPlayingId = currentPlayingMediaId
        currentPlayingMediaId = mediaId

        // 优化：只刷新改变的项
        if (oldPlayingId != null) {
            val oldPosition = songList.indexOfFirst { it.mediaId == oldPlayingId }
            if (oldPosition != -1) notifyItemChanged(oldPosition)
        }
        if (mediaId != null) {
            val newPosition = songList.indexOfFirst { it.mediaId == mediaId }
            if (newPosition != -1) notifyItemChanged(newPosition)
        }
    }
} 