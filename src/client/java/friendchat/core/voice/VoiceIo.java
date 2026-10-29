package friendchat.core.voice;

/** Microphone/speaker access. The Opus implementation lives in the game-side glue so this core needs no libraries. */
public interface VoiceIo {
    interface Recording {
        /** Stops and returns the {@link VoiceFormat} container, or null if nothing usable was captured. */
        byte[] finish();

        void cancel();

        int elapsedMs();
    }

    interface Playback {
        void stop();

        boolean done();
    }

    Recording startRecording() throws Exception;

    Playback play(byte[] container) throws Exception;
}
