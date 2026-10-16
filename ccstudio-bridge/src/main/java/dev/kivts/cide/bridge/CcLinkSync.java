package dev.kivts.cide.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dan200.computercraft.core.terminal.Palette;
import dan200.computercraft.core.terminal.TextBuffer;
import dan200.computercraft.shared.computer.terminal.NetworkedTerminal;
import dan200.computercraft.shared.computer.terminal.TerminalState;
import dev.kivts.cide.client.BridgePanel;
import dev.kivts.cide.client.CideClient;
import dev.kivts.cide.client.CideScreen;
import dev.kivts.cide.config.CideClientConfig;
import dev.kivts.cide.net.CidePackets;
import dev.kivts.cide.net.payload.ConsoleActionPayload;
import dev.kivts.cide.net.payload.ConsoleInputPayload;
import dev.kivts.cide.net.payload.ConsolePollPayload;
import dev.kivts.cide.net.payload.ConsoleStatePayload;
import dev.kivts.cide.net.payload.CreateFolderPayload;
import dev.kivts.cide.net.payload.DeleteFilePayload;
import dev.kivts.cide.net.payload.FileContentChunkPayload;
import dev.kivts.cide.net.payload.FileListPayload;
import dev.kivts.cide.net.payload.ListFilesPayload;
import dev.kivts.cide.net.payload.OperationResultPayload;
import dev.kivts.cide.net.payload.PeripheralScanPayload;
import dev.kivts.cide.net.payload.ReadFilePayload;
import dev.kivts.cide.net.payload.RenameFilePayload;
import dev.kivts.cide.net.payload.WriteFilePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ccLink RPC/file-sync adapter (spec sections 3 and 7, phase 4). Translates the
 * ccLink VS Code extension's filesystem/shell RPC commands into CIDE protocol
 * payloads addressed to the computer whose IDE screen is open, correlates the
 * server results (which carry no request id) with the outstanding RPC, and
 * pushes fileChange/listFiles/writeTerminal/setStatus events back over the
 * WebSocket bridge.
 *
 * The single-player connection is ordered and the server never sends a request
 * id, so at most one bridge operation is in flight at a time: results are
 * attributed to the queue head, matched by path where possible. All state is
 * touched only on the Minecraft client thread (WebSocket callbacks are
 * marshaled there by {@link WebSocketBridge}, network results arrive through
 * {@link CideClient}, and the pumps run on the client tick event).
 */
public final class CcLinkSync {
    private static final int RPC_TIMEOUT_MS = 4000;      // the extension times out at 5s
    private static final int SHELL_POLL_TICKS = 25;      // 250ms, matches the in-game IDE console
    private static final int WATCH_POLL_TICKS = 40;      // 2s
    private static final int STUB_SCAN_TICKS = 100;      // 5s, re-scrape attached peripherals for VS Code stubs
    private static final int WATCH_SNAP_TTL_MS = 1000;
    private static final int WALK_MAX_FILES = 1000;
    private static final int WALK_MAX_DIRS = 1000;
    private static final int WALK_MAX_DEPTH = 12;
    private static final long TREE_REFRESH_COOLDOWN_MS = 2000;

    // ccLink/VS Code protocol constants
    private static final int STATUS_CONNECTED = 0;
    private static final int STATUS_RUNNING = 2;
    private static final int FILE_TYPE_FILE = 1;
    private static final int FILE_TYPE_DIR = 2;
    private static final int PERM_READONLY = 1;
    // Must match vscode.FileChangeType: Created=1, Changed=2, Deleted=3
    private static final int CHANGE_CREATED = 1;
    private static final int CHANGE_CHANGED = 2;
    private static final int CHANGE_DELETED = 3;
    private static final int KEY_ENTER = 257;
    private static final int KEY_TAB = 258;
    private static final int KEY_BACKSPACE = 259;

    private static final String INACTIVE_MSG = "Computer is locked/inactive. Interact with the block in-game to enable live sync.";

    // Active computer (the IDE screen). targetPos / targetComputerId / lastLabel
    // are also read from the dialer thread (pairing identity), hence volatile.
    private static volatile BlockPos targetPos;
    private static volatile int targetComputerId = -1;
    private static UUID targetSession;
    private static volatile String lastLabel = "";
    private static volatile String lastType = "";

    /** Computer id of the server-wide /cide admin view (matches CideMod/CideFileService). */
    private static final int ADMIN_COMPUTER_ID = -1;

    /**
     * Deterministic ccLink pairing identities. CC:T's own per-run instance
     * UUIDs are not persisted, so each computer's identity is derived from
     * what survives server restarts: block position (placed computers),
     * owning player + computer id (pockets), a fixed key (admin view), or the
     * bridge key itself (no computer open). The extension registers one entry
     * per UUID, so every placed computer keeps its own entry in VS Code.
     */
    public static final String BRIDGE_UUID = pairingUuidKey("ccstudio-bridge");
    public static final String BRIDGE_NAME = "CC-Studio Bridge";
    private static final String ADMIN_UUID = pairingUuidKey("ccstudio-bridge:admin");

