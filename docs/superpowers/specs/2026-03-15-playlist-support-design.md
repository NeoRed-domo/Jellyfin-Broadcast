# Multi-Item Playlist Support for Seamless Transitions

**Date:** 2026-03-15
**Status:** Approved
**Scope:** MediaPlayer, TvActivity, PhoneActivity

## Context

Jellyfin Broadcast is used in automated cinema sessions orchestrated by a Jeedom plugin. The plugin sends sequences of clips (ad → trailer → film) via `PlayNow` commands with multiple ItemIds. Currently, only the first ItemId is used (`items.first()` in TvActivity), and each transition takes ~3 seconds due to sequential single-item playback.

**Goal:** Support multi-item playlists so ExoPlayer loads all items upfront and transitions seamlessly (~0ms) between them via native pre-buffering. Single-item playback must remain identical.

## Design

### 1. MediaPlayer — Playlist Support

New methods on `MediaPlayer`:

- **`playPlaylist(streamInfos: List<StreamInfo>)`** — converts each `StreamInfo` to a `MediaSource` (Progressive or HLS), loads all via `player.setMediaSources(sources)`, calls `prepare()` and `playWhenReady = true`. ExoPlayer pre-buffers the next item automatically.
- **`replaceItem(index: Int, streamInfo: StreamInfo)`** — replaces a single item in the playlist by rebuilding the complete source list with the replacement and calling `player.setMediaSources(updatedList, index, 0)`. This avoids race conditions from modifying the playlist at the currently-playing index (remove+add would trigger unwanted `onMediaItemTransition` events). The `resetPosition = false` semantics of the 3-arg overload starts playback at the specified index and position.
- **`getCurrentItemIndex(): Int`** — returns `player?.currentMediaItemIndex ?: 0`.
- **`seekToItem(index: Int)`** — calls `player?.seekTo(index, 0)` to jump to a specific playlist item.
- **`getItemCount(): Int`** — returns `player?.mediaItemCount ?: 0`.

New callback:

- **`onItemTransition: ((Int) -> Unit)?`** — fires via `Player.Listener.onMediaItemTransition(MediaItem, @TransitionReason int)`. Fires for `MEDIA_ITEM_TRANSITION_REASON_AUTO` (natural end of item) and `MEDIA_ITEM_TRANSITION_REASON_SEEK` (NextTrack/PreviousTrack). Ignores `MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED` (avoids double-firing during `replaceItem`) and `MEDIA_ITEM_TRANSITION_REASON_REPEAT`. Provides the new item index.

Existing behavior unchanged:

- `play(streamInfo)` still works for single-item playback.
- `onPlaybackEnded` only fires when the **last** item in the playlist ends. This is guaranteed by ExoPlayer's behavior: `STATE_ENDED` is only reached after the last item finishes; intermediate items trigger `onMediaItemTransition` instead. No guard needed in code.
- `onError` fires for the **current** item in the playlist.

### 2. TvActivity — Playlist Orchestration

**WebSocket handler modification:** When `PlayCommand.PLAY_NOW` arrives:

- 1 item → existing `playItem()` (unchanged)
- N items → new `playPlaylist(api, items, startPositionMs)`

**New fields:**

- `playlistStreamInfos: MutableList<StreamInfo>?` — mutable list of stream infos, updated on fallback replacements
- `playlistItemIds: List<UUID>?` — immutable list of item UUIDs for reporting

**New `playPlaylist()` method:**

1. Resolves `StreamInfo` for all items **in parallel** via `async` + `awaitAll` on `Dispatchers.IO`. Each resolution has a **5-second timeout** — on timeout, falls back to the HLS URL for that item. This prevents a single slow resolution from blocking the entire cinema chain start.
2. Stores `playlistStreamInfos` and `playlistItemIds` as Activity fields
3. Calls `mediaPlayer.playPlaylist(streamInfos)`
4. Seeks to `startPositionMs` on the first item if > 0. Items 2+ always start from position 0 (expected cinema chain behavior).
5. Creates a new `PlaybackReporter` for the first item with that item's `playMethod` and `playSessionId`
6. Wires up callbacks: `onItemTransition`, `onError`, `onPlaybackEnded`, `onSeekCompleted`

