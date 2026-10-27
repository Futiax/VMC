// Execute une chaine post_effect sur le VRAI GPU (EGL sans fenetre) et sauve chaque cible.
//
//   gcc -O2 qr_glcheck.c -o qr_glcheck -lEGL -lGL
//   ./qr_glcheck <W> <H> <in.rgba> <dossier de sortie> <chaine.txt>
//
// POURQUOI : gen_qr_detect.py prouve l'ALGORITHME en numpy, mais le GLSL en est une
// transcription a la main -- un signe, un off-by-one, un arrondi float32 et le shader fait
// autre chose que la simulation sans que rien ne le dise en jeu. Ici les memes .fsh tournent
// sur la meme image, et `gen_qr_detect.py --gpu` compare chaque cible a numpy.
//
// chaine.txt est ecrit par gen_qr_detect.py DEPUIS effects() : c'est le cablage exact du JSON,
// qui ne peut donc pas diverger de ce harnais.
//   target <nom> <w> <h>                       0 0 = taille de l'ecran
//   pass <fsh> <cible de sortie> <sampler>=<cible> ...   minecraft:main = l'image d'entree
//   image <nom> <w> <h> <fichier.rgba>         texture fixe (textures/effect/<nom>.png en jeu),
//                                              ligne 0 du fichier = texel y = 0, comme un PNG
// La passe blit vanilla (copie de out vers main) n'y figure pas : rien a verifier.
//
// Le vertex shader n'est PAS celui du jeu : core/screenquad utilise gl_VertexIndex (Vulkan,
// ShaderC le traduit), inconnu du GLSL desktop. Le triangle plein ecran equivalent en
// gl_VertexID suffit : seuls les fragment shaders sont testes.
//
// Entree et sorties en RGBA8 brut, ligne 0 = BAS (convention texelFetch / glReadPixels).
// Sortie standard : une ligne "ms <fsh> <duree>" par passe (2e tirage, apres glFinish).
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <GL/glext.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define POST "resourcepack/assets/minecraft/shaders/post/"
#define MAX_TARGETS 32
#define MAX_INPUTS 8

static const char *VSH =
    "#version 330\n"
    "void main() {\n"
    "    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n"
    "    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n"
    "}\n";

static void die(const char *what) {
    fprintf(stderr, "qr_glcheck: %s\n", what);
    exit(1);
}

static char *slurp(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) die(path);
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    rewind(f);
    char *buf = malloc(n + 1);
    if (fread(buf, 1, n, f) != (size_t)n) die(path);
    buf[n] = 0;
    fclose(f);
    return buf;
}

static GLuint compile(GLenum type, const char *src, const char *name) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetShaderInfoLog(s, sizeof log, NULL, log);
        fprintf(stderr, "%s:\n%s\n", name, log);
        exit(1);
    }
    return s;
}

static GLuint program(const char *fsh) {
    char path[512];
    snprintf(path, sizeof path, POST "%s.fsh", fsh);
    char *src = slurp(path);
    GLuint p = glCreateProgram();
    glAttachShader(p, compile(GL_VERTEX_SHADER, VSH, "vsh"));
    glAttachShader(p, compile(GL_FRAGMENT_SHADER, src, path));
    glLinkProgram(p);
    GLint ok;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetProgramInfoLog(p, sizeof log, NULL, log);
        fprintf(stderr, "link %s:\n%s\n", fsh, log);
        exit(1);
    }
    free(src);
    return p;
}

typedef struct {
    char name[64];
    GLuint tex, fbo;
    int w, h;
} Target;

static Target targets[MAX_TARGETS];
static int ntargets;

static Target *target(const char *name, int w, int h, const void *pixels) {
    if (ntargets == MAX_TARGETS) die("trop de cibles");
    Target *t = &targets[ntargets++];
    snprintf(t->name, sizeof t->name, "%s", name);
    t->w = w;
    t->h = h;
    glGenTextures(1, &t->tex);
    glBindTexture(GL_TEXTURE_2D, t->tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &t->fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, t->fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t->tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) die("fbo");
    return t;
}

static Target *find(const char *name) {
    for (int i = 0; i < ntargets; i++) {
        if (!strcmp(targets[i].name, name)) return &targets[i];
    }
    fprintf(stderr, "qr_glcheck: cible inconnue %s\n", name);
    exit(1);
}

static double now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

