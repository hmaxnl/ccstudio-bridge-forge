package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import java.util.UUID;

public record ConsolePollPayload(BlockPos pos, int computerId, UUID sessionId) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "console_poll");

    public static void encode(ConsolePollPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUUID(p.sessionId());
    }

    public static ConsolePollPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        UUID sessionId = buf.readUUID();
        return new ConsolePollPayload(pos, computerId, sessionId);
    }
}
