# Multi-Item Playlist Support Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support multi-item playlists with seamless ExoPlayer transitions and per-item error fallback for automated cinema chains.

**Architecture:** ExoPlayer's native `setMediaSources()` loads multiple items with automatic pre-buffering. `Player.Listener.onMediaItemTransition()` detects item changes for Jellyfin reporting. Per-item fallback replaces a failing item's source with HLS transcode without disrupting the playlist. New `PlaybackReporter` instance per item transition (immutable `playSessionId`/`playMethod`).

**Tech Stack:** Media3/ExoPlayer 1.3.1, Kotlin Coroutines (async/awaitAll/withTimeout)

**Spec:** `docs/superpowers/specs/2026-03-15-playlist-support-design.md`

---

## Chunk 1: MediaPlayer playlist support

### Task 1: Add playlist methods and onItemTransition to MediaPlayer

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt`
- Test: `app/src/test/java/com/jellyfinbroadcast/core/MediaPlayerTest.kt`

- [ ] **Step 1: Add import for MediaSource**

At top of `MediaPlayer.kt`, add:

```kotlin
import androidx.media3.exoplayer.source.MediaSource
```

- [ ] **Step 2: Add `onItemTransition` callback and `currentSources` field**

After `var onSeekCompleted` (line 86), add:

```kotlin
var onItemTransition: ((Int) -> Unit)? = null

private var currentSources: MutableList<MediaSource> = mutableListOf()
```

- [ ] **Step 3: Add `onMediaItemTransition` to the Player.Listener in `initialize()`**

Inside the `addListener(object : Player.Listener { ... })` block in `initialize()`, after the `onPositionDiscontinuity` override (after line 140), add:

```kotlin
override fun onMediaItemTransition(
    mediaItem: MediaItem?,
    @Player.MediaItemTransitionReason reason: Int
) {
    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    ) {
        val index = player?.currentMediaItemIndex ?: 0
        onItemTransition?.invoke(index)
    }
}
```

- [ ] **Step 4: Extract `buildMediaSource` helper and refactor `play()`**

Add a private helper after `isPassthroughEnabled()` (before `initialize()`):

```kotlin
@OptIn(UnstableApi::class)
private fun buildMediaSource(streamInfo: StreamInfo): MediaSource {
    val dataSourceFactory = DefaultDataSource.Factory(context)
    return when (streamInfo) {
        is StreamInfo.DirectPlay -> ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(streamInfo.url))
        is StreamInfo.HlsTranscode -> HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(streamInfo.url))
    }
}
```

Replace the existing `play()` method (lines 145-163) with:

```kotlin
@OptIn(UnstableApi::class)
fun play(streamInfo: StreamInfo) {
    val mediaSource = buildMediaSource(streamInfo)
    currentSources = mutableListOf(mediaSource)
    player?.run {
        setMediaSource(mediaSource)
        prepare()
        playWhenReady = true
    }
}
```

- [ ] **Step 5: Add `playPlaylist()` method**

After `play()`, add:

```kotlin
@OptIn(UnstableApi::class)
fun playPlaylist(streamInfos: List<StreamInfo>) {
    currentSources = streamInfos.map { buildMediaSource(it) }.toMutableList()
    player?.run {
        setMediaSources(currentSources)
        prepare()
        playWhenReady = true
    }
}
```

- [ ] **Step 6: Add `replaceItem()` method**

After `playPlaylist()`, add:

```kotlin
@OptIn(UnstableApi::class)
fun replaceItem(index: Int, streamInfo: StreamInfo) {
    if (index < 0 || index >= currentSources.size) return
    currentSources[index] = buildMediaSource(streamInfo)
    player?.setMediaSources(currentSources, index, 0)
}
```

- [ ] **Step 7: Add playlist accessor methods**

After `isPlaying()` (line 173), add:

```kotlin
fun getCurrentItemIndex(): Int = player?.currentMediaItemIndex ?: 0
fun seekToItem(index: Int) { player?.seekTo(index, 0) }
fun getItemCount(): Int = player?.mediaItemCount ?: 0
```

- [ ] **Step 8: Run tests to verify no regression**

Run: `./gradlew testDebugUnitTest --tests "com.jellyfinbroadcast.core.MediaPlayerTest" --info`
Expected: ALL PASS (8 existing tests).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/core/MediaPlayer.kt
git commit -m "feat: add playlist support to MediaPlayer (playPlaylist, replaceItem, onItemTransition)"
```

