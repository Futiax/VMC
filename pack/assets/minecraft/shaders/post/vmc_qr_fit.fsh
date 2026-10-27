#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// Passe 3/4 : cible 4x1. Texels 0-2 = reperes TL, TR, BL ; texel 3 = statut.
// Transcription de clusters() / triple() / fit(). Les quatre fragments refont la MEME
// recherche deterministe et ne sortent que leur texel : pas de memoire partagee entre
// fragments, et 4 fragments x ~80 000 iterations reste negligeable.
//
// Texels 0-2 : x, y sur 12 bits (R = x & 255, G = y & 255, B = x >> 8 | (y >> 8) << 4),
// A = 255. Texel 3 : R = tuiles non vides, G = candidats / 16, B = motif de rejet
// (1 < 3 grappes, 2 trop de tuiles = bruit, 4 modules incoherents, 8 jambes inegales ou
// angle, 16 jambe / portee hors plage), A = 255 si le quad est valide.

uniform sampler2D InSampler;    // tile
uniform sampler2D CandSampler;  // cand, lu SEULEMENT pour la taille de l'ecran

layout(location = 0) out vec4 fragColor;

const int TILE_N = 32;
const float CNT_SCALE = 3.984375;
const int K_MAX = 8;
const float CLUSTER_MODULES = 2.0;
const int MAX_TILES = 48;
const float MOD_RATIO_MAX = 3.0;
const float MIN_FILL = 0.1;
const float LEG_RATIO_MAX = 2.5;
const float COS_MAX = 0.6;
const float LEG_LO = 0.5;
const float LEG_HI = 2.0;
const float SPAN_U = 50.0;
const float SPAN_V = 50.0;

ivec4 tileAt(int tx, int ty) {
    return ivec4(round(texelFetch(InSampler, ivec2(tx, ty), 0) * 255.0));
}

vec2 tilePos(ivec4 t, int tx, int ty, vec2 ts) {
    return vec2(float(tx), float(ty)) * ts + vec2(float(t.r), float(t.g)) / 255.0 * ts;
}

float tileCount(ivec4 t) {
    float c = float(t.b) / CNT_SCALE;
    return c * c;
}

// Un choix de TL : TR / BL departages au produit vectoriel -- une image n'est jamais miroir --
// puis jambes en modules (distance / module moyen des deux bouts : en pixels la perspective
// trompe) comparees aux portees. Transcription de triple().assess().
float assess(vec3 t, vec3 e1, vec3 e2, float mr, out int rej, out vec2 tl, out vec2 tr, out vec2 bl) {
    vec2 u = e1.xy - t.xy, v = e2.xy - t.xy;
    if (u.x * v.y - u.y * v.x > 0.0) {
        vec3 s = e1; e1 = e2; e2 = s;
        u = e1.xy - t.xy;
        v = e2.xy - t.xy;
    }
    tl = t.xy;
    tr = e1.xy;
    bl = e2.xy;
    float lu = length(u), lv = length(v);
    float cs = abs(dot(u, v)) / max(lu * lv, 1e-6);
    float mu = lu / ((t.z + e1.z) * 0.5), mv = lv / ((t.z + e2.z) * 0.5);   // jambes en modules
    float ru = mu / SPAN_U, rv = mv / SPAN_V;
    rej = 0;
    if (mr > MOD_RATIO_MAX) rej |= 4;
    if (max(ru, rv) > LEG_RATIO_MAX * min(ru, rv) || cs > COS_MAX) rej |= 8;
    if (min(ru, rv) < LEG_LO || max(ru, rv) > LEG_HI) rej |= 16;
    return abs(log(max(ru, 1e-6))) + abs(log(max(rv, 1e-6))) + log(mr) + cs;
}

// Le plus long cote EN MODULES relie TR a BL, le sommet restant est TL. Transcription de triple().
float triple(vec3 a, vec3 b, vec3 c, out int rej, out vec2 tl, out vec2 tr, out vec2 bl) {
    float d01 = distance(a.xy, b.xy) / ((a.z + b.z) * 0.5);
    float d02 = distance(a.xy, c.xy) / ((a.z + c.z) * 0.5);
    float d12 = distance(b.xy, c.xy) / ((b.z + c.z) * 0.5);
    float mr = max(a.z, max(b.z, c.z)) / min(a.z, min(b.z, c.z));
    if (d12 >= d02 && d12 >= d01) return assess(a, b, c, mr, rej, tl, tr, bl);
    if (d02 >= d01) return assess(b, a, c, mr, rej, tl, tr, bl);
    return assess(c, a, b, mr, rej, tl, tr, bl);
}


