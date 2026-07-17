package ovh.futiax.minecraftvideo;

/**
 * Subtitle-overlay geometry, set via {@code /video option sub ...} and persisted
 * in config.yml. Read when the screen is built, so a change applies to the next
 * {@code /video play} (like the screen-size / audio options).
 *
 * @param scale          text scale multiplier ({@code subtitle-size})
 * @param heightAboveEdge blocks above the screen's bottom edge ({@code subtitle-height});
 *                        a control bar, if present, adds its own clearance on top
 * @param depthInFront   blocks in front of the screen surface ({@code subtitle-depth};
 *                       0 = on the plane, a small value avoids z-fighting)
 */
public record SubtitleSettings(float scale, double heightAboveEdge, double depthInFront) {
}
