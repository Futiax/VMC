package ovh.futiax.minecraftvideo;

/**
 * Immutable audio configuration snapshot taken when a playback starts.
 *
 * @param enabled            master switch ({@code audio-enabled})
 * @param ffmpegPath         ffmpeg executable ({@code ffmpeg-path})
 * @param distance           SVC falloff distance in blocks ({@code audio-distance})
 * @param mode               channel layout ({@code audio-mode})
 * @param avSyncDelayMillis  how long to delay the FIRST video frame after audio
 *                           starts, to compensate the latency the SVC client
 *                           jitter buffer adds to the audio path only
 *                           ({@code av-sync-delay-ms})
 * @param rearDistance       how many blocks behind the screen plane (toward the
 *                           audience) the surround rear speakers sit
 *                           ({@code surround-rear-distance})
 */
public record AudioSettings(boolean enabled, String ffmpegPath, int distance,
                            AudioMode mode, int avSyncDelayMillis, double rearDistance) {
}
