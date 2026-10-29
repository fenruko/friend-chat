package friendchat.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Opens the OS file explorer (multi-select) on its own daemon thread so the
 * game never freezes.
 *
 * No AWT (loading its native toolkit into a process that already owns an
 * SDL/GLFW window crashes the JVM on Linux) and no extra libraries: the
 * desktop's own dialog is launched as a separate process - zenity/kdialog on
 * Linux, PowerShell on Windows, osascript on macOS. If none is available the
 * callback gets an empty list and the player can paste a file path instead.
 */
public final class FilePicker {
    private static final String[] EXTENSIONS = {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "mp4", "webm", "mov", "avi"
    };
    private static final String TITLE = "Friend Chat - pick an image, GIF or video";
    private static volatile boolean open;

    private FilePicker() {
    }

    /** Opens the OS file dialog and hands back the single chosen file (never called when cancelled). */
    public static void pick(Consumer<File> then) {
        pickImages(files -> {
            if (!files.isEmpty()) then.accept(files.get(0));
        });
    }

    public static boolean isOpen() {
        return open;
    }

    public static void pickImages(Consumer<List<File>> callback) {
        if (open) {
            return;
        }
        open = true;
        Thread thread = new Thread(() -> {
            List<File> result = new ArrayList<>();
            try {
                String out = choose();
                if (out != null) {
                    for (String line : out.split("[\\r\\n|]+")) {
                        String path = line.trim();
                        if (path.isEmpty()) {
                            continue;
                        }
                        File f = new File(path);
                        if (f.isFile()) {
                            result.add(f);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // no dialog backend: caller shows the paste-a-path hint
            } finally {
                open = false;
                callback.accept(result);
            }
        }, "FriendChat-FilePicker");
        thread.setDaemon(true);
        thread.start();
    }

    private static String choose() throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            StringBuilder filter = new StringBuilder();
            for (String ext : EXTENSIONS) {
                filter.append(filter.length() == 0 ? "" : ";").append("*.").append(ext);
            }
            String script = "Add-Type -AssemblyName System.Windows.Forms; "
                    + "$d = New-Object System.Windows.Forms.OpenFileDialog; "
                    + "$d.Multiselect = $false; $d.Title = '" + TITLE + "'; "
                    + "$d.Filter = 'Images, GIFs and videos|" + filter + "'; "
                    + "if ($d.ShowDialog() -eq 'OK') { [string]::Join([string][char]10, $d.FileNames) }";
            return run(List.of("powershell", "-NoProfile", "-STA", "-Command", script));
        }
        if (os.contains("mac")) {
            StringBuilder types = new StringBuilder();
            for (String ext : EXTENSIONS) {
                types.append(types.length() == 0 ? "" : ", ").append('"').append(ext).append('"');
            }
            String script = "set theFiles to choose file with prompt \"" + TITLE + "\" of type {" + types
                    + "}\n"
                    + "set out to \"\"\n"
                    + "repeat with f in theFiles\n"
                    + "set out to out & POSIX path of f & linefeed\n"
                    + "end repeat\n"
                    + "return out";
            return run(List.of("osascript", "-e", script));
        }
        // Linux / BSD: zenity first, kdialog second
        StringBuilder patterns = new StringBuilder();
        for (String ext : EXTENSIONS) {
            patterns.append(patterns.length() == 0 ? "" : " ")
                    .append("*.").append(ext).append(" *.").append(ext.toUpperCase(Locale.ROOT));
        }
        String viaZenity = run(List.of("zenity", "--file-selection", "--separator=|",
                "--title=" + TITLE, "--file-filter=Images, GIFs and videos | " + patterns));
        if (viaZenity != null) {
            return viaZenity;
        }
        return run(List.of("kdialog", "--getopenfilename", System.getProperty("user.home", "."),
                patterns.toString(), "--separate-output", "--title", TITLE));
    }

    /** @return the process's stdout, or null when the command could not be started. */
    private static String run(List<String> command) throws InterruptedException {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                in.transferTo(buf);
            }
            p.waitFor(); // a cancelled dialog exits non-zero with empty output: that is fine
            return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
