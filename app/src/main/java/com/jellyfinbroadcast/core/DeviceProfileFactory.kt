package com.jellyfinbroadcast.core

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import androidx.media3.exoplayer.audio.AudioCapabilities
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

object DeviceProfileFactory {

    private const val TAG = "DeviceProfileFactory"

    /** Android MIME type → Jellyfin codec name */
    private val VIDEO_MIME_MAP = mapOf(
        "video/avc" to "h264",
        "video/hevc" to "hevc",
        "video/x-vnd.on2.vp8" to "vp8",
        "video/x-vnd.on2.vp9" to "vp9",
        "video/av01" to "av1",
        "video/mpeg2" to "mpeg2video"
    )

    private val AUDIO_MIME_MAP = mapOf(
        "audio/mp4a-latm" to "aac",
        "audio/ac3" to "ac3",
        "audio/eac3" to "eac3",
        "audio/vnd.dts" to "dts",
        "audio/vnd.dts.hd" to "dts",
        "audio/mpeg" to "mp3",
        "audio/flac" to "flac",
        "audio/opus" to "opus",
        "audio/vorbis" to "vorbis",
        "audio/true-hd" to "truehd"
    )

    private val SUPPORTED_CONTAINERS = arrayOf("mkv", "mp4", "webm", "mov", "mpegts", "ts")

    /** Capabilities detected for a specific video codec */
    private data class VideoCodecCaps(
        val codec: String,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxBitrate: Int,
        val maxBitDepth: Int
    )

