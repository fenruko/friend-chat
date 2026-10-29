package friendchat.core.media;

/**
 * Minimal MP4 container reader: duration + dimensions from mvhd/tkhd.
 * ChatImage does not bundle a video codec (that would mean megabytes of
 * native FFmpeg in every jar); playback of MP4/WebM goes through the free
 * system ffmpeg when present - see {@link FfmpegBridge} and the docs.
 */
public record VideoInfo(int width, int height, int durationMs) {

    public static VideoInfo parseMp4(byte[] d) {
        try {
            int[] wh = {-1, -1};
            int duration = 0;
            int i = 0;
            while (i + 8 <= d.length) {
                long size = u32(d, i);
                String type = new String(d, i + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                int header = 8;
                if (size == 1 && i + 16 <= d.length) {
                    size = u64(d, i + 8);
                    header = 16;
                } else if (size == 0) {
                    size = d.length - i;
                }
                if (size < header || i + size > d.length + 8) {
                    // truncated final box is common in streamed files; stop
                    if (type.equals("moov") || type.equals("mvhd") || type.equals("tkhd")) {
                        // fall through and try to parse what we have
                    } else {
                        break;
                    }
                }
                int boxLen = (int) Math.min(size, d.length - i);
                switch (type) {
                    case "moov", "trak" -> {
                        VideoInfo nested = parseMp4Range(d, i + header, i + boxLen);
                        if (nested != null) {
                            if (nested.width() > 0) {
                                wh[0] = nested.width();
                                wh[1] = nested.height();
                            }
                            if (nested.durationMs() > 0) {
                                duration = nested.durationMs();
                            }
                        }
                    }
                    case "mvhd" -> {
                        int dur = mvhdDuration(d, i + header, boxLen - header);
                        if (dur > 0) {
                            duration = dur;
                        }
                    }
                    case "tkhd" -> {
                        int[] s = tkhdSize(d, i + header, boxLen - header);
                        if (s[0] > 0) {
                            wh = s;
                        }
                    }
                    default -> {
                        // not a box we need
                    }
                }
                if (size <= 0) {
                    break;
                }
                i += (int) size;
                if (i >= d.length) {
                    break;
                }
            }
            if (wh[0] < 0 && duration == 0) {
                return null;
            }
            return new VideoInfo(wh[0], wh[1], duration);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static VideoInfo parseMp4Range(byte[] d, int from, int to) {
        byte[] slice = new byte[Math.max(0, to - from)];
        System.arraycopy(d, from, slice, 0, slice.length);
        // reuse the box walker on the slice by pretending it is a file
        return parseMp4(slice);
    }

    private static int mvhdDuration(byte[] d, int o, int len) {
        if (len < 20 || o + len > d.length) {
            return 0;
        }
        int version = d[o] & 0xFF;
        long timescale;
        long dur;
        if (version == 1) {
            if (len < 32) {
                return 0;
            }
            timescale = u32(d, o + 20);
            dur = u64(d, o + 24);
        } else {
            timescale = u32(d, o + 12);
            dur = u32(d, o + 16);
        }
        if (timescale <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, dur * 1000L / timescale);
    }

    private static int[] tkhdSize(byte[] d, int o, int len) {
        // width/height are the final 8 bytes of tkhd, 16.16 fixed point
        if (len < 8 || o + len > d.length) {
            return new int[]{-1, -1};
        }
        int end = o + len;
        int w = (int) (u32(d, end - 8) >> 16);
        int h = (int) (u32(d, end - 4) >> 16);
        return new int[]{w, h};
    }

    static long u32(byte[] d, int o) {
        return ((long) (d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    static long u64(byte[] d, int o) {
        return (u32(d, o) << 32) | u32(d, o + 4);
    }
}