    private static String pairingUuidKey(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Pairing UUID for the currently open computer (or the idle bridge). */
    public static String pairingUuid() {
        BlockPos pos = targetPos;
        int id = targetComputerId;
        if (pos == null) return BRIDGE_UUID; // no computer's IDE is open
        if (id == ADMIN_COMPUTER_ID) return ADMIN_UUID; // /cide admin view
        if (pos.equals(BlockPos.ZERO)) {
            return pairingUuidKey("ccstudio-bridge:pocket:" + localPlayerUuid() + ":" + id);
        }
        return pairingUuidKey("ccstudio-bridge:" + pos.toShortString());
    }

    /**
     * Display name of the computer entry in the extension's computer tree.
     * Includes the computer type (so an Advanced Computer is not confused with a
     * Pocket Computer) and, for block computers, the block position (so two
     * computers of the same type are distinguishable). A user-set label wins as
     * the leading word.
     */
    public static String pairingName() {
        BlockPos pos = targetPos;
        int id = targetComputerId;
        if (pos == null) return BRIDGE_NAME; // no computer's IDE is open
        if (id == ADMIN_COMPUTER_ID) return "CIDE Admin";
        String type = lastType == null || lastType.isEmpty() ? "Computer" : lastType;
        String label = lastLabel;
        String name = label == null || label.isEmpty() ? type : label + " (" + type + ")";
        if (!pos.equals(BlockPos.ZERO)) {
            name += " @ " + pos.toShortString();
        }
        return name;
    }

    private static String localPlayerUuid() {
        try {
            net.minecraft.world.entity.player.Player player = Minecraft.getInstance().player;
            return player == null ? "no-player" : player.getUUID().toString();
        } catch (Throwable t) {
            return "no-player";
        }
    }

    // Single in-flight operation + wait queue. Main thread only.
    private static final Deque<Op> QUEUE = new ArrayDeque<>();
    private static Op head;

    // Open shells (terminal tabs in VS Code). Main thread only.
    private static final Map<Integer, Shell> SHELLS = new HashMap<>();
    private static int nextShellId = 1;
    private static int shellPollIndex = 0;

    // Watched files: parent directory -> file names. Main thread only.
    private static final Map<String, Set<String>> WATCHED = new LinkedHashMap<>();
    private static final Map<String, Map<String, long[]>> SNAPS = new HashMap<>();
    private static final Map<String, Long> SNAP_TIME = new HashMap<>();
    private static int watchIndex = 0;

    private static long lastTreeRefreshMs = 0;
    private static int tickCount = 0;

    private static final CideClient.ResultListener LISTENER = new CideClient.ResultListener() {
        @Override public void onOperation(OperationResultPayload p) { resultOperation(p); }
        @Override public void onFileList(FileListPayload p) { resultFileList(p); }
        @Override public void onFileContentChunk(FileContentChunkPayload p) { resultChunk(p); }
        @Override public void onConsoleState(ConsoleStatePayload p) { resultConsoleState(p); }
    };

    static {
        CideClient.addResultListener(LISTENER);
    }

    private CcLinkSync() {}

    // ------------------------------------------------------------- lifecycle

    /** Registers the client-tick pumps; called when the bridge starts. */
    public static void setPumpsEnabled(boolean enabled) {
        if (enabled) {
            MinecraftForge.EVENT_BUS.register(PUMP);
        } else {
            MinecraftForge.EVENT_BUS.unregister(PUMP);
        }
    }

    private static final Object PUMP = new Object() {
        @SubscribeEvent
        public void onClientTickPost(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            try {
                tick();
            } catch (Throwable t) {
                BridgeLog.error("ccLink tick pump failed", t);
            }
        }
    };

    /** The ccLink WebSocket connected; report the computer status. */
    public static void onConnected() {
        sendStatus(targetPos != null && targetComputerId >= 0 ? STATUS_RUNNING : STATUS_CONNECTED);
    }

    // ------------------------------------------------------ screen lifecycle

    /**
     * The in-game IDE screen became active (spec section 3.1): the computer is
     * now ACTIVE and remote RPCs are allowed. Idempotent while the same
     * computer stays open (screen resizes re-run init).
     */
    public static void onScreenActivated(CideScreen screen) {
        BlockPos pos = screen.bridgePos();
        int id = screen.bridgeComputerId();
        boolean same = pos != null && pos.equals(targetPos) && id == targetComputerId;
        targetPos = pos;
        targetComputerId = id;
        targetSession = screen.bridgeSessionId();
        String label = screen.bridgeLabel();
        lastLabel = label == null ? "" : label.trim();
        lastType = screen.bridgeType() == null ? "" : screen.bridgeType().trim();
        BridgePanel.setSession(true, pairingName());
        // Opening a computer should keep it visible in VS Code. If the bridge is
        // paired to a different computer (or none), re-pair it so each computer
        // gets its own entry in the extension's computer tree.
        if (WebSocketBridge.active()) {
            if (!pairingUuid().equals(WebSocketBridge.activeUuid())) {
                WebSocketBridge.restart();
            }
        } else if (CideClientConfig.AUTO_START_SERVER.get()) {
            WebSocketBridge.start(CideClientConfig.DEFAULT_PORT.get());
        }
        // Re-scrape attached peripherals so the VS Code stub file matches what
        // is physically attached (may have changed while the screen was closed).
        if (WebSocketBridge.connected()) requestStubScan();
        if (same) return;

        for (Shell shell : new ArrayList<>(SHELLS.values())) {
            if (shell.computerId != id) {
                sendKillTerminal(shell.id);
                SHELLS.remove(shell.id);
            }
        }
        clearWatches();
        if (WebSocketBridge.connected()) sendStatus(STATUS_RUNNING);
        BridgeLog.sync("ccLink session active: " + pairingName());
    }

    /** The IDE screen closed: the computer is INACTIVE, RPCs are rejected. */
    public static void onScreenDeactivated() {
        if (targetPos == null && targetComputerId < 0 && SHELLS.isEmpty()) return;
        for (Shell shell : SHELLS.values()) sendKillTerminal(shell.id);
        SHELLS.clear();
        clearWatches();
        targetPos = null;
        targetComputerId = -1;
        targetSession = null;
        BridgePanel.setSession(false, "");
        if (WebSocketBridge.connected()) sendStatus(STATUS_CONNECTED);
        BridgeLog.sync("ccLink session inactive");
    }

    private static void clearWatches() {
        WATCHED.clear();
        SNAPS.clear();
        SNAP_TIME.clear();
    }

    /**
     * Asks the server to re-scrape the active computer's peripherals and
     * (re)generate its stub file. Fire-and-forget: the server replies only by
     * updating the drive, which the watch poll picks up as a file change.
     */
    private static void requestStubScan() {
        if (targetPos == null || targetComputerId < 0) return;
        try {
            CidePackets.sendToServer(new PeripheralScanPayload(targetPos, targetComputerId));
        } catch (Throwable t) {
            BridgeLog.error("stub scan request failed", t);
        }
    }

    // ------------------------------------------------------------- RPC entry

    /**
     * Dispatches one ccLink RPC command from the extension (spec section 7).
     * Must run on the client thread.
     */
    public static void dispatch(String command, JsonElement payloadEl, int serverId) {
        try {
            JsonObject payload = payloadEl != null && payloadEl.isJsonObject() ? payloadEl.getAsJsonObject() : new JsonObject();
            if ("ping".equals(command)) {
                respondOk(serverId, payload.isJsonPrimitive() ? payload : new JsonPrimitive(0));
                return;
            }
            if (targetPos == null || targetComputerId < 0) {
                respondErr(serverId, "[WARN] Save rejected for " + pairingName() + ": " + INACTIVE_MSG);
                return;
            }
            switch (command) {
                case "writeFile":
                    opWrite(serverId, payload);
                    break;
                case "readFile":
                    opRead(serverId, payload);
                    break;
                case "readDirectory":
                    opReadDirectory(serverId, payload);
                    break;
                case "stat":
                    opStat(serverId, payload);
                    break;
                case "copyFile":
                    opCopy(serverId, payload);
                    break;
                case "rename":
                    opRename(serverId, payload);
                    break;
                case "delete":
                    opDelete(serverId, payload);
                    break;
                case "createDirectory":
                    opCreateDirectory(serverId, payload);
                    break;
                case "listFiles":
                    opListAll(serverId);
                    break;
                case "watch":
                    opWatch(payload);
                    respondOk(serverId, null);
                    break;
                case "stopWatch":
                    opStopWatch(payload);
                    respondOk(serverId, null);
                    break;
                case "createShell":
                    opCreateShell(serverId);
                    break;
                case "readyShell": {
                    int shellId = payload.has("shellID") && payload.get("shellID").isJsonPrimitive()
                        ? payload.get("shellID").getAsInt() : -1;
                    Shell shell = SHELLS.get(shellId);
                    if (shell != null) {
                        // The extension clears the screen when the terminal opens,
                        // right before this RPC: force a fresh full re-render and
                        // request the terminal state now so the boot screen shows.
                        shell.firstRender = true;
                        CidePackets.sendToServer(new ConsolePollPayload(shell.pos, shell.computerId, shell.session));
                    }
                    respondOk(serverId, null);
                    break;
                }
                case "resizeShell":
                    respondOk(serverId, null);
                    break;
                case "keyToShell":
                    opKeyToShell(payload);
                    respondOk(serverId, null);
                    break;
                case "killShell":
                    opKillShell(payload);
                    respondOk(serverId, null);
                    break;
                case "restart":
                    opRestart(serverId);
                    break;
                default:
                    respondErr(serverId, "unsupported ccLink command: " + command);
            }
        } catch (Throwable t) {
            BridgeLog.error("ccLink RPC dispatch failed: " + command, t);
            respondErr(serverId, "internal bridge error: " + t);
        }
    }

    // ------------------------------------------------- filesystem operations

    private static void opWrite(int serverId, JsonObject p) {
        final String path = normalize(str(p, "path"));
        final String content = str(p, "content");
        startOp(new RpcOp(serverId, path) {
            @Override public void begin() {
                CidePackets.sendToServer(new WriteFilePayload(targetPos, targetComputerId, path, content));
            }
            @Override public void onOperation(OperationResultPayload r) {
                if (r.ok()) {
                    ok();
                    scheduleTreeRefresh();
                } else {
                    fail(r.message());
                }
            }
        });
    }

    private static void opRead(int serverId, JsonObject p) {
        // Normalized: server results are matched against expectPath and the
        // server echoes its sanitized path, so a raw "/x" would never match.
        final String path = normalize(str(p, "path"));
        startOp(new RpcOp(serverId, path) {
            private Map<Integer, byte[]> chunks;
            private int total = 0;

            @Override public void begin() {
                CidePackets.sendToServer(new ReadFilePayload(targetPos, targetComputerId, path));
            }
            @Override public void onChunk(FileContentChunkPayload c) {
                if (chunks == null || c.totalChunks() != total) {
                    chunks = new HashMap<>();
                    total = c.totalChunks();
                }
                chunks.put(c.chunkIndex(), c.data());
                if (chunks.size() == total) {
                    ok(new JsonPrimitive(joinChunks(chunks, total)));
                }
            }
            @Override public void onOperation(OperationResultPayload r) {
                if (r.ok()) ok(new JsonPrimitive(""));
                else fail(r.message());
            }
        });
    }

    private static void opReadDirectory(int serverId, JsonObject p) {
        final String path = normalize(str(p, "path"));
        startOp(new RpcOp(serverId, path) {
            @Override public void begin() {
                CidePackets.sendToServer(new ListFilesPayload(targetPos, targetComputerId, path));
            }
            @Override public void onFileList(FileListPayload l) {
                JsonArray out = new JsonArray();
                for (FileListPayload.Entry e : l.entries()) {
                    JsonArray item = new JsonArray();
                    item.add(new JsonPrimitive(lastSegment(e.path())));
                    item.add(new JsonPrimitive(e.directory() ? FILE_TYPE_DIR : FILE_TYPE_FILE));
                    out.add(item);
                }
                ok(out);
            }
            @Override public void onOperation(OperationResultPayload r) {
                if (r.ok()) ok(new JsonArray());
                else fail(r.message());
            }
        });
    }

    private static void opStat(int serverId, JsonObject p) {
        final String path = normalize(str(p, "path"));
        final int slash = path.lastIndexOf('/');
        final String dir = slash < 0 ? "" : path.substring(0, slash);
        final String name = slash < 0 ? path : path.substring(slash + 1);
        startOp(new RpcOp(serverId, dir) {
            @Override public void begin() {
                CidePackets.sendToServer(new ListFilesPayload(targetPos, targetComputerId, dir));
            }
            @Override public void onFileList(FileListPayload l) {
                for (FileListPayload.Entry e : l.entries()) {
                    if (!lastSegment(e.path()).equals(name)) continue;
                JsonObject out = new JsonObject();
                out.addProperty("type", e.directory() ? FILE_TYPE_DIR : FILE_TYPE_FILE);
                out.addProperty("ctime", 0);
                out.addProperty("mtime", 0);
                out.addProperty("size", e.size());
                // Omitted when writable: the extension parses permissions with
                // z.nativeEnum(vscode.FilePermission) (1|2|4), so a 0 value is
                // rejected even though the field is optional.
                if (e.readOnly()) out.addProperty("permissions", PERM_READONLY);
                ok(out);
                    return;
                }
                fail("file not found: " + path);
            }
            @Override public void onOperation(OperationResultPayload r) {
                fail(r.ok() ? "file not found: " + path : r.message());
            }
        });
    }

    private static void opCopy(int serverId, JsonObject p) {
        final String src = normalize(str(p, "source"));
        final String dst = normalize(str(p, "destination"));
        final boolean overwrite = p.has("overwrite") && p.get("overwrite").isJsonPrimitive() && p.get("overwrite").getAsBoolean();
        final int slash = dst.lastIndexOf('/');
        final String dstDir = slash < 0 ? "" : dst.substring(0, slash);
        final String dstName = slash < 0 ? dst : dst.substring(slash + 1);
        startOp(new RpcOp(serverId, dstDir) {
            private int step;
            private Map<Integer, byte[]> chunks;
            private int total = 0;

            @Override public void begin() {
                deadline = now() + RPC_TIMEOUT_MS;
                CidePackets.sendToServer(new ListFilesPayload(targetPos, targetComputerId, dstDir));
            }
            @Override public void onFileList(FileListPayload l) {
                if (step != 0) return;
                for (FileListPayload.Entry e : l.entries()) {
                    if (lastSegment(e.path()).equals(dstName)) {
                        if (!overwrite) {
                            fail("file already exists: " + dst);
                            return;
                        }
                        break;
                    }
                }
                step = 1;
                expectPath = src;
                deadline = now() + RPC_TIMEOUT_MS;
                CidePackets.sendToServer(new ReadFilePayload(targetPos, targetComputerId, src));
            }
            @Override public void onChunk(FileContentChunkPayload c) {
                if (step != 1) return;
                if (chunks == null || c.totalChunks() != total) {
                    chunks = new HashMap<>();
                    total = c.totalChunks();
                }
                chunks.put(c.chunkIndex(), c.data());
                if (chunks.size() == total) {
                    step = 2;
                    expectPath = dst;
                    deadline = now() + RPC_TIMEOUT_MS;
                    CidePackets.sendToServer(new WriteFilePayload(targetPos, targetComputerId, dst, joinChunks(chunks, total)));
                }
            }
            @Override public void onOperation(OperationResultPayload r) {
                if (step == 2 && r.ok()) {
                    ok();
                    scheduleTreeRefresh();
                } else {
                    fail(r.ok() ? "copy failed" : r.message());
                }
            }
        });
    }

    private static void opRename(int serverId, JsonObject p) {
        final String oldPath = normalize(str(p, "oldPath"));
        final String newPath = normalize(str(p, "newPath"));
        final boolean overwrite = p.has("overwrite") && p.get("overwrite").isJsonPrimitive() && p.get("overwrite").getAsBoolean();
        final int oldSlash = oldPath.lastIndexOf('/');
        final int newSlash = newPath.lastIndexOf('/');
        final String oldDir = oldSlash < 0 ? "" : oldPath.substring(0, oldSlash);
        final String newDir = newSlash < 0 ? "" : newPath.substring(0, newSlash);
        final String newName = newSlash < 0 ? newPath : newPath.substring(newSlash + 1);

        if (oldDir.equals(newDir)) {
            startOp(new RpcOp(serverId, newPath) {
                @Override public void begin() {
                    CidePackets.sendToServer(new RenameFilePayload(targetPos, targetComputerId, oldPath, newName));
                }
                @Override public void onOperation(OperationResultPayload r) {
                    if (r.ok()) {
                        ok();
                        scheduleTreeRefresh();
                    } else {
                        fail(r.message());
                    }
                }
            });
            return;
        }

        // Cross-directory rename: the server rename is same-directory only, so
        // emulate it with read + write + delete.
        startOp(new RpcOp(serverId, newDir) {
            private int step;
            private Map<Integer, byte[]> chunks;
            private int total = 0;

            @Override public void begin() {
                deadline = now() + RPC_TIMEOUT_MS;
                CidePackets.sendToServer(new ListFilesPayload(targetPos, targetComputerId, newDir));
            }
            @Override public void onFileList(FileListPayload l) {
                if (step != 0) return;
                for (FileListPayload.Entry e : l.entries()) {
                    if (lastSegment(e.path()).equals(newName)) {
                        if (!overwrite) {
                            fail("file already exists: " + newPath);
                            return;
                        }
                        break;
                    }
                }
                step = 1;
                expectPath = oldPath;
                deadline = now() + RPC_TIMEOUT_MS;
                CidePackets.sendToServer(new ReadFilePayload(targetPos, targetComputerId, oldPath));
            }
            @Override public void onChunk(FileContentChunkPayload c) {
                if (step != 1) return;
                if (chunks == null || c.totalChunks() != total) {
                    chunks = new HashMap<>();
                    total = c.totalChunks();
                }
                chunks.put(c.chunkIndex(), c.data());
                if (chunks.size() == total) {
                    step = 2;
                    expectPath = newPath;
                    deadline = now() + RPC_TIMEOUT_MS;
                    CidePackets.sendToServer(new WriteFilePayload(targetPos, targetComputerId, newPath, joinChunks(chunks, total)));
                }
            }
            @Override public void onOperation(OperationResultPayload r) {
                if (step == 2) {
                    if (!r.ok()) {
                        fail(r.message());
                        return;
                    }
                    step = 3;
                    expectPath = oldPath;
                    deadline = now() + RPC_TIMEOUT_MS;
                    CidePackets.sendToServer(new DeleteFilePayload(targetPos, targetComputerId, oldPath));
                } else if (step == 3) {
                    if (r.ok()) {
                        ok();
                        scheduleTreeRefresh();
                    } else {
                        fail(r.message());
                    }
                } else {
                    fail(r.ok() ? "rename failed" : r.message());
                }
            }
        });
    }

    private static void opDelete(int serverId, JsonObject p) {
        final String path = normalize(str(p, "path"));
        final boolean recursive = p.has("recursive") && p.get("recursive").isJsonPrimitive() && p.get("recursive").getAsBoolean();
        if (!recursive) {
            startOp(new RpcOp(serverId, path) {
                @Override public void begin() {
                    CidePackets.sendToServer(new DeleteFilePayload(targetPos, targetComputerId, path));
                }
                @Override public void onOperation(OperationResultPayload r) {
                    if (r.ok()) {
                        ok();
                        scheduleTreeRefresh();
                    } else {
                        fail(r.message());
                    }
                }
            });
            return;
        }
        // Recursive delete: collect the directory contents, then delete files
        // first and directories deepest-first, finishing with the directory.
        startOp(new WalkOp(serverId, path, WALK_MODE_DELETE));
    }

    private static void opCreateDirectory(int serverId, JsonObject p) {
        final String path = normalize(str(p, "path"));
        startOp(new RpcOp(serverId, path) {
            @Override public void begin() {
                CidePackets.sendToServer(new CreateFolderPayload(targetPos, targetComputerId, path));
            }
            @Override public void onOperation(OperationResultPayload r) {
                if (r.ok()) {
                    ok();
                    scheduleTreeRefresh();
                } else {
                    fail(r.message());
                }
            }
        });
    }

    private static final int WALK_MODE_LIST = 0;
    private static final int WALK_MODE_EVENT = 1;
    private static final int WALK_MODE_DELETE = 2;

    private static void opListAll(int serverId) {
        startOp(new WalkOp(serverId, "", WALK_MODE_LIST));
    }

    /**
     * Bounded breadth-first walk of the computer drive. The walk is a single
     * queue operation that sends one list request at a time and processes the
     * results as they arrive, so it composes with the single-in-flight rule.
     */
    private static final class WalkOp extends RpcOp {
        private final int mode;
        private final String root;
        private final Deque<String[]> todo = new ArrayDeque<>();
        private final List<String> files = new ArrayList<>();
        private final List<String> dirs = new ArrayList<>(); // "depth|path"
        private int pending = 0;
        private int curDepth;

        WalkOp(int serverId, String root, int mode) {
            super(serverId, "");
            this.root = root;
            this.mode = mode;
            todo.add(new String[] { root, "0" });
        }

        @Override public void begin() {
            startNext();
        }

        private void startNext() {
            if (todo.isEmpty()) {
                finish();
                return;
            }
            pending++;
            String[] next = todo.poll();
            curDepth = Integer.parseInt(next[1]);
            expectPath = next[0];
            deadline = now() + RPC_TIMEOUT_MS;
            CidePackets.sendToServer(new ListFilesPayload(targetPos, targetComputerId, next[0]));
        }

        @Override public void onFileList(FileListPayload l) {
            for (FileListPayload.Entry e : l.entries()) {
                int entryDepth = curDepth + 1;
                if (e.directory()) {
                    if (entryDepth <= WALK_MAX_DEPTH && dirs.size() + files.size() < WALK_MAX_DIRS + WALK_MAX_FILES) {
                        dirs.add(entryDepth + "|" + e.path());
                        todo.add(new String[] { e.path(), String.valueOf(entryDepth) });
                    }
                } else if (files.size() < WALK_MAX_FILES) {
                    files.add(e.path());
                }
            }
            pending--;
            if (pending == 0 && todo.isEmpty()) finish();
            else startNext();
        }

        @Override public void onOperation(OperationResultPayload r) {
            if (serverId >= 0) fail(r.ok() ? "list failed: " + root : r.message());
            else done = true;
        }

        private void finish() {
            if (mode == WALK_MODE_DELETE) {
                List<String> ordered = new ArrayList<>(files);
                dirs.sort((a, b) ->
                    Integer.parseInt(b.substring(0, b.indexOf('|'))) - Integer.parseInt(a.substring(0, a.indexOf('|'))));
                for (String d : dirs) ordered.add(d.substring(d.indexOf('|') + 1));
                ordered.add(root);
                done = true;
                startOp(new DeleteChainOp(serverId, ordered));
                return;
            }
            JsonArray out = new JsonArray();
            for (String f : files) out.add(new JsonPrimitive(f));
            if (mode == WALK_MODE_EVENT) {
                done = true;
                WebSocketBridge.sendEvent("listFiles", out);
            } else {
                ok(out);
            }
        }
    }

    /** Deletes a list of paths one after another; the last result answers the RPC. */
    private static final class DeleteChainOp extends RpcOp {
        private final List<String> paths;
        private int idx;

        DeleteChainOp(int serverId, List<String> paths) {
            super(serverId, paths.isEmpty() ? "" : paths.get(0));
            this.paths = paths;
        }

        @Override public void begin() {
            if (paths.isEmpty()) {
                ok();
                return;
            }
            sendDelete(0);
        }

        private void sendDelete(int i) {
            idx = i;
            expectPath = paths.get(i);
            deadline = now() + RPC_TIMEOUT_MS;
            CidePackets.sendToServer(new DeleteFilePayload(targetPos, targetComputerId, paths.get(i)));
        }

        @Override public void onOperation(OperationResultPayload r) {
            if (!r.ok()) {
                fail(r.message());
                return;
            }
            if (idx + 1 < paths.size()) sendDelete(idx + 1);
            else {
                ok();
                scheduleTreeRefresh();
            }
        }
    }

    // ------------------------------------------------------ watch operations

    private static void opWatch(JsonObject p) {
        String path = normalize(str(p, "path"));
        if (path.isEmpty()) return;
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash);
        String name = slash < 0 ? path : path.substring(slash + 1);
        WATCHED.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(name);
    }

