package friendchat.client;

import friendchat.core.voice.VoiceFormat;
import friendchat.core.voice.VoiceIo;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;

/**
 * Voice notes with the same codec setup Simple Voice Chat uses: 48 kHz mono, 20 ms frames, Opus VOIP via the pure-Java
 * Concentus encoder/decoder, captured and played through javax.sound (SVC's JavaxMicrophone approach). Each recording
 * is one small worker thread that sleeps inside the audio line's blocking read, so it costs next to nothing.
 */
final class VoiceOpus implements VoiceIo {
    private static final int RATE = 48000, FRAME = RATE / 1000 * VoiceFormat.FRAME_MS;
    private static final AudioFormat FORMAT = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, RATE, 16, 1, 2, RATE, false);

    @Override
    public Recording startRecording() throws Exception {
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
        line.open(FORMAT, FRAME * 2 * 6);
        OpusEncoder enc = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        enc.setBitrate(16000); // ~2 KB/s: about 12 s of voice still fits inside the message itself
        enc.setComplexity(5);
        line.start();
        return new Rec(line, enc);
    }

    @Override
    public Playback play(byte[] container) throws Exception {
        List<byte[]> packets = VoiceFormat.unpack(container);
        if (packets == null) throw new IllegalArgumentException("not a voice note");
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, FORMAT));
        line.open(FORMAT, FRAME * 2 * 8);
        return new Play(line, packets);
    }

    private static final class Rec implements Recording {
        private final TargetDataLine line;
        private final OpusEncoder enc;
        private final List<byte[]> packets = new ArrayList<>();
        private volatile boolean stop, cancelled;
        private final Thread thread;

        Rec(TargetDataLine line, OpusEncoder enc) {
            this.line = line;
            this.enc = enc;
            thread = new Thread(this::loop, "friendchat-record");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
        }

        private void loop() {
            byte[] raw = new byte[FRAME * 2];
            short[] pcm = new short[FRAME];
            byte[] out = new byte[1275];
            try {
                while (!stop && packets.size() * VoiceFormat.FRAME_MS < VoiceFormat.MAX_MS) {
                    int got = 0;
                    while (got < raw.length && !stop) {
                        int n = line.read(raw, got, raw.length - got);
                        if (n <= 0) break;
                        got += n;
                    }
                    if (got < raw.length) break;
                    for (int i = 0; i < FRAME; i++) pcm[i] = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
                    int len = enc.encode(pcm, 0, FRAME, out, 0, out.length);
                    if (len > 0) {
                        byte[] pkt = new byte[len];
                        System.arraycopy(out, 0, pkt, 0, len);
                        synchronized (packets) {
                            packets.add(pkt);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[friend-chat] recording stopped: " + e);
            } finally {
                line.stop();
                line.close();
            }
        }

        @Override
        public byte[] finish() {
            stop = true;
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (cancelled) return null;
            synchronized (packets) {
                return packets.isEmpty() ? null : VoiceFormat.pack(packets);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            stop = true;
        }

        @Override
        public int elapsedMs() {
            synchronized (packets) {
                return packets.size() * VoiceFormat.FRAME_MS;
            }
        }
    }

    private static final class Play implements Playback {
        private final SourceDataLine line;
        private volatile boolean stop, done;

        Play(SourceDataLine line, List<byte[]> packets) {
            this.line = line;
            Thread t = new Thread(() -> run(packets), "friendchat-play");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
        }

        private void run(List<byte[]> packets) {
            try {
                OpusDecoder dec = new OpusDecoder(RATE, 1);
                short[] pcm = new short[FRAME];
                byte[] raw = new byte[FRAME * 2];
                line.start();
                for (byte[] p : packets) {
                    if (stop) break;
                    int n = dec.decode(p, 0, p.length, pcm, 0, FRAME, false);
                    for (int i = 0; i < n; i++) {
                        raw[i * 2] = (byte) pcm[i];
                        raw[i * 2 + 1] = (byte) (pcm[i] >> 8);
                    }
                    line.write(raw, 0, n * 2);
                }
                if (!stop) line.drain();
            } catch (Exception e) {
                System.err.println("[friend-chat] playback stopped: " + e);
            } finally {
                line.stop();
                line.close();
                done = true;
            }
        }

        @Override
        public void stop() {
            stop = true;
            line.stop();
            line.flush();
        }

        @Override
        public boolean done() { return done; }
    }
}
