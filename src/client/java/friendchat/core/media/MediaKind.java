package friendchat.core.media;

/** The three media families ChatImage understands. Ordinal = wire value. */
public enum MediaKind {
    IMAGE, GIF, VIDEO;

    public static MediaKind fromWire(int v) {
        MediaKind[] all = values();
        return v >= 0 && v < all.length ? all[v] : IMAGE;
    }
}
