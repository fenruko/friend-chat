package friendchat.core.media;

/** Upload limits applied before anything is sent (same field names chatimage's server config used). */
public final class Limits {
    public boolean allowImages = true, allowGifs = true, allowVideos = true;
    public int maxFileBytes = 8 * 1024 * 1024;
    public int maxImageDimension = 1600;
    public int maxGifFrames = 400;
    public int maxVideoSeconds = 120;
}
