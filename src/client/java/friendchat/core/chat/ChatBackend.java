package friendchat.core.chat;

import friendchat.core.chat.Models.Msg;
import friendchat.core.chat.Models.Snapshot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/** What the UI needs from the chat engine; lets the UI be tested with a fake. All methods are cheap and thread-safe. */
public interface ChatBackend {
    Snapshot snapshot();

    int version();

    List<Msg> messages(String convKey);

    String title(String convKey);

    void setViewing(String convKey);

    /** @return an error message for the user, or null on success. */
    String addFriend(String code);

    void acceptRequest(String hex);

    void blockRequest(String hex);

    void removeFriend(String hex);

    void createGroup(String name, List<String> memberHex);

    void leaveGroup(String id);

    void sendText(String convKey, String text);

    void sendFile(String convKey, File file);

    void startRecording();

    void stopRecordingAndSend(String convKey);

    void cancelRecording();

    /** Milliseconds recorded so far, or -1 when not recording. */
    int recordingMs();

    void playVoice(Msg m);

    String playingId();

    /** 0 not requested, 1 loading, 2 ready, 3 failed. */
    int mediaStatus(Msg m);

    BufferedImage image(Msg m);

    void save(Msg m);
}
