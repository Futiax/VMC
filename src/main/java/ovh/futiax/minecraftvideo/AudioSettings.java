package ovh.futiax.minecraftvideo;

/**
 * Immutable audio configuration snapshot taken when a playback starts.
 *
 * <p>Sync model (freeze-frame): the first video frame is sent immediately and
 * FROZEN for {@code audioStartDelayMillis} — the SVC client absorbs the render
 * spike of a screen appearing while the picture is static — then video pacing
 * and audio start together. The audio SKIPS {@code avSyncDelayMillis} of
 * content (via ffmpeg {@code -ss}) so the sound the client hears, after its
 * own buffering, lines up with the picture.
 *
 * @param enabled                master switch ({@code audio-enabled})
 * @param ffmpegPath             ffmpeg executable ({@code ffmpeg-path})
 * @param distance               SVC falloff distance in blocks ({@code audio-distance})
 * @param mode                   channel layout ({@code audio-mode})
 * @param avSyncDelayMillis      estimated client-side audio latency (jitter
 *                               buffer) compensated by skipping that much audio
 *                               content; audio lags → increase, audio leads →
 *                               decrease ({@code av-sync-delay-ms})
 * @param audioStartDelayMillis  how long the first frame stays frozen before
 *                               pacing and audio start ({@code audio-start-delay-ms})
 * @param rearDistance           how many blocks behind the screen plane (toward
 *                               the audience) the surround rear speakers sit
 *                               ({@code surround-rear-distance})
 */
public record AudioSettings(boolean enabled, String ffmpegPath, int distance,
                            AudioMode mode, int avSyncDelayMillis,
                            int audioStartDelayMillis, double rearDistance) {

    /** Audio content skipped at each segment start, in millis. */
    public long audioSkipMillis() {
        return Math.max(0, avSyncDelayMillis);
    }
}