// une passe : samplers names[i] -> ins[i], sortie out ; renvoie la duree en ms (glFinish)
static double pass(const char *fsh, int n, char **names, Target **ins, Target *out) {
    GLuint p = program(fsh);
    glUseProgram(p);
    for (int i = 0; i < n; i++) {
        char uname[80];
        snprintf(uname, sizeof uname, "%sSampler", names[i]);
        glActiveTexture(GL_TEXTURE0 + i);
        glBindTexture(GL_TEXTURE_2D, ins[i]->tex);
        GLint loc = glGetUniformLocation(p, uname);
        if (loc >= 0) glUniform1i(loc, i);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, out->fbo);
    glViewport(0, 0, out->w, out->h);
    // un premier tirage a blanc : Mesa compile le programme au premier draw, et ce temps-la
    // n'a rien a voir avec le cout par frame
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    double t0 = now();
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    return (now() - t0) * 1e3;
}

static void save(Target *t, const char *dir) {
    char path[512];
    snprintf(path, sizeof path, "%s/%s.rgba", dir, t->name);
    unsigned char *buf = malloc((size_t)t->w * t->h * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, t->fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, t->w, t->h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
    FILE *f = fopen(path, "wb");
    if (!f) die(path);
    fwrite(buf, 1, (size_t)t->w * t->h * 4, f);
    fclose(f);
    free(buf);
}

int main(int argc, char **argv) {
    if (argc != 6) die("usage: qr_glcheck W H in.rgba outdir chain.txt");
    int w = atoi(argv[1]), h = atoi(argv[2]);

    PFNEGLGETPLATFORMDISPLAYEXTPROC getPlatformDisplay =
        (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    EGLDisplay dpy = getPlatformDisplay
        ? getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL)
        : eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(dpy, NULL, NULL)) die("eglInitialize");
    eglBindAPI(EGL_OPENGL_API);
    // aucune surface : pas besoin de config (EGL_KHR_no_config_context, Mesa l'a)
    EGLint xattr[] = {EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 5,
                      EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                      EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, xattr);
    if (ctx == EGL_NO_CONTEXT) die("eglCreateContext");
    if (!eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) die("eglMakeCurrent");
    fprintf(stderr, "GL: %s\n", glGetString(GL_RENDERER));

    GLuint vao;
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);

    size_t bytes = (size_t)w * h * 4;
    unsigned char *img = malloc(bytes);
    FILE *f = fopen(argv[3], "rb");
    if (!f || fread(img, 1, bytes, f) != bytes) die(argv[3]);
    fclose(f);
    target("minecraft:main", w, h, img);

    FILE *cf = fopen(argv[5], "r");
    if (!cf) die(argv[5]);
    char line[1024];
    while (fgets(line, sizeof line, cf)) {
        char *tok = strtok(line, " \n");
        if (!tok) continue;
        if (!strcmp(tok, "target")) {
            char *name = strtok(NULL, " \n");
            int tw = atoi(strtok(NULL, " \n")), th = atoi(strtok(NULL, " \n"));
            target(name, tw ? tw : w, th ? th : h, NULL);
        } else if (!strcmp(tok, "image")) {
            char *name = strtok(NULL, " \n");
            int tw = atoi(strtok(NULL, " \n")), th = atoi(strtok(NULL, " \n"));
            char *path = strtok(NULL, "\n");
            size_t n = (size_t)tw * th * 4;
            unsigned char *px = malloc(n);
            FILE *pf = fopen(path, "rb");
            if (!pf || fread(px, 1, n, pf) != n) die(path);
            fclose(pf);
            target(name, tw, th, px);
            free(px);
        } else if (!strcmp(tok, "pass")) {
            char *fsh = strtok(NULL, " \n");
            Target *out = find(strtok(NULL, " \n"));
            char *names[MAX_INPUTS];
            Target *ins[MAX_INPUTS];
            int n = 0;
            char *io;
            while ((io = strtok(NULL, " \n")) && n < MAX_INPUTS) {
                char *eq = strchr(io, '=');
                if (!eq) die(io);
                *eq = 0;
                names[n] = io;
                ins[n++] = find(eq + 1);
            }
            printf("ms %s %.3f\n", fsh, pass(fsh, n, names, ins, out));
        } else {
            die(tok);
        }
    }
    fclose(cf);

    for (int i = 1; i < ntargets; i++) save(&targets[i], argv[4]);
    return 0;
}
