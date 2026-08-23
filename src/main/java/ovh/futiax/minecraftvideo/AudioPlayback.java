package ovh.futiax.minecraftvideo;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import org.bukkit.World;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

// Envoie la piste audio d'une video dans un ou plusieurs canaux locationnels Simple Voice
// Chat ancres dans le monde, pour que les joueurs autour l'entendent spatialisee.
//
// Placement des enceintes (cf AudioMode) : mono = 1 au centre de l'ecran ; stereo = 2 aux
// bords gauche/droite ; surround = 5 (avant G/D aux bords, centre au milieu avec le LFE
// replie dedans, arriere G/D derriere le public). Image de cinema FIXE : c'est le head
// tracking du client qui fait bouger le son quand le spectateur se deplace, et un canal
// locationnel est diffuse a tous les joueurs proches, donc N canaux couvrent toute la salle,
// pas N par spectateur.
//
// ALIGNEMENT DES CANAUX. Chaque AudioPlayer SVC tire sur son propre thread toutes les 20 ms,
// donc les suppliers ne sont PAS en phase. Avec une file par canal, leurs decisions d'underrun
// independantes pouvaient faire diverger les canaux de frames entieres sans jamais se
// resynchroniser (race sur une frame qui arrive dans une file vide : le poll de l'un l'attrape,
// celui de l'autre vient de timeouter en silence). A la place, UNE file de frames deja mappees
// aux enceintes ; le canal 0 est le "master", il depile la frame suivante et la publie dans
// current, les autres canaux la recopient. Du coup tous les canaux sortent le meme index de
// frame a chaque tick, et underrun/EOF les touchent ensemble. Il ne reste que le dephasage
// sous-frame (<20 ms) entre les threads SVC, qui lui ne peut pas s'accumuler.
//
// Cycle de vie : l'appelant cree l'AudioStream lui-meme (eventuellement bien avant que l'ecran
// existe, pour pre-chauffer la connexion reseau de ffmpeg et l'init du codec), puis appelle
// create(), awaitPrimed() et begin(). A partir de create() c'est cette classe qui possede le
// stream et le ferme dans stop(). Si create echoue, l'appelant garde la propriete.
//
// Les joueurs sans Simple Voice Chat installe/connecte n'entendent simplement rien.
public final class AudioPlayback {

    private static final int QUEUE_CAPACITY = 50;   // ~1 s de buffer (50 * 20 ms)
    private static final short[] SILENCE = new short[AudioStream.FRAME_SAMPLES];

    // Boost du caisson : les mixages cinema attendent que le canal LFE soit joue ~+10 dB plus
    // fort (bass management). +6 dB (x2, sature) est notre compromis pour ne pas clipper le s16.
    private static final int LFE_BOOST = 2;

    // Patience du reader avant de declarer l'audio fini. Un trou (la file ne tient que ~1 s)
    // se rattrape ; un EOF non : le thread de lecture sort et plus aucun relais ne sera lu.
    private static final long HANDOFF_WAIT_SECONDS = 20;

    private volatile AudioStream stream;    // remplace a chaque tranche en direct
    private final AudioPlayer[] players;
    private final Logger logger;

    // Direct : l'EOF du flux courant n'est PAS la fin de l'audio, juste la fin d'une tranche.
    private volatile boolean continuous;
    // Flux de relais deposes par la session, consommes par le thread de lecture.
    private final BlockingQueue<AudioStream> handoff = new ArrayBlockingQueue<>(2);

    // frames entieres deja mappees aux enceintes : short[speakers][FRAME_SAMPLES]
    private final BlockingQueue<short[][]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile short[][] current;     // derniere frame depilee par le master, recopiee par les autres

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean begun = new AtomicBoolean(false);
    private volatile boolean eof = false;   // le reader a atteint la fin de la sortie ffmpeg
    private volatile boolean ended = false; // le master a rendu null, les esclaves doivent finir aussi
    private volatile boolean paused = false;
    private final Thread reader;

