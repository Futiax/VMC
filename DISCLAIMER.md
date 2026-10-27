# Disclaimer — AI assistance

Most of the Java in this repository was written with AI assistance (Claude Code).
I'd rather say it plainly than have someone guess.

## What this code is

This repository is the **Minecraft interface layer**, not the engine: packets,
fake entity ids and metadata indices, screen geometry, Simple Voice Chat channel
wiring, commands, subtitle overlay. The actual video engine is
[`mcmm`](https://github.com/Futiax/MinecraftVideo), a C converter of mine, itself
the descendant of a Python script I wrote in 2023
([McMovieMaker](https://github.com/Futiax/McMovieMaker)).

I mainly write C, JavaScript, Python and OCaml. Java is not a language I use, and
this layer is glue against the Bukkit and packetevents APIs — so I used a model to
write most of it.

## What is mine

- The project, its design and its history: quantization onto the vanilla map
  palette, the `base × {180, 220, 255, 135}` shade structure, tile indexing, the
  streaming protocol between `mcmm` and the plugin, the move from datapack `.dat`
  files to live packets, the world-anchored "cinema" speaker layout.
- Every architectural decision here, and every debugging cycle: the protocol
  quirks in this codebase (item frame metadata index 9, the 20 fps ceiling imposed
  by the client tick, the A/V sync model, the HTTP 403 on YouTube signed URLs
  without a `Range` header) were found by testing in game, not generated.
- No version is published without a manual in-game test. Every local build
  auto-increments the patch version, and CI tags a release for each push — so the
  gaps in the release list (`0.2.1 → 0.3.1 → 0.4.3 → 0.4.5 → 0.4.8 → 0.5.0`) are
  exactly the builds that failed in game and were never pushed. Roughly a dozen
  died between 0.3.1 and 0.4.3.

## What the model did

Wrote the bulk of the Java to my specification, and helped debug it.

If that disqualifies the project in your eyes, that's a legitimate position. The
engine is in the other repository, and its history goes back to 2023 — judge the
whole thing rather than this layer alone.
