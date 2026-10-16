package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import java.util.UUID;

public record RunProgramPayload(BlockPos pos, int computerId, UUID sessionId, String path) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "run_program");

    public static void encode(RunProgramPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUUID(p.sessionId());
        buf.writeUtf(p.path());
    }

    public static RunProgramPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        UUID sessionId = buf.readUUID();
        String path = buf.readUtf();
        return new RunProgramPayload(pos, computerId, sessionId, path);
    }
}