---

## Chunk 2: TvActivity playlist orchestration

### Task 2: TvActivity — WebSocket branching + playPlaylist + transitions + fallback + NextTrack

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/tv/TvActivity.kt`

- [ ] **Step 1: Add coroutine imports**

At top of `TvActivity.kt`, add:

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout
```

- [ ] **Step 2: Add playlist fields**

After `private var lastBackPressTime = 0L` (line 48), add:

```kotlin
private var playlistStreamInfos: MutableList<StreamInfo>? = null
private var playlistItemIds: List<UUID>? = null
private var playlistFailedCount = 0
```

- [ ] **Step 3: Modify WebSocket PlayMessage handler**

Replace lines 148-154 (the `if (data.playCommand == PlayCommand.PLAY_NOW ...)` block) with:

```kotlin
if (data.playCommand == PlayCommand.PLAY_NOW && items.isNotEmpty()) {
    val startPositionTicks = data.startPositionTicks ?: 0L
    val startPositionMs = startPositionTicks / 10_000L
    withContext(Dispatchers.Main) {
        if (items.size == 1) {
            playItem(api, items.first(), startPositionMs)
        } else {
            playPlaylist(api, items, startPositionMs)
        }
    }
}
```

- [ ] **Step 4: Add `playPlaylist()` method**

Add after `playItem()` (after line 192):

