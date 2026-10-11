package com.salloum.tv.ui

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

import androidx.media3.common.Player

import androidx.media3.common.MimeTypes

import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

import androidx.media3.exoplayer.DefaultLoadControl

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C

import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

private const val MAX_RETRIES = 3

/**
 * Holds retry state so pending retry callbacks can be cancelled when the
 * composable leaves composition or the channel changes (prevents leaks).
 */
private class RetryState {
    var count = 0
    var pending: Runnable? = null

    fun cancel(handler: Handler) {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(url: String, modifier: Modifier = Modifier, onError: (String) -> Unit = {}) {
    val context = LocalContext.current
    val currentOnError by rememberUpdatedState(onError)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val retryState = remember { RetryState() }

    val exoPlayer = remember {
        // Enable software decoding and fallback decoders
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("ar")
                    .setSelectUndeterminedTextLanguage(true)
                    // Let ABR pick the right quality for the current network speed
                    // instead of forcing the highest bitrate (caused stuttering).
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedSampleRateAdaptiveness(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
            )
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // Optimize buffering for high bitrate streams (like 6Mbps)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50000, // minBuffer
                100000, // maxBuffer
                3000,  // bufferForPlayback
                5000   // bufferForPlaybackAfterRebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            prepare()
                            play()
                        }
                        if (state == Player.STATE_READY) {
                            retryState.count = 0
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (retryState.count < MAX_RETRIES) {
                            retryState.count++
                            retryState.cancel(mainHandler)
                            val retry = Runnable {
                                prepare()
                                play()
                            }
                            retryState.pending = retry
                            // Backoff between attempts: 2s, 4s, 6s
                            mainHandler.postDelayed(retry, 2000L * retryState.count)
                        } else {
                            currentOnError("Channel currently offline")
                        }
                    }
                })
            }
    }

    DisposableEffect(url) {
        val isRotana = url.contains("rotana", ignoreCase = true)
        val isAlwan = url.contains("alwan", ignoreCase = true) || url.contains("marketplace-io", ignoreCase = true) || url.contains("kurdtv.org", ignoreCase = true) || url.contains("ameditv.ai.studio", ignoreCase = true)
        val isSpacetoon = url.contains("spacetoongo.com", ignoreCase = true)
        val isElahmad = url.contains("elahmad", ignoreCase = true)
        val isMixflix = url.contains("mixflixapp", ignoreCase = true)
        val isTs = url.contains(".php") || url.contains("extension=ts")
        val isM3u8 = url.contains(".m3u8") || url.contains(".m3u") || url.contains(".php")

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)

        val headers = mutableMapOf<String, String>()
        if (isRotana) {
            headers["Referer"] = "https://rotana.net/"
        }
        if (isAlwan) {
            if (url.contains("kurdtv.org", ignoreCase = true)) {
                headers["Referer"] = "https://kurdtv.org/"
                headers["Origin"] = "https://kurdtv.org"
            } else if (url.contains("ameditv.ai.studio", ignoreCase = true)) {
                headers["Referer"] = "https://ameditv.ai.studio/"
                headers["Origin"] = "https://ameditv.ai.studio"
            } else {
                headers["Referer"] = "https://k2.marketplace-io-none-kode-steeeee.lol/"
                headers["Origin"] = "https://k2.marketplace-io-none-kode-steeeee.lol"
            }
        }
        if (isSpacetoon) {
            // Spacetoon Go links often need these to play audio/video correctly
            headers["Referer"] = "https://fast.spacetoongo.com/"
            headers["Origin"] = "https://fast.spacetoongo.com"
        }
        if (isElahmad) {
            headers["Referer"] = "https://www.elahmad.ru/"
            headers["Origin"] = "https://www.elahmad.ru"
        }
        if (isMixflix) {
            headers["Referer"] = "https://mixflixapp.online/"
            headers["Origin"] = "https://mixflixapp.online"
        }

        if (headers.isNotEmpty()) {
            dataSourceFactory.setDefaultRequestProperties(headers)
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(url)

        if (isM3u8) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (isTs) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)

        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)

        val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

        // Reset retry state for the new channel
        retryState.cancel(mainHandler)
        retryState.count = 0

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.play()
        onDispose {
            // Cancel any pending retry and release the stream's resources
            retryState.cancel(mainHandler)
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            retryState.cancel(mainHandler)
            exoPlayer.release()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false // Use our custom controls in FullScreenPlayer
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                keepScreenOn = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    )
}
