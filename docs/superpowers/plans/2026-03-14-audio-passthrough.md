# Audio Passthrough Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable automatic HDMI audio passthrough (AC3, EAC3, DTS, TrueHD) on Android TV with transparent fallback to PCM decoding, then to HLS transcode.

**Architecture:** ExoPlayer's `DefaultAudioSink` handles passthrough via `AudioCapabilities` from the HDMI output. We override `DefaultRenderersFactory.buildAudioSink()` to inject the right capabilities. On audio track errors, the player re-initializes in PCM-only mode and replays the same stream. Existing HLS fallback remains unchanged as the final safety net.

**Tech Stack:** Media3/ExoPlayer 1.3.1, `AudioCapabilities`, `DefaultAudioSink`, `DefaultRenderersFactory`

**Spec:** `docs/superpowers/specs/2026-03-14-audio-passthrough-design.md`

---

## Chunk 1: Core — MediaPlayer passthrough support

### Task 1: Add `isAudioTrackError()` helper to MediaPlayer

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt:30-63` (companion object)
- Test: `app/src/test/java/com/jellyfinbroadcast/core/MediaPlayerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/java/com/jellyfinbroadcast/core/MediaPlayerTest.kt`:

```kotlin
import androidx.media3.common.PlaybackException

// ... existing tests ...

@Test
fun `isAudioTrackError returns true for AUDIO_TRACK_INIT_FAILED`() {
    val error = PlaybackException(
        "audio init failed",
        null,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
    )
    assertTrue(MediaPlayer.isAudioTrackError(error))
}

@Test
fun `isAudioTrackError returns true for AUDIO_TRACK_WRITE_FAILED`() {
    val error = PlaybackException(
        "audio write failed",
        null,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
    )
    assertTrue(MediaPlayer.isAudioTrackError(error))
}

