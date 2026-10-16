package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** One chunk of the datapack-contributed wiki/autocomplete blob, server to client. */
public record WikiSyncChunkPayload(int chunkIndex, int totalChunks, byte[] data) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "wiki_sync_chunk");

    public static void encode(WikiSyncChunkPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.chunkIndex());
        buf.writeVarInt(p.totalChunks());
        buf.writeByteArray(p.data());
    }

    public static WikiSyncChunkPayload decode(FriendlyByteBuf buf) {
        int chunkIndex = buf.readVarInt();
        int totalChunks = buf.readVarInt();
        byte[] data = buf.readByteArray();
        return new WikiSyncChunkPayload(chunkIndex, totalChunks, data);
    }
}
