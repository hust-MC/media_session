package com.max.media_center

import android.content.ComponentName
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log

/**
 * Helper class to manage MediaBrowser connection and MediaController
 * This class encapsulates all the boilerplate code for connecting to MediaService
 */
class MediaBrowserHelper(
    private val context: Context,
    private val listener: MediaConnectionListener
) {
    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null

    companion object {
        private const val TAG = "MediaBrowserHelper"
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.d(TAG, "MediaBrowser connected")

            // Get MediaController
            mediaController = MediaControllerCompat(context, mediaBrowser.sessionToken).apply {
                registerCallback(mediaControllerCallback)
            }

            // Notify listener
            mediaController?.let { listener.onConnected(it) }

            // Subscribe to get media items
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
        // Initialize MediaBrowser
        mediaBrowser = MediaBrowserCompat(
            context,
            ComponentName(context, MediaService::class.java),
            connectionCallback,
            null
        )
    }

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

    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            listener.onPlaybackStateChanged(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            listener.onMetadataChanged(metadata)
        }
    }

    /**
     * Connect to MediaService
     * Should be called in Activity's onStart()
     */
    fun connect() {
        if (!mediaBrowser.isConnected) {
            mediaBrowser.connect()
        }
    }

    /**
     * Disconnect from MediaService
     * Should be called in Activity's onStop()
     */
    fun disconnect() {
        mediaController?.unregisterCallback(mediaControllerCallback)
        if (mediaBrowser.isConnected) {
            mediaBrowser.unsubscribe(mediaBrowser.root)
            mediaBrowser.disconnect()
        }
    }

    /**
     * Subscribe to media items
     */
    fun subscribe() {
        if (mediaBrowser.isConnected) {
            mediaBrowser.subscribe(mediaBrowser.root, subscriptionCallback)
        }
    }

    /**
     * Unsubscribe from media items
     */
    fun unsubscribe() {
        if (mediaBrowser.isConnected) {
            mediaBrowser.unsubscribe(mediaBrowser.root)
        }
    }

    /**
     * Get current MediaController
     */
    fun getMediaController(): MediaControllerCompat? = mediaController

    /**
     * Get transport controls for playback control
     */
    fun getTransportControls() = mediaController?.transportControls

    /**
     * Interface for receiving media connection events
     */
    interface MediaConnectionListener {
        fun onConnected(controller: MediaControllerCompat)
        fun onChildrenLoaded(items: List<MediaBrowserCompat.MediaItem>)
        fun onConnectionFailed()
        fun onConnectionSuspended()
        fun onPlaybackStateChanged(state: PlaybackStateCompat?)
        fun onMetadataChanged(metadata: MediaMetadataCompat?)
        fun onError(errorMessage: String)
    }
} 