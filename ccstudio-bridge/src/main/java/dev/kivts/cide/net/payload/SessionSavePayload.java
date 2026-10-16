package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record SessionSavePayload(BlockPos pos, int computerId, String json) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "session_save");

    public static void encode(SessionSavePayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.json());
    }

    public static SessionSavePayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        String json = buf.readUtf();
        return new SessionSavePayload(pos, computerId, json);
    }
}
