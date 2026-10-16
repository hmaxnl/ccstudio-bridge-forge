package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record LockStatePayload(int computerId, boolean lockedToPlayer) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "lock_state");

    public static void encode(LockStatePayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.computerId());
        buf.writeBoolean(p.lockedToPlayer());
    }

    public static LockStatePayload decode(FriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        boolean lockedToPlayer = buf.readBoolean();
        return new LockStatePayload(computerId, lockedToPlayer);
    }
}
