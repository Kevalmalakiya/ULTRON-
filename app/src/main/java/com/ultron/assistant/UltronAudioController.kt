package com.ultron.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger

/** Handles Kokoro WAV downloads and local playback. All MediaPlayer work stays on the main thread. */
class UltronAudioController(
    context: Context,
    private val onPlaybackChanged: (Long?) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)

    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var worker: Thread? = null

    // Accessed only on the main thread.
    private var mediaPlayer: MediaPlayer? = null
    private var activeFile: File? = null

    fun play(
        baseUrl: String,
        apiKey: String,
        text: String,
        language: String,
        speed: Float,
        messageId: Long
    ) {
        stop()
        val token = generation.get()

        mainHandler.post {
            if (generation.get() == token) onPlaybackChanged(messageId)
        }

        val requestThread = Thread({
            try {
                val audio = UltronApiClient.synthesizeSpeech(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    text = text,
                    language = language,
                    speed = speed,
                    onConnection = { connection ->
                        if (generation.get() == token) {
                            activeConnection = connection
                        } else {
                            connection?.disconnect()
                        }
                    }
                )

                if (generation.get() != token || Thread.currentThread().isInterrupted) return@Thread

                val file = File(appContext.cacheDir, "ultron_kokoro_$token.wav")
                file.writeBytes(audio)

                mainHandler.post {
                    if (generation.get() != token) {
                        file.delete()
                        return@post
                    }

                    try {
                        val player = MediaPlayer()
                        mediaPlayer = player
                        activeFile = file
                        player.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        player.setOnPreparedListener {
                            if (generation.get() == token) {
                                it.start()
                            } else {
                                releasePlayer(token)
                            }
                        }
                        player.setOnCompletionListener {
                            releasePlayer(token)
                        }
                        player.setOnErrorListener { _, _, _ ->
                            releasePlayer(token)
                            if (generation.get() == token) {
                                onError("Kokoro audio playback failed on this device.")
                            }
                            true
                        }
                        player.setDataSource(file.absolutePath)
                        player.prepareAsync()
                    } catch (error: Exception) {
                        releasePlayer(token)
                        if (generation.get() == token) {
                            onError("Unable to play Kokoro audio: ${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }
            } catch (error: Exception) {
                if (generation.get() == token && !Thread.currentThread().isInterrupted) {
                    mainHandler.post {
                        if (generation.get() == token) {
                            onPlaybackChanged(null)
                            onError("Kokoro voice request failed: ${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }
            } finally {
                if (generation.get() == token) activeConnection = null
            }
        }, "ultron-kokoro-request")

        worker = requestThread
        requestThread.start()
    }

    fun stop() {
        generation.incrementAndGet()
        activeConnection?.disconnect()
        activeConnection = null
        worker?.interrupt()
        worker = null

        mainHandler.post {
            releasePlayer(generation.get())
            onPlaybackChanged(null)
        }
    }

    fun shutdown() {
        stop()
    }

    private fun releasePlayer(token: Int) {
        val player = mediaPlayer
        mediaPlayer = null
        runCatching {
            if (player?.isPlaying == true) player.stop()
        }
        runCatching { player?.release() }

        activeFile?.delete()
        activeFile = null

        if (generation.get() == token) onPlaybackChanged(null)
    }
}
