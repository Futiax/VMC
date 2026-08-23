package ovh.futiax.minecraftvideo;

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

    // Display
    public static final int DISPLAY_SCALE = 12;          // Vector3f
    public static final int DISPLAY_BILLBOARD = 15;      // byte
    public static final int DISPLAY_BRIGHTNESS = 16;     // int

    // Text Display
    public static final int TEXT_DISPLAY_TEXT = 23;      // Component
    public static final int TEXT_DISPLAY_LINE_WIDTH = 24; // int

    // Interaction
    public static final int INTERACTION_WIDTH = 8;       // float
    public static final int INTERACTION_HEIGHT = 9;      // float
    public static final int INTERACTION_RESPONSIVE = 10; // boolean
}
