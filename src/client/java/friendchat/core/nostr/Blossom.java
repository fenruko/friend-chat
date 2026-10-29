package friendchat.core.nostr;

import friendchat.core.media.PreparedAttachment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Blob storage on public Blossom servers (BUD-01/02): blobs are addressed by their SHA-256, uploads are authorised
 * by a signed Nostr event. Everything uploaded here is already encrypted, so the server only ever sees random bytes.
 */
public final class Blossom {
    private Blossom() { }

    public record Signer(byte[] sk, String pubHex) { }

    /** @return the URL of the stored blob. Tries each server in turn. */
    public static String upload(HttpClient http, List<String> servers, Signer who, byte[] blob, String sha256Hex) throws IOException {
        IOException last = new IOException("no storage server configured");
        for (String server : servers) {
            String base = trim(server);
            try {
                long now = System.currentTimeMillis() / 1000;
                Event auth = Event.create(who.sk(), who.pubHex(), 24242,
                        List.of(List.of("t", "upload"), List.of("x", sha256Hex), List.of("expiration", Long.toString(now + 600))),
                        "Upload", now);
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/upload"))
                        .timeout(Duration.ofSeconds(120))
                        .header("Authorization", "Nostr " + Base64.getEncoder().encodeToString(auth.toJson().getBytes(StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/octet-stream")
                        .header("X-SHA-256", sha256Hex)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(blob)).build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 != 2) {
                    last = new IOException(base + " answered " + res.statusCode());
                    continue;
                }
                Map<String, Object> o = Json.obj(Json.parse(res.body()));
                String url = Json.str(o, "url", null);
                return url != null && url.startsWith("http") ? url : base + "/" + sha256Hex;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (RuntimeException e) {
                last = new IOException(e.getMessage());
            }
        }
        throw last;
    }

    /** Downloads a blob from its URL, falling back to the same hash on the other servers; verifies the hash. */
    public static byte[] download(HttpClient http, String url, List<String> servers, String sha256Hex, int maxBytes) throws IOException {
        java.util.ArrayList<String> tries = new java.util.ArrayList<>();
        if (url != null && url.startsWith("http")) tries.add(url);
        for (String s : servers) tries.add(trim(s) + "/" + sha256Hex);
        IOException last = new IOException("not found");
        for (String u : tries) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(60)).GET().build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() / 100 != 2) {
                    res.body().close();
                    last = new IOException(u + " answered " + res.statusCode());
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = res.body()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > maxBytes) throw new IOException("blob too large");
                    }
                }
                byte[] data = out.toByteArray();
                if (PreparedAttachment.sha256Hex(data).equalsIgnoreCase(sha256Hex)) return data;
                last = new IOException("hash mismatch from " + u);
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (RuntimeException e) {
                last = new IOException(e.getMessage());
            }
        }
        throw last;
    }

    private static String trim(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
