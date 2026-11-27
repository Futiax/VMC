// DOOMMC : le moteur Doom (doomgeneric) pilote par le plugin, un processus par partie (1 a 4 joueurs).
//
// Arguments : ceux de Doom (-iwad, -warp 1 1, -skill, -altdeath, -nomonsters...), plus -dmc-netgame
// pour une partie a plusieurs (coop, ou arene avec -altdeath) : le niveau est recharge en mode reseau
// (objets reserves au multijoueur, reapparition sur place au lieu de recommencer le niveau).
// Le joueur 0 est en jeu des le depart ; les autres arrivent et partent en cours de partie.
//
// stdin, une ligne par tick serveur (20/s) :
//   "T b0 w0 a0 b1 w1 a1 b2 w2 a2 b3 w3 a3" : entrees des 4 places (ignorees si la place est libre)
//     b : boutons 1 avancer, 2 reculer, 4 pas a gauche, 8 pas a droite, 16 tirer, 32 utiliser, 64 courir
//     w : 0 rien, 1..7 = changer d'arme ; a : angle de vue (BAM, 0 = est, sens trigonometrique)
//   "J p" : le joueur p arrive (place libre) ; "L p" : il part (son corps reste, inerte)
// Apres chaque ligne T, le moteur joue 1 ou 2 tics (35 tics/s pour 20 ticks/s) et ecrit sur stdout :
// uint16 nombre de trames, puis par joueur en jeu : uint16 place, uint16 n, n valeurs uint16 (petit-
// boutiste) = la charge utile de sa balise a partir de la valeur 6 (les valeurs 0-5 sont le compteur
// et les angles, ajoutes par le plugin). Disposition : write_state ; gen_level.py (STATE) et le shader
// doom_view la lisent aux memes indices.
//
// Les tics sont joues ici (commandes construites pour chaque joueur, puis G_Ticker) : ni clavier ni
// reseau de Doom. Le journal de Doom (stdout d'origine) part sur stderr ; aucun rendu apres le
// demarrage, seule la logique du jeu tourne.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "doomgeneric.h"
#include "doomstat.h"
#include "d_player.h"
#include "g_game.h"
#include "info.h"
#include "m_argv.h"
#include "m_random.h"
#include "p_local.h"
#include "r_main.h"
#include "r_state.h"

// pas exportes par les en-tetes de doomgeneric
extern byte consistancy[MAXPLAYERS][BACKUPTICS];
void P_SpawnPlayer(mapthing_t *mthing);

#define MAX_VALUES 4090
#define MAX_THINGS 256
#define NO_LUMP 0xFFFF

// ---- plateforme doomgeneric : rien a afficher, aucune touche ----

void DG_Init(void) {}
void DG_DrawFrame(void) {}
void DG_SetWindowTitle(const char *title) { (void)title; }

void DG_SleepMs(uint32_t ms) {
    usleep(ms * 1000);
}

uint32_t DG_GetTicksMs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

int DG_GetKey(int *pressed, unsigned char *key) {
    (void)pressed;
    (void)key;
    return 0;
}

// ---- joueurs ----

typedef struct {
    unsigned buttons, weapon;
    angle_t angle;
} input_t;

static input_t inputs[MAXPLAYERS];

// Commande d'un tic, comme G_BuildTiccmd la construirait au clavier (vitesses de g_game.c)
static void build_cmd(int i, ticcmd_t *cmd) {
    static const int FORWARD[2] = {0x19, 0x32}, SIDE[2] = {0x18, 0x28};
    input_t *in = &inputs[i];
    int run = (in->buttons & 64) != 0;
    memset(cmd, 0, sizeof *cmd);
    cmd->forwardmove = (signed char)(((in->buttons & 1) ? FORWARD[run] : 0) - ((in->buttons & 2) ? FORWARD[run] : 0));
    cmd->sidemove = (signed char)(((in->buttons & 8) ? SIDE[run] : 0) - ((in->buttons & 4) ? SIDE[run] : 0));
    if (in->buttons & 16) cmd->buttons |= BT_ATTACK;
    if (in->buttons & 32) cmd->buttons |= BT_USE;
    if (in->weapon >= 1 && in->weapon <= 7) {
        cmd->buttons |= BT_CHANGE | ((in->weapon - 1) << BT_WEAPONSHIFT);
    }
    // controle de coherence du reseau de Doom (G_Ticker) : la valeur qu'il attend
    cmd->consistancy = consistancy[i][(gametic / ticdup) % BACKUPTICS];
}