    fun build(context: Context): DeviceProfile {
        val videoCaps = detectVideoCodecCaps()
        val audioCodecs = detectCodecs(AUDIO_MIME_MAP)
        val videoCodecNames = videoCaps.map { it.codec }

        Log.i(TAG, "Detected video codecs: $videoCodecNames")
        for (cap in videoCaps) {
            Log.i(TAG, "  ${cap.codec}: ${cap.maxWidth}x${cap.maxHeight}, " +
                "${cap.maxBitDepth}-bit, ${cap.maxBitrate / 1_000_000}Mbps max")
        }
        Log.i(TAG, "Detected audio codecs: $audioCodecs")

        val passthroughCodecs = detectPassthroughCodecs(context)
        Log.i(TAG, "Passthrough codecs (HDMI): $passthroughCodecs")

        return buildDeviceProfile {
            maxStreamingBitrate = 120_000_000
            maxStaticBitrate = 120_000_000

            // DirectPlay for video: only codecs the device can actually decode
            directPlayProfile {
                type = DlnaProfileType.VIDEO
                container(*SUPPORTED_CONTAINERS)
                videoCodec(*videoCodecNames.toTypedArray())
                audioCodec(*audioCodecs.toTypedArray())
            }

            // DirectPlay for audio-only items
            directPlayProfile {
                type = DlnaProfileType.AUDIO
                container("mp3", "flac", "aac", "ogg", "wav", "webm")
                audioCodec(*audioCodecs.toTypedArray())
            }

            // Codec conditions: tell Jellyfin the limits per video codec
            for (cap in videoCaps) {
                codecProfile {
                    type = CodecType.VIDEO
                    codec = cap.codec
                    conditions {
                        lowerThanOrEquals(ProfileConditionValue.WIDTH, cap.maxWidth)
                        lowerThanOrEquals(ProfileConditionValue.HEIGHT, cap.maxHeight)
                        lowerThanOrEquals(ProfileConditionValue.VIDEO_BIT_DEPTH, cap.maxBitDepth)
                        lowerThanOrEquals(ProfileConditionValue.VIDEO_BITRATE, cap.maxBitrate)
                    }
                }
            }

            // HLS transcode fallback when direct play is not possible
            transcodingProfile {
                type = DlnaProfileType.VIDEO
                this.context = EncodingContext.STREAMING
                protocol = MediaStreamProtocol.HLS
                container = "ts"
                videoCodec("h264")
                audioCodec("aac", "ac3")
                maxAudioChannels = "6"
                breakOnNonKeyFrames = true
            }

            // Audio-only transcode fallback
            transcodingProfile {
                type = DlnaProfileType.AUDIO
                this.context = EncodingContext.STREAMING
                protocol = MediaStreamProtocol.HLS
                container = "ts"
                audioCodec("aac")
                maxAudioChannels = "2"
            }

            // Subtitle profiles
            subtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL)
            subtitleProfile("sub", SubtitleDeliveryMethod.EXTERNAL)
            subtitleProfile("vtt", SubtitleDeliveryMethod.EXTERNAL)
            subtitleProfile("ass", SubtitleDeliveryMethod.ENCODE)
            subtitleProfile("ssa", SubtitleDeliveryMethod.ENCODE)
            subtitleProfile("pgs", SubtitleDeliveryMethod.ENCODE)
            subtitleProfile("pgssub", SubtitleDeliveryMethod.ENCODE)
            subtitleProfile("dvdsub", SubtitleDeliveryMethod.ENCODE)
            subtitleProfile("dvbsub", SubtitleDeliveryMethod.ENCODE)
        }
    }

    /**
     * Detects video codecs with their actual hardware capabilities:
     * max resolution, max bitrate, and max bit depth (8 or 10).
     */
    private fun detectVideoCodecCaps(): List<VideoCodecCaps> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        // Track best caps per Jellyfin codec name (multiple decoders may exist)
        val best = mutableMapOf<String, VideoCodecCaps>()

        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            for (mimeType in info.supportedTypes) {
                val mime = mimeType.lowercase()
                val jellyfinName = VIDEO_MIME_MAP[mime] ?: continue

                try {
                    val caps = info.getCapabilitiesForType(mimeType)
                    val videoCaps = caps.videoCapabilities ?: continue

                    val maxW = videoCaps.supportedWidths.upper
                    val maxH = videoCaps.supportedHeights.upper
                    val maxBr = videoCaps.bitrateRange.upper

                    val maxBitDepth = detectMaxBitDepth(caps, mime)

                    val current = VideoCodecCaps(jellyfinName, maxW, maxH, maxBr, maxBitDepth)
                    val existing = best[jellyfinName]

                    // Keep the decoder with the best capabilities
                    if (existing == null || isBetter(current, existing)) {
                        best[jellyfinName] = current
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query caps for $mimeType on ${info.name}: ${e.message}")
                }
            }
        }

        return best.values.toList()
    }

    /**
     * Determines max supported bit depth by checking codec profile-level pairs.
     * Returns 10 if any 10-bit profile is supported, 8 otherwise.
     */
    private fun detectMaxBitDepth(
        caps: MediaCodecInfo.CodecCapabilities,
        mime: String
    ): Int {
        val tenBitProfiles = when (mime) {
            "video/hevc" -> setOf(
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            )
            "video/av01" -> setOf(
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
            )
            "video/x-vnd.on2.vp9" -> setOf(
                MediaCodecInfo.CodecProfileLevel.VP9Profile2,     // 10-bit
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus,
                MediaCodecInfo.CodecProfileLevel.VP9Profile3,     // 10-bit
                MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR,
                MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
            )
            else -> emptySet()
        }

        if (tenBitProfiles.isEmpty()) return 8

        val supported = caps.profileLevels ?: return 8
        return if (supported.any { it.profile in tenBitProfiles }) 10 else 8
    }

    /** Returns true if [a] has better capabilities than [b]. */
    private fun isBetter(a: VideoCodecCaps, b: VideoCodecCaps): Boolean {
        // Prefer higher bit depth, then higher resolution, then higher bitrate
        if (a.maxBitDepth != b.maxBitDepth) return a.maxBitDepth > b.maxBitDepth
        val aPixels = a.maxWidth.toLong() * a.maxHeight
        val bPixels = b.maxWidth.toLong() * b.maxHeight
        if (aPixels != bPixels) return aPixels > bPixels
        return a.maxBitrate > b.maxBitrate
    }

    private fun detectCodecs(mimeMap: Map<String, String>): List<String> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val detected = mutableSetOf<String>()

        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            for (mimeType in info.supportedTypes) {
                val jellyfinName = mimeMap[mimeType.lowercase()]
                if (jellyfinName != null) {
                    detected.add(jellyfinName)
                }
            }
        }

        return detected.toList()
    }

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
}
