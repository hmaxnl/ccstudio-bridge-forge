package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import java.util.UUID;

public record ConsoleActionPayload(BlockPos pos, int computerId, UUID sessionId, int action) {

    public static final int TERMINATE = 0;
    public static final int TURN_ON = 1;
    public static final int SHUTDOWN = 2;
    public static final int REBOOT = 3;
    public static final int CLOSE = 4;
    public static final int RELEASE_INPUTS = 5;

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "console_action");

    public static void encode(ConsoleActionPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUUID(p.sessionId());
        buf.writeVarInt(p.action());
    }

    public static ConsoleActionPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        UUID sessionId = buf.readUUID();
        int action = buf.readVarInt();
        return new ConsoleActionPayload(pos, computerId, sessionId, action);
    }
}