```kotlin
private suspend fun playPlaylist(api: ApiClient, itemIds: List<UUID>, startPositionMs: Long) {
    val playerFragment = supportFragmentManager
        .findFragmentById(R.id.container) as? TvPlayerFragment
        ?: run {
            showPlayerScreen()
            supportFragmentManager.executePendingTransactions()
            supportFragmentManager.findFragmentById(R.id.container) as? TvPlayerFragment
        }
    val mediaPlayer = playerFragment?.getMediaPlayer() ?: return
    val serverUrl = jellyfinSession.getServerUrl() ?: return
    val token = api.accessToken ?: return

    // Stop previous playback
    mediaPlayer.stop()
    playbackReporter?.release()
    playbackReporter = null
    playlistFailedCount = 0

    // Resolve all streams in parallel with 5s per-item timeout
    val streamInfos = withContext(Dispatchers.IO) {
        itemIds.map { itemId ->
            async {
                try {
                    withTimeout(5000) {
                        resolveStreamInfo(api, itemId, serverUrl, token)
                    }
                } catch (_: Exception) {
                    Log.w(TAG, "Timeout resolving stream for $itemId, using HLS fallback")
                    val url = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
                    StreamInfo.HlsTranscode(url, null) as StreamInfo
                }
            }
        }.awaitAll()
    }

    playlistStreamInfos = streamInfos.toMutableList()
    playlistItemIds = itemIds

    Log.i(TAG, "Playing playlist: ${itemIds.size} items")
    mediaPlayer.playPlaylist(streamInfos)
    if (startPositionMs > 0) {
        mediaPlayer.seekTo(startPositionMs)
    }

    stateMachine.transition(AppEvent.Play)

    // Setup reporter for first item
    val firstStream = streamInfos.first()
    val reportPlayMethod = when (firstStream) {
        is StreamInfo.DirectPlay -> PlayMethod.DIRECT_PLAY
        is StreamInfo.HlsTranscode -> PlayMethod.TRANSCODE
    }
    val reporter = PlaybackReporter(api, reportPlayMethod, firstStream.playSessionId)
    playbackReporter = reporter
    reporter.reportPlaybackStart(itemIds.first(), startPositionMs)
    reporter.startPeriodicReporting(
        getPosition = { mediaPlayer.getCurrentPosition() },
        getIsPaused = { !mediaPlayer.isPlaying() }
    )

    // Item transition handler — new reporter per item
    mediaPlayer.onItemTransition = { newIndex ->
        val ids = playlistItemIds
        val streams = playlistStreamInfos
        if (ids != null && streams != null && newIndex < ids.size) {
            val posMs = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                playbackReporter?.reportPlaybackStop(posMs)
            }
            playbackReporter?.release()

            val stream = streams[newIndex]
            val method = when (stream) {
                is StreamInfo.DirectPlay -> PlayMethod.DIRECT_PLAY
                is StreamInfo.HlsTranscode -> PlayMethod.TRANSCODE
            }
            val newReporter = PlaybackReporter(api, method, stream.playSessionId)
            playbackReporter = newReporter
            newReporter.reportPlaybackStart(ids[newIndex], 0)
            newReporter.startPeriodicReporting(
                getPosition = { mediaPlayer.getCurrentPosition() },
                getIsPaused = { !mediaPlayer.isPlaying() }
            )
            Log.i(TAG, "Playlist transition: item ${newIndex + 1}/${ids.size} (${ids[newIndex]})")
        }
    }

    mediaPlayer.onSeekCompleted = {
        playbackReporter?.reportProgressNow()
    }

    // Playlist ended (last item finished)
    mediaPlayer.onPlaybackEnded = {
        val posMs = mediaPlayer.getCurrentPosition()
        lifecycleScope.launch(Dispatchers.IO) {
            playbackReporter?.reportPlaybackStop(posMs)
        }
        playlistStreamInfos = null
        playlistItemIds = null
        stateMachine.transition(AppEvent.Stop)
    }

    // Per-item error fallback
    mediaPlayer.onError = { error ->
        Log.e(TAG, "Playlist item error: ${error.message}", error)
        val currentIndex = mediaPlayer.getCurrentItemIndex()
        val ids = playlistItemIds
        val streams = playlistStreamInfos

        if (ids == null || streams == null || currentIndex >= streams.size) {
            stateMachine.transition(AppEvent.Stop)
            return@onError
        }

        val posMs = mediaPlayer.getCurrentPosition()
        lifecycleScope.launch(Dispatchers.IO) {
            playbackReporter?.reportPlaybackStop(posMs)
        }
        playbackReporter?.release()

        val currentStream = streams[currentIndex]
        val itemId = ids[currentIndex]

        // Try HLS fallback for this item (unless already HLS)
        val hlsUrl = when (currentStream) {
            is StreamInfo.DirectPlay -> currentStream.serverTranscodeUrl
                ?: MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
            is StreamInfo.HlsTranscode -> null
        }

        if (hlsUrl != null) {
            Log.i(TAG, "Replacing playlist item $currentIndex with HLS fallback")
            val hlsStream = StreamInfo.HlsTranscode(hlsUrl, currentStream.playSessionId)
            streams[currentIndex] = hlsStream
            mediaPlayer.replaceItem(currentIndex, hlsStream)

            val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId)
            playbackReporter = hlsReporter
            lifecycleScope.launch(Dispatchers.IO) {
                hlsReporter.reportPlaybackStart(itemId, 0)
            }
            hlsReporter.startPeriodicReporting(
                getPosition = { mediaPlayer.getCurrentPosition() },
                getIsPaused = { !mediaPlayer.isPlaying() }
            )
        } else {
            // Already HLS or no fallback → skip item
            playlistFailedCount++
            if (playlistFailedCount >= ids.size) {
                Log.e(TAG, "All playlist items failed, stopping")
                stateMachine.transition(AppEvent.Stop)
            } else if (currentIndex + 1 < mediaPlayer.getItemCount()) {
                Log.i(TAG, "Skipping failed item $currentIndex, advancing to next")
                mediaPlayer.seekToItem(currentIndex + 1)
            } else {
                Log.e(TAG, "Last playlist item failed, stopping")
                stateMachine.transition(AppEvent.Stop)
            }
        }
    }
}
```

- [ ] **Step 5: Implement NextTrack / PreviousTrack**

In `handlePlaystateCommand()`, replace lines 470-471:

