package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record MoveFilePayload(BlockPos pos, int computerId, String sourcePath, String destinationDirectory) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "move_file");

    public static void encode(MoveFilePayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.sourcePath());
        buf.writeUtf(p.destinationDirectory());
    }

    public static MoveFilePayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        String sourcePath = buf.readUtf();
        String destinationDirectory = buf.readUtf();
        return new MoveFilePayload(pos, computerId, sourcePath, destinationDirectory);
    }
}
