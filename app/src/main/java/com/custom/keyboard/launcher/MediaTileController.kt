package com.custom.keyboard.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Tracks whatever is playing on the device for the music tile. Reading media sessions needs the
 * same notification access as live tiles; without it the tile still sends media keys.
 */
class MediaTileController(private val context: Context, private val onChanged: () -> Unit) {

    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, TileNotificationListener::class.java)
    private var controller: MediaController? = null
    private var started = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        attach(sessions.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChanged()
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChanged()
        override fun onSessionDestroyed() {
            detach()
            onChanged()
        }
    }

    val hasSession: Boolean get() = controller != null
    val packageName: String? get() = controller?.packageName

    val title: String
        get() = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()

    val artist: String
        get() = controller?.metadata?.let {
            it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        }.orEmpty()

    val art: Bitmap?
        get() = controller?.metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }

    val isPlaying: Boolean
        get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun start() {
        if (started) return
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            attach(sessionManager?.getActiveSessions(listenerComponent).orEmpty())
            started = true
        } catch (_: SecurityException) {
            // Notification access not granted yet.
        }
    }

    fun stop() {
        if (started) {
            try {
                sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (_: Exception) {
            }
        }
        started = false
        detach()
    }

    private fun attach(sessions: List<MediaController>) {
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return
        detach()
        controller = best
        best?.registerCallback(callback)
        onChanged()
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
    }

    fun playPause() {
        val c = controller
        if (c == null) {
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        if (isPlaying) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext() ?: sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun previous() = controller?.transportControls?.skipToPrevious() ?: sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun sendKey(keyCode: Int) {
        try {
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (_: Exception) {
        }
    }
}