@Test
fun `isAudioTrackError returns false for non-audio errors`() {
    val error = PlaybackException(
        "source error",
        null,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    )
    assertFalse(MediaPlayer.isAudioTrackError(error))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.jellyfinbroadcast.core.MediaPlayerTest" --info`
Expected: FAIL — `isAudioTrackError` does not exist yet.

- [ ] **Step 3: Implement `isAudioTrackError()`**

In `app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt`, add inside the `companion object` block (after `buildHlsFallbackUrl`):

```kotlin
private val AUDIO_TRACK_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
)

fun isAudioTrackError(error: PlaybackException): Boolean =
    error.errorCode in AUDIO_TRACK_ERROR_CODES
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.jellyfinbroadcast.core.MediaPlayerTest" --info`
Expected: ALL PASS (8 tests — 5 existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt app/src/test/java/com/jellyfinbroadcast/core/MediaPlayerTest.kt
git commit -m "feat: add isAudioTrackError() helper for passthrough fallback detection"
```

---

### Task 2: Add passthrough support to `MediaPlayer.initialize()`

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt:65-94`

- [ ] **Step 1: Add new imports**

Add these imports at the top of `MediaPlayer.kt`:

```kotlin
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
```

- [ ] **Step 2: Add `passthroughEnabled` field**

After the existing `var onSeekCompleted` (line 69), add:

```kotlin
private var passthroughEnabled = false

fun isPassthroughEnabled(): Boolean = passthroughEnabled
```

- [ ] **Step 3: Replace `initialize()` with passthrough-aware version**

Replace the entire `initialize()` method (lines 71-94) with:

```kotlin
@OptIn(UnstableApi::class)
fun initialize(enablePassthrough: Boolean = true) {
    player?.release()
    passthroughEnabled = enablePassthrough

    val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    val ctx = context
    val renderersFactory = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
            enableOffload: Boolean
        ): AudioSink {
            val builder = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            if (enablePassthrough) {
                builder.setAudioCapabilities(AudioCapabilities.getCapabilities(context))
            }
            // When enablePassthrough is false, we don't call setAudioCapabilities(),
            // so DefaultAudioSink defaults to PCM-only output
            return builder.build()
        }
    }

    player = ExoPlayer.Builder(ctx, renderersFactory)
        .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) onPlaybackEnded?.invoke()
                }

                override fun onPlayerError(error: PlaybackException) {
                    onError?.invoke(error)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        onSeekCompleted?.invoke()
                    }
                }
            })
        }
}
```

- [ ] **Step 4: Run existing tests to verify no regression**

Run: `./gradlew testDebugUnitTest --tests "com.jellyfinbroadcast.core.MediaPlayerTest" --info`
Expected: ALL PASS (8 tests). The default `enablePassthrough = true` preserves backward compatibility.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt
git commit -m "feat: add audio passthrough support to MediaPlayer.initialize()"
```

---

### Task 3: DeviceProfileFactory — passthrough detection logging

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/core/DeviceProfileFactory.kt:54-64,221-236`

- [ ] **Step 1: Add imports**

Add at the top of `DeviceProfileFactory.kt`:

```kotlin
import android.media.AudioFormat
import androidx.media3.exoplayer.audio.AudioCapabilities
```

- [ ] **Step 2: Remove `@Suppress("UNUSED_PARAMETER")` from `build()`**

On line 54, change:

```kotlin
// OLD:
fun build(@Suppress("UNUSED_PARAMETER") context: Context): DeviceProfile {

// NEW:
fun build(context: Context): DeviceProfile {
```

The `context` parameter is now used by `detectPassthroughCodecs()`.

- [ ] **Step 3: Add `detectPassthroughCodecs()` method**

Add after `detectCodecs()` (after line 236):

```kotlin
/** Encoding constant → Jellyfin codec name for passthrough */
private val PASSTHROUGH_ENCODING_MAP = mapOf(
    AudioFormat.ENCODING_AC3 to "ac3",
    AudioFormat.ENCODING_E_AC3 to "eac3",
    AudioFormat.ENCODING_DTS to "dts",
    AudioFormat.ENCODING_DTS_HD to "dts",
    AudioFormat.ENCODING_DOLBY_TRUEHD to "truehd"
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun detectPassthroughCodecs(context: Context): List<String> {
    val capabilities = AudioCapabilities.getCapabilities(context)
    return PASSTHROUGH_ENCODING_MAP.entries
        .filter { (encoding, _) -> capabilities.supportsEncoding(encoding) }
        .map { (_, codec) -> codec }
}
```

Note: `ENCODING_DTS_HD` maps to `"dts"` (same Jellyfin codec family as standard DTS).

- [ ] **Step 4: Add logging call in `build()`**

After line 64 (`Log.i(TAG, "Detected audio codecs: $audioCodecs")`), add:

```kotlin
val passthroughCodecs = detectPassthroughCodecs(context)
Log.i(TAG, "Passthrough codecs (HDMI): $passthroughCodecs")
```

- [ ] **Step 5: Run all tests to verify no regression**

Run: `./gradlew testDebugUnitTest --info`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/DeviceProfileFactory.kt
git commit -m "feat: detect and log HDMI passthrough audio codecs"
```

---

## Chunk 2: Fragment & Activity integration

### Task 4: TvPlayerFragment — enable passthrough + add rebindPlayer()

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/tv/TvPlayerFragment.kt:23-30,32`

- [ ] **Step 1: Update `onViewCreated` to enable passthrough**

Replace lines 25-26 of `TvPlayerFragment.kt`:

```kotlin
// OLD:
val player = MediaPlayer(requireContext())
player.initialize()

// NEW:
val player = MediaPlayer(requireContext())
player.initialize(enablePassthrough = true)
```

- [ ] **Step 2: Add `rebindPlayer()` method**

After `getMediaPlayer()` (line 32), add:

```kotlin
fun rebindPlayer(mediaPlayer: MediaPlayer) {
    _binding?.playerView?.player = mediaPlayer.getExoPlayer()
}
```

- [ ] **Step 3: Run all tests to verify no regression**

Run: `./gradlew testDebugUnitTest --info`
Expected: ALL PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/tv/TvPlayerFragment.kt
git commit -m "feat: enable audio passthrough in TV player with rebind support"
```

---

### Task 5: PhoneQrCodeFragment — disable passthrough

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/phone/PhoneQrCodeFragment.kt:45-46`

- [ ] **Step 1: Update `onViewCreated` to disable passthrough**

Replace lines 45-46 of `PhoneQrCodeFragment.kt`:

```kotlin
// OLD:
val player = MediaPlayer(requireContext())
player.initialize()

// NEW:
val player = MediaPlayer(requireContext())
player.initialize(enablePassthrough = false)
```

- [ ] **Step 2: Run all tests to verify no regression**

Run: `./gradlew testDebugUnitTest --info`
Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/phone/PhoneQrCodeFragment.kt
git commit -m "feat: disable audio passthrough on phone (no HDMI output)"
```

---

### Task 6: TvActivity — passthrough fallback in error handler

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/tv/TvActivity.kt:307-350`

The existing `onError` handler in `startPlayback()` handles DirectPlay → HLS fallback. We insert a passthrough → PCM retry **before** the HLS fallback, using an if/else structure.

- [ ] **Step 1: Replace the `onError` handler in `startPlayback()`**

Replace lines 307-350 of `TvActivity.kt` (the `mediaPlayer.onError = { error -> ... }` block) with:

```kotlin
mediaPlayer.onError = { error ->
    Log.e(TAG, "Playback error: ${error.message}", error)
    val posMs = mediaPlayer.getCurrentPosition()
    lifecycleScope.launch(Dispatchers.IO) {
        reporter.reportPlaybackStop(posMs)
    }

    if (mediaPlayer.isPassthroughEnabled() && MediaPlayer.isAudioTrackError(error)) {
        // Audio passthrough failed → retry with PCM decoding (same stream)
        Log.i(TAG, "Audio passthrough failed (${error.errorCode}), retrying with PCM decoding")
        reporter.release()
        mediaPlayer.initialize(enablePassthrough = false)
        // Rebind PlayerView to the new ExoPlayer instance
        val playerFrag = supportFragmentManager
            .findFragmentById(R.id.container) as? TvPlayerFragment
        playerFrag?.rebindPlayer(mediaPlayer)
        mediaPlayer.play(streamInfo)
        if (posMs > 0) mediaPlayer.seekTo(posMs)

        val pcmReporter = PlaybackReporter(api, reportPlayMethod, streamInfo.playSessionId)
        playbackReporter = pcmReporter
        lifecycleScope.launch(Dispatchers.IO) {
            pcmReporter.reportPlaybackStart(itemId, posMs)
        }
        pcmReporter.startPeriodicReporting(
            getPosition = { mediaPlayer.getCurrentPosition() },
            getIsPaused = { !mediaPlayer.isPlaying() }
        )
        mediaPlayer.onSeekCompleted = { pcmReporter.reportProgressNow() }
        mediaPlayer.onPlaybackEnded = {
            val endPos = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                pcmReporter.reportPlaybackStop(endPos)
            }
            stateMachine.transition(AppEvent.Stop)
        }
        // PCM error → fall through to HLS (same as existing DirectPlay fallback)
        mediaPlayer.onError = { pcmError ->
            Log.e(TAG, "PCM playback also failed: ${pcmError.message}", pcmError)
            val pcmPos = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                pcmReporter.reportPlaybackStop(pcmPos)
            }
            if (streamInfo is StreamInfo.DirectPlay) {
                val sourceId = itemId.toString().replace("-", "")
                val hlsUrl = streamInfo.serverTranscodeUrl
                    ?: MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token, sourceId)
                Log.i(TAG, "Falling back to HLS transcode")
                val hlsStream = StreamInfo.HlsTranscode(hlsUrl, streamInfo.playSessionId)
                mediaPlayer.play(hlsStream)
                if (pcmPos > 0) mediaPlayer.seekTo(pcmPos)
                pcmReporter.release()
                val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId)
                playbackReporter = hlsReporter
                lifecycleScope.launch(Dispatchers.IO) {
                    hlsReporter.reportPlaybackStart(itemId, pcmPos)
                }
                hlsReporter.startPeriodicReporting(
                    getPosition = { mediaPlayer.getCurrentPosition() },
                    getIsPaused = { !mediaPlayer.isPlaying() }
                )
                mediaPlayer.onSeekCompleted = { hlsReporter.reportProgressNow() }
                mediaPlayer.onPlaybackEnded = {
                    val hlsEndPos = mediaPlayer.getCurrentPosition()
                    lifecycleScope.launch(Dispatchers.IO) {
                        hlsReporter.reportPlaybackStop(hlsEndPos)
                    }
                    stateMachine.transition(AppEvent.Stop)
                }
                mediaPlayer.onError = { hlsError ->
                    Log.e(TAG, "HLS fallback also failed: ${hlsError.message}", hlsError)
                    val hlsPos = mediaPlayer.getCurrentPosition()
                    lifecycleScope.launch(Dispatchers.IO) {
                        hlsReporter.reportPlaybackStop(hlsPos)
                    }
                    stateMachine.transition(AppEvent.Stop)
                }
            } else {
                stateMachine.transition(AppEvent.Stop)
            }
        }
    } else if (streamInfo is StreamInfo.DirectPlay) {
        // Non-audio error on DirectPlay: existing HLS fallback (unchanged)
        val sourceId = itemId.toString().replace("-", "")
        val hlsUrl = streamInfo.serverTranscodeUrl
            ?: MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token, sourceId)
        val source = if (streamInfo.serverTranscodeUrl != null) "server transcode" else "manual HLS fallback"
        Log.i(TAG, "DirectPlay failed, falling back to $source")
        val hlsStream = StreamInfo.HlsTranscode(hlsUrl, streamInfo.playSessionId)
        Log.i(TAG, "Retrying with $source: $hlsUrl")
        mediaPlayer.play(hlsStream)
        if (startPositionMs > 0) {
            mediaPlayer.seekTo(startPositionMs)
        }
        reporter.release()
        val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId)
        playbackReporter = hlsReporter
        lifecycleScope.launch(Dispatchers.IO) {
            hlsReporter.reportPlaybackStart(itemId, startPositionMs)
        }
        hlsReporter.startPeriodicReporting(
            getPosition = { mediaPlayer.getCurrentPosition() },
            getIsPaused = { !mediaPlayer.isPlaying() }
        )
        mediaPlayer.onSeekCompleted = { hlsReporter.reportProgressNow() }
        mediaPlayer.onPlaybackEnded = {
            val hlsEndPos = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                hlsReporter.reportPlaybackStop(hlsEndPos)
            }
            stateMachine.transition(AppEvent.Stop)
        }
        mediaPlayer.onError = { hlsError ->
            Log.e(TAG, "HLS fallback also failed: ${hlsError.message}", hlsError)
            val hlsPos = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                hlsReporter.reportPlaybackStop(hlsPos)
            }
            stateMachine.transition(AppEvent.Stop)
        }
    } else {
        stateMachine.transition(AppEvent.Stop)
    }
}
```

Key changes vs. existing code:
- **New `if` branch** at the top: passthrough audio error → re-init PCM, rebind PlayerView, replay at `posMs` (current position, not `startPositionMs`).
- **`onPlaybackEnded` is reassigned** in every fallback branch (passthrough→PCM, PCM→HLS, direct→HLS) so the correct reporter is always used for stop reporting.
- **Existing HLS fallback** is now in the `else if` branch — logic unchanged.
- **No `return@` needed** — the if/else if/else structure ensures mutual exclusivity.

- [ ] **Step 2: Run all tests**

Run: `./gradlew testDebugUnitTest --info`
Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/tv/TvActivity.kt
git commit -m "feat: add audio passthrough fallback chain in TvActivity (passthrough → PCM → HLS)"
```

---

### Task 7: Build verification

- [ ] **Step 1: Run full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run all unit tests**

Run: `./gradlew testDebugUnitTest --info`
Expected: ALL PASS.

- [ ] **Step 3: Final commit if any fixups needed**

Only commit if there were actual build fixups. Do not create an empty commit.

```bash
git add -A
git commit -m "fix: build fixups for audio passthrough"
```
