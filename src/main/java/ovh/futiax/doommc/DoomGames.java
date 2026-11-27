package ovh.futiax.doommc;

import org.bukkit.Input;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Les parties de /doom : un moteur (EngineProcess) par partie, 1 a 4 joueurs. Solo = Doom normal
// (la mort recommence le niveau) ; coop et arene = mode reseau de Doom (reapparition sur place),
// l'arene etant un deathmatch ou les objets reviennent (-altdeath), sans monstres. On rejoint et on
// quitte en cours de partie. Chaque tick : les entrees de tous les joueurs partent au moteur, et la
// derniere trame de chaque place part dans la balise de son joueur. Main thread seulement.
final class DoomGames {

    enum Mode {
        SOLO(List.of()),
        COOP(List.of("-dmc-netgame")),
        ARENA(List.of("-dmc-netgame", "-altdeath", "-nomonsters"));

        final List<String> engineArgs;

        Mode(List<String> engineArgs) {
            this.engineArgs = engineArgs;
        }

        String label() {
            return name().toLowerCase();
        }
    }

    // Clics et molette, recus par evenements entre deux ticks (DoomListener)
    static final class Controls {
        int fireTicks, useTicks, weapon, weaponTicks;
    }

    private static final class Seat {
        final Player player;
        final DoomProbe.Beacon beacon;
        final Controls controls = new Controls();

        Seat(Player player, DoomProbe.Beacon beacon) {
            this.player = player;
            this.beacon = beacon;
        }
    }

    private static final class Game {
        final Mode mode;
        final EngineProcess engine;
        final Seat[] seats = new Seat[EngineProcess.SLOTS];
        BukkitTask task;
        int counter;
        boolean warned;

        Game(Mode mode, EngineProcess engine) {
            this.mode = mode;
            this.engine = engine;
        }

        int players() {
            int n = 0;
            for (Seat s : seats) {
                n += s != null ? 1 : 0;
            }
            return n;
        }
    }

    private static final int FIRE_TICKS = 6;     // un clic gauche = gachette tenue 6 ticks
    private static final int USE_TICKS = 3;
    private static final int WEAPON_TICKS = 2;   // touche d'arme appuyee puis relachee
    private static final int SILENT_TICKS = 40;  // moteur muet au-dela : on le signale

    private final DoomMc plugin;
    private final Map<UUID, Game> byPlayer = new HashMap<>();

    DoomGames(DoomMc plugin) {
        this.plugin = plugin;
    }

    boolean isPlaying(Player player) {
        return byPlayer.containsKey(player.getUniqueId());
    }

    Controls controls(Player player) {
        Game game = byPlayer.get(player.getUniqueId());
        if (game == null) {
            return null;
        }
        Seat seat = seatOf(game, player);
        return seat != null ? seat.controls : null;
    }

    // Nouvelle partie ; players.get(0) prend la place 0 (en jeu des le chargement du niveau).
    void create(Mode mode, List<Player> players) throws IOException {
        for (Player p : players) {
            leave(p);
            plugin.getDoomProbe().stop(p);
        }
        EngineProcess engine = plugin.startEngine(players.get(0).getName() + "-" + mode.label(), mode.engineArgs);
        Game game = new Game(mode, engine);
        for (int i = 0; i < players.size() && i < EngineProcess.SLOTS; i++) {
            seat(game, i, players.get(i), i > 0);
        }
        game.task = plugin.getServer().getScheduler().runTaskTimer(plugin.host(), () -> tick(game), 0L, 1L);
    }

    // Rejoint la partie de host ; faux si host ne joue pas, si c'est du solo ou si c'est complet.
    String join(Player host, Player player) {
        Game game = byPlayer.get(host.getUniqueId());
        if (game == null) {
            return host.getName() + " is not playing Doom.";
        }
        if (game.mode == Mode.SOLO) {
            return host.getName() + " plays solo: start a coop or arena game to play together.";
        }
        if (byPlayer.get(player.getUniqueId()) == game) {
            return player.getName() + " is already in that game.";
        }
        for (int i = 0; i < EngineProcess.SLOTS; i++) {
            if (game.seats[i] == null) {
                leave(player);
                plugin.getDoomProbe().stop(player);
                seat(game, i, player, true);
                return null;
            }
        }
        return "That game is full (4 players).";
    }

    private void seat(Game game, int slot, Player player, boolean announce) {
        if (announce) {
            game.engine.join(slot);
        }
        game.seats[slot] = new Seat(player, DoomProbe.attach(player));
        byPlayer.put(player.getUniqueId(), game);
        freeze(player, true);
        plugin.getPostEffects().apply(player, DoomProbe.VIEW_EFFECT);
        for (Seat s : game.seats) {
            if (s != null && s.player != player) {
                s.player.sendMessage("[DOOMMC] " + player.getName() + " joined the " + game.mode.label() + " game.");
            }
        }
    }

