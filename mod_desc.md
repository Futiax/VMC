# MinecraftVideo (VMC)

**MinecraftVideo** is a high-performance Paper plugin that streams videos — from local files, direct links, or **YouTube** — directly onto virtual map screens in-game, complete with **synchronized, spatialized audio**, **clickable playback controls**, and **embedded subtitle support**!

Everything is rendered **completely virtually** — the screen is made of packet-only fake maps and item frames, so **no map items are ever saved to your world**. Remote videos are only cached **temporarily** and wiped when playback ends, so your world save never bloats.

---

## ✨ Features

- 📺 **Virtual Screens**: Renders videos on custom-sized walls of maps using invisible item frames — nothing is placed in the world.
- 🎛️ **Clickable In-Game Controls**: A control bar floats over the bottom of the screen — **rewind 10s, play/pause, forward 10s, and skip** — clickable with no commands needed. Everyone sees it; only players with permission can use it. Can be disabled in the config.
- 🔊 **Advanced Spatial Audio**: Streams the video's soundtrack through **Simple Voice Chat** (optional) in three modes:
  - **Mono**: Single source at the screen's center.
  - **Stereo**: Left/Right channels anchored to the screen edges.
  - **Surround**: 6 virtual speakers (Front L/C/R, a subwoofer at the base, and rear speakers behind the audience) for a true cinema experience — stereo sources are up-mixed so every speaker plays.
- 💬 **Subtitle Overlay**: Detects and displays embedded **text-based** subtitle tracks as a 3D text overlay under the screen — pick a track, reposition and resize it, or turn it off, all in-game.
- ⚡ **No Client Mods Required for Video**: Uses the vanilla Minecraft color palette, so players can watch on a **completely vanilla client** — a client mod (Simple Voice Chat) is only needed to *hear the audio*.
- 🚀 **High Performance**: Native decoding runs in a separate process, packet broadcasting is fully asynchronous, and unchanged parts of the picture aren't re-sent — keeping the main server thread at a solid 20 TPS.
- 📥 **Smart Caching**: Remote sources are downloaded **once** and cached locally, so seeking and toggling subtitles never re-fetch — and it avoids the rate-limiting that plagues concurrent streaming from the same host.
- 🔗 **Stream Anything**: Plays local files, **direct** URLs (MP4/MKV/… links), and **YouTube** (plus 1000+ other sites via yt-dlp) — a page URL is automatically resolved to a playable stream. yt-dlp isn't bundled; the plugin uses one already on the server's `PATH` or downloads it once on the first use (and keeps it updated).
- 📋 **Playlist / Queue System**: Queue multiple videos, list the queue, remove specific items, or skip the current video — the next one starts automatically.

---

## 📋 Requirements

To run this plugin, you will need:

1. A **Linux (x64)** server running **Paper 1.21.8** (or a compatible version) on **Java 21**. *The bundled native decoder ships for linux-x64 only; other platforms must supply their own `mcmm` build.*
2. The [**PacketEvents**](https://modrinth.com/plugin/packetevents) plugin installed on your server (**hard dependency**).
3. **ffmpeg & ffprobe** available in the system `PATH` — **only** required for audio and subtitles. Silent video plays without them.
4. *(Optional, for audio)* [**Simple Voice Chat**](https://modrinth.com/plugin/simple-voice-chat) installed on the server, plus the client mod for players who want to hear the soundtrack.
5. *(Optional, for YouTube-style URLs)* **yt-dlp** — no install needed: the plugin auto-downloads it on first use if it isn't already on the server's `PATH`. Requires the remote-source cache to stay enabled (the default).

---

## 🛠️ How to Use

All commands require the `minecraftvideo.use` permission (default: OP). Once a video is playing, the **on-screen control bar** handles rewind / play-pause / forward / skip with a click — the commands below do the same and more.

### Playback Controls
- `/video play <url-or-path>`
  *Spawns a virtual screen in front of you and starts playing, using your configured options.*
- `/video pause` & `/video resume`
  *Pauses and resumes both video and audio in perfect sync.*
- `/video stop`
  *Stops playback, removes the screen, and clears the queue.*
- `/video seek <+seconds|-seconds|[hh:]mm:ss>`
  *Skip forward/backward (e.g. `/video seek +10` / `-10`) or jump to a timestamp (e.g. `/video seek 1:30`).*
- `/video status`
  *Shows real-time decoding performance, latency, and headroom.*

### Playlist Queue Management
- `/video queue add <url-or-path>`: Add a video to the queue (plays immediately if nothing is running).
- `/video queue list`: View the queued videos.
- `/video queue remove <position>`: Remove the video at a given position.
- `/video queue clear`: Clear the whole queue.
- `/video skip` (or `/video next`): Skip the current video and start the next one.

### Subtitle Management
- `/video subs list`: Probes the source and lists its embedded subtitle tracks.
- `/video subs <track-number>`: Displays the selected text-based track as a 3D overlay.
- `/video subs off`: Hides the subtitle overlay.

### Options & Configuration
Use `/video option` to customize the display, audio, and subtitles (settings persist in `config.yml` and apply to the next `/video play`):
- `/video option <width> <height> [fps]`
  *Sets the default screen size (in maps, e.g. 4×3) and frame rate (up to 20 fps).*
- `/video option audio <mono|stereo|surround>`
  *Switches the spatial audio mode.*
- `/video option avsync <ms>`
  *Adjusts the audio/video sync delay (default: 200 ms) — increase if sound lags, decrease if it leads.*
- `/video option sub <size|height|depth> <value>`
  *Tunes the subtitle text size (scale), height above the screen's bottom edge, or depth (distance in front of the screen). Height and depth accept negatives.*

---

## 🔒 Technical Details & Performance

- **No world bloat**: The screen is fake maps and item frames sent purely as packets — nothing is written to your world save files.
- **Temporary caching only**: Remote videos are cached under `plugins/MinecraftVideo/cache/` while they play and removed afterwards. Fetches are restricted to public hosts (internal/loopback addresses are refused) to keep your server safe.
- **Asynchronous pipeline**: The native decoder runs as a subprocess, and packet sending is thread-safe and off the main thread, so the server tick rate stays at 20 TPS.
- **Bandwidth-aware rendering**: Only the parts of the picture that actually changed are re-sent, saving client bandwidth on static scenes and letterboxing.
- **Auto-sync for joiners**: Players who join during playback automatically receive the screen and see the video from the current frame.
