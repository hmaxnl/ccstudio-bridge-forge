package dev.kivts.cide.net;

import dev.kivts.cide.client.CideClient;
import dev.kivts.cide.net.payload.*;
import dev.kivts.cide.server.CideComputerLock;
import dev.kivts.cide.server.CideConsoleService;
import dev.kivts.cide.server.CideFileService;
import dev.kivts.cide.server.CideSessionService;
import dev.kivts.cide.server.CideStubService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Packet channel for the CIDE bridge (backported to the Forge 1.20.1 SimpleChannel API).
 * All 31 message types are registered with an incrementing discriminator; encoders/decoders
 * live on the message classes themselves.
 */
public final class CidePackets {
    private CidePackets() {}

    private static final String PROTOCOL_VERSION = "14"; // v14: OpenIdePayload gains computerType
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation("ccstudio", "cide");

    // acceptMissingOr(...) makes the channel optional on both sides: a client that has this
    // mod can join a server without it (and vice versa) without the handshake rejecting the
    // connection with "the server is missing the following mods". When both sides have the
    // mod, the protocol version must still match exactly.
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(CHANNEL_ID)
        .networkProtocolVersion(() -> PROTOCOL_VERSION)
        .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION))
        .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION))
        .simpleChannel();

    private static int nextId = 0;

    /** Register all message types. Called once from the mod constructor. */
    public static void register() {
        // ---- server-bound (client -> server) ----
        CHANNEL.registerMessage(nextId++, ListFilesPayload.class, ListFilesPayload::encode, ListFilesPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.list(player(ctx), p.pos(), p.computerId(), p.path())));
        CHANNEL.registerMessage(nextId++, ReadFilePayload.class, ReadFilePayload::encode, ReadFilePayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.read(player(ctx), p.pos(), p.computerId(), p.path())));
        CHANNEL.registerMessage(nextId++, WriteFilePayload.class, WriteFilePayload::encode, WriteFilePayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.write(player(ctx), p.pos(), p.computerId(), p.path(), p.content())));
        CHANNEL.registerMessage(nextId++, WriteChunkPayload.class, WriteChunkPayload::encode, WriteChunkPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.writeChunk(player(ctx), p.pos(), p.computerId(), p.path(), p.chunkIndex(), p.totalChunks(), p.data())));
        CHANNEL.registerMessage(nextId++, CreateFilePayload.class, CreateFilePayload::encode, CreateFilePayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.create(player(ctx), p.pos(), p.computerId(), p.directory())));
        CHANNEL.registerMessage(nextId++, CreateFolderPayload.class, CreateFolderPayload::encode, CreateFolderPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.createFolder(player(ctx), p.pos(), p.computerId(), p.path())));
        CHANNEL.registerMessage(nextId++, DeleteFilePayload.class, DeleteFilePayload::encode, DeleteFilePayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.delete(player(ctx), p.pos(), p.computerId(), p.path())));
        CHANNEL.registerMessage(nextId++, CopyFilePayload.class, CopyFilePayload::encode, CopyFilePayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.copy(player(ctx), p.pos(), p.computerId(), p.sourcePath(), p.destinationDirectory())));
        CHANNEL.registerMessage(nextId++, MoveFilePayload.class, MoveFilePayload::encode, MoveFilePayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.move(player(ctx), p.pos(), p.computerId(), p.sourcePath(), p.destinationDirectory())));
        CHANNEL.registerMessage(nextId++, RenameFilePayload.class, RenameFilePayload::encode, RenameFilePayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.rename(player(ctx), p.pos(), p.computerId(), p.sourcePath(), p.newName())));
        CHANNEL.registerMessage(nextId++, PeripheralQueryPayload.class, PeripheralQueryPayload::encode, PeripheralQueryPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideFileService.queryPeripherals(player(ctx), p.pos(), p.computerId())));
        CHANNEL.registerMessage(nextId++, PeripheralScanPayload.class, PeripheralScanPayload::encode, PeripheralScanPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideStubService.updateStubs(player(ctx), p.pos(), p.computerId())));
        CHANNEL.registerMessage(nextId++, ToggleLockPayload.class, ToggleLockPayload::encode, ToggleLockPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideComputerLock.toggle(player(ctx), p.pos(), p.computerId())));
        CHANNEL.registerMessage(nextId++, SessionSavePayload.class, SessionSavePayload::encode, SessionSavePayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideSessionService.save(player(ctx), p.pos(), p.computerId(), p.json())));
        CHANNEL.registerMessage(nextId++, SessionSaveChunkPayload.class, SessionSaveChunkPayload::encode, SessionSaveChunkPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideSessionService.saveChunk(player(ctx), p.pos(), p.computerId(), p.chunkIndex(), p.totalChunks(), p.data())));
        CHANNEL.registerMessage(nextId++, ConsolePollPayload.class, ConsolePollPayload::encode, ConsolePollPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideConsoleService.poll(player(ctx), p.pos(), p.computerId(), p.sessionId())));
        CHANNEL.registerMessage(nextId++, ConsoleActionPayload.class, ConsoleActionPayload::encode, ConsoleActionPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideConsoleService.action(player(ctx), p.pos(), p.computerId(), p.sessionId(), p.action())));
        CHANNEL.registerMessage(nextId++, ConsoleInputPayload.class, ConsoleInputPayload::encode, ConsoleInputPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideConsoleService.input(player(ctx), p)));
        CHANNEL.registerMessage(nextId++, RunProgramPayload.class, RunProgramPayload::encode, RunProgramPayload::decode,
            (p, ctx) -> serverWork(ctx, () -> CideConsoleService.runProgram(player(ctx), p.pos(), p.computerId(), p.sessionId(), p.path())));

        // ---- client-bound (server -> client) ----
        CHANNEL.registerMessage(nextId++, OpenIdePayload.class, OpenIdePayload::encode, OpenIdePayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.open(p)));
        CHANNEL.registerMessage(nextId++, FileListPayload.class, FileListPayload::encode, FileListPayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleFileList(p)));
        CHANNEL.registerMessage(nextId++, FileContentPayload.class, FileContentPayload::encode, FileContentPayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleFileContent(p)));
        CHANNEL.registerMessage(nextId++, FileContentChunkPayload.class, FileContentChunkPayload::encode, FileContentChunkPayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleFileContentChunk(p)));
        CHANNEL.registerMessage(nextId++, OperationResultPayload.class, OperationResultPayload::encode, OperationResultPayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleOperation(p)));
        CHANNEL.registerMessage(nextId++, PeripheralMapPayload.class, PeripheralMapPayload::encode, PeripheralMapPayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handlePeripheralMap(p)));
        CHANNEL.registerMessage(nextId++, LockStatePayload.class, LockStatePayload::encode, LockStatePayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleLockState(p)));
        CHANNEL.registerMessage(nextId++, SessionLoadPayload.class, SessionLoadPayload::encode, SessionLoadPayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleSessionLoad(p)));
        CHANNEL.registerMessage(nextId++, SessionLoadChunkPayload.class, SessionLoadChunkPayload::encode, SessionLoadChunkPayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleSessionLoadChunk(p)));
        CHANNEL.registerMessage(nextId++, ConsoleStatePayload.class, ConsoleStatePayload::encode, ConsoleStatePayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleConsoleState(p)));
        CHANNEL.registerMessage(nextId++, WikiSyncChunkPayload.class, WikiSyncChunkPayload::encode, WikiSyncChunkPayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleWikiSyncChunk(p)));
        CHANNEL.registerMessage(nextId++, LuaManifestPayload.class, LuaManifestPayload::encode, LuaManifestPayload::decode,
            (p, ctx) -> clientWork(ctx, () -> CideClient.handleLuaManifest(p)));
    }

    // ---- helpers ----

    private static ServerPlayer player(Supplier<NetworkEvent.Context> ctx) {
        return (ServerPlayer) ctx.get().getSender();
    }

    private static void serverWork(Supplier<NetworkEvent.Context> ctx, Runnable work) {
        ctx.get().enqueueWork(work);
        ctx.get().setPacketHandled(true);
    }

    private static void clientWork(Supplier<NetworkEvent.Context> ctx, Runnable work) {
        ctx.get().enqueueWork(work);
        ctx.get().setPacketHandled(true);
    }

    /**
     * Whether the given player's client actually has this mod: true only when the client
     * announced our channel during the login handshake. Use this before any server->client
     * interaction so players on vanilla (or mod-less) clients are left on the stock behaviour.
     */
    public static boolean hasChannel(ServerPlayer player) {
        ConnectionData data = NetworkHooks.getConnectionData(player.connection.connection);
        return data != null && data.getChannels().containsKey(CHANNEL_ID);
    }

    public static void sendToPlayer(ServerPlayer player, Object message) {
        if (!hasChannel(player)) return; // client without the mod would only drop the packet anyway
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendDenied(ServerPlayer player, String reason) {
        sendToPlayer(player, new OperationResultPayload(false, reason, ""));
    }
}