    void leave(Player player) {
        Game game = byPlayer.remove(player.getUniqueId());
        if (game == null) {
            return;
        }
        for (int i = 0; i < EngineProcess.SLOTS; i++) {
            Seat seat = game.seats[i];
            if (seat == null || !seat.player.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            game.seats[i] = null;
            DoomProbe.detach(player, seat.beacon);
            freeze(player, false);
            if (player.isOnline()) {
                plugin.getPostEffects().clear(player);
            }
            if (game.players() == 0) {
                game.task.cancel();
                game.engine.close();
            } else {
                game.engine.leave(i);
                for (Seat s : game.seats) {
                    if (s != null) {
                        s.player.sendMessage("[DOOMMC] " + player.getName() + " left the game.");
                    }
                }
            }
        }
    }

    void stopAll() {
        for (Game game : new ArrayList<>(byPlayer.values())) {
            for (Seat seat : game.seats.clone()) {
                if (seat != null) {
                    leave(seat.player);
                }
            }
        }
    }

    private static Seat seatOf(Game game, Player player) {
        for (Seat s : game.seats) {
            if (s != null && s.player.getUniqueId().equals(player.getUniqueId())) {
                return s;
            }
        }
        return null;
    }

    private void tick(Game game) {
        if (!game.engine.isAlive()) {
            for (Seat s : game.seats.clone()) {
                if (s != null) {
                    s.player.sendMessage("[DOOMMC] The Doom engine stopped (see plugins/DOOMMC/engine/*/engine.log).");
                    leave(s.player);
                }
            }
            return;
        }
        int[] buttons = new int[EngineProcess.SLOTS];
        int[] weapons = new int[EngineProcess.SLOTS];
        long[] angles = new long[EngineProcess.SLOTS];
        for (int i = 0; i < EngineProcess.SLOTS; i++) {
            Seat s = game.seats[i];
            if (s == null) {
                continue;
            }
            if (!s.player.isOnline()) {
                leave(s.player);
                if (game.players() == 0) {
                    return;
                }
                continue;
            }
            buttons[i] = buttons(s.player.getCurrentInput(), s.controls);
            weapons[i] = weapon(s.controls);
            angles[i] = EngineProcess.doomAngle(s.player.getLocation().getYaw());
        }
        game.engine.send(buttons, weapons, angles);
        boolean silent = true;
        for (int i = 0; i < EngineProcess.SLOTS; i++) {
            Seat s = game.seats[i];
            if (s != null) {
                int[] payload = game.engine.latest(i);
                silent &= payload.length == 0;
                DoomProbe.send(s.player, s.beacon, game.counter, payload);
            }
        }
        if (silent && game.counter >= SILENT_TICKS && !game.warned) {
            game.warned = true;
            plugin.getLogger().warning("A Doom engine is running but has sent no frame after " + SILENT_TICKS + " ticks.");
        }
        game.counter++;
    }

    // Modificateurs transitoires (jamais sauves avec le joueur) : x (1 - 1) sur la vitesse et le saut.
    private void freeze(Player player, boolean on) {
        NamespacedKey key = new NamespacedKey(plugin.host(), "doom_freeze");
        for (Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            instance.removeModifier(key);
            if (on) {
                instance.addTransientModifier(new AttributeModifier(key, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
        if (on) {
            player.setFlying(false);
        }
    }

    // Deplacements ZQSD, sprint = courir, saut = tirer (tenu), sneak = utiliser ; clics en plus.
    static int buttons(Input in, Controls c) {
        int b = (in.isForward() ? EngineProcess.FORWARD : 0) | (in.isBackward() ? EngineProcess.BACK : 0)
                | (in.isLeft() ? EngineProcess.LEFT : 0) | (in.isRight() ? EngineProcess.RIGHT : 0)
                | (in.isSprint() ? EngineProcess.RUN : 0)
                | (in.isJump() || c.fireTicks > 0 ? EngineProcess.FIRE : 0)
                | (in.isSneak() || c.useTicks > 0 ? EngineProcess.USE : 0);
        c.fireTicks = Math.max(0, c.fireTicks - 1);
        c.useTicks = Math.max(0, c.useTicks - 1);
        return b;
    }

    static int weapon(Controls c) {
        if (c.weaponTicks == 0) {
            return 0;
        }
        c.weaponTicks--;
        return c.weapon;
    }

    static void fire(Controls c) {
        c.fireTicks = FIRE_TICKS;
    }

    static void use(Controls c) {
        c.useTicks = USE_TICKS;
    }

    // emplacement 0..6 de la barre = arme 1..7 de Doom
    static void selectWeapon(Controls c, int slot) {
        if (slot < 7) {
            c.weapon = slot + 1;
            c.weaponTicks = WEAPON_TICKS;
        }
    }
}
