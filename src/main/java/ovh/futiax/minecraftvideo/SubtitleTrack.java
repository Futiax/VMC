package ovh.futiax.minecraftvideo;

import java.util.Set;
import java.util.Locale;

// Une piste de sous-titres embarquee, telle que ffprobe la rapporte : index relatif aux
// subs (le n de -map 0:s:n), codec, et les tags langue/titre s'ils sont la.
//
// Seuls les codecs texte peuvent finir en overlay : ffmpeg sait les transcoder en SRT.
// Les codecs bitmap (PGS/VOBSUB/DVB) sont des images pre-rendues, il n'y a aucun texte a
// extraire -> on les marque non supportees et /video subs <n> les refuse.
public record SubtitleTrack(int index, String codec, String language, String title) {

    // codecs image que ffprobe peut sortir : rien d'extractible, on refuse proprement
    // plutot que de lancer un ffmpeg qui va juste se vautrer
    private static final Set<String> BITMAP_CODECS = Set.of(
            "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle",
            "dvbsub", "pgssub", "xsub");

    public boolean textBased() {
        return codec != null && !BITMAP_CODECS.contains(codec.toLowerCase(Locale.ROOT));
    }

    // ligne pour /video subs list, genre  0: subrip [fre] "Full subtitles"
    // ou pour une piste image  2: hdmv_pgs_subtitle [eng] (bitmap track, not supported)
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append(": ").append(codec != null ? codec : "?");
        if (language != null && !language.isBlank()) {
            sb.append(" [").append(language).append(']');
        }
        if (title != null && !title.isBlank()) {
            sb.append(" \"").append(title).append('"');
        }
        if (!textBased()) {
            sb.append(" (bitmap track, not supported)");
        }
        return sb.toString();
    }
}
