# MinecraftVideo Paper plugin

Plays videos on virtual in-game map screens on a Paper 1.21.8 server.

The plugin spawns the existing native converter (`mcmm`, from `c version/`)
as a subprocess and forwards its decoded frames to players as map packets.
**Everything is virtual**: fake entity ids, fake map ids, packets only —
nothing is written to the world or to disk.

## Requirements

- Paper 1.21.8
- Java 21
- The [**packetevents**](https://modrinth.com/plugin/packetevents) plugin
  installed on the server (a recent build that supports 1.21.8, e.g. >= 2.11.2)
- `ffmpeg` in `PATH` (only needed for audio; see below)
- **Optional, for audio:** the [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)
  mod/plugin (server **and** clients), version >= 2.6.0

The `mcmm` binary and the color palette are **bundled inside the jar** and
extracted into the plugin data folder on first start, so you don't place them
by hand (a statically-linked `linux-x64` binary ships by default; other
platforms need their binary added — see Build). ffmpeg is not bundled (size and
(L)GPL redistribution) and must be in `PATH`.

The bundled palette is the **vanilla Minecraft map-color palette**, so videos
render on any client with **no mod required** (the custom "Improved Map Colors"
palette is a Fabric/NeoForge mod and can't run on a Paper server). Only the
audio needs a client mod (Simple Voice Chat). On a modded Fabric/NeoForge setup
you can point `palette-path` at a custom palette instead.

Neither packetevents nor the Simple Voice Chat API is shaded into this jar —
both are provided at runtime by their own installed plugin/mod. Shading
packetevents breaks its registry initialisation on 1.21.8, so it **must** be
installed separately (it is declared as a hard dependency in `plugin.yml`).

## Build

```sh
cd plugin
./prepare-natives.sh   # builds a static linux-x64 mcmm + stages the palette
mvn package
```

`prepare-natives.sh` compiles a portable (statically-linked) `mcmm` and copies
it, with `preset_color_list.json`, into `src/main/resources/` so `mvn package`
bundles them. To also bundle a Windows binary, build `mcmm.exe` (on Windows or
with a mingw toolchain) and drop it at
`src/main/resources/natives/mcmm-windows-x64.exe` before packaging.

The plugin jar is produced at `target/minecraftvideo-plugin-<version>.jar`
(`./build.sh` wraps the whole thing and bumps the version).

## Install

1. Install the [packetevents](https://modrinth.com/plugin/packetevents) plugin
   into `plugins/` (required). For audio, also install Simple Voice Chat.
2. Copy `target/minecraftvideo-plugin-<version>.jar` into `plugins/`.
3. Start the server. On first start the plugin extracts `mcmm` and the palette
   into `plugins/MinecraftVideo/` and generates `config.yml`. Nothing else to
   place by hand (except `ffmpeg` in `PATH` for audio).

## Configure

`plugins/MinecraftVideo/config.yml`:

```yaml
# Path to the native mcmm converter executable. Leave EMPTY to use the binary
# bundled with the plugin (extracted into this folder on first start). Set a
# path to override with your own build.
mcmm-path: ""

# Path to the palette JSON passed to mcmm. Leave EMPTY to use the bundled one.
palette-path: ""

# Default screen size in maps (each map is 128x128 pixels) and default fps.
default-width: 4
default-height: 3
default-fps: 10

# Clickable control bar overlaid on the screen's bottom edge (see Use).
control-bar-enabled: true

# Audio (requires Simple Voice Chat on server + clients).
audio-enabled: true
ffmpeg-path: "ffmpeg"
audio-distance: 48

# Audio channel layout: mono | stereo | surround (see the Audio section).
audio-mode: "mono"

# Download an http(s) source to a local file once, so mcmm, audio, ffprobe and
# subtitles all read the local file instead of each opening its own connection
# to the origin (fixes 5XX rate-limiting on hosts like archive.org; a seek or
# subtitle toggle never re-fetches). Kept while the URL is queued or playing,
# removed afterwards; wiped on server stop. A source over the hard cap is
# refused. Local paths are always played directly.
cache-remote-sources: true
cache-max-size-mb: 2048

# Blocks behind the screen plane (toward the audience) for the surround rears.
surround-rear-distance: 10

# A/V sync (see the Audio section): the first frame is frozen for
# audio-start-delay-ms while the client absorbs the screen appearing, then
# video and audio start together; the audio skips av-sync-delay-ms of content
# to cover the client's buffering. Sound late -> increase, early -> decrease.
av-sync-delay-ms: 200
audio-start-delay-ms: 1000
```

## Use

Permission: `minecraftvideo.use` (default: op).

- `/video option <width> <height> [fps]` — sets and **persists** the screen
  options (saved to `config.yml`), so you configure once and just `/video play`
  afterwards. With no arguments it prints the current options.
- `/video option audio <mono|stereo|surround>` — sets and persists the audio
  channel layout (see the Audio section).
- `/video option avsync <ms>` — sets and persists the A/V sync delay
  (`av-sync-delay-ms`) without editing the config: sound arrives late →
  increase, sound early → decrease. Applies to the next `/video play`.
- `/video option sub <size|height|depth> <value>` — sets and persists the
  subtitle overlay geometry: `size` = text scale, `height` = blocks above the
  screen's bottom edge, `depth` = blocks in front of the screen surface.
  Applies to the next `/video play`.
- `/video play <url-or-path> [w] [h] [fps]` — spawns a virtual screen a few
  blocks in front of you, facing you, and starts playback. Uses the options set
  by `/video option`; the optional `[w] [h] [fps]` override them for this one
  play. The source can be a local video file path or a URL (anything mcmm/ffmpeg
  accepts). One video at a time in this base version.
- `/video seek <+s|-s|[hh:]mm:ss>` — skips forward/backward (`+10`, `-10`, any
  number of seconds) or jumps to an absolute timestamp (`90`, `1:30`,
  `1:02:03`). The screen is kept (the last frame stays up while the decoder
  reconnects at the target position); video and audio restart together at the
  target. Seeking while paused resumes playback.
- `/video queue add <url-or-path>` — queues a video to play after the current
  one (starts immediately if nothing is playing). The screen stays where the
  current video plays. `/video queue [list]`, `queue remove <n>` and
  `queue clear` manage the list.
- `/video skip` — ends the current video and starts the next queued one.
- `/video stop` — stops playback, removes the screen and clears the queue.
- `/video pause` / `/video resume` — freezes/continues the video and its audio.
  While paused, mcmm and the audio ffmpeg block on their pipe/queue (no decoding
  runs ahead), so both resume in sync.
- `/video status` — shows how fast frames are being generated versus the frame
  budget, i.e. how much headroom you have. "4.5x headroom (78% idle)" means the
  pipeline could sustain ~4.5x the current fps; "BEHIND real time" means it
  can't keep up — lower the fps or the screen size.
- `/video subs list` — lists the source's embedded subtitle tracks (index,
  codec, language, title). `/video subs <n>` overlays text track `n` under the
  screen; `/video subs off` hides it. Only **text** tracks (SubRip/ASS/mov_text)
  can be overlaid — bitmap tracks (PGS/VOBSUB/DVB) are listed but refused. The
  selection is per-playback (not persisted); a new `/video play` starts with
  subtitles off. See the Subtitles section.

**Control bar**: unless disabled in the config (`control-bar-enabled`), four
clickable buttons float over the screen's bottom edge, video-player style —
⏪ seek −10 s, ⏯ pause/resume, ⏩ seek +10 s, ⏭ skip to the next queued
video. Left click and right click both work. Everyone sees the buttons, but
only players with the `minecraftvideo.use` permission can use them. Like the
screen, the bar is packet-only (fake text-display glyphs plus invisible
Interaction hitboxes) — nothing exists server-side.

All players online at start time see the screen; players who join during
playback are added automatically and receive the current frame.

## Audio

Minecraft has no raw audio channel in its protocol, so the video's soundtrack
is streamed through **Simple Voice Chat**'s addon API instead:

- The plugin registers as an SVC addon and, when a video starts, spawns its own
  `ffmpeg` to decode the source to 48 kHz PCM (`ffmpeg -i <src> -vn -ac <n>
  -ar 48000 -f s16le -`). ffmpeg reads local files and URLs alike.
- The PCM is streamed into **locational** SVC channels anchored in the world
  around the screen — a fixed "cinema": the speakers never move, and each
  client spatialises them from its own position (falloff = `audio-distance`).
- Only players who have Simple Voice Chat installed and connected hear it.
  Without SVC on the server, videos play silently and everything else works.

Three channel layouts (`audio-mode` / `/video option audio ...`):

| mode | ffmpeg decode | speakers |
|------|---------------|----------|
| `mono` | `-ac 1` | 1 at the screen center |
| `stereo` | `-ac 2` | L/R at the screen's left/right edges |
| `surround` | `-ac 6` (5.1) | front L/R at the edges, center at the screen, a **subwoofer** (LFE +6 dB) at the screen's base, rear L/R `surround-rear-distance` blocks behind the audience |

Any source layout works in any mode. A 5.1+ film (or an Atmos bed — true
object-based Atmos rendering is proprietary; the 5.1 bed is what ffmpeg
exposes) maps its channels directly onto the speakers. A mono/stereo source in
surround mode gets a **real upmix** (ffmpeg's `surround` filter, chosen after
an automatic `ffprobe` of the source): the center is extracted from the stereo
correlation, the rears from the ambience, and the LFE is synthesized by
low-pass — so all six speakers play even for a plain stereo file. (A naive
`-ac 6` would leave center, rears and subwoofer digitally silent.)

**Bass**: four measures keep the low end intact. The Opus encoders are created
in `AUDIO` mode (the default `VOIP` mode is speech-tuned and guts music bass);
the mono/stereo downmix folds a 5.1/7.1 source's LFE channel in at −3 dB
(ffmpeg's default downmix silently drops LFE — needs ffmpeg ≥ 6 for the
`out_chlayout` option); in surround mode the LFE gets its own in-world
subwoofer at the screen's base, boosted +6 dB like real bass management does;
and stereo sources played in surround get a synthesized LFE (low-passed
program material), so the subwoofer is never silent.

All decoded channels ride one queue in lockstep (one master SVC channel drains
it, the others mirror the same frame), so speakers cannot drift apart.

**A/V sync**: the audio path is inherently more laggy than the video path. The
SVC client buffers received audio in a jitter buffer, and that buffer inflates
when packets arrive while the client is busy — exactly what happens when the
screen first appears (a burst of map/entity uploads) — then never drains back
down. So the plugin sends the first video frame immediately and **freezes** it
for `audio-start-delay-ms` (default 1000): the spawn spike passes over a
static picture with no audio in flight, then video pacing and audio start
together — no soundtrack content is lost to the warm-up. The audio only skips
`av-sync-delay-ms` (default 200) of content, covering the client's
steady-state buffering. Tune it: sound late → increase, sound early →
decrease. Because SVC channels are recreated on `/video seek`, a seek also
resets any audio delay the client accumulated mid-play. (Client side, lowering
Simple Voice Chat's `output_buffer_size` reduces the maximum possible lag.)

## Subtitles

Embedded subtitle tracks can be shown as a floating text overlay under the
screen, with no re-encoding of the video (map tiles are 128 px, far too coarse
to burn subtitles into legibly):

- `/video subs list` runs `ffprobe` on the source and lists its subtitle
  streams. `/video subs <n>` selects one; `/video subs off` hides it.
- The selected track is transcoded to SubRip on the fly by a dedicated ffmpeg
  (`ffmpeg -v error [-ss <offset>] -copyts -i <src> -map 0:s:<n> -f srt -`) and
  parsed into timed cues. Cues are shown on **one fake `TEXT_DISPLAY`** over the
  lower picture (above the control bar if present) — packet-only, like
  everything else — and updated in place with metadata packets as they come and
  go.
- Timing follows the **picture**: `-copyts` keeps the original (absolute) cue
  timestamps, so they are matched directly against the video position — pausing
  holds the current line, and the subtitle ffmpeg is restarted on `/video seek`
  (like the audio). No A/V-sync skew is applied to subtitles. Enabling subtitles
  mid-playback seeks the extraction ffmpeg to the **current position** (not the
  segment start), so the active line appears at once instead of after replaying
  every earlier cue.
- Only **text** codecs work (SubRip, ASS/SSA, mov_text): they carry extractable
  text. **Bitmap** tracks (PGS, VOBSUB, DVB) are pre-rendered images with no
  text, so they are listed but refused. Inline markup (`<i>`, `<b>`, ASS
  overrides) is stripped; long lines wrap.
- The selection is per-playback and not persisted — a fresh `/video play`
  starts with subtitles off.

## mcmm `--stream` contract

The plugin spawns:

```
mcmm --stream --palette <palette.json> [--seek <seconds>] <video> <map_w> <map_h> <fps>
```

stdout is pure binary, big-endian. All logs go to stderr. The optional
`--seek` starts decoding at the given offset (mcmm forwards it to its internal
ffmpeg as a fast input `-ss`); the plugin uses it for `/video seek`.

16-byte header:

| bytes | field                                   |
|-------|-----------------------------------------|
| 0-3   | magic `"MCMM"` (0x4D 0x43 0x4D 0x4D)    |
| 4-5   | uint16 version = 1                      |
| 6-7   | uint16 map_w                            |
| 8-9   | uint16 map_h                            |
| 10-11 | uint16 fps                              |
| 12-15 | uint32 reserved = 0                     |

Then per decoded frame: `map_w * map_h` buffers of exactly 16384 bytes each
(128x128 Minecraft map color ids, byte index = `y*128+x`, same layout as the
`colors` array of a map `.dat`), in row-major tile order: tile `i = row*map_w
+ col`, row 0 = TOP of the image.

No per-frame marker (fixed sizes). mcmm flushes stdout after each frame.
EOF on stdout = end of video.

## Implementation notes

- `McmmStream` wraps the subprocess, validates the header and reads frames
  with blocking exact-size reads (a short read is treated as EOF).
  mcmm's stderr is forwarded to the plugin logger on a daemon thread.
- `VirtualScreen` builds a wall of glow item frames (fake entity ids from
  2,000,000,000) holding filled maps (fake map ids from 2,000,000,000, set
  through the `minecraft:map_id` data component). Frames are made invisible so
  only the map surface shows. Starting the fake ids near `Integer.MAX_VALUE`
  keeps them disjoint from the real entity/map ids a server allocates from 0.
- `PlaybackSession` paces frames on a daemon thread at the header fps using
  `System.nanoTime` deadlines. Map/entity packets are sent from that async
  thread — packetevents packet sending is thread-safe.
- Facing support: NORTH / SOUTH / EAST / WEST, derived from the player yaw;
  tile (row 0, col 0) is the top-left corner from the viewer's point of view.
- `VoicechatHook` / `AudioStream` / `AudioPlayback` implement the audio path
  (see the Audio section). A bounded queue decouples ffmpeg I/O from SVC's
  audio thread so the frame supplier never blocks on the network.
- `ControlBar` / `ControlBarListener` implement the clickable buttons: per
  button one TEXT_DISPLAY (the glyph; vertical billboard, full-bright) plus
  one INTERACTION entity (the hitbox), spawned and destroyed with the screen.
  Clicks arrive as serverbound INTERACT_ENTITY packets on the netty thread,
  where they are filtered by fake entity id, cancelled (the ids don't exist
  server-side), collapsed to one action per physical click (a right click
  sends several packets) and debounced (250 ms per player); the action itself
  (permission check, pause/seek/skip, chat feedback) runs in one main-thread
  task.
- `SubtitleStream` / `SrtParser` / `SubtitleTrack` / `SubtitleOverlay` implement
  the subtitle overlay: `ffprobe` lists the tracks (like `AudioStream`), a
  per-segment ffmpeg transcodes the chosen text track to SRT, `SrtParser`
  turns it into timed cues, and the playback loop drives one fake `TEXT_DISPLAY`
  (`SubtitleOverlay`, owned by the screen) from the video position.


### Example Status Output

```log
[02:24:09] [Render thread/INFO]: [System] [CHAT]   Source: ...Subbing%5D%20Code%20Lyoko%20-%2000%20%28softsubbed%29.mp4
[02:24:09] [Render thread/INFO]: [System] [CHAT]   Screen: 4x3 maps @ 10 fps
[02:24:09] [Render thread/INFO]: [System] [CHAT]   Progress: 1086 frames, 110,8s, 9,8 effective fps
[02:24:09] [Render thread/INFO]: [System] [CHAT]   Generation: 3,6 ms/frame (decode 3,2 + send 0,4) vs 100,0 ms budget
[02:24:09] [Render thread/INFO]: [System] [CHAT]   Margin: 27,6x headroom (96% idle) — plenty of room
```

## Roadmap

### Already done

- **Core playback loop**: virtual screen rendering, packet-only delivery, pause/resume, status reporting.
- **Stereo audio**: L/R channels anchored to the screen edges (`/video option audio stereo`).
- **Surround audio**: 5.1 decode mapped onto 6 world-anchored speakers, subwoofer included (`/video option audio surround`). True object-based Atmos is proprietary; its 5.1 bed is used.
- **Video seeking**: `/video seek +10 | -10 | <timestamp>` — relative skips and absolute jumps, keeping the screen up (v0.2.0, needs the bundled mcmm with `--seek`).
- **A/V sync compensation** (v0.3.0): freeze-frame warm-up (`audio-start-delay-ms`) so the SVC client absorbs the screen spawn before any audio flows, then video and audio start together; configurable content skip (`av-sync-delay-ms`) against the client jitter buffer.
- **Real stereo→5.1 upmix** (v0.3.0): sources with fewer than 6 channels get a true upmix in surround mode (synthesized center/rears/LFE) instead of silent speakers.
- **Playlist support** (v0.4.0): `/video queue add|list|remove|clear`,
  auto-advance at EOF, `/video skip`.
- **In-game control bar** (v0.4.0): clickable ⏪ ⏯ ⏩ ⏭ buttons overlaid on
  the screen's bottom edge, built from text displays + Interaction entities
  (packet-only, like the screen itself).
- **Subtitle overlay** (v0.4.0): `/video subs list|<n>|off` overlays an
  embedded text subtitle track (SubRip/ASS/mov_text) on a fake text display
  under the screen, timed to the picture and restarted on seek; bitmap tracks
  (PGS/VOBSUB/DVB) are detected and refused.

### Future ideas

- **Per-player controls**: allow personal volume, mute, or playback preferences.