    private AudioPlayback(VoicechatServerApi api, ServerLevel level, Position[] positions,
                          int distance, AudioStream stream, Logger logger) {
        this.stream = stream;
        this.logger = logger;
        int speakers = positions.length;

        this.players = new AudioPlayer[speakers];
        try {
            for (int ch = 0; ch < speakers; ch++) {
                LocationalAudioChannel channel =
                        api.createLocationalAudioChannel(UUID.randomUUID(), level, positions[ch]);
                if (channel == null) {
                    throw new IllegalStateException("SVC returned no locational audio channel");
                }
                channel.setDistance(distance);
                final int idx = ch;
                // Mode AUDIO, PAS le VOIP par defaut : le VOIP est cale pour la parole
                // (passe-haut, modele de voix SILK) et bousille les basses de la musique.
                players[ch] = api.createAudioPlayer(channel,
                        api.createEncoder(OpusEncoderMode.AUDIO),
                        () -> supplyFrame(idx));
            }
        } catch (RuntimeException e) {
            // Construction partielle : on relache les players deja crees avant le plantage
            // pour ne pas fuiter leurs encodeurs (aucun n'est encore en startPlaying).
            for (AudioPlayer p : players) {
                if (p != null) {
                    try {
                        p.stopPlaying();
                    } catch (Exception ignored) {
                    }
                }
            }
            throw e;
        }

        this.reader = new Thread(this::readLoop, "MinecraftVideo-Audio-Reader");
        this.reader.setDaemon(true);
    }

    // Monte les canaux/players SVC autour d'un AudioStream deja cree (et idealement deja
    // pre-chauffe) et commence a bufferiser. anchors = une position monde {x,y,z} par
    // enceinte ; le nombre de canaux decodes vient du stream lui-meme. Rien ne sort tant
    // que begin() n'est pas appele.
    //
    // Si ca passe, c'est cette instance qui possede le stream. Si ca leve, c'est l'APPELANT
    // qui le possede encore (et doit le fermer).
    public static AudioPlayback create(VoicechatServerApi api, World world,
                                       double[][] anchors, int distance,
                                       AudioStream stream, Logger logger) {
        ServerLevel level = api.fromServerLevel(world);
        Position[] positions = new Position[anchors.length];
        for (int i = 0; i < anchors.length; i++) {
            positions[i] = api.createPosition(anchors[i][0], anchors[i][1], anchors[i][2]);
        }
        AudioPlayback playback = new AudioPlayback(api, level, positions, distance, stream, logger);
        playback.reader.start();
        return playback;
    }

    // Au moins une frame bufferisee ? begin() ne devrait etre appele qu'une fois amorce, pour
    // que la timeline SVC parte sur le premier sample du stream et pas sur du silence pendant
    // que ffmpeg chauffe.
    public boolean isPrimed() {
        return !queue.isEmpty();
    }

    // Le reader a-t-il atteint la fin de la sortie ffmpeg ? Sert a arreter d'attendre
    // l'amorcage sur une source sans (ou sans plus) audio, pour qu'un appelant qui bloque
    // sur isPrimed() ne reste pas coince sur une video muette.
    public boolean hasReachedEof() {
        return eof;
    }

    // Demarre les players SVC, le son est audible a partir de la. IDEMPOTENT : en direct la
    // boucle de frames rappelle begin() a chaque tranche alors que les players tournent deja.
    public void begin() {
        if (!begun.compareAndSet(false, true)) {
            return;
        }
        for (AudioPlayer p : players) {
            p.startPlaying();
        }
    }

    // Jette jusqu'a n frames bufferisees (non bloquant). Sert quand begin() arrive plus tard
    // que prevu : sauter l'equivalent en contenu garde l'audio cale sur l'image au lieu de
    // partir en retard et de le rester. Renvoie le nombre reellement jete (borne par le buffer).
    public int skipFrames(int n) {
        int dropped = 0;
        while (dropped < n && queue.poll() != null) {
            dropped++;
        }
        return dropped;
    }

    // A poser AVANT que le premier flux se termine : apres, le reader est deja sorti sur EOF
    // et le relais qu'on lui deposera n'aura plus personne pour le lire.
    public void setContinuous(boolean value) {
        continuous = value;
    }

    // Confie le flux de la tranche suivante : il alimentera les MEMES canaux SVC, qui ne sont
    // jamais fermes ni recrees -- un canal neuf par tranche, ca s'entend (coupure nette).
    //
    // Renvoie false si la file de relais est pleine ou si on est arrete ; l'appelant garde
    // alors la propriete du flux et doit le fermer.
    public boolean handOff(AudioStream next) {
        if (stopped.get()) {
            return false;
        }
        return handoff.offer(next);
    }

