package ovh.futiax.minecraftvideo;

// Snapshot de la config audio pris au demarrage d'une lecture.
//
// Modele de sync (freeze-frame) : la premiere image est envoyee tout de suite puis FIGEE
// pendant audioStartDelayMillis, le temps que le client SVC encaisse le pic de rendu de
// l'ecran qui apparait pendant que l'image ne bouge pas. Ensuite le pacing video et l'audio
// demarrent ensemble. L'audio SAUTE avSyncDelayMillis de contenu (-ss ffmpeg) pour que ce
// que le client entend, apres son propre buffering, tombe en face de l'image.
//
//   enabled               interrupteur general (audio-enabled)
//   ffmpegPath            executable ffmpeg (ffmpeg-path)
//   distance              distance de falloff SVC en blocs (audio-distance)
//   mode                  layout de canaux (audio-mode)
//   avSyncDelayMillis     latence client estimee (jitter buffer) compensee en sautant
//                         autant de contenu. Son en retard -> augmenter, en avance ->
//                         diminuer (av-sync-delay-ms)
//   audioStartDelayMillis duree du gel de la premiere image (audio-start-delay-ms)
//   rearDistance          blocs derriere le plan de l'ecran (vers le public) ou se posent
//                         les enceintes arriere du surround (surround-rear-distance)
public record AudioSettings(boolean enabled, String ffmpegPath, int distance,
                            AudioMode mode, int avSyncDelayMillis,
                            int audioStartDelayMillis, double rearDistance) {

    public long audioSkipMillis() {                 // contenu saute a chaque debut de segment, en ms
        return Math.max(0, avSyncDelayMillis);
    }
}
