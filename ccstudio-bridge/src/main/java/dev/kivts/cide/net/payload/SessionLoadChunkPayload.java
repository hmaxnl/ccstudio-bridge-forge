package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SessionLoadChunkPayload(int computerId, int chunkIndex, int totalChunks, byte[] data) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "session_load_chunk");

    public static void encode(SessionLoadChunkPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.computerId());
        buf.writeVarInt(p.chunkIndex());
        buf.writeVarInt(p.totalChunks());
        buf.writeByteArray(p.data());
    }

    public static SessionLoadChunkPayload decode(FriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        int chunkIndex = buf.readVarInt();
        int totalChunks = buf.readVarInt();
        byte[] data = buf.readByteArray();
        return new SessionLoadChunkPayload(computerId, chunkIndex, totalChunks, data);
    }
}
