package ovh.futiax.doommc;

// Index de metadata des paquets d'entite, valables de 1.20.2 a 26.2+ (verifies via EntityLib).
// Base Entity = 0-7, Display = 8-22, TextDisplay = 23-27, Interaction = 8-10.
// ATTENTION item frame : ecrire l'item a l'index 8 (= Direction) deconnecte le client
// avec un "Network Protocol Error", c'est bien 9.
public final class MetadataIndices {

    private MetadataIndices() {}

    // Base Entity
    public static final int ENTITY_FLAGS = 0;

    // Item Frame
    public static final int ITEM_FRAME_ITEM = 9;
}