**`onItemTransition` handler:**

1. Reads current position for the previous item
2. Calls `reporter.reportPlaybackStop(posMs)` for the previous item
3. Releases the old reporter
4. Creates a **new `PlaybackReporter`** for the new item with the correct `playMethod` and `playSessionId` from `playlistStreamInfos[newIndex]`. Each item may have a different `playSessionId` (returned by `getPostedPlaybackInfo`) and `playMethod` (DirectPlay vs HLS), so the reporter cannot be reused.
5. Calls `reporter.reportPlaybackStart(newItemId, 0)` for the new item
6. Starts periodic reporting on the new reporter

**Per-item error fallback in `onError`:**

1. Gets the current item index via `mediaPlayer.getCurrentItemIndex()`
2. If audio passthrough error or DirectPlay error → replaces the failing item with its HLS transcode version via `mediaPlayer.replaceItem(index, hlsStreamInfo)`. Updates `playlistStreamInfos[index]` with the new stream info. The HLS transcode uses `AudioCodec=aac` which is always software-decoded by ExoPlayer (never passed through HDMI), so this effectively bypasses passthrough issues without re-initializing the player. The passthrough decision in `DefaultAudioSink` is per-format: AC3/DTS go through passthrough, AAC is always PCM-decoded.
3. If HLS also fails → skip the item via `mediaPlayer.seekToItem(index + 1)` if more items remain. Increment `failedItemCount`.
4. **Circuit breaker:** If `failedItemCount == playlistItemIds.size`, all items have failed. Report stop and transition to Stop state immediately without further retries.
5. If it's the last item and it fails, transition to Stop state.

**NextTrack / PreviousTrack:**

```
NEXT_TRACK → player.seekToNextMediaItem()
PREVIOUS_TRACK → player.seekToPreviousMediaItem()
```

These trigger `onMediaItemTransition` with `MEDIA_ITEM_TRANSITION_REASON_SEEK`, which handles the reporting automatically.

**Stop command during playlist:** `PlaystateCommand.STOP` reports stop for the currently playing item only. Other queued items are silently discarded (they were never started, so no report needed).

### 3. PhoneActivity — Same WebSocket Handler Change

The PlayMessage handler gains the same 1-item vs N-items branching. `PhoneActivity` gets a `playPlaylist()` that resolves streams in parallel (with 5s per-item timeout) and loads them. The per-item fallback on Phone is DirectPlay → HLS only (no passthrough chain, since `enablePassthrough = false` on Phone). New reporter per item transition, same pattern as TvActivity.

### 4. PlaybackReporter — No Structural Changes

The reporter class itself is unchanged. A **new instance** is created per item transition, with the correct `playMethod` and `playSessionId` for that item. The Activity manages the reporter lifecycle:
- On transition: release old reporter → create new → start periodic reporting
- On stop/end: release current reporter

### 5. Testing

- **MediaPlayer:** Test `playPlaylist` loads N sources. Test `replaceItem` rebuilds source list correctly. Test `getCurrentItemIndex` / `getItemCount` accessors.
- **Existing tests:** Unaffected — single-item `play()` behavior unchanged.
- **Integration:** Playlist transitions and reporting cannot be unit-tested without a mock Jellyfin server. Manual testing with the Jeedom cinema chain is the primary validation.

## Files Modified

| File | Change |
|---|---|
| `MediaPlayer.kt` | `playPlaylist()`, `replaceItem()`, `getCurrentItemIndex()`, `seekToItem()`, `getItemCount()`, `onItemTransition` callback |
| `TvActivity.kt` | `playPlaylist()`, parallel stream resolution with timeout, `onItemTransition` handler with new reporter per item, per-item error fallback with circuit breaker, NextTrack/PreviousTrack |
| `PhoneActivity.kt` | Same WebSocket handler change (1 item vs N items), simplified `playPlaylist()`, new reporter per transition |
| `PlaybackReporter.kt` | No changes |
| `MediaPlayerTest.kt` | New tests for playlist methods |

## Non-Goals

- No queue management (add/remove items after playlist starts)
- No repeat/shuffle modes
- No changes to QR code / configuration / Ktor server / device profile
- No UI playlist display (headless playback)
