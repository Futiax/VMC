package ovh.futiax.minecraftvideo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Parseur incremental du SubRip (SRT) que sort "ffmpeg -f srt". On le nourrit ligne par
// ligne au fil de la lecture de stdout et il rend une Cue des qu'un bloc est complet,
// comme ca on peut consommer les sous-titres pendant que la source se lit encore.
//
// Un bloc SRT c'est :
//   1                                   <- index numerique, optionnel
//   00:00:20,000 --> 00:00:24,400       <- debut --> fin (+ coords en fin de ligne parfois)
//   Premiere ligne de texte
//   Deuxieme ligne de texte
//                                       <- ligne vide = fin du bloc
//
// Robustesse : BOM UTF-8 vire sur la premiere ligne, CR du CRLF coupe sur toutes, ligne
// d'index optionnelle (des muxers l'omettent), balises inline (<i> <b> <u> <font ...>)
// retirees, et les accolades d'override ASS ({\anX}, {\pos(...)}) degagees. Un timecode
// malforme fait jeter le bloc en cours au lieu de tout faire peter.
//
// Pas thread-safe : SubtitleStream le pilote depuis son unique thread de lecture et
// ressort des Cue immuables.
public final class SrtParser {

    public record Cue(long startMillis, long endMillis, String text) {}     // [start, end) en temps stream

    // hh:mm:ss,mmm --> hh:mm:ss,mmm (virgule ou point pour les millis)
    private static final Pattern TIMECODE = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*"
                    + "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    private static final Pattern TAGS = Pattern.compile("</?[a-zA-Z][^>]*>");    // <i> </i> <b> <font color=...>
    private static final Pattern OVERRIDE_BRACES = Pattern.compile("\\{[^}]*}"); // {\an8}, {\pos(1,2)}...

    private boolean bomStripped = false;
    private long pendingStart = -1;
    private long pendingEnd = -1;
    private final List<String> pendingText = new ArrayList<>();

    // Avale une ligne brute (sans son terminateur). Rend une Cue quand la ligne ferme un
    // bloc (ligne vide), null sinon. Appeler flush() en fin de stream pour sortir un
    // dernier bloc qui n'aurait pas eu sa ligne vide.
    public Cue accept(String rawLine) {
        String line = rawLine;
        if (!bomStripped) {
            if (!line.isEmpty() && line.charAt(0) == '﻿') {     // BOM UTF-8 que ffmpeg colle parfois
                line = line.substring(1);
            }
            bomStripped = true;
        }
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {     // CRLF
            line = line.substring(0, line.length() - 1);
        }

        if (line.isBlank()) {
            return flush(); // ligne vide = fin du bloc courant
        }

        // On ne prend une ligne de timecode pour un DEBUT de cue que si aucune cue n'est en
        // train d'accumuler (pendingStart < 0). Une fois les timecodes poses, les blocs sont
        // separes par une ligne vide, donc une ligne qui CONTIENT juste un truc qui ressemble
        // a un timecode (un sous-titre qui cite "00:00:01,000 --> 00:00:02,000", ou une piste
        // pourrie) c'est du texte de la cue, pas un nouvel en-tete. Sans ce garde, le find()
        // sur toute la ligne dropait silencieusement la vraie cue en clearant texte + timecodes.
        if (pendingStart < 0) {
            Matcher m = TIMECODE.matcher(line);
            if (m.find()) {
                // La ligne de timecode ouvre la cue. La ligne d'index (optionnelle) juste
                // avant a ete ignoree faute de timecode en attente, donc on repart propre.
                pendingText.clear();
                pendingStart = toMillis(m.group(1), m.group(2), m.group(3), m.group(4));
                pendingEnd = toMillis(m.group(5), m.group(6), m.group(7), m.group(8));
                return null;
            }
            return null;    // ligne d'index, ou texte parasite avant la 1re cue -> poubelle
        }

        // Timecodes en attente : tout jusqu'a la prochaine ligne vide est le texte de la cue
        // (ce qui a saute au passage la ligne d'index, arrivee quand pendingStart etait < 0).
        String cleaned = clean(line);
        if (!cleaned.isEmpty()) {
            pendingText.add(cleaned);
        }
        return null;
    }

    // Sort le bloc accumule (s'il a un timecode valide ET du texte) et repart a zero.
    // A appeler en EOF pour vider un dernier bloc non termine. null si rien a sortir.
    public Cue flush() {
        Cue cue = null;
        if (pendingStart >= 0 && pendingEnd > pendingStart && !pendingText.isEmpty()) {
            cue = new Cue(pendingStart, pendingEnd, String.join("\n", pendingText));
        }
        pendingStart = -1;
        pendingEnd = -1;
        pendingText.clear();
        return cue;
    }

    private static String clean(String line) {
        String s = OVERRIDE_BRACES.matcher(line).replaceAll("");
        s = TAGS.matcher(s).replaceAll("");
        return s.strip();
    }

    private static long toMillis(String h, String m, String s, String millis) {
        // millis fait 1 a 3 chiffres : on complete a droite jusqu'a 3. SubRip en met 3 mais
        // des muxers en sortent moins, genre "20,5" qui veut dire 500 ms.
        String ms = (millis + "000").substring(0, 3);
        return (Long.parseLong(h) * 3600L + Long.parseLong(m) * 60L + Long.parseLong(s)) * 1000L
                + Long.parseLong(ms);
    }
}
