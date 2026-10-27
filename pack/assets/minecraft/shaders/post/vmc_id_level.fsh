#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_color_id.py -- ne pas editer a la main.
// Passe 2/3, rejouee une fois par niveau (Level = 1..9) : grossit le carre homogene.
//
// POURQUOI des blocs ALIGNES et pas le plus gros carre homogene au sens strict : un
// bloc aligne de 2^L px est homogene SSI ses 4 sous-blocs de 2^(L-1) le sont et
// portent le meme id. C'est une recurrence a 4 taps par passe, et surtout tous les
// pixels d'un meme bloc en tirent la MEME reponse -- donc aucune propagation n'est
// necessaire, chaque pixel sait seul dans quel carre il est et ou en est le centre.
// La version exacte demanderait une transformee de distance de Chebyshev suivie d'un
// jump flooding pour diffuser l'argmax, soit ~35 passes au lieu de 12.
//
// PRIX ASSUME : le carre est cale sur une grille, donc au pire deux fois plus petit
// que l'optimal, et une grande zone plate recoit une etiquette par bloc au lieu d'une
// seule. Pour lire des ids c'est plutot un avantage : le nombre est lisible partout.

uniform sampler2D InSampler;

layout(std140) uniform LevelConfig {
    int Level;
};

layout(location = 0) out vec4 fragColor;

int idAt(ivec2 p) {
    return int(round(texelFetch(InSampler, p, 0).r * 255.0));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    int s = 1 << Level;
    int sub = s >> 1;
    ivec2 o = (p / s) * s;              // origine du bloc aligne qui me contient

    // Les 4 sous-blocs, lus a leur origine : B et G sont constants sur tout un
    // sous-bloc, donc un tap par sous-bloc suffit. clamp() parce qu'un bloc de bord
    // deborde -- ce cas est de toute facon rejete par `inside` juste apres.
    ivec2 c0 = clamp(o,                     ivec2(0), size - 1);
    ivec2 c1 = clamp(o + ivec2(sub, 0),     ivec2(0), size - 1);
    ivec2 c2 = clamp(o + ivec2(0, sub),     ivec2(0), size - 1);
    ivec2 c3 = clamp(o + ivec2(sub, sub),   ivec2(0), size - 1);

    bool allUniform = texelFetch(InSampler, c0, 0).b > 0.5
                   && texelFetch(InSampler, c1, 0).b > 0.5
                   && texelFetch(InSampler, c2, 0).b > 0.5
                   && texelFetch(InSampler, c3, 0).b > 0.5;
    int id0 = idAt(c0);
    bool sameId = id0 == idAt(c1) && id0 == idAt(c2) && id0 == idAt(c3);
    // Un bloc qui sort de l'ecran est refuse, sinon l'etiquette serait coupee au bord.
    bool inside = o.x + s <= size.x && o.y + s <= size.y;

    bool ok = allUniform && sameId && inside;
    vec4 me = texelFetch(InSampler, p, 0);
    // G ne recule jamais : chaque pixel garde le plus grand niveau ou SON bloc tenait.
    fragColor = vec4(me.r, ok ? float(Level) / 255.0 : me.g, ok ? 1.0 : 0.0, 1.0);
}
