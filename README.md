# MCDATA

Universal map-data channel for Minecraft (Paper 26.3+): the server pushes arbitrary data to
clients through **map packets only** — nothing is written to the world — and the resource pack's
**shaders read it back** from a fixed corner of the screen.

This plugin is the shared infrastructure. On top of it, **POCs** plug in their own payload and
post-effect:

| POC | Command | What it does |
|-----|---------|--------------|
| `probe` | `/mcdata probe on` | Channel test: counter + camera angles displayed bottom-left |
| `video` | `/mcdata video play <url> [normal\|data] [wan\|lan]` | Virtual screen. **data mode** places 3 light cushions in front of the screen at the right distance so players know where to stand |

## How it works

```
server ──map packets──▶ client ──core shader (text.vsh/fsh)──▶ beacon in bottom-left corner
                                                            ──post-effect shader──▶ reads pixels
```

1. **Beacon** — 4 invisible glow item frames around the player's eyes, each holding the same
   128×128 map. The core shader `text.vsh` recognizes the beacon by its 4-texel signature and
   moves it to the bottom-left of the screen; `text.fsh` copies it pixel-perfect + the
   `ModelViewMat` (camera rotation).
2. **Encoding** — each texel carries one nibble (4 bits) from 16 fixed vanilla map colors.
   Layout: texels 0-3 = signature, 4-27 = header (counter, yaw, pitch), 28+ = payload.
3. **Post-effect** — pushed per-player via vanilla `/posteffect` (26.3+). The shader reads the
   beacon pixels at fixed positions and renders whatever the POC wants.

## Requirements

- **Server**: Paper 26.3 (or compatible) & Java 21
- [**PacketEvents**](https://modrinth.com/plugin/packetevents) plugin (>= 2.11.2)
- Players must accept the resource pack (self-hosted by default on `resource-pack-port`)

## Commands

```
/mcdata list                                              — list registered POCs
/mcdata <poc> on|off [player]                             — start/stop a POC
/mcdata video play <url> [normal|data] [wan|lan] [player] — play a video (decoded live by mcmm)
/mcdata video stop [player]                               — stop the video
/mcdata stop [player]                                     — stop all POCs for a player
```

The `video` POC spawns the native `mcmm` binary (bundled in the jar, auto-extracted) as a
subprocess, reads its stream frames and pushes them to the virtual screen. In `data` mode it
also places **3 light cushions** in front of the screen at the optimal reading distance.

## Adding a POC

Implement `ovh.futiax.mcdata.poc.Poc`, register it in `McData.onEnable()`, and add a post-effect
JSON + fragment shader in `pack/`. The beacon channel is already running — just feed it payload.

## Build

```bash
./build.sh        # bump version, build pack.zip, compile with Maven
make server       # copy the jar into server/plugins/
```

Licensed under the GPL v3.
