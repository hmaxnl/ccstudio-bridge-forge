package dev.kivts.cide.net.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record OpenIdePayload(BlockPos pos, int computerId, String label, boolean writesEnabled,
                              boolean lockedToPlayer, UUID sessionId, boolean adminView, String computerType) {
    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "open_ide");

    public static void encode(OpenIdePayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeVarInt(p.computerId);
        buf.writeUtf(p.label, 256);
        buf.writeBoolean(p.writesEnabled);
        buf.writeBoolean(p.lockedToPlayer);
        buf.writeUUID(p.sessionId);
        buf.writeBoolean(p.adminView);
        buf.writeUtf(p.computerType, 64);
    }

    public static OpenIdePayload decode(FriendlyByteBuf buf) {
        return new OpenIdePayload(
            buf.readBlockPos(),
            buf.readVarInt(),
            buf.readUtf(256),
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readUUID(),
            buf.readBoolean(),
            buf.readUtf(64)
        );
    }
}
