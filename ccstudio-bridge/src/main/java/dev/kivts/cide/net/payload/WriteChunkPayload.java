package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record WriteChunkPayload(BlockPos pos, int computerId, String path, int chunkIndex, int totalChunks, byte[] data) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "write_chunk");

    public static void encode(WriteChunkPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.path());
        buf.writeVarInt(p.chunkIndex());
        buf.writeVarInt(p.totalChunks());
        buf.writeByteArray(p.data());
    }

    public static WriteChunkPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        String path = buf.readUtf();
        int chunkIndex = buf.readVarInt();
        int totalChunks = buf.readVarInt();
        byte[] data = buf.readByteArray();
        return new WriteChunkPayload(pos, computerId, path, chunkIndex, totalChunks, data);
    }
}