// Une place libre sans joueur vivant a moins de 64 unites
static boolean spot_free(mapthing_t *spot) {
    for (int j = 0; j < MAXPLAYERS; j++) {
        mobj_t *mo = players[j].mo;
        if (!playeringame[j] || !mo || mo->health <= 0) continue;
        fixed_t dx = abs(mo->x - (spot->x << FRACBITS)), dy = abs(mo->y - (spot->y << FRACBITS));
        if (dx < 64 * FRACUNIT && dy < 64 * FRACUNIT) return false;
    }
    return true;
}

static void spawn_at(int slot, mapthing_t *spot) {
    short type = spot->type;
    spot->type = (short)(slot + 1);     // P_SpawnPlayer lit le numero du joueur dans le type
    P_SpawnPlayer(spot);
    spot->type = type;
}

// Arrivee en cours de partie (G_DoReborn suppose un corps existant : on fait sans)
static void join(int slot) {
    if (slot < 0 || slot >= MAXPLAYERS || playeringame[slot]) return;
    playeringame[slot] = true;
    players[slot].playerstate = PST_REBORN;
    players[slot].mo = NULL;
    memset(players[slot].frags, 0, sizeof players[slot].frags);
    if (gamestate != GS_LEVEL) return;          // il apparaitra au chargement du niveau
    int n = deathmatch ? (int)(deathmatch_p - deathmatchstarts) : MAXPLAYERS;
    mapthing_t *starts = deathmatch ? deathmatchstarts : playerstarts;
    int first = deathmatch ? P_Random() % n : slot;
    for (int k = 0; k < n; k++) {
        mapthing_t *spot = &starts[(first + k) % n];
        if (spot->type != 0 && spot_free(spot)) {
            spawn_at(slot, spot);
            return;
        }
    }
    spawn_at(slot, &playerstarts[slot]);        // tout est occupe : tant pis
}

// Depart : le corps reste, detache du joueur (comme un cadavre de G_DoReborn)
static void leave(int slot) {
    if (slot < 0 || slot >= MAXPLAYERS || !playeringame[slot]) return;
    playeringame[slot] = false;
    if (players[slot].mo) {
        players[slot].mo->player = NULL;
    }
}

static void run_tic(void) {
    static ticcmd_t cmds[MAXPLAYERS];
    // le pack ne contient que E1M1 : fin du niveau = on le recommence, joueurs gardes
    if (gamestate == GS_INTERMISSION || gamestate == GS_FINALE) {
        G_InitNew(gameskill, 1, 1);
    }
    for (int i = 0; i < MAXPLAYERS; i++) {
        player_t *p = &players[i];
        // mort : Doom tourne le joueur vers son tueur, on le laisse faire
        if (playeringame[i] && gamestate == GS_LEVEL && p->mo && p->playerstate == PST_LIVE) {
            p->mo->angle = inputs[i].angle;
        }
        build_cmd(i, &cmds[i]);
    }
    netcmds = cmds;
    G_Ticker();
    gametic++;
}

// ---- etat ----

static uint16_t values[MAX_VALUES];
static int nvalues;

static void put(int v) {
    if (nvalues < MAX_VALUES) values[nvalues++] = (uint16_t)v;
}

static void put32(int v) {
    put(v & 0xFFFF);
    put((unsigned)v >> 16);
}

typedef struct {
    mobj_t *mo;
    double dist;
} near_t;

