# MinecraftVideo (VMC) Wiki & Documentation

Welcome to the full documentation for **MinecraftVideo (VMC)**. This wiki covers advanced commands, spatial audio configurations, subtitle track rendering, technical architecture, and custom compilation.

---

## Table of Contents

1. [Advanced Commands Reference](#advanced-commands-reference)
2. [Settings (/vmc)](#settings-vmc)
3. [Subtitle Tracking (video subs)](#subtitle-tracking-video-subs)
4. [Audio Modes & Spatialization](#audio-modes--spatialization)
5. [Technical Architecture & Performance](#technical-architecture--performance)
6. [Building & Compiling From Source](#building--compiling-from-source)

---

## Advanced Commands Reference

All commands require the `minecraftvideo.use` permission (default: OP).

| Command | Description |
|---------|-------------|
| `/video play <url-or-path>` | Starts playback of the source. Uses configured default options. |
| `/video pause` | Freezes the video frame-loop and pauses the audio stream in sync. |
| `/video resume` | Continues playback from the paused state. |
| `/video stop` | Stops playback, completely removes the virtual screen, and clears the queue. |
| `/video seek <+s\|-s\|[hh:]mm:ss>` | Jumps relative (e.g., `+10`, `-30`) or absolute (e.g., `1:30`) in the video. |
| `/video skip` / `/video next` | Skips the current video and advances to the next queued item. |
| `/video status` | Displays decoding latency, effective FPS, and system headroom metrics. |

### Playlist & Queue Commands
- `/video queue add <url-or-path>`: Appends a media source to the queue. Starts playback immediately if idle.
- `/video queue list`: Lists all queued items with their order and short names.
- `/video queue remove <position>`: Removes the item at the specified position.
- `/video queue clear`: Clears all pending videos from the queue.

---

## Settings (/vmc)

The `/vmc` command shows (with no argument) or sets the plugin's global settings on the fly. They are persisted to the server's `config.yml` and apply to the next `/video play`, `/photo` or `/stream`.

### Screen Size
```
/vmc size <width> <height>
```
- **`<width>` and `<height>`**: Dimension in Minecraft maps (1 to 16). Each map is 128x128 pixels. Ignored by `/vmc render data`, whose size is set by the wall.

### Frame Rate
```
/vmc fps <n>
```
- **`<n>`**: Frame rate (1 to 20 FPS). Default is 10. Bandwidth and server load follow it.

### Audio Configuration
```
/vmc audio <mono|stereo|surround>
```
Sets the spatial audio rendering mode. (See the [Audio Modes](#audio-modes--spatialization) section below).

### A/V Synchronization
```
/vmc avsync <milliseconds>
```
- **`<milliseconds>`**: Sync delay compensation (default: `200` ms). If audio is arriving late compared to the video, increase this value. If the audio is too early, decrease it.

### Subtitle Overlay Geometry
```
/vmc sub <size|height|depth> <value>
```
Adjusts the 3D text overlay placement for subtitles:
- **`size <scale>`**: Text size scale (default: `1.0`, range: `0.1` to `20.0`).
- **`height <value>`**: Blocks relative to the bottom edge of the screen (default: `0.45`, range: `-20.0` to `20.0`).
- **`depth <value>`**: Distance in blocks in front of the screen (default: `0.05`, range: `-10.0` to `10.0`).

### Render Mode
```
/vmc render <normal|dither|strict|data>
```
How the picture becomes map colours: `normal` (scaled, nearest palette colour), `dither` (smoother gradients, more bandwidth), `strict` (pixel-exact whole-number scale, for pixel art and QR codes), `data` (experimental: a wall of colour symbols that only the VMC resource pack decodes, see below).

### Data Wall
```
/vmc data <wan|lan>
```
The wall used by `/vmc render data`:
- **`wan`** (default): 4x2 maps, an 848x464 picture, up to ~9 Mbit/s per viewer at 10 fps. Readable from about 1.4 to 3.2 blocks away on a 1080p screen (FOV 70).
- **`lan`**: 5x3 maps, a 1232x720 picture, up to ~19 Mbit/s per viewer at 10 fps, and twice the encoding work for the server. The whole wall must be on screen and readable: on a 1080p screen that means standing about 2.1 to 3.1 blocks away (FOV 70).

---

## Subtitle Tracking (video subs)

VMC supports rendering text-based subtitles embedded inside your video files. Subtitles are displayed as a 3D text display entity located directly in front of the virtual screen.

1. **Check Available Tracks**:
   Run `/video subs list` to probe the media file using `ffprobe`. It will print all available embedded text subtitle tracks.
2. **Select and Load a Track**:
   Run `/video subs <track-number>` (e.g., `/video subs 0`) to load and display that subtitle track.
3. **Disable Subtitles**:
   Run `/video subs off` or `/video subs none` to turn them off.

*Note: Only text-based formats (like SRT, ASS, WebVTT) are supported. Bitmap subtitle tracks (such as PGS or DVD subtitles) cannot be rendered.*

---

## Audio Modes & Spatialization

Minecraft does not support raw streaming audio channels by default. VMC achieves synced sound by streaming decoded PCM audio (1, 2 or 6 channels, depending on the audio mode) through **Simple Voice Chat**'s addon API. 

Depending on your configuration, VMC can spatialize the audio in three ways:

1. **Mono**: 
   The entire soundtrack is downmixed and anchored as a single locational source at the very center of the virtual screen.
2. **Stereo**: 
   Creates two separate virtual audio sources anchored at the left and right edges of the screen, mapping the left and right audio channels accordingly.
3. **Surround (5.1-like)**:
   Simulates a 6-speaker cinema system layout:
   - **Front Left / Center / Front Right**: Anchored at the screen plane.
   - **Subwoofer**: Placed at the base of the screen center.
   - **Rear Left / Rear Right**: Positioned behind the viewer/audience area.

---

## Technical Architecture & Performance

VMC is built for speed and low server overhead:

```mermaid
graph TD
    A[Minecraft Server] -- starts /video play --> B(PlaybackSession)
    B -- spawns processes --> C[mcmm decoder subprocess]
    B -- spawns processes --> D[ffmpeg audio decoder]
    C -- raw map color IDs stdout --> B
    D -- PCM stdout: 1, 2 or 6 channels --> E[Simple Voice Chat API]
    B -- virtual map packets async --> F[Players in range]
    E -- locational audio voice streams --> F
```

- **Subprocess Offloading**: The heavy decoding tasks of converting raw video to Minecraft map color palettes are handled by the external native binary `mcmm` and `ffmpeg` as subprocesses, keeping JVM garbage collection and CPU cycles clear.
- **Map Packet Virtualization**: No map item entities or world data are stored. Frames are sent directly to the client network queue using **PacketEvents** custom packet injection.
- **Asynchronous Broadcasting**: Packets are built and transmitted on a separate daemon thread to ensure the server's main tick loop is completely unaffected.
- **Dynamic Catch-up**: Late-joining players or players entering the tracking range are dynamically added to the packet broadcast list, instantly receiving the current screen state.

### Detailed Video Pipeline

The path of one video, from the command to the player's screen and speakers, in three diagrams that follow each other. Sizes written `a / b` are for the `wan` and `lan` data walls.

**From the command to the decoders.** Each segment runs one video, one audio and one optional subtitle process; a seek ends the segment and starts a new one.

```mermaid
flowchart TD
    CMD["/video play, /photo, /stream"] --> KIND{"Source type?"}
    KIND -- "local path" --> FILE["Local file"]
    KIND -- "direct media URL" --> HTTP["MediaCache: checks the host is public,<br/>downloads it once"] --> FILE
    KIND -- "page URL (YouTube...)" --> YT["yt-dlp downloads it<br/>into the cache"] --> FILE
    KIND -- "live (/stream)" --> LIVE["LiveStream: ffmpeg records<br/>finished slices"] --> FILE
    FILE -- "/photo" --> STILL["StillClip:<br/>one-second clip"] --> SEG
    FILE --> SEG["PlaybackSession starts one segment at the current offset"]
    SEEK["/video seek"] -. "new segment" .-> SEG

    SEG --> VID["Video: mcmm<br/>(next diagram)"]

    SEG --> AFF["ffmpeg: 48 kHz PCM, 1, 2 or 6 channels,<br/>starts av-sync-delay-ms ahead of the picture"]
    AFF --> ASTR["AudioStream: 20 ms frames,<br/>960 samples per channel"]
    ASTR --> APLAY["AudioPlayback: one queue, channel 0 is<br/>the master, the others mirror it"]
    APLAY --> SVC["Simple Voice Chat: Opus AUDIO encoder,<br/>one locational channel per speaker"]
    SVC --> SPEAK["Player's speakers"]

    SEG -- "if a track is selected" --> SFF["SubtitleStream: ffmpeg outputs<br/>SRT with absolute timestamps"]
    SFF --> CUE["Cue lookup"]
    CUE --> OVER["SubtitleOverlay: fake text display<br/>in front of the screen"]

    VID -. "video and audio start<br/>at the same instant" .-> APLAY
    VID -. "position of the<br/>frame just sent" .-> CUE
```

**Video encoding and sending.** mcmm turns each frame into tiles of map colour ids, then the plugin sends only what changed. In `data` mode the tiles hold a compressed picture instead of the picture itself.

```mermaid
flowchart TD
    subgraph ENC["mcmm: native process"]
        FF["Internal ffmpeg: seek, fps filter, scale and pad<br/>RGB frames + timestamps"] --> MODE{"Render mode?"}
        MODE -- "normal" --> LUT["Nearest palette colour<br/>(precomputed LUT)"]
        MODE -- "dither" --> DITH["Palette colour +<br/>Floyd-Steinberg"]
        MODE -- "strict" --> STRICT["Whole-number scale in C,<br/>then palette colour"]
        MODE -- "data" --> DCT["YCbCr 4:2:0, 16x16 macroblocks, 8x8 DCT<br/>picture: 848x464 / 1232x720"]
        DCT --> Q["Finest global q that fits the wall<br/>q = 0 first, then bisection"]
        Q --> VLC["Quantize, zigzag, RLE, JPEG Huffman<br/>macroblocks capped at 2047 bits"]
        VLC --> IDX["q + row starts + macroblock lengths + blocks,<br/>zeros fill the remaining capacity"]
        IDX --> SYM["31-bit words to 4 base-244 symbols,<br/>one map colour id per module"]
        SYM --> WALL["Wall: data + finder and alignment markers<br/>4x2 / 5x3 maps"]
        LUT --> OUT["stdout: timestamp + 16384-byte tiles"]
        DITH --> OUT
        STRICT --> OUT
        WALL --> OUT
    end

    subgraph SEND["Plugin: playback thread"]
        OUT --> PACE["PlaybackSession paces the frames: frame 0 held<br/>audio-start-delay-ms, waits for audio, then one frame per 1/fps"]
        PACE --> DIFF["VirtualScreen.sendFrame:<br/>compares each tile with the previous frame"]
        VIEW["Viewers: same world, within viewer-range-blocks,<br/>checked every second, late joiners get the whole frame"] --> DIFF
        DIFF --> CAN{"Viewer's socket<br/>writable?"}
        CAN -- "no" --> DEBT["Remember the changed<br/>tiles (missed)"]
        DEBT -. "next frame" .-> CAN
        CAN -- "yes" --> BUNDLE["One packet bundle:<br/>changed + missed tiles"]
        BUNDLE --> PE["PacketEvents: map packets on fake map ids,<br/>zlib-compressed by the server"]
        VIEW -- "data mode" --> FX["PostEffects: /posteffect add<br/>vmc_data / vmc_data_lan"]
    end

    PE --> NEXT["Minecraft client (next diagram)"]
    FX --> NEXT
```

**Display on the client.** The map colours reach the framebuffer exactly. Without a post effect, they are the picture. With `vmc_data`, the GPU finds the wall on screen, reads it and decodes the picture again, on every rendered frame.

```mermaid
flowchart TD
    TEX["128x128 map textures on fake glow item frames<br/>VMC pack text.fsh without fog keeps the colours exact"] --> FB["Game framebuffer"]
    FB --> POST{"Post effect?"}
    POST -- "none: normal, dither, strict" --> SCREEN["Player's screen"]
    POST -- "vmc_data / vmc_data_lan" --> SEED

    subgraph DEC["Data decoder (GPU passes)"]
        SEED["seed: 1:1:3:1:1 finder patterns"] --> TILE["tile: groups candidates on a 32x32 grid of the screen"]
        TILE --> FIT["fit: the 3 corners of the wall"]
        FIT --> PERSP["persp: perspective from the corners"]
        PERSP --> ALIGN["align, pick: alignment markers refine the homography"]
        ALIGN --> CELLS["extract: reads each module, exact colour by binary search<br/>cells: 430x238 / 622x366"]
        CELLS --> WORDS["words: symbols back to 31-bit words"]
        WORDS --> MB["mbstart: start of each macroblock, from the index"]
        MB --> BLOCKS["blocks: start of the AC coefficients + DC"]
        BLOCKS --> COEFS["coefs: Huffman + RLE decoding"]
        COEFS --> IDCT["rows, idct: fixed-point inverse DCT, YCbCr to RGB<br/>img: 848x464 / 1232x720"]
        IDCT --> DRAW["draw: picture fills the screen (bilinear)"]
    end
    DRAW --> SCREEN
```

---

## Building & Compiling From Source

If you want to compile VMC yourself or build for a platform other than Linux x64:

### 1. Compile the native converter (`mcmm`)
The native converter source code is in `c version/` of the separate [MinecraftVideo](https://github.com/Futiax/MinecraftVideo) repository. Clone it next to this repository, or set `MCMM_SRC` to that directory, then run the helper script:
```sh
./prepare-natives.sh
```
This script compiles a portable, statically-linked `mcmm` executable with CMake and copies it to `src/main/resources/natives/mcmm-linux-x64`. The repository already contains a Linux x64 binary, so this step is only needed after changing `mcmm` or for another platform.

### 2. Package the Plugin Jar
Build the resource pack and the plugin without changing the version number:
```sh
./build.sh keep
```
The compiled jar will be generated at `target/minecraftvideo-plugin-<version>.jar`. Without `keep`, `build.sh` increases the patch version first.
