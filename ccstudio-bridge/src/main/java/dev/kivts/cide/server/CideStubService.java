package dev.kivts.cide.server;

import dan200.computercraft.api.filesystem.MountConstants;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.core.methods.MethodSupplier;
import dan200.computercraft.core.methods.PeripheralMethod;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.core.ServerContext;
import dan200.computercraft.shared.peripheral.modem.wired.WiredModemElement;
import dan200.computercraft.shared.peripheral.modem.wired.WiredModemPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Peripheral scraping &amp; stub generation (spec phase 5). Scans the computer's
 * attached peripherals - the six local sides plus anything reachable over the
 * wired modem network - and rewrites {@value #STUB_PATH} on the computer's
 * drive so VS Code / LuaLS autocomplete tracks what is actually attached.
 *
 * <p>Method lists come from the same {@link MethodSupplier} CC:T's own
 * peripheral wrapper uses, so the stubs match {@code peripheral.getMethods()}
 * exactly. The file is only rewritten when the content changes, and the
 * operation is best-effort: it must never surface an error to the user.
 */
public final class CideStubService {
    private CideStubService() {}

    public static final String STUB_DIR = ".vscode";
    public static final String STUB_PATH = STUB_DIR + "/peripheral_stubs.lua";

    // Anchors the LuaLS workspace at the drive root (so the stub metafile is
    // found no matter which file is opened) and marks the stubs as a library.
    // Only written when missing - a user-provided config is never clobbered.
    public static final String LUARC_PATH = ".luarc.json";
    private static final String LUARC_CONTENT = "{\"workspace.library\":[\"" + STUB_DIR + "\"]}\n";

    /**
     * True for paths CIDE itself manages (the LuaLS config and the stub
     * directory). These are exempt from the denied/allowed path-prefix config:
     * the default denied prefix "." would otherwise make them unopenable in
     * the CIDE UI and unreadable through the VS Code filesystem provider.
     */
    public static boolean isManagedPath(String path) {
        return path.equals(LUARC_PATH) || path.startsWith(STUB_DIR + "/");
    }

    /**
     * Scans the computer and (re)generates the stub file. Silent no-op when the
     * target is gone or nothing changed.
     */
    public static void updateStubs(ServerPlayer player, BlockPos pos, int computerId) {
        try {
            String denied = CideAccess.validateLiveTarget(player, pos, computerId);
            if (!denied.isEmpty()) return;

            Map<String, IPeripheral> attached = collectAttached(player, pos);
            String content = generate(attached, methodsByType(player, attached));
            writeIfChanged(player, pos, computerId, content);
        } catch (Throwable ignored) {
            // Best-effort by design: a stub refresh must never break the session.
        }
    }

    /**
     * Collects every peripheral visible to the computer: local sides plus the
     * remote peripherals of each attached wired modem. Keys are the names
     * {@code peripheral.getNames()} reports, in a stable order.
     */
    public static Map<String, IPeripheral> collectAttached(ServerPlayer player, BlockPos pos) {
        Map<String, IPeripheral> attached = new LinkedHashMap<>();
        if (pos.equals(BlockPos.ZERO)) return attached; // pocket computers have no sides
        BlockEntity be = player.level().getBlockEntity(pos);
        if (!(be instanceof AbstractComputerBlockEntity computer)) return attached;
        ServerComputer sc = computer.createServerComputer();
        for (ComputerSide side : ComputerSide.values()) {
            IPeripheral p = sc.getPeripheral(side);
            if (p != null) {
                attached.put(side.getName(), p);
                collectWiredNetwork(p, attached);
            }
        }
        return attached;
    }

    // Walk a wired modem's network and add its remote peripherals (by address).
    private static void collectWiredNetwork(IPeripheral attachedPeripheral, Map<String, IPeripheral> attached) {
        if (!(attachedPeripheral instanceof WiredModemPeripheral modem)) return;
        try {
            var element = modem.getNode().getElement();
            if (!(element instanceof WiredModemElement modemElement)) return;
            Map<String, IPeripheral> remote = modemElement.getRemotePeripherals();
            synchronized (remote) {
                for (Map.Entry<String, IPeripheral> entry : remote.entrySet()) {
                    String name = entry.getKey();
                    IPeripheral peripheral = entry.getValue();
                    if (name == null || peripheral == null) continue;
                    attached.putIfAbsent(name, peripheral);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Lua-visible method names per peripheral type, via CC:T's own method resolver. */
    private static Map<String, Set<String>> methodsByType(ServerPlayer player, Map<String, IPeripheral> attached) {
        MethodSupplier<PeripheralMethod> supplier = ServerContext.get(player.server).peripheralMethods();
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        for (IPeripheral p : attached.values()) {
            Set<String> methods = byType.computeIfAbsent(p.getType(), k -> new LinkedHashSet<>());
            try {
                methods.addAll(supplier.getSelfMethods(p).keySet());
            } catch (Throwable ignored) {}
        }
        return byType;
    }

    /**
     * Renders the LuaLS annotation file. One {@code ---@class} per attached
     * peripheral type; each method becomes a {@code ---@field <name> function}
     * so completion offers every method of every attached peripheral.
     */
    static String generate(Map<String, IPeripheral> attached, Map<String, Set<String>> methodsByType) {
        StringBuilder sb = new StringBuilder();
        sb.append("---@meta\n");
        sb.append("-- AUTO-GENERATED BY CC-STUDIO BRIDGE - DO NOT EDIT\n");
        sb.append("-- Peripheral stubs for VS Code / LuaLS autocomplete.\n");
        sb.append("-- For method completion on one peripheral, annotate a local\n");
        sb.append("-- with its class name (see below), e.g.:\n");
        sb.append("--   ---@type <ClassName>\n");
        sb.append("--   local p = peripheral.find(\"<type>\")\n");
        if (attached.isEmpty()) {
            sb.append("-- No peripherals attached.\n");
            return sb.toString();
        }
        StringBuilder list = new StringBuilder();
        for (Map.Entry<String, IPeripheral> e : attached.entrySet()) {
            if (list.length() > 0) list.append(", ");
            list.append('\"').append(escape(e.getKey())).append("\" -> ").append(escape(e.getValue().getType()));
        }
        sb.append("-- Attached Peripherals: [").append(list).append("]\n\n");
        Set<String> usedLocals = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : methodsByType.entrySet()) {
            String type = e.getKey();
            sb.append("---@class ").append(className(type)).append('\n');
            List<String> methods = new ArrayList<>(e.getValue());
            Collections.sort(methods);
            for (String m : methods) sb.append("---@field ").append(field(m)).append(" function\n");
            sb.append("local ").append(uniqueLocal(type, usedLocals)).append(" = {}\n\n");
        }
        return sb.toString();
    }

    // Reads the current stub file (if any) and rewrites it only when changed.
    // Also makes sure the LuaLS workspace anchor (.luarc.json) is present.
    private static void writeIfChanged(ServerPlayer player, BlockPos pos, int computerId, String content)
            throws IOException {
        WritableMount mount = CideFileService.mount(player, pos, computerId);
        ensureLuarc(mount);

        String existing = null;
        if (mount.exists(STUB_PATH) && !mount.isDirectory(STUB_PATH)) {
            try {
                int size = (int) Math.min(mount.getSize(STUB_PATH), Integer.MAX_VALUE);
                ByteBuffer buffer = ByteBuffer.allocate(size);
                try (var in = mount.openForRead(STUB_PATH)) {
                    while (buffer.hasRemaining() && in.read(buffer) >= 0) {}
                }
                existing = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
        if (existing != null && existing.equals(content)) return;
        if (!mount.exists(".vscode")) mount.makeDirectory(".vscode");
        try (var out = mount.openFile(STUB_PATH, MountConstants.WRITE_OPTIONS)) {
            out.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
        }
    }

    // Writes the .luarc.json anchor exactly once; a user-edited file is left alone.
    private static void ensureLuarc(WritableMount mount) throws IOException {
        if (mount.exists(LUARC_PATH)) return;
        try (var out = mount.openFile(LUARC_PATH, MountConstants.WRITE_OPTIONS)) {
            out.write(ByteBuffer.wrap(LUARC_CONTENT.getBytes(StandardCharsets.UTF_8)));
        }
    }

    // "ae2:me_interface" -> "Ae2MeInterface", "monitor" -> "Monitor"
    private static String className(String type) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : type.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                up = false;
            } else {
                up = true;
            }
        }
        return sb.length() == 0 ? "Peripheral" : sb.toString();
    }

    // Mod-id prefix becomes the local name ("ae2:me_interface" -> "ae2");
    // otherwise the lower-cased class name ("monitor" -> "monitor").
    private static String uniqueLocal(String type, Set<String> usedLocals) {
        String base;
        int colon = type.indexOf(':');
        if (colon > 0) {
            StringBuilder sb = new StringBuilder();
            for (char c : type.substring(0, colon).toCharArray()) {
                if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
            }
            base = sb.length() > 0 ? sb.toString() : type.substring(0, colon).toLowerCase(Locale.ROOT);
        } else {
            String cls = className(type);
            base = Character.toLowerCase(cls.charAt(0)) + cls.substring(1);
        }
        String local = base;
        int n = 2;
        while (!usedLocals.add(local)) local = base + n++;
        return local;
    }

    private static String field(String name) {
        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) return name;
        return "\"" + escape(name) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