```kotlin
// OLD:
PlaystateCommand.NEXT_TRACK -> Log.d(TAG, "NextTrack not implemented")
PlaystateCommand.PREVIOUS_TRACK -> Log.d(TAG, "PreviousTrack not implemented")

// NEW:
PlaystateCommand.NEXT_TRACK -> {
    if (mediaPlayer.getItemCount() > 1) {
        mediaPlayer.getExoPlayer()?.seekToNextMediaItem()
    }
}
PlaystateCommand.PREVIOUS_TRACK -> {
    if (mediaPlayer.getItemCount() > 1) {
        mediaPlayer.getExoPlayer()?.seekToPreviousMediaItem()
    }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew testDebugUnitTest --info`
Expected: ALL PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/tv/TvActivity.kt
git commit -m "feat: add playlist support to TvActivity (multi-item, transitions, per-item fallback, NextTrack)"
```

---

## Chunk 3: PhoneActivity + build verification

### Task 3: PhoneActivity — playlist support

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/phone/PhoneActivity.kt`

- [ ] **Step 1: Add coroutine imports**

At top of `PhoneActivity.kt`, add:

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout
```

- [ ] **Step 2: Add playlist fields**

After `private var lastBackPressTime = 0L` (line 48), add:

```kotlin
private var playlistStreamInfos: MutableList<StreamInfo>? = null
private var playlistItemIds: List<UUID>? = null
private var playlistFailedCount = 0
```

- [ ] **Step 3: Modify WebSocket PlayMessage handler**

Replace lines 195-201 (the `if (data.playCommand == PlayCommand.PLAY_NOW ...)` block) with:

```kotlin
if (data.playCommand == PlayCommand.PLAY_NOW && items.isNotEmpty()) {
    val startPositionTicks = data.startPositionTicks ?: 0L
    val startPositionMs = startPositionTicks / 10_000L
    withContext(Dispatchers.Main) {
        if (items.size == 1) {
            playItem(api, items.first(), startPositionMs)
        } else {
            playPlaylist(api, items, startPositionMs)
        }
    }
}
```

- [ ] **Step 4: Add `playPlaylist()` method**

Add after `playItem()` (after line 239):

```kotlin
private suspend fun playPlaylist(api: ApiClient, itemIds: List<UUID>, startPositionMs: Long) {
    val fragment = supportFragmentManager
        .findFragmentById(R.id.container) as? PhoneQrCodeFragment
        ?: run {
            supportFragmentManager.popBackStack()
            showIdleScreen()
            supportFragmentManager.executePendingTransactions()
            supportFragmentManager.findFragmentById(R.id.container) as? PhoneQrCodeFragment
        }
    val mediaPlayer = fragment?.getMediaPlayer() ?: return
    val serverUrl = jellyfinSession.getServerUrl() ?: return
    val token = api.accessToken ?: return

    mediaPlayer.stop()
    playbackReporter?.release()
    playbackReporter = null
    playlistFailedCount = 0

    val streamInfos = withContext(Dispatchers.IO) {
        itemIds.map { itemId ->
            async {
                try {
                    withTimeout(5000) {
                        resolveStreamInfo(api, itemId, serverUrl, token)
                    }
                } catch (_: Exception) {
                    Log.w(TAG, "Timeout resolving stream for $itemId, using HLS fallback")
                    val url = MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
                    StreamInfo.HlsTranscode(url, null) as StreamInfo
                }
            }
        }.awaitAll()
    }

    playlistStreamInfos = streamInfos.toMutableList()
    playlistItemIds = itemIds

    Log.i(TAG, "Playing playlist: ${itemIds.size} items")
    mediaPlayer.playPlaylist(streamInfos)
    if (startPositionMs > 0) {
        mediaPlayer.seekTo(startPositionMs)
    }

    fragment.onPlaybackStarted()

    val firstStream = streamInfos.first()
    val reportPlayMethod = when (firstStream) {
        is StreamInfo.DirectPlay -> PlayMethod.DIRECT_PLAY
        is StreamInfo.HlsTranscode -> PlayMethod.TRANSCODE
    }
    val reporter = PlaybackReporter(api, reportPlayMethod, firstStream.playSessionId)
    playbackReporter = reporter
    reporter.reportPlaybackStart(itemIds.first(), startPositionMs)
    reporter.startPeriodicReporting(
        getPosition = { mediaPlayer.getCurrentPosition() },
        getIsPaused = { !mediaPlayer.isPlaying() }
    )

    mediaPlayer.onItemTransition = { newIndex ->
        val ids = playlistItemIds
        val streams = playlistStreamInfos
        if (ids != null && streams != null && newIndex < ids.size) {
            val posMs = mediaPlayer.getCurrentPosition()
            lifecycleScope.launch(Dispatchers.IO) {
                playbackReporter?.reportPlaybackStop(posMs)
            }
            playbackReporter?.release()

            val stream = streams[newIndex]
            val method = when (stream) {
                is StreamInfo.DirectPlay -> PlayMethod.DIRECT_PLAY
                is StreamInfo.HlsTranscode -> PlayMethod.TRANSCODE
            }
            val newReporter = PlaybackReporter(api, method, stream.playSessionId)
            playbackReporter = newReporter
            newReporter.reportPlaybackStart(ids[newIndex], 0)
            newReporter.startPeriodicReporting(
                getPosition = { mediaPlayer.getCurrentPosition() },
                getIsPaused = { !mediaPlayer.isPlaying() }
            )
            Log.i(TAG, "Playlist transition: item ${newIndex + 1}/${ids.size}")
        }
    }

    mediaPlayer.onSeekCompleted = { playbackReporter?.reportProgressNow() }

    mediaPlayer.onPlaybackEnded = {
        val posMs = mediaPlayer.getCurrentPosition()
        lifecycleScope.launch(Dispatchers.IO) {
            playbackReporter?.reportPlaybackStop(posMs)
        }
        playlistStreamInfos = null
        playlistItemIds = null
    }

    mediaPlayer.onError = { error ->
        Log.e(TAG, "Playlist item error: ${error.message}", error)
        val currentIndex = mediaPlayer.getCurrentItemIndex()
        val ids = playlistItemIds
        val streams = playlistStreamInfos

        if (ids == null || streams == null || currentIndex >= streams.size) return@onError

        val posMs = mediaPlayer.getCurrentPosition()
        lifecycleScope.launch(Dispatchers.IO) {
            playbackReporter?.reportPlaybackStop(posMs)
        }
        playbackReporter?.release()

        val currentStream = streams[currentIndex]
        val itemId = ids[currentIndex]
        val hlsUrl = when (currentStream) {
            is StreamInfo.DirectPlay -> MediaPlayer.buildHlsFallbackUrl(serverUrl, itemId.toString(), token)
            is StreamInfo.HlsTranscode -> null
        }

        if (hlsUrl != null) {
            Log.i(TAG, "Replacing playlist item $currentIndex with HLS fallback")
            val hlsStream = StreamInfo.HlsTranscode(hlsUrl, currentStream.playSessionId)
            streams[currentIndex] = hlsStream
            mediaPlayer.replaceItem(currentIndex, hlsStream)

            val hlsReporter = PlaybackReporter(api, PlayMethod.TRANSCODE, hlsStream.playSessionId)
            playbackReporter = hlsReporter
            lifecycleScope.launch(Dispatchers.IO) {
                hlsReporter.reportPlaybackStart(itemId, 0)
            }
            hlsReporter.startPeriodicReporting(
                getPosition = { mediaPlayer.getCurrentPosition() },
                getIsPaused = { !mediaPlayer.isPlaying() }
            )
        } else {
            playlistFailedCount++
            if (playlistFailedCount >= ids.size) {
                Log.e(TAG, "All playlist items failed")
            } else if (currentIndex + 1 < mediaPlayer.getItemCount()) {
                Log.i(TAG, "Skipping failed item $currentIndex")
                mediaPlayer.seekToItem(currentIndex + 1)
            }
        }
    }
}
```

- [ ] **Step 5: Implement NextTrack / PreviousTrack**

In `handlePlaystateCommand()`, replace lines 411-412:

```kotlin
// OLD:
PlaystateCommand.NEXT_TRACK -> {}
PlaystateCommand.PREVIOUS_TRACK -> {}

