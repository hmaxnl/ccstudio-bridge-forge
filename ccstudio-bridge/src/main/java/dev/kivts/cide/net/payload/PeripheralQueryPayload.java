package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record PeripheralQueryPayload(BlockPos pos, int computerId) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "peripheral_query");

    public static void encode(PeripheralQueryPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
    }

    public static PeripheralQueryPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        return new PeripheralQueryPayload(pos, computerId);
    }
}
