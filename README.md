# MinecraftVideo (VMC)

Plays videos on virtual in-game map screens on a Paper 1.21.8 server with synchronized, spatialized audio.

The plugin spawns the native converter (`mcmm`) as a subprocess and forwards its decoded frames to players as map packets. **Everything is virtual**: fake entity IDs, fake map IDs, packets only — nothing is written to the world or to disk.

---

## Requirements

- **Server**: Paper 1.21.8 (or compatible) & Java 21
- **Dependencies**: 
  - [**PacketEvents**](https://modrinth.com/plugin/packetevents) plugin (Required, >= 2.11.2)
  - **ffmpeg & ffprobe** installed on the server and added to your system `PATH` (Required for audio & subtitles)
  - [**Simple Voice Chat**](https://modrinth.com/plugin/simple-voice-chat) mod/plugin (Optional, required for audio; must be installed on both the server and clients, >= 2.6.0)

*Note: The native `mcmm` binary and vanilla color palette are bundled inside the jar and extracted automatically on first start. Only a statically-linked `linux-x64` binary is bundled by default.*

---

## Installation & Setup

1. Install the [**PacketEvents**](https://modrinth.com/plugin/packetevents) plugin in your server's `plugins/` folder.
2. (Optional for audio) Install the [**Simple Voice Chat**](https://modrinth.com/plugin/simple-voice-chat) plugin.
3. Download and place the `minecraftvideo-plugin` jar in the `plugins/` directory.
4. Start your server. On the first start, the plugin will extract `mcmm` and the color palette into `plugins/MinecraftVideo/` and generate a `config.yml`.
5. Ensure `ffmpeg` and `ffprobe` are available in your system `PATH`.

---

## Quick Start

Requires the `minecraftvideo.use` permission (default: OP).

- **Start Playback**:
  ```
  /video play <url-or-path>
  ```
  *Spawns a virtual screen in front of you and plays the video (e.g. from a local path or direct URL).*
- **Pause / Resume**:
  ```
  /video pause
  /video resume
  ```
- **Stop Playback**:
  ```
  /video stop
  ```
- **Performance Status**:
  ```
  /video status
  ```

---

## Full Documentation & Wiki

For detailed information on advanced commands, custom configuration, spatial audio, and subtitles, see the **[Wiki & Documentation Guide (WIKI.md)](WIKI.md)**:

- **[Screen size & FPS configuration](WIKI.md#options-configuration-video-option)**
- **[Mono, Stereo & Surround audio modes](WIKI.md#audio-modes--spatialization)**
- **[Subtitle track rendering](WIKI.md#subtitle-tracking-video-subs)**
- **[Playlist & Queue management](WIKI.md#advanced-commands-reference)**
- **[A/V Sync and delay adjustment](WIKI.md#options-configuration-video-option)**
- **[Building & Compiling from source](WIKI.md#building--compiling-from-source)**
