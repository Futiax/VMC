# MinecraftVideo Paper plugin (VMC)

Paper 1.21.8 plugin that plays videos on virtual in-game map screens — fully
packet-based (fake entity ids ≥ 2e9, fake map ids ≥ 2e9 via the
`minecraft:map_id` component on GLOW_ITEM_FRAMEs, nothing written to world or
disk). Video decoding is done by the native converter `mcmm` from the SISTER
REPO `../MinecraftVideo/c version/mcmm.c` (uncommitted work lives on branch
`appmod/java-upgrade-20260715141045` there).

## User / process (Futiax, French)

- Répondre en **français** ; style direct et honnête techniquement.
- License policy (critical to the user): CC BY-NC-SA; no advertising, no
  political appropriation, no commercial use without written authorization, no
  implied endorsement. Never commit ffmpeg binaries/DLLs (size + (L)GPL).
- **Never commit without being asked** (an early commit was rejected once).
- Feedback loop: the user tests in-game and drops notes in `retours_ingame.md`
  and client logs in `test.log` (both untracked, at repo root).
- Test sources: archive.org 5.1 test files (`Splash.mp4`, `surroundTest.mp4`).
- Build with `./build.sh` (auto-bumps PATCH; `minor|major|set X.Y.Z|keep`).
  Current version 0.3.1. `plugin.yml` gets `${project.version}` via Maven
  filtering (ONLY plugin.yml is filtered — filtering other resources would
  corrupt the bundled native binary).

## Architecture (hard-won facts)

- **packetevents is an EXTERNAL plugin** (provided scope, `depend` in
  plugin.yml). Shading it breaks registry init on 1.21.8 (their issue #1440).
- Item-frame entity metadata on 1.21.x: index 8 = Direction, **9 = Item**.
  Writing the item at 8 disconnects the client ("Network Protocol Error").
- Map packets apply once per CLIENT tick → **MAX_FPS = 20** (VideoCommand).
- `mcmm --stream --palette <json> [--seek <s>] <video> <w> <h> <fps>`:
  16-byte big-endian header ("MCMM", u16 ver=1, w, h, fps, u32 reserved),
  then per frame w*h tiles of exactly 16384 bytes, row-major, row 0 = top;
  fflush per frame; EOF = end. `--seek` → `-ss` before `-i` in mcmm's internal
  ffmpeg (fast input seek; validated end-to-end). Static linux-x64 binary is
  built by `./prepare-natives.sh` (source dir override: `MCMM_SRC`), bundled
  in the jar, extracted by `NativeInstaller` whenever the plugin version
  changes (`.installed-version` marker) — the auto version bump guarantees
  fresh natives on every deploy.
- **PlaybackSession = segments**: `run()` loops `playSegment(offset)`;
  `/video seek` sets `pendingSeekMillis` (AtomicLong, -1 = none), closes the
  current McmmStream + stops audio under `lock`, force-resumes; the frame loop
  checks pendingSeek each iteration; an IOException while a seek is pending
  means "segment over", not an error. The VirtualScreen is created on the
  first segment and REUSED across seeks (header dims must match).
- Commands: `play <src> [w] [h] [fps]`, `option <w> <h> [fps]`,
  `option audio <mono|stereo|surround>`, `option avsync <ms>`,
  `seek <+s|-s|[hh:]mm:ss>`, `stop|pause|resume|status`. Options persist in
  config.yml (donc `option avsync` règle le décalage sans toucher au fichier).

## Audio (Simple Voice Chat addon, voicechat-api 2.6.0)

- Registered via `BukkitVoicechatService.registerPlugin`; server api captured
  on `VoicechatServerStartedEvent`. Repo maven.maxhenkel.de.
- `AudioStream`: own ffmpeg, `-f s16le -ar 48000 -ac N [-ss offset]`,
  de-interleaved into `short[channels][960]` (960 samples = 20 ms @ 48 kHz).
- `AudioPlayback`: ONE queue of speaker-mapped frames; **channel 0 is master**
  (dequeues, publishes `current`), other channels mirror it. This exists
  because a per-channel-queue design had a CONFIRMED race: independent
  supplier underrun decisions desync L/R permanently by 20 ms. Do not regress.
- **Opus encoders must be `OpusEncoderMode.AUDIO`** — the default
  `createEncoder()` is VOIP (speech-tuned, kills music bass).
- SVC internals (vérifié dans le source, branche 26.2, 17/07/2026) : sur
  Bukkit/Paper l'encodeur est **Concentus (port Java d'Opus), PAS libopus** ;
  aucun bitrate explicite (défaut ≈ 51 kbps mono), FEC inband + 5 % loss.
  Client : décodage Opus → volume → OpenAL, AUCUN traitement fréquentiel ;
  atténuation AL_LINEAR_DISTANCE (pleine puissance jusqu'à distance/2, puis
  linéaire vers 0 à `audio-distance`). Le denoiser client = micro SEULEMENT.
  Nos canaux (sans catégorie, UUID inconnu du client) tombent sous le volume
  client **"Autre/Other"** de SVC → idée 0.4.0 : `registerVolumeCategory`
  ("Video") + `setCategory` pour un slider dédié par joueur.
