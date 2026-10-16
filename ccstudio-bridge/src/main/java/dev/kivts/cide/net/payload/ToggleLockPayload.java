package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record ToggleLockPayload(BlockPos pos, int computerId) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "toggle_lock");

    public static void encode(ToggleLockPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
    }

    public static ToggleLockPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        return new ToggleLockPayload(pos, computerId);
    }
}
