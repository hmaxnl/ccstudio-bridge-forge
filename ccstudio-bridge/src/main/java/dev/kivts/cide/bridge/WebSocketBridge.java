package dev.kivts.cide.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kivts.cide.config.CideClientConfig;
import net.minecraft.client.Minecraft;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking manager for the embedded ccLink WebSocket client (spec sections
 * 2.2 and 7, phase 2). The ccLink VS Code extension runs the WebSocket server
 * on the local machine; this bridge dials it on a dedicated background thread
 * and retries a bounded number of times while the extension is not running
     * (see {@link #MAX_CONNECT_ATTEMPTS}), then stops so the user can re-press
     * Connect. Pairing is per-computer and automatic: the handshake carries a
     * deterministic UUID derived from the open computer's identity
     * ({@link CcLinkSync#pairingUuid()}), so each placed computer gets its own
     * entry in the extension's computer tree without any manual setup. Every
     * callback is wrapped in a global try-catch so no
 * failure path can crash the Minecraft client; unreachable-extension warnings
 * are routed to the log drawer.
 *
 * The ccLink protocol (spec section 7) is handled here: incoming
 * {@code {serverId, data:{command, payload}}} RPC messages are parsed on the
 * WebSocket thread and dispatched to {@link CcLinkSync} on the main thread,
 * which answers them with {@code {serverId, success, data}} and pushes
 * unsolicited events ({@code {clientId, data:{command, payload}}}) for
 * terminal output, file-change and status updates.
 */
public final class WebSocketBridge {
    public enum State { STOPPED, CONNECTING, CONNECTED, ERROR }

    private static final String HOST = "127.0.0.1";
    private static final String SUBPROTOCOL = "computercraft-link";
    private static final String CLIENT_VERSION = "0.1.0";
    private static final String CLIENT_OS = "Minecraft 1.20.1";
    private static final long CONNECT_TIMEOUT_MS = 3000;
    private static final long OPEN_POLL_MS = 500;
    private static final long INITIAL_RETRY_MS = 1500;
    private static final long MAX_RETRY_MS = 15000;
    /** Failed dials before the bridge gives up and waits for a manual Connect. */
    private static final int MAX_CONNECT_ATTEMPTS = 3;

    private static volatile State state = State.STOPPED;
    private static volatile int port = -1;
    private static volatile boolean active;
    private static volatile WebSocketClient client;
    private static Thread dialThread;
    /** Consecutive failed dials; reset on a successful connect or a fresh start(). */
    private static volatile int attempts;
    /** Pairing UUID used by the most recent dial attempt (identity of the open computer). */
    private static volatile String activeUuid = "";

    private WebSocketBridge() {}

    public static State state() {
        return state;
    }

    public static int port() {
        return port;
    }

    /** True while the bridge is dialing/connected (drives UI port locking). */
    public static boolean active() {
        return active;
    }

    /** Pairing UUID of the current dial attempt (the computer the bridge is paired to). */
    public static String activeUuid() {
        return activeUuid;
    }

    /**
     * Re-dials on the same port with the pairing identity of the currently open
     * computer. Used when the user opens a different computer's IDE while the
     * bridge is live, so the extension re-registers under the new computer's
     * UUID. Main thread.
     */
    public static synchronized void restart() {
        int p = port;
        if (p < 1) p = CideClientConfig.DEFAULT_PORT.get();
        BridgeLog.info("Re-pairing the bridge for a different computer");
        stop();
        start(p);
    }

    /** True while a live connection to the ccLink extension is open. */
    public static boolean connected() {
        WebSocketClient c = client;
        return state == State.CONNECTED && c != null && c.isOpen();
    }

    /**
     * Dials the ccLink extension at 127.0.0.1:{@code requestedPort} and keeps
     * retrying while it is unreachable. Returns false only when the port value
     * itself is invalid. Never throws.
     */
    public static synchronized boolean start(int requestedPort) {
        if (requestedPort < 1 || requestedPort > 65535) {
            BridgeLog.warn("Ignoring start request: invalid port " + requestedPort);
            return false;
        }
        if (active) {
            if (port == requestedPort) {
                BridgeLog.warn("WebSocket client is already running for port " + port + " - ignoring start request");
                return false;
            }
            // Port changed while running: restart the dialer with the new port.
            stop();
        }
        active = true;
        port = requestedPort;
        attempts = 0;
        activeUuid = CcLinkSync.pairingUuid();
        CcLinkSync.setPumpsEnabled(true);
        Thread t = dialThread;
        if (t == null || !t.isAlive()) {
            Thread dialer = new Thread(WebSocketBridge::dialLoop, "ccstudio-bridge-dialer");
            dialer.setDaemon(true);
            dialThread = dialer;
            dialer.start();
        }
        BridgeLog.info("WebSocket client starting - connecting to ws://" + HOST + ":" + port);
        return true;
    }

    /**
     * Disconnects and stops retrying. Safe to call from any thread; never throws.
     */
    public static synchronized void stop() {
        if (!active && dialThread == null) return;
        active = false;
        CcLinkSync.setPumpsEnabled(false);
        WebSocketClient c = client;
        client = null;
        Thread t = dialThread;
        dialThread = null;
        state = State.STOPPED;
        if (c != null) {
            try {
                // Deliberately non-blocking: closeBlocking() waits on a latch
                // until the remote finishes the WebSocket close handshake,
                // and a stalled extension would freeze the main thread
                // (this runs from the in-game UI button). The I/O thread
                // finishes the teardown in the background.
                c.close();
            } catch (Throwable ignored) {
                // Never crash the client from a close call.
            }
        }
        if (t != null) {
            t.interrupt();
        }
        BridgeLog.info("WebSocket client stopped - disconnected from VS Code");
    }

    /**
     * Dedicated background dialer: connect (with timeout), hold until the remote
     * closes, then retry up to {@link #MAX_CONNECT_ATTEMPTS} times before giving
     * up (the user re-presses Connect to try again). The pairing UUID and the
     * computer name are re-read from the open computer on every dial attempt
     * ({@link CcLinkSync#pairingUuid()} / {@link CcLinkSync#pairingName()}).
     */
    private static void dialLoop() {
        long retryDelay = INITIAL_RETRY_MS;
        while (active && !Thread.currentThread().isInterrupted()) {
            final String uuid = CcLinkSync.pairingUuid();
            final String name = CcLinkSync.pairingName();
            activeUuid = uuid;
            final WebSocketClient c = new WebSocketClient(URI.create("ws://" + HOST + ":" + port)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    try {
                        state = State.CONNECTED;
                        BridgeLog.info("Connected to VS Code at ws://" + HOST + ":" + port + " as \"" + name + "\"");
                        Minecraft.getInstance().execute(CcLinkSync::onConnected);
                    } catch (Throwable t) {
                        BridgeLog.error("WebSocket onOpen failed", t);
                    }
                }

                @Override
                public void onMessage(String message) {
                    try {
                        handleCcLinkMessage(message);
                    } catch (Throwable t) {
                        BridgeLog.error("ccLink message handling failed", t);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    try {
                        String r = reason == null ? "" : reason;
                        BridgeLog.info("VS Code connection closed (code " + code + (r.isEmpty() ? "" : ", " + r) + ")");
                        if (r.contains("401")) {
                            BridgeLog.warn("ccLink rejected the handshake (401): no UUID reached the extension. This is unexpected - the bridge derives a UUID from the open computer automatically.");
                        } else if (r.contains("403")) {
                            BridgeLog.warn("ccLink rejected the handshake (403): this computer is already connected by another client instance on this machine.");
                        }
                    } catch (Throwable ignored) {
                        // Never crash the client from a log callback.
                    }
                }

                @Override
                public void onError(Exception ex) {
                    try {
                        BridgeLog.warn("WebSocket connection error: " + ex);
                    } catch (Throwable ignored) {
                        // Never crash the client from a log callback.
                    }
                }
            };
            // ccLink handshake (spec section 7): subprotocol + computer pairing
            // headers. The extension auto-registers unknown UUIDs; 401/403 only
            // occur for a missing header or a UUID already in use.
            c.addHeader("Sec-WebSocket-Protocol", SUBPROTOCOL);
            c.addHeader("Authorization", uuid);
            c.addHeader("X-ccLink-Version", CLIENT_VERSION);
            c.addHeader("X-ccLink-OS", CLIENT_OS);
            c.addHeader("X-ccLink-Computer-Name", name);
            client = c;
            state = State.CONNECTING;
            boolean up = false;
            try {
                up = c.connectBlocking(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                BridgeLog.error("Connection to ws://" + HOST + ":" + port + " failed", t);
            }
            if (up) {
                attempts = 0;
                retryDelay = INITIAL_RETRY_MS;
                // Hold the loop until the remote closes or we are told to stop.
                while (active && !Thread.currentThread().isInterrupted() && c.isOpen()) {
                    sleep(OPEN_POLL_MS);
                }
            } else {
                attempts++;
                if (attempts >= MAX_CONNECT_ATTEMPTS) {
                    CcLinkSync.setPumpsEnabled(false);
                    active = false;
                    BridgeLog.warn("Could not reach VS Code after " + attempts + " attempts - stopping the bridge. Make sure the ccLink extension is running and the port matches, then press Connect to retry.");
                } else {
                    BridgeLog.warn("VS Code unreachable at ws://" + HOST + ":" + port + " - attempt " + attempts + "/" + MAX_CONNECT_ATTEMPTS + " (is the ccLink extension running?). Retrying in " + (retryDelay / 1000) + "s");
                }
            }
            state = active ? State.ERROR : State.STOPPED;
            try {
                // Non-blocking: the same latch that closeBlocking() waits on
                // only counts down when the remote answers the close frame.
                c.close();
            } catch (Throwable ignored) {
                // Closing an already-dead connection is harmless.
            }
            if (client == c) {
                client = null;
            }
            if (!active) break;
            sleep(retryDelay);
            retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
        }
        if (client != null) {
            client = null;
        }
        state = active ? State.ERROR : State.STOPPED;
    }

    // ------------------------------------------------------------- ccLink RPC

    private static final AtomicInteger NEXT_CLIENT_ID = new AtomicInteger(1);

    /**
     * Parses one inbound ccLink message on the WebSocket thread and hands it
     * to {@link CcLinkSync} on the main thread. Server RPC messages carry a
     * {@code serverId} and an {@code {command, payload}} body; client event
     * responses (acks for our own {@code clientId} messages) are logged.
     */
    private static void handleCcLinkMessage(String message) {
        String command = null;
        JsonElement payload = null;
        int serverId = -1;
        try {
            JsonObject root = JsonParser.parseString(message).getAsJsonObject();
            if (root.has("serverId") && root.get("serverId").isJsonPrimitive()) {
                serverId = root.get("serverId").getAsInt();
                JsonElement dataElement = root.get("data");
                if (dataElement != null && dataElement.isJsonObject()) {
                    JsonObject data = dataElement.getAsJsonObject();
                    if (data.has("command") && data.get("command").isJsonPrimitive()) {
                        command = data.get("command").getAsString();
                    }
                    if (data.has("payload")) {
                        payload = data.get("payload");
                    }
                }
            } else if (root.has("clientId") && root.get("clientId").isJsonPrimitive()) {
                BridgeLog.sync("ccLink event ack (clientId " + root.get("clientId").getAsInt() + "): "
                    + (root.has("success") ? root.get("success").getAsString() : "?"));
                return;
            } else {
                BridgeLog.warn("Unrecognized ccLink message - ignoring");
                return;
            }
        } catch (Throwable t) {
            BridgeLog.warn("ccLink message parse failed: " + t);
            return;
        }
        if (command == null) {
            BridgeLog.warn("ccLink RPC without a command - ignoring");
            return;
        }
        final String cmd = command;
        final JsonElement pl = payload;
        final int id = serverId;
        BridgeLog.sync("ccLink RPC: " + cmd);
        Minecraft.getInstance().execute(() -> CcLinkSync.dispatch(cmd, pl, id));
    }

    /**
     * Sends a ccLink response envelope {@code {serverId, success, data}}
     * (spec section 7). Main thread.
     */
    public static void respond(int serverId, boolean success, JsonElement data) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("serverId", serverId);
        envelope.addProperty("success", success);
        // A null payload means "void": the key must be omitted entirely (as the
        // upstream Lua client does) - the extension parses void responses with
        // z.void(), which only accepts the key's absence, not a string.
        if (data != null) envelope.add("data", data);
        send(envelope.toString());
    }

    /**
     * Sends an unsolicited ccLink event {@code {clientId, data:{command, payload}}}
     * (spec section 7) — the extension parses this as a request and acks it with
     * a {@code clientId} message. Main thread.
     */
    public static void sendEvent(String command, JsonElement payload) {
        JsonObject data = new JsonObject();
        data.addProperty("command", command);
        if (payload != null) data.add("payload", payload);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("clientId", NEXT_CLIENT_ID.getAndIncrement());
        envelope.add("data", data);
        send(envelope.toString());
    }

    private static void send(String text) {
        WebSocketClient c = client;
        if (c == null || !c.isOpen()) {
            BridgeLog.warn("Dropped ccLink response - connection is not open");
            return;
        }
        try {
            c.send(text);
        } catch (Throwable t) {
            BridgeLog.error("Failed to send ccLink response", t);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