    // thread de lecture : tire les frames PCM de ffmpeg dans la file jusqu'a EOF/stop
    private void readLoop() {
        try {
            while (!stopped.get()) {
                short[][] decoded = stream.nextFrame();
                if (decoded == null) {
                    AudioStream next = nextStream();
                    if (next == null) {
                        eof = true;
                        return;
                    }
                    stream.close();     // la tranche precedente a fini de servir
                    stream = next;
                    continue;
                }
                // Un put par frame (toutes enceintes ensemble) ; ca bloque quand la file est
                // pleine, ce qui backpressure ffmpeg et maintient ~1 s de buffer.
                queue.put(mapToSpeakers(decoded));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            eof = true;
            logger.fine("Audio reader ended: " + e.getMessage());
        }
    }

    // Le flux de la tranche suivante, ou null si l'audio est vraiment fini. Hors direct on ne
    // patiente jamais : une video qui se termine doit fermer ses canaux tout de suite.
    private AudioStream nextStream() throws InterruptedException {
        if (!continuous) {
            return null;
        }
        return handoff.poll(HANDOFF_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    // Mappe les canaux decodes sur les canaux enceintes. Identite en mono/stereo. En 5.1
    // l'ordre correspond deja aux enceintes (FL FR FC LFE BL BR, le LFE etant le caisson en
    // jeu au pied de l'ecran) ; le LFE est monte de +6 dB avec saturation, comme le gain de
    // bass management qu'applique le vrai materiel sur ce canal.
    private short[][] mapToSpeakers(short[][] decoded) {
        if (decoded.length == 6) {
            short[] lfe = new short[AudioStream.FRAME_SAMPLES];
            for (int i = 0; i < lfe.length; i++) {
                int v = decoded[3][i] * LFE_BOOST;
                lfe[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            }
            decoded[3] = lfe;
        }
        return decoded;
    }

    // Pause/reprise. En pause chaque supplier sort du silence et le master ne VIDE PAS la
    // file, donc ffmpeg se fait backpressurer (file pleine) et la reprise repart exactement
    // sur la frame ou on s'etait arrete, en phase avec la video (freinee pareil).
    public void setPaused(boolean value) {
        paused = value;
    }

    // Appele par SVC toutes les 20 ms et par canal, chacun sur son thread.
    private short[] supplyFrame(int channel) {
        if (stopped.get()) {
            return null;
        }
        if (paused) {
            return SILENCE; // gel : on garde le canal ouvert sans le vider
        }
        if (channel == 0) {
            return supplyMaster();
        }
        // Les autres canaux recopient la frame courante du master pour que tout le monde
        // sorte le meme index de frame ; ils ne touchent jamais a la file.
        if (ended) {
            return null;
        }
        short[][] f = current;
        return (f != null && channel < f.length) ? f[channel] : SILENCE;
    }

    // canal 0 : fait avancer le flux commun et publie chaque frame pour les recopieurs
    private short[] supplyMaster() {
        try {
            short[][] frame = queue.poll(20, TimeUnit.MILLISECONDS);
            if (frame != null) {
                current = frame;
                return frame[0];
            }
            if (eof && queue.isEmpty()) {
                ended = true;   // vraie fin de l'audio : les recopieurs finissent ce tick aussi
                current = null;
                return null;
            }
            current = null;     // underrun passager : les recopieurs se taisent avec nous
            return SILENCE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // Idempotent : arrete les players SVC, tue ffmpeg et le thread de lecture.
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        for (AudioPlayer p : players) {
            try {
                p.stopPlaying();
            } catch (Exception ignored) {
                // SVC a peut-etre deja demonte le canal
            }
        }
        stream.close();
        reader.interrupt();
        queue.clear();
        // Un relais depose juste avant l'arret n'aura jamais de lecteur : on le ferme nous-memes
        // plutot que de laisser un ffmpeg orphelin par tranche.
        for (AudioStream pending = handoff.poll(); pending != null; pending = handoff.poll()) {
            pending.close();
        }
    }
}
