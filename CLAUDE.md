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
  Current version 0.4.8. `plugin.yml` gets `${project.version}` via Maven
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
- **MediaCache = seule écriture disque du plugin** (0.4.4). URLs http(s)
  téléchargées UNE fois dans `<dataFolder>/cache`, puis mcmm + audio + ffprobe
  + subs lisent le fichier LOCAL → supprime les connexions concurrentes qui
  faisaient répondre archive.org en 5XX (diagnostiqué dans `latest.log` : 3-4
  connexions simultanées vers le même mkv → rate-limit), et le seek/toggle subs
  ne re-fetch plus. **Comptage de références** : `reference()` par occurrence
  vivante (chaque item en file + le play direct), `release()` à sa fin ; fichier
  supprimé au dernier release. Invariant : 1 reference + 1 release par
  occurrence. La session `release` TOUJOURS dans le finally de `run()` (après
  `stop()`, pour que les pipelines aient fini de lire) ; `PlaylistManager.add`
  reference, `remove/clear` release, `advance` TRANSFÈRE la ref à la session
  (pas de release) sauf skip mcmm-manquant (release explicite) ; `handlePlay`
  reference + release si trySetActiveSession échoue. Download bloquant hors lock
  (jamais le main thread), abortable via `stopped::get`, **cap DUR**
  `cache-max-size-mb` (2048, refuse au-delà — pas de stream). `playSource`
  (volatile, =source avant résolution) porté par la session ; `source` reste
  l'original pour l'affichage. purge à onEnable (orphelins) + onDisable.
  **Garde SSRF (0.4.5, suite à /security-review)** : `assertPublicHost` résout
  TOUTES les IPs de l'hôte et refuse loopback/link-local (169.254 métadonnées
  cloud)/site-local/CGNAT 100.64/8/ULA fc00::/7/wildcard/multicast ; redirects
  suivis À LA MAIN (`Redirect.NEVER` + boucle, re-valide chaque `Location`,
  cap 5, scheme http/https only) pour qu'un hôte public ne rebondisse pas vers
  l'interne ; l'erreur détaillée ne va qu'au LOG serveur, le joueur reçoit un
  message générique (pas d'oracle de scan de ports interne). Résiduel connu :
  DNS-rebinding entre la validation et la connexion HttpClient (hors modèle de
  menace = joueur avec `minecraftvideo.use`).
- **PlaybackSession = segments**: `run()` loops `playSegment(offset)`;
  `/video seek` sets `pendingSeekMillis` (AtomicLong, -1 = none), closes the
  current McmmStream + stops audio under `lock`, force-resumes; the frame loop
  checks pendingSeek each iteration; an IOException while a seek is pending
  means "segment over", not an error. The VirtualScreen is created on the
  first segment and REUSED across seeks (header dims must match).
- Commands: `play <src> [w] [h] [fps]`, `option <w> <h> [fps]`,
  `option audio <mono|stereo|surround>`, `option avsync <ms>`,
  `option sub <size|height|depth> <val>`, `seek <+s|-s|[hh:]mm:ss>`,
  `stop|pause|resume|status`. Options persist in config.yml. `option sub` règle
  la géométrie de l'overlay sous-titres (`subtitle-size/-height/-depth`, lues
  au build de l'écran → prochain play ; `SubtitleSettings` porté par la session
  → VirtualScreen → SubtitleOverlay ; depth = blocs DEVANT la surface 0.5).

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
   **FIX 0.4.2 (décalage au seek)** : au play, `freezeFirstFrame()` prime
   l'audio AVANT d'ancrer `playStartNanos` → audio/vidéo synchrones. Au seek
   il n'y avait PAS d'attente : la vidéo partait pendant que l'audio bufferisait
   encore, et le rattrapage `skipFrames` (non-bloquant, plafonné par la file
   réelle) ne récupérait pas toujours → audio en RETARD. Nouveau
   `awaitAudioPrimed(ap)` appelé avant l'ancrage pour TOUS les segments (no-op
   au play, déjà primed ; attend le priming au seek), borné par
   `audio-start-delay-ms` et relâché tôt si `AudioPlayback.hasReachedEof()`
   (source muette → pas de stall). Cause résiduelle possible si le décalage
   persiste EN AVANCE (pas en retard) : le fast-seek de mcmm (`-ss` avant `-i`)
   cale sur le keyframe vidéo <P alors que l'audio ffmpeg seek plus finement →
   à creuser côté mcmm.c (repo frère), PAS dans le plugin.
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
4. **0.4.0 = batch UI.**
   (1) **Playlist : IMPLÉMENTÉ en 0.4.0 (17/07, à tester en jeu).**
   `PlaylistManager` (main-thread only, `scheduleAdvance()` = seul point
   cross-thread, déclenché par `clearSession`) ; `/video queue
   add|list|remove <n>|clear`, `/video skip` (alias next) ; `stop` vide la
   file AVANT de stopper ; item en file = ancre de la session active au
   moment du add (l'écran ne bouge pas entre épisodes), options/audio lus au
   START de l'item ; `queue add` à vide = lecture immédiate ; nouveau ctor
   PlaybackSession(UUID, name, anchor) + getAnchor() ;
   `plugin.buildAudioSettings()` factorisé.
   (2) **Barre de contrôle : IMPLÉMENTÉE en 0.4.0 (17/07, à tester en jeu).**
   `ControlBar` (possédée par VirtualScreen ; `control-bar-enabled`, défaut
   true, lu dans le ctor de PlaybackSession = main thread) : 4 boutons
   ⏪ ⏯ ⏩ ⏭ = TextDisplay (billboard VERTICAL : droit, pivote vers chaque
   viewer — pas de convention de yaw FIXED à se tromper ; scale 2,
   full-bright, fond par défaut) + INTERACTION 0.55×0.55 au même point
   (les deux types s'ancrent au bas). GÉOMÉTRIE : PAS sous le bord bas
   (= enterré, l'écran pose au niveau des pieds !) mais OVERLAY bas d'image :
   baseline = bord bas +0.05, 0.6 devant le plan, espacement 0.9. Indices
   metadata 1.21.x VÉRIFIÉS via EntityLib (Display 12=scale 15=billboard
   16=brightness ; TextDisplay 23=text ADV_COMPONENT ; Interaction 8=w 9=h
   10=responsive, mis true = swing feedback). Adventure : le jar packetevents
   le bundle NON relocalisé + classloading Paper parent-first → même classe
   Component partout, OK. `ControlBarListener` (unregister en onDisable AVANT
   le stop) : côté netty zéro Bukkit — fast path id < VirtualScreen
   .FAKE_ID_BASE, setCancelled pour TOUT id >= FAKE_ID_BASE AVANT le lookup de
   session (sinon un clic arrivé 1 tick après un swap/stop de session — id
   fake mais buttonAt=null → fuite au serveur vanilla ; fixé), filtre
   INTERACT_AT + INTERACT off-hand → 1 action/clic, debounce 250 ms par UUID
   packetevents — puis UN runTask main = permission minecraftvideo.use
   (silencieux sinon) + action + message. skip = session.stop() seul
   (clearSession → scheduleAdvance). Ids du bar inclus dans le destroy de
   l'écran ; late joiners via addViewer → spawnFor. À VÉRIFIER EN JEU :
   rendu des glyphes ⏪⏯⏩⏭ (Unifont), portée de clic (bar à ~3.4 blocs de
   l'ancre, reach entité = 3 → avancer d'un pas), lisibilité scale 2,
   recouvrement du bas de l'image acceptable.
   (3) **Sous-titres : IMPLÉMENTÉ en 0.4.0 (17/07, à tester en jeu).**
   Approche TextDisplay overlay (PAS de burn-in mcmm : illisible en 128px/map,
   et redémarrage vidéo à chaque toggle). Nouvelles classes :
   `SubtitleTrack` (record : index s:n, codec, langue, titre ; `textBased()`
   refuse hdmv_pgs_subtitle/dvd_subtitle/dvb_subtitle/dvbsub/pgssub/xsub),
   `SrtParser` (incrémental, nourri ligne par ligne : BOM, CRLF, index optionnel,
   timecodes `hh:mm:ss,mmm`/`.mmm`, multi-ligne, strip `<i>/<b>/<font>` +
   accolades ASS `{\anX}`), `SubtitleStream` (ffmpeg dédié
   `-v error [-ss offset] -copyts -i src -map 0:s:<n> -f srt -`, thread reader
   daemon → liste de cues triée ; `cueAtVideoMillis(pos)` = recherche binaire,
   comparaison DIRECTE — **`-copyts` garde les timecodes ABSOLUS** (validé
   ffmpeg 8 : sans lui, le rebasing subs est faux — ne décale pas de la valeur
   du seek, ne drope pas les cues antérieures ; `-ss` = simple hint de vitesse,
   peut laisser fuir des cues pré-offset, inactives donc jamais affichées) ;
   stderr drainé sur SON PROPRE thread daemon (sinon deadlock pipe) ;
   `probeTracks()` = ffprobe `-select_streams s -show_entries
   stream=codec_name:stream_tags=language,title -of csv=p=0`, dérivation
   ffprobe-depuis-ffmpeg comme AudioStream, timeout 20 s), `SubtitleOverlay`
   (UNE fake TEXT_DISPLAY possédée par VirtualScreen, billboard VERTICAL,
   full-bright, scale 1.4, **index 24 = line width INT = 320** [SEUL index au-delà
   de ce que ControlBar exerce → à confirmer en jeu], baseline bottomEdge +0.7,
   +0.8 de plus si control bar pour dégager les boutons ; `setText` idempotent
   par cue = pas de spam metadata). Câblage PlaybackSession : `subtitleTrackIndex`
   volatile (-1=off), `subtitles` (SubtitleStream) gardé par `lock` PAR SEGMENT
   (fermé/relancé sur seek/stop comme l'audio), `subtitleTracks` caché comme
   sourceAudioChannels. La boucle de frames relit `currentSubtitles()` chaque
   frame (toggle mid-segment pris en compte) et pousse `cueAtVideoMillis
   (getPositionMillis())` → sous-titres synchro PICTURE (pas d'avSync ; pause =
   texte figé car boucle parkée ; timecodes absolus donc comparaison directe).
   La boucle est LE SEUL writer de l'overlay (branche null = clear chaque frame),
   ce qui ferme la race "cue figée après subs off". `finally` du
   segment + entre-segments : `clearSubtitle()` pour ne pas figer l'ancienne cue
   sur un seek. `/video subs list|<n>|off` (+ alias subtitles/none) : ffprobe
   BLOQUANT → `runAsyncThenSync(session, ...)` (async ffprobe, retour main pour
   action+chat, garde `getActiveSession()==session` pour ne pas appliquer un
   probe de l'ancienne vidéo à la nouvelle). Tab-complete subs = list|off SEULEMENT
   (les numéros viendraient d'un ffprobe bloquant, exclu du main thread).
   Id de l'overlay inclus dans le destroy de l'écran ; spawn (caché) à chaque
   viewer/late joiner. À VÉRIFIER EN JEU : index 24 line width accepté par le
   client 1.21.8 (sinon paquet metadata rejeté → retirer l'index, défaut wrap
   200), lisibilité scale 1.4, position au-dessus de la barre, rendu glyphes
   accentués, synchro cue vs image, refus propre des pistes bitmap.
   **Revue adversariale sous-titres (17/07) — 6 findings CONFIRMÉS corrigés :**
   (a) enable mid-segment relançait le flux depuis le DÉBUT du segment
   (`startSubtitleSegment(segmentOffsetMillis)`) → cue active en retard le temps
   que ffmpeg rejoue toutes les cues ; passé à `getPositionMillis()` (l'`-ss`
   atterrit près de "maintenant", timecodes absolus donc lookup inchangé).
   (b) lookup 1 frame en avance : `framesSent++` avant le reconcile → on
   comparait la frame affichée à la position de la frame SUIVANTE ; corrigé en
   `getPositionMillis() - 1000/fps` (instant réel de la frame envoyée).
   (c) back-scan overlap capé à 5 cues dans `cueAtVideoMillis` → une longue cue
   bannière pouvait être dropée sous >5 cues courtes qui la chevauchent ; cap
   retiré (scan complet `best..0`). (d) `probeTracks` lisait stdout APRÈS
   `waitFor` avec `redirectErrorStream(true)` → deadlock pipe >64 KB (source
   corrompue/multi-pistes) jusqu'au timeout 20 s ; drain sur thread daemon
   DÉMARRÉ AVANT `waitFor`. (e) `probeTracks` renvoie désormais **`null` en cas
   d'ÉCHEC** (ffprobe absent/timeout/IO) vs liste vide = "sondé, aucune piste" ;
   `getSubtitleTracks` ne cache QUE les probes réussis (échec → `List.of()` au
   caller, cache non figé → re-probe possible). (f) `SrtParser` : une ligne de
   TEXTE contenant un timecode (`hh:mm:ss,mmm --> ...`) était prise pour un
   nouvel en-tête (clear du texte en cours) ; le match timecode n'est tenté que
   si aucune cue n'accumule (`pendingStart < 0`), sinon la ligne = texte.
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