    private static void opStopWatch(JsonObject p) {
        String path = normalize(str(p, "path"));
        if (path.isEmpty()) return;
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash);
        String name = slash < 0 ? path : path.substring(slash + 1);
        Set<String> names = WATCHED.get(parent);
        if (names != null) {
            names.remove(name);
            if (names.isEmpty()) WATCHED.remove(parent);
        }
        SNAPS.remove(parent);
        SNAP_TIME.remove(parent);
    }

    /** One watch cycle: refresh one stale parent snapshot and diff watched files. */
    private static final class WatchOp extends Op {
        private final String parent;
        private final String[] watched;

        WatchOp(String parent, Set<String> names) {
            this.parent = parent;
            this.watched = names.toArray(new String[0]);
            expectPath = parent;
            deadline = now() + RPC_TIMEOUT_MS;
        }

        @Override public void begin() {
            CidePackets.sendToServer(new ListFilesPayload(targetPos, targetComputerId, parent));
        }

        @Override public void onFileList(FileListPayload l) {
            done = true;
            Map<String, long[]> next = new HashMap<>();
            for (FileListPayload.Entry e : l.entries()) {
                next.put(lastSegment(e.path()), new long[] { e.size(), e.directory() ? 1 : 0 });
            }
            Map<String, long[]> prev = SNAPS.get(parent);
            if (prev == null) {
                // First snapshot after watch registration is the baseline.
                SNAPS.put(parent, next);
                SNAP_TIME.put(parent, now());
                return;
            }
            JsonArray changes = null;
            for (String w : watched) {
                long[] a = prev.get(w);
                long[] b = next.get(w);
                int type = -1;
                if (a == null && b != null) type = CHANGE_CREATED;
                else if (a != null && b == null) type = CHANGE_DELETED;
                else if (a != null && b != null && (a[0] != b[0] || a[1] != b[1])) type = CHANGE_CHANGED;
                if (type < 0) continue;
                if (changes == null) changes = new JsonArray();
                JsonObject change = new JsonObject();
                change.addProperty("type", type);
                change.addProperty("path", "/" + (parent.isEmpty() ? w : parent + "/" + w));
                changes.add(change);
            }
            SNAPS.put(parent, next);
            SNAP_TIME.put(parent, now());
            if (changes != null && !changes.isEmpty()) {
                WebSocketBridge.sendEvent("fileChange", changes);
                scheduleTreeRefresh();
            }
        }

        @Override public void onOperation(OperationResultPayload r) {
            // A denied list (locked computer, offline, ...) just skips this cycle.
            done = true;
        }
    }

    // --------------------------------------------------------- shell support

    private static final class Shell {
        final int id;
        final UUID session;
        final BlockPos pos;
        final int computerId;
        String[] lastLines;
        int lastWidth = -1;
        int lastHeight = -1;
        int lastCursorX = -1;
        int lastCursorY = -1;
        boolean firstRender = true;

        Shell(int id, UUID session, BlockPos pos, int computerId) {
            this.id = id;
            this.session = session;
            this.pos = pos;
            this.computerId = computerId;
        }
    }

    private static void opCreateShell(int serverId) {
        if (targetSession == null) {
            respondErr(serverId, "no active IDE session - open the computer's IDE in-game first");
            return;
        }
        int id = nextShellId++;
        SHELLS.put(id, new Shell(id, targetSession, targetPos, targetComputerId));
        JsonObject bg = new JsonObject();
        bg.addProperty("r", 11);
        bg.addProperty("g", 15);
        bg.addProperty("b", 20);
        JsonObject out = new JsonObject();
        out.addProperty("shellID", id);
        out.add("backgroundColor", bg);
        respondOk(serverId, out);
        BridgeLog.sync("ccLink shell " + id + " opened for " + pairingName());
    }

    private static void opKeyToShell(JsonObject p) {
        int shellId = p.has("shellID") && p.get("shellID").isJsonPrimitive() ? p.get("shellID").getAsInt() : -1;
        Shell shell = SHELLS.get(shellId);
        if (shell == null) return;
        String key = str(p, "key");
        if (key.isEmpty()) return;
        if (key.charAt(0) == 0x1B) return; // escape sequences (arrows, queries): unsupported
        if (key.length() == 1) {
            int cp = key.charAt(0);
            switch (cp) {
                case '\r':
                case '\n':
                    sendConsoleInput(shell, ConsoleInputPayload.KEY_DOWN, KEY_ENTER);
                    sendConsoleInput(shell, ConsoleInputPayload.KEY_UP, KEY_ENTER);
                    break;
                case '\t':
                    sendConsoleInput(shell, ConsoleInputPayload.KEY_DOWN, KEY_TAB);
                    sendConsoleInput(shell, ConsoleInputPayload.KEY_UP, KEY_TAB);
                    break;
                case '\b':
                case 0x7F:
                    sendConsoleInput(shell, ConsoleInputPayload.KEY_DOWN, KEY_BACKSPACE);
                    sendConsoleInput(shell, ConsoleInputPayload.KEY_UP, KEY_BACKSPACE);
                    break;
                default:
                    if (cp >= 0x20) sendConsoleInput(shell, ConsoleInputPayload.CHAR, cp);
            }
            return;
        }
        CidePackets.sendToServer(new ConsoleInputPayload(shell.pos, shell.computerId, shell.session, ConsoleInputPayload.PASTE, 0, 0, 0, 0, key));
    }

    private static void sendConsoleInput(Shell shell, int action, int code) {
        CidePackets.sendToServer(new ConsoleInputPayload(shell.pos, shell.computerId, shell.session, action, code, 0, 0, 0, ""));
    }

    private static void opKillShell(JsonObject p) {
        int shellId = p.has("shellID") && p.get("shellID").isJsonPrimitive() ? p.get("shellID").getAsInt() : -1;
        Shell shell = SHELLS.remove(shellId);
        if (shell != null) BridgeLog.sync("ccLink shell " + shellId + " closed");
        // The console session itself is owned by the IDE screen; do not close it.
    }

    private static void opRestart(int serverId) {
        if (targetSession == null) {
            respondErr(serverId, "no active IDE session - open the computer's IDE in-game first");
            return;
        }
        CidePackets.sendToServer(new ConsoleActionPayload(targetPos, targetComputerId, targetSession, ConsoleActionPayload.REBOOT));
        respondOk(serverId, null);
    }

    /** Poll pump: request the terminal state of one shell per interval. */
    private static void pollShells() {
        List<Integer> ids = new ArrayList<>(SHELLS.keySet());
        Shell shell = SHELLS.get(ids.get(shellPollIndex % ids.size()));
        shellPollIndex++;
        CidePackets.sendToServer(new ConsolePollPayload(shell.pos, shell.computerId, shell.session));
    }

    /** Renders terminal state changes for every matching shell as xterm output. */
    static void resultConsoleState(ConsoleStatePayload p) {
        if (SHELLS.isEmpty() || p.terminal() == null) return;
        for (Shell shell : SHELLS.values()) {
            if (shell.computerId != p.computerId()) continue;
            renderShell(shell, p.terminal());
        }
    }

    private static void renderShell(Shell shell, TerminalState state) {
        try {
            NetworkedTerminal terminal = state.create();
            int height = terminal.getHeight();
            int width = terminal.getWidth();
            if (height <= 0 || width <= 0) return;
            Palette palette = terminal.getPalette();
            String[] lines = new String[height];
            for (int y = 0; y < height; y++) {
                lines[y] = renderLine(terminal, y, width, palette);
            }
            int cx = terminal.getCursorX();
            int cy = terminal.getCursorY();
            if (shell.firstRender || shell.lastLines == null
                    || shell.lastHeight != height || shell.lastWidth != width) {
                shell.lastLines = lines;
                shell.lastHeight = height;
                shell.lastWidth = width;
                shell.lastCursorX = cx;
                shell.lastCursorY = cy;
                shell.firstRender = false;
                sendShellOutput(shell.id, fullTerminal(lines, cx, cy));
                return;
            }
            StringBuilder out = new StringBuilder();
            boolean any = false;
            for (int y = 0; y < height; y++) {
                if (!lines[y].equals(shell.lastLines[y])) {
                    out.append('\u001b').append('[').append(y + 1).append(";1H")
                            .append(lines[y]).append("\u001b[K");
                    any = true;
                }
            }
            if (cx != shell.lastCursorX || cy != shell.lastCursorY) {
                out.append('\u001b').append('[').append(cy + 1).append(';').append(cx + 1).append('H');
                shell.lastCursorX = cx;
                shell.lastCursorY = cy;
                any = true;
            }
            shell.lastLines = lines;
            if (any) sendShellOutput(shell.id, out.toString());
        } catch (Throwable t) {
            BridgeLog.error("ccLink shell render failed", t);
        }
    }

    /** Renders one terminal row with per-cell truecolor SGR sequences (fg + bg),
     *  matching how the in-game console renders via the terminal palette. */
    private static String renderLine(NetworkedTerminal terminal, int y, int width, Palette palette) {
        TextBuffer line = terminal.getLine(y);
        if (line == null) return "";
        TextBuffer fgLine = terminal.getTextColourLine(y);
        TextBuffer bgLine = terminal.getBackgroundColourLine(y);
        StringBuilder sb = new StringBuilder(width * 2);
        int lastFg = -1, lastBg = -1;
        for (int x = 0; x < width; x++) {
            int fg = fgLine != null && x < fgLine.length() ? colourIndex(fgLine.charAt(x)) : 0;
            int bg = bgLine != null && x < bgLine.length() ? colourIndex(bgLine.charAt(x)) : 0;
            if (fg != lastFg) {
                int c = palette.getRenderColours(fg & 0xF);
                sb.append("\u001b[38;2;").append((c >> 16) & 0xFF).append(';')
                        .append((c >> 8) & 0xFF).append(';').append(c & 0xFF).append('m');
                lastFg = fg;
            }
            if (bg != lastBg) {
                int c = palette.getRenderColours(bg & 0xF);
                sb.append("\u001b[48;2;").append((c >> 16) & 0xFF).append(';')
                        .append((c >> 8) & 0xFF).append(';').append(c & 0xFF).append('m');
                lastBg = bg;
            }
            sb.append(x < line.length() ? line.charAt(x) : ' ');
        }
        return sb.toString();
    }

    /** Terminal colour cells hold the colour index as a hex digit char (0-9, a-f). */
    private static int colourIndex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return 0;
    }

    private static String fullTerminal(String[] lines, int cx, int cy) {
        StringBuilder out = new StringBuilder("\u001b[2J\u001b[H");
        for (int y = 0; y < lines.length; y++) {
            out.append(lines[y]);
            if (y < lines.length - 1) out.append('\n');
        }
        out.append('\u001b').append('[').append(cy + 1).append(';').append(cx + 1).append('H');
        return out.toString();
    }

    private static void sendShellOutput(int shellId, String text) {
        JsonObject data = new JsonObject();
        data.addProperty("shellID", shellId);
        data.addProperty("text", text);
        WebSocketBridge.sendEvent("writeTerminal", data);
    }

    // ---------------------------------------------------------- tick pumping

    private static void tick() {
        tickCount++;
        if (head != null && !head.done && now() > head.deadline) {
            BridgeLog.warn("ccLink operation timed out (no server response)");
            head.fail("operation timed out - computer not responding");
            advance();
        }
        if (!WebSocketBridge.connected()) return;
        if (tickCount % STUB_SCAN_TICKS == 0 && targetPos != null && targetComputerId >= 0) {
            requestStubScan();
        }
        if (tickCount % SHELL_POLL_TICKS == 0 && head == null && !SHELLS.isEmpty()) {
            pollShells();
        }
        if (tickCount % WATCH_POLL_TICKS == 0 && head == null && !WATCHED.isEmpty() && targetPos != null) {
            List<String> parents = new ArrayList<>(WATCHED.keySet());
            String parent = parents.get(watchIndex % parents.size());
            watchIndex++;
            Set<String> names = WATCHED.get(parent);
            if (names != null && !names.isEmpty()) {
                long snapTime = SNAP_TIME.getOrDefault(parent, 0L);
                if (snapTime == 0L || now() - snapTime >= WATCH_SNAP_TTL_MS) {
                    startOp(new WatchOp(parent, new LinkedHashSet<>(names)));
                }
            }
        }
    }

    /** Schedules a full listFiles event so the extension's remote tree refreshes. */
    private static void scheduleTreeRefresh() {
        if (now() - lastTreeRefreshMs < TREE_REFRESH_COOLDOWN_MS) return;
        lastTreeRefreshMs = now();
        startOp(new WalkOp(-1, "", WALK_MODE_EVENT));
    }

    // ------------------------------------------------- result routing (queue)

    /**
     * At most one bridge operation is in flight (the server results carry no
     * request id and the connection is ordered), so every result is matched
     * against the queue head by path. Results for the in-game IDE's own
     * operations do not match and fall through to the screen.
     */
    private static void resultOperation(OperationResultPayload p) {
        Op op = head;
        if (op == null || op.done) return;
        // Rejections carry no path; successes must match the operation's path.
        if (p.path().isEmpty() || p.path().equals(op.expectPath)) {
            op.onOperation(p);
            advance();
        }
    }

    private static void resultFileList(FileListPayload p) {
        Op op = head;
        if (op == null || op.done || !p.path().equals(op.expectPath)) return;
        op.onFileList(p);
        advance();
    }

    private static void resultChunk(FileContentChunkPayload p) {
        Op op = head;
        if (op == null || op.done || !p.path().equals(op.expectPath)) return;
        op.onChunk(p);
        advance();
    }

    private static void startOp(Op op) {
        QUEUE.add(op);
        if (head == null) {
            head = QUEUE.poll();
            beginHead();
        }
    }

    private static void beginHead() {
        if (head == null) return;
        try {
            head.begin();
        } catch (Throwable t) {
            BridgeLog.error("ccLink operation start failed", t);
            head.fail("internal bridge error: " + t);
            advance();
        }
    }

    private static void advance() {
        if (head == null || !head.done) return;
        head = null;
        if (!QUEUE.isEmpty()) {
            head = QUEUE.poll();
            beginHead();
        }
    }

    // -------------------------------------------------------------- responses

    private static void respondOk(int serverId, JsonElement data) {
        WebSocketBridge.respond(serverId, true, data);
    }

    private static void respondErr(int serverId, String message) {
        WebSocketBridge.respond(serverId, false, new JsonPrimitive(message == null || message.isEmpty() ? "operation failed" : message));
    }

    private static void sendStatus(int status) {
        JsonObject data = new JsonObject();
        data.addProperty("status", status);
        WebSocketBridge.sendEvent("setStatus", data);
    }

    private static void sendKillTerminal(int shellId) {
        JsonObject data = new JsonObject();
        data.addProperty("shellID", shellId);
        WebSocketBridge.sendEvent("killTerminal", data);
    }

    // ---------------------------------------------------------------- helpers

    private abstract static class Op {
        int serverId = -1; // -1 = internal, no RPC answer
        String expectPath = "";
        long deadline = now() + RPC_TIMEOUT_MS;
        boolean done;

        abstract void begin();

        abstract void onOperation(OperationResultPayload p);

        void onFileList(FileListPayload p) {}

        void onChunk(FileContentChunkPayload p) {}

        /** Marks the operation finished; the RPC flavor also answers it. */
        void fail(String message) {
            done = true;
        }
    }

    private abstract static class RpcOp extends Op {
        RpcOp(int serverId, String expectPath) {
            this.serverId = serverId;
            this.expectPath = expectPath;
        }

        void ok() {
            if (done || serverId < 0) return;
            done = true;
            WebSocketBridge.respond(serverId, true, null);
        }

        void ok(JsonElement data) {
            if (done || serverId < 0) return;
            done = true;
            WebSocketBridge.respond(serverId, true, data);
        }

        @Override void fail(String message) {
            if (done || serverId < 0) return;
            done = true;
            respondErr(serverId, message);
        }
    }

    /** Normalizes extension paths ("/main.lua" -> "main.lua"). */
    private static String normalize(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        if (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String str(JsonObject p, String key) {
        if (p != null && p.has(key) && p.get(key).isJsonPrimitive()) {
            try {
                return p.get(key).getAsString();
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String joinChunks(Map<Integer, byte[]> chunks, int total) {
        byte[] blob = new byte[0];
        int size = 0;
        for (int i = 0; i < total; i++) {
            byte[] c = chunks.getOrDefault(i, new byte[0]);
            size += c.length;
        }
        blob = new byte[size];
        int offset = 0;
        for (int i = 0; i < total; i++) {
            byte[] c = chunks.getOrDefault(i, new byte[0]);
            System.arraycopy(c, 0, blob, offset, c.length);
            offset += c.length;
        }
        return new String(blob, StandardCharsets.UTF_8);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
