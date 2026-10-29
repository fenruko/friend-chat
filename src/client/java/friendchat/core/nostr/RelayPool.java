package friendchat.core.nostr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A handful of relay WebSockets kept open with exponential-backoff reconnects. It does no parsing: raw frames are
 * handed to the listener, which moves them onto the engine thread. Idle cost is a few sleeping sockets.
 */
public final class RelayPool {
    public interface Listener {
        void onMessage(String relay, String raw);

        /** Called when a relay (re)connects, so the engine can re-announce its presence. */
        void onConnected(String relay, int connectedNow);
    }

    private static final String SUB = "fc";

    private final HttpClient http;
    private final ScheduledExecutorService ses;
    private final Listener listener;
    private final List<Conn> conns = new ArrayList<>();
    private volatile String filters = "";
    private volatile boolean running;
    private final List<String> pending = new ArrayList<>(); // events published while nothing was connected

    public RelayPool(HttpClient http, ScheduledExecutorService ses, List<String> urls, Listener listener) {
        this.http = http;
        this.ses = ses;
        this.listener = listener;
        for (String u : urls) conns.add(new Conn(u));
    }

    public void start() {
        running = true;
        for (Conn c : conns) c.connect();
    }

    public void stop() {
        running = false;
        for (Conn c : conns) c.close();
    }

    public int connected() {
        int n = 0;
        for (Conn c : conns) if (c.open) n++;
        return n;
    }

    public int total() {
        return conns.size();
    }

    /** Replaces the live subscription; {@code filtersJson} is the comma-separated list of filter objects. */
    public void subscribe(String filtersJson) {
        filters = filtersJson;
        for (Conn c : conns) if (c.open) c.sendReq();
    }

    public void publish(Event e) {
        String frame = "[\"EVENT\"," + e.toJson() + "]";
        boolean sent = false;
        for (Conn c : conns) {
            if (c.open) {
                c.send(frame);
                sent = true;
            }
        }
        if (!sent) {
            synchronized (pending) {
                if (pending.size() < 64) pending.add(frame);
            }
        }
    }

    private void flushPending(Conn c) {
        List<String> copy;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            copy = new ArrayList<>(pending);
            pending.clear();
        }
        for (String f : copy) c.send(f);
    }

    private final class Conn {
        final String url;
        volatile WebSocket ws;
        volatile boolean open;
        final AtomicInteger failures = new AtomicInteger();
        final StringBuilder buf = new StringBuilder();
        CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

        Conn(String url) { this.url = url; }

        void connect() {
            if (!running) return;
            try {
                http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(url), new WebSocket.Listener() {
                            @Override public void onOpen(WebSocket w) {
                                ws = w;
                                open = true;
                                failures.set(0);
                                w.request(1);
                                sendReq();
                                flushPending(Conn.this);
                                listener.onConnected(url, connected());
                            }

                            @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                                buf.append(data);
                                if (buf.length() > 1_500_000) buf.setLength(0); // runaway frame: drop it
                                else if (last) {
                                    String s = buf.toString();
                                    buf.setLength(0);
                                    listener.onMessage(url, s);
                                }
                                w.request(1);
                                return null;
                            }

                            @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                                dropped();
                                return null;
                            }

                            @Override public void onError(WebSocket w, Throwable error) {
                                dropped();
                            }
                        }).exceptionally(t -> {
                            dropped();
                            return null;
                        });
            } catch (RuntimeException e) {
                dropped();
            }
        }

        void dropped() {
            open = false;
            ws = null;
            if (!running) return;
            int n = Math.min(6, failures.incrementAndGet());
            long delay = (1L << n) + (long) (Math.random() * 3); // 2s .. 64s
            ses.schedule(this::connect, delay, TimeUnit.SECONDS);
        }

        void sendReq() {
            if (filters.isEmpty()) return;
            send("[\"REQ\",\"" + SUB + "\"," + filters + "]");
        }

        synchronized void send(String text) {
            WebSocket w = ws;
            if (w == null) return;
            tail = tail.handle((r, e) -> null).thenCompose(v -> w.sendText(text, true));
        }

        void close() {
            WebSocket w = ws;
            open = false;
            if (w != null) {
                try {
                    w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                } catch (RuntimeException ignored) {
                    // already closed
                }
            }
        }
    }
}