static int by_distance(const void *a, const void *b) {
    double d = ((const near_t *)a)->dist - ((const near_t *)b)->dist;
    return (d > 0) - (d < 0);
}

static void put_psprite(pspdef_t *psp) {
    if (!psp->state) {
        put(NO_LUMP); put(0); put(0); put(0);
        return;
    }
    spriteframe_t *f = &sprites[psp->state->sprite].spriteframes[psp->state->frame & FF_FRAMEMASK];
    put(f->lump[0]);
    put((psp->state->frame & FF_FULLBRIGHT) ? 1 : 0);
    put(psp->sx >> FRACBITS);
    put(psp->sy >> FRACBITS);
}

// Palette de PLAYPAL selon les degats / bonus (ST_doPaletteStuff de st_stuff.c)
static int palette(player_t *p) {
    int cnt = p->damagecount;
    if (p->powers[pw_strength]) {
        int bzc = 12 - (p->powers[pw_strength] >> 6);
        if (bzc > cnt) cnt = bzc;
    }
    if (cnt) {
        int pal = (cnt + 7) >> 3;
        return (pal >= 8 ? 7 : pal) + 1;
    }
    if (p->bonuscount) {
        int pal = (p->bonuscount + 7) >> 3;
        return (pal >= 4 ? 3 : pal) + 9;
    }
    if (p->powers[pw_ironfeet] > 4 * 32 || (p->powers[pw_ironfeet] & 8)) {
        return 13;
    }
    return 0;
}

// Frags affiches par Doom (ST_calcFrags) : ceux sur les autres moins les suicides
static int frags(int slot) {
    int f = 0;
    for (int j = 0; j < MAXPLAYERS; j++) {
        f += j == slot ? -players[slot].frags[j] : players[slot].frags[j];
    }
    return f;
}

static void write_state(int slot) {
    static near_t near[4096];
    player_t *p = &players[slot];
    mobj_t *pmo = p->mo;
    nvalues = 0;

    // 6..11 : camera en 1/16 d'unite
    put32(pmo ? pmo->x >> 12 : 0);
    put32(pmo ? pmo->y >> 12 : 0);
    put32(p->viewz >> 12);
    // 12..16 : HUD
    put(p->health);
    put(p->armorpoints);
    ammotype_t ammo = weaponinfo[p->readyweapon].ammo;
    put(ammo == am_noammo ? NO_LUMP : p->ammo[ammo]);
    put(p->readyweapon);
    put(palette(p));
    // 17..24 : arme et eclair du tir (lump de sprite, plein feu, position ecran 320x200)
    put_psprite(&p->psprites[ps_weapon]);
    put_psprite(&p->psprites[ps_flash]);

    // objets visibles, les plus proches d'abord
    int n = 0;
    for (thinker_t *th = thinkercap.next; th != &thinkercap && n < 4096; th = th->next) {
        if (th->function.acp1 != (actionf_p1)P_MobjThinker) continue;
        mobj_t *mo = (mobj_t *)th;
        if (mo == pmo || (mo->flags & MF_NOSECTOR)) continue;
        double dx = (mo->x - (pmo ? pmo->x : 0)) / 65536.0, dy = (mo->y - (pmo ? pmo->y : 0)) / 65536.0;
        near[n].mo = mo;
        near[n++].dist = dx * dx + dy * dy;
    }
    qsort(near, n, sizeof near[0], by_distance);
    int room = (MAX_VALUES - 29 - 3 * numsectors) / 5;
    if (n > MAX_THINGS) n = MAX_THINGS;
    if (n > room) n = room;

    // 25..28 : compteurs, etat (mort, niveau en cours, deathmatch, lumiere du tir), frags
    put(numsectors);
    put(n);
    put((p->playerstate == PST_DEAD ? 1 : 0) | (gamestate == GS_LEVEL ? 2 : 0) | (deathmatch ? 4 : 0)
        | (p->extralight << 8));
    put(frags(slot));

    // 29.. : secteurs (sol, plafond, lumiere), puis objets (x, y, z, lump, drapeaux)
    for (int i = 0; i < numsectors; i++) {
        put(sectors[i].floorheight >> FRACBITS);
        put(sectors[i].ceilingheight >> FRACBITS);
        put(sectors[i].lightlevel);
    }
    for (int i = 0; i < n; i++) {
        mobj_t *mo = near[i].mo;
        spriteframe_t *f = &sprites[mo->sprite].spriteframes[mo->frame & FF_FRAMEMASK];
        int rot = 0;
        if (f->rotate) {
            angle_t ang = R_PointToAngle2(pmo ? pmo->x : 0, pmo ? pmo->y : 0, mo->x, mo->y);
            rot = (ang - mo->angle + (unsigned)(ANG45 / 2) * 9) >> 29;
        }
        put(mo->x >> FRACBITS);
        put(mo->y >> FRACBITS);
        put(mo->z >> FRACBITS);
        put(f->lump[rot]);
        // drapeaux : miroir, plein feu, ombre (spectre), couleur de joueur (MF_TRANSLATION), lumiere
        put((f->flip[rot] ? 1 : 0) | ((mo->frame & FF_FULLBRIGHT) ? 2 : 0) | ((mo->flags & MF_SHADOW) ? 4 : 0)
            | (int)(((mo->flags & MF_TRANSLATION) >> MF_TRANSSHIFT) << 3)
            | (mo->subsector->sector->lightlevel << 8));
    }

}