// NEW:
PlaystateCommand.NEXT_TRACK -> {
    if (mediaPlayer.getItemCount() > 1) {
        mediaPlayer.getExoPlayer()?.seekToNextMediaItem()
    }
}
PlaystateCommand.PREVIOUS_TRACK -> {
    if (mediaPlayer.getItemCount() > 1) {
        mediaPlayer.getExoPlayer()?.seekToPreviousMediaItem()
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/phone/PhoneActivity.kt
git commit -m "feat: add playlist support to PhoneActivity (multi-item, transitions, fallback, NextTrack)"
```

---

### Task 4: Fix remote control key handling (pause bug)

**Files:**
- Modify: `app/src/main/java/com/jellyfinbroadcast/tv/TvActivity.kt`
- Modify: `app/src/main/java/com/jellyfinbroadcast/tv/TvPlayerFragment.kt`
- Modify: `app/src/main/java/com/jellyfinbroadcast/phone/PhoneActivity.kt`

**Bug:** `PlayerView.dispatchMediaKeyEvent()` intercepts `MEDIA_PLAY_PAUSE` before the Activity sees it, toggling ExoPlayer directly but bypassing state machine and reporter. Also `MEDIA_PAUSE`, `MEDIA_PLAY`, and `ENTER` keycodes are unhandled.

**Fix:** Override `dispatchKeyEvent()` in TvActivity to intercept media keys BEFORE the View hierarchy. Remove dead code in TvPlayerFragment.

- [ ] **Step 1: Add `dispatchKeyEvent()` override in TvActivity**

Add before `onKeyDown()`:

```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (stateMachine.currentState is AppState.PLAYING ||
                    stateMachine.currentState is AppState.PAUSED
                ) {
                    handlePlaystateCommand(PlaystateCommand.PLAY_PAUSE, null)
                    return true
                }
            }
        }
    }
    return super.dispatchKeyEvent(event)
}
```

- [ ] **Step 2: Remove dead `onKeyDown` from TvPlayerFragment**

In `TvPlayerFragment.kt`, remove the entire `onKeyDown()` method (lines 38-46):

```kotlin
// DELETE THIS:
fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            val mp = mediaPlayer ?: return false
            if (mp.isPlaying()) mp.pause() else mp.resume()
            true
        }
        else -> false
    }
}
```

Also remove the unused `import android.view.KeyEvent` from `TvPlayerFragment.kt`.

- [ ] **Step 3: Remove TvPlayerFragment.onKeyDown call from TvActivity.onKeyDown**

In `TvActivity.onKeyDown()`, remove lines 498-500:

```kotlin
// DELETE THESE LINES:
val playerFragment = supportFragmentManager
    .findFragmentById(R.id.container) as? TvPlayerFragment
