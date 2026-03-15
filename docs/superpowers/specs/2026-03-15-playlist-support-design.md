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
- **`replaceItem(index: Int, streamInfo: StreamInfo)`** — replaces a single item in the playlist via `player.removeMediaItem(index)` + `player.addMediaItem(index, mediaSource)`. Used for per-item fallback when passthrough or DirectPlay fails.
- **`getCurrentItemIndex(): Int`** — returns `player?.currentMediaItemIndex ?: 0`.
- **`seekToItem(index: Int)`** — calls `player?.seekTo(index, 0)` to jump to a specific playlist item.
- **`getItemCount(): Int`** — returns `player?.mediaItemCount ?: 0`.

New callback:

- **`onItemTransition: ((Int) -> Unit)?`** — fires via `Player.Listener.onMediaItemTransition()` when ExoPlayer auto-advances to the next item. Provides the new item index. Only fires for automatic transitions (not seek-based).

Existing behavior unchanged:

- `play(streamInfo)` still works for single-item playback.
- `onPlaybackEnded` only fires when the **last** item in the playlist ends (`STATE_ENDED`).
- `onError` fires for the **current** item in the playlist.

### 2. TvActivity — Playlist Orchestration

**WebSocket handler modification:** When `PlayCommand.PLAY_NOW` arrives:

- 1 item → existing `playItem()` (unchanged)
- N items → new `playPlaylist(api, items, startPositionMs)`

**New `playPlaylist()` method:**

1. Resolves `StreamInfo` for all items **in parallel** via `async` + `awaitAll` on `Dispatchers.IO`
2. Stores `playlistStreamInfos: List<StreamInfo>` and `playlistItemIds: List<UUID>` as Activity fields (needed for fallback and reporting)
3. Calls `mediaPlayer.playPlaylist(streamInfos)`
4. Seeks to `startPositionMs` on the first item if > 0
5. Initializes `PlaybackReporter` for the first item
6. Wires up callbacks: `onItemTransition`, `onError`, `onPlaybackEnded`, `onSeekCompleted`

**`onItemTransition` handler:**

1. Reads current position for the previous item
2. Calls `reporter.reportPlaybackStop(posMs)` for the previous item
3. Calls `reporter.reportPlaybackStart(newItemId, 0)` for the new item (updates `currentItemId` in the reporter)
4. Logs the transition

**Per-item error fallback in `onError`:**

1. Gets the current item index via `mediaPlayer.getCurrentItemIndex()`
2. If audio passthrough error → replaces the failing item with its HLS transcode version via `mediaPlayer.replaceItem(index, hlsStreamInfo)`, then `mediaPlayer.seekToItem(index)` to replay it
3. If non-audio error on DirectPlay → same: replace with HLS transcode, seek to item
4. If HLS also fails → skip the item via `mediaPlayer.seekToItem(index + 1)` if more items remain. If it's the last item, transition to Stop state.
5. The `playlistStreamInfos` list is updated at each replacement so subsequent errors reference the correct stream info.

**NextTrack / PreviousTrack:**

```
NEXT_TRACK → player.seekToNextMediaItem()
PREVIOUS_TRACK → player.seekToPreviousMediaItem()
```

Transitions trigger `onItemTransition` which handles the reporting automatically.

### 3. PhoneActivity — Same WebSocket Handler Change

The PlayMessage handler gains the same 1-item vs N-items branching. `PhoneActivity` gets a simplified `playPlaylist()` that resolves streams in parallel and loads them. The per-item fallback follows the same logic as TvActivity.

### 4. PlaybackReporter — No Structural Changes

The reporter is reused across item transitions. The Activity calls:
- `reportPlaybackStop(posMs)` for the ending item
- `reportPlaybackStart(newItemId, 0)` for the starting item

The reporter's `currentItemId` is updated via `reportPlaybackStart`, so periodic reporting (every 10s) always references the correct item. No new reporter instance is created per item.

### 5. Testing

- **MediaPlayer:** Test `playPlaylist` loads N sources. Test `replaceItem` swaps a single item. Test `getCurrentItemIndex` / `getItemCount` accessors.
- **Existing tests:** Unaffected — single-item `play()` behavior unchanged.
- **Integration:** Playlist transitions and reporting cannot be unit-tested without a mock Jellyfin server. Manual testing with the Jeedom cinema chain is the primary validation.

## Files Modified

| File | Change |
|---|---|
| `MediaPlayer.kt` | `playPlaylist()`, `replaceItem()`, `getCurrentItemIndex()`, `seekToItem()`, `getItemCount()`, `onItemTransition` callback |
| `TvActivity.kt` | `playPlaylist()`, parallel stream resolution, `onItemTransition` handler, per-item error fallback, NextTrack/PreviousTrack |
| `PhoneActivity.kt` | Same WebSocket handler change (1 item vs N items), simplified `playPlaylist()` |
| `PlaybackReporter.kt` | No changes |
| `MediaPlayerTest.kt` | New tests for playlist methods |

## Non-Goals

- No queue management (add/remove items after playlist starts)
- No repeat/shuffle modes
- No changes to QR code / configuration / Ktor server / device profile
- No UI playlist display (headless playback)
