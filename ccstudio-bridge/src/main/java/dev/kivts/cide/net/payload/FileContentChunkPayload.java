package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record FileContentChunkPayload(int computerId, String path, int chunkIndex, int totalChunks, byte[] data, boolean readOnly) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "file_content_chunk");

    public static void encode(FileContentChunkPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.path());
        buf.writeVarInt(p.chunkIndex());
        buf.writeVarInt(p.totalChunks());
        buf.writeByteArray(p.data());
        buf.writeBoolean(p.readOnly());
    }

    public static FileContentChunkPayload decode(FriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        String path = buf.readUtf();
        int chunkIndex = buf.readVarInt();
        int totalChunks = buf.readVarInt();
        byte[] data = buf.readByteArray();
        boolean readOnly = buf.readBoolean();
        return new FileContentChunkPayload(computerId, path, chunkIndex, totalChunks, data, readOnly);
    }
}
