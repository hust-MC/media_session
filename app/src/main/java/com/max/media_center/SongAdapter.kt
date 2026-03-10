package com.max.media_center

import android.support.v4.media.MediaBrowserCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * 播放列表的 RecyclerView 适配器。展示 MediaItem 列表，支持点击回调；根据当前播放的 mediaId 高亮对应项（绿色），其余使用默认文字颜色。
 */
class SongAdapter(
    /** 点击某条歌曲时的回调，由 PlaylistActivity 传入并执行 playFromMediaId */
    private val onItemClick: (MediaBrowserCompat.MediaItem) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    /** 当前展示的歌曲列表（MediaBrowser 返回的 MediaItem） */
    private var songList = emptyList<MediaBrowserCompat.MediaItem>()
    /** 当前正在播放的歌曲的 mediaId，用于高亮显示，null 表示无播放中 */
    private var currentPlayingMediaId: String? = null

    /**
     * ViewHolder：持有单条歌曲的标题 TextView，并保存默认文字颜色以便非高亮时恢复。
     */
    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleTextView: TextView = itemView.findViewById(R.id.song_title)
        /** 创建时保存的默认文字颜色，用于非“正在播放”项 */
        val defaultTextColor: Int = titleTextView.currentTextColor

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(songList[position])
                }
            }
        }
    }

    /**
     * 创建 ViewHolder，使用 item_song 布局。
     * @param parent 父 ViewGroup
     * @param viewType 视图类型（本 Adapter 未区分类型）
     * @return 新的 SongViewHolder
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    /**
     * 返回当前列表项数量。
     * @return songList 的大小
     */
    override fun getItemCount(): Int {
        return songList.size
    }

    /**
     * 绑定指定位置的数据：设置标题（无标题时用“未知歌曲”），根据 mediaId 是否等于 currentPlayingMediaId 设置绿色高亮或默认颜色。
     * @param holder ViewHolder
     * @param position 数据位置
     */
    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val songItem = songList[position]
        holder.titleTextView.text = songItem.description.title ?: holder.itemView.context.getString(R.string.unknown_song)

        if (songItem.mediaId == currentPlayingMediaId) {
            holder.titleTextView.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.holo_green_dark))
        } else {
            holder.titleTextView.setTextColor(holder.defaultTextColor)
        }
    }

    /**
     * 全量更新歌曲列表并刷新整个列表 UI。
     * @param newSongList 新的 MediaItem 列表
     */
    fun updateList(newSongList: List<MediaBrowserCompat.MediaItem>) {
        songList = newSongList
        notifyDataSetChanged()
    }

    /**
     * 设置当前正在播放的歌曲 mediaId，用于高亮。仅刷新从“正在播放”变为非播放、或从非播放变为“正在播放”的那一/两行，避免整表刷新。
     * @param mediaId 当前播放项的 mediaId，null 表示无播放中
     */
    fun setCurrentPlayingId(mediaId: String?) {
        val oldPlayingId = currentPlayingMediaId
        currentPlayingMediaId = mediaId

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