if (playerFragment?.onKeyDown(keyCode, event) == true) return true
```

The method becomes:

```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.repeatCount == 0) {
        event.startTracking()
        return true
    }
    return super.onKeyDown(keyCode, event)
}
```

- [ ] **Step 4: Add same `dispatchKeyEvent()` to PhoneActivity**

In `PhoneActivity.kt`, add before `onBackPressed()`:

```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                handlePlaystateCommand(PlaystateCommand.PLAY_PAUSE, null)
                return true
            }
        }
    }
    return super.dispatchKeyEvent(event)
}
```

Add import at top of PhoneActivity.kt:

```kotlin
import android.view.KeyEvent
```

- [ ] **Step 5: Run tests**

Run: `./gradlew testDebugUnitTest --info`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellyfinbroadcast/tv/TvActivity.kt app/src/main/java/com/jellyfinbroadcast/tv/TvPlayerFragment.kt app/src/main/java/com/jellyfinbroadcast/phone/PhoneActivity.kt
git commit -m "fix: intercept media keys in dispatchKeyEvent to fix intermittent pause on remote"
```

---

### Task 5: Build verification

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew testDebugUnitTest --info`
Expected: ALL PASS.

- [ ] **Step 2: Run full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit fixups if needed**

Only commit if there were actual build fixups. Do not create an empty commit.
