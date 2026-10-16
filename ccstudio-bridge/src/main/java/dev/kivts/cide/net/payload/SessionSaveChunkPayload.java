package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record SessionSaveChunkPayload(BlockPos pos, int computerId, int chunkIndex, int totalChunks, byte[] data) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "session_save_chunk");

    public static void encode(SessionSaveChunkPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeVarInt(p.chunkIndex());
        buf.writeVarInt(p.totalChunks());
        buf.writeByteArray(p.data());
    }

    public static SessionSaveChunkPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        int chunkIndex = buf.readVarInt();
        int totalChunks = buf.readVarInt();
        byte[] data = buf.readByteArray();
        return new SessionSaveChunkPayload(pos, computerId, chunkIndex, totalChunks, data);
    }
}