static unsigned char outbuf[2 + MAXPLAYERS * (4 + 2 * MAX_VALUES)];

static void write_all(int fd, size_t len) {
    size_t done = 0;
    while (done < len) {
        ssize_t w = write(fd, outbuf + done, len - done);
        if (w <= 0) exit(0);    // le plugin est parti
        done += (size_t)w;
    }
}

static void write_frames(int fd) {
    size_t len = 2;
    uint16_t frames = 0;
    for (int slot = 0; slot < MAXPLAYERS; slot++) {
        if (!playeringame[slot]) continue;
        write_state(slot);
        uint16_t head[2] = {(uint16_t)slot, (uint16_t)nvalues};
        memcpy(outbuf + len, head, 4);
        memcpy(outbuf + len + 4, values, 2 * (size_t)nvalues);
        len += 4 + 2 * (size_t)nvalues;
        frames++;
    }
    memcpy(outbuf, &frames, 2);
    write_all(fd, len);
}

int main(int argc, char **argv) {
    int out = dup(1);
    dup2(2, 1);
    setvbuf(stdout, NULL, _IOLBF, 0);   // journal ligne par ligne : le processus est tue, pas quitte
    doomgeneric_Create(argc, argv);     // demarre, charge E1M1 et joue la premiere tic (rendu compris)
    if (M_CheckParm("-dmc-netgame")) {
        netgame = true;                 // puis recharge le niveau en mode reseau
        G_InitNew(gameskill, 1, 1);
    }

    char line[256];
    long tick = 0;
    while (fgets(line, sizeof line, stdin)) {
        int slot;
        if (line[0] == 'J' && sscanf(line + 1, "%d", &slot) == 1) {
            join(slot);
            continue;
        }
        if (line[0] == 'L' && sscanf(line + 1, "%d", &slot) == 1) {
            leave(slot);
            continue;
        }
        if (line[0] != 'T') continue;
        char *at = line + 1;
        for (int i = 0; i < MAXPLAYERS; i++) {
            unsigned long angle;
            int used;
            if (sscanf(at, "%u %u %lu%n", &inputs[i].buttons, &inputs[i].weapon, &angle, &used) != 3) break;
            inputs[i].angle = (angle_t)angle;
            at += used;
        }
        int tics = (int)((tick + 1) * 35 / 20 - tick * 35 / 20);
        tick++;
        for (int i = 0; i < tics; i++) {
            run_tic();
        }
        write_frames(out);
    }
    return 0;
}
