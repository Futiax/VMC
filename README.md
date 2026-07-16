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

The plugin jar is produced at `target/minecraftvideo-plugin-0.2.0.jar`.

## Install

1. Install the [packetevents](https://modrinth.com/plugin/packetevents) plugin
   into `plugins/` (required). For audio, also install Simple Voice Chat.
2. Copy `target/minecraftvideo-plugin-0.2.0.jar` into `plugins/`.
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

# Audio (requires Simple Voice Chat on server + clients).
audio-enabled: true
ffmpeg-path: "ffmpeg"
audio-distance: 48

# Audio channel layout: mono | stereo | surround (see the Audio section).
audio-mode: "mono"

# Blocks behind the screen plane (toward the audience) for the surround rears.
surround-rear-distance: 10

# Delay (ms) applied to the first video frame after audio starts, compensating
# the ~0.5 s the SVC client holds in its jitter buffer. Increase if audio lags
# the video, decrease (or 0) if audio leads it.
av-sync-delay-ms: 500
```

## Use

Permission: `minecraftvideo.use` (default: op).

- `/video option <width> <height> [fps]` — sets and **persists** the screen
  options (saved to `config.yml`), so you configure once and just `/video play`
  afterwards. With no arguments it prints the current options.
- `/video option audio <mono|stereo|surround>` — sets and persists the audio
  channel layout (see the Audio section).
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
- `/video stop` — stops playback and removes the screen.
- `/video pause` / `/video resume` — freezes/continues the video and its audio.
  While paused, mcmm and the audio ffmpeg block on their pipe/queue (no decoding
  runs ahead), so both resume in sync.
- `/video status` — shows how fast frames are being generated versus the frame
  budget, i.e. how much headroom you have. "4.5x headroom (78% idle)" means the
  pipeline could sustain ~4.5x the current fps; "BEHIND real time" means it
  can't keep up — lower the fps or the screen size.

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
| `surround` | `-ac 6` (5.1) | front L/R at the edges, center at the screen (LFE folded in at −3 dB), rear L/R `surround-rear-distance` blocks behind the audience |

ffmpeg up/downmixes ANY source layout to the requested one, so a stereo file
plays fine in surround mode (image stays in the front pair) and a 5.1 or
Atmos-bedded film feeds all five speakers. True object-based Atmos rendering is
proprietary; the 5.1 bed is what ffmpeg exposes, and it is what we map.

All decoded channels ride one queue in lockstep (one master SVC channel drains
it, the others mirror the same frame), so speakers cannot drift apart.

**A/V sync**: the audio path is inherently more laggy than the video path — the
SVC client holds ~0.5 s of audio in its jitter buffer, while map packets apply
on the next client tick. The plugin compensates by (a) pre-warming the audio
ffmpeg while mcmm probes the source, (b) waiting for the first audio frame
before starting the SVC timeline, and (c) delaying the first video frame by
`av-sync-delay-ms` (default 500) so the picture lands on the buffered sound.
If your setup still drifts, tune that value.

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
- **Surround audio**: 5.1 decode mapped onto 5 world-anchored speakers (`/video option audio surround`). True object-based Atmos is proprietary; its 5.1 bed is used.
- **Video seeking**: `/video seek +10 | -10 | <timestamp>` — relative skips and absolute jumps, keeping the screen up (v0.2.0, needs the bundled mcmm with `--seek`).
- **A/V sync compensation**: audio pre-warm + configurable `av-sync-delay-ms` against the SVC client jitter buffer.

### Future ideas

- **Playlist support**: queue multiple videos and move to the next item automatically.
- **In-game seek UI**: add a small command or menu flow for quick seeking without typing timestamps.
- **Subtitle overlays**: render captions or subtitles on top of the virtual screen.
- **Per-player controls**: allow personal volume, mute, or playback preferences.