- LFE handling: mono/stereo downmix uses
  `-af aresample=out_chlayout=<mono|stereo>:lfe_mix_level=0.707` (ffmpeg's
  default downmix DROPS LFE entirely — proven with an LFE-only test file:
  −91 dB before, signal after; needs ffmpeg ≥ 6). Surround = 6 speakers:
  FL/FR at screen edges, FC center, **LFE = subwoofer at screen bottom-center
  boosted +6 dB (x2, saturating)**, BL/BR `surround-rear-distance` (10) blocks
  behind the audience (`VirtualScreen.getSubAnchor/getRearLeftAnchor/...`).
  Speaker order MUST match decoded 5.1 order: FL FR FC LFE BL BR.

## EN COURS — état exact au moment du handoff

1. **Sync A/V : fix freeze-frame IMPLÉMENTÉ en 0.2.5 — à tester en jeu.**
   (Le retour 0.2.4 : son en avance, début de bande-son manquant — les 1,5 s
   de skip surcompensaient.) Nouveau modèle : frame 0 envoyée immédiatement
   puis FIGÉE `audio-start-delay-ms` (1000) via `freezeFirstFrame()` (pause
   étend le gel, stop/seek l'interrompent, réactif ≤50 ms) ; ensuite
   `pausedAccumNanos` remis à 0 et `playStartNanos`/`deadline`/
   `audioBeginNanos` ancrés sur le même instant post-gel : pacing vidéo et
   audio partent ensemble. L'audio ne saute plus que `av-sync-delay-ms`
   (défaut 200) ; `audioSkipMillis()` = avSync seul. Segments seek (first =
   false) : pas de gel, audio dès isPrimed + rattrapage skipFrames.
   **ATTENTION : la config déjà déployée sur le serveur garde
   `av-sync-delay-ms: 500` (saveDefaultConfig n'écrase pas) — passer la
   valeur à 200 à la main.** Réglage : son en retard → augmenter, en
   avance → diminuer.
2. **Basses "inaudibles" : ÉLUCIDÉ (16/07/2026) — la source de test était
   STÉRÉO.** `surroundTest.mp4` (et `Splash.mp4`) d'archive.org sont des
   DERIVATIVES ré-encodés AAC stéréo ; en mode surround notre `-ac 6` upmixe
   stéréo→5.1 avec **FC/LFE/BL/BR en silence numérique** (prouvé : astats
   −inf dB). Le sub ne pouvait rien jouer. **Opus innocenté** : roundtrip du
   vrai LFE (`surroundTest.ac3`, énergie surtout 60–120 Hz à −25,9 dB RMS)
   → bande 30–120 Hz intacte même à 24 kbps AUDIO (VOIP ≈ −1,5 dB).
   L'API SVC n'expose AUCUN bitrate (`OpusEncoder` = encode/reset/close ;
   javadocs fournies par l'utilisateur dans `voice_chat_api_doc/`, untracked ;
   `VoicechatServerApi.getServerConfig()` → ConfigAccessor en lecture).
   **Bons fichiers de test : les ORIGINAUX `surroundTest.wmv` / `Splash.wmv`
   (wmapro 5.1 + wmv3, ffmpeg les lit).**
   **Vrai upmix IMPLÉMENTÉ en 0.3.0** : `AudioStream.probeChannels()`
   (ffprobe dérivé du chemin ffmpeg, timeout 20 s, cache par session dans
   `PlaybackSession.sourceAudioChannels`, 0=pas sondé/-1=échec→mapping direct)
   ; si source <6 canaux en surround →
   `-af aresample=out_chlayout=stereo:lfe_mix_level=0.707,surround=chl_out=5.1`.
   Validé sur le mp4 stéréo : FC −33 dB, LFE −37,8 dB (avant : −inf) ; BL/BR
   restent ~−74 dB sur CE fichier (annonces mono pannées, zéro ambiance —
   normal, le filtre extrait l'ambiance décorrélée). 5.1 direct inchangé.
3. **Revue adversariale : partielle (3e tentative, encore coupée par la
   limite).** Seule la lentille "timing" a tourné sur le nouveau code 0.2.5 ;
   vérifiée à la main ensuite : 4 findings réels mais MINEURS (≤1 frame /
   ~50–100 ms), NON appliqués pour ne pas polluer le test en jeu :
   (a) au 1er segment la vidéo part 1 frame en avance vs seek (frame 1
   envoyée à playStart au lieu d'occuper le slot de frame 0) ; (b) chaque
   pause/resume décale l'audio ≤1 frame ; (c) skipFrames cap 500 inatteignable
   (queue = 50, retour ignoré) → rattrapage max ~1 s ; (d) getPositionMillis
   +1 frame (seeks relatifs overshoot d'1 intervalle). Lentilles concurrence /
   régressions seek / parsing seek (`VideoCommand.java:248`) et
   `mcmm.c:381/731/795` : toujours jamais passées.
4. **0.4.0 = batch UI (design validé avec l'utilisateur, PAS commencé).**
   L'utilisateur débogue d'abord la 0.3.0 (freeze-frame + vrai upmix) en jeu.
   Ensuite, dans cet ordre suggéré :
   (1) **Playlist** : file de sources dans le plugin (`/video queue add`,
   auto-advance à l'EOF naturel — PAS sur `/video stop`, qui vide la file),
   `/video skip` = stop courant + next. Attention : le start du next passe
   par le main thread ; ancre/anchor à stocker dans la file (l'initiateur
   peut être déco) ; écran recréé par item (réutilisation si dims égales =
   optim plus tard).
   (2) **UI displays + Interaction entities** : barre de contrôle en
   TextDisplay/ItemDisplay (fake ids ≥2e9 comme l'écran) + entités
   `minecraft:interaction` cliquables ; le clic arrive en
   PLAY_CLIENT_INTERACT_ENTITY (netty thread) → filtrer nos ids ;
   pause/resume/seek/skip sont DÉJÀ thread-safe, pas de hop nécessaire
   sauf pour les messages. Boutons : ⏯ ⏪ ⏩ ⏭.
   (3) **Sous-titres** : approche TextDisplay overlay (PAS de burn-in mcmm :
   illisible en 128px/map, et redémarrage vidéo à chaque toggle). ffmpeg
   séparé `-map 0:s:<n> -f srt -` → parseur SRT → cues affichées sous
   l'écran, synchro sur getPositionMillis(), re-seek du flux subs sur
   /video seek. `/video subs list` via ffprobe (réutiliser probeChannels
   comme modèle). Texte seulement (srt/ass/mov_text) ; PGS/VOBSUB = bitmap,
   à détecter et refuser proprement.
5. **Rien n'est commité** : tout le travail plugin (option/seek/surround/
   sync/upmix/build.sh/CLAUDE.md) est en working tree ici ; les changements
   mcmm (--seek, MCMM_STATIC) sont en working tree de ../MinecraftVideo
   (branche de migration + stashes à démêler un jour).

## Historique condensé des décisions

- Palette VANILLA par défaut (Improved Map Colors = mod Fabric/NeoForge,
  impossible sur serveur Paper) ; palette bundlée `vanilla_map_colors.json`.
- Audio via SVC choisi après échec FFT→playsound ("ça rendait mal").
- Cinéma FIXE (enceintes ancrées au monde, pas au joueur) : partagé entre
  spectateurs, zéro tracking, le client spatialise. Le "siège" (monture
  invisible) reste une idée de mise en scène optionnelle, non implémentée.
- Atmos : ffmpeg n'expose que le lit 5.1/7.1 (rendu objet propriétaire) —
  on mappe le lit, c'est assumé et documenté.
- Seek pendant pause = reprise (documenté). Timestamps : `+10/-10/90/1:30/
  1:02:03`.
- Headroom observé en jeu : ~27× à 4x3@10fps, ~11× à 5x3/6x4@20fps ; le vrai
  goulot est la bande passante client (5x3@20fps ≈ 30 Mbit/s par spectateur),
  suspectée de contribuer au lag audio (contention UDP voice vs TCP maps).