void main() {
    int idx = int(gl_FragCoord.x);
    vec2 ts = vec2((textureSize(CandSampler, 0) + TILE_N - 1) / TILE_N);

    // grappes : graine = tuile la plus peuplee hors des grappes deja prises
    vec2 sp[K_MAX];
    float sm[K_MAX];
    int ns = 0;
    for (int it = 0; it < K_MAX; it++) {
        int bestCnt = 0;
        vec2 bp = vec2(0.0);
        float bm = 0.0;
        for (int ty = 0; ty < TILE_N; ty++) {
            for (int tx = 0; tx < TILE_N; tx++) {
                ivec4 t = tileAt(tx, ty);
                if (t.b == 0) continue;
                vec2 p = tilePos(t, tx, ty, ts);
                bool taken = false;
                for (int j = 0; j < ns; j++) {
                    if (distance(p, sp[j]) <= CLUSTER_MODULES * sm[j]) taken = true;
                }
                if (!taken && t.b > bestCnt) {
                    bestCnt = t.b;
                    bp = p;
                    bm = float(t.a);
                }
            }
        }
        if (bestCnt == 0) break;
        sp[ns] = bp;
        sm[ns] = bm;
        ns++;
    }

    // grappes trop maigres pour un vrai repere ecartees ici, l'ordre des autres conserve
    vec3 cl[K_MAX];
    int nc = 0;
    for (int i = 0; i < ns; i++) {
        float wsum = 0.0;
        vec3 acc = vec3(0.0);
        for (int ty = 0; ty < TILE_N; ty++) {
            for (int tx = 0; tx < TILE_N; tx++) {
                ivec4 t = tileAt(tx, ty);
                if (t.b == 0) continue;
                vec2 p = tilePos(t, tx, ty, ts);
                if (distance(p, sp[i]) <= CLUSTER_MODULES * sm[i]) {
                    float wt = tileCount(t);
                    wsum += wt;
                    acc += wt * vec3(p, float(t.a));
                }
            }
        }
        vec3 c = acc / wsum;
        if (wsum >= MIN_FILL * c.z * c.z) {
            cl[nc] = c;
            nc++;
        }
    }

    // meilleur triangle valide ; a defaut, le motif de rejet du meilleur triangle tout court
    bool found = false;
    float bestScore = 1e30, fbScore = 1e30;
    int fallback = 1;
    vec2 best[3];
    for (int i = 0; i < nc; i++) {
        for (int j = i + 1; j < nc; j++) {
            for (int k = j + 1; k < nc; k++) {
                int rej;
                vec2 tl, tr, bl;
                float score = triple(cl[i], cl[j], cl[k], rej, tl, tr, bl);
                if (rej == 0 && score < bestScore) {
                    found = true;
                    bestScore = score;
                    best[0] = tl;
                    best[1] = tr;
                    best[2] = bl;
                }
                if (score < fbScore) {
                    fallback = rej;
                    fbScore = score;
                }
            }
        }
    }

    int nonEmpty = 0;
    float total = 0.0;
    for (int ty = 0; ty < TILE_N; ty++) {
        for (int tx = 0; tx < TILE_N; tx++) {
            ivec4 t = tileAt(tx, ty);
            if (t.b > 0) {
                nonEmpty++;
                total += tileCount(t);
            }
        }
    }
    int reject = found ? 0 : fallback;
    if (nonEmpty > MAX_TILES) reject |= 2;

    if (idx == 3) {
        fragColor = vec4(float(min(nonEmpty, 255)), min(floor(total / 16.0), 255.0),
                         float(reject), reject == 0 ? 255.0 : 0.0) / 255.0;
        return;
    }
    if (reject != 0) {
        fragColor = vec4(0.0);
        return;
    }
    ivec2 q = clamp(ivec2(floor(best[idx] + 0.5)), ivec2(0), ivec2(4095));
    fragColor = vec4(float(q.x & 255), float(q.y & 255),
                     float((q.x >> 8) | ((q.y >> 8) << 4)), 255.0) / 255.0;
}
