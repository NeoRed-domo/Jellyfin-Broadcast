# Audio Passthrough with Automatic Fallback

**Date:** 2026-03-14
**Status:** Approved
**Scope:** MediaPlayer, TvActivity, PhoneActivity, DeviceProfileFactory

## Context

Jellyfin Broadcast uses Media3/ExoPlayer 1.3.1 for playback. Currently, all audio is decoded on-device (PCM). Android TV devices connected to AVRs/soundbars via HDMI can pass encoded audio bitstreams (AC3, EAC3, DTS, TrueHD) directly to the receiver — this is "audio passthrough."

**Goal:** Enable passthrough automatically when the hardware supports it, with transparent fallback to PCM decoding on failure. Zero user configuration.

## Design

### 1. MediaPlayer — Passthrough Support

`MediaPlayer.initialize()` gains a parameter `enablePassthrough: Boolean = true`.

**AudioAttributes:** Set `CONTENT_TYPE_MOVIE` + `USAGE_MEDIA` with `handleAudioFocus = true`. This signals Android that the content is media suitable for passthrough consideration.

**Custom RenderersFactory:** Subclass `DefaultRenderersFactory` and override `buildAudioSink()` to inject `AudioCapabilities`:

- `enablePassthrough = true` → `AudioCapabilities.getCapabilities(context)` — queries HDMI/EDID for supported encoded formats
- `enablePassthrough = false` → `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` — PCM only, forces software decoding

**State tracking:** `isPassthroughEnabled()` exposes current mode for fallback logic.

**Player recreation callback:** `onPlayerRecreated: ((ExoPlayer) -> Unit)?` allows the hosting Fragment to rebind its `PlayerView` after the player is re-initialized.

**Default behavior unchanged:** `initialize()` defaults to `enablePassthrough = true`, so all existing call sites work without modification.

### 2. Fallback Chain

The fallback logic lives in the Activities (TvActivity, PhoneActivity), which already orchestrate DirectPlay → HLS retries. A new intermediate step is inserted:

```
DirectPlay + Passthrough audio
    ↓ audio error (ERROR_CODE_AUDIO_TRACK_INIT_FAILED / ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
DirectPlay + PCM (same stream, player re-initialized without passthrough)
    ↓ non-audio error
HLS Transcode (existing fallback, unchanged)
```

**Error detection:** In the `onError` handler within `startPlayback()`:

1. Check if `mediaPlayer.isPassthroughEnabled()` AND the error code is audio-related (`PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED` or `ERROR_CODE_AUDIO_TRACK_WRITE_FAILED`)
2. If yes: call `mediaPlayer.initialize(enablePassthrough = false)`, rebind PlayerView via `onPlayerRecreated`, replay the same `StreamInfo` at the same position, log the fallback
3. If no: proceed to existing HLS fallback (unchanged)

**Position preservation:** On passthrough fallback, the current playback position is captured before re-init and restored via `seekTo()` after replay.

### 3. DeviceProfileFactory — Passthrough Detection

Add `detectPassthroughCodecs(context): List<String>` using `AudioCapabilities.getCapabilities(context).supportsEncoding()`.

Tested encodings:

| Android Encoding Constant | Jellyfin Codec |
|---|---|
| `ENCODING_AC3` | ac3 |
| `ENCODING_E_AC3` | eac3 |
| `ENCODING_DTS` | dts |
| `ENCODING_DTS_HD` | dts (HD) |
| `ENCODING_DOLBY_TRUEHD` | truehd |

**Logging only:** Results are logged at `INFO` level during `build()` for debugging. Example: `"Passthrough codecs: [ac3, eac3]"`.

**No profile change:** The Jellyfin device profile already declares supported audio codecs for DirectPlay. Whether playback uses passthrough or PCM decoding is transparent to the server — it sends the same stream either way.

### 4. Testing

- **MediaPlayer:** Verify `initialize(true)` and `initialize(false)` create the player correctly. Verify `isPassthroughEnabled()` reflects the parameter.
- **DeviceProfileFactory:** Verify `detectPassthroughCodecs()` returns a list (empty on emulator, populated on real device with HDMI).
- **Fallback logic:** Verify audio error codes (`ERROR_CODE_AUDIO_TRACK_INIT_FAILED`) trigger PCM retry, and non-audio errors pass through to existing HLS fallback.
- **Existing tests:** Unaffected — `initialize()` default is `true`, same as current behavior (no passthrough config = ExoPlayer default).

## Files Modified

| File | Change |
|---|---|
| `MediaPlayer.kt` | `AudioAttributes`, `enablePassthrough` param, custom `RenderersFactory`, `onPlayerRecreated` callback, `isPassthroughEnabled()` |
| `TvActivity.kt` | `onError` handler: audio error + passthrough → re-init PCM + replay |
| `PhoneActivity.kt` | Same fallback logic as TvActivity |
| `DeviceProfileFactory.kt` | `detectPassthroughCodecs(context)` + logging in `build()` |
| `MediaPlayerTest.kt` | New tests for passthrough init and fallback error detection |

## Non-Goals

- No user-facing settings or toggles
- No changes to the Jellyfin device profile sent to the server
- No changes to the HLS transcode fallback logic
- No audio offload (DSP processing) — only HDMI passthrough
